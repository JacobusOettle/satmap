import Foundation

// The plaintexts that groups seal (docs/groups/SPEC.md §4): trail chunks, live positions, pins and the two profiles.
// The Android core (GroupRecords.kt) writes and reads the same bytes; the shared vectors check both.

/// A record that doesn't follow its format; the reader drops it and logs one line.
enum GroupRecordError: Error, Equatable {
    case malformed
    case unsupportedVersion
}

/// One trail point (§4.2): whole seconds, micro-degrees, and whether a new line segment starts here.
struct TrailPoint: Equatable {
    var time: Int64
    var lat: Int32
    var lon: Int32
    var startsSegment: Bool
}

/// One member's trail points in one 10-minute window (§4.1, §4.2): a two-byte header (format 1, encoding), then the
/// body as it is (encoding 0) or as raw DEFLATE after its decompressed length (encoding 1). The body is the point
/// count and, per point, `(dt << 1) | s`, then the latitude and longitude as zigzag deltas.
enum TrailChunk {
    static let format: UInt8 = 0x01

    /// The start of the window that holds a time: `t − (t mod 600)`.
    static func windowStart(_ time: Int64) -> Int64 {
        let w = GroupPolicy.windowSeconds
        return time - ((time % w) + w) % w
    }

    /// The chunk plaintext. With `compress`, a body of 256 bytes or more is deflated when that is smaller. Throws
    /// for 0 or more than 600 points, a time outside the window or earlier than the one before, or a coordinate out
    /// of range.
    static func encode(_ points: [TrailPoint], windowStart: Int64, compress: Bool) throws -> Data {
        let body = try encodeBody(points, windowStart: windowStart)
        if compress, body.count >= GroupPolicy.compressFrom, let deflated = Deflate.raw(Data(body)) {
            var out: [UInt8] = [format, 0x01]
            GroupVarint.append(UInt32(body.count), to: &out)
            if out.count + deflated.count < 2 + body.count {
                return Data(out) + deflated
            }
        }
        return Data([format, 0x00] + body)
    }

    /// The points of a chunk plaintext, checked as §4.2 says (DEFLATE inflated exactly). Throws if it breaks a rule.
    static func decode(_ plain: Data, windowStart: Int64) throws -> [TrailPoint] {
        let bytes = [UInt8](plain)
        guard bytes.count >= 2 else { throw GroupRecordError.malformed }
        guard bytes[0] == format else { throw GroupRecordError.unsupportedVersion }
        let body: [UInt8]
        switch bytes[1] {
        case 0x00:
            body = Array(bytes[2...])
        case 0x01:
            var i = 2
            guard let n = GroupVarint.read(bytes, &i), n >= 1, n <= UInt32(GroupPolicy.maxChunkBody), i < bytes.count,
                  let inflated = Deflate.inflateExact(Data(bytes[i...]), size: Int(n)) else { throw GroupRecordError.malformed }
            body = [UInt8](inflated)
        default:
            throw GroupRecordError.unsupportedVersion
        }
        guard body.count <= GroupPolicy.maxChunkBody else { throw GroupRecordError.malformed }
        return try decodeBody(body, windowStart: windowStart)
    }

    private static func encodeBody(_ points: [TrailPoint], windowStart: Int64) throws -> [UInt8] {
        guard !points.isEmpty, points.count <= GroupPolicy.maxPointsPerChunk else { throw GroupRecordError.malformed }
        var out: [UInt8] = []
        GroupVarint.append(UInt32(points.count), to: &out)
        var previousTime = windowStart
        var previousLat: Int32 = 0
        var previousLon: Int32 = 0
        for p in points {
            let dt = p.time - previousTime
            guard dt >= 0, p.time < windowStart + GroupPolicy.windowSeconds,
                  MicroDegrees.isValid(lat: p.lat, lon: p.lon) else { throw GroupRecordError.malformed }
            GroupVarint.append(UInt32(dt << 1) | (p.startsSegment ? 1 : 0), to: &out)
            GroupVarint.append(GroupVarint.zigzag(p.lat &- previousLat), to: &out)
            GroupVarint.append(GroupVarint.zigzag(p.lon &- previousLon), to: &out)
            previousTime = p.time
            previousLat = p.lat
            previousLon = p.lon
        }
        guard out.count <= GroupPolicy.maxChunkBody else { throw GroupRecordError.malformed }
        return out
    }

