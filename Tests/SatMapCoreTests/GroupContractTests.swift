import Foundation
import XCTest
@testable import SatMapCore

/// A JSON value, so bodies can be compared whatever their key order, spacing or number spelling.
enum JSONValue: Codable, Equatable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else if let i = try? c.decode(Int64.self) {
            self = .int(i)
        } else if let d = try? c.decode(Double.self) {
            self = .double(d)
        } else if let s = try? c.decode(String.self) {
            self = .string(s)
        } else if let a = try? c.decode([JSONValue].self) {
            self = .array(a)
        } else {
            self = .object(try c.decode([String: JSONValue].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .int(let i): try c.encode(i)
        case .double(let d): try c.encode(d)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    static func parse(_ data: Data) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: data) }

    /// What a Codable value encodes to.
    static func of<T: Encodable>(_ value: T) throws -> JSONValue { try parse(JSONEncoder().encode(value)) }

    var data: Data { (try? JSONEncoder().encode(self)) ?? Data() }

    subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    var string: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    var int: Int64? {
        if case .int(let i) = self { return i }
        return nil
    }

    var bool: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    var array: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    var object: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    /// The value with every object member whose value is null left out, at any depth (nulls in arrays stay): the
    /// server writes `"key": null` where the Swift encoder leaves an unset optional out, and both mean the same.
    var withoutNullMembers: JSONValue {
        switch self {
        case .array(let a): return .array(a.map(\.withoutNullMembers))
        case .object(let o): return .object(o.filter { $0.value != .null }.mapValues(\.withoutNullMembers))
        default: return self
        }
    }

    /// The object without these keys.
    func removing(_ keys: String...) -> JSONValue {
        guard case .object(var o) = self else { return self }
        for k in keys { o.removeValue(forKey: k) }
        return .object(o)
    }
}

/// One example of Fixtures/groups/api-examples.json (docs/groups/SPEC.md §13.1), recorded by the server's integration
/// tests from real requests to the Worker.
struct APIExample: Decodable {
    let id: String
    let method: String
    let path: String
    let request: JSONValue?
    let status: Int
    let response: JSONValue?

    /// "E1", "E26", "A3" …
    var endpoint: String { String(id.prefix { $0 != "-" }) }

    /// The path's segments without the query: ["v1", "groups", groupId, …].
    var segments: [String] { path.split(separator: "?")[0].split(separator: "/").map(String.init) }

    var query: [String: String] {
        let parts = path.split(separator: "?", maxSplits: 1)
        guard parts.count == 2 else { return [:] }
        var out: [String: String] = [:]
        for item in parts[1].split(separator: "&") {
            let kv = item.split(separator: "=", maxSplits: 1).map(String.init)
            out[kv[0]] = kv.count > 1 ? kv[1] : ""
        }
        return out
    }

    static func loadAll() throws -> [APIExample] {
        struct File: Decodable { let examples: [APIExample] }
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/groups/api-examples.json", withExtension: nil))
        return try JSONDecoder().decode(File.self, from: Data(contentsOf: url)).examples
    }
}

/// Contract tests (docs/groups/SPEC.md §13.3 step 4). Every example the server recorded goes through `GroupAPI`: the
/// core must build the request the server accepted (method, path, Bearer or not, and the body as the server reads
/// it), and read every answer: a success body back to the same JSON, every error to its status and §6.2 code. The
/// WebSocket frames go through `SocketMessage` the same way. The Android core's GroupContractTests read the same file.
final class GroupContractTests: XCTestCase {
    private var examples: [APIExample] = []

    /// Endpoints the apps never call: Apple's and Google's notifications, the health check, and the developer's admin
    /// endpoints (A1–A6).
    private static let notForApps: Set<String> = ["E27", "E28", "E29", "A1", "A2", "A3", "A4", "A5", "A6"]

