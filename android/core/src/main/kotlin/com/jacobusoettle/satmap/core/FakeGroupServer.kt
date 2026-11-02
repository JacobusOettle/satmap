package com.jacobusoettle.satmap.core

import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** An in-memory server that follows the HTTP API of docs/groups/SPEC.md §6 (roles and permissions, invites, change
 *  numbers, the change feed with its `joinedAt` rules, uploads, pins, pauses), used by the hub tests and by demo mode
 *  (§8.15). It stores only what the real server stores: ids, times, roles and sealed records. There are no sockets;
 *  `setWatchers` stands in for other members watching.
 *
 *  Test hooks seed records directly (`seedGroup`, `seedMember`, …), pause and resume groups, archive old chunks, make
 *  requests fail, and let tests look at what was stored. The iPhone core's FakeGroupServer.swift is the same. */
class FakeGroupServer(now: () -> Long) : GroupTransport {
    private val clock = now

    /** Throws `GroupTransportError.Offline` for every request while true. */
    @Volatile
    var offline = false

    /** The lowest accepted `termsVersion` (`TERMS_VERSION`). */
    @Volatile
    var termsVersion = 1

    /** Whether Pro proofs verify; otherwise `402 proError`. */
    @Volatile
    var proAccepted = true

    @Volatile
    var proError = "pro_invalid"

    @Volatile
    var maxGroupsPerPurchase = 20

    /** Uploaded chunks whose window start is a key here are rejected with that error (`before_join`, `bad_time`,
     *  `bad_record` or `window_paused`), as long as the entry stays. */
    @Volatile
    var chunkRejections: Map<Long, String> = emptyMap()

    /** Demo mode: asked at each E21 for a group's live records at that time; newer ones replace the stored ones, as
     *  if those members kept sharing (`GroupDemo` keeps its members' dots fresh however long a walkthrough runs). It
     *  runs under the server's lock and must not call back into the server. */
    @Volatile
    var liveSource: ((groupId: String, now: Long) -> List<LiveEntry>)? = null

    private val lock = Any()
    private val groups = HashMap<String, FakeGroup>()
    private val failures = mutableListOf<Failure>()
    private val proGroups = HashMap<String, MutableSet<String>>()
    private val requestLog = mutableListOf<LoggedRequest>()
    private val storedReports = mutableListOf<ReportRequest>()

    class LoggedRequest(val method: String, val path: String, body: ByteArray?, val pass: String?) {
        private val bytes = body?.copyOf()
        val body: ByteArray? get() = bytes?.copyOf()

        override fun equals(other: Any?): Boolean =
            other is LoggedRequest && other.method == method && other.path == path && other.pass == pass &&
                (if (bytes == null) other.bytes == null else other.bytes?.contentEquals(bytes) == true)

        override fun hashCode(): Int = (method.hashCode() * 31 + path.hashCode()) * 31 + (bytes?.contentHashCode() ?: 0)
        override fun toString(): String = "$method $path"
    }

    private class Failure(val method: String, val pathContains: String, val status: Int, val code: String, var times: Int)

    // Test hooks

    /** Every request so far. */
    val log: List<LoggedRequest> get() = synchronized(lock) { requestLog.toList() }

    fun clearLog() {
        synchronized(lock) { requestLog.clear() }
    }

    val reports: List<ReportRequest> get() = synchronized(lock) { storedReports.toList() }

    /** The next `times` requests with this method whose path contains the text answer `status` with `code`. */
    fun fail(method: String, pathContains: String, status: Int, code: String, times: Int = 1) {
        synchronized(lock) { failures.add(Failure(method, pathContains, status, code, times)) }
    }

    /** The number of other members with a watching socket, as E18, E21 and the status report it. */
    fun setWatchers(count: Int, groupId: String) {
        withGroup(groupId) { it.watchers = count }
    }

    /** Pauses the group as the Pro checks do (§7.3): live positions deleted. */
    fun pause(groupId: String, reason: String = "pro_ended", at: Long? = null) {
        val now = at ?: clock()
        withGroup(groupId) { g ->
            g.status = "paused"
            g.pausedReason = reason
            g.pausedAt = now
            g.pauseLog.add(PauseEntry(now, null, reason))
            g.live.clear()
        }
    }

    fun resume(groupId: String) {
        val now = clock()
        withGroup(groupId) { it.resume(now) }
    }

    /** Moves chunks that start before `before` into month archives, as the archive pass does (§7.5). */
    fun archive(groupId: String, before: Long) {
        val now = clock()
        withGroup(groupId) { g ->
            val old = g.chunks.filter { it.value.start < before }
            val byMonth = sortedMapOf<String, MutableList<Pair<String, FakeChunk>>>()
            for ((key, c) in old) {
                byMonth.getOrPut("${c.memberId}|${month(c.start)}") { mutableListOf() }.add(key to c)
            }
            for ((archiveKey, rows) in byMonth) {
                val memberId = rows[0].second.memberId
                val a = g.archives[archiveKey] ?: FakeArchive(memberId, month(rows[0].second.start), HashMap(), seq = 0, maxChunkSeq = 0, archivedAt = 0)
                a.maxChunkSeq = maxOf(a.maxChunkSeq, a.seq, rows.maxOfOrNull { it.second.seq } ?: 0)
                for ((key, chunk) in rows) {
                    a.records[chunk.start] = chunk.rec
                    g.chunks.remove(key)
                }
                a.seq = g.nextSeq()
                a.archivedAt = now
                g.archives[archiveKey] = a
            }
        }
    }

