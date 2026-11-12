import Foundation
#if canImport(CryptoKit)
import Security
#else
import COpenSSL
#endif
#if canImport(os)
import os

private let groupLogger = Logger(subsystem: "com.koonap.spoor", category: "groups")
#endif

// Wire basics for groups (docs/groups/SPEC.md §2): base64url, ids, random bytes, micro-degrees, text cleaning,
// initials, "ago" times, the upload pace constants and the live-position rounding. The Android core
// (GroupWire.kt) is the same, checked by the shared vectors (Tests/SatMapCoreTests/Fixtures/groups/vectors.json).

/// base64url (RFC 4648 §5) without padding (groups §2.1).
enum Base64URL {
    static func encode(_ data: Data) -> String {
        var text = data.base64EncodedString()
        text = text.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        while text.hasSuffix("=") { text.removeLast() }
        return text
    }

    /// The bytes, or nil for any character outside `A–Z a–z 0–9 - _` (padding, `+`, `/` and whitespace included) or a
    /// length that is 1 more than a multiple of 4. Non-zero unused trailing bits are accepted (§2.1). Decoded here
    /// rather than by `Data(base64Encoded:)`, whose handling of those bits differs between Foundation versions.
    static func decode(_ text: String) -> Data? {
        var out: [UInt8] = []
        out.reserveCapacity(text.utf8.count * 3 / 4)
        var buffer: UInt32 = 0
        var bits = 0
        var count = 0
        for byte in text.utf8 {
            let value: UInt32
            switch byte {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"): value = UInt32(byte - UInt8(ascii: "A"))
            case UInt8(ascii: "a")...UInt8(ascii: "z"): value = UInt32(byte - UInt8(ascii: "a")) + 26
            case UInt8(ascii: "0")...UInt8(ascii: "9"): value = UInt32(byte - UInt8(ascii: "0")) + 52
            case UInt8(ascii: "-"): value = 62
            case UInt8(ascii: "_"): value = 63
            default: return nil
            }
            buffer = (buffer << 6) | value
            bits += 6
            count += 1
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((buffer >> UInt32(bits)) & 0xFF))
            }
        }
        guard count % 4 != 1 else { return nil }
        return Data(out)
    }

    /// `decode`, accepted only when it gives exactly `count` bytes: an id text (22 chars → 16 bytes, 43 → 32, §2.2).
    static func decode(_ text: String, count: Int) -> Data? {
        guard let data = decode(text), data.count == count else { return nil }
        return data
    }
}

/// Bytes from the system's cryptographic generator (§2.2).
enum GroupRandom {
    static func bytes(_ count: Int) -> Data {
        guard count > 0 else { return Data() }
        var bytes = [UInt8](repeating: 0, count: count)
        #if canImport(CryptoKit)
        let ok = SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess
        #else
        let ok = RAND_bytes(&bytes, Int32(count)) == 1
        #endif
        if !ok {
            // Never seen in practice; the system generator is also cryptographically secure.
            var generator = SystemRandomNumberGenerator()
            for i in bytes.indices { bytes[i] = UInt8.random(in: .min ... .max, using: &generator) }
        }
        return Data(bytes)
    }

    /// A new random id: 16 bytes as 22 base64url characters (`groupId`, `memberId`, `pinId`).
    static func id() -> String {
        Base64URL.encode(bytes(16))
    }
}

/// Coordinates as Int32 micro-degrees (SD19, §2.4).
enum MicroDegrees {
    /// `degrees × 1e6`, rounded half away from zero (Swift's default rounding rule).
    static func from(_ degrees: Double) -> Int32 {
        let x = (degrees * 1e6).rounded()
        guard x.isFinite else { return 0 }
        return Int32(max(Double(Int32.min), min(Double(Int32.max), x)))
    }

    static func degrees(_ e6: Int32) -> Double {
        Double(e6) / 1e6
    }

    static let maxLatitude: Int32 = 90_000_000
    static let maxLongitude: Int32 = 180_000_000

    static func isValid(lat: Int32, lon: Int32) -> Bool {
        abs(Int64(lat)) <= Int64(maxLatitude) && abs(Int64(lon)) <= Int64(maxLongitude)
    }
}

/// The group text fields with limits (§2.5).
enum GroupTextField: Equatable {
    case groupName, displayName, animal, note, reportComment

    /// Fewest and most code points, and most UTF-8 bytes.
    var limits: (min: Int, max: Int, maxBytes: Int) {
        switch self {
        case .groupName: return (1, 40, 160)
        case .displayName: return (1, 30, 120)
        case .animal: return (1, 40, 160)
        case .note: return (0, 140, 560)
        case .reportComment: return (0, 500, 2000)
        }
    }

