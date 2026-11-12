import Foundation

/// Drone link (drone mode): the drones and the ADS-B aircraft a ground station forwards over MAVLink, the rules for
/// when they go grey and go, the texts, and the distances in nautical miles. The same names and behaviour as Android's
/// core (`DroneLink.kt`); the decoder is `MAVLink`.
enum DroneLink {
    /// QGroundControl's "MAVLink Forwarding" default host is localhost:14445.
    static let defaultPort = 14445
    static let portRange = 1024...65535

    /// Saved app-wide.
    static let enabledKey = "droneLinkEnabled"
    static let portKey = "droneLinkPort"
    static let disclaimerKey = "droneLinkDisclaimerAccepted"

    /// A drone without a new position this long is greyed, and removed after `droneRemoveAfter`.
    static let droneStaleAfter: TimeInterval = 10
    static let droneRemoveAfter: TimeInterval = 60
    /// Aircraft fade and go as in QGroundControl.
    static let aircraftStaleAfter: TimeInterval = 15
    static let aircraftRemoveAfter: TimeInterval = 120
    /// The status says no data after this long without a datagram.
    static let silentAfter: TimeInterval = 10

    /// Aircraft below this height within this distance of you or a drone are highlighted.
    static let lowAltitudeFeet = 2000.0
    static let closeNauticalMiles = 5.0

    static let disclaimer = "Traffic awareness only. Not for navigation, separation or collision avoidance. Many aircraft "
        + "don't broadcast. Under SACAA Part 101 the remote pilot must give way to manned aircraft."

    /// A port typed in the settings, if it can be listened on.
    static func port(_ text: String) -> Int? {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.count <= 5, t.allSatisfy({ $0.isASCII && $0.isNumber }), let p = Int(t),
              portRange.contains(p) else { return nil }
        return p
    }

    /// A saved port, or the default for anything out of range.
    static func savedPort(_ value: Int?) -> Int {
        guard let v = value, portRange.contains(v) else { return defaultPort }
        return v
    }

    /// What to put in QGroundControl for these addresses.
    static func qgcInstructions(addresses: [String], port: Int) -> String {
        let host = addresses.first.map { "\($0):\(port)" } ?? "<phone IP>:\(port)"
        return "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name \(host), restart QGC"
    }

    // MARK: - Network interfaces

    /// How the settings name an interface the phone can be reached on ("Wi-Fi", "Hotspot"); nil for those that can't
    /// take a ground station's datagrams (loopback, mobile data, VPN tunnels, AWDL …).
    static func interfaceLabel(_ name: String) -> String? {
        if name == "en0" || name.hasPrefix("wlan") { return "Wi-Fi" }
        if name.hasPrefix("bridge") || name.hasPrefix("ap") || name.hasPrefix("swlan") { return "Hotspot" }
        if name.hasPrefix("en") || name.hasPrefix("eth") { return "Network" }
        return nil
    }

    /// Whether an IPv4 address (dotted) is one a ground station on the same Wi-Fi could send to: not loopback,
    /// link-local or unspecified.
    static func isReachableIPv4(_ address: String) -> Bool {
        let parts = address.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        if parts[0] == 127 || parts[0] == 0 || (parts[0] == 169 && parts[1] == 254) || parts[0] >= 224 { return false }
        return true
    }

    /// The addresses to show, Wi-Fi first, then the hotspot, then others; each once.
    static func addresses(_ interfaces: [(name: String, address: String)]) -> [(label: String, address: String)] {
        let order = ["Wi-Fi": 0, "Hotspot": 1, "Network": 2]
        var seen = Set<String>()
        return interfaces.compactMap { i -> (label: String, address: String)? in
            guard let label = interfaceLabel(i.name), isReachableIPv4(i.address), seen.insert(i.address).inserted else { return nil }
            return (label, i.address)
        }
        .enumerated()
        .sorted { (order[$0.element.label] ?? 9, $0.offset) < (order[$1.element.label] ?? 9, $1.offset) }
        .map(\.element)
    }

    // MARK: - Status

    enum Status: Equatable {
        case off
        case waiting(port: Int)
        case receiving(drones: Int, aircraft: Int)
        case silent(seconds: Int)
        case failed(port: Int)

