import CoreLocation
import CoreMotion
import UIKit

/// Records the GPS track with a battery-first policy:
/// - On screen: GPS at best accuracy, one fix used every 2 s.
/// - In the background: one fix every 10 s; between fixes accuracy is lowered to 3 km so the GPS can power down,
///   and it is raised again shortly before the next fix is due.
/// - Not moving for 2 minutes: sleep mode (GPS off, 3 km accuracy). It wakes when the motion coprocessor reports
///   walking/driving, when a periodic short GPS check shows we moved, or on a large coarse position change.
///
/// Location updates are never stopped (only their accuracy changes): under When-In-Use permission, stopping in the
/// background would end background recording until the app is opened again. `distanceFilter` stays at "none"
/// because iOS 16.4+ may suspend apps that combine low accuracy with a distance filter.
///
/// Create and use on the main thread only; Core Location delivers callbacks on the main run loop.
final class LocationRecorder: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum Power: Equatable {
        case active
        case sleeping
    }

    /// Last fix used (main thread).
    @Published private(set) var location: CLLocation?
    @Published private(set) var power: Power = .active
    @Published private(set) var authorization: CLAuthorizationStatus
    /// Precise Location switched off for the app: fixes are kilometres off and no track can be recorded.
    @Published private(set) var reducedAccuracy = false

    /// Called on the main thread for each new track point.
    var onTrackPoint: ((TrackPoint) -> Void)?
    /// Called on the main thread for each fix that is used (≤ every 2 s on screen, ≤ every 10 s in background).
    var onFix: ((CLLocation) -> Void)?
    /// Called on the main thread with the compass heading of the top of the screen, in degrees from true north (from
    /// magnetic north until there is a position), or nil when the compass can't tell.
    var onHeading: ((CLLocationDirection?) -> Void)?

    /// Whether the compass is wanted (the phone's screen shows the map): it runs only then.
    var headingWanted = false {
        didSet { if headingWanted != oldValue { updateHeading() } }
    }
    /// How the screen is turned (the phone's map tells as it is laid out): the compass gives the heading of its top.
    var screenOrientation = UIInterfaceOrientation.portrait {
        didSet { if screenOrientation != oldValue { applyHeadingOrientation() } }
    }

    /// Set while the measuring marker is placed: on screen, sleep-mode GPS checks run every 15 s instead of every
    /// minute and react to smaller moves, so the live distance follows even steps motion detection misses.
    var measuring = false {
        didSet {
            if measuring && isForeground && power == .sleeping { startPeek() }
        }
    }

    private let manager = CLLocationManager()
    private let motion = CMMotionActivityManager()
    private let motionQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "satmap.motion"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .utility
        return q
    }()

    private var started = false
    private var isForeground: Bool

    private var lastUsedAt = Date.distantPast
    private var lastRecorded: TrackPoint?
    /// After waking, the first track point waits for a fix of 10 m or better (or 30 s), to avoid spikes.
    private var preciseFixNeededUntil = Date.distantPast

    // Stationary detection.
    private var movementAnchor: CLLocation?
    private var lastMovementAt = Date()
    /// Last fix of 50 m or better, and when the GPS started searching without one.
    private var lastGoodFixAt: Date?
    private var searchingSince = Date()
    private var stationaryTimer: Timer?
    private var motionAvailable = false
    private var motionSaysMoving = false
    private var motionMovingSince: Date?
    private var pendingMotionWake: DispatchWorkItem?

    // Background duty cycle.
    private var gpsResting = false
    private var acquiringSince = Date()
    private var dutyTimer: Timer?

    // Sleep mode.
    private var sleepAnchor: CLLocation?
    private var peeking = false
    private var peekTimer: Timer?
    private var peekEndTimer: Timer?

    override init() {
        authorization = manager.authorizationStatus
        isForeground = UIApplication.shared.applicationState != .background
        super.init()
        manager.delegate = self
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.distanceFilter = kCLDistanceFilterNone
        manager.desiredAccuracy = kCLLocationAccuracyBest

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(didBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    /// The last recorded point, so a restart continues the existing track correctly.
    func seed(lastRecorded p: TrackPoint?) {
        lastRecorded = p
    }

    /// Requests permission if needed and starts recording. Safe to call repeatedly.
    func start() {
        authorization = manager.authorizationStatus
        switch authorization {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            beginUpdates()
        default:
            break
        }
    }

    private func beginUpdates() {
        // Background updates can only be started while the app is in the foreground.
        guard !started, UIApplication.shared.applicationState != .background else { return }
        started = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        lastMovementAt = Date()
        searchingSince = Date()
        applyAccuracy()
        manager.startUpdatingLocation()
        startMotion()
        startStationaryTimer()
    }

    /// Checks for "not moving" while active; stopped during sleep so the CPU isn't woken needlessly.
    private func startStationaryTimer() {
        stationaryTimer?.invalidate()
        let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.evaluateStationary() }
        t.tolerance = 5
        RunLoop.main.add(t, forMode: .common)
        stationaryTimer = t
    }

    // MARK: - Accuracy

    private func applyAccuracy() {
        let desired: CLLocationAccuracy
        switch power {
        case .sleeping:
            desired = peeking ? (isForeground ? kCLLocationAccuracyBest : kCLLocationAccuracyNearestTenMeters) : kCLLocationAccuracyThreeKilometers
        case .active:
            if isForeground {
                desired = kCLLocationAccuracyBest
            } else {
                desired = gpsResting ? kCLLocationAccuracyThreeKilometers : kCLLocationAccuracyNearestTenMeters
            }
        }
        if manager.desiredAccuracy != desired { manager.desiredAccuracy = desired }
    }

    // MARK: - Compass

    private func updateHeading() {
        guard CLLocationManager.headingAvailable() else { return }
        if headingWanted {
            manager.headingFilter = 2
            applyHeadingOrientation()
            manager.startUpdatingHeading()
        } else {
            manager.stopUpdatingHeading()
            onHeading?(nil)
        }
    }

    private func applyHeadingOrientation() {
        // The screen's landscape left is the phone's landscape right (its top turned to the right).
        let o: CLDeviceOrientation
        switch screenOrientation {
        case .landscapeLeft: o = .landscapeRight
        case .landscapeRight: o = .landscapeLeft
        case .portraitUpsideDown: o = .portraitUpsideDown
        default: o = .portrait
        }
        if manager.headingOrientation != o { manager.headingOrientation = o }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading heading: CLHeading) {
        guard headingWanted else { return }
        guard heading.headingAccuracy >= 0 else {
            onHeading?(nil)
            return
        }
        onHeading?(heading.trueHeading >= 0 ? heading.trueHeading : heading.magneticHeading)
    }

    /// iOS's calibration screen only while the compass can't tell at all (the arrow is hidden then).
    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        headingWanted && (manager.heading?.headingAccuracy ?? -1) < 0
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        reducedAccuracy = manager.accuracyAuthorization == .reducedAccuracy
        if authorization == .authorizedWhenInUse || authorization == .authorizedAlways {
            beginUpdates()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // kCLErrorLocationUnknown is transient; denial is reported through the authorization callback.
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy >= 0, abs(loc.timestamp.timeIntervalSinceNow) < 20 else { return }
        let now = Date()

        if power == .sleeping {
            handleWhileSleeping(loc, now: now)
            return
        }

        if isForeground {
            guard now.timeIntervalSince(lastUsedAt) >= LocationPolicy.foregroundInterval - 0.15 else { return }
            use(loc, now: now)
        } else {
            // Coarse fixes while the GPS rests only keep the app alive.
            guard !gpsResting else { return }
            let waited = now.timeIntervalSince(acquiringSince)
            // Only a fix taken after the GPS was woken, and no earlier than the 10 s mark.
            guard loc.timestamp >= acquiringSince.addingTimeInterval(-0.5),
                  now.timeIntervalSince(lastUsedAt) >= LocationPolicy.backgroundInterval - 0.5 || waited >= 20 else { return }
            guard loc.horizontalAccuracy <= 20 || (waited >= 6 && loc.horizontalAccuracy <= LocationPolicy.maxTrackAccuracy) || waited >= 20 else { return }
            use(loc, now: now)
            restUntilNextBackgroundFix()
        }
    }

    private func use(_ loc: CLLocation, now: Date) {
        lastUsedAt = now
        noteMovement(loc, now: now)
        // A coarse (cell / Wi-Fi) fix, e.g. right after waking, mustn't replace a recent GPS position.
        if let current = location, loc.horizontalAccuracy > 100, current.horizontalAccuracy <= 100,
           now.timeIntervalSince(current.timestamp) < 60 {
            return
        }
        location = loc
        record(loc)
        onFix?(loc)
    }

    // MARK: - Track

    private func record(_ loc: CLLocation) {
        guard loc.horizontalAccuracy <= LocationPolicy.maxTrackAccuracy else { return }
        if Date() < preciseFixNeededUntil {
            guard loc.horizontalAccuracy <= 10 else { return }
            preciseFixNeededUntil = .distantPast
        }
        var p = TrackPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                           time: loc.timestamp.timeIntervalSince1970, startsSegment: true)
        if let last = lastRecorded {
            let d = CLLocation(latitude: last.lat, longitude: last.lon).distance(from: loc)
            // Moves smaller than the fix's own error are GPS wander, not walking.
            guard d >= max(LocationPolicy.minTrackSpacing, loc.horizontalAccuracy) else { return }
            p.startsSegment = p.time - last.time > LocationPolicy.segmentBreakSeconds && d > LocationPolicy.segmentBreakMeters
        }
        lastRecorded = p
        onTrackPoint?(p)
    }

    // MARK: - Background duty cycle

    private func restUntilNextBackgroundFix() {
        guard !isForeground, power == .active else { return }
        gpsResting = true
        applyAccuracy()
        dutyTimer?.invalidate()
        let fireAt = lastUsedAt.addingTimeInterval(LocationPolicy.backgroundInterval - LocationPolicy.backgroundWakeLead)
        let t = Timer(fire: fireAt, interval: 0, repeats: false) { [weak self] _ in self?.startAcquiring() }
        t.tolerance = 0.5
        RunLoop.main.add(t, forMode: .common)
        dutyTimer = t
    }

    private func startAcquiring() {
        dutyTimer?.invalidate()
        dutyTimer = nil
        gpsResting = false
        acquiringSince = Date()
        applyAccuracy()
    }

    // MARK: - Stationary detection & sleep

    private func noteMovement(_ loc: CLLocation, now: Date) {
        guard loc.horizontalAccuracy <= 50 else { return }
        lastGoodFixAt = now
        if let a = movementAnchor {
            if loc.distance(from: a) > max(LocationPolicy.movementThreshold, loc.horizontalAccuracy) {
                movementAnchor = loc
                lastMovementAt = now
            }
        } else {
            movementAnchor = loc
            lastMovementAt = now
        }
    }

    private func evaluateStationary() {
        guard started, power == .active else { return }
        let now = Date()
        // Motion says we're walking/driving: don't sleep (covers pacing within a few metres).
        if motionSaysMoving, let since = motionMovingSince, now.timeIntervalSince(since) < 600 { return }
        // No usable fix yet (cold start, poor sky view): keep searching for a while, since we can't tell
        // whether we're moving.
        if lastGoodFixAt == nil && now.timeIntervalSince(searchingSince) < 600 { return }
        if now.timeIntervalSince(lastMovementAt) >= LocationPolicy.stationaryTimeout {
            enterSleep()
        }
    }

    private func enterSleep() {
        power = .sleeping
        sleepAnchor = location
        stationaryTimer?.invalidate()
        stationaryTimer = nil
        dutyTimer?.invalidate()
        dutyTimer = nil
        gpsResting = false
        peeking = false
        applyAccuracy()
        schedulePeek()
    }

    private func wake() {
        guard power == .sleeping else { return }
        power = .active
        peeking = false
        peekTimer?.invalidate()
        peekEndTimer?.invalidate()
        peekTimer = nil
        peekEndTimer = nil
        pendingMotionWake?.cancel()
        pendingMotionWake = nil
        lastMovementAt = Date()
        searchingSince = Date()
        movementAnchor = nil
        lastUsedAt = .distantPast
        preciseFixNeededUntil = Date().addingTimeInterval(30)
        gpsResting = false
        acquiringSince = Date()
        applyAccuracy()
        startStationaryTimer()
    }

    private func schedulePeek() {
        peekTimer?.invalidate()
        guard power == .sleeping else { return }
        let interval = !isForeground ? LocationPolicy.sleepPeekIntervalBackground
            : measuring ? LocationPolicy.sleepPeekIntervalMeasuring : LocationPolicy.sleepPeekIntervalForeground
        let t = Timer(timeInterval: interval, repeats: false) { [weak self] _ in self?.startPeek() }
        t.tolerance = interval * 0.1
        RunLoop.main.add(t, forMode: .common)
        peekTimer = t
    }

    private func startPeek() {
        peekTimer?.invalidate()
        peekTimer = nil
        guard power == .sleeping else { return }
        peeking = true
        applyAccuracy()
        peekEndTimer?.invalidate()
        // Without a recent good fix the GPS may need a cold start, which takes longer.
        let recentFix = lastGoodFixAt.map { Date().timeIntervalSince($0) < 600 } ?? false
        let t = Timer(timeInterval: recentFix ? LocationPolicy.peekTimeout : 45, repeats: false) { [weak self] _ in self?.endPeek() }
        RunLoop.main.add(t, forMode: .common)
        peekEndTimer = t
    }

    private func endPeek() {
        peekEndTimer?.invalidate()
        peekEndTimer = nil
        guard power == .sleeping else { return }
        peeking = false
        applyAccuracy()
        schedulePeek()
    }

    private func handleWhileSleeping(_ loc: CLLocation, now: Date) {
        if peeking {
            guard loc.horizontalAccuracy <= 25 else { return }
            lastGoodFixAt = now
            guard let anchor = sleepAnchor, anchor.horizontalAccuracy <= 25 else {
                // We fell asleep without a good position: this is the first one, so use it.
                wake()
                use(loc, now: now)
                return
            }
            let threshold = measuring && isForeground ? max(8, 1.5 * loc.horizontalAccuracy) : max(20, 2 * loc.horizontalAccuracy)
            if loc.distance(from: anchor) > threshold {
                wake()
                use(loc, now: now)
            } else {
                // Still here: refresh the dot and measuring line without adding to the track.
                location = loc
                onFix?(loc)
                endPeek()
            }
            return
        }
        // Coarse (cell / Wi-Fi) fixes: wake on a jump that can't be explained by their error.
        if let a = sleepAnchor, loc.horizontalAccuracy < 1500,
           loc.distance(from: a) > max(300, 2 * loc.horizontalAccuracy + a.horizontalAccuracy) {
            wake()
        }
    }

    // MARK: - Motion coprocessor

    private func startMotion() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        switch CMMotionActivityManager.authorizationStatus() {
        case .authorized:
            startActivityUpdates()
        case .notDetermined:
            // Reading activity history shows the permission prompt.
            let now = Date()
            motion.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: motionQueue) { [weak self] _, _ in
                DispatchQueue.main.async {
                    if CMMotionActivityManager.authorizationStatus() == .authorized { self?.startActivityUpdates() }
                }
            }
        default:
            break // GPS-only movement detection (periodic peeks) still works.
        }
    }

    private func startActivityUpdates() {
        guard !motionAvailable else { return }
        motionAvailable = true
        motion.startActivityUpdates(to: motionQueue) { [weak self] activity in
            guard let a = activity else { return }
            DispatchQueue.main.async { self?.handleActivity(a) }
        }
    }

    private func handleActivity(_ a: CMMotionActivity) {
        guard !a.unknown else { return }
        let translating = a.walking || a.running || a.cycling || (a.automotive && !a.stationary)
        if translating && a.confidence != .low {
            if !motionSaysMoving { motionMovingSince = Date() }
            motionSaysMoving = true
            guard power == .sleeping else { return }
            if a.confidence == .high {
                wake()
            } else if pendingMotionWake == nil {
                // Medium confidence: wait a few seconds for a contradiction before waking the GPS.
                let w = DispatchWorkItem { [weak self] in
                    self?.pendingMotionWake = nil
                    self?.wake()
                }
                pendingMotionWake = w
                DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: w)
            }
        } else if a.stationary && !a.automotive {
            motionSaysMoving = false
            motionMovingSince = nil
            pendingMotionWake?.cancel()
            pendingMotionWake = nil
        }
    }

    // MARK: - App state

    @objc private func didEnterBackground() {
        isForeground = false
        if power == .active {
            // Take the next fix now, then rest the GPS between 10 s fixes.
            startAcquiring()
        } else {
            peeking = false
            peekEndTimer?.invalidate()
            applyAccuracy()
            schedulePeek()
        }
    }

    @objc private func willEnterForeground() {
        isForeground = true
        dutyTimer?.invalidate()
        dutyTimer = nil
        gpsResting = false
        applyAccuracy()
        if power == .sleeping {
            // Check right away whether we moved while the app was in the background.
            startPeek()
        }
    }

    @objc private func didBecomeActive() {
        isForeground = true
        if !started { start() }
        // Motion permission may have been answered while the app was inactive behind the prompt.
        if started { startMotion() }
    }
}
