import BackgroundTasks
import UIKit

/// Keeps an offline download running while SatMap is in the background.
///
/// From iOS 26 a download started with SatMap on screen becomes a continued processing task: iOS keeps it running in
/// the background and shows its progress on the Lock Screen and in the Dynamic Island, with a button to stop it.
/// Earlier iOS versions give an app that was just left about half a minute. When the time ends (or the download is
/// stopped from the Lock Screen), the download pauses and continues when SatMap is opened again.
/// Used on the main thread only.
final class BackgroundDownload {
    /// The background time ended: the download has to stop.
    var onExpire: (() -> Void)?
    /// What happens to downloads in the background, in a few words for the Offline maps screen (nil until the first
    /// download). Also says why iOS didn't allow more, as there is no other way to see that on the phone.
    private(set) var status: String? {
        didSet { if status != oldValue { onStatusChange?() } }
    }
    var onStatusChange: (() -> Void)?

    /// The continued processing task (iOS 26 and later) once iOS has started it, and its identifier until then.
    private var task: AnyObject?
    private var pending: String?
    /// The extra time iOS gives an app that was just left (all iOS versions).
    private var backgroundTime: UIBackgroundTaskIdentifier = .invalid

    private var title = ""
    private var subtitle = ""
    /// Progress for the task's bar, over every area it has downloaded so far: the areas before the current one count
    /// as done.
    private var areaID: UUID?
    private var carried: Int64 = 0
    private var done: Int64 = 0
    private var total: Int64 = 0
    private var shownDone: Int64 = 0
    private var shownTitle = ""
    private var shownSubtitle = ""
    private var titleShownAt = Date.distantPast

    /// Whether a download may run on with SatMap in the background.
    var allowsBackgroundWork: Bool { task != nil || backgroundTime != .invalid }