    /** Removes a member as the developer's A4 does (§7.7), like E14 by an admin. */
    fun removeMember(groupId: String, memberId: String) {
        val now = clock()
        withGroup(groupId) { it.remove(memberId, removed = true, now = now) }
    }

    /** Deletes the group as E4 does. */
    fun deleteGroup(groupId: String) {
        val now = clock()
        withGroup(groupId) { it.markDeleted(now) }
        synchronized(lock) { for (set in proGroups.values) set.remove(groupId) }
    }

    /** Forgets the group entirely (never created, or purged): `404 group_not_found`. */
    fun purgeGroup(groupId: String) {
        synchronized(lock) { groups.remove(groupId) }
    }

    fun memberIds(groupId: String): List<String> = withGroup(groupId) { g -> g.orderedMembers.map { it.id } } ?: emptyList()

    fun role(groupId: String, memberId: String): String? = withGroup(groupId) { it.members[memberId]?.role }

    fun chunkStarts(groupId: String, memberId: String): List<Long> =
        withGroup(groupId) { g -> g.chunks.values.filter { it.memberId == memberId }.map { it.start }.sorted() } ?: emptyList()

    fun chunkRecord(groupId: String, memberId: String, start: Long): String? = withGroup(groupId) { it.chunks["$memberId|$start"]?.rec }

    fun pinIds(groupId: String): List<String> = withGroup(groupId) { it.pins.keys.sorted() } ?: emptyList()

    fun liveAt(groupId: String, memberId: String): Long? = withGroup(groupId) { it.live[memberId]?.at }

    fun memberProfile(groupId: String, memberId: String): String? = withGroup(groupId) { it.members[memberId]?.profile }

    fun pendingInviteCount(groupId: String): Int {
        val now = clock()
        return withGroup(groupId) { g -> g.invites.values.count { it.state == "pending" && it.expiresAt > now } } ?: 0
    }

    fun status(groupId: String): String? = withGroup(groupId) { it.status }

    // Seeding (demo mode and tests)

    /** A group with its owner, as E1 makes it. */
    fun seedGroup(groupId: String, joinProof: String, profile: String, ownerId: String, ownerPass: String, ownerProfile: String,
                  createdAt: Long, pro: ProInfoDTO?) {
        synchronized(lock) {
            val g = FakeGroup(groupId, joinProof, profile, pro, proKey = "seed")
            g.profileSeq = g.nextSeq()
            g.members[ownerId] = FakeMember(ownerId, ownerPass, "owner", createdAt, ownerProfile, g.nextSeq(), lastLiveAt = null)
            groups[groupId] = g
        }
    }

    fun seedMember(groupId: String, memberId: String, pass: String, role: String, joinedAt: Long, profile: String) {
        withGroup(groupId) { g -> g.members[memberId] = FakeMember(memberId, pass, role, joinedAt, profile, g.nextSeq(), lastLiveAt = null) }
    }

    fun seedChunk(groupId: String, memberId: String, start: Long, rec: String) {
        withGroup(groupId) { g -> g.chunks["$memberId|$start"] = FakeChunk(memberId, start, rec, g.nextSeq()) }
    }

    fun seedLive(groupId: String, memberId: String, at: Long, rec: String) {
        withGroup(groupId) { g ->
            g.live[memberId] = FakeLive(at, rec)
            g.members[memberId]?.lastLiveAt = at
        }
    }

    fun seedPin(groupId: String, pinId: String, memberId: String, createdAt: Long, expiresAt: Long?, rec: String) {
        withGroup(groupId) { g -> g.pins[pinId] = FakePin(pinId, memberId, createdAt, expiresAt, rec, g.nextSeq()) }
    }

    /** An invite with a known secret (so a fixed link works), pending until `expiresAt`. Returns its token. */
    fun seedInvite(groupId: String, inviteId: String, secret: ByteArray, createdBy: String, createdAt: Long, expiresAt: Long): String {
        withGroup(groupId) { g -> g.invites[inviteId] = FakeInvite(inviteId, secret.copyOf(), createdBy, createdAt, expiresAt, "pending", usedPass = null) }
        return Base64URL.encode((Base64URL.decode(groupId) ?: ByteArray(0)) + secret)
    }

    // GroupTransport