        var text: String {
            switch self {
            case .off:
                return "Drone link is off"
            case .waiting(let port):
                return "Waiting for MAVLink on port \(port)…"
            case let .receiving(drones, aircraft):
                var s = drones == 0 ? "Receiving MAVLink" : "Receiving from \(drones) drone" + (drones == 1 ? "" : "s")
                if aircraft > 0 { s += " · \(aircraft) aircraft" }
                return s
            case .silent(let seconds):
                return "No data for \(seconds) s"
            case .failed(let port):
                return "Can't listen on port \(port): another app may be using it"
            }
        }
    }

    // MARK: - Distances

    /// "4.2 NM 225° SW": how far and which way `to` is from `from`.
    static func rangeAndBearing(from: GeoPoint, to: GeoPoint) -> String {
        let d = Geo.distance(from, to)
        if d < 0.05 * Aviation.metresPerNauticalMile { return Aviation.nauticalMiles(d) }
        let b = Int(Geo.bearing(from: from, to: to).rounded()) % 360
        return "\(Aviation.nauticalMiles(d)) \(String(format: "%03d", b))° \(Aviation.compass(from: from, to: to))"
    }

    /// The nearest aerodromes to a spot (not helipads unless nothing else is near), nearest first, within `withinNM`.
    static func nearestAerodromes(_ index: AviationIndex, to p: GeoPoint, count: Int = 3,
                                  withinNM: Double = 30) -> [(place: AviationPoint, meters: Double)] {
        let limit = withinNM * Aviation.metresPerNauticalMile
        let latPad = limit / 111_000
        let lonPad = min(180, latPad / max(0.05, cos(p.lat * .pi / 180)))
        let b = GeoBounds(minLat: max(-90, p.lat - latPad), minLon: p.lon - lonPad,
                          maxLat: min(90, p.lat + latPad), maxLon: p.lon + lonPad)
        let found = index.points(in: b) { $0.kind.isAerodrome }
            .map { (place: index.points[$0], meters: Geo.distance(p, index.points[$0].point)) }
            .filter { $0.meters <= limit }
            .sorted { $0.meters < $1.meters }
        let fields = found.filter { $0.place.kind != .heliport }
        return Array((fields.isEmpty ? found : fields).prefix(count))
    }

    /// Whether an aircraft is low and close: within `closeNauticalMiles` of one of the reference points (you, a drone)
    /// and less than `lowAltitudeFeet` above that point's ground (its elevation when known, else sea level). An
    /// aircraft whose altitude isn't valid counts as low.
    static func isLowAndClose(_ a: Aircraft, references: [(point: GeoPoint, groundM: Double?)]) -> Bool {
        for r in references {
            guard Geo.distance(r.point, a.position) <= closeNauticalMiles * Aviation.metresPerNauticalMile else { continue }
            guard let alt = a.altitudeM else { return true }
            if alt - (r.groundM ?? 0) < lowAltitudeFeet * DistanceUnit.metresPerFoot { return true }
        }
        return false
    }

    // MARK: - Aircraft

    /// The map's icon for an ADS-B emitter type.
    enum AircraftKind: String, CaseIterable {
        case airliner, light, helicopter, glider, balloon, uav, other

        init(emitterType: Int) {
            switch emitterType {
            case 2, 3, 4, 5, 6: self = .airliner   // small, large, high-vortex, heavy, high-performance
            case 1, 12: self = .light              // light, ultralight
            case 7: self = .helicopter             // rotorcraft
            case 9: self = .glider
            case 10: self = .balloon               // lighter than air
            case 14: self = .uav
            default: self = .other                 // unknown, parachute, space, ground vehicles, obstacles
            }
        }

        var title: String {
            switch self {
            case .airliner: return "Airliner"
            case .light: return "Light aircraft"
            case .helicopter: return "Helicopter"
            case .glider: return "Glider"
            case .balloon: return "Balloon"
            case .uav: return "Drone (UAV)"
            case .other: return "Aircraft"
            }
        }

        /// Balloons and the generic symbol aren't turned to the heading.
        var rotates: Bool { self != .balloon && self != .other }
    }

    /// An aircraft from ADSB_VEHICLE, with what is valid of it.
    struct Aircraft: Equatable {
        let icaoAddress: UInt32
        var position: GeoPoint
        var altitudeM: Double?
        /// 0 pressure (QNH), 1 geometric.
        var altitudeType: Int
        var headingDeg: Double?
        var speedMs: Double?
        var climbMs: Double?
        var callsign: String?
        var squawk: Int?
        var emitterType: Int
        var tslcS: Int
        /// The drone (system id) whose receiver heard it.
        var viaSysid: UInt8
        var lastSeen: Date