    /// Every code in §6.2. The file must hold an example of each, so the cores are checked against what the server
    /// really sends for it.
    private static let errorCodes: Set<String> = [
        "bad_request", "bad_record", "unauthorized", "pro_invalid", "pro_inactive", "forbidden", "not_found",
        "invite_not_found", "group_not_found", "method_not_allowed", "group_exists", "member_exists", "pin_exists",
        "group_paused", "owner_must_hand_over", "not_allowed", "too_many_groups", "terms_outdated", "removed",
        "group_deleted", "pin_deleted", "too_large", "unsupported_media_type", "bad_time", "pin_expired",
        "upgrade_required", "rate_limited", "internal", "store_unavailable",
    ]

    override func setUpWithError() throws {
        examples = try APIExample.loadAll()
    }

    func testTheFileCoversEveryEndpointAndErrorCode() throws {
        XCTAssertEqual(Set(examples.map(\.id)).count, examples.count, "ids are unique")
        let endpoints = Set(examples.map(\.endpoint))
        // E27 can't be recorded: Apple's signed payload carries a fresh ECDSA signature on every run.
        for n in 1...26 {
            XCTAssertTrue(endpoints.contains("E\(n)"), "no example for E\(n)")
            XCTAssertTrue(examples.contains { $0.endpoint == "E\(n)" && ($0.method == "WS" || (200..<300).contains($0.status)) },
                          "no success example for E\(n)")
        }
        let codes = Set(examples.filter { !(100..<300).contains($0.status) }.compactMap { $0.response?["error"]?.string })
        XCTAssertEqual(codes.subtracting(Self.errorCodes), [], "codes the spec doesn't list")
        XCTAssertEqual(Self.errorCodes.subtracting(codes), [], "§6.2 codes with no example")
        for e in examples where !(100..<300).contains(e.status) {
            XCTAssertNotNil(e.response?["error"]?.string, "\(e.id): an error answer has an error code")
        }
        let frames = Set(examples.filter { $0.method == "WS" }.compactMap { $0.response?["type"]?.string })
        XCTAssertEqual(frames, ["welcome", "uploaded", "error", "tick"], "every server frame of §6.6")
    }

    func testEveryHTTPExampleBuildsTheSameRequestAndReadsTheAnswer() async throws {
        var checked = 0
        for e in examples where e.method != "WS" && !Self.notForApps.contains(e.endpoint) {
            try await check(e)
            checked += 1
        }
        XCTAssertGreaterThan(checked, 70)
    }

    func testEveryWebSocketFrame() throws {
        let frames = examples.filter { $0.method == "WS" }
        XCTAssertFalse(frames.isEmpty)
        for e in frames {
            if let request = e.request {
                XCTAssertEqual(Self.normalised(e.endpoint, try JSONValue.parse(Data(try sentFrame(e, request).utf8))),
                               Self.normalised(e.endpoint, request), "\(e.id): the frame the core sends")
            }
            if let response = e.response {
                try checkServerFrame(e, response)
            }
        }
    }

    // MARK: HTTP

    /// Runs the example's request through GroupAPI, answering with the recorded status and body.
    private func check(_ e: APIExample) async throws {
        let t = RecordingTransport()
        t.answers = [(e.status, e.response?.data ?? Data())]
        let api = GroupAPI(transport: t)
        let outcome: Result<JSONValue?, Error>
        do {
            outcome = .success(try await call(e, api))
        } catch {
            outcome = .failure(error)
        }
        if let sent = t.requests.first {
            XCTAssertEqual(t.requests.count, 1, "\(e.id): one request")
            XCTAssertEqual(sent.method, e.method, "\(e.id): method")
            XCTAssertEqual(Self.normalisedPath(sent.path), Self.normalisedPath(e.path), "\(e.id): path")
            let anonymous: Set<String> = ["E1", "E9", "E10"]
            XCTAssertEqual(sent.pass == nil, anonymous.contains(e.endpoint), "\(e.id): Bearer pass sent only where §6.1 asks for it")
            if Self.rawBodies.contains(e.id) {
                XCTAssertNotNil(sent.json, "\(e.id): a body is sent")
            } else if let request = e.request {
                let body = try JSONValue.parse(try XCTUnwrap(sent.json, "\(e.id): no body sent"))
                XCTAssertEqual(Self.normalised(e.endpoint, body), Self.normalised(e.endpoint, request), "\(e.id): body")
            } else {
                XCTAssertNil(sent.json, "\(e.id): no body")
            }
        } else {
            XCTAssertTrue(Self.notSentByTheCore.contains(e.id), "\(e.id): the core sent nothing")
        }
        if (200..<300).contains(e.status) {
            switch outcome {
            case .success(let read):
                if let response = e.response {
                    let read = try XCTUnwrap(read, "\(e.id): the core read nothing")
                    XCTAssertEqual(read.withoutNullMembers, response.withoutNullMembers, "\(e.id): what the core read")
                }
            case .failure(let error):
                XCTFail("\(e.id): \(error)")
            }
        } else {
            let code = try XCTUnwrap(e.response?["error"]?.string, e.id)
            switch outcome {
            case .success:
                XCTFail("\(e.id): an error answer was taken for a success")
            case .failure(let error):
                XCTAssertEqual(error as? GroupAPIError, GroupAPIError(status: e.status, code: code), e.id)
            }
        }
    }

