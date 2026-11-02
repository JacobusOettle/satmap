import Foundation

/// An in-memory server that follows the HTTP API of docs/groups/SPEC.md §6 (roles and permissions, invites, change
/// numbers, the change feed with its `joinedAt` rules, uploads, pins, pauses), used by the hub tests and by demo mode
/// (§8.15). It stores only what the real server stores: ids, times, roles and sealed records. There are no sockets;
/// `setWatchers` stands in for other members watching.
///
/// Test hooks seed records directly (`seedGroup`, `seedMember`, …), pause and resume groups, archive old chunks, make
/// requests fail, and let tests look at what was stored.
final class FakeGroupServer: GroupTransport {
    /// Throws `GroupTransportError.offline` for every request while true.
    var offline = false
    /// The lowest accepted `termsVersion` (`TERMS_VERSION`).
    var termsVersion = 1
    /// Whether Pro proofs verify; otherwise `402 proError`.
    var proAccepted = true
    var proError = "pro_invalid"
    var maxGroupsPerPurchase = 20
    /// Uploaded chunks whose window start is a key here are rejected with that error (`before_join`, `bad_time`,
    /// `bad_record` or `window_paused`), as long as the entry stays.
    var chunkRejections: [Int64: String] = [:]
    /// Demo mode: asked at each E21 for a group's live records at that time; newer ones replace the stored ones, as
    /// if those members kept sharing (`GroupDemo` keeps its members' dots fresh however long a walkthrough runs). It
    /// runs under the server's lock and must not call back into the server.
    var liveSource: ((_ groupId: String, _ now: Int64) -> [LiveEntry])?

    private let clock: () -> Int64
    private let lock = NSLock()
    private var groups: [String: FakeGroup] = [:]
    private var failures: [Failure] = []
    private var proGroups: [String: Set<String>] = [:]
    private var requestLog: [LoggedRequest] = []
    private var storedReports: [ReportRequest] = []

    struct LoggedRequest: Equatable {
        let method: String
        let path: String
        let body: Data?
        let pass: String?
    }

    private struct Failure {
        let method: String
        let pathContains: String
        let status: Int
        let code: String
        var times: Int
    }

    init(now: @escaping () -> Int64) {
        clock = now
    }

    // MARK: - Test hooks

    /// Every request so far.
    var log: [LoggedRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requestLog
    }

    func clearLog() {
        lock.lock()
        requestLog = []
        lock.unlock()
    }

    var reports: [ReportRequest] {
        lock.lock()
        defer { lock.unlock() }
        return storedReports
    }

    /// The next `times` requests with this method whose path contains the text answer `status` with `code`.
    func fail(_ method: String, _ pathContains: String, status: Int, code: String, times: Int = 1) {
        lock.lock()
        failures.append(Failure(method: method, pathContains: pathContains, status: status, code: code, times: times))
        lock.unlock()
    }

    /// The number of other members with a watching socket, as E18, E21 and the status report it.
    func setWatchers(_ count: Int, groupId: String) {
        withGroup(groupId) { $0.watchers = count }
    }

    /// Pauses the group as the Pro checks do (§7.3): live positions deleted.
    func pause(groupId: String, reason: String = "pro_ended", at: Int64? = nil) {
        let now = at ?? clock()
        withGroup(groupId) { g in
            g.status = "paused"
            g.pausedReason = reason
            g.pausedAt = now
            g.pauseLog.append((now, nil, reason))
            g.live = [:]
        }
    }

    func resume(groupId: String) {
        let now = clock()
        withGroup(groupId) { g in
            g.status = "active"
            g.pausedReason = nil
            g.pausedAt = nil
            if let last = g.pauseLog.indices.last, g.pauseLog[last].to == nil { g.pauseLog[last].to = now }
        }
    }

    /// Moves chunks that start before `before` into month archives, as the archive pass does (§7.5).
    func archive(groupId: String, before: Int64) {
        let now = clock()
        withGroup(groupId) { g in
            let old = g.chunks.filter { $0.value.start < before }
            var byMonth: [String: [(key: String, chunk: FakeChunk)]] = [:]
            for (key, c) in old {
                byMonth["\(c.memberId)|\(Self.month(c.start))", default: []].append((key, c))
            }
            for (archiveKey, rows) in byMonth.sorted(by: { $0.key < $1.key }) {
                let memberId = rows[0].chunk.memberId
                var a = g.archives[archiveKey] ?? FakeArchive(memberId: memberId, month: Self.month(rows[0].chunk.start), records: [:],
                                                               seq: 0, maxChunkSeq: 0, archivedAt: 0)
                a.maxChunkSeq = max(a.maxChunkSeq, a.seq, rows.map(\.chunk.seq).max() ?? 0)
                for r in rows {
                    a.records[r.chunk.start] = r.chunk.rec
                    g.chunks[r.key] = nil
                }
                a.seq = g.nextSeq()
                a.archivedAt = now
                g.archives[archiveKey] = a
            }
        }
    }

    /// Removes a member as the developer's A4 does (§7.7), like E14 by an admin.
    func removeMember(groupId: String, memberId: String) {
        let now = clock()
        withGroup(groupId) { g in g.remove(memberId, removed: true, now: now) }
    }