        var icaoHex: String { String(format: "%06X", icaoAddress) }
        var kind: AircraftKind { AircraftKind(emitterType: emitterType) }
        /// The callsign, or the ICAO address in hex.
        var label: String { callsign ?? icaoHex }

        func isStale(_ now: Date) -> Bool { now.timeIntervalSince(lastSeen) > aircraftStaleAfter }

        /// "9,000 ft" (to the nearest 100 ft), nil if unknown.
        var altitudeText: String? {
            altitudeM.map { DroneLink.feet($0) }
        }

        /// "250 kt", nil if unknown.
        var speedText: String? {
            speedMs.map { "\(Int(($0 / 0.514444).rounded())) kt" }
        }

        /// "↑", "↓" when climbing or descending faster than 100 ft/min, else nil.
        var climbArrow: String? {
            guard let c = climbMs, abs(c) >= 0.508 else { return nil }
            return c > 0 ? "↑" : "↓"
        }
    }

    /// A speed in the app's distance unit: "35 km/h", "22 mph", "19 kt".
    static func speed(_ ms: Double, _ unit: DistanceUnit) -> String {
        switch unit {
        case .metric: return "\(Int((ms * 3.6).rounded())) km/h"
        case .imperial: return "\(Int((ms * 3600 / DistanceUnit.metresPerMile).rounded())) mph"
        case .nautical: return "\(Int((ms * 3600 / DistanceUnit.metresPerNauticalMile).rounded())) kt"
        }
    }

    /// A climb (up positive) in the app's height unit: "+0.5 m/s", "−1.2 m/s", "0.0 m/s"; "+100 ft/min" (to 10 ft/min).
    static func climb(_ ms: Double, _ unit: HeightUnit) -> String {
        let v: Double, text: String
        switch unit {
        case .metres:
            v = (ms * 10).rounded() / 10
            text = String(format: "%.1f m/s", abs(v))
        case .feet:
            v = (ms / DistanceUnit.metresPerFoot * 60 / 10).rounded() * 10
            text = "\(grouped(Int(abs(v)))) ft/min"
        }
        return (v > 0 ? "+" : v < 0 ? "\u{2212}" : "") + text
    }

    /// "9,000 ft": metres in feet, to the nearest 100 ft (to the nearest 10 below 1,000 ft).
    static func feet(_ metres: Double) -> String {
        let ft = metres / DistanceUnit.metresPerFoot
        let step = abs(ft) < 1000 ? 10.0 : 100.0
        let v = Int((ft / step).rounded() * step)
        return grouped(v) + " ft"
    }

