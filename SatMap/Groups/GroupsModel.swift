import Combine
import CoreLocation
import SwiftUI
import UIKit

/// The groups feature in the app (docs/groups/SPEC.md §8.1): it owns the sync engine (`GroupHub`) with the
/// platform's transport, WebSocket, Keychain and Pro store; drives its lifecycle and upload ticks; feeds it the
/// recorder's points, fixes and authorisation; and holds what the screens show (the Groups sheet's stack, the sheet of
/// a map item, the join sheet) and the map's revision. Made by `AppModel` at launch, because sharing runs with no
/// screen. Main thread only.
final class GroupsModel: ObservableObject {
    let hub: GroupHub
    let pro: ProStore
    let isDemo: Bool

    /// Bumped when a group or the list changes: the screens read the hub again.
    @Published private(set) var revision = 0
    /// Bumped when the shown group's map content changes (and every minute, for the pins' ages and expiry).
    @Published private(set) var mapRevision = 0

    /// The Groups sheet: its root and pushed screens.
    @Published var root = GroupsRoot.list
    @Published var path: [GroupsRoute] = []
    /// S11, S12 or S10 opened from the map, in their own sheet.
    @Published var groupItem: GroupItem?
    /// The accepted terms version (`groupsTermsAccepted`, §8.13).
    @Published private(set) var termsAccepted: Int
    /// Groups whose paused banner was closed: hidden until the next launch (§9.1).
    @Published private(set) var bannerHidden: Set<String> = []

    weak var app: AppModel?
    /// The compass heading (degrees from north), for the live position when not moving (§4.4).
    var heading: Double?

    private let words: WordFilter
    private let defaults = UserDefaults.standard
    private var startTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private var eventsPending = false
    private var mapPending = false
    private var foregroundTimer: Timer?
    private var backgroundTimer: Timer?
    private var minuteTimer: Timer?
    private var nextBackgroundTick: Date?
    private var ticking = false
    /// A group just shown from S5 or S8 whose live positions haven't arrived yet: the camera fits it when they do.
    private var pendingFit: (groupId: String, until: Date)?
    private let invitePresenter = InviteSheetPresenter()

    init() {
        isDemo = GroupConfig.isDemo
        termsAccepted = UserDefaults.standard.integer(forKey: "groupsTermsAccepted")
        words = WordFilter(list: Bundle.main.url(forResource: "GroupWords", withExtension: "txt")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "")
        pro = ProStore(demo: isDemo)
        let clock = { Int64(Date().timeIntervalSince1970) }
        if isDemo {
            // Demo mode (§8.15): the fake server with the seed, memberships and the store in memory, fresh each launch.
            hub = GroupDemo.makeHub(store: Self.openStore(path: ":memory:"), words: words, clock: clock).hub
        } else {
            let base = GroupConfig.apiBaseURL
            let sockets = GroupSocketFactoryImpl(baseURL: base)
            hub = GroupHub(store: Self.openStore(path: Self.storePath()), secrets: KeychainSecrets(), transport: GroupHTTP(baseURL: base),
                           sockets: sockets, words: words, domain: GroupConfig.domain, clock: clock)
            sockets.hub = hub
        }
        hub.acceptedTermsVersion = max(termsAccepted, 1)
        hub.onChange = { [weak self] event in self?.hubChanged(event) }
        pro.onEntitlement = { [weak self] e in self?.sendPro(e) }
    }

    // MARK: Store