    /// Deletes the group as E4 does.
    func deleteGroup(groupId: String) {
        let now = clock()
        withGroup(groupId) { g in g.markDeleted(now: now) }
        lock.lock()
        for key in proGroups.keys { proGroups[key]?.remove(groupId) }
        lock.unlock()
    }

    /// Forgets the group entirely (never created, or purged): `404 group_not_found`.
    func purgeGroup(groupId: String) {
        lock.lock()
        groups[groupId] = nil
        lock.unlock()
    }

    func memberIds(groupId: String) -> [String] {
        withGroup(groupId) { g in g.orderedMembers.map(\.id) } ?? []
    }

    func role(groupId: String, memberId: String) -> String? {
        withGroup(groupId) { g in g.members[memberId]?.role } ?? nil
    }

    func chunkStarts(groupId: String, memberId: String) -> [Int64] {
        withGroup(groupId) { g in g.chunks.values.filter { $0.memberId == memberId }.map(\.start).sorted() } ?? []
    }

    func chunkRecord(groupId: String, memberId: String, start: Int64) -> String? {
        withGroup(groupId) { g in g.chunks["\(memberId)|\(start)"]?.rec } ?? nil
    }

    func pinIds(groupId: String) -> [String] {
        withGroup(groupId) { g in g.pins.keys.sorted() } ?? []
    }

    func liveAt(groupId: String, memberId: String) -> Int64? {
        withGroup(groupId) { g in g.live[memberId]?.at } ?? nil
    }

    func memberProfile(groupId: String, memberId: String) -> String? {
        withGroup(groupId) { g in g.members[memberId]?.profile } ?? nil
    }

    func pendingInviteCount(groupId: String) -> Int {
        let now = clock()
        return withGroup(groupId) { g in g.invites.values.filter { $0.state == "pending" && $0.expiresAt > now }.count } ?? 0
    }

    func status(groupId: String) -> String? {
        withGroup(groupId) { g in g.status }
    }

    // MARK: - Seeding (demo mode and tests)

    /// A group with its owner, as E1 makes it.
    func seedGroup(groupId: String, joinProof: String, profile: String, ownerId: String, ownerPass: String, ownerProfile: String,
                   createdAt: Int64, pro: ProInfoDTO?) {
        lock.lock()
        defer { lock.unlock() }
        let g = FakeGroup(id: groupId, joinProof: joinProof, profile: profile, pro: pro, proKey: "seed")
        g.profileSeq = g.nextSeq()
        g.members[ownerId] = FakeMember(id: ownerId, pass: ownerPass, role: "owner", joinedAt: createdAt, profile: ownerProfile,
                                        seq: g.nextSeq(), lastLiveAt: nil)
        groups[groupId] = g
    }

    func seedMember(groupId: String, memberId: String, pass: String, role: String, joinedAt: Int64, profile: String) {
        withGroup(groupId) { g in
            g.members[memberId] = FakeMember(id: memberId, pass: pass, role: role, joinedAt: joinedAt, profile: profile, seq: g.nextSeq(), lastLiveAt: nil)
        }
    }

    func seedChunk(groupId: String, memberId: String, start: Int64, rec: String) {
        withGroup(groupId) { g in g.chunks["\(memberId)|\(start)"] = FakeChunk(memberId: memberId, start: start, rec: rec, seq: g.nextSeq()) }
    }

    func seedLive(groupId: String, memberId: String, at: Int64, rec: String) {
        withGroup(groupId) { g in
            g.live[memberId] = (at, rec)
            g.members[memberId]?.lastLiveAt = at
        }
    }

    func seedPin(groupId: String, pinId: String, memberId: String, createdAt: Int64, expiresAt: Int64?, rec: String) {
        withGroup(groupId) { g in
            g.pins[pinId] = FakePin(id: pinId, memberId: memberId, createdAt: createdAt, expiresAt: expiresAt, rec: rec, seq: g.nextSeq())
        }
    }

    /// An invite with a known secret (so a fixed link works), pending until `expiresAt`. Returns its token.
    @discardableResult
    func seedInvite(groupId: String, inviteId: String, secret: Data, createdBy: String, createdAt: Int64, expiresAt: Int64) -> String {
        withGroup(groupId) { g in
            g.invites[inviteId] = FakeInvite(id: inviteId, secret: secret, createdBy: createdBy, createdAt: createdAt, expiresAt: expiresAt,
                                             state: "pending", usedPass: nil)
        }
        return Base64URL.encode((Base64URL.decode(groupId) ?? Data()) + secret)
    }