    /// Examples whose body the server recorded as null because it was raw bytes: a body over 64 KiB and one that isn't
    /// JSON. The core sends E1-created's body; only the answer is checked against them.
    private static let rawBodies: Set<String> = ["E1-too_large", "E1-unsupported_media_type"]

    /// Requests the core never makes (PATCH; a WebSocket path without `Upgrade`, which only the app's socket layer
    /// could send): only their error answers are read.
    private static let notSentByTheCore: Set<String> = ["E2-method_not_allowed", "E26-upgrade_required"]

    /// Calls the GroupAPI function for the example's endpoint with the values in its path and body; returns what the
    /// core read, as JSON (nil for an answer without a JSON body).
    private func call(_ e: APIExample, _ api: GroupAPI) async throws -> JSONValue? {
        let pass = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8"
        let p = e.segments
        let groupId = p.count > 2 ? p[2] : ""
        let body = e.request ?? .null
        if Self.notSentByTheCore.contains(e.id) {
            throw GroupAPI.error(e.status, e.response?.data ?? Data())
        }
        switch e.endpoint {
        case "E1":
            let source = Self.rawBodies.contains(e.id) ? try XCTUnwrap(examples.first { $0.id == "E1-created" }?.request) : body
            return try .of(await api.create(CreateGroupRequest(
                groupId: try text(source, "groupId"), memberId: try text(source, "memberId"), pass: try text(source, "pass"),
                joinProof: try text(source, "joinProof"), groupProfile: try text(source, "groupProfile"),
                memberProfile: try text(source, "memberProfile"), termsVersion: Int(try number(source, "termsVersion")),
                pro: try proof(source["pro"]))))
        case "E2":
            return try .of(await api.status(groupId, pass: pass))
        case "E3":
            return try .of(await api.renameGroup(groupId, pass: pass, profile: try text(body, "profile")))
        case "E4":
            try await api.deleteGroup(groupId, pass: pass)
            return nil
        case "E5":
            return try .of(await api.updatePro(groupId, pass: pass, pro: try proof(body["pro"])))
        case "E6":
            XCTAssertEqual(body, .object([:]), "\(e.id): E6 sends {}")
            return try .of(await api.createInvite(groupId, pass: pass))
        case "E7":
            return .object(["invites": try .of(await api.invites(groupId, pass: pass))])
        case "E8":
            try await api.cancelInvite(groupId, pass: pass, inviteId: p[4])
            return nil
        case "E9":
            return try .of(await api.preview(token: try text(body, "token"), joinProof: try text(body, "joinProof")))
        case "E10":
            return try .of(await api.redeem(RedeemRequest(
                token: try text(body, "token"), joinProof: try text(body, "joinProof"), memberId: try text(body, "memberId"),
                pass: try text(body, "pass"), profile: try text(body, "profile"), termsVersion: Int(try number(body, "termsVersion")))))
        case "E11":
            return .object(["members": try .of(await api.members(groupId, pass: pass))])
        case "E12":
            XCTAssertEqual(p[4], "me")
            return try .of(await api.updateMyProfile(groupId, pass: pass, profile: try text(body, "profile")))
        case "E13":
            return try .of(await api.setRole(groupId, pass: pass, memberId: p[4], role: try text(body, "role")))
        case "E14":
            try await api.removeMember(groupId, pass: pass, memberId: p[4])
            return nil
        case "E15":
            return try .of(await api.offerOwnership(groupId, pass: pass, to: try text(body, "to")))
        case "E16":
            try await api.cancelOwnershipOffer(groupId, pass: pass)
            return nil
        case "E17":
            return try .of(await api.acceptOwnership(groupId, pass: pass, pro: try proof(body["pro"])))
        case "E18":
            return try .of(await api.upload(groupId, pass: pass, try upload(body)))
        case "E19":
            let since = Int64(e.query["since"] ?? "0") ?? -1
            let limit = Int(e.query["limit"] ?? "500") ?? -1
            return try .of(await api.changes(groupId, pass: pass, since: since, limit: limit))
        case "E20":
            // The answer is the §4.9 object, which the recorder keeps as null: answer with an empty archive.
            let object = GroupArchive.build([])
            let t = try XCTUnwrap(api.transport as? RecordingTransport)
            if (200..<300).contains(e.status) { t.answers = [(e.status, object)] }
            let data = try await api.archive(groupId, pass: pass, memberId: p[4], month: p[5])
            XCTAssertEqual(data, object, "\(e.id): the archive bytes as sent")
            return nil
        case "E21":
            return try .of(await api.live(groupId, pass: pass))
        case "E22":
            XCTAssertEqual(p[4], "me")
            try await api.clearTrail(groupId, pass: pass)
            return nil
        case "E23":
            return try .of(await api.dropPin(groupId, pass: pass, PinRequest(
                pinId: try text(body, "pinId"), createdAt: try number(body, "createdAt"), expiresAt: body["expiresAt"]?.int,
                rec: try text(body, "rec"))))
        case "E24":
            try await api.deletePin(groupId, pass: pass, pinId: p[4])
            return nil
        case "E25":
            var content: [String: String] = [:]
            for (k, v) in body["content"]?.object ?? [:] { if let s = v.string { content[k] = s } }
            let reportId = try await api.report(groupId, pass: pass, ReportRequest(
                targetKind: try text(body, "targetKind"), targetId: try text(body, "targetId"), reason: try text(body, "reason"),
                comment: body["comment"]?.string ?? "", content: content))
            return .object(["reportId": .string(reportId)])
        default:
            XCTFail("\(e.id): no core call for \(e.endpoint)")
            return nil
        }
    }

