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

    /// The continued processing task (iOS 26 and later) once iOS has started it, and its identifier until then.
    private var task: AnyObject?
    private var pending: String?
    /// The extra time iOS gives an app that was just left (all iOS versions).
    private var backgroundTime: UIBackgroundTaskIdentifier = .invalid

    private var title = ""
    private var subtitle = ""
    private var done: Int64 = 0
    private var total: Int64 = 0
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
        if #available(iOS 26.0, *) { submit() }
        #endif
    }

    /// The download's progress, for the system's progress bar.
    func update(title: String, subtitle: String, done: Int, total: Int) {
        self.title = title
        self.subtitle = subtitle
        self.done = Int64(max(0, done))
        self.total = Int64(max(0, total))
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
        // Whole tiles: the system watches the completed count to see that the work is moving.
        let total = max(self.total, 1)
        if t.progress.totalUnitCount != total { t.progress.totalUnitCount = total }
        t.progress.completedUnitCount = min(done, total)
        if (title != shownTitle || subtitle != shownSubtitle) && Date().timeIntervalSince(titleShownAt) >= 1 {
            t.updateTitle(title, subtitle: subtitle)
            shownTitle = title
            shownSubtitle = subtitle
            titleShownAt = Date()
        }
        #endif
    }

    #if compiler(>=6.2)
    @available(iOS 26.0, *)
    private func submit() {
        guard let bundleID = Bundle.main.bundleIdentifier else { return }
        // A new identifier each time (registering one twice ends the app). It has to start with the bundle identifier
        // the app is installed with (sideloading tools add the team ID to it) and match BGTaskSchedulerPermittedIdentifiers.
        let id = "\(bundleID).download.\(UUID().uuidString)"
        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        guard permitted.contains(where: { $0 == id || ($0.hasSuffix(".*") && id.hasPrefix(String($0.dropLast()))) }) else { return }
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { [weak self] t in
            if let self = self { self.started(t, id: id) } else { t.setTaskCompleted(success: true) }
        }
        guard registered else { return }
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: title, subtitle: subtitle)
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            pending = id
        } catch {
            // Not possible now (too many tasks, or not allowed): the download runs while SatMap is on screen, as before.
        }
    }

    @available(iOS 26.0, *)
    private func started(_ t: BGTask, id: String) {
        guard let t = t as? BGContinuedProcessingTask, pending == id else {
            t.setTaskCompleted(success: true) // the download ended before iOS started the task
            return
        }
        pending = nil
        task = t
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
        shownTitle = ""
        shownSubtitle = ""
        titleShownAt = .distantPast
        push()
    }
    #endif
}
