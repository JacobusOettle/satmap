import Foundation
import XCTest
@testable import SatMapCore

/// A transport that records requests and answers with what the test queued.
final class RecordingTransport: GroupTransport {
    struct Request: Equatable { let method: String; let path: String; let pass: String?; let json: Data? }
    var requests: [Request] = []
    var answers: [(Int, Data)] = []
    var error: GroupTransportError?

    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data) {
        requests.append(Request(method: method, path: path, pass: pass, json: json))
        if let e = error { throw e }
        return answers.isEmpty ? (204, Data()) : answers.removeFirst()
    }

    func jsonBody(_ i: Int) -> [String: Any]? {
        requests[i].json.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
    }
}

/// The API models and request building (docs/groups/SPEC.md §6), checked against the spec's own examples.
final class GroupAPITests: XCTestCase {
    func testStatusDecodesTheSpecExample() throws {
        let json = """
            {"groupId":"oKGio6SlpqeoqaqrrK2urw","status":"active","pausedReason":null,"pausedAt":null,"deleteAt":null,"pausedIntervals":[],
             "ownerId":"sLGys7S1tre4ubq7vL2-vw","memberCount":5,"profile":"AREREREREREREREREV_yTwsC5LIDzG7sS6sW_icQlP6dKzC1hZkPTH6_brQ5KAIFCwbmb1tOUemc",
             "profileSeq":1,"you":{"memberId":"sLGys7S1tre4ubq7vL2-vw","role":"owner","joinedAt":1793613000},"transfer":null,
             "pro":{"platform":"apple","productId":"pro.yearly","expiresAt":1825149600},"watchers":2,"seq":812,"serverTime":1793613622,"future":true}
            """
        let s = try JSONDecoder().decode(GroupStatusDTO.self, from: Data(json.utf8))
        XCTAssertEqual(s.memberCount, 5)
        XCTAssertEqual(s.you.role, "owner")
        XCTAssertEqual(s.pro?.expiresAt, 1_825_149_600)
        XCTAssertEqual(s.watchers, 2)
        XCTAssertFalse(s.isPaused)
        XCTAssertTrue(s.pauses.isEmpty)
        let paused = try JSONDecoder().decode(GroupStatusDTO.self, from: Data(json.replacingOccurrences(of: "\"status\":\"active\"", with: "\"status\":\"paused\"")
            .replacingOccurrences(of: "\"pausedIntervals\":[]", with: "\"pausedIntervals\":[[100,200],[300,null]]").utf8))
        XCTAssertTrue(paused.isPaused)
        XCTAssertEqual(paused.pauses.map(\.from), [100, 300])
        XCTAssertEqual(paused.pauses.map(\.to), [200, nil])
        // It survives the store's JSON round trip.
        XCTAssertEqual(try JSONDecoder().decode(GroupStatusDTO.self, from: JSONEncoder().encode(paused)), paused)
        // A wrong type is refused.
        XCTAssertThrowsError(try JSONDecoder().decode(GroupStatusDTO.self, from: Data(json.replacingOccurrences(of: "\"memberCount\":5", with: "\"memberCount\":\"5\"").utf8)))
    }