    // MARK: WebSocket

    /// The text the core sends for the example's client frame.
    private func sentFrame(_ e: APIExample, _ request: JSONValue) throws -> String {
        switch try text(request, "type") {
        case "hello":
            XCTAssertEqual(request["v"], .int(1), e.id)
            return SocketMessage.hello(watching: try XCTUnwrap(request["watching"]?.bool), cursor: try number(request, "cursor"))
        case "watching":
            return SocketMessage.watching(try XCTUnwrap(request["on"]?.bool))
        case "ack":
            return SocketMessage.ack(cursor: try number(request, "cursor"))
        case "upload":
            return String(decoding: try upload(request).json(socketId: Int(try number(request, "id"))), as: UTF8.self)
        case let other:
            XCTFail("\(e.id): unknown client frame \(other)")
            return ""
        }
    }

    /// The server frame through `SocketMessage.parse`, and what it read back as JSON.
    private func checkServerFrame(_ e: APIExample, _ response: JSONValue) throws {
        let text = String(decoding: response.data, as: UTF8.self)
        let message = try XCTUnwrap(SocketMessage.parse(text), "\(e.id): the core can't read the frame")
        var read: [String: JSONValue] = [:]
        var expected = response
        switch message {
        case .welcome(let status, let live):
            // The core doesn't read `v`: there is only version 1.
            XCTAssertEqual(response["v"], .int(1), e.id)
            expected = response.removing("v")
            read = ["type": "welcome", "status": try .of(status), "live": try .of(live)]
        case .uploaded(let id, let result):
            read = ["type": "uploaded", "id": .int(Int64(id)), "accepted": try .of(result.accepted), "rejected": try .of(result.rejected)]
            if let l = result.liveAccepted { read["liveAccepted"] = .bool(l) }
        case .error(let id, let code):
            // `message` is for logs; apps never show it (§6.1).
            expected = response.removing("message")
            read = ["type": "error", "error": .string(code)]
            if let id = id { read["id"] = .int(Int64(id)) }
        case .tick(let tick):
            read = ["type": "tick"]
            if let v = tick.serverTime { read["serverTime"] = .int(v) }
            if let v = tick.watchers { read["watchers"] = .int(Int64(v)) }
            if let v = tick.live { read["live"] = try .of(v) }
            if let v = tick.changes { read["changes"] = try .of(v) }
            if let v = tick.status { read["status"] = try .of(v) }
        case .unknown:
            return XCTFail("\(e.id): the core doesn't know this frame")
        }
        XCTAssertEqual(JSONValue.object(read).withoutNullMembers, expected.withoutNullMembers, "\(e.id): what the core read")
    }

