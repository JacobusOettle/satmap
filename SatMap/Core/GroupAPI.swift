import Foundation

// The server's HTTP and WebSocket API as Codable models (docs/groups/SPEC.md §6), and `GroupAPI`, which builds each
// request and reads its answer over an injected `GroupTransport`. Envelopes travel as base64url strings. The
// Android core (GroupAPI.kt) builds and reads the same JSON.

/// A non-2xx answer: its status and the `error` code from the body (§6.2). A 2xx answer whose body doesn't parse is
/// `badResponse`.
struct GroupAPIError: Error, Equatable {
    let status: Int
    let code: String

    static let badResponse = "bad_response"
}

// MARK: - Shared objects (§6.3)

struct YouDTO: Codable, Equatable {
    let memberId: String
    let role: String
    let joinedAt: Int64
}

struct TransferDTO: Codable, Equatable {
    let to: String
    let expiresAt: Int64
}

struct ProInfoDTO: Codable, Equatable {
    let platform: String
    let productId: String
    /// Nil for Lifetime.
    let expiresAt: Int64?
}

struct GroupStatusDTO: Codable, Equatable {
    let groupId: String
    /// "active" or "paused".
    let status: String
    let pausedReason: String?
    let pausedAt: Int64?
    let deleteAt: Int64?
    /// `[pausedAt, resumedAt or null]` for the pauses of the last 30 days and the current one.
    let pausedIntervals: [[Int64?]]?
    let ownerId: String
    let memberCount: Int
    /// The group profile's envelope.
    let profile: String?
    let profileSeq: Int64?
    let you: YouDTO
    let transfer: TransferDTO?
    /// Shown only to the owner.
    let pro: ProInfoDTO?
    let watchers: Int?
    let seq: Int64
    let serverTime: Int64

    var isPaused: Bool { status == "paused" }

    /// The pauses as closed or open ranges (an open one has no end).
    var pauses: [(from: Int64, to: Int64?)] {
        (pausedIntervals ?? []).compactMap { pair in
            guard let first = pair.first, let from = first else { return nil }
            return (from, pair.count > 1 ? pair[1] : nil)
        }
    }
}

struct MemberDTO: Codable, Equatable {
    let memberId: String
    let role: String
    let joinedAt: Int64
    let profile: String
    let lastLiveAt: Int64?
}

/// A live record (§6.3); `rec` is null in a tick for a member who stopped sharing.
struct LiveEntry: Codable, Equatable {
    let memberId: String
    let at: Int64
    let rec: String?

    init(memberId: String, at: Int64, rec: String?) {
        self.memberId = memberId
        self.at = at
        self.rec = rec
    }

    enum CodingKeys: String, CodingKey { case memberId, at, rec }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(memberId, forKey: .memberId)
        try c.encode(at, forKey: .at)
        if let rec = rec { try c.encode(rec, forKey: .rec) } else { try c.encodeNil(forKey: .rec) }
    }
}

/// A Pro proof (§6.3).
enum ProProof: Equatable {
    case apple(signedTransaction: String)
    case google(productId: String, purchaseToken: String)
}

extension ProProof: Codable {
    private enum CodingKeys: String, CodingKey { case platform, signedTransaction, productId, purchaseToken }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        switch try c.decode(String.self, forKey: .platform) {
        case "apple": self = .apple(signedTransaction: try c.decode(String.self, forKey: .signedTransaction))
        case "google":
            self = .google(productId: try c.decode(String.self, forKey: .productId),
                           purchaseToken: try c.decode(String.self, forKey: .purchaseToken))
        default:
            throw DecodingError.dataCorruptedError(forKey: .platform, in: c, debugDescription: "unknown platform")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .apple(let jws):
            try c.encode("apple", forKey: .platform)
            try c.encode(jws, forKey: .signedTransaction)
        case .google(let productId, let token):
            try c.encode("google", forKey: .platform)
            try c.encode(productId, forKey: .productId)
            try c.encode(token, forKey: .purchaseToken)
        }
    }
}

/// One change item (§6.3). Kinds this version doesn't know are kept as `unknown`, skipped, and still move the cursor.
enum ChangeItem: Equatable {
    case group(seq: Int64, profile: String)
    case member(seq: Int64, memberId: String, role: String, joinedAt: Int64, profile: String)
    case chunk(seq: Int64, memberId: String, start: Int64, rec: String)
    case archive(seq: Int64, memberId: String, month: String, maxChunkSeq: Int64, archivedAt: Int64)
    case pin(seq: Int64, pinId: String, memberId: String, createdAt: Int64, expiresAt: Int64?, rec: String)
    /// `what` is "pin", "trail" or "member".
    case removed(seq: Int64, what: String, id: String)
    case unknown(seq: Int64)