    /// "1,200" (thousands separated by commas, whatever the locale, as on Android).
    static func grouped(_ v: Int) -> String {
        let digits = String(abs(v))
        var out = ""
        for (i, c) in digits.enumerated() {
            if i > 0, (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(c)
        }
        return (v < 0 ? "-" : "") + out
    }

    // MARK: - Drones

    struct Drone: Equatable {
        let sysid: UInt8
        /// The colour slot, in the order drones were first heard (0, 1, 2 …).
        let colourIndex: Int
        var heartbeat: MAVLink.Heartbeat?
        var position: MAVLink.GlobalPositionInt?
        var positionAt: Date?
        var vfr: MAVLink.VfrHud?
        var batteryVoltageV: Double?
        var batteryRemainingPct: Int?
        var gps: MAVLink.GpsRawInt?
        var home: MAVLink.HomePosition?
        let firstSeen: Date
        var lastSeen: Date

        /// "Drone 1" (the MAVLink system id).
        var label: String { "Drone \(sysid)" }
        var point: GeoPoint? { position.map { GeoPoint(lat: $0.latDeg, lon: $0.lonDeg) } }
        var homePoint: GeoPoint? { home.map { GeoPoint(lat: $0.latDeg, lon: $0.lonDeg) } }

        /// Grey: no position, or none for `droneStaleAfter`.
        func isStale(_ now: Date) -> Bool {
            guard let t = positionAt else { return true }
            return now.timeIntervalSince(t) > droneStaleAfter
        }

        /// Heading: GLOBAL_POSITION_INT's, else VFR_HUD's.
        var headingDeg: Double? {
            position?.headingDeg ?? vfr.map { Double($0.headingDeg) }
        }

        /// Ground speed: VFR_HUD's, else from GLOBAL_POSITION_INT's velocity.
        var groundSpeedMs: Double? {
            vfr?.groundspeedMs ?? position.map { hypot($0.vxMs, $0.vyMs) }
        }

        /// Climb (up positive): VFR_HUD's, else GLOBAL_POSITION_INT's.
        var climbMs: Double? {
            vfr?.climbMs ?? position.map { -$0.vzMs }
        }

        /// The ground's height where it took off (its height above sea level less its height above take-off).
        var takeOffGroundM: Double? {
            position.map { $0.altMslM - $0.relativeAltM } ?? home?.altMslM
        }

        /// "No fix", "2D fix", "3D fix", "DGPS", "RTK float", "RTK fixed".
        var fixText: String? {
            guard let g = gps else { return nil }
            switch g.fixType {
            case 0, 1: return "No fix"
            case 2: return "2D fix"
            case 3: return "3D fix"
            case 4: return "DGPS"
            case 5: return "RTK float"
            case 6: return "RTK fixed"
            case 7: return "Static"
            case 8: return "PPP"
            default: return "Fix \(g.fixType)"
            }
        }
    }

    /// What one datagram did (for the status line and tests).
    struct Received: Equatable {
        var frames = 0
        var droneMessages = 0
        var aircraftMessages = 0
    }

    /// The drones (by system id) and aircraft (by ICAO address) heard, kept as the rules say. Not thread-safe: use it
    /// on one queue.
    final class Traffic {
        private(set) var drones: [UInt8: Drone] = [:]
        private(set) var aircraft: [UInt32: Aircraft] = [:]
        /// When the last datagram with a known frame came.
        private(set) var lastReceived: Date?
        /// System ids that sent a ground station's heartbeat: never drones.
        private(set) var groundStations = Set<UInt8>()
        private var nextColour = 0

        init() {}

        /// Every frame in a datagram.
        @discardableResult
        func receive(_ datagram: [UInt8], at now: Date) -> Received {
            var r = Received()
            for f in MAVLink.decode(datagram) {
                r.frames += 1
                switch apply(f, at: now) {
                case .drone: r.droneMessages += 1
                case .aircraft: r.aircraftMessages += 1
                case .ignored: break
                }
            }
            if r.frames > 0 { lastReceived = now }
            return r
        }

        enum Applied { case drone, aircraft, ignored }

        /// One frame.
        @discardableResult
        func apply(_ f: MAVLink.Frame, at now: Date) -> Applied {
            guard let message = f.message else { return .ignored }
            if case .heartbeat(let h) = message, h.isGcs {
                if drones[f.sysid] == nil { groundStations.insert(f.sysid) }
                return .ignored
            }
            if case .adsbVehicle(let a) = message {
                return applyAircraft(a, via: f.sysid, at: now) ? .aircraft : .ignored
            }
            guard !groundStations.contains(f.sysid) || drones[f.sysid] != nil else { return .ignored }
            var d = drones[f.sysid] ?? {
                defer { nextColour += 1 }
                return Drone(sysid: f.sysid, colourIndex: nextColour, firstSeen: now, lastSeen: now)
            }()
            d.lastSeen = now
            switch message {
            case .heartbeat(let h):
                d.heartbeat = h
            case .globalPositionInt(let p):
                d.position = p
                d.positionAt = now
            case .vfrHud(let v):
                d.vfr = v
            case .sysStatus(let s):
                if let v = s.batteryVoltageV { d.batteryVoltageV = v }
                if let p = s.batteryRemainingPct { d.batteryRemainingPct = p }
            case .batteryStatus(let b):
                if let p = b.batteryRemainingPct { d.batteryRemainingPct = p }
            case .gpsRawInt(let g):
                d.gps = g
            case .homePosition(let h):
                d.home = h
            case .adsbVehicle:
                break
            }
            drones[f.sysid] = d
            return .drone
        }

        private func applyAircraft(_ m: MAVLink.AdsbVehicle, via sysid: UInt8, at now: Date) -> Bool {
            let v = m.valid
            var a = aircraft[m.icaoAddress]
            if v.coords {
                let p = GeoPoint(lat: m.latDeg, lon: m.lonDeg)
                guard (-90...90).contains(p.lat), (-180...180).contains(p.lon) else { return false }
                if a == nil {
                    a = Aircraft(icaoAddress: m.icaoAddress, position: p, altitudeM: nil, altitudeType: m.altitudeType,
                                 headingDeg: nil, speedMs: nil, climbMs: nil, callsign: nil, squawk: nil,
                                 emitterType: m.emitterType, tslcS: m.tslcS, viaSysid: sysid, lastSeen: now)
                }
                a?.position = p
                a?.lastSeen = now
            }
            // Without a position yet there is nothing to show.
            guard var b = a else { return false }
            if v.altitude {
                b.altitudeM = m.altitudeM
                b.altitudeType = m.altitudeType
            }
            if v.heading { b.headingDeg = m.headingDeg }
            if v.velocity { b.speedMs = m.horVelocityMs }
            if v.verticalVelocity { b.climbMs = m.verVelocityMs }
            if v.callsign, let c = m.callsign { b.callsign = c }
            if v.squawk { b.squawk = m.squawk }
            b.emitterType = m.emitterType
            b.tslcS = m.tslcS
            b.viaSysid = sysid
            aircraft[m.icaoAddress] = b
            return true
        }

        /// Removes drones and aircraft gone too long; true if any went.
        @discardableResult
        func prune(_ now: Date) -> Bool {
            let d0 = drones.count, a0 = aircraft.count
            drones = drones.filter { now.timeIntervalSince($0.value.positionAt ?? $0.value.lastSeen) <= droneRemoveAfter }
            aircraft = aircraft.filter { now.timeIntervalSince($0.value.lastSeen) <= aircraftRemoveAfter }
            return drones.count != d0 || aircraft.count != a0
        }

        /// Forgets everything (the link was switched off).
        func clear() {
            drones = [:]
            aircraft = [:]
            lastReceived = nil
            groundStations = []
            nextColour = 0
        }

        /// The status line while listening on `port`.
        func status(port: Int, now: Date) -> Status {
            guard let last = lastReceived else { return .waiting(port: port) }
            let quiet = now.timeIntervalSince(last)
            if quiet > silentAfter { return .silent(seconds: Int(quiet)) }
            return .receiving(drones: drones.count, aircraft: aircraft.count)
        }

        /// Drones in system-id order.
        var sortedDrones: [Drone] { drones.values.sorted { $0.sysid < $1.sysid } }
    }

    // MARK: - Demo replay

    /// A recorded flight (docs/mavlink/demo_flight.json) for the walkthroughs: frames at time offsets, fed through
    /// the same decoder as the network's datagrams, round and round.
    struct Replay {
        let duration: TimeInterval
        let frames: [(t: TimeInterval, bytes: [UInt8])]

        init?(json: Data) {
            guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
                  let list = root["frames"] as? [[String: Any]], !list.isEmpty else { return nil }
            var frames: [(t: TimeInterval, bytes: [UInt8])] = []
            for f in list {
                guard let t = (f["t"] as? NSNumber)?.doubleValue, let hex = f["hex"] as? String,
                      let bytes = DroneLink.bytes(hex: hex) else { return nil }
                frames.append((t, bytes))
            }
            self.frames = frames.sorted { $0.t < $1.t }
            let last = self.frames.last?.t ?? 0
            duration = max((root["durationS"] as? NSNumber)?.doubleValue ?? last, last) + 1
        }

        /// The frames due after `from` up to and including `to` (seconds since the replay began), looping. Start
        /// with `from` below 0 to have those at 0.
        func frames(from: TimeInterval, to: TimeInterval) -> [[UInt8]] {
            guard to > from, duration > 0 else { return [] }
            var out: [[UInt8]] = []
            var lap = max(0, floor(from / duration))
            while lap * duration <= to {
                for f in frames {
                    let t = lap * duration + f.t
                    if t > from, t <= to { out.append(f.bytes) }
                }
                lap += 1
            }
            return out
        }
    }

    /// Bytes from hex ("fd09…"), nil if it isn't hex.
    static func bytes(hex: String) -> [UInt8]? {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(chars.count / 2)
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 97...102: return c - 87
            case 65...70: return c - 55
            default: return nil
            }
        }
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }
}