    // MARK: Helpers

    /// The body as the server reads it (§6.5): defaults filled in, so two bodies that mean the same compare equal.
    /// An E18 body (or WebSocket upload) without `chunks` has none; an E25 report without `comment` has an empty one,
    /// and its `content` keeps only string values.
    static func normalised(_ endpoint: String, _ body: JSONValue) -> JSONValue {
        guard case .object(var o) = body else { return body }
        if endpoint == "E18" || (endpoint == "E26" && o["type"] == .string("upload")) {
            if o["chunks"] == nil { o["chunks"] = .array([]) }
        }
        if endpoint == "E25" {
            if o["comment"] == nil || o["comment"] == .null { o["comment"] = .string("") }
            o["content"] = .object((o["content"]?.object ?? [:]).filter { $0.value != .null })
        }
        return .object(o)
    }

    /// The path with its query in a fixed order and E19's defaults (`since` 0, `limit` 500) filled in.
    static func normalisedPath(_ path: String) -> String {
        let parts = path.split(separator: "?", maxSplits: 1).map(String.init)
        var query: [String: String] = [:]
        if parts.count == 2 {
            for item in parts[1].split(separator: "&") {
                let kv = item.split(separator: "=", maxSplits: 1).map(String.init)
                query[kv[0]] = kv.count > 1 ? kv[1] : ""
            }
        }
        if parts[0].hasSuffix("/changes") {
            query["since"] = query["since"] ?? "0"
            query["limit"] = query["limit"] ?? "500"
        }
        return query.isEmpty ? parts[0] : parts[0] + "?" + query.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "&")
    }

    private func text(_ v: JSONValue?, _ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> String {
        try XCTUnwrap(v?[key]?.string, "no string \(key)", file: file, line: line)
    }

    private func number(_ v: JSONValue?, _ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> Int64 {
        try XCTUnwrap(v?[key]?.int, "no integer \(key)", file: file, line: line)
    }

    private func proof(_ v: JSONValue?) throws -> ProProof {
        switch try text(v, "platform") {
        case "apple": return .apple(signedTransaction: try text(v, "signedTransaction"))
        case "google": return .google(productId: try text(v, "productId"), purchaseToken: try text(v, "purchaseToken"))
        case let other: throw GroupAPIError(status: 0, code: "unknown platform \(other)")
        }
    }

    private func upload(_ body: JSONValue) throws -> UploadRequest {
        let chunks = try (body["chunks"]?.array ?? []).map { UploadRequest.Chunk(start: try number($0, "start"), rec: try text($0, "rec")) }
        let live: UploadRequest.LiveField
        switch body["live"] {
        case nil: live = .unchanged
        case .null?: live = .stopped
        case let l?: live = .position(.init(at: try number(l, "at"), rec: try text(l, "rec")))
        }
        return UploadRequest(chunks: chunks, live: live)
    }
}

extension JSONValue: ExpressibleByStringLiteral {
    init(stringLiteral value: String) { self = .string(value) }
}
