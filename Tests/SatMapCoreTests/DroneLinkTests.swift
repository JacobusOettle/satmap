import Dispatch
import Foundation
#if canImport(Glibc)
import Glibc
#endif
import XCTest
@testable import SatMapCore

/// Drone link: the MAVLink decoder against the shared fixture (Fixtures/mavlink/cases.json, which the Android core's
/// tests read too), the traffic rules, the texts, the demo replay and the UDP listener over the loopback.
final class DroneLinkTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/mavlink/cases.json", withExtension: nil))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func cases() throws -> [[String: Any]] {
        try XCTUnwrap(try fixture()["cases"] as? [[String: Any]])
    }

    private func datagram(_ c: [String: Any]) throws -> [UInt8] {
        try XCTUnwrap(DroneLink.bytes(hex: try XCTUnwrap(c["hex"] as? String)))
    }

    private func named(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(try cases().first { $0["name"] as? String == name }, name)
    }

    // MARK: - Decoder

    func testMessageTable() throws {
        let table = try XCTUnwrap(try fixture()["messages"] as? [String: [String: Any]])
        XCTAssertEqual(table.count, MAVLink.messages.count)
        for (id, m) in table {
            let info = try XCTUnwrap(MAVLink.messages[try XCTUnwrap(UInt32(id))], id)
            XCTAssertEqual(info.name, m["name"] as? String)
            XCTAssertEqual(Int(info.crcExtra), m["crcExtra"] as? Int, info.name)
            XCTAssertEqual(info.v1Length, m["v1Length"] as? Int, info.name)
            XCTAssertEqual(info.v2Length, m["v2Length"] as? Int, info.name)
        }
    }

    func testCRC() {
        // CRC-16/MCRF4XX check value.
        XCTAssertEqual(MAVLink.crc(Array("123456789".utf8)), 0x6F91)
    }

    func testFixtureCases() throws {
        let tolerance = try XCTUnwrap(try fixture()["tolerance"] as? Double)
        let all = try cases()
        XCTAssertGreaterThanOrEqual(all.count, 39)
        for c in all {
            let name = try XCTUnwrap(c["name"] as? String)
            let expect = try XCTUnwrap(c["expect"] as? [[String: Any]], name)
            let frames = MAVLink.decode(try datagram(c))
            XCTAssertEqual(frames.count, expect.count, "\(name): frames")
            for (f, e) in zip(frames, expect) {
                XCTAssertEqual(Int(f.msgId), e["msgId"] as? Int, name)
                XCTAssertEqual(f.version, e["version"] as? Int, name)
                XCTAssertEqual(f.signed, e["signed"] as? Bool, name)
                XCTAssertEqual(Int(f.sysid), e["sysid"] as? Int, name)
                XCTAssertEqual(Int(f.compid), e["compid"] as? Int, name)
                XCTAssertEqual(f.payload.count, MAVLink.messages[f.msgId]?.v2Length, "\(name): zero-extended")
                let decoded = try XCTUnwrap(e["decoded"] as? [String: Any], name)
                let message = try XCTUnwrap(f.message, name)
                try check(message, decoded, tolerance: tolerance, name)
            }
        }
    }

    /// The decoded values as the fixture names them.
    private func values(_ m: MAVLink.Message) -> [String: Any?] {
        switch m {
        case .heartbeat(let h):
            return ["type": h.type, "autopilot": h.autopilot, "baseMode": h.baseMode, "customMode": Int(h.customMode),
                    "systemStatus": h.systemStatus, "armed": h.armed, "isGcs": h.isGcs]
        case .sysStatus(let s):
            return ["batteryVoltageV": s.batteryVoltageV, "batteryCurrentA": s.batteryCurrentA,
                    "batteryRemainingPct": s.batteryRemainingPct]
        case .gpsRawInt(let g):
            return ["fixType": g.fixType, "satellitesVisible": g.satellitesVisible, "latDeg": g.latDeg, "lonDeg": g.lonDeg,
                    "altMslM": g.altMslM]
        case .globalPositionInt(let p):
            return ["timeBootMs": Int(p.timeBootMs), "latDeg": p.latDeg, "lonDeg": p.lonDeg, "altMslM": p.altMslM,
                    "relativeAltM": p.relativeAltM, "vxMs": p.vxMs, "vyMs": p.vyMs, "vzMs": p.vzMs, "headingDeg": p.headingDeg]
        case .vfrHud(let v):
            return ["airspeedMs": v.airspeedMs, "groundspeedMs": v.groundspeedMs, "headingDeg": v.headingDeg,
                    "throttlePct": v.throttlePct, "altM": v.altM, "climbMs": v.climbMs]
        case .batteryStatus(let b):
            return ["id": b.id, "batteryRemainingPct": b.batteryRemainingPct]
        case .homePosition(let h):
            return ["latDeg": h.latDeg, "lonDeg": h.lonDeg, "altMslM": h.altMslM]
        case .adsbVehicle(let a):
            let v = a.valid
            return ["icaoAddress": Int(a.icaoAddress), "icaoHex": a.icaoHex, "latDeg": a.latDeg, "lonDeg": a.lonDeg,
                    "altitudeType": a.altitudeType, "altitudeM": a.altitudeM, "headingDeg": a.headingDeg,
                    "horVelocityMs": a.horVelocityMs, "verVelocityMs": a.verVelocityMs, "callsign": a.callsign,
                    "emitterType": a.emitterType, "tslcS": a.tslcS, "flags": a.flags, "squawk": a.squawk,
                    "valid": ["coords": v.coords, "altitude": v.altitude, "heading": v.heading, "velocity": v.velocity,
                              "callsign": v.callsign, "squawk": v.squawk, "verticalVelocity": v.verticalVelocity] as [String: Any]]
        }
    }

    private func check(_ m: MAVLink.Message, _ expected: [String: Any], tolerance: Double, _ name: String) throws {
        let got = values(m)
        XCTAssertEqual(Set(got.keys), Set(expected.keys), "\(name): fields")
        for (key, want) in expected {
            let have = got[key] ?? nil
            switch want {
            case is NSNull:
                XCTAssertNil(have, "\(name).\(key)")
            case let w as String:
                XCTAssertEqual(have as? String, w, "\(name).\(key)")
            case let w as [String: Any]:
                let h = try XCTUnwrap(have as? [String: Any], "\(name).\(key)")
                for (k, v) in w { XCTAssertEqual(h[k] as? Bool, v as? Bool, "\(name).\(key).\(k)") }
            case let w as NSNumber:
                if let b = have as? Bool {
                    XCTAssertEqual(b, w.boolValue, "\(name).\(key)")
                } else if let i = have as? Int {
                    XCTAssertEqual(Double(i), w.doubleValue, "\(name).\(key)")
                } else {
                    let d = try XCTUnwrap(have as? Double, "\(name).\(key) is \(String(describing: have))")
                    XCTAssertEqual(d, w.doubleValue, accuracy: max(tolerance, abs(w.doubleValue) * 1e-9), "\(name).\(key)")
                }
            default:
                XCTFail("\(name).\(key): unexpected \(want)")
            }
        }
    }

    func testTruncatedPayloadIsShorterOnTheWire() throws {
        let c = try named("v2-truncated-payload")
        let bytes = try datagram(c)
        XCTAssertEqual(Int(bytes[1]), c["payloadLength"] as? Int)
        XCTAssertLessThan(Int(bytes[1]), 28)
        XCTAssertEqual(MAVLink.decode(bytes).first?.payload.count, 28)
    }

    func testEveryPrefixAndRandomGarbageIsSafe() throws {
        // A frame cut anywhere decodes nothing past the cut and never crashes; noise never crashes either.
        let hb = try datagram(try named("two-frames-one-datagram"))
        for n in 0...hb.count { _ = MAVLink.decode(Array(hb.prefix(n))) }
        var rng = SystemRandomNumberGenerator()
        for _ in 0..<300 {
            var noise = (0..<Int.random(in: 0...300, using: &rng)).map { _ in UInt8.random(in: 0...255, using: &rng) }
            if !noise.isEmpty { noise[0] = Bool.random() ? 0xFD : 0xFE }
            _ = MAVLink.decode(noise)
        }
    }

    func testCallsignTrimming() {
        XCTAssertEqual(MAVLink.Message.callsign(Array("ZSRVK   ".utf8) + [0]), "ZSRVK")
        XCTAssertNil(MAVLink.Message.callsign([0x20, 0x20, 0, 0x41]))
        XCTAssertEqual(MAVLink.Message.callsign([0x41, 0xC3, 0]), "A\u{FFFD}")
    }

    // MARK: - Traffic

    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testGroundStationHeartbeatNeverMakesADrone() throws {
        let traffic = DroneLink.Traffic()
        let c = try named("gcs-heartbeat")
        XCTAssertEqual(c["tracksDrone"] as? Bool, false)
        traffic.receive(try datagram(c), at: t0)
        XCTAssertTrue(traffic.drones.isEmpty)
        XCTAssertEqual(traffic.groundStations, [255])
        XCTAssertNotNil(traffic.lastReceived, "a GCS heartbeat is still data")
        // A drone heartbeat does.
        traffic.receive(try datagram(try named("v2-heartbeat-armed")), at: t0)
        XCTAssertEqual(traffic.drones[1]?.heartbeat?.armed, true)
    }

    func testDronesAreTrackedPerSystemId() throws {
        let traffic = DroneLink.Traffic()
        traffic.receive(try datagram(try named("v2-global-position-int")), at: t0)
        traffic.receive(try datagram(try named("second-drone")), at: t0)
        XCTAssertEqual(traffic.sortedDrones.map(\.sysid), [1, 2])
        XCTAssertEqual(traffic.sortedDrones.map(\.colourIndex), [0, 1])
        XCTAssertEqual(traffic.drones[1]?.label, "Drone 1")
        XCTAssertEqual(traffic.drones[2]?.position?.relativeAltM ?? 0, 25, accuracy: 1e-6)
        XCTAssertEqual(traffic.status(port: 14445, now: t0), .receiving(drones: 2, aircraft: 0))
    }

    func testDroneTelemetryIsMerged() throws {
        let traffic = DroneLink.Traffic()
        for name in ["v2-heartbeat-armed", "v2-sys-status", "v2-gps-raw-int", "v2-global-position-int", "v2-vfr-hud",
                     "v2-battery-status", "v2-home-position"] {
            traffic.receive(try datagram(try named(name)), at: t0)
        }
        let d = try XCTUnwrap(traffic.drones[1])
        XCTAssertEqual(d.batteryVoltageV ?? 0, 16.08, accuracy: 1e-9)
        XCTAssertEqual(d.batteryRemainingPct, 86, "BATTERY_STATUS came after SYS_STATUS")
        XCTAssertEqual(d.fixText, "3D fix")
        XCTAssertEqual(d.gps?.satellitesVisible, 17)
        XCTAssertEqual(d.groundSpeedMs ?? 0, 9.75, accuracy: 1e-9)
        XCTAssertEqual(d.climbMs ?? 0, 0.5, accuracy: 1e-9)
        XCTAssertEqual(d.headingDeg ?? 0, 50.2, accuracy: 1e-9)
        XCTAssertEqual(d.takeOffGroundM ?? 0, 142, accuracy: 1e-6)
        XCTAssertEqual(d.homePoint?.lat ?? 0, -33.918292, accuracy: 1e-7)
        // Unknown battery values don't wipe known ones.
        traffic.receive(try datagram(try named("v2-sys-status-unknown-battery")), at: t0)
        XCTAssertEqual(traffic.drones[1]?.batteryVoltageV ?? 0, 16.08, accuracy: 1e-9)
    }

    func testStaleAndRemovalRules() throws {
        let traffic = DroneLink.Traffic()
        traffic.receive(try datagram(try named("v2-global-position-int")), at: t0)
        traffic.receive(try datagram(try named("adsb-saa312")), at: t0)
        let d = try XCTUnwrap(traffic.drones[1])
        let a = try XCTUnwrap(traffic.aircraft[0x00A1B2])
        XCTAssertFalse(d.isStale(t0.addingTimeInterval(10)))
        XCTAssertTrue(d.isStale(t0.addingTimeInterval(10.5)))
        XCTAssertFalse(a.isStale(t0.addingTimeInterval(15)))
        XCTAssertTrue(a.isStale(t0.addingTimeInterval(15.5)))
        // A heartbeat keeps the drone in the list but doesn't make its position fresh.
        traffic.receive(try datagram(try named("v2-heartbeat-armed")), at: t0.addingTimeInterval(30))
        XCTAssertTrue(try XCTUnwrap(traffic.drones[1]).isStale(t0.addingTimeInterval(30)))
        XCTAssertFalse(traffic.prune(t0.addingTimeInterval(60)))
        XCTAssertTrue(traffic.prune(t0.addingTimeInterval(60.5)))
        XCTAssertNil(traffic.drones[1], "gone 60 s after its last position")
        XCTAssertNotNil(traffic.aircraft[0x00A1B2])
        traffic.prune(t0.addingTimeInterval(120))
        XCTAssertNotNil(traffic.aircraft[0x00A1B2])
        traffic.prune(t0.addingTimeInterval(120.5))
        XCTAssertNil(traffic.aircraft[0x00A1B2], "gone after 120 s")
        // A drone with no position at all stays while it keeps talking.
        traffic.receive(try datagram(try named("v2-heartbeat-armed")), at: t0.addingTimeInterval(200))
        traffic.prune(t0.addingTimeInterval(250))
        XCTAssertNotNil(traffic.drones[1])
        XCTAssertTrue(try XCTUnwrap(traffic.drones[1]).isStale(t0.addingTimeInterval(200)))
    }

    func testAircraft() throws {
        let traffic = DroneLink.Traffic()
        traffic.receive(try datagram(try named("adsb-three-in-one-datagram")), at: t0)
        XCTAssertEqual(Set(traffic.aircraft.keys), [0x00A1B2, 0x00B3C4, 0x00C5D6])
        let saa = try XCTUnwrap(traffic.aircraft[0x00A1B2])
        XCTAssertEqual(saa.label, "SAA312")
        XCTAssertEqual(saa.kind, .airliner)
        XCTAssertEqual(saa.altitudeText, "9,000 ft")
        XCTAssertEqual(saa.speedText, "250 kt")
        XCTAssertEqual(saa.climbArrow, "↓")
        XCTAssertEqual(saa.squawk, 4521)
        XCTAssertEqual(traffic.aircraft[0x00B3C4]?.kind, .light)
        XCTAssertNil(traffic.aircraft[0x00B3C4]?.climbArrow)
        XCTAssertEqual(traffic.aircraft[0x00C5D6]?.kind, .helicopter)
        // Without callsign or heading: labelled by its ICAO address, no heading.
        traffic.receive(try datagram(try named("adsb-no-callsign-no-heading")), at: t0)
        let anon = try XCTUnwrap(traffic.aircraft[0x00D7E8])
        XCTAssertEqual(anon.label, "00D7E8")
        XCTAssertNil(anon.headingDeg)
        XCTAssertNil(anon.climbMs)
        XCTAssertEqual(anon.altitudeText, "3,500 ft")
        XCTAssertTrue(traffic.drones.isEmpty, "ADS-B reports don't make a drone")
        XCTAssertEqual(traffic.status(port: 14445, now: t0), .receiving(drones: 0, aircraft: 4))
    }

    func testAircraftKinds() {
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 0), .other)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 5), .airliner)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 12), .light)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 9), .glider)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 10), .balloon)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 14), .uav)
        XCTAssertEqual(DroneLink.AircraftKind(emitterType: 19), .other)
        XCTAssertFalse(DroneLink.AircraftKind.balloon.rotates)
    }

    func testLowAndClose() throws {
        let traffic = DroneLink.Traffic()
        traffic.receive(try datagram(try named("adsb-three-in-one-datagram")), at: t0)
        let farm = GeoPoint(lat: -33.918292, lon: 18.921204)
        let refs: [(point: GeoPoint, groundM: Double?)] = [(farm, 142)]
        // The helicopter (800 ft, 2.9 NM) and the light aircraft (1,500 ft, about 4 NM) are; the airliner isn't.
        XCTAssertTrue(DroneLink.isLowAndClose(try XCTUnwrap(traffic.aircraft[0x00C5D6]), references: refs))
        XCTAssertTrue(DroneLink.isLowAndClose(try XCTUnwrap(traffic.aircraft[0x00B3C4]), references: refs))
        XCTAssertFalse(DroneLink.isLowAndClose(try XCTUnwrap(traffic.aircraft[0x00A1B2]), references: refs))
        XCTAssertFalse(DroneLink.isLowAndClose(try XCTUnwrap(traffic.aircraft[0x00C5D6]), references: []))
        // Far away, nothing is.
        let far: [(point: GeoPoint, groundM: Double?)] = [(GeoPoint(lat: -25, lon: 31), nil)]
        XCTAssertFalse(DroneLink.isLowAndClose(try XCTUnwrap(traffic.aircraft[0x00C5D6]), references: far))
        // Unknown altitude counts as low.
        var a = try XCTUnwrap(traffic.aircraft[0x00A1B2])
        a.altitudeM = nil
        a.position = farm
        XCTAssertTrue(DroneLink.isLowAndClose(a, references: refs))
    }

    func testStatusTexts() throws {
        let traffic = DroneLink.Traffic()
        XCTAssertEqual(traffic.status(port: 14445, now: t0).text, "Waiting for MAVLink on port 14445…")
        traffic.receive(try datagram(try named("v2-global-position-int")), at: t0)
        XCTAssertEqual(traffic.status(port: 14445, now: t0.addingTimeInterval(5)).text, "Receiving from 1 drone")
        XCTAssertEqual(traffic.status(port: 14445, now: t0.addingTimeInterval(10.5)).text, "No data for 10 s")
        XCTAssertEqual(traffic.status(port: 14445, now: t0.addingTimeInterval(42.2)).text, "No data for 42 s")
        XCTAssertEqual(DroneLink.Status.receiving(drones: 2, aircraft: 3).text, "Receiving from 2 drones · 3 aircraft")
        XCTAssertEqual(DroneLink.Status.receiving(drones: 0, aircraft: 1).text, "Receiving MAVLink · 1 aircraft")
        XCTAssertEqual(DroneLink.Status.off.text, "Drone link is off")
        // Garbage alone isn't data.
        let quiet = DroneLink.Traffic()
        quiet.receive([1, 2, 3, 0xFE], at: t0)
        XCTAssertNil(quiet.lastReceived)
    }

    // MARK: - Settings and texts

    func testPorts() {
        XCTAssertEqual(DroneLink.port("14445"), 14445)
        XCTAssertEqual(DroneLink.port(" 1024 "), 1024)
        XCTAssertEqual(DroneLink.port("65535"), 65535)
        XCTAssertNil(DroneLink.port("1023"))
        XCTAssertNil(DroneLink.port("65536"))
        XCTAssertNil(DroneLink.port("14 445"))
        XCTAssertNil(DroneLink.port("-1"))
        XCTAssertNil(DroneLink.port(""))
        XCTAssertNil(DroneLink.port("١٤٤٤٥")) // only ASCII digits
        XCTAssertEqual(DroneLink.savedPort(nil), 14445)
        XCTAssertEqual(DroneLink.savedPort(80), 14445)
        XCTAssertEqual(DroneLink.savedPort(14550), 14550)
    }

    func testAddressesAndInstructions() {
        let list = DroneLink.addresses([("lo0", "127.0.0.1"), ("pdp_ip0", "10.12.0.4"), ("bridge100", "172.20.10.1"),
                                        ("en0", "192.168.1.23"), ("utun2", "10.8.0.2"), ("en2", "169.254.3.3"),
                                        ("en0", "192.168.1.23")])
        XCTAssertEqual(list.map(\.label), ["Wi-Fi", "Hotspot"])
        XCTAssertEqual(list.map(\.address), ["192.168.1.23", "172.20.10.1"])
        XCTAssertEqual(DroneLink.qgcInstructions(addresses: ["192.168.1.23"], port: 14445),
                       "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name 192.168.1.23:14445, restart QGC")
        XCTAssertTrue(DroneLink.qgcInstructions(addresses: [], port: 14550).contains("<phone IP>:14550"))
        XCTAssertFalse(DroneLink.isReachableIPv4("0.0.0.0"))
        XCTAssertFalse(DroneLink.isReachableIPv4("224.0.0.1"))
        XCTAssertTrue(DroneLink.isReachableIPv4("10.0.0.5"))
    }

    func testDistancesAndNumbers() {
        let farm = GeoPoint(lat: -33.918292, lon: 18.921204)
        let north = GeoPoint(lat: -33.870, lon: 18.935)
        XCTAssertEqual(DroneLink.rangeAndBearing(from: farm, to: north), "3.0 NM 013° NNE")
        XCTAssertEqual(DroneLink.rangeAndBearing(from: farm, to: farm), "0.0 NM")
        XCTAssertEqual(DroneLink.feet(2743.2), "9,000 ft")
        XCTAssertEqual(DroneLink.feet(243.84), "800 ft")
        XCTAssertEqual(DroneLink.grouped(1_234_567), "1,234,567")
        XCTAssertEqual(DroneLink.grouped(-1200), "-1,200")
        XCTAssertEqual(DroneLink.grouped(999), "999")
        XCTAssertEqual(DroneLink.speed(9.75, .metric), "35 km/h")
        XCTAssertEqual(DroneLink.speed(9.75, .imperial), "22 mph")
        XCTAssertEqual(DroneLink.speed(9.75, .nautical), "19 kt")
        XCTAssertEqual(DroneLink.climb(0.5, .metres), "+0.5 m/s")
        XCTAssertEqual(DroneLink.climb(-1.24, .metres), "\u{2212}1.2 m/s")
        XCTAssertEqual(DroneLink.climb(0.01, .metres), "0.0 m/s")
        XCTAssertEqual(DroneLink.climb(0.508, .feet), "+100 ft/min")
        XCTAssertEqual(DroneLink.climb(-6.096, .feet), "\u{2212}1,200 ft/min")
    }

    func testNearestAerodromes() {
        let tsv = """
        A\tS\tFASH\tStellenbosch Airfield\t-33.9800\t18.8200\t295\t1
        A\tH\t\tFarm Helipad\t-33.9190\t18.9220\t500\t
        A\tL\tFACT\tCape Town International Airport\t-33.9648\t18.6017\t151\t1
        N\tV\tCTV\tCape Town VOR\t-33.9700\t18.6000\t115.70 MHz
        """
        let index = AviationIndex(tsv: tsv)
        let farm = GeoPoint(lat: -33.918292, lon: 18.921204)
        let near = DroneLink.nearestAerodromes(index, to: farm)
        XCTAssertEqual(near.map(\.place.code), ["FASH", "FACT"], "helipads only when nothing else is near")
        XCTAssertEqual(DroneLink.nearestAerodromes(index, to: GeoPoint(lat: -33.9191, lon: 18.9221), count: 1, withinNM: 1)
            .map(\.place.name), ["Farm Helipad"])
    }

    // MARK: - Demo replay

    private var repoRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    func testDemoFlightCopyMatchesDocs() throws {
        let docs = try Data(contentsOf: repoRoot.appendingPathComponent("docs/mavlink/demo_flight.json"))
        let bundled = try Data(contentsOf: repoRoot.appendingPathComponent("SatMap/Resources/DroneDemoFlight.json"))
        XCTAssertEqual(docs, bundled, "SatMap/Resources/DroneDemoFlight.json is a copy of docs/mavlink/demo_flight.json")
    }

    func testDemoReplay() throws {
        let data = try Data(contentsOf: repoRoot.appendingPathComponent("docs/mavlink/demo_flight.json"))
        let replay = try XCTUnwrap(DroneLink.Replay(json: data))
        XCTAssertEqual(replay.frames.count, 855)
        XCTAssertEqual(replay.duration, 61, accuracy: 1e-9)
        let traffic = DroneLink.Traffic()
        // The first 20 s, a quarter second at a time, as the app feeds it.
        var t = -1.0
        var fed = 0
        while t < 20 {
            let next = max(0, t + 0.25)
            for f in replay.frames(from: t, to: next) {
                fed += 1
                traffic.receive(f, at: t0.addingTimeInterval(next))
            }
            t = next
        }
        XCTAssertEqual(fed, replay.frames.filter { $0.t <= 20 }.count)
        let d = try XCTUnwrap(traffic.drones[1])
        XCTAssertEqual(d.heartbeat?.armed, true)
        XCTAssertEqual(d.position?.relativeAltM ?? 0, 50, accuracy: 0.01)
        XCTAssertNotNil(d.home)
        XCTAssertEqual(traffic.aircraft.count, 3)
        XCTAssertEqual(traffic.status(port: 14445, now: t0.addingTimeInterval(20)), .receiving(drones: 1, aircraft: 3))
        // It loops: the frames at 0 come again one lap on.
        XCTAssertEqual(replay.frames(from: 60.9, to: 61.0).count, replay.frames.filter { $0.t == 0 }.count)
        XCTAssertTrue(replay.frames(from: 5, to: 5).isEmpty)
        XCTAssertNil(DroneLink.Replay(json: Data("{}".utf8)))
        XCTAssertNil(DroneLink.bytes(hex: "abc"))
        XCTAssertNil(DroneLink.bytes(hex: "zz"))
        XCTAssertEqual(DroneLink.bytes(hex: "FdfE"), [0xFD, 0xFE])
    }

    // MARK: - UDP over the loopback

    func testUDPLoopback() throws {
        let queue = DispatchQueue(label: "udp-test")
        let lock = NSLock()
        var received: [[UInt8]] = []
        let got = expectation(description: "datagrams")
        let all = try cases()
        let sendable = try all.map { try datagram($0) }
        got.expectedFulfillmentCount = sendable.count
        let receiver = UDPReceiver(port: 0, queue: queue) { bytes in
            lock.lock()
            received.append(bytes)
            lock.unlock()
            got.fulfill()
        }
        try receiver.start()
        XCTAssertTrue(receiver.isRunning)
        let port = receiver.port
        XCTAssertGreaterThan(port, 0)

        // A second listener can't take the same port while the first has it.
        let clash = UDPReceiver(port: port, queue: queue) { _ in }
        XCTAssertThrowsError(try clash.start())
        XCTAssertFalse(clash.isRunning)

        for d in sendable { try send(d, to: port) }
        wait(for: [got], timeout: 10)
        receiver.stop()
        XCTAssertFalse(receiver.isRunning)
        lock.lock()
        let datagrams = received
        lock.unlock()
        XCTAssertEqual(Set(datagrams.map { $0 }), Set(sendable))
        // Through the decoder, the same as the fixture says.
        let traffic = DroneLink.Traffic()
        var frames = 0
        for d in datagrams { frames += traffic.receive(d, at: t0).frames }
        let expected = all.reduce(0) { $0 + (($1["expect"] as? [Any])?.count ?? 0) }
        XCTAssertEqual(frames, expected)
        XCTAssertEqual(Set(traffic.drones.keys), [1, 2])
        // Stopped, nothing more arrives.
        try? send(sendable[0], to: port)
        Thread.sleep(forTimeInterval: 0.3)
        lock.lock()
        XCTAssertEqual(received.count, datagrams.count)
        lock.unlock()
    }

    private func send(_ bytes: [UInt8], to port: UInt16) throws {
        #if canImport(Glibc)
        let s = socket(AF_INET, Int32(SOCK_DGRAM.rawValue), Int32(IPPROTO_UDP))
        #else
        let s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        #endif
        XCTAssertGreaterThanOrEqual(s, 0)
        defer { close(s) }
        var addr = sockaddr_in()
        #if canImport(Darwin)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let n = withUnsafePointer(to: &addr) { a in
            a.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bytes.withUnsafeBytes { sendto(s, $0.baseAddress, $0.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
        }
        XCTAssertEqual(n, bytes.count)
    }
}