    func testChangeItems() throws {
        let json = """
            [{"kind": "group",   "seq": 1,  "profile": "e1"},
             {"kind": "member",  "seq": 2,  "memberId": "m", "role": "owner", "joinedAt": 1793613000, "profile": "e2"},
             {"kind": "chunk",   "seq": 40, "memberId": "m", "start": 1793613600, "rec": "e3"},
             {"kind": "archive", "seq": 41, "memberId": "m", "month": "2026-09", "maxChunkSeq": 39, "archivedAt": 1793581200},
             {"kind": "pin",     "seq": 42, "pinId": "p", "memberId": "m", "createdAt": 1793613900, "expiresAt": 1793657100, "rec": "e4"},
             {"kind": "pin",     "seq": 43, "pinId": "q", "memberId": "m", "createdAt": 1793613900, "expiresAt": null, "rec": "e5"},
             {"kind": "removed", "seq": 50, "what": "pin",    "id": "p"},
             {"kind": "photo",   "seq": 60, "url": "x"}]
            """
        let items = try JSONDecoder().decode([ChangeItem].self, from: Data(json.utf8))
        XCTAssertEqual(items, [
            .group(seq: 1, profile: "e1"),
            .member(seq: 2, memberId: "m", role: "owner", joinedAt: 1_793_613_000, profile: "e2"),
            .chunk(seq: 40, memberId: "m", start: 1_793_613_600, rec: "e3"),
            .archive(seq: 41, memberId: "m", month: "2026-09", maxChunkSeq: 39, archivedAt: 1_793_581_200),
            .pin(seq: 42, pinId: "p", memberId: "m", createdAt: 1_793_613_900, expiresAt: 1_793_657_100, rec: "e4"),
            .pin(seq: 43, pinId: "q", memberId: "m", createdAt: 1_793_613_900, expiresAt: nil, rec: "e5"),
            .removed(seq: 50, what: "pin", id: "p"),
            .unknown(seq: 60),
        ])
        XCTAssertEqual(items.map(\.seq), [1, 2, 40, 41, 42, 43, 50, 60])
        XCTAssertEqual(try JSONDecoder().decode([ChangeItem].self, from: JSONEncoder().encode(Array(items.dropLast()))), Array(items.dropLast()))
        let page = try JSONDecoder().decode(ChangesPage.self, from: Data("{\"items\":[],\"cursor\":812,\"more\":false,\"serverTime\":5}".utf8))
        XCTAssertEqual(page, ChangesPage(items: [], cursor: 812, more: false, serverTime: 5))
        let reset = try JSONDecoder().decode(ChangesPage.self, from: Data("{\"reset\":true,\"cursor\":0}".utf8))
        XCTAssertTrue(reset.reset)
        XCTAssertEqual(reset.cursor, 0)
    }

    func testUploadBodies() throws {
        let chunk = UploadRequest.Chunk(start: 1_793_613_600, rec: "AQID")
        func object(_ data: Data) throws -> [String: Any] {
            try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        }
        let heartbeat = try object(UploadRequest(chunks: [], live: .unchanged).json())
        XCTAssertEqual(heartbeat.keys.sorted(), ["chunks"])
        let stopped = try object(UploadRequest(chunks: [], live: .stopped).json())
        XCTAssertTrue(stopped["live"] is NSNull)
        let full = UploadRequest(chunks: [chunk], live: .position(.init(at: 1_793_613_620, rec: "BAUG")))
        let body = try object(full.json())
        XCTAssertEqual((body["live"] as? [String: Any])?["at"] as? Int, 1_793_613_620)
        XCTAssertEqual(((body["chunks"] as? [[String: Any]])?.first?["start"]) as? Int, 1_793_613_600)
        let socket = try object(full.json(socketId: 17))
        XCTAssertEqual(socket["type"] as? String, "upload")
        XCTAssertEqual(socket["id"] as? Int, 17)
        // The fake server reads what the hub writes.
        for request in [full, UploadRequest(chunks: [], live: .stopped), UploadRequest(chunks: [chunk], live: .unchanged)] {
            XCTAssertEqual(try JSONDecoder().decode(UploadRequest.self, from: request.json()), request)
        }
        XCTAssertEqual(UploadRequest.size(chunks: [chunk], live: .unchanged), full.json().count - ",\"live\":{\"at\":1793613620,\"rec\":\"BAUG\"}".count)
    }

