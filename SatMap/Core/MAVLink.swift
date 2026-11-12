import Foundation

/// The MAVLink messages Drone link reads (docs/mavlink, README "Drone link"), as a ground station forwards them over
/// UDP: MAVLink 1 and 2 frames, several to a datagram, checked with their CRC (with each message's CRC_EXTRA).
/// Signed MAVLink 2 frames are read without checking the signature; unknown messages are skipped. The rules and the
/// expected values are in Tests/SatMapCoreTests/Fixtures/mavlink/cases.json, which the Android core reads too.
enum MAVLink {
    /// A message the decoder knows: its CRC_EXTRA and its payload length with every field (MAVLink 2 extensions too).
    struct MessageInfo: Equatable {
        let name: String
        let crcExtra: UInt8
        let v1Length: Int
        let v2Length: Int
    }

    static let heartbeat: UInt32 = 0
    static let sysStatus: UInt32 = 1
    static let gpsRawInt: UInt32 = 24
    static let globalPositionInt: UInt32 = 33
    static let vfrHud: UInt32 = 74
    static let batteryStatus: UInt32 = 147
    static let homePosition: UInt32 = 242
    static let adsbVehicle: UInt32 = 246

    /// Message id → what the decoder needs (common.xml).
    static let messages: [UInt32: MessageInfo] = [
        heartbeat: MessageInfo(name: "HEARTBEAT", crcExtra: 50, v1Length: 9, v2Length: 9),
        sysStatus: MessageInfo(name: "SYS_STATUS", crcExtra: 124, v1Length: 31, v2Length: 43),
        gpsRawInt: MessageInfo(name: "GPS_RAW_INT", crcExtra: 24, v1Length: 30, v2Length: 52),
        globalPositionInt: MessageInfo(name: "GLOBAL_POSITION_INT", crcExtra: 104, v1Length: 28, v2Length: 28),
        vfrHud: MessageInfo(name: "VFR_HUD", crcExtra: 20, v1Length: 20, v2Length: 20),
        batteryStatus: MessageInfo(name: "BATTERY_STATUS", crcExtra: 154, v1Length: 36, v2Length: 54),
        homePosition: MessageInfo(name: "HOME_POSITION", crcExtra: 104, v1Length: 52, v2Length: 60),
        adsbVehicle: MessageInfo(name: "ADSB_VEHICLE", crcExtra: 184, v1Length: 38, v2Length: 38),
    ]

    static let v1Magic: UInt8 = 0xFE
    static let v2Magic: UInt8 = 0xFD
    /// MAVLink 2's incompat flag for a signed frame (13 signature bytes after the CRC).
    static let incompatSigned: UInt8 = 0x01
    static let signatureLength = 13

    /// One frame read from a datagram: who sent it and its payload, zero-extended to the message's full length.
    struct Frame: Equatable {
        let version: Int
        let signed: Bool
        let sysid: UInt8
        let compid: UInt8
        let msgId: UInt32
        let payload: [UInt8]

        /// The payload's values, read for the message it is.
        var message: Message? { Message(msgId: msgId, payload: payload) }
    }

    /// X.25 CRC (CRC-16/MCRF4XX), as MAVLink uses it.
    static func crc<S: Sequence>(_ bytes: S, start: UInt16 = 0xFFFF) -> UInt16 where S.Element == UInt8 {
        var crc = start
        for b in bytes {
            var tmp = b ^ UInt8(crc & 0xFF)
            tmp ^= tmp << 4
            crc = (crc >> 8) ^ (UInt16(tmp) << 8) ^ (UInt16(tmp) << 3) ^ (UInt16(tmp) >> 4)
        }
        return crc
    }

    /// Every known, intact frame in one datagram, in order. Garbage is skipped a byte at a time, so a frame after it
    /// (or after a damaged or unknown one) is still found; a frame cut off at the end is dropped.
    static func decode(_ datagram: [UInt8]) -> [Frame] {
        var out: [Frame] = []
        let d = datagram
        let n = d.count
        var i = 0
        while i < n {
            let headerStart: Int, headerEnd: Int, total: Int
            let version: Int, signed: Bool, sysid: UInt8, compid: UInt8, msgId: UInt32
            let plen = i + 1 < n ? Int(d[i + 1]) : 0
            switch d[i] {
            case v1Magic:
                guard i + 8 <= n else { return out }
                total = 6 + plen + 2
                guard i + total <= n else { i += 1; continue }
                version = 1
                signed = false
                sysid = d[i + 3]
                compid = d[i + 4]
                msgId = UInt32(d[i + 5])
                headerStart = i + 1
                headerEnd = i + 6
            case v2Magic:
                guard i + 12 <= n else { return out }
                let incompat = d[i + 2]
                guard incompat & ~incompatSigned == 0 else { i += 1; continue }
                signed = incompat & incompatSigned != 0
                total = 10 + plen + 2 + (signed ? signatureLength : 0)
                guard i + total <= n else { i += 1; continue }
                version = 2
                sysid = d[i + 5]
                compid = d[i + 6]
                msgId = UInt32(d[i + 7]) | UInt32(d[i + 8]) << 8 | UInt32(d[i + 9]) << 16
                headerStart = i + 1
                headerEnd = i + 10
            default:
                i += 1
                continue
            }
            // An unknown message has no CRC_EXTRA to check it with: it can't be told from garbage.
            guard let info = messages[msgId], plen <= info.v2Length else { i += 1; continue }
            let payloadEnd = headerEnd + plen
            var c = crc(d[headerStart..<payloadEnd])
            c = crc(CollectionOfOne(info.crcExtra), start: c)
            let stored = UInt16(d[payloadEnd]) | UInt16(d[payloadEnd + 1]) << 8
            guard c == stored else { i += 1; continue }
            var payload = Array(d[headerEnd..<payloadEnd])
            if payload.count < info.v2Length { payload += [UInt8](repeating: 0, count: info.v2Length - payload.count) }
            out.append(Frame(version: version, signed: signed, sysid: sysid, compid: compid, msgId: msgId, payload: payload))
            i += total
        }
        return out
    }