    /** Like a request to a real server, this suspends (it lets other work on the thread run first, as the iPhone's
     *  hop off the main actor does), then answers as `handle`. */
    override suspend fun request(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse {
        yield()
        return handle(method, path, pass, json)
    }

    /** The same as `request`, without suspending. */
    fun handle(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse = synchronized(lock) {
        requestLog.add(LoggedRequest(method, path, json, pass))
        if (offline) throw GroupTransportError.Offline()
        val i = failures.indexOfFirst { it.method == method && path.contains(it.pathContains) }
        if (i >= 0) {
            val f = failures[i]
            if (f.times <= 1) failures.removeAt(i) else f.times -= 1
            return@synchronized error(f.status, f.code)
        }
        try {
            route(method, path, pass, json)
        } catch (e: Answer) {
            error(e.status, e.code)
        }
    }

    // Routing

    private class Answer(val status: Int, val code: String) : Exception("$status $code")

    private fun route(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse {
        val now = clock()
        val pieces = path.split("?", limit = 2)
        val query = if (pieces.size > 1) query(pieces[1]) else emptyMap()
        val parts = pieces[0].split("/").filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] != "v1") throw Answer(404, "not_found")
        if (parts.size == 2 && parts[1] == "groups" && method == "POST") return create(json, now)
        if (parts.size == 3 && parts[1] == "invites" && method == "POST") {
            return when (parts[2]) {
                "preview" -> preview(json, now)
                "redeem" -> redeem(json, now)
                else -> throw Answer(404, "not_found")
            }
        }
        if (parts.size < 3 || parts[1] != "groups" || Base64URL.decode(parts[2], count = 16) == null) {
            val group = parts.size >= 3 && parts[1] == "groups"
            throw Answer(if (group) 400 else 404, if (group) "bad_request" else "not_found")
        }
        val (g, me) = auth(parts[2], pass, now)
        val rest = parts.drop(3)
        val first = rest.firstOrNull()
        return when {
            method == "GET" && first == null -> ok(status(g, me, now).toJson())
            method == "PUT" && first == "profile" && rest.size == 1 -> {
                require(g, me, setOf("owner", "admin"))
                val profile = envelope(stringMap(json)["profile"], 39..512)
                g.profile = profile
                g.profileSeq = g.nextSeq()
                ok(SeqDTO(g.profileSeq).toJson())
            }
            method == "DELETE" && first == null -> {
                require(g, me, setOf("owner"))
                g.markDeleted(now)
                for (set in proGroups.values) set.remove(g.id)
                GroupResponse(204, ByteArray(0))
            }
            method == "PUT" && first == "pro" && rest.size == 1 -> {
                require(g, me, setOf("owner"))
                val pro = decode(json) { ProProof.fromJson(it.obj("pro")) }
                g.pro = verify(pro, now)
                if (g.status == "paused" && g.pausedReason in proReasons) g.resume(now)
                ok(status(g, me, now).toJson())
            }
            method == "POST" && first == "invites" && rest.size == 1 -> {
                require(g, me, setOf("owner", "admin"))
                val pending = g.invites.values.count { it.state == "pending" && it.expiresAt > now }
                if (pending >= 100) throw Answer(429, "rate_limited")
                val secret = GroupRandom.bytes(16)
                val inviteId = GroupRandom.id()
                g.invites[inviteId] = FakeInvite(inviteId, secret, me, now, now + 604_800, "pending", usedPass = null)
                val token = Base64URL.encode((Base64URL.decode(g.id) ?: ByteArray(0)) + secret)
                ok(CreatedInviteDTO(inviteId, token, now, now + 604_800).toJson(), status = 201)
            }
            method == "GET" && first == "invites" && rest.size == 1 -> {
                require(g, me, setOf("owner", "admin"))
                val list = g.invites.values.filter { it.state == "pending" && it.expiresAt > now }
                    .sortedWith(compareByDescending<FakeInvite> { it.createdAt }.thenByDescending { it.id })
                    .map { InviteDTO(it.id, it.createdBy, it.createdAt, it.expiresAt) }
                ok(buildJsonObject { put("invites", buildJsonArray { list.forEach { add(it.toJson()) } }) })
            }
            method == "DELETE" && first == "invites" && rest.size == 2 -> {
                require(g, me, setOf("owner", "admin"))
                val invite = g.invites[rest[1]]
                if (invite == null || invite.state != "pending" || invite.expiresAt <= now) throw Answer(404, "not_found")
                invite.state = "cancelled"
                GroupResponse(204, ByteArray(0))
            }
            method == "GET" && first == "members" && rest.size == 1 -> {
                val members = g.orderedMembers.map { MemberDTO(it.id, it.role, it.joinedAt, it.profile, it.lastLiveAt) }
                ok(buildJsonObject { put("members", buildJsonArray { members.forEach { add(it.toJson()) } }) })
            }
            method == "PUT" && first == "members" && rest.size == 3 && rest[2] == "profile" && rest[1] == "me" -> {
                val profile = envelope(stringMap(json)["profile"], 39..512)
                val m = g.members[me]
                m?.profile = profile
                m?.seq = g.nextSeq()
                ok(SeqDTO(m?.seq ?: 0).toJson())
            }
            method == "PUT" && first == "members" && rest.size == 3 && rest[2] == "role" -> {
                require(g, me, setOf("owner"))
                val role = stringMap(json)["role"]
                if (role != "admin" && role != "member") throw Answer(400, "bad_request")
                val target = if (rest[1] == "me") me else rest[1]
                val m = g.members[target] ?: throw Answer(404, "not_found")
                if (m.role == "owner" || target == me) throw Answer(409, "not_allowed")
                if (m.role == "admin" && role == "member") g.cancelInvites(by = target)
                m.role = role
                m.seq = g.nextSeq()
                ok(SeqDTO(m.seq).toJson())
            }
            method == "DELETE" && first == "members" && rest.size == 2 -> {
                val target = if (rest[1] == "me") me else rest[1]
                val m = g.members[target] ?: throw Answer(404, "not_found")
                val myRole = g.members[me]?.role ?: "member"
                if (target == me) {
                    if (myRole == "owner") throw Answer(409, "owner_must_hand_over")
                    g.remove(target, removed = false, now = now)
                } else {
                    if (m.role == "owner") throw Answer(409, "not_allowed")
                    if (!(myRole == "owner" || (myRole == "admin" && m.role == "member"))) throw Answer(403, "forbidden")
                    g.remove(target, removed = true, now = now)
                }
                GroupResponse(204, ByteArray(0))
            }
            method == "POST" && first == "transfer" && rest.size == 1 -> {
                require(g, me, setOf("owner"))
                val to = stringMap(json)["to"] ?: throw Answer(400, "bad_request")
                if (to == me) throw Answer(409, "not_allowed")
                if (g.members[to] == null) throw Answer(404, "not_found")
                g.transfer = FakeTransfer(to, now + 604_800)
                ok(status(g, me, now).toJson())
            }
            method == "DELETE" && first == "transfer" && rest.size == 1 -> {
                val t = g.transfer ?: throw Answer(404, "not_found")
                if (!(g.members[me]?.role == "owner" || t.to == me)) throw Answer(403, "forbidden")
                g.transfer = null
                GroupResponse(204, ByteArray(0))
            }
            method == "POST" && first == "transfer" && rest.size == 2 && rest[1] == "accept" -> {
                val t = g.transfer ?: throw Answer(404, "not_found")
                if (t.to != me) throw Answer(403, "forbidden")
                val pro = decode(json) { ProProof.fromJson(it.obj("pro")) }
                g.pro = verify(pro, now)
                g.members.values.firstOrNull { it.role == "owner" }?.let { old ->
                    old.role = "admin"
                    old.seq = g.nextSeq()
                }
                g.members[me]?.let {
                    it.role = "owner"
                    it.seq = g.nextSeq()
                }
                g.transfer = null
                if (g.status == "paused" && g.pausedReason in proReasons) g.resume(now)
                ok(status(g, me, now).toJson())
            }
            method == "POST" && first == "upload" && rest.size == 1 -> upload(g, me, json, now)
            method == "GET" && first == "changes" && rest.size == 1 -> {
                val since = (query["since"] ?: "0").toLongOrNull() ?: -1
                val limit = minOf((query["limit"] ?: "500").toIntOrNull() ?: 500, 1000)
                if (since < 0 || limit <= 0) throw Answer(400, "bad_request")
                ok(changes(g, me, since, limit, now).toJson())
            }
            method == "GET" && first == "archive" && rest.size == 3 -> {
                val joinedAt = g.members[me]?.joinedAt
                val a = g.archives["${rest[1]}|${rest[2]}"]
                if (joinedAt == null || a == null) throw Answer(404, "not_found")
                val visible = a.records.filter { it.key + 600 > joinedAt }.mapNotNull { r -> Base64URL.decode(r.value)?.let { GroupArchive.Record(r.key, it) } }
                if (visible.isEmpty()) throw Answer(404, "not_found")
                GroupResponse(200, GroupArchive.build(visible))
            }
            method == "GET" && first == "live" && rest.size == 1 -> {
                val source = liveSource
                if (g.status == "active" && source != null) {
                    for (e in source(g.id, now)) {
                        val m = g.members[e.memberId] ?: continue
                        val rec = e.rec ?: continue
                        if ((g.live[e.memberId]?.at ?: Long.MIN_VALUE) >= e.at) continue
                        g.live[e.memberId] = FakeLive(e.at, rec)
                        m.lastLiveAt = e.at
                    }
                }
                val entries = g.live.filter { it.value.at >= now - 86_400 }.toSortedMap().map { LiveEntry(it.key, it.value.at, it.value.rec) }
                ok(LivePage(entries, g.watchers, now).toJson())
            }
            method == "DELETE" && first == "trail" && rest.size == 2 && rest[1] == "me" -> {
                g.clearTrail(me, now)
                GroupResponse(204, ByteArray(0))
            }
            method == "POST" && first == "pins" && rest.size == 1 -> dropPin(g, me, json, now)
            method == "DELETE" && first == "pins" && rest.size == 2 -> {
                val pin = g.pins[rest[1]] ?: throw Answer(404, "not_found")
                if (!(pin.memberId == me || g.members[me]?.role != "member")) throw Answer(403, "forbidden")
                g.pins.remove(rest[1])
                g.removals.add(Removal(g.nextSeq(), "pin", pin.id, now))
                GroupResponse(204, ByteArray(0))
            }
            method == "POST" && first == "reports" && rest.size == 1 -> {
                val body = decode(json, ReportRequest::fromJson)
                if (body.targetKind !in setOf("pin", "member", "group") || body.reason !in setOf("offensive", "harassment", "spam", "dangerous", "other") ||
                    GroupText.codePoints(body.comment) > 500) {
                    throw Answer(400, "bad_request")
                }
                val exists = when (body.targetKind) {
                    "pin" -> g.pins[body.targetId] != null || g.removals.any { it.what == "pin" && it.id == body.targetId }
                    "member" -> g.members[body.targetId] != null || g.removals.any { it.what == "member" && it.id == body.targetId }
                    else -> body.targetId == g.id
                }
                if (!exists) throw Answer(404, "not_found")
                storedReports.add(body)
                ok(buildJsonObject { put("reportId", GroupRandom.id()) }, status = 202)
            }
            else -> throw Answer(404, "not_found")
        }
    }

    // Endpoints

    private fun create(json: ByteArray?, now: Long): GroupResponse {
        val body = decode(json, CreateGroupRequest::fromJson)
        if (Base64URL.decode(body.groupId, count = 16) == null || Base64URL.decode(body.memberId, count = 16) == null ||
            Base64URL.decode(body.pass, count = 32) == null || Base64URL.decode(body.joinProof, count = 32) == null) {
            throw Answer(400, "bad_request")
        }
        val groupProfile = envelope(body.groupProfile, 39..512)
        val memberProfile = envelope(body.memberProfile, 39..512)
        if (body.termsVersion < termsVersion) throw Answer(409, "terms_outdated")
        val existing = groups[body.groupId]
        if (existing != null) {
            val owner = existing.members.values.firstOrNull { it.role == "owner" }
            if (existing.status != "deleted" && owner != null && owner.pass == body.pass) return ok(status(existing, owner.id, now).toJson())
            throw Answer(409, "group_exists")
        }
        val pro = verify(body.pro, now)
        val key = proKey(body.pro)
        if ((proGroups[key]?.size ?: 0) >= maxGroupsPerPurchase) throw Answer(409, "too_many_groups")
        val g = FakeGroup(body.groupId, body.joinProof, groupProfile, pro, key)
        g.profileSeq = g.nextSeq()
        g.members[body.memberId] = FakeMember(body.memberId, body.pass, "owner", now, memberProfile, g.nextSeq(), lastLiveAt = null)
        groups[body.groupId] = g
        proGroups.getOrPut(key) { HashSet() }.add(body.groupId)
        return ok(status(g, body.memberId, now).toJson(), status = 201)
    }

    private fun invite(token: String, joinProof: String, now: Long): Pair<FakeGroup, FakeInvite> {
        val bytes = Base64URL.decode(token, count = 32) ?: throw Answer(400, "bad_request")
        val gid = Base64URL.encode(bytes.copyOf(16))
        val secret = bytes.copyOfRange(16, 32)
        val g = groups[gid]
        val invite = g?.invites?.values?.firstOrNull { it.secret.contentEquals(secret) }
        if (g == null || g.status == "deleted" || g.joinProof != joinProof || invite == null) throw Answer(404, "invite_not_found")
        g.housekeeping(now)
        return g to invite
    }

    private fun preview(json: ByteArray?, now: Long): GroupResponse {
        val body = decode(json, PreviewRequest::fromJson)
        val (g, invite) = invite(body.token, body.joinProof, now)
        if (invite.state != "pending" || invite.expiresAt <= now) throw Answer(404, "invite_not_found")
        val inviter = g.members[invite.createdBy]?.let { PreviewDTO.Inviter(it.id, it.profile) }
        return ok(PreviewDTO(g.id, g.status, g.profile, g.members.size, inviter, invite.expiresAt).toJson())
    }

    private fun redeem(json: ByteArray?, now: Long): GroupResponse {
        val body = decode(json, RedeemRequest::fromJson)
        if (Base64URL.decode(body.memberId, count = 16) == null || Base64URL.decode(body.pass, count = 32) == null) throw Answer(400, "bad_request")
        val profile = envelope(body.profile, 39..512)
        val (g, invite) = invite(body.token, body.joinProof, now)
        if (invite.state == "used" && invite.usedPass == body.pass && g.members[body.memberId] != null) {
            return ok(status(g, body.memberId, now).toJson())
        }
        if (invite.state != "pending" || invite.expiresAt <= now) throw Answer(404, "invite_not_found")
        if (body.termsVersion < termsVersion) throw Answer(409, "terms_outdated")
        if (g.members[body.memberId] != null || g.removals.any { it.what == "member" && it.id == body.memberId }) throw Answer(409, "member_exists")
        invite.state = "used"
        invite.usedPass = body.pass
        g.members[body.memberId] = FakeMember(body.memberId, body.pass, "member", now, profile, g.nextSeq(), lastLiveAt = null)
        return ok(status(g, body.memberId, now).toJson(), status = 201)
    }

    private fun upload(g: FakeGroup, me: String, json: ByteArray?, now: Long): GroupResponse {
        val body = decode(json, UploadRequest::fromJson)
        if (body.chunks.size > 50) throw Answer(413, "too_large")
        if (g.status == "paused" && !(body.chunks.isEmpty() && body.live == UploadRequest.LiveField.Stopped)) throw Answer(409, "group_paused")
        val joinedAt = g.members[me]?.joinedAt ?: now
        val accepted = mutableListOf<Long>()
        val rejected = mutableListOf<UploadRejection>()
        for (c in body.chunks) {
            val rec = Base64URL.decode(c.rec)
            val size = rec?.size ?: 0
            val version = rec?.firstOrNull()
            val forced = chunkRejections[c.start]
            when {
                forced != null -> rejected.add(UploadRejection(c.start, forced))
                // In the real server's order; a start off the 600 s grid can never be accepted, so it is bad_record.
                c.start % 600 != 0L -> rejected.add(UploadRejection(c.start, "bad_record"))
                c.start + 600 <= joinedAt -> rejected.add(UploadRejection(c.start, "before_join"))
                c.start > now + 600 -> rejected.add(UploadRejection(c.start, "bad_time"))
                size !in 32..12_288 || version != 1.toByte() -> rejected.add(UploadRejection(c.start, "bad_record"))
                g.pauseLog.any { c.start >= it.from && c.start + 600 <= (it.to ?: Long.MAX_VALUE) } -> rejected.add(UploadRejection(c.start, "window_paused"))
                else -> {
                    g.chunks["$me|${c.start}"] = FakeChunk(me, c.start, c.rec, g.nextSeq())
                    accepted.add(c.start)
                }
            }
        }
        var liveAccepted = false
        when (val live = body.live) {
            UploadRequest.LiveField.Unchanged -> {}
            UploadRequest.LiveField.Stopped -> {
                g.live.remove(me)
                liveAccepted = true
            }
            is UploadRequest.LiveField.Position -> {
                val l = live.live
                val rec = Base64URL.decode(l.rec)
                val size = rec?.size ?: 0
                val newer = (g.live[me]?.at ?: Long.MIN_VALUE) < l.at
                val spaced = (g.lastLiveAccepted[me] ?: Long.MIN_VALUE) <= now - 2
                if (l.at >= now - 3600 && l.at <= now + 600 && size in 53..128 && rec?.firstOrNull() == 1.toByte() && newer && spaced) {
                    g.live[me] = FakeLive(l.at, l.rec)
                    g.lastLiveAccepted[me] = now
                    g.members[me]?.lastLiveAt = l.at
                    liveAccepted = true
                }
            }
        }
        return ok(UploadResult(accepted, rejected, liveAccepted, g.watchers, g.status, now).toJson())
    }

    private fun dropPin(g: FakeGroup, me: String, json: ByteArray?, now: Long): GroupResponse {
        val body = decode(json, PinRequest::fromJson)
        if (Base64URL.decode(body.pinId, count = 16) == null) throw Answer(400, "bad_request")
        g.pins[body.pinId]?.let { existing ->
            if (existing.memberId == me) return ok(SeqDTO(existing.seq).toJson())
            throw Answer(409, "pin_exists")
        }
        if (g.removals.any { it.what == "pin" && it.id == body.pinId }) throw Answer(410, "pin_deleted")
        if (g.status == "paused") throw Answer(409, "group_paused")
        if (body.expiresAt != null && body.expiresAt != body.createdAt + 43_200) throw Answer(400, "bad_record")
        val rec = envelope(body.rec, 49..1024, code = "bad_record")
        if (body.expiresAt != null && body.expiresAt <= now) throw Answer(422, "pin_expired")
        if (body.createdAt > now + 600 || (body.expiresAt == null && body.createdAt < now - 604_800)) throw Answer(422, "bad_time")
        val seq = g.nextSeq()
        g.pins[body.pinId] = FakePin(body.pinId, me, body.createdAt, body.expiresAt, rec, seq)
        return ok(SeqDTO(seq).toJson(), status = 201)
    }

    private fun changes(g: FakeGroup, me: String, since: Long, limit: Int, now: Long): ChangesPage {
        if (since > g.seq) return ChangesPage(emptyList(), cursor = 0, more = false, serverTime = now, reset = true)
        val joinedAt = g.members[me]?.joinedAt ?: 0
        val items = mutableListOf<ChangeItem>()
        if (g.profileSeq > since) items.add(ChangeItem.Group(g.profileSeq, g.profile))
        for (m in g.members.values) {
            if (m.seq > since) items.add(ChangeItem.Member(m.seq, m.id, m.role, m.joinedAt, m.profile))
        }
        for (c in g.chunks.values) {
            if (c.seq > since && c.start + 600 > joinedAt) items.add(ChangeItem.Chunk(c.seq, c.memberId, c.start, c.rec))
        }
        for (a in g.archives.values) {
            if (a.seq <= since) continue
            val range = GroupStore.monthRange(a.month)
            if (range != null && range.second <= joinedAt) continue
            items.add(ChangeItem.Archive(a.seq, a.memberId, a.month, a.maxChunkSeq, a.archivedAt))
        }
        for (p in g.pins.values) {
            if (p.seq > since && (p.expiresAt?.let { it > now } ?: true)) {
                items.add(ChangeItem.Pin(p.seq, p.id, p.memberId, p.createdAt, p.expiresAt, p.rec))
            }
        }
        for (r in g.removals) {
            if (r.seq > since) items.add(ChangeItem.Removed(r.seq, r.what, r.id))
        }
        items.sortBy { it.seq }
        val page = items.take(limit)
        return ChangesPage(page, cursor = page.lastOrNull()?.seq ?: since, more = items.size > page.size, serverTime = now)
    }

    // Helpers

    private fun auth(groupId: String, pass: String?, now: Long): Pair<FakeGroup, String> {
        val g = groups[groupId] ?: throw Answer(404, "group_not_found")
        if (g.status == "deleted") throw Answer(410, "group_deleted")
        if (pass == null || Base64URL.decode(pass, count = 32) == null) throw Answer(401, "unauthorized")
        val m = g.members.values.firstOrNull { it.pass == pass }
        if (m == null) {
            if (g.gone[pass] == "removed") throw Answer(410, "removed")
            throw Answer(401, "unauthorized")
        }
        g.housekeeping(now)
        return g to m.id
    }

    private fun require(g: FakeGroup, me: String, roles: Set<String>) {
        val role = g.members[me]?.role
        if (role == null || role !in roles) throw Answer(403, "forbidden")
    }

    private fun envelope(text: String?, sizes: IntRange, code: String = "bad_record"): String {
        val data = text?.let(Base64URL::decode)
        if (text == null || data == null || data.firstOrNull() != 1.toByte() || data.size !in sizes) throw Answer(400, code)
        return text
    }

    private fun verify(proof: ProProof, now: Long): ProInfoDTO {
        if (!proAccepted) throw Answer(402, proError)
        return when (proof) {
            is ProProof.Apple -> {
                // The fake reads "fake:{productId}:{expiresAt or -}"; anything else is a yearly purchase.
                val parts = proof.signedTransaction.split(":").filter { it.isNotEmpty() }
                if (parts.size == 3 && parts[0] == "fake") {
                    ProInfoDTO("apple", parts[1], parts[2].toLongOrNull())
                } else {
                    ProInfoDTO("apple", "pro.yearly", now + 365 * 86_400)
                }
            }
            is ProProof.Google -> ProInfoDTO("google", proof.productId, if (proof.productId == "pro_lifetime") null else now + 30 * 86_400)
        }
    }

    private fun status(g: FakeGroup, me: String, now: Long): GroupStatusDTO {
        val m = g.members[me]
        val intervals = g.pauseLog.filter { e -> e.to?.let { it > now - 2_592_000 } ?: true }.map { listOf(it.from, it.to) }
        val transfer = g.transfer?.let { if (it.expiresAt > now) TransferDTO(it.to, it.expiresAt) else null }
        return GroupStatusDTO(groupId = g.id, status = g.status, pausedReason = g.pausedReason, pausedAt = g.pausedAt,
                              deleteAt = g.pausedAt?.let { it + 2_592_000 }, pausedIntervals = intervals,
                              ownerId = g.members.values.firstOrNull { it.role == "owner" }?.id ?: "", memberCount = g.members.size,
                              profile = g.profile, profileSeq = g.profileSeq,
                              you = YouDTO(memberId = me, role = m?.role ?: "member", joinedAt = m?.joinedAt ?: now),
                              transfer = transfer, pro = if (m?.role == "owner") g.pro else null, watchers = g.watchers, seq = g.seq, serverTime = now)
    }

    private fun <T> decode(json: ByteArray?, read: (GroupJSON.Fields) -> T): T {
        if (json == null) throw Answer(400, "bad_request")
        return try {
            read(GroupJSON.Fields(GroupJSON.parseObject(json)))
        } catch (e: Exception) {
            throw Answer(400, "bad_request")
        }
    }

    /** A body of string values (Swift's `[String: String]`). */
    private fun stringMap(json: ByteArray?): Map<String, String> = decode(json) { f -> f.obj.mapValues { GroupJSON.stringValue(it.value) } }

    private fun ok(value: JsonObject, status: Int = 200): GroupResponse = GroupResponse(status, value.toString().toByteArray(Charsets.UTF_8))

    private fun <T> withGroup(groupId: String, body: (FakeGroup) -> T): T? = synchronized(lock) {
        groups[groupId]?.let(body)
    }

    companion object {
        private val proReasons = setOf("pro_ended", "pro_refunded", "pro_unverified")

        private fun error(status: Int, code: String): GroupResponse =
            GroupResponse(status, ErrorDTO(error = code, message = "fake server").toJson().toString().toByteArray(Charsets.UTF_8))

        private fun query(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (pair in text.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) out[kv[0]] = kv[1]
            }
            return out
        }

        private fun proKey(proof: ProProof): String = when (proof) {
            is ProProof.Apple -> "apple:" + proof.signedTransaction
            is ProProof.Google -> "google:" + proof.purchaseToken
        }

        /** "YYYY-MM" (UTC) of a time. */
        fun month(time: Long): String {
            val days = Math.floorDiv(time, 86_400L)
            // Civil from days (proleptic Gregorian).
            val z = days + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
            return String.format(Locale.ROOT, "%04d-%02d", y, m)
        }
    }
}