    /// `Application Support/SatMap/Groups/groups.sqlite`, excluded from backup (§8.3).
    private static func storePath() -> String {
        var dir = AppPaths.root.appendingPathComponent("Groups", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir.appendingPathComponent("groups.sqlite").path
    }

    /// The store, or one in memory if the file can't be opened (the memberships are then restored from the Keychain
    /// at every launch, §8.3).
    private static func openStore(path: String) -> GroupStore {
        if let s = try? GroupStore(path: path) { return s }
        GroupLog.note("couldn't open the groups store; using one in memory")
        guard let memory = try? GroupStore(path: ":memory:") else { fatalError("SQLite can't open a database in memory") }
        return memory
    }

    // MARK: Lifecycle (§8.4)

    /// At launch, from `AppModel.init`.
    func start(recorder: LocationRecorder) {
        let hub = self.hub
        startTask = Task { @MainActor in await hub.start() }
        pro.start()

        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.becameActive()
        }
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.enteredBackground()
        }
        Connectivity.shared.$state
            .map(\.isOnline)
            .removeDuplicates()
            .dropFirst()
            .filter { $0 }
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.afterStart { await $0.connectivityChanged(online: true) }
                }
            }
            .store(in: &cancellables)
        recorder.$authorization.combineLatest(recorder.$reducedAccuracy)
            .sink { [weak self] status, reduced in
                let authorised = status == .authorizedWhenInUse || status == .authorizedAlways
                DispatchQueue.main.async { self?.hub.setLocationAccess(authorised: authorised, precise: !reduced) }
            }
            .store(in: &cancellables)

        // The pins' ages and expiry (the map refreshes pins every 60 s, §8.9).
        let minute = Timer(timeInterval: 60, repeats: true) { [weak self] _ in self?.bumpMap() }
        minute.tolerance = 5
        RunLoop.main.add(minute, forMode: .common)
        minuteTimer = minute
        if UIApplication.shared.applicationState != .background { startForegroundTicks() } else { scheduleBackgroundTick() }
    }

    /// Runs hub work once `start()` has finished, so the two never interleave.
    private func afterStart(_ work: @escaping @MainActor (GroupHub) async -> Void) {
        let started = startTask
        let hub = self.hub
        Task { @MainActor in
            await started?.value
            await work(hub)
        }
    }

    private func becameActive() {
        afterStart { await $0.becameActive() }
        startForegroundTicks()
        bumpMap()
    }

    private func enteredBackground() {
        hub.enteredBackground()
        foregroundTimer?.invalidate()
        foregroundTimer = nil
        scheduleBackgroundTick()
    }

    // MARK: Ticks (§8.6)

    private var isForeground: Bool { UIApplication.shared.applicationState != .background }

    private func startForegroundTicks() {
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        nextBackgroundTick = nil
        guard foregroundTimer == nil else { return }
        let t = Timer(timeInterval: TimeInterval(GroupPolicy.liveWatchedForeground), repeats: true) { [weak self] _ in self?.tick() }
        t.tolerance = 0.5
        RunLoop.main.add(t, forMode: .common)
        foregroundTimer = t
    }

    /// In the background: the next tick when the hub has something to do (15 or 60 s), checked again on every fix.
    private func scheduleBackgroundTick() {
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        guard !isForeground, let delay = hub.nextTickDelay(foreground: false) else {
            nextBackgroundTick = nil
            return
        }
        let due = Date().addingTimeInterval(TimeInterval(delay))
        nextBackgroundTick = due
        let t = Timer(fire: due, interval: 0, repeats: false) { [weak self] _ in self?.tick() }
        t.tolerance = 2
        RunLoop.main.add(t, forMode: .common)
        backgroundTimer = t
    }

    private func tick() {
        guard !ticking else { return }
        ticking = true
        let foreground = isForeground
        afterStart { [weak self] hub in
            await hub.tick(foreground: foreground)
            guard let self = self else { return }
            self.ticking = false
            if !self.isForeground { self.scheduleBackgroundTick() }
        }
    }

    // MARK: The location pipeline (§8.5)

    /// A point of "Where I've been" (`AppModel.appendTrack`).
    func trackPoint(_ p: TrackPoint) {
        hub.trackPoint(p)
    }

    /// A fix used by the recorder (`AppModel.locationUpdated`): the newest live position, and in the background a check
    /// whether a tick is due.
    func fix(_ loc: CLLocation) {
        hub.fix(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                accuracy: loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : nil,
                course: loc.course >= 0 ? loc.course : nil, speed: loc.speed >= 0 ? loc.speed : nil,
                heading: heading, time: loc.timestamp.timeIntervalSince1970)
        if !isForeground {
            if let due = nextBackgroundTick {
                if Date() >= due { tick() }
            } else {
                scheduleBackgroundTick()
            }
        }
    }

    // MARK: Hub events

    private func hubChanged(_ event: GroupHubEvent) {
        switch event {
        case .groups, .group:
            guard !eventsPending else { return }
            eventsPending = true
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.eventsPending = false
                self.revision += 1
                self.closeScreensOfGoneGroups()
            }
        case .map:
            bumpMap()
        case .alert(let a):
            let text = GroupTexts.alert(a)
            DispatchQueue.main.async { [weak self] in
                self?.closeScreensOfGoneGroups()
                TopPresenter.alert(title: text.title, message: text.message)
            }
        }
    }

    private func bumpMap() {
        guard !mapPending else { return }
        mapPending = true
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.mapPending = false
            self.mapRevision += 1
            self.fitIfPending()
        }
    }

    /// Screens of a group that is no longer on the phone close (left, deleted, removed).
    private func closeScreensOfGoneGroups() {
        let ids = Set(hub.groups.map(\.groupId))
        if let gone = path.compactMap(\.groupId).first(where: { !ids.contains($0) }) {
            path = GroupsFlow.removingGroup(path, gone)
        }
        if case .detail(let g) = root, !ids.contains(g) {
            root = .list
            path = []
        }
        switch groupItem {
        case .pin(let g, _), .member(let g, _), .dropPin(let g, _, _):
            if !ids.contains(g) { groupItem = nil }
        case nil:
            break
        }
    }

    // MARK: Reading

    var shownGroupId: String? { hub.shownGroupId }

    /// The map's paused banner for the shown group, unless closed (§9.1).
    var pausedBanner: (groupId: String, text: String)? {
        guard let gid = hub.shownGroupId, !bannerHidden.contains(gid), let d = hub.detail(gid), d.isPaused else { return nil }
        return (gid, GroupTexts.pausedBanner(group: d.name, reason: d.pausedReason, isOwner: d.yourRole == .owner))
    }

    func hideBanner(_ groupId: String) {
        bannerHidden.insert(groupId)
    }

    /// The shown group when it is active: pins can be dropped on the map.
    var activeShownGroupId: String? {
        guard let gid = hub.shownGroupId, let d = hub.detail(gid), !d.isPaused else { return nil }
        return gid
    }

    /// Your position, for "Drop a pin where I am" and the camera.
    var myPosition: CLLocationCoordinate2D? { app?.recorder.location?.coordinate }

    // MARK: Terms and names

    func acceptTerms() {
        termsAccepted = GroupConfig.termsVersion
        defaults.set(GroupConfig.termsVersion, forKey: "groupsTermsAccepted")
        hub.acceptedTermsVersion = GroupConfig.termsVersion
    }

    /// Whether S1 must show first.
    var needsTerms: Bool { termsAccepted < GroupConfig.termsVersion }

    /// After `terms_outdated` (§8.13): S1 again and one retry when this app has newer terms than accepted.
    func canRetryAfterTermsOutdated() -> Bool {
        hub.acceptedTermsVersion < GroupConfig.termsVersion
    }

    var lastDisplayName: String { defaults.string(forKey: "groupsDisplayName") ?? "" }

    func rememberDisplayName(_ name: String) {
        if let cleaned = GroupText.clean(name, .displayName) { defaults.set(cleaned, forKey: "groupsDisplayName") }
    }

    // MARK: Opening screens

    /// The Groups button: S2 (S1 first until the terms are accepted).
    func openFromButton() {
        root = .list
        path = []
        app?.sheet = .groups
    }

    /// Opens the Groups sheet with a root and stack, closing whatever sheet is up first (§9.2 flows).
    func openGroups(root newRoot: GroupsRoot = .list, path newPath: [GroupsRoute] = []) {
        guard let app = app else { return }
        if app.sheet == .groups {
            root = newRoot
            path = newPath
            return
        }
        let open = { [weak self, weak app] in
            guard let self = self, let app = app else { return }
            self.root = newRoot
            self.path = newPath
            app.sheet = .groups
        }
        if app.sheet != nil || groupItem != nil || app.pendingArea != nil {
            app.sheet = nil
            groupItem = nil
            if app.pendingArea != nil { app.discardPendingArea() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.65, execute: open)
        } else {
            open()
        }
    }

    /// An invite link arrived (`onOpenURL`, the paste field or the scanner): S8 over anything, never a join by itself
    /// (§5.6). A group you're already in opens its screen and says so instead.
    func openInvite(_ parsed: InviteLink.Parsed) {
        if case .invite(let link) = parsed, let d = hub.detail(link.groupIdText) {
            openGroups(root: .detail(d.groupId))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
                TopPresenter.alert(title: "You're already in \(d.name).", message: nil)
            }
            return
        }
        guard parsed != .notInvite else { return }
        invitePresenter.present(JoinSheet(groups: self, parsed: parsed))
    }

    /// S8 is done: it closes.
    func closeInvite(then: (() -> Void)? = nil) {
        invitePresenter.dismiss(then: then)
    }

    /// S8 joined: the sheet closes, the Groups sheet opens with consent then the group's screen, and the map fits the
    /// group, which is now the shown group (§9.2).
    func joined(_ groupId: String) {
        closeInvite { [weak self] in
            guard let self = self else { return }
            self.openGroups(root: .list, path: GroupsFlow.afterJoin(groupId))
            self.fitWhenReady(groupId)
        }
    }

    /// S8's "Open group" for a group you're already in.
    func openGroupFromInvite(_ groupId: String) {
        closeInvite { [weak self] in self?.openGroups(root: .detail(groupId)) }
    }

    /// The paused banner's tap: the group's screen.
    func openPausedGroup(_ groupId: String) {
        openGroups(root: .detail(groupId))
    }

    /// A tap on a member dot or a group pin on the map (§9.1).
    func openFromMap(_ item: GroupItem) {
        groupItem = item
    }

    /// Closes the Groups sheet and a map item's sheet.
    func closeSheets() {
        groupItem = nil
        if app?.sheet == .groups { app?.sheet = nil }
    }

    // MARK: Showing on the map

    /// "Show on the map" (S5): the group becomes the shown group, and the map fits it (§9.5 Camera).
    func setShown(_ groupId: String, on: Bool) {
        if on {
            hub.setShown(groupId)
            fitWhenReady(groupId)
        } else if hub.shownGroupId == groupId {
            hub.setShown(nil)
        }
    }

    private func fitWhenReady(_ groupId: String) {
        pendingFit = (groupId, Date().addingTimeInterval(15))
        fitIfPending()
    }

    /// Fits every live member position plus yours, with 60 pt padding and at least 300 m across; with no live
    /// positions the camera doesn't move.
    private func fitIfPending() {
        guard let p = pendingFit else { return }
        guard Date() < p.until, hub.shownGroupId == p.groupId else {
            pendingFit = nil
            return
        }
        guard let content = hub.mapContent(now: Int64(Date().timeIntervalSince1970)), content.groupId == p.groupId,
              !content.members.isEmpty, let map = app?.map else { return }
        pendingFit = nil
        var coords = content.members.map { CLLocationCoordinate2D(latitude: $0.position.lat, longitude: $0.position.lon) }
        if let me = myPosition { coords.append(me) }
        map.fit(coords, padding: 60, minimumMeters: 300)
    }

    /// "Show on map" (S12): the map centres on a member and the sheet closes.
    func showOnMap(_ c: GroupCoordinate) {
        closeSheets()
        app?.following = false
        app?.map?.center(on: CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), animated: true)
    }

    // MARK: Location for sharing (§8.13)

    /// Whether location may be used for sharing: asks for permission when it isn't decided yet (and waits for the
    /// answer); false when it is refused or restricted (the screens then show the "Location is off" alert).
    @MainActor
    func locationForSharing() async -> Bool {
        guard let recorder = app?.recorder else { return false }
        func allowed(_ s: CLAuthorizationStatus) -> Bool { s == .authorizedWhenInUse || s == .authorizedAlways }
        switch recorder.authorization {
        case .authorizedWhenInUse, .authorizedAlways:
            return true
        case .notDetermined:
            recorder.start() // asks for When In Use (§9.9: Android runs its PermissionFlow)
            for await status in recorder.$authorization.values where status != .notDetermined {
                return allowed(status)
            }
            return false
        default:
            return false
        }
    }

    /// Switches sharing on or off for a group from its screen. On: S9 first without consent; the location permission
    /// asked if needed. Returns false when location is refused (sharing stays off).
    @MainActor
    func setSharing(_ on: Bool, groupId: String) async -> Bool {
        guard on else {
            hub.setSharing(false, groupId: groupId)
            return true
        }
        guard await locationForSharing() else { return false }
        hub.setSharing(true, groupId: groupId)
        return true
    }

    // MARK: Pro (§10.7)

    private func sendPro(_ e: ProStore.Entitlement) {
        afterStart { await $0.updatePro(e.proof, productId: e.productId, expiresAt: e.expiresAt) }
    }

    /// A purchase or restore on S3 succeeded: the action it was opened for continues (S4 is pushed by the flow; the
    /// proof for a renewal went with the store change).
    func continueAfterPurchase(_ next: PaywallNext) {
        if case .accept(let gid) = next { acceptOwnership(gid, nav: nil) }
    }

    /// "Renew SatMap Pro" (S5): E5 at once when the phone has Pro, otherwise S3 first (§10.5).
    func renew(_ groupId: String, nav: GroupsNav) {
        guard pro.isPro, let e = pro.entitlement else {
            nav.push(.paywall(.renew(groupId)))
            return
        }
        afterStart { await $0.updatePro(e.proof, productId: e.productId, expiresAt: e.expiresAt) }
    }

    /// "Accept" an ownership offer (S5): E17 at once when the phone has Pro, otherwise S3 first. A failure is said
    /// in an alert (the offer stays).
    func acceptOwnership(_ groupId: String, nav: GroupsNav?) {
        guard pro.isPro, let proof = pro.proof else {
            nav?.push(.paywall(.accept(groupId)))
            return
        }
        let hub = self.hub
        Task { @MainActor in
            do {
                try await hub.acceptOwnership(groupId: groupId, pro: proof)
            } catch let e as GroupActionError {
                TopPresenter.alert(title: e.message, message: nil)
            } catch {
                TopPresenter.alert(title: GroupActionError(.server).message, message: nil)
            }
        }
    }

    // MARK: Messages

    /// A short message at the bottom of the screen ("Link copied").
    func toast(_ text: String) {
        TopPresenter.toast(text)
    }
}