    var seq: Int64 {
        switch self {
        case .group(let s, _), .member(let s, _, _, _, _), .chunk(let s, _, _, _), .archive(let s, _, _, _, _),
             .pin(let s, _, _, _, _, _), .removed(let s, _, _), .unknown(let s):
            return s
        }
    }
}

extension ChangeItem: Codable {
    private enum CodingKeys: String, CodingKey {
        case kind, seq, profile, memberId, role, joinedAt, start, rec, month, maxChunkSeq, archivedAt, pinId, createdAt
        case expiresAt, what, id
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let seq = try c.decode(Int64.self, forKey: .seq)
        switch try c.decode(String.self, forKey: .kind) {
        case "group":
            self = .group(seq: seq, profile: try c.decode(String.self, forKey: .profile))
        case "member":
            self = .member(seq: seq, memberId: try c.decode(String.self, forKey: .memberId), role: try c.decode(String.self, forKey: .role),
                           joinedAt: try c.decode(Int64.self, forKey: .joinedAt), profile: try c.decode(String.self, forKey: .profile))
        case "chunk":
            self = .chunk(seq: seq, memberId: try c.decode(String.self, forKey: .memberId), start: try c.decode(Int64.self, forKey: .start),
                          rec: try c.decode(String.self, forKey: .rec))
        case "archive":
            self = .archive(seq: seq, memberId: try c.decode(String.self, forKey: .memberId), month: try c.decode(String.self, forKey: .month),
                            maxChunkSeq: try c.decode(Int64.self, forKey: .maxChunkSeq), archivedAt: try c.decode(Int64.self, forKey: .archivedAt))
        case "pin":
            self = .pin(seq: seq, pinId: try c.decode(String.self, forKey: .pinId), memberId: try c.decode(String.self, forKey: .memberId),
                        createdAt: try c.decode(Int64.self, forKey: .createdAt), expiresAt: try c.decodeIfPresent(Int64.self, forKey: .expiresAt),
                        rec: try c.decode(String.self, forKey: .rec))
        case "removed":
            self = .removed(seq: seq, what: try c.decode(String.self, forKey: .what), id: try c.decode(String.self, forKey: .id))
        default:
            self = .unknown(seq: seq)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(seq, forKey: .seq)
        switch self {
        case .group(_, let profile):
            try c.encode("group", forKey: .kind)
            try c.encode(profile, forKey: .profile)
        case .member(_, let memberId, let role, let joinedAt, let profile):
            try c.encode("member", forKey: .kind)
            try c.encode(memberId, forKey: .memberId)
            try c.encode(role, forKey: .role)
            try c.encode(joinedAt, forKey: .joinedAt)
            try c.encode(profile, forKey: .profile)
        case .chunk(_, let memberId, let start, let rec):
            try c.encode("chunk", forKey: .kind)
            try c.encode(memberId, forKey: .memberId)
            try c.encode(start, forKey: .start)
            try c.encode(rec, forKey: .rec)
        case .archive(_, let memberId, let month, let maxChunkSeq, let archivedAt):
            try c.encode("archive", forKey: .kind)
            try c.encode(memberId, forKey: .memberId)
            try c.encode(month, forKey: .month)
            try c.encode(maxChunkSeq, forKey: .maxChunkSeq)
            try c.encode(archivedAt, forKey: .archivedAt)
        case .pin(_, let pinId, let memberId, let createdAt, let expiresAt, let rec):
            try c.encode("pin", forKey: .kind)
            try c.encode(pinId, forKey: .pinId)
            try c.encode(memberId, forKey: .memberId)
            try c.encode(createdAt, forKey: .createdAt)
            if let e = expiresAt { try c.encode(e, forKey: .expiresAt) } else { try c.encodeNil(forKey: .expiresAt) }
            try c.encode(rec, forKey: .rec)
        case .removed(_, let what, let id):
            try c.encode("removed", forKey: .kind)
            try c.encode(what, forKey: .what)
            try c.encode(id, forKey: .id)
        case .unknown:
            try c.encode("unknown", forKey: .kind)
        }
    }
}

/// An E19 page (§6.5), or a reset: the client deletes its copy of the group and pulls from 0.
struct ChangesPage: Codable, Equatable {
    let items: [ChangeItem]
    let cursor: Int64
    let more: Bool
    let serverTime: Int64?
    let reset: Bool