// The fake's rows

private class FakeMember(val id: String, val pass: String, var role: String, val joinedAt: Long, var profile: String, var seq: Long, var lastLiveAt: Long?)

private class FakeInvite(val id: String, val secret: ByteArray, val createdBy: String, val createdAt: Long, val expiresAt: Long, var state: String,
                         var usedPass: String?)

private class FakeChunk(val memberId: String, val start: Long, val rec: String, val seq: Long)

private class FakeArchive(val memberId: String, val month: String, val records: HashMap<Long, String>, var seq: Long, var maxChunkSeq: Long,
                          var archivedAt: Long)

private class FakePin(val id: String, val memberId: String, val createdAt: Long, val expiresAt: Long?, val rec: String, val seq: Long)

private class FakeLive(val at: Long, val rec: String)

private class FakeTransfer(val to: String, val expiresAt: Long)

private class Removal(val seq: Long, val what: String, val id: String, val at: Long)

private class PauseEntry(val from: Long, var to: Long?, val reason: String)

private class FakeGroup(val id: String, val joinProof: String, var profile: String, var pro: ProInfoDTO?, val proKey: String) {
    var status = "active"
    var pausedReason: String? = null
    var pausedAt: Long? = null
    var deletedAt: Long? = null
    var profileSeq = 0L
    var seq = 0L
    val members = HashMap<String, FakeMember>()