    // MARK: - GroupTransport

    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data) {
        try handle(method: method, path: path, pass: pass, json: json)
    }

    /// The same as `request`, synchronously.
    func handle(method: String, path: String, pass: String?, json: Data?) throws -> (status: Int, body: Data) {
        lock.lock()
        defer { lock.unlock() }
        requestLog.append(LoggedRequest(method: method, path: path, body: json, pass: pass))
        if offline { throw GroupTransportError.offline }
        if let i = failures.firstIndex(where: { $0.method == method && path.contains($0.pathContains) }) {
            let f = failures[i]
            if f.times <= 1 { failures.remove(at: i) } else { failures[i].times -= 1 }
            return Self.error(f.status, f.code)
        }
        do {
            return try route(method: method, path: path, pass: pass, json: json)
        } catch let e as Answer {
            return Self.error(e.status, e.code)
        }
    }

    // MARK: - Routing

    private struct Answer: Error {
        let status: Int
        let code: String
    }

    private func route(method: String, path: String, pass: String?, json: Data?) throws -> (status: Int, body: Data) {
        let now = clock()
        let pieces = path.split(separator: "?", maxSplits: 1)
        let query = pieces.count > 1 ? Self.query(String(pieces[1])) : [:]
        let parts = pieces.first.map { $0.split(separator: "/").map(String.init) } ?? []
        guard parts.count >= 2, parts[0] == "v1" else { throw Answer(status: 404, code: "not_found") }
        if parts.count == 2 && parts[1] == "groups" && method == "POST" {
            return try create(json, now: now)
        }
        if parts.count == 3 && parts[1] == "invites" && method == "POST" {
            switch parts[2] {
            case "preview": return try preview(json, now: now)
            case "redeem": return try redeem(json, now: now)
            default: throw Answer(status: 404, code: "not_found")
            }
        }
        guard parts.count >= 3, parts[1] == "groups", Base64URL.decode(parts[2], count: 16) != nil else {
            throw Answer(status: parts.count >= 3 && parts[1] == "groups" ? 400 : 404, code: parts.count >= 3 && parts[1] == "groups" ? "bad_request" : "not_found")
        }
        let (g, me) = try auth(parts[2], pass, now: now)
        let rest = Array(parts.dropFirst(3))
        switch (method, rest.first, rest.count) {
        case ("GET", nil, _):
            return ok(status(g, me, now: now))
        case ("PUT", "profile", 1):
            try require(g, me, ["owner", "admin"])
            let body: [String: String] = try decode(json)
            let profile = try envelope(body["profile"], 39...512)
            g.profile = profile
            g.profileSeq = g.nextSeq()
            return ok(SeqDTO(seq: g.profileSeq))
        case ("DELETE", nil, _):
            try require(g, me, ["owner"])
            g.markDeleted(now: now)
            for key in proGroups.keys { proGroups[key]?.remove(g.id) }
            return (204, Data())
        case ("PUT", "pro", 1):
            try require(g, me, ["owner"])
            struct Body: Decodable { let pro: ProProof }
            let body: Body = try decode(json)
            g.pro = try verify(body.pro, now: now)
            if g.status == "paused", let r = g.pausedReason, ["pro_ended", "pro_refunded", "pro_unverified"].contains(r) { g.resume(now: now) }
            return ok(status(g, me, now: now))
        case ("POST", "invites", 1):
            try require(g, me, ["owner", "admin"])
            let pending = g.invites.values.filter { $0.state == "pending" && $0.expiresAt > now }.count
            guard pending < 100 else { throw Answer(status: 429, code: "rate_limited") }
            let secret = GroupRandom.bytes(16)
            let inviteId = GroupRandom.id()
            g.invites[inviteId] = FakeInvite(id: inviteId, secret: secret, createdBy: me, createdAt: now, expiresAt: now + 604_800,
                                             state: "pending", usedPass: nil)
            let token = Base64URL.encode((Base64URL.decode(g.id) ?? Data()) + secret)
            return ok(CreatedInviteDTO(inviteId: inviteId, token: token, createdAt: now, expiresAt: now + 604_800), status: 201)
        case ("GET", "invites", 1):
            try require(g, me, ["owner", "admin"])
            struct Page: Encodable { let invites: [InviteDTO] }
            let list = g.invites.values.filter { $0.state == "pending" && $0.expiresAt > now }
                .sorted { ($0.createdAt, $0.id) > ($1.createdAt, $1.id) }
                .map { InviteDTO(inviteId: $0.id, createdBy: $0.createdBy, createdAt: $0.createdAt, expiresAt: $0.expiresAt) }
            return ok(Page(invites: list))
        case ("DELETE", "invites", 2):
            try require(g, me, ["owner", "admin"])
            guard var invite = g.invites[rest[1]], invite.state == "pending", invite.expiresAt > now else {
                throw Answer(status: 404, code: "not_found")
            }
            invite.state = "cancelled"
            g.invites[rest[1]] = invite
            return (204, Data())
        case ("GET", "members", 1):
            struct Page: Encodable { let members: [MemberDTO] }
            return ok(Page(members: g.orderedMembers.map { MemberDTO(memberId: $0.id, role: $0.role, joinedAt: $0.joinedAt, profile: $0.profile, lastLiveAt: $0.lastLiveAt) }))
        case ("PUT", "members", 3) where rest[2] == "profile" && rest[1] == "me":
            let body: [String: String] = try decode(json)
            let profile = try envelope(body["profile"], 39...512)
            g.members[me]?.profile = profile
            g.members[me]?.seq = g.nextSeq()
            return ok(SeqDTO(seq: g.members[me]?.seq ?? 0))
        case ("PUT", "members", 3) where rest[2] == "role":
            try require(g, me, ["owner"])
            let body: [String: String] = try decode(json)
            guard let role = body["role"], role == "admin" || role == "member" else { throw Answer(status: 400, code: "bad_request") }
            let target = rest[1] == "me" ? me : rest[1]
            guard let m = g.members[target] else { throw Answer(status: 404, code: "not_found") }
            guard m.role != "owner", target != me else { throw Answer(status: 409, code: "not_allowed") }
            if m.role == "admin" && role == "member" { g.cancelInvites(by: target) }
            g.members[target]?.role = role
            g.members[target]?.seq = g.nextSeq()
            return ok(SeqDTO(seq: g.members[target]?.seq ?? 0))
        case ("DELETE", "members", 2):
            let target = rest[1] == "me" ? me : rest[1]
            guard let m = g.members[target] else { throw Answer(status: 404, code: "not_found") }
            let myRole = g.members[me]?.role ?? "member"
            if target == me {
                guard myRole != "owner" else { throw Answer(status: 409, code: "owner_must_hand_over") }
                g.remove(target, removed: false, now: now)
            } else {
                guard m.role != "owner" else { throw Answer(status: 409, code: "not_allowed") }
                guard myRole == "owner" || (myRole == "admin" && m.role == "member") else { throw Answer(status: 403, code: "forbidden") }
                g.remove(target, removed: true, now: now)
            }
            return (204, Data())
        case ("POST", "transfer", 1):
            try require(g, me, ["owner"])
            let body: [String: String] = try decode(json)
            guard let to = body["to"] else { throw Answer(status: 400, code: "bad_request") }
            guard to != me else { throw Answer(status: 409, code: "not_allowed") }
            guard g.members[to] != nil else { throw Answer(status: 404, code: "not_found") }
            g.transfer = (to, now + 604_800)
            return ok(status(g, me, now: now))
        case ("DELETE", "transfer", 1):
            guard let t = g.transfer else { throw Answer(status: 404, code: "not_found") }
            guard g.members[me]?.role == "owner" || t.to == me else { throw Answer(status: 403, code: "forbidden") }
            g.transfer = nil
            return (204, Data())
        case ("POST", "transfer", 2) where rest[1] == "accept":
            guard let t = g.transfer else { throw Answer(status: 404, code: "not_found") }
            guard t.to == me else { throw Answer(status: 403, code: "forbidden") }
            struct Body: Decodable { let pro: ProProof }
            let body: Body = try decode(json)
            g.pro = try verify(body.pro, now: now)
            if let old = g.members.values.first(where: { $0.role == "owner" }) {
                g.members[old.id]?.role = "admin"
                g.members[old.id]?.seq = g.nextSeq()
            }
            g.members[me]?.role = "owner"
            g.members[me]?.seq = g.nextSeq()
            g.transfer = nil
            if g.status == "paused", let r = g.pausedReason, ["pro_ended", "pro_refunded", "pro_unverified"].contains(r) { g.resume(now: now) }
            return ok(status(g, me, now: now))
        case ("POST", "upload", 1):
            return try upload(g, me, json, now: now)
        case ("GET", "changes", 1):
            let since = Int64(query["since"] ?? "0") ?? -1
            let limit = min(Int(query["limit"] ?? "500") ?? 500, 1000)
            guard since >= 0, limit > 0 else { throw Answer(status: 400, code: "bad_request") }
            return ok(changes(g, me, since: since, limit: limit, now: now))
        case ("GET", "archive", 3):
            guard let joinedAt = g.members[me]?.joinedAt, let a = g.archives["\(rest[1])|\(rest[2])"] else {
                throw Answer(status: 404, code: "not_found")
            }
            let visible = a.records.filter { $0.key + 600 > joinedAt }.compactMap { r in Base64URL.decode(r.value).map { (start: r.key, rec: $0) } }
            guard !visible.isEmpty else { throw Answer(status: 404, code: "not_found") }
            return (200, GroupArchive.build(visible))
        case ("GET", "live", 1):
            if g.status == "active", let source = liveSource {
                for e in source(g.id, now) where g.members[e.memberId] != nil && (g.live[e.memberId]?.at ?? .min) < e.at {
                    guard let rec = e.rec else { continue }
                    g.live[e.memberId] = (e.at, rec)
                    g.members[e.memberId]?.lastLiveAt = e.at
                }
            }
            let entries = g.live.filter { $0.value.at >= now - 86_400 }.sorted { $0.key < $1.key }
                .map { LiveEntry(memberId: $0.key, at: $0.value.at, rec: $0.value.rec) }
            return ok(LivePage(live: entries, watchers: g.watchers, serverTime: now))
        case ("DELETE", "trail", 2) where rest[1] == "me":
            g.clearTrail(me, now: now)
            return (204, Data())
        case ("POST", "pins", 1):
            return try dropPin(g, me, json, now: now)
        case ("DELETE", "pins", 2):
            guard let pin = g.pins[rest[1]] else { throw Answer(status: 404, code: "not_found") }
            guard pin.memberId == me || g.members[me]?.role != "member" else { throw Answer(status: 403, code: "forbidden") }
            g.pins[rest[1]] = nil
            g.removals.append((g.nextSeq(), "pin", pin.id, now))
            return (204, Data())
        case ("POST", "reports", 1):
            let body: ReportRequest = try decode(json)
            guard ["pin", "member", "group"].contains(body.targetKind),
                  ["offensive", "harassment", "spam", "dangerous", "other"].contains(body.reason),
                  GroupText.codePoints(body.comment) <= 500 else { throw Answer(status: 400, code: "bad_request") }
            let exists: Bool
            switch body.targetKind {
            case "pin": exists = g.pins[body.targetId] != nil || g.removals.contains { $0.what == "pin" && $0.id == body.targetId }
            case "member": exists = g.members[body.targetId] != nil || g.removals.contains { $0.what == "member" && $0.id == body.targetId }
            default: exists = body.targetId == g.id
            }
            guard exists else { throw Answer(status: 404, code: "not_found") }
            storedReports.append(body)
            struct ReportAnswer: Encodable { let reportId: String }
            return ok(ReportAnswer(reportId: GroupRandom.id()), status: 202)
        default:
            throw Answer(status: 404, code: "not_found")
        }
    }

    // MARK: - Endpoints

    private func create(_ json: Data?, now: Int64) throws -> (status: Int, body: Data) {
        let body: CreateGroupRequest = try decode(json)
        guard Base64URL.decode(body.groupId, count: 16) != nil, Base64URL.decode(body.memberId, count: 16) != nil,
              Base64URL.decode(body.pass, count: 32) != nil, Base64URL.decode(body.joinProof, count: 32) != nil else {
            throw Answer(status: 400, code: "bad_request")
        }
        let groupProfile = try envelope(body.groupProfile, 39...512)
        let memberProfile = try envelope(body.memberProfile, 39...512)
        guard body.termsVersion >= termsVersion else { throw Answer(status: 409, code: "terms_outdated") }
        if let existing = groups[body.groupId] {
            if existing.status != "deleted", let owner = existing.members.values.first(where: { $0.role == "owner" }), owner.pass == body.pass {
                return ok(status(existing, owner.id, now: now))
            }
            throw Answer(status: 409, code: "group_exists")
        }
        let pro = try verify(body.pro, now: now)
        let key = Self.proKey(body.pro)
        guard (proGroups[key]?.count ?? 0) < maxGroupsPerPurchase else { throw Answer(status: 409, code: "too_many_groups") }
        let g = FakeGroup(id: body.groupId, joinProof: body.joinProof, profile: groupProfile, pro: pro, proKey: key)
        g.profileSeq = g.nextSeq()
        g.members[body.memberId] = FakeMember(id: body.memberId, pass: body.pass, role: "owner", joinedAt: now, profile: memberProfile,
                                              seq: g.nextSeq(), lastLiveAt: nil)
        groups[body.groupId] = g
        proGroups[key, default: []].insert(body.groupId)
        return ok(status(g, body.memberId, now: now), status: 201)
    }

    private func invite(_ token: String, joinProof: String, now: Int64) throws -> (FakeGroup, FakeInvite) {
        guard let bytes = Base64URL.decode(token, count: 32) else { throw Answer(status: 400, code: "bad_request") }
        let gid = Base64URL.encode(bytes.prefix(16))
        let secret = Data(bytes.suffix(16))
        guard let g = groups[gid], g.status != "deleted", g.joinProof == joinProof,
              let invite = g.invites.values.first(where: { $0.secret == secret }) else {
            throw Answer(status: 404, code: "invite_not_found")
        }
        g.housekeeping(now: now)
        return (g, invite)
    }

    private func preview(_ json: Data?, now: Int64) throws -> (status: Int, body: Data) {
        let body: PreviewRequest = try decode(json)
        let (g, invite) = try invite(body.token, joinProof: body.joinProof, now: now)
        guard invite.state == "pending", invite.expiresAt > now else { throw Answer(status: 404, code: "invite_not_found") }
        let inviter = g.members[invite.createdBy].map { PreviewDTO.Inviter(memberId: $0.id, profile: $0.profile) }
        return ok(PreviewDTO(groupId: g.id, status: g.status, profile: g.profile, memberCount: g.members.count, inviter: inviter,
                             expiresAt: invite.expiresAt))
    }

    private func redeem(_ json: Data?, now: Int64) throws -> (status: Int, body: Data) {
        let body: RedeemRequest = try decode(json)
        guard Base64URL.decode(body.memberId, count: 16) != nil, Base64URL.decode(body.pass, count: 32) != nil else {
            throw Answer(status: 400, code: "bad_request")
        }
        let profile = try envelope(body.profile, 39...512)
        let (g, invite) = try invite(body.token, joinProof: body.joinProof, now: now)
        if invite.state == "used", invite.usedPass == body.pass, g.members[body.memberId] != nil {
            return ok(status(g, body.memberId, now: now))
        }
        guard invite.state == "pending", invite.expiresAt > now else { throw Answer(status: 404, code: "invite_not_found") }
        guard body.termsVersion >= termsVersion else { throw Answer(status: 409, code: "terms_outdated") }
        guard g.members[body.memberId] == nil, !g.removals.contains(where: { $0.what == "member" && $0.id == body.memberId }) else {
            throw Answer(status: 409, code: "member_exists")
        }
        g.invites[invite.id]?.state = "used"
        g.invites[invite.id]?.usedPass = body.pass
        g.members[body.memberId] = FakeMember(id: body.memberId, pass: body.pass, role: "member", joinedAt: now, profile: profile,
                                              seq: g.nextSeq(), lastLiveAt: nil)
        return ok(status(g, body.memberId, now: now), status: 201)
    }

    private func upload(_ g: FakeGroup, _ me: String, _ json: Data?, now: Int64) throws -> (status: Int, body: Data) {
        let body: UploadRequest = try decode(json)
        guard body.chunks.count <= 50 else { throw Answer(status: 413, code: "too_large") }
        if g.status == "paused" && !(body.chunks.isEmpty && body.live == .stopped) {
            throw Answer(status: 409, code: "group_paused")
        }
        let joinedAt = g.members[me]?.joinedAt ?? now
        var accepted: [Int64] = []
        var rejected: [UploadRejection] = []
        for c in body.chunks {
            let size = Base64URL.decode(c.rec).map { $0.count } ?? 0
            let version = Base64URL.decode(c.rec)?.first
            if let forced = chunkRejections[c.start] {
                rejected.append(UploadRejection(start: c.start, error: forced))
            } else if c.start % 600 != 0 {
                // In the real server's order; a start off the 600 s grid can never be accepted, so it is bad_record.
                rejected.append(UploadRejection(start: c.start, error: "bad_record"))
            } else if c.start + 600 <= joinedAt {
                rejected.append(UploadRejection(start: c.start, error: "before_join"))
            } else if c.start > now + 600 {
                rejected.append(UploadRejection(start: c.start, error: "bad_time"))
            } else if !(32...12_288).contains(size) || version != 1 {
                rejected.append(UploadRejection(start: c.start, error: "bad_record"))
            } else if g.pauseLog.contains(where: { c.start >= $0.from && c.start + 600 <= ($0.to ?? .max) }) {
                rejected.append(UploadRejection(start: c.start, error: "window_paused"))
            } else {
                g.chunks["\(me)|\(c.start)"] = FakeChunk(memberId: me, start: c.start, rec: c.rec, seq: g.nextSeq())
                accepted.append(c.start)
            }
        }
        var liveAccepted = false
        switch body.live {
        case .unchanged:
            break
        case .stopped:
            g.live[me] = nil
            liveAccepted = true
        case .position(let l):
            let size = Base64URL.decode(l.rec).map { $0.count } ?? 0
            let newer = (g.live[me]?.at ?? .min) < l.at
            let spaced = (g.lastLiveAccepted[me] ?? .min) <= now - 2
            if l.at >= now - 3600, l.at <= now + 600, (53...128).contains(size), Base64URL.decode(l.rec)?.first == 1, newer, spaced {
                g.live[me] = (l.at, l.rec)
                g.lastLiveAccepted[me] = now
                g.members[me]?.lastLiveAt = l.at
                liveAccepted = true
            }
        }
        return ok(UploadResult(accepted: accepted, rejected: rejected, liveAccepted: liveAccepted, watchers: g.watchers, status: g.status,
                               serverTime: now))
    }

    private func dropPin(_ g: FakeGroup, _ me: String, _ json: Data?, now: Int64) throws -> (status: Int, body: Data) {
        let body: PinRequest = try decode(json)
        guard Base64URL.decode(body.pinId, count: 16) != nil else { throw Answer(status: 400, code: "bad_request") }
        if let existing = g.pins[body.pinId] {
            if existing.memberId == me { return ok(SeqDTO(seq: existing.seq)) }
            throw Answer(status: 409, code: "pin_exists")
        }
        if g.removals.contains(where: { $0.what == "pin" && $0.id == body.pinId }) { throw Answer(status: 410, code: "pin_deleted") }
        guard g.status != "paused" else { throw Answer(status: 409, code: "group_paused") }
        guard body.expiresAt == nil || body.expiresAt == body.createdAt + 43_200 else { throw Answer(status: 400, code: "bad_record") }
        let rec = try envelope(body.rec, 49...1024, code: "bad_record")
        if let e = body.expiresAt, e <= now { throw Answer(status: 422, code: "pin_expired") }
        guard body.createdAt <= now + 600, body.expiresAt != nil || body.createdAt >= now - 604_800 else {
            throw Answer(status: 422, code: "bad_time")
        }
        let seq = g.nextSeq()
        g.pins[body.pinId] = FakePin(id: body.pinId, memberId: me, createdAt: body.createdAt, expiresAt: body.expiresAt, rec: rec, seq: seq)
        return ok(SeqDTO(seq: seq), status: 201)
    }

    private func changes(_ g: FakeGroup, _ me: String, since: Int64, limit: Int, now: Int64) -> ChangesPage {
        guard since <= g.seq else { return ChangesPage(items: [], cursor: 0, more: false, serverTime: now, reset: true) }
        let joinedAt = g.members[me]?.joinedAt ?? 0
        var items: [ChangeItem] = []
        if g.profileSeq > since { items.append(.group(seq: g.profileSeq, profile: g.profile)) }
        for m in g.members.values where m.seq > since {
            items.append(.member(seq: m.seq, memberId: m.id, role: m.role, joinedAt: m.joinedAt, profile: m.profile))
        }
        for c in g.chunks.values where c.seq > since && c.start + 600 > joinedAt {
            items.append(.chunk(seq: c.seq, memberId: c.memberId, start: c.start, rec: c.rec))
        }
        for a in g.archives.values where a.seq > since {
            if let range = GroupStore.monthRange(a.month), range.end <= joinedAt { continue }
            items.append(.archive(seq: a.seq, memberId: a.memberId, month: a.month, maxChunkSeq: a.maxChunkSeq, archivedAt: a.archivedAt))
        }
        for p in g.pins.values where p.seq > since && (p.expiresAt.map { $0 > now } ?? true) {
            items.append(.pin(seq: p.seq, pinId: p.id, memberId: p.memberId, createdAt: p.createdAt, expiresAt: p.expiresAt, rec: p.rec))
        }
        for r in g.removals where r.seq > since {
            items.append(.removed(seq: r.seq, what: r.what, id: r.id))
        }
        items.sort { $0.seq < $1.seq }
        let page = Array(items.prefix(limit))
        return ChangesPage(items: page, cursor: page.last?.seq ?? since, more: items.count > page.count, serverTime: now)
    }

    // MARK: - Helpers

    private func auth(_ groupId: String, _ pass: String?, now: Int64) throws -> (FakeGroup, String) {
        guard let g = groups[groupId] else { throw Answer(status: 404, code: "group_not_found") }
        if g.status == "deleted" { throw Answer(status: 410, code: "group_deleted") }
        guard let pass = pass, Base64URL.decode(pass, count: 32) != nil else { throw Answer(status: 401, code: "unauthorized") }
        guard let m = g.members.values.first(where: { $0.pass == pass }) else {
            if g.gone[pass] == "removed" { throw Answer(status: 410, code: "removed") }
            throw Answer(status: 401, code: "unauthorized")
        }
        g.housekeeping(now: now)
        return (g, m.id)
    }

    private func require(_ g: FakeGroup, _ me: String, _ roles: Set<String>) throws {
        guard let role = g.members[me]?.role, roles.contains(role) else { throw Answer(status: 403, code: "forbidden") }
    }

    private func envelope(_ text: String?, _ sizes: ClosedRange<Int>, code: String = "bad_record") throws -> String {
        guard let text = text, let data = Base64URL.decode(text), data.first == 1, sizes.contains(data.count) else {
            throw Answer(status: 400, code: code)
        }
        return text
    }

    private func verify(_ proof: ProProof, now: Int64) throws -> ProInfoDTO {
        guard proAccepted else { throw Answer(status: 402, code: proError) }
        switch proof {
        case .apple(let jws):
            // The fake reads "fake:{productId}:{expiresAt or -}"; anything else is a yearly purchase.
            let parts = jws.split(separator: ":").map(String.init)
            if parts.count == 3, parts[0] == "fake" {
                return ProInfoDTO(platform: "apple", productId: parts[1], expiresAt: Int64(parts[2]))
            }
            return ProInfoDTO(platform: "apple", productId: "pro.yearly", expiresAt: now + 365 * 86_400)
        case .google(let productId, _):
            return ProInfoDTO(platform: "google", productId: productId, expiresAt: productId == "pro_lifetime" ? nil : now + 30 * 86_400)
        }
    }

    private static func proKey(_ proof: ProProof) -> String {
        switch proof {
        case .apple(let jws): return "apple:" + jws
        case .google(_, let token): return "google:" + token
        }
    }

    private func status(_ g: FakeGroup, _ me: String, now: Int64) -> GroupStatusDTO {
        let m = g.members[me]
        let intervals: [[Int64?]] = g.pauseLog.filter { $0.to == nil || $0.to! > now - 2_592_000 }.map { [$0.from, $0.to] }
        let transfer = g.transfer.flatMap { $0.expiresAt > now ? TransferDTO(to: $0.to, expiresAt: $0.expiresAt) : nil }
        return GroupStatusDTO(groupId: g.id, status: g.status, pausedReason: g.pausedReason, pausedAt: g.pausedAt,
                              deleteAt: g.pausedAt.map { $0 + 2_592_000 }, pausedIntervals: intervals,
                              ownerId: g.members.values.first { $0.role == "owner" }?.id ?? "", memberCount: g.members.count,
                              profile: g.profile, profileSeq: g.profileSeq,
                              you: YouDTO(memberId: me, role: m?.role ?? "member", joinedAt: m?.joinedAt ?? now),
                              transfer: transfer, pro: m?.role == "owner" ? g.pro : nil, watchers: g.watchers, seq: g.seq, serverTime: now)
    }

    private func decode<T: Decodable>(_ json: Data?) throws -> T {
        guard let json = json, let value = try? JSONDecoder().decode(T.self, from: json) else { throw Answer(status: 400, code: "bad_request") }
        return value
    }

    private func ok<T: Encodable>(_ value: T, status: Int = 200) -> (status: Int, body: Data) {
        (status, (try? JSONEncoder().encode(value)) ?? Data("{}".utf8))
    }

    private static func error(_ status: Int, _ code: String) -> (status: Int, body: Data) {
        (status, (try? JSONEncoder().encode(ErrorDTO(error: code, message: "fake server"))) ?? Data())
    }

    private static func query(_ text: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { out[kv[0]] = kv[1] }
        }
        return out
    }

    /// "YYYY-MM" (UTC) of a time.
    static func month(_ time: Int64) -> String {
        let days = time >= 0 ? time / 86_400 : (time - 86_399) / 86_400
        // Civil from days (proleptic Gregorian).
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return String(format: "%04d-%02d", Int(y), Int(m))
    }

    @discardableResult
    private func withGroup<T>(_ groupId: String, _ body: (FakeGroup) -> T) -> T? {
        lock.lock()
        defer { lock.unlock() }
        guard let g = groups[groupId] else { return nil }
        return body(g)
    }
}

