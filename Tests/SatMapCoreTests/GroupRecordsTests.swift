import Foundation
import XCTest
@testable import SatMapCore

/// Group payloads (docs/groups/SPEC.md §4): the same vectors as the Android core's GroupRecordsTests.
final class GroupRecordsTests: XCTestCase {
    private var v: GroupVectors!

    override func setUpWithError() throws {
        v = try GroupVectors.load()
    }

    // MARK: - Trail chunks

    func testChunkEncodesToTheVector() throws {
        let c = v.trailChunk
        let points = c.points.map(\.point)
        XCTAssertEqual(hex(try TrailChunk.encode(points, windowStart: c.windowStart, compress: false)), c.plainHex)
        // A short body is never deflated.
        XCTAssertEqual(hex(try TrailChunk.encode(points, windowStart: c.windowStart, compress: true)), c.plainHex)
        XCTAssertEqual(try TrailChunk.decode(unhex(c.plainHex), windowStart: c.windowStart), points)
    }

    func testDeflatedChunkDecodes() throws {
        let c = v.trailChunkDeflated
        let points = c.points.map(\.point)
        XCTAssertEqual(points.count, 40)
        XCTAssertEqual(try TrailChunk.decode(unhex(c.plainHex), windowStart: c.windowStart), points)
        // The body before compression is what an encoding-0 writer produces.
        let plain = try TrailChunk.encode(points, windowStart: c.windowStart, compress: false)
        XCTAssertEqual(hex(plain), "0100" + (c.bodyHex ?? ""))
    }

    func testChunkMustFailCases() {
        XCTAssertEqual(Set(v.mustFail.chunkPlainHex.keys),
                       ["deflateLongerThanDeclared", "deflateTrailingInput", "deflateShorterThanDeclared"])
        for (name, plain) in v.mustFail.chunkPlainHex {
            XCTAssertThrowsError(try TrailChunk.decode(unhex(plain), windowStart: v.trailChunkDeflated.windowStart), name)
        }
    }

    func testLongChunkIsDeflatedAndReadBack() throws {
        let start: Int64 = 1_793_613_600
        var points: [TrailPoint] = []
        for i in 0..<600 {
            let lat: Int32 = -24_996_352 + Int32(i * 3)
            let lon: Int32 = 31_592_345 - Int32(i * 2)
            points.append(TrailPoint(time: start + Int64(i), lat: lat, lon: lon, startsSegment: i % 100 == 0))
        }
        let plain = try TrailChunk.encode(points, windowStart: start, compress: true)
        XCTAssertEqual(Array(plain.prefix(2)), [0x01, 0x01])
        let raw = try TrailChunk.encode(points, windowStart: start, compress: false)
        XCTAssertLessThan(plain.count, raw.count)
        XCTAssertEqual(try TrailChunk.decode(plain, windowStart: start), points)
        XCTAssertEqual(try TrailChunk.decode(raw, windowStart: start), points)
    }