    /** Pass → reason, for passes removed in the last 30 days. */
    val gone = HashMap<String, String>()
    val invites = HashMap<String, FakeInvite>()
    val chunks = HashMap<String, FakeChunk>()
    val archives = HashMap<String, FakeArchive>()
    val pins = HashMap<String, FakePin>()
    val removals = mutableListOf<Removal>()
    val live = HashMap<String, FakeLive>()
    val lastLiveAccepted = HashMap<String, Long>()
    val pauseLog = mutableListOf<PauseEntry>()
    var transfer: FakeTransfer? = null
    var watchers = 0

    val orderedMembers: List<FakeMember> get() = members.values.sortedWith(compareBy<FakeMember>({ it.joinedAt }, { it.id }))

    fun nextSeq(): Long {
        seq += 1
        return seq
    }

    /** Expired animal pins go (with a `removed pin` item); an expired ownership offer ends. */
    fun housekeeping(now: Long) {
        for (p in pins.values.toList()) {
            if (p.expiresAt?.let { it <= now } == true) {
                pins.remove(p.id)
                removals.add(Removal(nextSeq(), "pin", p.id, now))
            }
        }
        transfer?.let { if (it.expiresAt <= now) transfer = null }
    }

    fun resume(now: Long) {
        status = "active"
        pausedReason = null
        pausedAt = null
        pauseLog.lastOrNull()?.let { if (it.to == null) it.to = now }
    }