    private static func decodeBody(_ body: [UInt8], windowStart: Int64) throws -> [TrailPoint] {
        var i = 0
        guard let n = GroupVarint.read(body, &i), n >= 1, n <= UInt32(GroupPolicy.maxPointsPerChunk) else { throw GroupRecordError.malformed }
        var points: [TrailPoint] = []
        points.reserveCapacity(Int(n))
        var time = windowStart
        var lat: Int64 = 0
        var lon: Int64 = 0
        for _ in 0..<n {
            guard let head = GroupVarint.read(body, &i), let dLat = GroupVarint.read(body, &i),
                  let dLon = GroupVarint.read(body, &i) else { throw GroupRecordError.malformed }
            time += Int64(head >> 1)
            lat += Int64(GroupVarint.unzigzag(dLat))
            lon += Int64(GroupVarint.unzigzag(dLon))
            guard time < windowStart + GroupPolicy.windowSeconds, abs(lat) <= Int64(MicroDegrees.maxLatitude),
                  abs(lon) <= Int64(MicroDegrees.maxLongitude) else { throw GroupRecordError.malformed }
            points.append(TrailPoint(time: time, lat: Int32(lat), lon: Int32(lon), startsSegment: head & 1 == 1))
        }
        guard i == body.count else { throw GroupRecordError.malformed }
        return points
    }
}

/// A live position (§4.4): 24 bytes, little-endian, with flags for the optional accuracy, heading and speed.
struct LivePosition: Equatable {
    var time: Int64
    var lat: Int32
    var lon: Int32
    /// Metres, 0–65535.
    var accuracy: Int?
    /// Whole degrees, 0–359.
    var heading: Int?
    /// Centimetres per second, 0–65535.
    var speed: Int?

    static let size = 24
    static let format: UInt8 = 0x01

    func encoded() -> Data {
        var out: [UInt8] = [Self.format, 0]
        if accuracy != nil { out[1] |= 1 }
        if heading != nil { out[1] |= 2 }
        if speed != nil { out[1] |= 4 }
        withUnsafeBytes(of: time.littleEndian) { out.append(contentsOf: $0) }
        withUnsafeBytes(of: lat.littleEndian) { out.append(contentsOf: $0) }
        withUnsafeBytes(of: lon.littleEndian) { out.append(contentsOf: $0) }
        for value in [accuracy, heading.map { (($0 % 360) + 360) % 360 }, speed] {
            let v = UInt16(clamping: value ?? 0)
            withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) }
        }
        return Data(out)
    }

    /// Throws unless it is 24 bytes of format 1 with coordinates in range and a heading of 0–359. Flag bits this
    /// version doesn't know are ignored.
    static func decode(_ plain: Data) throws -> LivePosition {
        let bytes = [UInt8](plain)
        guard bytes.count == size else { throw GroupRecordError.malformed }
        guard bytes[0] == format else { throw GroupRecordError.unsupportedVersion }
        let flags = bytes[1]
        func int64(_ at: Int) -> Int64 {
            (0..<8).reduce(Int64(0)) { $0 | Int64(bytes[at + $1]) << (8 * Int64($1)) }
        }
        func int32(_ at: Int) -> Int32 {
            Int32(bitPattern: (0..<4).reduce(UInt32(0)) { $0 | UInt32(bytes[at + $1]) << (8 * UInt32($1)) })
        }
        func uint16(_ at: Int) -> Int {
            Int(bytes[at]) | Int(bytes[at + 1]) << 8
        }
        let p = LivePosition(time: int64(2), lat: int32(10), lon: int32(14),
                             accuracy: flags & 1 != 0 ? uint16(18) : nil,
                             heading: flags & 2 != 0 ? uint16(20) : nil,
                             speed: flags & 4 != 0 ? uint16(22) : nil)
        guard MicroDegrees.isValid(lat: p.lat, lon: p.lon), (p.heading ?? 0) < 360 else { throw GroupRecordError.malformed }
        return p
    }
}