    /// Notes and report comments may hold up to 3 newlines (U+000A).
    var allowsNewlines: Bool {
        self == .note || self == .reportComment
    }
}

enum GroupText {
    /// The Unicode White_Space code points, written out (§2.5): not Foundation's whitespace set, which differs.
    static func isWhiteSpace(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x09...0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
        default: return false
        }
    }

    /// The text as it is saved and sent (§2.5): NFC, White_Space trimmed at both ends, then checked (no control
    /// characters, U+2028 or U+2029; at most 3 newlines in notes and report comments) and held to the field's limits.
    /// Nil when it is invalid. An empty note or report comment is valid and gives "".
    static func clean(_ text: String, _ field: GroupTextField) -> String? {
        var scalars = Array(text.precomposedStringWithCanonicalMapping.unicodeScalars)
        var start = 0
        var end = scalars.count
        while start < end, isWhiteSpace(scalars[start]) { start += 1 }
        while end > start, isWhiteSpace(scalars[end - 1]) { end -= 1 }
        scalars = Array(scalars[start..<end])
        var newlines = 0
        for c in scalars {
            if c.value == 0x2028 || c.value == 0x2029 { return nil }
            if c.properties.generalCategory == .control {
                guard c.value == 0x0A, field.allowsNewlines else { return nil }
                newlines += 1
            }
        }
        guard newlines <= 3 else { return nil }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        let cleaned = String(view)
        let limits = field.limits
        guard scalars.count >= limits.min, scalars.count <= limits.max, cleaned.utf8.count <= limits.maxBytes else { return nil }
        return cleaned
    }

    /// The length in Unicode scalar values (code points), as every limit counts it.
    static func codePoints(_ text: String) -> Int {
        text.unicodeScalars.count
    }

    /// The letters on a member's dot (§9.5): a leading emoji as it is, otherwise the first letter or digit of the
    /// first and the last word, upper-cased; "?" when there is none.
    static func initials(_ name: String) -> String {
        let cps = Array(name.unicodeScalars)
        if let first = cps.first, isPictographic(first) {
            var out = String.UnicodeScalarView()
            for c in emojiHead(cps) { out.append(c) }
            return String(out)
        }
        var words: [[Unicode.Scalar]] = []
        var current: [Unicode.Scalar] = []
        for c in cps {
            if isWhiteSpace(c) {
                if !current.isEmpty { words.append(current) }
                current = []
            } else {
                current.append(c)
            }
        }
        if !current.isEmpty { words.append(current) }
        let firsts = words.compactMap { word in word.first(where: { isLetter($0) || $0.properties.generalCategory == .decimalNumber }) }
        guard let first = firsts.first else { return "?" }
        var picked = [first]
        if firsts.count >= 2, let last = firsts.last { picked.append(last) }
        return picked.map { String($0).uppercased() }.joined()
    }

    /// Whether the scalar is a letter: general category L*.
    static func isLetter(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    private static func isPictographic(_ c: Unicode.Scalar) -> Bool {
        (0x2600...0x27BF).contains(c.value) || (0x1F000...0x1FAFF).contains(c.value)
    }

    private static func isRegionalIndicator(_ c: Unicode.Scalar) -> Bool {
        (0x1F1E6...0x1F1FF).contains(c.value)
    }

    /// The emoji at the start: the code point plus its variation selectors, keycap, skin tones, tags and ZWJ joins; a
    /// flag's two regional indicators.
    private static func emojiHead(_ cps: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var out = [cps[0]]
        var i = 1
        if isRegionalIndicator(cps[0]) {
            if i < cps.count, isRegionalIndicator(cps[i]) { out.append(cps[i]) }
            return out
        }
        while i < cps.count {
            let v = cps[i].value
            if v == 0xFE0E || v == 0xFE0F || v == 0x20E3 || (0x1F3FB...0x1F3FF).contains(v) || (0xE0020...0xE007F).contains(v) {
                out.append(cps[i])
                i += 1
            } else if v == 0x200D, i + 1 < cps.count {
                out.append(cps[i])
                out.append(cps[i + 1])
                i += 2
            } else {
                break
            }
        }
        return out
    }
}

enum GroupFormat {
    /// "just now", "{m} min ago", "{h} h ago" or "{d} d ago" for an age in whole seconds (§9.3 "Times"); a negative
    /// age (clocks differ) is "just now".
    static func ago(_ seconds: Int64) -> String {
        if seconds < 60 { return "just now" }
        if seconds < 3600 { return "\(seconds / 60) min ago" }
        if seconds < 172_800 { return "\(seconds / 3600) h ago" }
        return "\(seconds / 86_400) d ago"
    }
}

