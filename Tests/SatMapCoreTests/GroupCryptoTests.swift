import Foundation
import XCTest
@testable import SatMapCore

/// The shared group vectors (docs/groups/SPEC.md §3.6, §4.8; made by docs/groups/vectors.py). The Android core's
/// tests (android/core/src/test/…/Group*Tests.kt) check the same file.
struct GroupVectors: Decodable {
    struct Inputs: Decodable {
        let groupKeyHex, groupKey, groupIdHex, groupId, memberIdHex, memberId, pinIdHex, pinId: String
        let inviteSecretHex, passHex, pass: String
        let windowStart: Int64
    }

    struct Hashes: Decodable {
        let joinProof: String
    }

    struct Invite: Decodable {
        let token, domain, url, schemeUrl: String
    }

    struct Record: Decodable {
        let key, aad, nonceHex, plainHex, envelopeHex, envelope: String
    }

    struct Writers: Decodable {
        struct Group: Decodable { let name, json: String }
        struct Member: Decodable { let name: String; let colour: Int; let json: String }
        struct Pin: Decodable { let type, animal: String; let lat, lon: Int32; let json: String }
        let groupProfile: Group
        let memberProfile: Member
        let pinAnimal: Pin
    }

    struct Point: Decodable {
        let time: Int64
        let lat, lon: Int32
        let startsSegment: Bool
        var point: TrailPoint { TrailPoint(time: time, lat: lat, lon: lon, startsSegment: startsSegment) }
    }

    struct Chunk: Decodable {
        let windowStart: Int64
        let points: [Point]
        let plainHex: String
        let bodyHex: String?
    }

    struct Live: Decodable {
        let time: Int64
        let lat, lon: Int32
        let accuracy, heading, speed: Int?
        let plainHex: String?
        var position: LivePosition { LivePosition(time: time, lat: lat, lon: lon, accuracy: accuracy, heading: heading, speed: speed) }
    }

    struct Pin: Decodable {
        let pinCreatedAt, pinExpiresAt: Int64
        let json: String
    }

    struct MustFail: Decodable {
        let tamperedTrailEnvelopeHex, trailWrongWindowAad, unknownVersionEnvelopeHex, shortEnvelopeHex: String
        let badBase64url, nonCanonicalIds: [String]
        let chunkPlainHex: [String: String]
    }

    struct Coordinate: Decodable { let deg: Double; let e6: Int32 }
    struct Varint: Decodable { let value: UInt32; let hex: String }
    struct Zigzag: Decodable { let value: Int32; let zigzag: UInt32 }

    struct InviteParse: Decodable {
        struct Case: Decodable { let text, result: String; let token, groupIdHex, groupKeyHex: String? }
        let domain: String
        let cases: [Case]
    }

    struct Text: Decodable { let field, input: String; let output: String? }

    struct Words: Decodable {
        struct Case: Decodable { let text: String; let allows: Bool; let masked: String }
        let list: String
        let cases: [Case]
    }

    struct Ago: Decodable { let seconds: Int64; let text: String }
    struct Initials: Decodable { let name, initials: String }

    struct Rounding: Decodable {
        struct Fix: Decodable { let lat, lon: Double; let accuracy, course, speed, heading: Double?; let time: Double }
        let fix: Fix
        let live: Live
    }

    let inputs: Inputs
    let subkeyInfo: [String: String]
    let subkeys: [String: String]
    let hashes: Hashes
    let invite: Invite
    let records: [String: Record]
    let jsonWriters: Writers
    let trailChunk: Chunk
    let trailChunkDeflated: Chunk
    let live: Live
    let liveUnknowns: Live
    let pin: Pin
    let mustFail: MustFail
    let coordinates: [Coordinate]
    let varints: [Varint]
    let zigzag: [Zigzag]
    let inviteParse: InviteParse
    let groupText: [Text]
    let wordFilter: Words
    let ago: [Ago]
    let initials: [Initials]
    let liveRounding: [Rounding]

    static func load() throws -> GroupVectors {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/groups/vectors.json", withExtension: nil))
        return try JSONDecoder().decode(GroupVectors.self, from: Data(contentsOf: url))
    }
}

func unhex(_ text: String, file: StaticString = #filePath, line: UInt = #line) -> Data {
    guard let data = Data(hex: text) else {
        XCTFail("not hex: \(text)", file: file, line: line)
        return Data()
    }
    return data
}