    init(items: [ChangeItem], cursor: Int64, more: Bool, serverTime: Int64?, reset: Bool = false) {
        self.items = items
        self.cursor = cursor
        self.more = more
        self.serverTime = serverTime
        self.reset = reset
    }

    private enum CodingKeys: String, CodingKey { case items, cursor, more, serverTime, reset }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reset = try c.decodeIfPresent(Bool.self, forKey: .reset) ?? false
        items = try c.decodeIfPresent([ChangeItem].self, forKey: .items) ?? []
        cursor = try c.decode(Int64.self, forKey: .cursor)
        more = try c.decodeIfPresent(Bool.self, forKey: .more) ?? false
        serverTime = try c.decodeIfPresent(Int64.self, forKey: .serverTime)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        if reset {
            try c.encode(true, forKey: .reset)
            try c.encode(cursor, forKey: .cursor)
            return
        }
        try c.encode(items, forKey: .items)
        try c.encode(cursor, forKey: .cursor)
        try c.encode(more, forKey: .more)
        try c.encodeIfPresent(serverTime, forKey: .serverTime)
    }
}

/// An E18 body, or the body of a WebSocket `upload` (§6.5, §6.6).
struct UploadRequest: Equatable {
    struct Chunk: Codable, Equatable {
        let start: Int64
        let rec: String
    }

    struct Live: Codable, Equatable {
        let at: Int64
        let rec: String
    }

    enum LiveField: Equatable {
        /// No `live` key: no change (a heartbeat).
        case unchanged
        /// `"live": null`: I stopped sharing.
        case stopped
        case position(Live)
    }

    var chunks: [Chunk]
    var live: LiveField

    /// The JSON body; with `socketId`, the WebSocket message (`type` "upload" and the client counter `id`).
    func json(socketId: Int? = nil) -> Data {
        var out = "{"
        if let id = socketId { out += "\"type\":\"upload\",\"id\":\(id)," }
        out += "\"chunks\":["
        out += chunks.map { "{\"start\":\($0.start),\"rec\":\"\($0.rec)\"}" }.joined(separator: ",")
        out += "]"
        switch live {
        case .unchanged: break
        case .stopped: out += ",\"live\":null"
        case .position(let l): out += ",\"live\":{\"at\":\(l.at),\"rec\":\"\(l.rec)\"}"
        }
        return Data((out + "}").utf8)
    }

    /// The size of the JSON body with these chunks: what packing (§4.3) keeps under 192 KiB.
    static func size(chunks: [Chunk], live: LiveField) -> Int {
        UploadRequest(chunks: chunks, live: live).json().count
    }
}

extension UploadRequest: Decodable {
    private enum CodingKeys: String, CodingKey { case chunks, live }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chunks = try c.decodeIfPresent([Chunk].self, forKey: .chunks) ?? []
        if !c.contains(.live) {
            live = .unchanged
        } else if try c.decodeNil(forKey: .live) {
            live = .stopped
        } else {
            live = .position(try c.decode(Live.self, forKey: .live))
        }
    }
}

struct UploadRejection: Codable, Equatable {
    let start: Int64
    /// "before_join", "bad_time", "bad_record" or "window_paused".
    let error: String
}

/// An E18 answer, or a WebSocket `uploaded` (which has no `watchers`, `status` or `serverTime`).
struct UploadResult: Codable, Equatable {
    let accepted: [Int64]
    let rejected: [UploadRejection]
    let liveAccepted: Bool?
    let watchers: Int?
    let status: String?
    let serverTime: Int64?
}

struct LivePage: Codable, Equatable {
    let live: [LiveEntry]
    let watchers: Int?
    let serverTime: Int64
}

struct CreateGroupRequest: Codable, Equatable {
    let groupId: String
    let memberId: String
    let pass: String
    let joinProof: String
    let groupProfile: String
    let memberProfile: String
    let termsVersion: Int
    let pro: ProProof
}

struct RedeemRequest: Codable, Equatable {
    let token: String
    let joinProof: String
    let memberId: String
    let pass: String
    let profile: String
    let termsVersion: Int
}

struct PreviewRequest: Codable, Equatable {
    let token: String
    let joinProof: String
}