/// The pace and size constants (§8.6), plus the other fixed numbers of the client.
enum GroupPolicy {
    static let uploadInterval: Int64 = 60
    static let liveWatchedForeground: Int64 = 5
    static let liveWatchedBackground: Int64 = 15
    /// Metres.
    static let liveMinMove: Double = 5
    static let liveHeartbeat: Int64 = 30
    static let staleAfter: Int64 = 600
    static let liveHiddenAfter: Int64 = 86_400
    static let animalPinLifetime: Int64 = 43_200
    static let maxChunksPerUpload = 50
    static let maxUploadBytes = 196_608
    /// Metres.
    static let liveMaxAccuracy: Double = 100
    static let socketPing: Int64 = 30

    /// Trail windows are 10 minutes, aligned to UTC (§4.1).
    static let windowSeconds: Int64 = 600
    /// An acknowledged window stays in `own_points` until it has been closed this long, so a point the recorder
    /// delivers a little late still joins its whole window instead of starting a new one that would replace the
    /// server's copy with that point alone.
    static let lateWindowGrace: Int64 = 60
    static let maxPointsPerChunk = 600
    static let maxChunkBody = 8192
    /// Writers try DEFLATE for bodies of at least this many bytes (§4.2).
    static let compressFrom = 256
    /// The default "Trails" setting shows the last 24 hours of other members' trails (SD16).
    static let dayTrail: Int64 = 86_400
    /// The terms version this core knows (§8.13).
    static let termsVersion = 1
    /// The member colour palette (§4.6), index 0–11.
    static let palette = ["#E5484D", "#46A758", "#8E4EC6", "#A18072", "#12A594", "#6E56CF",
                          "#AB4ABA", "#5C7C2F", "#C2298A", "#3E63DD", "#B54548", "#2F7C57"]
    /// "Unknown member" and stale dots are grey (§8.9, §9.5).
    static let grey = "#8E8E93"
    /// Socket reconnect backoff in seconds (§6.6), ±20 % jitter.
    static let socketBackoff: [Int64] = [1, 2, 4, 8, 16, 32, 60]
    /// Retry backoff for network failures and 5xx (§6.7).
    static let retryBackoff: [Int64] = [2, 4, 8, 16, 32, 64, 128, 256, 300]
}

/// A fix from the recorder, as a live position (§4.4).
enum LiveFix {
    /// The live position for a fix: micro-degree coordinates, accuracy and speed rounded and capped at 65535, the
    /// heading from the course when moving at 0.5 m/s or more, else the compass heading. A negative or missing value
    /// is unknown.
    static func position(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?,
                         heading: Double?, time: Double) -> LivePosition {
        let acc = accuracy.flatMap { $0 >= 0 && $0.isFinite ? Int(min(65535, $0.rounded())) : nil }
        let spd = speed.flatMap { $0 >= 0 && $0.isFinite ? Int(min(65535, ($0 * 100).rounded())) : nil }
        var hdg: Int?
        if let s = speed, s >= 0.5, let c = course, c >= 0, c.isFinite {
            hdg = Int(c.rounded()) % 360
        } else if let h = heading, h >= 0, h.isFinite {
            hdg = Int(h.rounded()) % 360
        }
        return LivePosition(time: Int64(time.rounded(.down)), lat: MicroDegrees.from(lat), lon: MicroDegrees.from(lon),
                            accuracy: acc, heading: hdg, speed: spd)
    }
}

/// `uvarint` and `svarint` (§2.7).
enum GroupVarint {
    static func append(_ value: UInt32, to out: inout [UInt8]) {
        var v = value
        while v >= 0x80 {
            out.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        out.append(UInt8(v))
    }

    /// Reads a `uvarint` at `index`, moving it past. Nil for a truncated value, a sixth byte, or 2^32 or more.
    static func read(_ bytes: [UInt8], _ index: inout Int) -> UInt32? {
        var value: UInt64 = 0
        var shift: UInt64 = 0
        for n in 0..<5 {
            guard index < bytes.count else { return nil }
            let b = bytes[index]
            index += 1
            value |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 {
                return value <= UInt64(UInt32.max) ? UInt32(value) : nil
            }
            shift += 7
            if n == 4 { return nil }
        }
        return nil
    }

    static func zigzag(_ v: Int32) -> UInt32 {
        UInt32(bitPattern: (v << 1) ^ (v >> 31))
    }

    static func unzigzag(_ u: UInt32) -> Int32 {
        Int32(bitPattern: (u >> 1) ^ (0 &- (u & 1)))
    }
}

/// One log line without content (§3.4): never names, places, notes, ids or passes.
enum GroupLog {
    static func note(_ message: String) {
        #if canImport(os)
        groupLogger.notice("\(message, privacy: .public)")
        #else
        FileHandle.standardError.write(Data(("groups: " + message + "\n").utf8))
        #endif
    }
}