    fun cancelInvites(by: String) {
        for (i in invites.values) {
            if (i.createdBy == by && i.state == "pending") i.state = "cancelled"
        }
    }

    fun clearTrail(memberId: String, now: Long) {
        chunks.values.removeAll { it.memberId == memberId }
        archives.values.removeAll { it.memberId == memberId }
        removals.add(Removal(nextSeq(), "trail", memberId, now))
    }

    /** E14's effects (§6.5): the member's chunks, archives, pins, live row and member row go; a `removed member` item;
     *  an offer to them ends; their pending invites are cancelled; a removed pass answers `410 removed`. */
    fun remove(memberId: String, removed: Boolean, now: Long) {
        val m = members[memberId] ?: return
        chunks.values.removeAll { it.memberId == memberId }
        archives.values.removeAll { it.memberId == memberId }
        pins.values.removeAll { it.memberId == memberId }
        live.remove(memberId)
        members.remove(memberId)
        removals.add(Removal(nextSeq(), "member", memberId, now))
        if (transfer?.to == memberId) transfer = null
        cancelInvites(by = memberId)
        if (removed) gone[m.pass] = "removed"
    }

    fun markDeleted(now: Long) {
        status = "deleted"
        deletedAt = now
        members.clear()
        chunks.clear()
        archives.clear()
        pins.clear()
        live.clear()
        invites.clear()
        transfer = null
    }
}