struct PreviewDTO: Codable, Equatable {
    struct Inviter: Codable, Equatable {
        let memberId: String
        let profile: String
    }

    let groupId: String
    let status: String
    let profile: String?
    let memberCount: Int
    let inviter: Inviter?
    let expiresAt: Int64
}

struct CreatedInviteDTO: Codable, Equatable {
    let inviteId: String
    let token: String
    let createdAt: Int64
    let expiresAt: Int64
}

struct InviteDTO: Codable, Equatable {
    let inviteId: String
    let createdBy: String
    let createdAt: Int64
    let expiresAt: Int64
}

struct PinRequest: Equatable {
    let pinId: String
    let createdAt: Int64
    let expiresAt: Int64?
    let rec: String
}

extension PinRequest: Codable {
    private enum CodingKeys: String, CodingKey { case pinId, createdAt, expiresAt, rec }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pinId = try c.decode(String.self, forKey: .pinId)
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        expiresAt = try c.decodeIfPresent(Int64.self, forKey: .expiresAt)
        rec = try c.decode(String.self, forKey: .rec)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(pinId, forKey: .pinId)
        try c.encode(createdAt, forKey: .createdAt)
        if let e = expiresAt { try c.encode(e, forKey: .expiresAt) } else { try c.encodeNil(forKey: .expiresAt) }
        try c.encode(rec, forKey: .rec)
    }
}

struct ReportRequest: Codable, Equatable {
    /// "pin", "member" or "group".
    let targetKind: String
    let targetId: String
    let reason: String
    let comment: String
    /// The reporter's decrypted copy (SD28): `type`, `animal`, `note`, `name`. Never a position (SD10).
    let content: [String: String]
}

struct SeqDTO: Codable, Equatable {
    let seq: Int64
}

struct ErrorDTO: Codable, Equatable {
    let error: String
    let message: String?
}

// MARK: - WebSocket messages (§6.6)

/// A message from the server on the WebSocket.
enum SocketMessage: Equatable {
    struct Tick: Equatable {
        struct Changes: Codable, Equatable {
            let from: Int64
            let items: [ChangeItem]
            let cursor: Int64
            let more: Bool
        }

        let serverTime: Int64?
        let watchers: Int?
        let live: [LiveEntry]?
        let changes: Changes?
        let status: GroupStatusDTO?
    }

    case welcome(status: GroupStatusDTO, live: [LiveEntry])
    case uploaded(id: Int, result: UploadResult)
    case error(id: Int?, code: String)
    case tick(Tick)
    case unknown

    /// The message in a text frame, or nil if it isn't JSON this version understands ("pong" included).
    static func parse(_ text: String) -> SocketMessage? {
        guard let data = text.data(using: .utf8),
              let wire = try? JSONDecoder().decode(Wire.self, from: data) else { return nil }
        switch wire.type {
        case "welcome":
            guard let status = wire.status else { return nil }
            return .welcome(status: status, live: wire.live ?? [])
        case "uploaded":
            guard let id = wire.id else { return nil }
            return .uploaded(id: id, result: UploadResult(accepted: wire.accepted ?? [], rejected: wire.rejected ?? [],
                                                          liveAccepted: wire.liveAccepted, watchers: nil, status: nil, serverTime: nil))
        case "error":
            return .error(id: wire.id, code: wire.error ?? "")
        case "tick":
            return .tick(Tick(serverTime: wire.serverTime, watchers: wire.watchers, live: wire.live, changes: wire.changes, status: wire.status))
        default:
            return .unknown
        }
    }

    private struct Wire: Decodable {
        let type: String
        let id: Int?
        let status: GroupStatusDTO?
        let live: [LiveEntry]?
        let accepted: [Int64]?
        let rejected: [UploadRejection]?
        let liveAccepted: Bool?
        let error: String?
        let serverTime: Int64?
        let watchers: Int?
        let changes: Tick.Changes?
    }

    static func hello(watching: Bool, cursor: Int64) -> String {
        "{\"type\":\"hello\",\"v\":1,\"watching\":\(watching),\"cursor\":\(cursor)}"
    }

    static func watching(_ on: Bool) -> String {
        "{\"type\":\"watching\",\"on\":\(on)}"
    }

    static func ack(cursor: Int64) -> String {
        "{\"type\":\"ack\",\"cursor\":\(cursor)}"
    }

    static let ping = "ping"
}

// MARK: - Archive objects (§4.9)