    func testSmallModels() throws {
        let pin = try JSONSerialization.jsonObject(with: JSONEncoder().encode(PinRequest(pinId: "p", createdAt: 1, expiresAt: nil, rec: "r"))) as? [String: Any]
        XCTAssertTrue(pin?["expiresAt"] is NSNull, "expiresAt is sent as null")
        XCTAssertEqual(try JSONDecoder().decode(PinRequest.self, from: JSONEncoder().encode(PinRequest(pinId: "p", createdAt: 1, expiresAt: 43_201, rec: "r"))).expiresAt, 43_201)
        let live = try JSONSerialization.jsonObject(with: JSONEncoder().encode(LiveEntry(memberId: "m", at: 5, rec: nil))) as? [String: Any]
        XCTAssertTrue(live?["rec"] is NSNull)
        let apple = try JSONSerialization.jsonObject(with: JSONEncoder().encode(ProProof.apple(signedTransaction: "jws"))) as? [String: String]
        XCTAssertEqual(apple, ["platform": "apple", "signedTransaction": "jws"])
        let google = try JSONSerialization.jsonObject(with: JSONEncoder().encode(ProProof.google(productId: "pro", purchaseToken: "t"))) as? [String: String]
        XCTAssertEqual(google, ["platform": "google", "productId": "pro", "purchaseToken": "t"])
        XCTAssertEqual(try JSONDecoder().decode(ProProof.self, from: Data(#"{"platform":"google","productId":"pro","purchaseToken":"t"}"#.utf8)),
                       .google(productId: "pro", purchaseToken: "t"))
        XCTAssertThrowsError(try JSONDecoder().decode(ProProof.self, from: Data(#"{"platform":"web"}"#.utf8)))
    }

    func testSocketMessages() throws {
        XCTAssertEqual(SocketMessage.hello(watching: true, cursor: 812), #"{"type":"hello","v":1,"watching":true,"cursor":812}"#)
        XCTAssertEqual(SocketMessage.watching(false), #"{"type":"watching","on":false}"#)
        XCTAssertEqual(SocketMessage.ack(cursor: 830), #"{"type":"ack","cursor":830}"#)
        XCTAssertEqual(SocketMessage.ping, "ping")
        XCTAssertNil(SocketMessage.parse("pong"))
        XCTAssertEqual(SocketMessage.parse(#"{"type":"news"}"#), .unknown)
        XCTAssertEqual(SocketMessage.parse(#"{"type":"uploaded","id":17,"accepted":[1793613600],"rejected":[{"start":1793000000,"error":"before_join"}],"liveAccepted":true}"#),
                       .uploaded(id: 17, result: UploadResult(accepted: [1_793_613_600], rejected: [UploadRejection(start: 1_793_000_000, error: "before_join")],
                                                              liveAccepted: true, watchers: nil, status: nil, serverTime: nil)))
        XCTAssertEqual(SocketMessage.parse(#"{"type":"error","id":17,"error":"group_paused","message":"…"}"#), .error(id: 17, code: "group_paused"))
        XCTAssertEqual(SocketMessage.parse(#"{"type":"error","error":"bad_request"}"#), .error(id: nil, code: "bad_request"))
        guard case .tick(let tick) = SocketMessage.parse("""
            {"type":"tick","serverTime":7,"watchers":2,"live":[{"memberId":"m","at":5,"rec":null}],
             "changes":{"from":812,"items":[{"kind":"removed","seq":813,"what":"trail","id":"m"}],"cursor":813,"more":false}}
            """) else { return XCTFail("not a tick") }
        XCTAssertEqual(tick.serverTime, 7)
        XCTAssertEqual(tick.watchers, 2)
        XCTAssertEqual(tick.live, [LiveEntry(memberId: "m", at: 5, rec: nil)])
        XCTAssertEqual(tick.changes?.items, [.removed(seq: 813, what: "trail", id: "m")])
        XCTAssertNil(tick.status)
    }

    func testArchiveObjects() throws {
        let records: [(start: Int64, rec: Data)] = [(1_793_614_200, Data([1, 2, 3])), (1_793_613_600, Data(repeating: 9, count: 40))]
        let built = GroupArchive.build(records)
        XCTAssertEqual(Array(built.prefix(5)), Array("SMGA".utf8) + [0x01])
        let parsed = try XCTUnwrap(GroupArchive.parse(built))
        XCTAssertEqual(parsed.map(\.start), [1_793_613_600, 1_793_614_200])
        XCTAssertEqual(parsed.map(\.rec), [Data(repeating: 9, count: 40), Data([1, 2, 3])])
        XCTAssertEqual(GroupArchive.parse(Data("SMGA".utf8) + Data([1]))?.count, 0)
        XCTAssertNil(GroupArchive.parse(Data("SMGB".utf8) + Data([1])))
        XCTAssertNil(GroupArchive.parse(Data("SMGA".utf8) + Data([2])))
        XCTAssertNil(GroupArchive.parse(built.dropLast()))
        XCTAssertNil(GroupArchive.parse(built + Data([0, 0, 0])))
    }

    func testRequestsAndErrors() async throws {
        let t = RecordingTransport()
        let api = GroupAPI(transport: t)
        t.answers = [(200, Data(#"{"items":[],"cursor":7,"more":false,"serverTime":9}"#.utf8))]
        _ = try await api.changes("G", pass: "P", since: 7)
        XCTAssertEqual(t.requests.last, .init(method: "GET", path: "/v1/groups/G/changes?since=7&limit=500", pass: "P", json: nil))
        try await api.removeMember("G", pass: "P", memberId: "me")
        XCTAssertEqual(t.requests.last?.method, "DELETE")
        XCTAssertEqual(t.requests.last?.path, "/v1/groups/G/members/me")
        try await api.clearTrail("G", pass: "P")
        XCTAssertEqual(t.requests.last?.path, "/v1/groups/G/trail/me")
        t.answers = [(200, Data(#"{"seq":3}"#.utf8))]
        _ = try await api.setRole("G", pass: "P", memberId: "M", role: "admin")
        XCTAssertEqual(t.requests.last?.path, "/v1/groups/G/members/M/role")
        XCTAssertEqual(t.jsonBody(t.requests.count - 1) as? [String: String], ["role": "admin"])
        t.answers = [(201, Data(#"{"inviteId":"i","token":"t","createdAt":1,"expiresAt":2}"#.utf8))]
        _ = try await api.createInvite("G", pass: "P")
        XCTAssertEqual(t.requests.last?.json, Data("{}".utf8))
        // Errors: the code from the body; a body that doesn't parse gives an empty code; a 2xx that doesn't parse is
        // bad_response; transport errors pass through.
        t.answers = [(404, Data(#"{"error":"invite_not_found","message":"x"}"#.utf8))]
        do {
            _ = try await api.preview(token: "t", joinProof: "j")
            XCTFail("no error")
        } catch let e as GroupAPIError {
            XCTAssertEqual(e, GroupAPIError(status: 404, code: "invite_not_found"))
        }
        XCTAssertEqual(t.requests.last?.path, "/v1/invites/preview")
        XCTAssertNil(t.requests.last?.pass)
        t.answers = [(502, Data("<html>".utf8))]
        do {
            try await api.deletePin("G", pass: "P", pinId: "p")
            XCTFail("no error")
        } catch let e as GroupAPIError {
            XCTAssertEqual(e, GroupAPIError(status: 502, code: ""))
        }
        t.answers = [(200, Data("[]".utf8))]
        do {
            _ = try await api.status("G", pass: "P")
            XCTFail("no error")
        } catch let e as GroupAPIError {
            XCTAssertEqual(e.code, GroupAPIError.badResponse)
        }
        t.error = .offline
        do {
            _ = try await api.live("G", pass: "P")
            XCTFail("no error")
        } catch let e as GroupTransportError {
            XCTAssertEqual(e, .offline)
        }
    }
}