// MARK: - The fake's rows

private struct FakeMember {
    let id: String
    let pass: String
    var role: String
    let joinedAt: Int64
    var profile: String
    var seq: Int64
    var lastLiveAt: Int64?
}

private struct FakeInvite {
    let id: String
    let secret: Data
    let createdBy: String
    let createdAt: Int64
    let expiresAt: Int64
    var state: String
    var usedPass: String?
}

private struct FakeChunk {
    let memberId: String
    let start: Int64
    let rec: String
    let seq: Int64
}

private struct FakeArchive {
    let memberId: String
    let month: String
    var records: [Int64: String]
    var seq: Int64
    var maxChunkSeq: Int64
    var archivedAt: Int64
}

private struct FakePin {
    let id: String
    let memberId: String
    let createdAt: Int64
    let expiresAt: Int64?
    let rec: String
    let seq: Int64
}

private final class FakeGroup {
    let id: String
    let joinProof: String
    var status = "active"
    var pausedReason: String?
    var pausedAt: Int64?
    var deletedAt: Int64?
    var profile: String
    var profileSeq: Int64 = 0
    var seq: Int64 = 0
    var pro: ProInfoDTO?
    let proKey: String
    var members: [String: FakeMember] = [:]
    /// Pass → reason, for passes removed in the last 30 days.
    var gone: [String: String] = [:]
    var invites: [String: FakeInvite] = [:]
    var chunks: [String: FakeChunk] = [:]
    var archives: [String: FakeArchive] = [:]
    var pins: [String: FakePin] = [:]
    var removals: [(seq: Int64, what: String, id: String, at: Int64)] = []
    var live: [String: (at: Int64, rec: String)] = [:]
    var lastLiveAccepted: [String: Int64] = [:]
    var pauseLog: [(from: Int64, to: Int64?, reason: String)] = []
    var transfer: (to: String, expiresAt: Int64)?
    var watchers = 0