enum PinType: String, CaseIterable {
    case animal, shelter, water, danger, pothole, police

    /// The title (§9.4).
    var label: String {
        switch self {
        case .animal: return "Animal sighting"
        case .shelter: return "Shelter"
        case .water: return "Water"
        case .danger: return "Danger"
        case .pothole: return "Pothole"
        case .police: return "Police"
        }
    }

    /// The pin's colour (§9.4).
    var colourHex: String {
        switch self {
        case .animal: return "#2E7D32"
        case .shelter: return "#8D6E63"
        case .water: return "#1E88E5"
        case .danger: return "#E53935"
        case .pothole: return "#F57C00"
        case .police: return "#3949AB"
        }
    }

    /// A pin of a type this version doesn't know is a plain "Pin" in grey (§4.5, §9.4).
    static let unknownLabel = "Pin"
    static let unknownColourHex = "#757575"
}

/// A pin's sealed JSON (§4.5). Its id, dropper and times are plain fields that the AAD binds.
struct PinRecord: Equatable {
    var type: String
    var animal: String?
    var note: String?
    var lat: Int32
    var lon: Int32

    var knownType: PinType? { PinType(rawValue: type) }

    /// Compact, keys sorted, padded to a multiple of 64 bytes (§2.6). An empty note is left out.
    func json() -> Data {
        var fields: [(String, GroupJSON.Value)] = [("v", .int(1)), ("type", .string(type)),
                                                   ("lat", .int(Int64(lat))), ("lon", .int(Int64(lon)))]
        if let animal = animal { fields.append(("animal", .string(animal))) }
        if let note = note, !note.isEmpty { fields.append(("note", .string(note))) }
        return GroupJSON.padded(fields, block: 64)
    }

    /// Throws for a wrong type, a missing field, `v` other than 1, a coordinate out of range, an animal pin without
    /// an animal, or a text over its limit. Unknown keys and `pad` are ignored.
    static func decode(_ json: Data) throws -> PinRecord {
        struct Wire: Decodable { let v: Int; let type: String; let lat: Int32; let lon: Int32; let animal: String?; let note: String? }
        let w: Wire = try GroupJSON.decode(json)
        guard w.v == 1 else { throw GroupRecordError.unsupportedVersion }
        guard MicroDegrees.isValid(lat: w.lat, lon: w.lon), !w.type.isEmpty else { throw GroupRecordError.malformed }
        if w.type == PinType.animal.rawValue {
            guard let animal = w.animal, GroupJSON.fits(animal, .animal, allowEmpty: false) else { throw GroupRecordError.malformed }
        } else if let animal = w.animal, !GroupJSON.fits(animal, .animal, allowEmpty: true) {
            throw GroupRecordError.malformed
        }
        if let note = w.note, !GroupJSON.fits(note, .note, allowEmpty: true) { throw GroupRecordError.malformed }
        let note = w.note.flatMap { $0.isEmpty ? nil : $0 }
        return PinRecord(type: w.type, animal: w.type == PinType.animal.rawValue ? w.animal : nil, note: note, lat: w.lat, lon: w.lon)
    }
}

/// A member's sealed profile (§4.6): display name and colour index (0–11 in this version).
struct MemberProfile: Equatable {
    var name: String
    var colour: Int

    func json() -> Data {
        GroupJSON.padded([("v", .int(1)), ("name", .string(name)), ("colour", .int(Int64(colour)))], block: 32)
    }