final class GroupCryptoTests: XCTestCase {
    private var v: GroupVectors!
    private var groupKey: Data { unhex(v.inputs.groupKeyHex) }
    private var groupId: Data { unhex(v.inputs.groupIdHex) }

    override func setUpWithError() throws {
        v = try GroupVectors.load()
    }

    func testInputsAreBase64URL() {
        XCTAssertEqual(Base64URL.encode(groupKey), v.inputs.groupKey)
        XCTAssertEqual(Base64URL.encode(groupId), v.inputs.groupId)
        XCTAssertEqual(Base64URL.encode(unhex(v.inputs.memberIdHex)), v.inputs.memberId)
        XCTAssertEqual(Base64URL.encode(unhex(v.inputs.pinIdHex)), v.inputs.pinId)
        XCTAssertEqual(Base64URL.encode(unhex(v.inputs.passHex)), v.inputs.pass)
        XCTAssertEqual(Base64URL.decode(v.inputs.groupKey), groupKey)
        XCTAssertEqual(Base64URL.decode(v.inputs.memberId, count: 16), unhex(v.inputs.memberIdHex))
        XCTAssertNil(Base64URL.decode(v.inputs.memberId, count: 32))
        XCTAssertEqual(Base64URL.decode(""), Data())
        for n in 0..<40 {
            let data = Data((0..<n).map { UInt8(($0 * 37 + 11) % 256) })
            let text = Base64URL.encode(data)
            XCTAssertFalse(text.contains("="))
            XCTAssertEqual(Base64URL.decode(text), data)
        }
    }

    func testSubkeysFromTheirInfo() {
        let prefix = "satmap.group.v1|key|"
        XCTAssertEqual(Set(v.subkeyInfo.keys), Set(v.subkeys.keys))
        for (name, info) in v.subkeyInfo {
            XCTAssertTrue(info.hasPrefix(prefix), name)
            let purpose = String(info.dropFirst(prefix.count))
            XCTAssertEqual(hex(GroupCrypto.subkey(groupKey: groupKey, groupId: groupId, purpose: purpose)), v.subkeys[name], name)
        }
    }

    func testGroupKeys() {
        let keys = GroupKeys(groupKey: groupKey, groupId: groupId)
        XCTAssertEqual(keys.groupId, v.inputs.groupId)
        XCTAssertEqual(hex(keys.group), v.subkeys["group"])
        XCTAssertEqual(hex(keys.member), v.subkeys["member"])
        XCTAssertEqual(hex(keys.pin), v.subkeys["pin"])
        XCTAssertEqual(hex(keys.joinProof), v.subkeys["join"])
        XCTAssertEqual(Base64URL.encode(keys.joinProof), v.hashes.joinProof)
        XCTAssertEqual(hex(keys.trail(v.inputs.memberId)), v.subkeys["trail"])
        XCTAssertEqual(hex(keys.live(v.inputs.memberId)), v.subkeys["live"])
        // Cached, and per member.
        XCTAssertEqual(hex(keys.trail(v.inputs.memberId)), v.subkeys["trail"])
        XCTAssertNotEqual(keys.trail(v.inputs.pinId), keys.trail(v.inputs.memberId))
        XCTAssertNotEqual(keys.live(v.inputs.memberId), keys.trail(v.inputs.memberId))
    }

    func testAssociatedData() {
        let g = v.inputs.groupId, m = v.inputs.memberId, p = v.inputs.pinId, w = v.inputs.windowStart
        XCTAssertEqual(GroupAAD.group(g), v.records["groupProfile"]?.aad)
        XCTAssertEqual(GroupAAD.member(g, m), v.records["memberProfile"]?.aad)
        XCTAssertEqual(GroupAAD.trail(g, m, start: w), v.records["trailChunk"]?.aad)
        XCTAssertEqual(GroupAAD.live(g, m, at: v.live.time), v.records["live"]?.aad)
        XCTAssertEqual(GroupAAD.live(g, m, at: v.liveUnknowns.time), v.records["liveUnknowns"]?.aad)
        XCTAssertEqual(GroupAAD.pin(g, pinId: p, memberId: m, createdAt: v.pin.pinCreatedAt, expiresAt: v.pin.pinExpiresAt),
                       v.records["pinAnimal"]?.aad)
        XCTAssertEqual(GroupAAD.pin(g, pinId: p, memberId: m, createdAt: v.pin.pinCreatedAt, expiresAt: nil), v.records["pinWater"]?.aad)
        XCTAssertEqual(GroupAAD.trail(g, m, start: w + 600), v.mustFail.trailWrongWindowAad)
    }