    func testChunkLimits() throws {
        let start: Int64 = 1_793_613_600
        let p = TrailPoint(time: start, lat: 0, lon: 0, startsSegment: true)
        XCTAssertThrowsError(try TrailChunk.encode([], windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode(Array(repeating: p, count: 601), windowStart: start, compress: false))
        XCTAssertNoThrow(try TrailChunk.encode(Array(repeating: p, count: 600), windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode([TrailPoint(time: start + 600, lat: 0, lon: 0, startsSegment: false)], windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode([TrailPoint(time: start - 1, lat: 0, lon: 0, startsSegment: false)], windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode([TrailPoint(time: start + 5, lat: 0, lon: 0, startsSegment: false), p], windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode([TrailPoint(time: start, lat: 90_000_001, lon: 0, startsSegment: false)], windowStart: start, compress: false))
        XCTAssertThrowsError(try TrailChunk.encode([TrailPoint(time: start, lat: 0, lon: -180_000_001, startsSegment: false)], windowStart: start, compress: false))
        XCTAssertNoThrow(try TrailChunk.encode([TrailPoint(time: start + 599, lat: -90_000_000, lon: 180_000_000, startsSegment: false)], windowStart: start, compress: false))

        func rejects(_ bytes: [UInt8], _ message: String) {
            XCTAssertThrowsError(try TrailChunk.decode(Data(bytes), windowStart: start), message)
        }
        let good = [UInt8](try TrailChunk.encode([p], windowStart: start, compress: false))
        XCTAssertEqual(good, [0x01, 0x00, 0x01, 0x01, 0x00, 0x00])
        rejects([0x01, 0x00, 0x00], "no points")
        rejects([0x01, 0x00] + [0xD9, 0x04] + [UInt8](repeating: 0, count: 601 * 3), "601 points")
        rejects(good + [0x00], "a byte left over")
        rejects(Array(good.dropLast()), "cut short")
        rejects([0x02] + good.dropFirst(), "another format")
        rejects([0x01, 0x02] + good.dropFirst(2), "another encoding")
        rejects([0x01], "no encoding")
        // dt of 600 leaves the window; a latitude delta past 90°.
        rejects([0x01, 0x00, 0x01] + [0xB0, 0x09] + [0x00, 0x00], "time outside the window")
        var lat: [UInt8] = []
        GroupVarint.append(GroupVarint.zigzag(90_000_001), to: &lat)
        rejects([0x01, 0x00, 0x01, 0x00] + lat + [0x00], "latitude out of range")
        // Two points whose sum leaves the range though each delta is fine.
        var big: [UInt8] = [0x01, 0x00, 0x02, 0x00]
        GroupVarint.append(GroupVarint.zigzag(90_000_000), to: &big)
        big.append(0x00)
        big.append(0x00)
        GroupVarint.append(GroupVarint.zigzag(1), to: &big)
        big.append(0x00)
        rejects(big, "latitude sum out of range")
        // Encoding 1 with a declared length over 8192, or 0.
        var over: [UInt8] = [0x01, 0x01]
        GroupVarint.append(8193, to: &over)
        rejects(over + [UInt8](Deflate.raw(Data(repeating: 0, count: 8193))!), "body over 8192")
        rejects([0x01, 0x01, 0x00, 0x03, 0x00], "declared 0")
        // Encoding 0 with a body over 8192 bytes.
        rejects([0x01, 0x00] + Array(good.dropFirst(2)) + [UInt8](repeating: 0, count: 8190), "body over 8192 bytes")
    }

    func testWindowStart() {
        XCTAssertEqual(TrailChunk.windowStart(1_793_613_600), 1_793_613_600)
        XCTAssertEqual(TrailChunk.windowStart(1_793_613_605), 1_793_613_600)
        XCTAssertEqual(TrailChunk.windowStart(1_793_614_199), 1_793_613_600)
        XCTAssertEqual(TrailChunk.windowStart(1_793_614_200), 1_793_614_200)
        XCTAssertEqual(TrailChunk.windowStart(0), 0)
    }

    func testDeflateRoundTripAndExactness() throws {
        let data = Data((0..<5000).map { UInt8(($0 * 7) % 13) })
        let deflated = try XCTUnwrap(Deflate.raw(data))
        XCTAssertLessThan(deflated.count, data.count)
        XCTAssertEqual(Deflate.inflateExact(deflated, size: data.count), data)
        XCTAssertNil(Deflate.inflateExact(deflated, size: data.count - 1))
        XCTAssertNil(Deflate.inflateExact(deflated, size: data.count + 1))
        XCTAssertNil(Deflate.inflateExact(deflated + Data([0]), size: data.count))
        XCTAssertNil(Deflate.inflateExact(deflated.dropLast(), size: data.count))
        XCTAssertNil(Deflate.inflateExact(Data([0xFF, 0xFF]), size: 2))
        XCTAssertNil(Deflate.inflateExact(Data(), size: 0))
        // Random bytes don't shrink but still round trip.
        let noise = GroupRandom.bytes(3000)
        XCTAssertEqual(Deflate.inflateExact(try XCTUnwrap(Deflate.raw(noise)), size: noise.count), noise)
    }

    // MARK: - Live

    func testLiveVectors() throws {
        for live in [v.live, v.liveUnknowns] {
            let plain = try XCTUnwrap(live.plainHex)
            XCTAssertEqual(hex(live.position.encoded()), plain)
            XCTAssertEqual(try LivePosition.decode(unhex(plain)), live.position)
        }
        XCTAssertEqual(v.liveUnknowns.position.accuracy, nil)
    }

    func testLiveRounding() {
        XCTAssertEqual(v.liveRounding.count, 6)
        for r in v.liveRounding {
            let f = r.fix
            let p = LiveFix.position(lat: f.lat, lon: f.lon, accuracy: f.accuracy, course: f.course, speed: f.speed,
                                     heading: f.heading, time: f.time)
            XCTAssertEqual(p, r.live.position, "\(f)")
        }
    }

    func testLiveRejections() throws {
        let good = [UInt8](v.live.position.encoded())
        XCTAssertThrowsError(try LivePosition.decode(Data(good.dropLast())))
        XCTAssertThrowsError(try LivePosition.decode(Data(good + [0])))
        var other = good
        other[0] = 2
        XCTAssertThrowsError(try LivePosition.decode(Data(other)))
        var heading = good
        heading[20] = 104 // 360
        heading[21] = 1
        XCTAssertThrowsError(try LivePosition.decode(Data(heading)))
        var lat = good
        lat[10...13] = [0x81, 0x4A, 0x5D, 0x05] // 90 000 001
        XCTAssertThrowsError(try LivePosition.decode(Data(lat)))
        // Unknown flag bits are ignored; a clear flag hides its field.
        var flags = good
        flags[1] = 0x08
        let p = try LivePosition.decode(Data(flags))
        XCTAssertNil(p.accuracy)
        XCTAssertNil(p.heading)
        XCTAssertNil(p.speed)
        XCTAssertEqual(p.lat, v.live.lat)
    }

    // MARK: - JSON records

    func testWritersProduceTheVectorBytes() throws {
        let w = v.jsonWriters
        let group = GroupProfile(name: w.groupProfile.name).json()
        XCTAssertEqual(String(decoding: group, as: UTF8.self), w.groupProfile.json)
        XCTAssertEqual(group.count % 32, 0)
        let member = MemberProfile(name: w.memberProfile.name, colour: w.memberProfile.colour).json()
        XCTAssertEqual(String(decoding: member, as: UTF8.self), w.memberProfile.json)
        let pin = PinRecord(type: w.pinAnimal.type, animal: w.pinAnimal.animal, note: nil, lat: w.pinAnimal.lat, lon: w.pinAnimal.lon).json()
        XCTAssertEqual(String(decoding: pin, as: UTF8.self), w.pinAnimal.json)
        XCTAssertEqual(pin.count, 128)
        // The padded writer output is also what the padded record vectors seal.
        XCTAssertEqual(hex(group), v.records["groupProfilePadded"]?.plainHex)
        XCTAssertEqual(hex(member), v.records["memberProfilePadded"]?.plainHex)
        XCTAssertEqual(hex(pin), v.records["pinAnimalPadded"]?.plainHex)
        XCTAssertEqual(try GroupProfile.decode(group), GroupProfile(name: "Kruger trip"))
        XCTAssertEqual(try MemberProfile.decode(member), MemberProfile(name: "Thandi", colour: 3))
        XCTAssertEqual(try PinRecord.decode(pin), PinRecord(type: "animal", animal: "Rhino", note: nil, lat: -24_996_352, lon: 31_592_345))
    }

    func testPaddingAndEscaping() throws {
        for name in ["A", "Zoë 🦏", String(repeating: "x", count: 30), "Quote \" and \\ / slash"] {
            let json = MemberProfile(name: name, colour: 11).json()
            XCTAssertEqual(json.count % 32, 0, name)
            XCTAssertEqual(try MemberProfile.decode(json).name, name)
        }
        let note = PinRecord(type: "danger", animal: nil, note: "Loose gravel\non the bend", lat: 1, lon: -1).json()
        XCTAssertEqual(note.count % 64, 0)
        XCTAssertTrue(String(decoding: note, as: UTF8.self).hasPrefix(#"{"lat":1,"lon":-1,"note":"Loose gravel\non the bend","pad":""#))
        XCTAssertEqual(try PinRecord.decode(note).note, "Loose gravel\non the bend")
        // An empty note is left out.
        XCTAssertFalse(String(decoding: PinRecord(type: "water", animal: nil, note: "", lat: 0, lon: 0).json(), as: UTF8.self).contains("note"))
        XCTAssertEqual(GroupJSON.quoted("a\u{1}b/é"), "\"a\\u0001b/é\"")
    }

    func testEveryRecordDecodes() throws {
        func plain(_ name: String) throws -> Data { unhex(try XCTUnwrap(v.records[name]).plainHex) }
        XCTAssertEqual(try GroupProfile.decode(plain("groupProfile")), GroupProfile(name: "Kruger trip"))
        XCTAssertEqual(try GroupProfile.decode(plain("groupProfilePadded")), GroupProfile(name: "Kruger trip"))
        XCTAssertEqual(try MemberProfile.decode(plain("memberProfile")), MemberProfile(name: "Thandi", colour: 3))
        XCTAssertEqual(try MemberProfile.decode(plain("memberProfilePadded")), MemberProfile(name: "Thandi", colour: 3))
        let zoe = try MemberProfile.decode(plain("memberProfileUnicode"))
        XCTAssertEqual(zoe, MemberProfile(name: "Zoë 🦏", colour: 0))
        XCTAssertEqual(GroupText.codePoints(zoe.name), 5)
        XCTAssertEqual(zoe.name.utf8.count, 9)
        let animal = try PinRecord.decode(plain("pinAnimal"))
        XCTAssertEqual(animal, PinRecord(type: "animal", animal: "Rhino", note: "Two adults near the water hole", lat: -24_996_352, lon: 31_592_345))
        XCTAssertEqual(animal.knownType, .animal)
        XCTAssertEqual(try PinRecord.decode(Data(v.pin.json.utf8)), animal)
        XCTAssertEqual(try PinRecord.decode(plain("pinWater")), PinRecord(type: "water", animal: nil, note: nil, lat: -24_990_001, lon: 31_600_000))
        XCTAssertEqual(try PinRecord.decode(plain("pinAnimalPadded")), PinRecord(type: "animal", animal: "Rhino", note: nil, lat: -24_996_352, lon: 31_592_345))
        XCTAssertEqual(v.pin.pinExpiresAt - v.pin.pinCreatedAt, GroupPolicy.animalPinLifetime)
    }

    func testReadersRejectWrongTypes() throws {
        func pin(_ s: String) throws -> PinRecord { try PinRecord.decode(Data(s.utf8)) }
        XCTAssertThrowsError(try pin(#"{"lat":"1","lon":2,"type":"water","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lat":1.5,"lon":2,"type":"water","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lon":2,"type":"water","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lat":1,"lon":2,"type":7,"v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lat":1,"lon":2,"type":"animal","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"animal":"","lat":1,"lon":2,"type":"animal","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lat":90000001,"lon":2,"type":"water","v":1}"#))
        XCTAssertThrowsError(try pin(#"{"lat":1,"lon":2,"type":"water","v":2}"#)) { XCTAssertEqual($0 as? GroupRecordError, .unsupportedVersion) }
        XCTAssertThrowsError(try pin(#"{"lat":1,"lon":2,"note":"\#(String(repeating: "x", count: 141))","type":"water","v":1}"#))
        XCTAssertThrowsError(try pin("not json"))
        // Any key order, unknown keys and an unknown type are fine.
        let other = try pin(#"{"v":1,"photo":"no","type":"campsite","lon":2,"lat":1}"#)
        XCTAssertEqual(other.type, "campsite")
        XCTAssertNil(other.knownType)
        XCTAssertThrowsError(try MemberProfile.decode(Data(#"{"colour":"3","name":"Thandi","v":1}"#.utf8)))
        XCTAssertThrowsError(try MemberProfile.decode(Data(#"{"colour":3,"name":"","v":1}"#.utf8)))
        XCTAssertThrowsError(try MemberProfile.decode(Data(#"{"colour":3,"name":true,"v":1}"#.utf8)))
        XCTAssertEqual(try MemberProfile.decode(Data(#"{"colour":14,"name":"Thandi","v":1}"#.utf8)).colour, 14)
        XCTAssertThrowsError(try GroupProfile.decode(Data(#"{"name":"Kruger trip"}"#.utf8)))
        XCTAssertThrowsError(try GroupProfile.decode(Data(#"{"name":"\#(String(repeating: "a", count: 41))","v":1}"#.utf8)))
    }

    // MARK: - Numbers

    func testCoordinates() {
        XCTAssertEqual(v.coordinates.count, 13)
        for c in v.coordinates {
            XCTAssertEqual(MicroDegrees.from(c.deg), c.e6, "\(c.deg)")
            XCTAssertEqual(MicroDegrees.degrees(c.e6), Double(c.e6) / 1e6)
        }
    }

    func testVarints() {
        for c in v.varints {
            var out: [UInt8] = []
            GroupVarint.append(c.value, to: &out)
            XCTAssertEqual(hex(Data(out)), c.hex)
            var i = 0
            XCTAssertEqual(GroupVarint.read([UInt8](unhex(c.hex)), &i), c.value)
            XCTAssertEqual(i, out.count)
        }
        var i = 0
        XCTAssertNil(GroupVarint.read([0xFF, 0xFF, 0xFF, 0xFF, 0x10], &i), "2^32")
        i = 0
        XCTAssertNil(GroupVarint.read([0x80, 0x80, 0x80, 0x80, 0x80, 0x01], &i), "a sixth byte")
        i = 0
        XCTAssertNil(GroupVarint.read([0x80], &i), "cut short")
        i = 0
        XCTAssertNil(GroupVarint.read([], &i))
    }

    func testZigzag() {
        for c in v.zigzag {
            XCTAssertEqual(GroupVarint.zigzag(c.value), c.zigzag)
            XCTAssertEqual(GroupVarint.unzigzag(c.zigzag), c.value)
        }
    }
}
