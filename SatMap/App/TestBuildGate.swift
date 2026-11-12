import Combine
import Foundation
import UIKit

/// The app's side of the test-build switch (the rule is `TestBuildSwitch`): a test build fetches the status file at
/// launch, when it comes to the foreground and every five minutes while in the foreground (never in the background),
/// keeps the last known state so a build that has seen "off" starts locked even offline, and publishes the lock
/// (nil when unlocked). While locked it also pauses the offline downloads, the track recording and the groups'
/// location sharing, and resumes them when it unlocks. Store builds and debug builds never check and never lock.
/// Main thread only.
final class TestBuildGate: ObservableObject {
    /// What to show over everything, or nil when the app runs normally.
    @Published private(set) var lock: TestBuildSwitch.Lock?

    private weak var app: AppModel?
    private let channel = TestBuildConfig.channel
    private let isDebugBuild = TestBuildConfig.isDebugBuild
    private let testOverride = TestBuildConfig.testOverride
    private let defaults = UserDefaults.standard
    /// The last state fetched or kept (nil: never fetched, which counts as on).
    private var known: TestBuildSwitch.Status?
    private var timer: Timer?
    private var fetching = false
    private var started = false

    /// For the walkthroughs only: no check at all, or forced to the sample lock.
    private var overridden: Bool { testOverride != nil }

    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = TestBuildSwitch.timeout
        config.timeoutIntervalForResource = TestBuildSwitch.timeout
        config.httpAdditionalHeaders = ["User-Agent": TestBuildGate.appVersion]
        // No cookies or credentials: a plain, anonymous GET.
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        return URLSession(configuration: config)
    }()

    private static let appVersion = TestBuildSwitch.userAgent(
        version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")

    /// Wires the subsystems and applies the first lock (from the kept state, before any network answer), then, for a
    /// build that checks, begins the checks. Call once, from `AppModel.init`, before the recorder starts.
    func start(app: AppModel) {
        guard !started else { return }
        started = true
        self.app = app

        if testOverride == .sampleOff {
            apply(TestBuildSwitch.lock(channel: .test, isDebugBuild: false, status: TestBuildSwitch.sample, platform: .iPhone))
            return
        }
        guard testOverride == nil, TestBuildSwitch.checks(channel: channel, isDebugBuild: isDebugBuild) else { return }

        known = TestBuildSwitch.saved(defaults.string(forKey: TestBuildSwitch.savedKey))
        apply(currentLock())

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(didBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        check()
        startTimer()
    }

    // MARK: - Foreground life

    @objc private func didBecomeActive() { check() }

    @objc private func willEnterForeground() {
        check()
        startTimer()
    }

    @objc private func didEnterBackground() {
        timer?.invalidate()
        timer = nil
    }

    /// Checks every five minutes while in the foreground.
    private func startTimer() {
        guard timer == nil, UIApplication.shared.applicationState != .background else { return }
        let t = Timer(timeInterval: TestBuildSwitch.checkInterval, repeats: true) { [weak self] _ in self?.check() }
        t.tolerance = 30
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    // MARK: - Fetching

    /// One check at a time: tries each URL in turn until one gives information, then keeps and applies it. Anything
    /// unreadable leaves the last known state as it is.
    private func check() {
        guard !fetching, !overridden, UIApplication.shared.applicationState != .background else { return }
        fetching = true
        let urls = TestBuildConfig.statusURLs
        Task { [weak self] in
            let fetched: TestBuildSwitch.Status? = await self?.fetch(urls) ?? nil
            await MainActor.run { [weak self] in
                guard let self = self else { return }
                self.fetching = false
                guard let fetched = fetched else { return }
                self.known = TestBuildSwitch.known(saved: self.known, fetched: fetched)
                self.defaults.set(TestBuildSwitch.savedText(fetched), forKey: TestBuildSwitch.savedKey)
                self.apply(self.currentLock())
            }
        }
    }

    /// The first URL that gives information (`TestBuildSwitch.parse`), or nil when none does.
    private func fetch(_ urls: [String]) async -> TestBuildSwitch.Status? {
        for base in urls {
            guard let url = URL(string: TestBuildSwitch.requestURL(base, now: Date())) else { continue }
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            do {
                let (data, response) = try await session.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200, let status = TestBuildSwitch.parse(data) else { continue }
                return status
            } catch {
                continue
            }
        }
        return nil
    }

    private func currentLock() -> TestBuildSwitch.Lock? {
        TestBuildSwitch.lock(channel: channel, isDebugBuild: isDebugBuild, status: known, platform: .iPhone)
    }

    // MARK: - Applying the lock

    private func apply(_ newLock: TestBuildSwitch.Lock?) {
        let wasLocked = lock != nil
        if lock != newLock { lock = newLock }
        let nowLocked = newLock != nil
        guard nowLocked != wasLocked else { return }
        app?.offline.setLocked(nowLocked)
        app?.recorder.setLocked(nowLocked)
        app?.groups.setLocked(nowLocked)
        app?.droneLink.setLocked(nowLocked)
        app?.carPlayMap?.setLocked(newLock)
    }
}
