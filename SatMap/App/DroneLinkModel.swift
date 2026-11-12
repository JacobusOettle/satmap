import Foundation
import UIKit

/// Drone link (README "Drone link"): listens for the MAVLink a ground station (QGroundControl, Mission Planner,
/// MAVProxy) forwards to the phone over UDP, and keeps the drones and ADS-B aircraft it hears for drone mode's map.
/// Receive-only: nothing is ever sent. The socket is open only while the switch is on and the app is in the
/// foreground (closed in the background, opened again on return), and never while test builds are switched off.
/// Main thread only, except the socket's queue, which only hands datagrams over.
final class DroneLinkModel: ObservableObject, @unchecked Sendable {
    /// What a tap on the map selected: a drone (system id) or an aircraft (ICAO address).
    enum Selection: Equatable {
        case drone(UInt8)
        case aircraft(UInt32)
    }

    struct Address: Hashable {
        let label: String
        let address: String
    }

    /// The switch (off by default), saved app-wide.
    @Published private(set) var isEnabled: Bool
    /// The port listened on (default 14445), saved app-wide.
    @Published private(set) var port: Int
    @Published private(set) var status: DroneLink.Status = .off
    /// Bumped when the drones or aircraft change (the map and the panel redraw).
    @Published private(set) var revision = 0
    @Published var selection: Selection?
    /// The phone's Wi-Fi and hotspot addresses, as last looked up (the settings look them up again when shown).
    @Published private(set) var addresses: [Address] = []
    /// Asked once, the first time the switch is turned on.
    @Published var askingDisclaimer = false

    let traffic = DroneLink.Traffic()
    /// The walkthroughs' replay (`DroneLinkConfig.isDemo`): a recorded flight goes through the same decoder, and
    /// nothing is listened for or saved.
    let isDemo = DroneLinkConfig.isDemo

    private let defaults = UserDefaults.standard
    private var disclaimerAccepted: Bool
    private var isForeground = UIApplication.shared.applicationState != .background
    private var isLocked = false

    private let queue = DispatchQueue(label: "com.jacobusoettle.satmap.droneLink", qos: .userInitiated)
    /// The socket, on the port wanted while listening; a port that couldn't be had is tried again by `tick()`.
    private lazy var listening: UDPListening = {
        let inbox = self.inbox
        return UDPListening(queue: queue) { inbox.put($0) }
    }()
    /// Datagrams from the socket's queue, taken on the main thread four times a second.
    private let inbox = Inbox()
    private var timer: Timer?
    /// The replay's clock: seconds since it began, and when it was last fed.
    private var replay: DroneLink.Replay?
    private var replayStarted: Date?
    private var replayFed: TimeInterval = -1
    private var observers: [NSObjectProtocol] = []