// MARK: - Presenting over anything

/// Alerts and toasts over whatever is on screen (a sheet, another sheet over it), for what the hub says on its own.
enum TopPresenter {
    static var top: UIViewController? {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        guard var top = window?.rootViewController else { return nil }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        return top
    }

    static func alert(title: String, message: String?) {
        guard let top = top else { return }
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .cancel))
        a.overrideUserInterfaceStyle = .dark
        top.present(a, animated: true)
    }

    /// A capsule near the bottom of the key window for two seconds.
    static func toast(_ text: String) {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
        let label = PaddedLabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .subheadline).withWeight(.semibold)
        label.textColor = .white
        label.backgroundColor = UIColor(white: 0.12, alpha: 0.92)
        label.layer.cornerRadius = 18
        label.layer.masksToBounds = true
        label.textAlignment = .center
        label.accessibilityIdentifier = "toast"
        label.translatesAutoresizingMaskIntoConstraints = false
        label.alpha = 0
        window.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: window.centerXAnchor),
            label.bottomAnchor.constraint(equalTo: window.safeAreaLayoutGuide.bottomAnchor, constant: -40),
            label.heightAnchor.constraint(greaterThanOrEqualToConstant: 36),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: window.leadingAnchor, constant: 24),
        ])
        UIAccessibility.post(notification: .announcement, argument: text)
        UIView.animate(withDuration: 0.2) { label.alpha = 1 }
        UIView.animate(withDuration: 0.3, delay: 2, options: []) { label.alpha = 0 } completion: { _ in label.removeFromSuperview() }
    }

    private final class PaddedLabel: UILabel {
        override var intrinsicContentSize: CGSize {
            let s = super.intrinsicContentSize
            return CGSize(width: s.width + 32, height: s.height + 16)
        }
    }
}

private extension UIFont {
    func withWeight(_ weight: UIFont.Weight) -> UIFont {
        UIFont.systemFont(ofSize: pointSize, weight: weight)
    }
}

/// Presents S8 (the join sheet) over whatever is on screen, so an invite link can open it over anything (§9.2).
final class InviteSheetPresenter {
    private weak var presented: UIViewController?

    func present<Content: View>(_ content: Content) {
        let show = { [weak self] in
            guard let self = self, let top = TopPresenter.top else { return }
            let host = HostingSheet(rootView: AnyView(content.preferredColorScheme(.dark)))
            host.overrideUserInterfaceStyle = .dark
            self.presented = host
            top.present(host, animated: true)
        }
        if let old = presented {
            old.presentingViewController?.dismiss(animated: false, completion: show)
        } else {
            show()
        }
    }

    func dismiss(then: (() -> Void)?) {
        guard let p = presented, let presenter = p.presentingViewController else {
            then?()
            return
        }
        presented = nil
        presenter.dismiss(animated: true) { then?() }
    }

    private final class HostingSheet: UIHostingController<AnyView> {}
}