    /// A download is running with SatMap on screen: ask iOS to carry it on in the background with a progress bar.
    /// Only possible while SatMap is on screen, so it is asked when a download starts and when SatMap is opened.
    func begin(title: String, subtitle: String) {
        self.title = title
        self.subtitle = subtitle
        guard task == nil, pending == nil, UIApplication.shared.applicationState == .active else { return }
        #if compiler(>=6.2) // the iOS 26 SDK (Xcode 26)
        if #available(iOS 26.0, *) {
            submit()
            return
        }
        #endif
        status = "In the background a download goes on for about half a minute, then continues when you open SatMap. With iOS 26 or later it keeps going, with its progress on the Lock Screen."
    }

    /// The download's progress (tiles done and planned in the area being downloaded), for the system's progress bar.
    func update(area: UUID, title: String, subtitle: String, done: Int, total: Int) {
        if area != areaID {
            if areaID != nil { carried = max(self.done, self.total) } // the area before was finished
            areaID = area
        }
        self.title = title
        self.subtitle = subtitle
        self.done = carried + Int64(max(0, done))
        self.total = carried + Int64(max(0, total))
        push()
    }

    /// SatMap went to the background with a download running. Without a continued processing task, the download uses
    /// the time iOS gives a just-left app; with one, iOS keeps it going.
    func enteredBackground() {
        guard task == nil, backgroundTime == .invalid else { return }
        backgroundTime = UIApplication.shared.beginBackgroundTask(withName: "Offline download") { [weak self] in
            // Called on the main thread when the time is nearly up.
            guard let self = self else { return }
            if self.task == nil { self.onExpire?() }
            self.endBackgroundTime()
        }
    }

    /// SatMap is on screen again.
    func enteredForeground() {
        endBackgroundTime()
    }

    /// No download is running any more (all done, waiting for a network, paused or stopped). `success` is shown by
    /// the system's progress bar as it closes.
    func end(success: Bool) {
        pending = nil
        if let t = task {
            task = nil
            #if compiler(>=6.2)
            if #available(iOS 26.0, *), let t = t as? BGContinuedProcessingTask {
                t.expirationHandler = nil
                t.setTaskCompleted(success: success)
            }
            #endif
        }
        areaID = nil
        carried = 0
        done = 0
        total = 0
        endBackgroundTime()
    }

    private func endBackgroundTime() {
        guard backgroundTime != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTime)
        backgroundTime = .invalid
    }

    private func push() {
        #if compiler(>=6.2)
        guard #available(iOS 26.0, *), let t = task as? BGContinuedProcessingTask else { return }
        // Whole tiles, never going back: the system watches the completed count to see that the work is moving.
        let total = max(self.total, 1)
        if t.progress.totalUnitCount != total { t.progress.totalUnitCount = total }
        shownDone = max(shownDone, min(done, total))
        t.progress.completedUnitCount = shownDone
        if (title != shownTitle || subtitle != shownSubtitle) && Date().timeIntervalSince(titleShownAt) >= 1 {
            t.updateTitle(title, subtitle: subtitle)
            shownTitle = title
            shownSubtitle = subtitle
            titleShownAt = Date()
        }
        #endif
    }

    #if compiler(>=6.2)
    /// How the task's identifier is made. It has to start with the bundle identifier the app is installed with and
    /// match an entry "<prefix>.*" in BGTaskSchedulerPermittedIdentifiers. Sideloading with Impactor adds the team ID
    /// to the bundle identifier (com.jacobusoettle.satmap.ABCDE12345) but not to those entries, and iOS doesn't say
    /// whether "*" then covers more than one part, so two ways are tried; one that iOS never starts isn't used again.
    private enum Style: String, CaseIterable {
        /// "<bundle id>.download.<new UUID>": a new identifier for each download.
        case unique
        /// "<bundle id>" itself: one part after "com.jacobusoettle.satmap.", registered once.
        case bundle
    }

    /// Ways iOS didn't start (remembered, tried last) and ways not tried again until SatMap is restarted.
    private static let failedStylesKey = "backgroundTaskFailedStyles"
    private var timedOut = Set<String>()
    private var registeredBundleID = false

    @available(iOS 26.0, *)
    private func submit() {
        guard let bundleID = Bundle.main.bundleIdentifier else { return }
        let prefixes = (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? [])
            .filter { $0.hasSuffix(".*") }
            .map { String($0.dropLast(2)) }
        func permitted(_ id: String) -> Bool { prefixes.contains { id.hasPrefix($0 + ".") } }

        let failed = Set(UserDefaults.standard.stringArray(forKey: Self.failedStylesKey) ?? [])
        let possible = Style.allCases.filter { style in
            switch style {
            case .unique: return permitted("\(bundleID).download.x")
            case .bundle: return permitted(bundleID)
            }
        }
        guard !possible.isEmpty else {
            status = "In the background a download goes on for about half a minute: this copy of SatMap (\(bundleID)) isn't set up for more."
            return
        }
        let styles = possible
            .filter { !timedOut.contains($0.rawValue) }
            .sorted { !failed.contains($0.rawValue) && failed.contains($1.rawValue) }
        guard !styles.isEmpty else { return } // none started this time; the status says so

        var problems: [String] = []
        for style in styles {
            let id: String
            switch style {
            case .unique:
                // New each time: registering an identifier twice ends the app.
                id = "\(bundleID).download.\(UUID().uuidString)"
                guard BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main, launchHandler: { [weak self] t in
                    if let self = self { self.started(t, id: id) } else { t.setTaskCompleted(success: true) }
                }) else {
                    problems.append("\(style.rawValue): not registered")
                    continue
                }
            case .bundle:
                id = bundleID
                if !registeredBundleID {
                    guard BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main, launchHandler: { [weak self] t in
                        if let self = self { self.started(t, id: id) } else { t.setTaskCompleted(success: true) }
                    }) else {
                        problems.append("\(style.rawValue): not registered")
                        continue
                    }
                    registeredBundleID = true
                }
            }
            let request = BGContinuedProcessingTaskRequest(identifier: id, title: title, subtitle: subtitle)
            request.strategy = .fail
            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                problems.append("\(style.rawValue): \((error as NSError).code)")
                continue
            }
            pending = id
            // iOS starts the task within a second or not at all (e.g. it doesn't accept the identifier).
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self = self, self.pending == id else { return }
                self.pending = nil
                self.timedOut.insert(style.rawValue)
                UserDefaults.standard.set(Array(failed.union([style.rawValue])), forKey: Self.failedStylesKey)
                self.status = "In the background a download goes on for about half a minute: iOS didn't start it as a background task (\(style.rawValue), \(bundleID))."
                self.begin(title: self.title, subtitle: self.subtitle) // the other way, if there is one left
            }
            return
        }
        status = "In the background a download goes on for about half a minute: iOS didn't accept it as a background task (\(problems.joined(separator: ", ")), \(bundleID))."
    }

    @available(iOS 26.0, *)
    private func started(_ t: BGTask, id: String) {
        guard let t = t as? BGContinuedProcessingTask, pending == id else {
            t.setTaskCompleted(success: true) // the download ended before iOS started the task
            return
        }
        pending = nil
        task = t
        if let style = Style.allCases.first(where: { ($0 == .bundle) == (id == Bundle.main.bundleIdentifier) }) {
            // This way works: try it first from now on.
            let failed = Set(UserDefaults.standard.stringArray(forKey: Self.failedStylesKey) ?? []).subtracting([style.rawValue])
            UserDefaults.standard.set(Array(failed), forKey: Self.failedStylesKey)
        }
        status = "Downloads keep going in the background, with their progress on the Lock Screen and in the Dynamic Island."
        t.expirationHandler = { [weak self, weak t] in
            // iOS ends the task, or it was stopped from the Lock Screen (iOS doesn't say which).
            DispatchQueue.main.async {
                guard let self = self, let t = t, self.task === t else { return }
                self.task = nil
                t.setTaskCompleted(success: false)
                if self.backgroundTime == .invalid && UIApplication.shared.applicationState == .background {
                    self.onExpire?()
                }
            }
        }
        shownDone = 0
        shownTitle = ""
        shownSubtitle = ""
        titleShownAt = .distantPast
        push()
    }
    #endif
}