    init(id: String, joinProof: String, profile: String, pro: ProInfoDTO?, proKey: String) {
        self.id = id
        self.joinProof = joinProof
        self.profile = profile
        self.pro = pro
        self.proKey = proKey
    }

    var orderedMembers: [FakeMember] {
        members.values.sorted { ($0.joinedAt, $0.id) < ($1.joinedAt, $1.id) }
    }

    func nextSeq() -> Int64 {
        seq += 1
        return seq
    }

    /// Expired animal pins go (with a `removed pin` item); an expired ownership offer ends.
    func housekeeping(now: Int64) {
        for p in pins.values where (p.expiresAt.map { $0 <= now } ?? false) {
            pins[p.id] = nil
            removals.append((nextSeq(), "pin", p.id, now))
        }
        if let t = transfer, t.expiresAt <= now { transfer = nil }
    }

    func resume(now: Int64) {
        status = "active"
        pausedReason = nil
        pausedAt = nil
        if let last = pauseLog.indices.last, pauseLog[last].to == nil { pauseLog[last].to = now }
    }

    func cancelInvites(by memberId: String) {
        for (id, i) in invites where i.createdBy == memberId && i.state == "pending" {
            invites[id]?.state = "cancelled"
        }
    }

    func clearTrail(_ memberId: String, now: Int64) {
        chunks = chunks.filter { $0.value.memberId != memberId }
        archives = archives.filter { $0.value.memberId != memberId }
        removals.append((nextSeq(), "trail", memberId, now))
    }

    /// E14's effects (§6.5): the member's chunks, archives, pins, live row and member row go; a `removed member` item;
    /// an offer to them ends; their pending invites are cancelled; a removed pass answers `410 removed`.
    func remove(_ memberId: String, removed: Bool, now: Int64) {
        guard let m = members[memberId] else { return }
        chunks = chunks.filter { $0.value.memberId != memberId }
        archives = archives.filter { $0.value.memberId != memberId }
        pins = pins.filter { $0.value.memberId != memberId }
        live[memberId] = nil
        members[memberId] = nil
        removals.append((nextSeq(), "member", memberId, now))
        if transfer?.to == memberId { transfer = nil }
        cancelInvites(by: memberId)
        if removed { gone[m.pass] = "removed" }
    }

    func markDeleted(now: Int64) {
        status = "deleted"
        deletedAt = now
        members = [:]
        chunks = [:]
        archives = [:]
        pins = [:]
        live = [:]
        invites = [:]
        transfer = nil
    }
}