    /// Throws for a wrong type, a missing field, `v` other than 1, or an empty or too long name. A colour index this
    /// version doesn't know is kept; it is drawn grey.
    static func decode(_ json: Data) throws -> MemberProfile {
        struct Wire: Decodable { let v: Int; let name: String; let colour: Int }
        let w: Wire = try GroupJSON.decode(json)
        guard w.v == 1 else { throw GroupRecordError.unsupportedVersion }
        guard GroupJSON.fits(w.name, .displayName, allowEmpty: false) else { throw GroupRecordError.malformed }
        return MemberProfile(name: w.name, colour: w.colour)
    }
}

/// The group's sealed profile (§4.7): its name.
struct GroupProfile: Equatable {
    var name: String

    func json() -> Data {
        GroupJSON.padded([("v", .int(1)), ("name", .string(name))], block: 32)
    }

    static func decode(_ json: Data) throws -> GroupProfile {
        struct Wire: Decodable { let v: Int; let name: String }
        let w: Wire = try GroupJSON.decode(json)
        guard w.v == 1 else { throw GroupRecordError.unsupportedVersion }
        guard GroupJSON.fits(w.name, .groupName, allowEmpty: false) else { throw GroupRecordError.malformed }
        return GroupProfile(name: w.name)
    }
}

/// The JSON of the sealed records (§2.6): compact, keys sorted by code point, non-ASCII written as itself and "/" not
/// escaped, padded with `pad`. Written by hand (the spec's JSONEncoder with `.sortedKeys`, `.withoutEscapingSlashes`
/// gives the same bytes for these records) so the bytes don't depend on a Foundation version: they must equal the
/// Android core's and the vectors'.
enum GroupJSON {
    enum Value {
        case int(Int64)
        case string(String)
    }

    /// The object's JSON with its keys sorted.
    static func object(_ fields: [(String, Value)]) -> Data {
        let sorted = fields.sorted { a, b in a.0.unicodeScalars.lexicographicallyPrecedes(b.0.unicodeScalars) { $0.value < $1.value } }
        var out = "{"
        for (i, field) in sorted.enumerated() {
            if i > 0 { out += "," }
            out += quoted(field.0) + ":"
            switch field.1 {
            case .int(let v): out += String(v)
            case .string(let s): out += quoted(s)
            }
        }
        out += "}"
        return Data(out.utf8)
    }

    /// The object with `pad`, a string of spaces that makes its length a multiple of `block` (SD25): encoded with an
    /// empty pad of length L, then again with (B − L mod B) mod B spaces.
    static func padded(_ fields: [(String, Value)], block: Int) -> Data {
        let base = fields.filter { $0.0 != "pad" }
        let length = object(base + [("pad", .string(""))]).count
        let spaces = (block - length % block) % block
        return object(base + [("pad", .string(String(repeating: " ", count: spaces)))])
    }

    /// The text as a JSON string: quote, backslash and control characters escaped as Python's `json.dumps` does
    /// (`\n`, `\r`, `\t`, `\b`, `\f`, else `\u00xx`); everything else as itself.
    static func quoted(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x00..<0x20:
                let digits = Array("0123456789abcdef")
                out += "\\u00" + String(digits[Int(c.value >> 4)]) + String(digits[Int(c.value & 15)])
            default: out.unicodeScalars.append(c)
            }
        }
        return out + "\""
    }

    /// Decodes a record, mapping any failure (bad JSON, a wrong type, a missing key) to `malformed`.
    static func decode<T: Decodable>(_ json: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: json)
        } catch {
            throw GroupRecordError.malformed
        }
    }

    /// Whether a received text is within its field's limits (§2.5).
    static func fits(_ text: String, _ field: GroupTextField, allowEmpty: Bool) -> Bool {
        let n = text.unicodeScalars.count
        let limits = field.limits
        return (allowEmpty || n >= 1) && n <= limits.max && text.utf8.count <= limits.maxBytes
    }
}