    init() {
        let demo = DroneLinkConfig.isDemo
        isEnabled = demo ? false : UserDefaults.standard.bool(forKey: DroneLink.enabledKey)
        port = demo ? DroneLink.defaultPort
            : DroneLink.savedPort(UserDefaults.standard.object(forKey: DroneLink.portKey) as? Int)
        disclaimerAccepted = demo ? false : UserDefaults.standard.bool(forKey: DroneLink.disclaimerKey)
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.isForeground = false
            self?.update()
        })
        observers.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.isForeground = true
            self?.refreshAddresses()
            self?.update()
        })
        refreshAddresses()
        update()
    }

    deinit {
        for o in observers { NotificationCenter.default.removeObserver(o) }
    }

    // MARK: - Settings

    /// The switch: turning it on the first time asks the disclaimer first.
    func setEnabled(_ on: Bool) {
        if on, !disclaimerAccepted {
            askingDisclaimer = true
            return
        }
        guard on != isEnabled else { return }
        isEnabled = on
        if !isDemo { defaults.set(on, forKey: DroneLink.enabledKey) }
        update()
    }

    /// "I understand": the switch comes on.
    func acceptDisclaimer() {
        disclaimerAccepted = true
        if !isDemo { defaults.set(true, forKey: DroneLink.disclaimerKey) }
        askingDisclaimer = false
        setEnabled(true)
    }

    /// A new port (checked by `DroneLink.port`): listened on at once if the link is on, whether or not the old port
    /// could be had (the old socket, or the old port's failure, goes).
    func setPort(_ p: Int) {
        guard DroneLink.portRange.contains(p), p != port else { return }
        port = p
        if !isDemo { defaults.set(p, forKey: DroneLink.portKey) }
        update()
    }

    func refreshAddresses() {
        let list = DroneLink.addresses(UDPReceiver.interfaceAddresses()).map { Address(label: $0.label, address: $0.address) }
        if list != addresses { addresses = list }
    }

    var qgcInstructions: String {
        DroneLink.qgcInstructions(addresses: addresses.map(\.address), port: port)
    }

    /// Test builds switched off: no socket (`TestBuildGate`).
    func setLocked(_ locked: Bool) {
        isLocked = locked
        update()
    }

    // MARK: - Listening

    private var shouldListen: Bool { isEnabled && isForeground && !isLocked }

    /// Opens or closes the socket (or the replay) for the switch, the app's state and the lock.
    private func update() {
        if shouldListen {
            if isDemo { startReplay() } else { startListening() }
            startTimer()
        } else {
            stopListening()
            stopReplay()
            stopTimer()
            if !isEnabled {
                traffic.clear()
                selection = nil
                revision += 1
            }
        }
        refreshStatus()
    }

    private func startListening() {
        let p = UInt16(port)
        if listening.wantedPort != p { inbox.clear() }
        listening.want(p)
    }

    private func stopListening() {
        guard listening.wantedPort != nil else { return }
        listening.want(nil)
        inbox.clear()
    }

    private func startReplay() {
        if replay == nil {
            replay = Bundle.main.url(forResource: "DroneDemoFlight", withExtension: "json")
                .flatMap { try? Data(contentsOf: $0) }
                .flatMap { DroneLink.Replay(json: $0) }
        }
        guard replayStarted == nil else { return }
        replayStarted = Date()
        replayFed = -1
        feedReplay()
    }

    private func stopReplay() {
        replayStarted = nil
    }

    private func feedReplay() {
        guard let r = replay, let start = replayStarted else { return }
        let now = Date().timeIntervalSince(start)
        for f in r.frames(from: replayFed, to: now) { inbox.put(f) }
        replayFed = now
    }

    private func startTimer() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }
        t.tolerance = 0.05
        RunLoop.main.add(t, forMode: .common)
        timer = t
        tick()
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    /// Four times a second while listening: what arrived goes through the decoder, the old goes, the status line.
    private func tick() {
        // A port that was taken: try again every few seconds (the other app may have let it go).
        ticks += 1
        if !isDemo, shouldListen, !listening.isListening, ticks % 12 == 0 {
            listening.retry()
        }
        feedReplay()
        let now = Date()
        var changed = false
        for d in inbox.take() where traffic.receive(d, at: now).frames > 0 {
            changed = true
        }
        if traffic.prune(now) { changed = true }
        // Grey and fading follow the clock too: redraw at least once a second.
        if changed || now.timeIntervalSince(lastRedraw) >= 1 {
            lastRedraw = now
            revision += 1
        }
        if let s = selection, !exists(s) { selection = nil }
        lookUpGround()
        refreshStatus()
    }

    private var lastRedraw = Date.distantPast
    private var ticks = 0

    // MARK: - The selected drone's ground

    /// The ground's height under the selected drone (SatMap's elevation data, offline where downloaded), for its
    /// "≈ above ground"; nil until found.
    @Published private(set) var selectedGroundM: Double?
    private var groundFor: (sysid: UInt8, point: GeoPoint)?
    private lazy var groundLookup = HeightLookup { [weak self] p, e in
        guard let self = self, let g = self.groundFor, g.point == p else { return }
        if e != self.selectedGroundM { self.selectedGroundM = e }
    }

    /// Looks the ground up again once the selected drone has moved 20 m (or another is selected).
    private func lookUpGround() {
        guard case .drone(let id) = selection, let p = traffic.drones[id]?.point else {
            if groundFor != nil {
                groundFor = nil
                selectedGroundM = nil
                groundLookup.set(nil)
            }
            return
        }
        if let g = groundFor, g.sysid == id, Geo.distance(g.point, p) < 20 { return }
        if groundFor?.sysid != id { selectedGroundM = nil }
        groundFor = (id, p)
        groundLookup.set(p)
    }

    /// Selects a drone or aircraft (a tap on the map), or nothing.
    func select(_ s: Selection?) {
        selection = s
        lookUpGround()
    }

    private func exists(_ s: Selection) -> Bool {
        switch s {
        case .drone(let id): return traffic.drones[id]?.point != nil
        case .aircraft(let icao): return traffic.aircraft[icao] != nil
        }
    }

    private func refreshStatus() {
        let new: DroneLink.Status
        if !isEnabled {
            new = .off
        } else if !isDemo, shouldListen, !listening.isListening, let p = listening.failedPort {
            new = .failed(port: Int(p))
        } else {
            new = traffic.status(port: port, now: Date())
        }
        if new != status { status = new }
    }

    /// Datagrams handed from the socket's queue to the main thread; at most 2,000 waiting (a flood is dropped).
    private final class Inbox: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [[UInt8]] = []

        func put(_ d: [UInt8]) {
            lock.lock()
            if items.count < 2000 { items.append(d) }
            lock.unlock()
        }

        func take() -> [[UInt8]] {
            lock.lock()
            defer { items = []; lock.unlock() }
            return items
        }

        func clear() {
            lock.lock()
            items = []
            lock.unlock()
        }
    }
}

/// The walkthroughs' demo replay: the launch argument `-droneLinkDemo YES`, read only in the simulator and debug
/// builds (as `-testBuildSwitch`), so the IPA people install always listens for real.
enum DroneLinkConfig {
    static let isDemo: Bool = {
        #if DEBUG || targetEnvironment(simulator)
        return UserDefaults.standard.bool(forKey: "droneLinkDemo")
        #else
        return false
        #endif
    }()
}