enum GroupArchive {
    static let magic = Data("SMGA".utf8)

    /// The records of an archive object: `SMGA`, version 1, then per window its start (Int64), length (UInt32) and
    /// envelope. Nil if it doesn't follow the format.
    static func parse(_ data: Data) -> [(start: Int64, rec: Data)]? {
        let bytes = [UInt8](data)
        guard bytes.count >= 5, Data(bytes[0..<4]) == magic, bytes[4] == 0x01 else { return nil }
        var out: [(Int64, Data)] = []
        var i = 5
        while i < bytes.count {
            guard i + 12 <= bytes.count else { return nil }
            var start: UInt64 = 0
            for k in 0..<8 { start |= UInt64(bytes[i + k]) << UInt64(8 * k) }
            var length = 0
            for k in 0..<4 { length |= Int(bytes[i + 8 + k]) << (8 * k) }
            i += 12
            guard length <= bytes.count - i else { return nil }
            out.append((Int64(bitPattern: start), Data(bytes[i..<(i + length)])))
            i += length
        }
        return out
    }

    static func build(_ records: [(start: Int64, rec: Data)]) -> Data {
        var out = [UInt8](magic) + [0x01]
        for r in records.sorted(by: { $0.start < $1.start }) {
            withUnsafeBytes(of: r.start.littleEndian) { out.append(contentsOf: $0) }
            withUnsafeBytes(of: UInt32(r.rec.count).littleEndian) { out.append(contentsOf: $0) }
            out.append(contentsOf: r.rec)
        }
        return Data(out)
    }
}

// MARK: - Requests

/// Builds each request of §6.5 and reads its answer. Throws `GroupTransportError` when the request didn't reach the
/// server, `GroupAPIError` for any other answer than the expected 2xx.
struct GroupAPI {
    let transport: GroupTransport

    private static let encoder = JSONEncoder()

    private func path(_ groupId: String, _ rest: String = "") -> String {
        "/v1/groups/\(groupId)\(rest)"
    }

    /// E1
    func create(_ body: CreateGroupRequest) async throws -> GroupStatusDTO {
        try await call("POST", "/v1/groups", pass: nil, body: body)
    }

    /// E2
    func status(_ groupId: String, pass: String) async throws -> GroupStatusDTO {
        try await call("GET", path(groupId), pass: pass)
    }

    /// E3
    func renameGroup(_ groupId: String, pass: String, profile: String) async throws -> SeqDTO {
        try await call("PUT", path(groupId, "/profile"), pass: pass, body: ["profile": profile])
    }

    /// E4
    func deleteGroup(_ groupId: String, pass: String) async throws {
        try await callEmpty("DELETE", path(groupId), pass: pass)
    }

    /// E5
    func updatePro(_ groupId: String, pass: String, pro: ProProof) async throws -> GroupStatusDTO {
        try await call("PUT", path(groupId, "/pro"), pass: pass, body: ["pro": pro])
    }

    /// E6
    func createInvite(_ groupId: String, pass: String) async throws -> CreatedInviteDTO {
        try await call("POST", path(groupId, "/invites"), pass: pass, body: [String: String]())
    }

    /// E7
    func invites(_ groupId: String, pass: String) async throws -> [InviteDTO] {
        struct Page: Decodable { let invites: [InviteDTO] }
        let page: Page = try await call("GET", path(groupId, "/invites"), pass: pass)
        return page.invites
    }

    /// E8
    func cancelInvite(_ groupId: String, pass: String, inviteId: String) async throws {
        try await callEmpty("DELETE", path(groupId, "/invites/\(inviteId)"), pass: pass)
    }

    /// E9
    func preview(token: String, joinProof: String) async throws -> PreviewDTO {
        try await call("POST", "/v1/invites/preview", pass: nil, body: PreviewRequest(token: token, joinProof: joinProof))
    }

    /// E10
    func redeem(_ body: RedeemRequest) async throws -> GroupStatusDTO {
        try await call("POST", "/v1/invites/redeem", pass: nil, body: body)
    }

    /// E11
    func members(_ groupId: String, pass: String) async throws -> [MemberDTO] {
        struct Page: Decodable { let members: [MemberDTO] }
        let page: Page = try await call("GET", path(groupId, "/members"), pass: pass)
        return page.members
    }

    /// E12
    func updateMyProfile(_ groupId: String, pass: String, profile: String) async throws -> SeqDTO {
        try await call("PUT", path(groupId, "/members/me/profile"), pass: pass, body: ["profile": profile])
    }