    func testSealsAndOpensEveryRecord() throws {
        XCTAssertEqual(v.records.count, 12)
        for (name, r) in v.records {
            let key = unhex(try XCTUnwrap(v.subkeys[r.key], name))
            let sealed = try GroupCrypto.seal(unhex(r.plainHex), key: key, aad: r.aad, nonce: unhex(r.nonceHex))
            XCTAssertEqual(hex(sealed), r.envelopeHex, name)
            XCTAssertEqual(Base64URL.encode(sealed), r.envelope, name)
            XCTAssertEqual(hex(try GroupCrypto.open(unhex(r.envelopeHex), key: key, aad: r.aad)), r.plainHex, name)
            XCTAssertEqual(try GroupCrypto.open(try XCTUnwrap(Base64URL.decode(r.envelope)), key: key, aad: r.aad), unhex(r.plainHex), name)
        }
        XCTAssertEqual(unhex(try XCTUnwrap(v.records["pinAnimal"]).envelopeHex).count, 140)
        XCTAssertEqual(unhex(try XCTUnwrap(v.records["pinAnimalPadded"]).envelopeHex).count, 157)
    }

    func testRandomNonces() throws {
        let key = GroupRandom.bytes(32)
        let plain = Data("Kruger trip".utf8)
        let a = try GroupCrypto.seal(plain, key: key, aad: "x")
        let b = try GroupCrypto.seal(plain, key: key, aad: "x")
        XCTAssertEqual(a.count, 1 + 12 + plain.count + 16)
        XCTAssertEqual(a.first, 0x01)
        XCTAssertNotEqual(a.subdata(in: 1..<13), b.subdata(in: 1..<13))
        XCTAssertEqual(try GroupCrypto.open(a, key: key, aad: "x"), plain)
        XCTAssertEqual(try GroupCrypto.open(GroupCrypto.seal(Data(), key: key, aad: ""), key: key, aad: ""), Data())
        XCTAssertNotEqual(GroupRandom.bytes(16), GroupRandom.bytes(16))
        XCTAssertEqual(GroupRandom.id().count, 22)
        XCTAssertEqual(Base64URL.decode(GroupRandom.id(), count: 16)?.count, 16)
    }

    func testMustFail() throws {
        let trail = try XCTUnwrap(v.records["trailChunk"])
        let key = unhex(try XCTUnwrap(v.subkeys["trail"]))
        func failure(_ envelope: Data, aad: String) -> GroupCrypto.Failure? {
            do {
                _ = try GroupCrypto.open(envelope, key: key, aad: aad)
                return nil
            } catch {
                return error as? GroupCrypto.Failure
            }
        }
        XCTAssertEqual(failure(unhex(v.mustFail.tamperedTrailEnvelopeHex), aad: trail.aad), .unreadable)
        XCTAssertEqual(failure(unhex(trail.envelopeHex), aad: v.mustFail.trailWrongWindowAad), .unreadable)
        XCTAssertEqual(failure(unhex(v.mustFail.unknownVersionEnvelopeHex), aad: trail.aad), .unsupportedVersion)
        XCTAssertEqual(unhex(v.mustFail.shortEnvelopeHex).count, 28)
        XCTAssertEqual(failure(unhex(v.mustFail.shortEnvelopeHex), aad: trail.aad), .unreadable)
        XCTAssertEqual(failure(Data(), aad: trail.aad), .unreadable)
        // Another key, another member's trail key.
        XCTAssertThrowsError(try GroupCrypto.open(unhex(trail.envelopeHex), key: unhex(v.subkeys["live"]!), aad: trail.aad))
        XCTAssertThrowsError(try GroupCrypto.open(unhex(trail.envelopeHex), key: Data(count: 16), aad: trail.aad))
        XCTAssertThrowsError(try GroupCrypto.seal(Data(), key: Data(count: 31), aad: ""))
        XCTAssertThrowsError(try GroupCrypto.seal(Data(), key: key, aad: "", nonce: Data(count: 11)))
        for text in v.mustFail.badBase64url {
            XCTAssertNil(Base64URL.decode(text), text)
        }
        // Clients may accept non-canonical ids (only the server must refuse them); they still decode to 16 bytes.
        for text in v.mustFail.nonCanonicalIds {
            XCTAssertEqual(Base64URL.decode(text, count: 16)?.count, 16, text)
            XCTAssertNotEqual(Base64URL.encode(Base64URL.decode(text)!), text)
        }
    }
}