    // MARK: - Messages

    struct Heartbeat: Equatable {
        let type: Int
        let autopilot: Int
        let baseMode: Int
        let customMode: UInt32
        let systemStatus: Int
        /// MAV_MODE_FLAG_SAFETY_ARMED.
        var armed: Bool { baseMode & 0x80 != 0 }
        /// A ground station's own heartbeat (MAV_TYPE_GCS): its system id is never a drone.
        var isGroundStation: Bool { type == 6 }
        /// The flight controller's own heartbeat. A companion computer, camera, gimbal or ADS-B transceiver on the
        /// vehicle sends MAV_AUTOPILOT_INVALID (8) under the vehicle's system id: it says nothing about the vehicle's
        /// state (armed, type), so it is ignored, but it doesn't hide the vehicle either.
        var isAutopilot: Bool { type != 6 && autopilot != 8 }
    }

    struct SysStatus: Equatable {
        let batteryVoltageV: Double?
        let batteryCurrentA: Double?
        let batteryRemainingPct: Int?
    }

    struct GpsRawInt: Equatable {
        /// GPS_FIX_TYPE: 0–1 no fix, 2 2D, 3 3D, 4 DGPS, 5 RTK float, 6 RTK fixed …
        let fixType: Int
        let satellitesVisible: Int?
        let latDeg: Double
        let lonDeg: Double
        let altMslM: Double
    }

    struct GlobalPositionInt: Equatable {
        let timeBootMs: UInt32
        let latDeg: Double
        let lonDeg: Double
        let altMslM: Double
        /// Height above home (take-off).
        let relativeAltM: Double
        /// North, east and down, m/s.
        let vxMs: Double
        let vyMs: Double
        let vzMs: Double
        let headingDeg: Double?
    }

    struct VfrHud: Equatable {
        let airspeedMs: Double
        let groundspeedMs: Double
        let headingDeg: Int
        let throttlePct: Int
        let altM: Double
        let climbMs: Double
    }

    struct BatteryStatus: Equatable {
        let id: Int
        let batteryRemainingPct: Int?
    }

    struct HomePosition: Equatable {
        let latDeg: Double
        let lonDeg: Double
        let altMslM: Double
    }

    struct AdsbVehicle: Equatable {
        struct Valid: Equatable {
            let coords: Bool
            let altitude: Bool
            let heading: Bool
            let velocity: Bool
            let callsign: Bool
            let squawk: Bool
            let verticalVelocity: Bool
        }

        let icaoAddress: UInt32
        let latDeg: Double
        let lonDeg: Double
        /// ADSB_ALTITUDE_TYPE: 0 pressure (QNH), 1 geometric (GNSS).
        let altitudeType: Int
        let altitudeM: Double
        let headingDeg: Double
        let horVelocityMs: Double
        let verVelocityMs: Double
        let callsign: String?
        /// ADSB_EMITTER_TYPE (`DroneLink.AircraftKind` groups them for the map).
        let emitterType: Int
        /// Seconds since the last communication.
        let tslcS: Int
        let flags: Int
        let squawk: Int

        /// "00A1B2".
        var icaoHex: String { String(format: "%06X", icaoAddress) }

        var valid: Valid {
            Valid(coords: flags & 1 != 0, altitude: flags & 2 != 0, heading: flags & 4 != 0, velocity: flags & 8 != 0,
                  callsign: flags & 16 != 0, squawk: flags & 32 != 0, verticalVelocity: flags & 128 != 0)
        }
    }

    enum Message: Equatable {
        case heartbeat(Heartbeat)
        case sysStatus(SysStatus)
        case gpsRawInt(GpsRawInt)
        case globalPositionInt(GlobalPositionInt)
        case vfrHud(VfrHud)
        case batteryStatus(BatteryStatus)
        case homePosition(HomePosition)
        case adsbVehicle(AdsbVehicle)