    /// E13
    func setRole(_ groupId: String, pass: String, memberId: String, role: String) async throws -> SeqDTO {
        try await call("PUT", path(groupId, "/members/\(memberId)/role"), pass: pass, body: ["role": role])
    }

    /// E14; `memberId` "me" leaves.
    func removeMember(_ groupId: String, pass: String, memberId: String) async throws {
        try await callEmpty("DELETE", path(groupId, "/members/\(memberId)"), pass: pass)
    }

    /// E15
    func offerOwnership(_ groupId: String, pass: String, to memberId: String) async throws -> GroupStatusDTO {
        try await call("POST", path(groupId, "/transfer"), pass: pass, body: ["to": memberId])
    }

    /// E16
    func cancelOwnershipOffer(_ groupId: String, pass: String) async throws {
        try await callEmpty("DELETE", path(groupId, "/transfer"), pass: pass)
    }

    /// E17
    func acceptOwnership(_ groupId: String, pass: String, pro: ProProof) async throws -> GroupStatusDTO {
        try await call("POST", path(groupId, "/transfer/accept"), pass: pass, body: ["pro": pro])
    }

    /// E18
    func upload(_ groupId: String, pass: String, _ body: UploadRequest) async throws -> UploadResult {
        let (status, data) = try await transport.request(method: "POST", path: path(groupId, "/upload"), pass: pass, json: body.json())
        return try decode(status, data)
    }

    /// E19
    func changes(_ groupId: String, pass: String, since: Int64, limit: Int = 500) async throws -> ChangesPage {
        try await call("GET", path(groupId, "/changes?since=\(since)&limit=\(limit)"), pass: pass)
    }

    /// E20: the §4.9 object.
    func archive(_ groupId: String, pass: String, memberId: String, month: String) async throws -> Data {
        let (status, data) = try await transport.request(method: "GET", path: path(groupId, "/archive/\(memberId)/\(month)"), pass: pass, json: nil)
        guard (200..<300).contains(status) else { throw Self.error(status, data) }
        return data
    }

    /// E21
    func live(_ groupId: String, pass: String) async throws -> LivePage {
        try await call("GET", path(groupId, "/live"), pass: pass)
    }

    /// E22
    func clearTrail(_ groupId: String, pass: String) async throws {
        try await callEmpty("DELETE", path(groupId, "/trail/me"), pass: pass)
    }

    /// E23
    func dropPin(_ groupId: String, pass: String, _ body: PinRequest) async throws -> SeqDTO {
        try await call("POST", path(groupId, "/pins"), pass: pass, body: body)
    }

    /// E24
    func deletePin(_ groupId: String, pass: String, pinId: String) async throws {
        try await callEmpty("DELETE", path(groupId, "/pins/\(pinId)"), pass: pass)
    }

    /// E25: the report id.
    func report(_ groupId: String, pass: String, _ body: ReportRequest) async throws -> String {
        struct Answer: Decodable { let reportId: String }
        let answer: Answer = try await call("POST", path(groupId, "/reports"), pass: pass, body: body)
        return answer.reportId
    }

    // MARK: Plumbing

    private func call<T: Decodable>(_ method: String, _ path: String, pass: String?) async throws -> T {
        let (status, data) = try await transport.request(method: method, path: path, pass: pass, json: nil)
        return try decode(status, data)
    }

    private func call<T: Decodable, B: Encodable>(_ method: String, _ path: String, pass: String?, body: B) async throws -> T {
        let json = try Self.encoder.encode(body)
        let (status, data) = try await transport.request(method: method, path: path, pass: pass, json: json)
        return try decode(status, data)
    }

    private func callEmpty(_ method: String, _ path: String, pass: String?) async throws {
        let (status, data) = try await transport.request(method: method, path: path, pass: pass, json: nil)
        guard (200..<300).contains(status) else { throw Self.error(status, data) }
    }

    private func decode<T: Decodable>(_ status: Int, _ data: Data) throws -> T {
        guard (200..<300).contains(status) else { throw Self.error(status, data) }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw GroupAPIError(status: status, code: GroupAPIError.badResponse)
        }
    }

    static func error(_ status: Int, _ data: Data) -> GroupAPIError {
        GroupAPIError(status: status, code: (try? JSONDecoder().decode(ErrorDTO.self, from: data))?.error ?? "")
    }
}