        /// Reads a (zero-extended) payload; nil for a message the decoder doesn't know or a payload too short.
        init?(msgId: UInt32, payload p: [UInt8]) {
            guard let info = MAVLink.messages[msgId], p.count >= info.v2Length else { return nil }
            let r = Reader(p)
            switch msgId {
            case MAVLink.heartbeat:
                self = .heartbeat(Heartbeat(type: Int(r.u8(4)), autopilot: Int(r.u8(5)), baseMode: Int(r.u8(6)),
                                            customMode: r.u32(0), systemStatus: Int(r.u8(7))))
            case MAVLink.sysStatus:
                let mv = r.u16(14), ca = r.i16(16), pct = r.i8(30)
                self = .sysStatus(SysStatus(batteryVoltageV: mv == 0xFFFF ? nil : Double(mv) / 1000,
                                            batteryCurrentA: ca == -1 ? nil : Double(ca) / 100,
                                            batteryRemainingPct: pct == -1 ? nil : Int(pct)))
            case MAVLink.gpsRawInt:
                let sats = r.u8(29)
                self = .gpsRawInt(GpsRawInt(fixType: Int(r.u8(28)), satellitesVisible: sats == 255 ? nil : Int(sats),
                                            latDeg: Double(r.i32(8)) / 1e7, lonDeg: Double(r.i32(12)) / 1e7,
                                            altMslM: Double(r.i32(16)) / 1000))
            case MAVLink.globalPositionInt:
                let hdg = r.u16(26)
                self = .globalPositionInt(GlobalPositionInt(
                    timeBootMs: r.u32(0), latDeg: Double(r.i32(4)) / 1e7, lonDeg: Double(r.i32(8)) / 1e7,
                    altMslM: Double(r.i32(12)) / 1000, relativeAltM: Double(r.i32(16)) / 1000,
                    vxMs: Double(r.i16(20)) / 100, vyMs: Double(r.i16(22)) / 100, vzMs: Double(r.i16(24)) / 100,
                    headingDeg: hdg == 65535 ? nil : Double(hdg) / 100))
            case MAVLink.vfrHud:
                self = .vfrHud(VfrHud(airspeedMs: Double(r.f32(0)), groundspeedMs: Double(r.f32(4)),
                                      headingDeg: Int(r.i16(16)), throttlePct: Int(r.u16(18)), altM: Double(r.f32(8)),
                                      climbMs: Double(r.f32(12))))
            case MAVLink.batteryStatus:
                let pct = r.i8(35)
                self = .batteryStatus(BatteryStatus(id: Int(r.u8(32)), batteryRemainingPct: pct == -1 ? nil : Int(pct)))
            case MAVLink.homePosition:
                self = .homePosition(HomePosition(latDeg: Double(r.i32(0)) / 1e7, lonDeg: Double(r.i32(4)) / 1e7,
                                                  altMslM: Double(r.i32(8)) / 1000))
            case MAVLink.adsbVehicle:
                self = .adsbVehicle(AdsbVehicle(
                    icaoAddress: r.u32(0), latDeg: Double(r.i32(4)) / 1e7, lonDeg: Double(r.i32(8)) / 1e7,
                    altitudeType: Int(r.u8(26)), altitudeM: Double(r.i32(12)) / 1000, headingDeg: Double(r.u16(16)) / 100,
                    horVelocityMs: Double(r.u16(18)) / 100, verVelocityMs: Double(r.i16(20)) / 100,
                    callsign: Self.callsign(Array(p[27..<36])), emitterType: Int(r.u8(36)), tslcS: Int(r.u8(37)),
                    flags: Int(r.u16(22)), squawk: Int(r.u16(24))))
            default:
                return nil
            }
        }

        /// Up to the first NUL, trailing spaces trimmed; nil when nothing is left. A byte that isn't ASCII becomes
        /// U+FFFD (as the fixture's generator decodes it).
        static func callsign(_ bytes: [UInt8]) -> String? {
            let used = bytes.prefix { $0 != 0 }
            var s = String(String.UnicodeScalarView(used.map { $0 < 0x80 ? UnicodeScalar($0) : "\u{FFFD}" }))
            while s.hasSuffix(" ") { s.removeLast() }
            return s.isEmpty ? nil : s
        }
    }

    /// Little-endian fields at byte offsets.
    private struct Reader {
        let p: [UInt8]
        init(_ p: [UInt8]) { self.p = p }

        func u8(_ o: Int) -> UInt8 { p[o] }
        func i8(_ o: Int) -> Int8 { Int8(bitPattern: p[o]) }
        func u16(_ o: Int) -> UInt16 { UInt16(p[o]) | UInt16(p[o + 1]) << 8 }
        func i16(_ o: Int) -> Int16 { Int16(bitPattern: u16(o)) }
        func u32(_ o: Int) -> UInt32 { UInt32(u16(o)) | UInt32(u16(o + 2)) << 16 }
        func i32(_ o: Int) -> Int32 { Int32(bitPattern: u32(o)) }
        func f32(_ o: Int) -> Float { Float(bitPattern: u32(o)) }
    }
}
