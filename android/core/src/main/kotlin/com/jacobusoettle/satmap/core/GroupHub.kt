package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.random.Random

// Transport, sockets and secrets (docs/groups/SPEC.md §8.2)

/** The app's HTTP transport (OkHttp on Android; `FakeGroupServer` in demo mode and tests). */
interface GroupTransport {
    /** One request to "/v1/…" under the base URL; `pass` is the Bearer token or null. Throws
     *  `GroupTransportError.Offline` when there is no connection, `.Failed` for any other transport error. */
    suspend fun request(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse
}

/** A transport's answer: the HTTP status and the body (the Swift `(status: Int, body: Data)`). */
class GroupResponse(val status: Int, val body: ByteArray)

/** The Swift `GroupTransportError` cases. */
sealed class GroupTransportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable? = null) : GroupTransportError("No connection", cause)
    class Failed(cause: Throwable? = null) : GroupTransportError("The request failed", cause)
}

/** Opens the WebSocket of a group (the URL comes from the base URL). The app reports what happens on it through
 *  `GroupHub.socketDidOpen`, `socketDidReceive` and `socketDidClose`, on the main thread. */
interface GroupSocketFactory {
    fun open(groupId: String, pass: String): GroupSocket
}

interface GroupSocket {
    fun send(text: String)
    fun close(code: Int)
}

/** Where the memberships live (Keystore on Android, Keychain on iPhone; never backed up). */
interface GroupSecretStore {
    fun loadAll(): List<GroupSecret>

    /** Throws if the secret can't be saved. */
    fun save(secret: GroupSecret)
    fun delete(groupId: String)
}

/** A membership: the group, your member id and pass, and the group key (base64url texts). */
data class GroupSecret(val groupId: String, val memberId: String, val pass: String, val groupKey: String) {
    /** The pass and the key never go into logs. */
    override fun toString(): String = "GroupSecret(groupId=$groupId, memberId=$memberId)"

    /** The Swift `Codable` form: `{"groupId", "memberId", "pass", "groupKey"}`. */
    fun toJson() = buildJsonObject {
        put("groupId", groupId)
        put("memberId", memberId)
        put("pass", pass)
        put("groupKey", groupKey)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = GroupSecret(f.string("groupId"), f.string("memberId"), f.string("pass"), f.string("groupKey"))

        /** The JSON array of secrets the app's secret store keeps (§8.3). */
        fun encodeAll(secrets: List<GroupSecret>): ByteArray = buildJsonArray { secrets.forEach { add(it.toJson()) } }.toString().toByteArray(Charsets.UTF_8)

        /** `encodeAll`'s bytes; throws `SerializationException` if they aren't such an array. */
        fun decodeAll(json: ByteArray): List<GroupSecret> =
            (GroupJSON.parse(json) as? JsonArray ?: throw SerializationException("Not an array")).map { fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }
    }
}

/** Secrets in memory only: demo mode (§8.15) and tests. */
class MemoryGroupSecrets(initial: List<GroupSecret> = emptyList()) : GroupSecretStore {
    private val secrets = LinkedHashMap<String, GroupSecret>()

    init {
        for (s in initial) save(s)
    }

    override fun loadAll(): List<GroupSecret> = synchronized(secrets) { secrets.values.toList() }

    override fun save(secret: GroupSecret) {
        synchronized(secrets) { secrets[secret.groupId] = secret }
    }

    override fun delete(groupId: String) {
        synchronized(secrets) { secrets.remove(groupId) }
    }
}

// Value types (§8.2.1)

enum class GroupRole {
    owner,
    admin,
    member;

    val rawValue: String get() = name

    companion object {
        operator fun invoke(rawValue: String): GroupRole? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class TrailsMode {
    off,
    day,
    all;

    val rawValue: String get() = name

    companion object {
        operator fun invoke(rawValue: String): TrailsMode? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class PausedReason(val rawValue: String) {
    proEnded("pro_ended"),
    proRefunded("pro_refunded"),
    proUnverified("pro_unverified");

    companion object {
        operator fun invoke(rawValue: String): PausedReason? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class ReportReason {
    offensive,
    harassment,
    spam,
    dangerous,
    other;

    val rawValue: String get() = name
}

sealed interface ReportTarget {
    data class Pin(val pinId: String) : ReportTarget
    data class Member(val memberId: String) : ReportTarget
    data object Group : ReportTarget
}

/** The rule in §8.9. */
sealed interface MemberState {
    data class Sharing(val at: Long) : MemberState
    data class LastSeen(val at: Long) : MemberState
    data object NotSharing : MemberState
}

data class GroupCoordinate(val lat: Double, val lon: Double)

/** An S2 row. */
data class GroupSummary(
    val groupId: String,
    val name: String,
    val memberCount: Int,
    val isSharing: Boolean,
    val isPaused: Boolean,
    val hasOfferToYou: Boolean,
    val isShown: Boolean,
)

/** An S5 member row. */
data class MemberRow(
    val memberId: String,
    val name: String,
    val initials: String,
    val colourHex: String,
    val role: GroupRole,
    val isYou: Boolean,
    val isBlocked: Boolean,
    val state: MemberState,
)

/** S5. */
data class GroupDetail(
    val groupId: String,
    val name: String,
    val memberCount: Int,
    val yourMemberId: String,
    val yourRole: GroupRole,
    val joinedAt: Long,
    val isPaused: Boolean,
    val pausedReason: PausedReason?,
    val deleteAt: Long?,
    val isShown: Boolean,
    val isSharing: Boolean,
    val hasConsent: Boolean,
    val trails: TrailsMode,
    /** The owner's name when an offer to you is open. */
    val offerToYouFrom: String?,
    /** The target's name when your offer is open. */
    val offerByYouTo: String?,
    /** Ordered by joinedAt. */
    val members: List<MemberRow>,
    /** Null unless owner or admin; empty until E7 answers. */
    val invites: List<PendingInvite>?,
    /** S16; real names. */
    val blocked: List<MemberRow>,
    val canInvite: Boolean,
    val canRename: Boolean,
    val canHandOver: Boolean,
    val canDeleteGroup: Boolean,
    val canLeave: Boolean,
    /** Admins and members (§9.3 S5). */
    val canReportGroupName: Boolean,
    /** "{groupId}.{memberId}". */
    val supportCode: String,
)

/** S12. */
data class MemberDetail(
    val row: MemberRow,
    /** The newest live position, if any and not hidden. */
    val live: GroupCoordinate?,
    val canMakeAdmin: Boolean,
    val canRemoveAdmin: Boolean,
    val canMakeOwner: Boolean,
    val canRemove: Boolean,
    /** False for yourself. */
    val canReport: Boolean,
    val canBlock: Boolean,
)

/** S11. */
data class PinDetail(
    val pinId: String,
    val type: String,
    val knownType: PinType?,
    val animal: String?,
    val note: String?,
    val dropperId: String,
    val dropperName: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val position: GroupCoordinate,
    val isYours: Boolean,
    val canDelete: Boolean,
    val canReport: Boolean,
    val canBlock: Boolean,
)

/** S8. */
data class InvitePreview(
    val groupId: String,
    val name: String,
    val inviterName: String?,
    val memberCount: Int,
    val isPaused: Boolean,
    val expiresAt: Long,
)

/** S6. */
data class MadeInvite(val inviteId: String, val link: String, val expiresAt: Long, val groupName: String)

/** An S5 invite row. */
data class PendingInvite(
    val inviteId: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** Only if this phone made it. */
    val link: String?,
)

data class GroupAlert(val kind: Kind, val groupId: String, val groupName: String) {
    enum class Kind { removed, deleted, gone }
}

sealed interface GroupHubEvent {
    data object Groups : GroupHubEvent
    data class Group(val groupId: String) : GroupHubEvent
    data object Map : GroupHubEvent
    data class Alert(val alert: GroupAlert) : GroupHubEvent
}

/** §9.5, for the shown group; blocked members and filters already applied. */
data class GroupMapContent(
    val groupId: String,
    /** Not you; not blocked; live within 24 h. */
    val members: List<MapMember>,
    /** Per the Trails setting; already thinned (§9.5). */
    val trails: List<MapTrail>,
    /** Unexpired; not from blocked members. */
    val pins: List<MapPin>,
)

data class MapMember(
    val memberId: String,
    val position: GroupCoordinate,
    val colourHex: String,
    val initials: String,
    /** Null when unknown or stale. */
    val headingDeg: Int?,
    val isStale: Boolean,
    /** "{name}, {state text}" (§9.6). */
    val a11yLabel: String,
)

data class MapTrail(
    val memberId: String,
    val colourHex: String,
    /** 3, or 2 above 10 visible trails. */
    val widthPt: Double,
    /** Each a polyline of 2+ points, oldest first. */
    val segments: List<List<GroupCoordinate>>,
)

data class MapPin(
    val pinId: String,
    val type: String,
    val knownType: PinType?,
    val position: GroupCoordinate,
    /** §9.4. */
    val colourHex: String,
    /** Animal pins: "{animal} · {ago}" at `now`; null otherwise. */
    val label: String?,
    /** "{title}, dropped by {name} {ago}" (§9.6). */
    val a11yLabel: String,
)

/** What an action throws; `message` is the text to show (§8.14, or the field texts of §9.3). */
data class GroupActionError(val kind: Kind, override val message: String) : Exception(message) {
    sealed interface Kind {
        data object Offline : Kind
        data object RateLimited : Kind
        data object Server : Kind
        data object StoreUnavailable : Kind
        data object ProNotConfirmed : Kind
        data object TooManyGroups : Kind
        data object Forbidden : Kind
        data object InviteNotFound : Kind
        data class AlreadyMember(val groupId: String) : Kind
        data object DamagedLink : Kind
        data object TermsOutdated : Kind
        data object GroupPaused : Kind
        data object NotFound : Kind
        data class InvalidText(val field: GroupTextField) : Kind
        data class WordFilter(val field: GroupTextField) : Kind
    }

    /** The error with its standard text. */
    constructor(kind: Kind) : this(kind, standardMessage(kind))

    companion object {
        /** The "Gone" alert's message, for an action on a group that no longer exists for you. */
        val gone: GroupActionError get() = GroupActionError(Kind.NotFound, "The group no longer exists, or your membership ended.")

        fun standardMessage(kind: Kind): String = when (kind) {
            Kind.Offline -> "No connection. Try again when you're online."
            Kind.RateLimited -> "Too many tries. Wait a moment and try again."
            Kind.Server -> "Something went wrong on our side. Try again in a minute."
            Kind.StoreUnavailable -> "The store can't be reached right now. Try again later."
            Kind.ProNotConfirmed -> "SatMap Pro couldn't be confirmed. Tap Restore purchases, then try again."
            Kind.TooManyGroups -> "You already own 20 groups. Delete one to make a new group."
            Kind.Forbidden -> "You can't do that in this group."
            Kind.InviteNotFound -> "This invite has already been used, was cancelled or has expired. Ask for a new one."
            is Kind.AlreadyMember -> "You're already in this group."
            Kind.DamagedLink -> "This invite link is damaged. Ask for a new one."
            Kind.TermsOutdated -> "Please update SatMap to create or join groups."
            Kind.GroupPaused -> "This group is paused at the moment."
            Kind.NotFound -> "This is no longer in the group."
            is Kind.InvalidText -> when (kind.field) {
                GroupTextField.groupName -> "Group names are 1 to 40 characters, on one line."
                GroupTextField.displayName -> "Names are 1 to 30 characters, on one line."
                GroupTextField.animal -> "Type the animal, up to 40 characters."
                GroupTextField.note -> "Notes are up to 140 characters and 4 lines."
                GroupTextField.reportComment -> "Details are up to 500 characters and 4 lines."
            }
            is Kind.WordFilter -> when (kind.field) {
                GroupTextField.groupName -> "Please use different words for the group name."
                GroupTextField.displayName -> "Please use different words for your name."
                GroupTextField.animal, GroupTextField.note, GroupTextField.reportComment -> "Please use different words."
            }
        }
    }
}

// The hub

/** The groups sync engine (§8): it holds the memberships, keeps the local store in step with the server (status,
 *  change feed, live positions, WebSocket ticks), uploads your trail and live position at the §8.6 pace, sends the
 *  queued requests, and builds what the screens and the map show.
 *
 *  All calls happen on the main thread (§8.1): the plain functions by the app's contract, the `suspend` ones by
 *  being called from it (the network requests may switch threads inside the transport and come back). The work the
 *  plain functions start (an upload after sharing is switched off, a pull a socket tick asks for) runs in `scope`,
 *  which must run on the same thread: the main thread in the app (the default), the test's own thread in the tests.
 *  State is in memory, mirrored in `GroupStore`. The iPhone core's GroupHub.swift behaves the same, on the main
 *  actor. */
class GroupHub(
    private val store: GroupStore,
    private val secrets: GroupSecretStore,
    transport: GroupTransport,
    private val sockets: GroupSocketFactory?,
    private val words: WordFilter,
    /** The invite domain, for links (`GroupConfig.domain`). */
    val domain: String,
    private val clock: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val api = GroupAPI(transport)

    var onChange: ((GroupHubEvent) -> Unit)? = null

    /** The terms version the person accepted (`groupsTermsAccepted`), sent with create and redeem (§8.13). On
     *  `terms_outdated` the action throws `TermsOutdated`; the app shows S1 when it has newer terms, sets this and
     *  tries again. */
    var acceptedTermsVersion = GroupPolicy.termsVersion

    private val states = LinkedHashMap<String, GroupState>()

    /** Secrets with no local group yet: a reinstall's restore (§8.3), or a create or join whose answer was lost. */
    private val pendingSecrets = HashMap<String, GroupSecret>()
    private var foreground = false
    private var locationAuthorised = false
    private var locationPrecise = false
    private var latestFix: Fix? = null
    private var socket: SocketState? = null
    private var reconnectAttempt = 0
    private var reconnectAt: Long? = null
    private val backgroundWork = mutableListOf<Job>()

    // Reading

    val groups: List<GroupSummary>
        get() = orderedStates.map { st ->
            GroupSummary(groupId = st.id, name = groupName(st), memberCount = memberCount(st), isSharing = st.sharingOn,
                         isPaused = st.isPaused, hasOfferToYou = st.status?.transfer?.to == st.secret.memberId, isShown = st.row.shown)
        }

    fun detail(groupId: String): GroupDetail? {
        val st = states[groupId] ?: return null
        val now = clock()
        val role = st.role
        val rows = st.members.map { memberRow(st, it, now, realName = false) }
        val blockedRows = st.members.filter { st.blocked.contains(it.memberId) && it.memberId != st.secret.memberId }
            .map { memberRow(st, it, now, realName = true) }
        var offerTo: String? = null
        var offerBy: String? = null
        val t = st.status?.transfer
        if (t != null && t.expiresAt > now) {
            val owner = st.status?.ownerId
            if (t.to == st.secret.memberId && owner != null) {
                offerTo = name(st, owner, realName = false)
            } else if (role == GroupRole.owner) {
                offerBy = name(st, t.to, realName = false)
            }
        }
        var invites: List<PendingInvite>? = null
        if (role == GroupRole.owner || role == GroupRole.admin) {
            val made = HashMap<String, String>()
            for (m in st.madeInvites) made.putIfAbsent(m.inviteId, m.link)
            invites = (st.invites ?: emptyList()).filter { it.expiresAt > now }.map { i ->
                PendingInvite(inviteId = i.inviteId, createdBy = i.createdBy, createdByName = name(st, i.createdBy, realName = false),
                              createdAt = i.createdAt, expiresAt = i.expiresAt, link = made[i.inviteId])
            }
        }
        return GroupDetail(groupId = st.id, name = groupName(st), memberCount = memberCount(st), yourMemberId = st.secret.memberId,
                           yourRole = role, joinedAt = st.row.joinedAt, isPaused = st.isPaused,
                           pausedReason = st.status?.pausedReason?.let { PausedReason(it) },
                           deleteAt = if (st.isPaused) st.status?.deleteAt else null, isShown = st.row.shown, isSharing = st.sharingOn,
                           hasConsent = st.row.consentAt != null, trails = TrailsMode(st.row.trails) ?: TrailsMode.day,
                           offerToYouFrom = offerTo, offerByYouTo = offerBy, members = rows, invites = invites, blocked = blockedRows,
                           canInvite = role != GroupRole.member, canRename = role != GroupRole.member, canHandOver = role == GroupRole.owner,
                           canDeleteGroup = role == GroupRole.owner, canLeave = role != GroupRole.owner, canReportGroupName = role != GroupRole.owner,
                           supportCode = "${st.id}.${st.secret.memberId}")
    }

    val shownGroupId: String? get() = orderedStates.firstOrNull { it.row.shown }?.id

    fun mapContent(now: Long): GroupMapContent? {
        val gid = shownGroupId ?: return null
        val st = states[gid] ?: return null
        val me = st.secret.memberId
        val members = mutableListOf<MapMember>()
        for (m in st.members) {
            if (m.memberId == me || st.blocked.contains(m.memberId)) continue
            val l = st.live[m.memberId] ?: continue
            if (now - l.at > GroupPolicy.liveHiddenAfter) continue
            val stale = now - l.at > GroupPolicy.staleAfter
            val profile = memberProfile(st, m)
            val shown = profile?.let { words.masked(it.name) } ?: unknownMember
            members.add(MapMember(memberId = m.memberId, position = coordinate(l.position.lat, l.position.lon),
                                  colourHex = colourHex(profile), initials = GroupText.initials(shown),
                                  headingDeg = if (stale) null else l.position.heading, isStale = stale,
                                  a11yLabel = "$shown, ${stateText(memberState(st, m.memberId, now), now)}"))
        }
        val pins = mutableListOf<MapPin>()
        for (p in st.pins) {
            if (!(p.expiresAt?.let { it > now } ?: true) || st.blocked.contains(p.memberId)) continue
            val rec = pinRecord(st, p) ?: continue
            val ago = GroupFormat.ago(now - p.createdAt)
            val animal = rec.animal?.let { words.masked(it) }
            val title = if (rec.knownType == PinType.animal) animal ?: PinType.animal.label else rec.knownType?.label ?: PinType.unknownLabel
            pins.add(MapPin(pinId = p.pinId, type = rec.type, knownType = rec.knownType, position = coordinate(rec.lat, rec.lon),
                            colourHex = rec.knownType?.colourHex ?: PinType.unknownColourHex,
                            label = if (rec.knownType == PinType.animal) "${animal ?: ""} · $ago" else null,
                            a11yLabel = "$title, dropped by ${name(st, p.memberId, realName = false)} $ago"))
        }
        return GroupMapContent(groupId = gid, members = members, trails = trails(st, now), pins = pins)
    }

    fun pinDetail(groupId: String, pinId: String): PinDetail? {
        val st = states[groupId] ?: return null
        val p = st.pins.firstOrNull { it.pinId == pinId } ?: return null
        val rec = pinRecord(st, p) ?: return null
        val mine = p.memberId == st.secret.memberId
        return PinDetail(pinId = p.pinId, type = rec.type, knownType = rec.knownType, animal = rec.animal?.let { words.masked(it) },
                         note = rec.note?.let { words.masked(it) }, dropperId = p.memberId, dropperName = name(st, p.memberId, realName = false),
                         createdAt = p.createdAt, expiresAt = p.expiresAt, position = coordinate(rec.lat, rec.lon), isYours = mine,
                         canDelete = mine || st.role != GroupRole.member, canReport = !mine, canBlock = !mine)
    }

    fun memberDetail(groupId: String, memberId: String): MemberDetail? {
        val st = states[groupId] ?: return null
        val m = st.members.firstOrNull { it.memberId == memberId } ?: return null
        val now = clock()
        val row = memberRow(st, m, now, realName = false)
        val you = memberId == st.secret.memberId
        var live: GroupCoordinate? = null
        val l = st.live[memberId]
        if (!row.isBlocked && l != null && now - l.at <= GroupPolicy.liveHiddenAfter) {
            live = coordinate(l.position.lat, l.position.lon)
        }
        val myRole = st.role
        return MemberDetail(row = row, live = live,
                            canMakeAdmin = myRole == GroupRole.owner && !you && row.role == GroupRole.member,
                            canRemoveAdmin = myRole == GroupRole.owner && !you && row.role == GroupRole.admin,
                            canMakeOwner = myRole == GroupRole.owner && !you,
                            canRemove = !you && row.role != GroupRole.owner &&
                                (myRole == GroupRole.owner || (myRole == GroupRole.admin && row.role == GroupRole.member)),
                            canReport = !you, canBlock = !you)
    }

    fun needsConsent(groupId: String): Boolean = states[groupId]?.row?.consentAt == null

    /** Seconds until the app should call `tick` again: 5 in the foreground; in the background 15 while someone watches a
     *  group you share with, 60 while you share or something is queued; null when there is nothing to do. */
    fun nextTickDelay(foreground: Boolean): Int? {
        if (states.isEmpty() && pendingSecrets.isEmpty()) return null
        if (foreground) return GroupPolicy.liveWatchedForeground.toInt()
        var delay: Long? = null
        for (st in states.values) {
            if (isEffectivelySharing(st)) {
                delay = minOf(delay ?: Long.MAX_VALUE, if (st.watchers > 0) GroupPolicy.liveWatchedBackground else GroupPolicy.uploadInterval)
            } else if (st.stopLivePending || store.ops(st.id).any { it.kind != "archive" } ||
                store.ownWindows(st.id).any { it.version > it.sentVersion }) {
                delay = minOf(delay ?: Long.MAX_VALUE, GroupPolicy.uploadInterval)
            }
        }
        return delay?.toInt()
    }

    // Lifecycle and pipeline

    /** At launch: loads the memberships and the store, then restores any membership with no local copy (§8.3). */
    suspend fun start() {
        val all = secrets.loadAll()
        val ids = all.map { it.groupId }.toSet()
        for (row in store.groups()) {
            if (!ids.contains(row.groupId)) store.deleteGroup(row.groupId)
        }
        store.deleteExpiredMadeInvites(now = clock())
        for (s in all) {
            val key = Base64URL.decode(s.groupKey, count = 32)
            val gid = Base64URL.decode(s.groupId, count = 16)
            if (key == null || gid == null || Base64URL.decode(s.pass, count = 32) == null || Base64URL.decode(s.memberId, count = 16) == null) {
                GroupLog.note("a stored membership is damaged; deleted")
                secrets.delete(s.groupId)
                continue
            }
            val row = store.group(s.groupId)
            if (row != null && row.memberId == s.memberId) {
                states[s.groupId] = GroupState(s, GroupKeys(key, gid), row)
                reload(s.groupId)
            } else {
                // No local copy, or one of an earlier membership (another member id): restored from the server.
                store.deleteGroup(s.groupId)
                pendingSecrets[s.groupId] = s
            }
        }
        emit(GroupHubEvent.Groups)
        emit(GroupHubEvent.Map)
        restorePending()
        for (st in orderedStates) {
            if (st.row.cursor == 0L) pull(st.id)
        }
    }

    /** The app came to the front: for every group the status, the changes and the queued uploads; for the shown
     *  group the live positions and the socket (§8.4). */
    suspend fun becameActive() {
        foreground = true
        restorePending()
        for (st in orderedStates) {
            refreshStatus(st.id)
            pull(st.id)
            flush(st.id, now = clock(), force = true)
        }
        shownGroupId?.let { fetchLive(it) }
        connectSocketIfWanted()
    }

    fun enteredBackground() {
        foreground = false
        closeSocket(code = 1000)
    }

    suspend fun connectivityChanged(online: Boolean) {
        if (!online) return
        for (st in states.values) {
            st.retryAt = 0
            st.retryStep = 0
        }
        reconnectAt = null
        restorePending()
        for (st in orderedStates) {
            flush(st.id, now = clock(), force = true)
        }
        connectSocketIfWanted()
    }

    fun setLocationAccess(authorised: Boolean, precise: Boolean) {
        locationAuthorised = authorised
        locationPrecise = precise
    }

    /** S5 opened: the status, the members' last live times (E11) and, for owners and admins, the pending invites.
     *  It also reads the live positions (E21) when the group isn't shown (the shown group's come with the socket or
     *  the foreground ticks), so a member who shares now reads "Sharing", not "Last seen" (§8.9). */
    suspend fun refreshDetail(groupId: String) {
        refreshStatus(groupId)
        val st = states[groupId] ?: return
        try {
            val members = api.members(groupId, st.secret.pass)
            val current = states[groupId] ?: return
            val lastLiveAt = HashMap<String, Long>()
            for (m in members) m.lastLiveAt?.let { lastLiveAt.putIfAbsent(m.memberId, it) }
            current.lastLiveAt = lastLiveAt
            emit(GroupHubEvent.Group(groupId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(e, groupId)
        }
        states[groupId]?.let { if (!it.row.shown || sockets == null) fetchLive(groupId) }
        states[groupId]?.let {
            if (it.role != GroupRole.member) {
                try {
                    invites(groupId)
                } catch (e: GroupActionError) {
                    // Shown again at the next refresh.
                }
            }
        }
    }

    /** A point of "Where I've been" (`AppModel.appendTrack`): queued for every group that shares (§8.5). */
    fun trackPoint(point: TrackPoint) {
        if (!point.time.isFinite()) return
        for (st in orderedStates) {
            if (!isEffectivelySharing(st)) continue
            val p = TrailPoint(time = floor(point.time).toLong(), lat = MicroDegrees.from(point.lat), lon = MicroDegrees.from(point.lon),
                               startsSegment = point.startsSegment || st.startSegmentNext)
            if (store.addOwnPoint(st.id, p)) {
                st.startSegmentNext = false
            }
        }
    }

    /** The newest location fix; kept in memory only, never queued (§8.5). */
    fun fix(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?, heading: Double?, time: Double) {
        latestFix = Fix(lat, lon, accuracy, course, speed, heading, time)
    }

    /** Sends what is due (§8.6): queued requests, trail chunks and live positions. In the foreground it also keeps
     *  the socket going (reconnects, pings) or, without sockets (demo mode), pulls the shown group's changes and live
     *  positions. */
    suspend fun tick(foreground: Boolean) {
        this.foreground = foreground
        val now = clock()
        for (st in orderedStates) {
            if (store.deleteExpiredPins(st.id, now) > 0) {
                reload(st.id)
                notify(st.id)
            }
        }
        if (states.values.any { st -> st.madeInvites.any { it.expiresAt <= now } }) {
            store.deleteExpiredMadeInvites(now)
            for (st in states.values) st.madeInvites = store.madeInvites(st.id)
        }
        if (foreground) {
            val shown = shownGroupId
            if (sockets == null && shown != null) {
                pull(shown)
                fetchLive(shown)
            } else {
                connectSocketIfWanted()
                val s = socket
                if (s != null && s.isOpen && now - s.lastPing >= GroupPolicy.socketPing) {
                    s.socket.send(SocketMessage.ping)
                    s.lastPing = now
                }
            }
        } else {
            closeSocket(code = 1000)
        }
        for (st in orderedStates) {
            flush(st.id, now = clock(), force = false)
        }
    }

    fun socketDidOpen(groupId: String) {
        val s = socket ?: return
        if (s.groupId != groupId) return
        val st = states[groupId] ?: return
        s.isOpen = true
        s.lastPing = clock()
        s.socket.send(SocketMessage.hello(watching = isWatching(st), cursor = st.row.cursor))
    }

    fun socketDidReceive(groupId: String, text: String) {
        val s = socket ?: return
        if (s.groupId != groupId) return
        val st = states[groupId] ?: return
        when (val message = SocketMessage.parse(text) ?: return) {
            is SocketMessage.Welcome -> {
                s.welcomed = true
                reconnectAttempt = 0
                applyStatus(groupId, message.status)
                applyLive(st, message.live)
                if (message.status.seq > st.row.cursor) background { it.pull(groupId) }
            }
            is SocketMessage.Tick -> {
                message.watchers?.let { st.watchers = it }
                message.live?.let { applyLive(st, it) }
                message.status?.let { applyStatus(groupId, it) }
                val changes = message.changes
                if (changes != null) {
                    if (changes.from > st.row.cursor) {
                        background { it.pull(groupId) }
                    } else {
                        applyPage(groupId, ChangesPage(items = changes.items, cursor = changes.cursor, more = changes.more,
                                                       serverTime = message.serverTime ?: st.row.cursorAt))
                        if (changes.more) background { it.pull(groupId) }
                    }
                }
            }
            is SocketMessage.Uploaded -> {
                val sent = socket?.pending?.remove(message.id) ?: return
                applyUploadResult(st, message.result, sent, now = clock())
            }
            is SocketMessage.Error -> {
                message.id?.let { socket?.pending?.remove(it) }
                if (message.code == "group_paused") background { it.refreshStatus(groupId) }
            }
            SocketMessage.Unknown -> {}
        }
    }

    fun socketDidClose(groupId: String, code: Int) {
        val s = socket ?: return
        if (s.groupId != groupId) return
        socket = null
        when (code) {
            4401 -> background { it.checkMembership(groupId) }
            4403 -> wipe(groupId, alert = GroupAlert.Kind.removed)
            4404 -> wipe(groupId, alert = GroupAlert.Kind.deleted)
            else -> {
                val step = GroupPolicy.socketBackoff[minOf(reconnectAttempt, GroupPolicy.socketBackoff.size - 1)]
                val jitter = Random.nextDouble(0.8, 1.2)
                reconnectAt = clock() + maxOf(1L, (step.toDouble() * jitter).rounded().toLong())
                reconnectAttempt += 1
            }
        }
    }

    // Actions

    suspend fun createGroup(name: String, displayName: String, pro: ProProof): String {
        val groupName = checked(name, GroupTextField.groupName)
        val myName = checked(displayName, GroupTextField.displayName)
        var clashes = 0
        while (true) {
            val groupIdBytes = GroupRandom.bytes(16)
            val groupKey = GroupRandom.bytes(32)
            val keys = GroupKeys(groupKey, groupIdBytes)
            val gid = keys.groupId
            val secret = GroupSecret(groupId = gid, memberId = GroupRandom.id(), pass = Base64URL.encode(GroupRandom.bytes(32)),
                                     groupKey = Base64URL.encode(groupKey))
            val body = try {
                val request = CreateGroupRequest(
                    groupId = gid, memberId = secret.memberId, pass = secret.pass, joinProof = Base64URL.encode(keys.joinProof),
                    groupProfile = Base64URL.encode(GroupCrypto.seal(GroupProfile(name = groupName).json(), keys.group, GroupAAD.group(gid))),
                    memberProfile = Base64URL.encode(GroupCrypto.seal(MemberProfile(name = myName, colour = 0).json(), keys.member,
                                                                      GroupAAD.member(gid, secret.memberId))),
                    termsVersion = acceptedTermsVersion, pro = pro)
                // Saved before the request, so a crash after the server said yes can't lose the membership (§8.3).
                secrets.save(secret)
                request
            } catch (e: Exception) {
                throw GroupActionError(GroupActionError.Kind.Server)
            }
            try {
                val status = api.create(body)
                addGroup(secret, keys, status)
                if (pro is ProProof.Google) store.updateGroup(gid) { it.proSent = pro.purchaseToken }
                reload(gid)
                notify(gid)
                pull(gid)
                return gid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GroupAPIError && e.status == 409 && e.code == "group_exists" && clashes == 0) {
                    secrets.delete(gid)
                    clashes += 1
                } else {
                    forgetSecretIfDefinite(secret, e)
                    throw actionError(e)
                }
            }
        }
    }

    suspend fun preview(invite: InviteLink): InvitePreview {
        val gid = invite.groupIdText
        if (pendingSecrets[gid] != null) restore(gid)
        states[gid]?.let { throw GroupActionError(GroupActionError.Kind.AlreadyMember(gid), "You're already in ${groupName(it)}.") }
        val keys = GroupKeys(invite.groupKey, invite.groupId)
        val p = try {
            api.preview(token = invite.token, joinProof = Base64URL.encode(keys.joinProof))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw actionError(e)
        }
        val profile = p.profile?.let(Base64URL::decode)?.let { env ->
            orNull { GroupProfile.decode(GroupCrypto.open(env, keys.group, GroupAAD.group(gid))) }
        } ?: throw GroupActionError(GroupActionError.Kind.DamagedLink)
        val inviter = p.inviter?.let { i ->
            Base64URL.decode(i.profile)?.let { env -> orNull { MemberProfile.decode(GroupCrypto.open(env, keys.member, GroupAAD.member(gid, i.memberId))) } }
                ?.let { words.masked(it.name) } ?: unknownMember
        }
        return InvitePreview(groupId = gid, name = words.masked(profile.name), inviterName = inviter, memberCount = p.memberCount,
                             isPaused = p.status == "paused", expiresAt = p.expiresAt)
    }

    suspend fun join(invite: InviteLink, displayName: String): String {
        val gid = invite.groupIdText
        states[gid]?.let { throw GroupActionError(GroupActionError.Kind.AlreadyMember(gid), "You're already in ${groupName(it)}.") }
        val myName = checked(displayName, GroupTextField.displayName)
        val keys = GroupKeys(invite.groupKey, invite.groupId)
        val colour = Random.nextInt(GroupPolicy.palette.size)
        // A join whose answer was lost is retried with the same member id and pass: the server answers it again (§6.7).
        var secret = pendingSecrets[gid]?.takeIf { it.groupKey == Base64URL.encode(invite.groupKey) }
            ?: GroupSecret(groupId = gid, memberId = GroupRandom.id(), pass = Base64URL.encode(GroupRandom.bytes(32)),
                           groupKey = Base64URL.encode(invite.groupKey))
        pendingSecrets.remove(gid)
        var clashes = 0
        while (true) {
            val body = try {
                val profile = GroupCrypto.seal(MemberProfile(name = myName, colour = colour).json(), keys.member, GroupAAD.member(gid, secret.memberId))
                val request = RedeemRequest(token = invite.token, joinProof = Base64URL.encode(keys.joinProof), memberId = secret.memberId,
                                            pass = secret.pass, profile = Base64URL.encode(profile), termsVersion = acceptedTermsVersion)
                secrets.save(secret)
                request
            } catch (e: Exception) {
                throw GroupActionError(GroupActionError.Kind.Server)
            }
            try {
                val status = api.redeem(body)
                addGroup(secret, keys, status)
                states[gid]?.needsColourCheck = true
                setShown(gid)
                notify(gid)
                pull(gid)
                return gid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GroupAPIError && e.status == 409 && e.code == "member_exists" && clashes == 0) {
                    secrets.delete(gid)
                    secret = GroupSecret(groupId = gid, memberId = GroupRandom.id(), pass = Base64URL.encode(GroupRandom.bytes(32)),
                                         groupKey = secret.groupKey)
                    clashes += 1
                } else {
                    forgetSecretIfDefinite(secret, e)
                    throw actionError(e)
                }
            }
        }
    }

    /** Chooses the group the map shows (SD1); null shows none. */
    fun setShown(groupId: String?) {
        val target = groupId?.takeIf { states.containsKey(it) }
        store.setShown(target)
        for (st in states.values) st.row.shown = st.id == target
        emit(GroupHubEvent.Groups)
        if (target != null) emit(GroupHubEvent.Group(target))
        emit(GroupHubEvent.Map)
        connectSocketIfWanted()
        if (target != null && (foreground || sockets == null)) {
            background { it.fetchLive(target) }
        }
    }

    fun recordConsent(groupId: String) {
        val st = states[groupId] ?: return
        val now = clock()
        store.updateGroup(groupId) { it.consentAt = now }
        st.row.consentAt = now
        notify(groupId)
    }

    /** Sharing on or off for a group. On: the next point starts a segment. Off: an immediate upload with `"live": null`;
     *  points already queued still go (§8.5). */
    fun setSharing(on: Boolean, groupId: String) {
        val st = states[groupId] ?: return
        if (st.row.sharing == on) return
        store.updateGroup(groupId) { it.sharing = on }
        st.row.sharing = on
        if (on) {
            st.startSegmentNext = true
            st.stopLivePending = false
        } else {
            st.stopLivePending = true
            st.lastLiveSentAt = null
            st.lastLivePosition = null
            background { hub -> hub.flush(groupId, now = hub.clock(), force = true) }
        }
        notify(groupId)
    }

    fun setTrails(mode: TrailsMode, groupId: String) {
        val st = states[groupId] ?: return
        store.updateGroup(groupId) { it.trails = mode.rawValue }
        st.row.trails = mode.rawValue
        st.trailCache = null
        notify(groupId)
    }

    suspend fun setDisplayName(name: String, colour: Int, groupId: String) {
        val cleaned = checked(name, GroupTextField.displayName)
        val st = states[groupId] ?: throw GroupActionError.gone
        val me = st.secret.memberId
        val env = try {
            GroupCrypto.seal(MemberProfile(name = cleaned, colour = colour.coerceIn(0, GroupPolicy.palette.size - 1)).json(),
                             st.keys.member, GroupAAD.member(groupId, me))
        } catch (e: GroupCrypto.Failure) {
            throw GroupActionError(GroupActionError.Kind.Server)
        }
        try {
            val seq = api.updateMyProfile(groupId, st.secret.pass, Base64URL.encode(env)).seq
            for (op in store.ops(groupId)) if (op.kind == "profile") store.deleteOp(op.id)
            states[groupId]?.members?.firstOrNull { it.memberId == me }?.copy()?.let { m ->
                m.profile = env
                m.seq = seq
                store.putMember(groupId, m)
            }
            reload(groupId)
            notify(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    suspend fun renameGroup(name: String, groupId: String) {
        val cleaned = checked(name, GroupTextField.groupName)
        val st = states[groupId] ?: throw GroupActionError.gone
        val env = try {
            GroupCrypto.seal(GroupProfile(name = cleaned).json(), st.keys.group, GroupAAD.group(groupId))
        } catch (e: GroupCrypto.Failure) {
            throw GroupActionError(GroupActionError.Kind.Server)
        }
        try {
            val seq = api.renameGroup(groupId, st.secret.pass, Base64URL.encode(env)).seq
            store.updateGroup(groupId) { g ->
                if (seq >= g.profileSeq) {
                    g.profile = env
                    g.profileSeq = seq
                }
            }
            reload(groupId)
            notify(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    suspend fun createInvite(groupId: String): MadeInvite {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            val made = api.createInvite(groupId, st.secret.pass)
            val groupKey = Base64URL.decode(st.secret.groupKey, count = 32)
            val link = groupKey?.let { InviteLink(token = made.token, groupKey = it) }
            if (link == null || link.groupIdText != groupId) throw GroupActionError(GroupActionError.Kind.Server)
            val url = link.url(domain)
            store.putMadeInvite(groupId, GroupStore.MadeInvite(inviteId = made.inviteId, link = url, expiresAt = made.expiresAt))
            states[groupId]?.let { current ->
                current.madeInvites = store.madeInvites(groupId)
                current.invites = listOf(InviteDTO(inviteId = made.inviteId, createdBy = current.secret.memberId, createdAt = made.createdAt,
                                                   expiresAt = made.expiresAt)) + (current.invites ?: emptyList())
                notify(groupId)
            }
            return MadeInvite(inviteId = made.inviteId, link = url, expiresAt = made.expiresAt, groupName = groupName(st))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    suspend fun invites(groupId: String): List<PendingInvite> {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            val list = api.invites(groupId, st.secret.pass)
            val current = states[groupId] ?: throw GroupActionError.gone
            current.invites = list
            val ids = list.map { it.inviteId }.toSet()
            for (made in current.madeInvites) {
                if (!ids.contains(made.inviteId)) store.deleteMadeInvite(groupId, made.inviteId)
            }
            current.madeInvites = store.madeInvites(groupId)
            notify(groupId)
            return detail(groupId)?.invites ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    suspend fun cancelInvite(inviteId: String, groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            api.cancelInvite(groupId, st.secret.pass, inviteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already gone: done.
            if (!(e is GroupAPIError && e.status == 404 && e.code == "not_found")) throw failure(e, groupId)
        }
        store.deleteMadeInvite(groupId, inviteId)
        states[groupId]?.let { current ->
            current.invites = current.invites?.filter { it.inviteId != inviteId }
            current.madeInvites = store.madeInvites(groupId)
            notify(groupId)
        }
    }

    suspend fun setRole(role: GroupRole, memberId: String, groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            val seq = api.setRole(groupId, st.secret.pass, memberId, role.rawValue).seq
            states[groupId]?.members?.firstOrNull { it.memberId == memberId }?.copy()?.let { m ->
                m.role = role.rawValue
                m.seq = seq
                store.putMember(groupId, m)
            }
            reload(groupId)
            notify(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    suspend fun removeMember(memberId: String, groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            api.removeMember(groupId, st.secret.pass, memberId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already gone: done.
            if (!(e is GroupAPIError && e.status == 404 && e.code == "not_found")) throw failure(e, groupId)
        }
        store.deleteMember(groupId, memberId)
        reload(groupId)
        notify(groupId)
    }

    suspend fun offerOwnership(to: String, groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            val status = api.offerOwnership(groupId, st.secret.pass, to)
            applyStatus(groupId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    /** The owner cancels the offer, or its target declines it. */
    suspend fun cancelOwnershipOffer(groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            api.cancelOwnershipOffer(groupId, st.secret.pass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No offer any more: done.
            if (!(e is GroupAPIError && e.status == 404 && e.code == "not_found")) throw failure(e, groupId)
        }
        refreshStatus(groupId)
    }

    suspend fun acceptOwnership(groupId: String, pro: ProProof) {
        val st = states[groupId] ?: throw GroupActionError.gone
        try {
            val status = api.acceptOwnership(groupId, st.secret.pass, pro)
            if (pro is ProProof.Google) store.updateGroup(groupId) { it.proSent = pro.purchaseToken }
            applyStatus(groupId, status)
            pull(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    /** Sends the Pro proof (E5) for each group you own where §10.7 says so: no Pro on record, a Pro pause, another
     *  product, a later known expiry, or (Google) a new purchase token. A `402` changes nothing on the phone.
     *  `expiresAt` is always null on Android (Play Billing gives no expiry). */
    suspend fun updatePro(pro: ProProof, productId: String, expiresAt: Long?) {
        for (st in orderedStates) {
            if (st.role != GroupRole.owner) continue
            val status = st.status ?: continue
            if (!shouldSendPro(status, st.row, pro, productId, expiresAt)) continue
            try {
                val answer = api.updatePro(st.id, st.secret.pass, pro)
                if (pro is ProProof.Google) store.updateGroup(st.id) { it.proSent = pro.purchaseToken }
                applyStatus(st.id, answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GroupAPIError && e.status == 402) {
                    GroupLog.note("pro proof refused (${e.code})")
                } else {
                    handleFailure(e, st.id)
                }
            }
        }
    }

    /** Drops a pin: shown at once, sent when online (queued in `ops`). Throws `GroupActionError`. */
    fun dropPin(groupId: String, type: PinType, animal: String?, note: String?, lat: Double, lon: Double) {
        val st = states[groupId] ?: throw GroupActionError.gone
        if (st.isPaused) throw GroupActionError(GroupActionError.Kind.GroupPaused)
        var animalName: String? = null
        if (type == PinType.animal) {
            animalName = checked(animal ?: "", GroupTextField.animal)
        }
        val cleanedNote = checked(note ?: "", GroupTextField.note)
        val record = PinRecord(type = type.rawValue, animal = animalName, note = cleanedNote.ifEmpty { null },
                               lat = MicroDegrees.from(lat), lon = MicroDegrees.from(lon))
        val pinId = GroupRandom.id()
        val createdAt = clock()
        val expiresAt = if (type == PinType.animal) createdAt + GroupPolicy.animalPinLifetime else null
        val me = st.secret.memberId
        val env = try {
            GroupCrypto.seal(record.json(), st.keys.pin, GroupAAD.pin(groupId, pinId = pinId, memberId = me, createdAt = createdAt, expiresAt = expiresAt))
        } catch (e: GroupCrypto.Failure) {
            throw GroupActionError(GroupActionError.Kind.Server)
        }
        val op = PinOp(pinId = pinId, createdAt = createdAt, expiresAt = expiresAt, plain = Base64URL.encode(record.json()), retried = false)
        try {
            store.transaction {
                store.putPin(groupId, GroupStore.StoredPin(pinId = pinId, memberId = me, createdAt = createdAt, expiresAt = expiresAt, rec = env, seq = 0))
                store.addOp(groupId, kind = "pin", body = op.json(), createdAt = createdAt)
            }
        } catch (e: GroupStore.StoreError) {
            GroupLog.note("couldn't queue a pin (${e.errorDescription})")
        }
        reload(groupId)
        notify(groupId)
        background { hub -> hub.flush(groupId, now = hub.clock(), force = true) }
    }

    /** Deletes a pin for everyone: gone from the map at once; the request is queued. */
    fun deletePin(pinId: String, groupId: String) {
        if (!states.containsKey(groupId)) return
        val unsent = store.ops(groupId).firstOrNull { it.kind == "pin" && PinOp.decode(it.body)?.pinId == pinId }
        try {
            store.transaction {
                store.deletePin(groupId, pinId)
                if (unsent != null) {
                    store.deleteOp(unsent.id)
                } else {
                    store.addOp(groupId, kind = "unpin", body = pinId, createdAt = clock())
                }
            }
        } catch (e: GroupStore.StoreError) {
            GroupLog.note("couldn't queue a pin deletion (${e.errorDescription})")
        }
        reload(groupId)
        notify(groupId)
        background { hub -> hub.flush(groupId, now = hub.clock(), force = true) }
    }

    /** Reports a pin, a member or the group name with the reporter's decrypted copy, never a position (SD10, SD28). */
    suspend fun report(groupId: String, target: ReportTarget, reason: ReportReason, comment: String) {
        val cleaned = checked(comment, GroupTextField.reportComment)
        val st = states[groupId] ?: throw GroupActionError.gone
        val content = LinkedHashMap<String, String>()
        val kind: String
        val targetId: String
        when (target) {
            is ReportTarget.Pin -> {
                kind = "pin"
                targetId = target.pinId
                val p = st.pins.firstOrNull { it.pinId == target.pinId }
                if (p != null) {
                    pinRecord(st, p)?.let { rec ->
                        val cps = rec.type.codePoints().limit(20).toArray()
                        content["type"] = String(cps, 0, cps.size)
                        rec.animal?.let { content["animal"] = it }
                        rec.note?.let { content["note"] = it }
                    }
                    realName(st, p.memberId)?.let { content["name"] = it }
                }
            }
            is ReportTarget.Member -> {
                kind = "member"
                targetId = target.memberId
                realName(st, target.memberId)?.let { content["name"] = it }
            }
            ReportTarget.Group -> {
                kind = "group"
                targetId = groupId
                groupProfile(st)?.name?.let { content["name"] = it }
            }
        }
        try {
            api.report(groupId, st.secret.pass, ReportRequest(targetKind = kind, targetId = targetId, reason = reason.rawValue, comment = cleaned,
                                                              content = content))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, groupId)
        }
    }

    /** Hides a member on this phone only (§8.11). You can't block yourself. */
    fun block(memberId: String, groupId: String) {
        val st = states[groupId] ?: return
        if (memberId == st.secret.memberId) return
        store.setBlocked(groupId, memberId, true)
        st.blocked = st.blocked + memberId
        st.trailCache = null
        notify(groupId)
    }

    fun unblock(memberId: String, groupId: String) {
        val st = states[groupId] ?: return
        store.setBlocked(groupId, memberId, false)
        st.blocked = st.blocked - memberId
        st.trailCache = null
        notify(groupId)
    }

    /** Deletes your queued points and stored chunks at once and queues E22; no chunks go up until it has gone (§8.10). */
    fun clearMyTrail(groupId: String) {
        val st = states[groupId] ?: return
        try {
            store.transaction {
                store.deleteOwnPoints(groupId)
                store.deleteChunks(groupId, memberId = st.secret.memberId)
                if (!store.hasOp(groupId, kind = "cleartrail")) {
                    store.addOp(groupId, kind = "cleartrail", body = "", createdAt = clock())
                }
            }
        } catch (e: GroupStore.StoreError) {
            GroupLog.note("couldn't queue clearing the trail (${e.errorDescription})")
        }
        st.badTimeHold.clear()
        st.chunkRevision += 1
        notify(groupId)
        background { hub -> hub.flush(groupId, now = hub.clock(), force = true) }
    }

    /** Leaves the group (needs a connection); then everything of it goes from the phone. The owner can't leave (SD12). */
    suspend fun leave(groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        if (st.role == GroupRole.owner) throw GroupActionError(GroupActionError.Kind.Forbidden)
        try {
            api.removeMember(groupId, st.secret.pass, memberId = "me")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No longer a member anyway.
            if (!(e is GroupAPIError && e.status in listOf(401, 404, 410))) throw actionError(e)
        }
        wipe(groupId, alert = null)
    }

    suspend fun deleteGroup(groupId: String) {
        val st = states[groupId] ?: throw GroupActionError.gone
        if (st.role != GroupRole.owner) throw GroupActionError(GroupActionError.Kind.Forbidden)
        try {
            api.deleteGroup(groupId, st.secret.pass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already deleted.
            val already = e is GroupAPIError && ((e.status == 404 && e.code == "group_not_found") || (e.status == 410 && e.code == "group_deleted"))
            if (!already) throw failure(e, groupId)
        }
        wipe(groupId, alert = null)
    }

    /** Waits for the work the plain calls started (tests). */
    suspend fun waitForBackgroundWork() {
        while (backgroundWork.isNotEmpty()) {
            val work = backgroundWork.toList()
            backgroundWork.clear()
            work.joinAll()
        }
    }

    // Internals: state

    private class Fix(val lat: Double, val lon: Double, val accuracy: Double?, val course: Double?, val speed: Double?, val heading: Double?, val time: Double)

    /** What a send carried: the windows and versions. */
    private class SentUpload(val windows: MutableList<Pair<Long, Long>> = mutableListOf())

    private class SocketState(val groupId: String, val socket: GroupSocket) {
        var isOpen = false
        var welcomed = false
        var lastPing = 0L
        var nextId = 1
        val pending = HashMap<Int, SentUpload>()
    }

    /** A queued pin (`ops` kind "pin"): its plain record is kept so it can be sealed again with a new id after a
     *  `pin_exists` clash. */
    private data class PinOp(val pinId: String, val createdAt: Long, val expiresAt: Long?, val plain: String, val retried: Boolean) {
        fun json(): String = buildJsonObject {
            put("pinId", pinId)
            put("createdAt", createdAt)
            expiresAt?.let { put("expiresAt", it) }
            put("plain", plain)
            put("retried", retried)
        }.toString()

        companion object {
            fun decode(json: String): PinOp? = try {
                val f = GroupJSON.Fields(GroupJSON.parseObject(json.toByteArray(Charsets.UTF_8)))
                PinOp(f.string("pinId"), f.long("createdAt"), f.optLong("expiresAt"), f.string("plain"), f.bool("retried"))
            } catch (e: SerializationException) {
                null
            }
        }
    }

    private class Live(val at: Long, val position: LivePosition)

    private class GroupState(val secret: GroupSecret, val keys: GroupKeys, var row: GroupStore.StoredGroup) {
        var status: GroupStatusDTO? = row.statusJSON?.let { GroupStatusDTO.decode(it) }
        var members: List<GroupStore.StoredMember> = emptyList()
        var pins: List<GroupStore.StoredPin> = emptyList()
        var live = HashMap<String, Live>()
        var blocked: Set<String> = emptySet()
        var madeInvites: List<GroupStore.MadeInvite> = emptyList()
        var invites: List<InviteDTO>? = null
        var lastLiveAt: Map<String, Long> = emptyMap()

        val profileCache = HashMap<String, Pair<ByteArray, MemberProfile?>>()
        val pinCache = HashMap<String, Pair<ByteArray, PinRecord?>>()
        val chunkCache = HashMap<String, Triple<Long, ByteArray, List<TrailPoint>?>>()
        var chunkRevision = 0
        var trailCache: Pair<String, List<MapTrail>>? = null

        /** When the last chunk upload and the last live slot were (null: never), for the pace (§8.6). */
        var lastChunkUpload: Long? = null
        var lastLiveSlot: Long? = null
        var lastLiveSentAt: Long? = null
        var lastLivePosition: GeoPoint? = null
        var watchers = 0
        var startSegmentNext = true
        val badTimeHold = HashSet<Long>()
        var retryAt = 0L
        var retryStep = 0
        var stopLivePending = false
        var needsColourCheck = false
        var pulling = false
        var pullAgain = false
        var flushing = false

        val id: String get() = secret.groupId
        val role: GroupRole get() = GroupRole(status?.you?.role ?: row.role) ?: GroupRole.member
        val isPaused: Boolean get() = status?.isPaused ?: false

        /** The sharing switch as shown: on, with consent. */
        val sharingOn: Boolean get() = row.sharing && row.consentAt != null
    }

    private val orderedStates: List<GroupState>
        get() = states.values.sortedWith(compareBy<GroupState>({ it.row.joinedAt }, { it.id }))

    /** Whether the group shares now (§8.5): switched on, consent recorded, active, location authorised and precise. */
    private fun isEffectivelySharing(st: GroupState): Boolean = st.sharingOn && !st.isPaused && locationAuthorised && locationPrecise

    private fun isWatching(st: GroupState): Boolean = foreground && st.row.shown && !st.isPaused

    /** Reloads a group's rows from the store into memory. */
    private fun reload(groupId: String) {
        val st = states[groupId] ?: return
        val row = store.group(groupId) ?: return
        st.row = row
        row.statusJSON?.let { GroupStatusDTO.decode(it) }?.let { st.status = it }
        st.members = store.members(groupId)
        st.pins = store.pins(groupId)
        st.blocked = store.blocked(groupId)
        st.madeInvites = store.madeInvites(groupId)
        val live = HashMap<String, Live>()
        for (l in store.live(groupId)) {
            openLive(st, l.memberId, l.at, l.rec)?.let { live[l.memberId] = Live(l.at, it) }
        }
        st.live = live
    }

    private fun addGroup(secret: GroupSecret, keys: GroupKeys, status: GroupStatusDTO) {
        val row = GroupStore.StoredGroup(groupId = secret.groupId, memberId = secret.memberId, role = status.you.role, joinedAt = status.you.joinedAt,
                                         statusJSON = status.json(), profile = status.profile?.let(Base64URL::decode),
                                         profileSeq = status.profileSeq ?: 0)
        val existing = store.group(secret.groupId)
        if (existing != null) {
            existing.role = row.role
            existing.joinedAt = row.joinedAt
            existing.statusJSON = row.statusJSON
            store.putGroup(existing)
        } else {
            store.putGroup(row)
        }
        pendingSecrets.remove(secret.groupId)
        val st = GroupState(secret, keys, store.group(secret.groupId) ?: row)
        st.status = status
        st.watchers = status.watchers ?: 0
        states[secret.groupId] = st
        reload(secret.groupId)
    }

    private fun emit(event: GroupHubEvent) {
        onChange?.invoke(event)
    }

    /** Tells the app a group changed (and the map, if it is the shown group). */
    private fun notify(groupId: String) {
        emit(GroupHubEvent.Groups)
        emit(GroupHubEvent.Group(groupId))
        if (states[groupId]?.row?.shown == true) emit(GroupHubEvent.Map)
    }

    /** Starts work in `scope` (queued behind what already waits there, as a Swift `Task` on the main actor is). The
     *  work handles its own failures; anything else is logged rather than let through to the scope's handler, which
     *  would end the app. */
    private fun background(work: suspend (GroupHub) -> Unit) {
        backgroundWork.removeAll { it.isCompleted }
        backgroundWork.add(scope.launch {
            try {
                work(this@GroupHub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GroupLog.note("background work failed (${e.javaClass.simpleName})")
            }
        })
    }

    // Internals: names and decoding

    private fun memberProfile(st: GroupState, m: GroupStore.StoredMember): MemberProfile? {
        st.profileCache[m.memberId]?.let { (data, profile) -> if (data.contentEquals(m.profile)) return profile }
        var profile: MemberProfile? = null
        try {
            profile = MemberProfile.decode(GroupCrypto.open(m.profile, st.keys.member, GroupAAD.member(st.id, m.memberId)))
        } catch (e: Exception) {
            GroupLog.note("a member profile doesn't open (${e.javaClass.simpleName}); shown as unknown")
        }
        st.profileCache[m.memberId] = m.profile to profile
        return profile
    }

    private fun groupProfile(st: GroupState): GroupProfile? {
        val env = st.row.profile ?: return null
        return orNull { GroupProfile.decode(GroupCrypto.open(env, st.keys.group, GroupAAD.group(st.id))) }
    }

    private fun groupName(st: GroupState): String = groupProfile(st)?.let { words.masked(it.name) } ?: unnamedGroup

    private fun memberCount(st: GroupState): Int = st.status?.memberCount ?: st.members.size

    /** The name shown for a member: masked, "Unknown member" if the profile doesn't open, "Blocked member" if blocked. */
    private fun name(st: GroupState, memberId: String, realName: Boolean): String {
        if (!realName && st.blocked.contains(memberId) && memberId != st.secret.memberId) return blockedMember
        val m = st.members.firstOrNull { it.memberId == memberId } ?: return unknownMember
        val p = memberProfile(st, m) ?: return unknownMember
        return words.masked(p.name)
    }

    /** The decrypted name as typed (reports carry the reporter's copy unmasked). */
    private fun realName(st: GroupState, memberId: String): String? {
        val m = st.members.firstOrNull { it.memberId == memberId } ?: return null
        return memberProfile(st, m)?.name
    }

    private fun colourHex(profile: MemberProfile?): String {
        val c = profile?.colour ?: return GroupPolicy.grey
        return GroupPolicy.palette.getOrNull(c) ?: GroupPolicy.grey
    }

    private fun memberRow(st: GroupState, m: GroupStore.StoredMember, now: Long, realName: Boolean): MemberRow {
        val blocked = st.blocked.contains(m.memberId) && m.memberId != st.secret.memberId
        val profile = memberProfile(st, m)
        val shown = name(st, m.memberId, realName)
        return MemberRow(memberId = m.memberId, name = shown, initials = GroupText.initials(shown),
                         colourHex = if (blocked && !realName) GroupPolicy.grey else colourHex(profile),
                         role = GroupRole(m.role) ?: GroupRole.member, isYou = m.memberId == st.secret.memberId, isBlocked = blocked,
                         state = memberState(st, m.memberId, now))
    }

    /** §8.9: the newest live record if it is at most 10 minutes old is sharing, older is last seen; without one, the
     *  last E11 `lastLiveAt`; otherwise not sharing. */
    private fun memberState(st: GroupState, memberId: String, now: Long): MemberState {
        val l = st.live[memberId]
        if (l != null && now - l.at <= GroupPolicy.liveHiddenAfter) {
            return if (now - l.at <= GroupPolicy.staleAfter) MemberState.Sharing(at = l.at) else MemberState.LastSeen(at = l.at)
        }
        st.lastLiveAt[memberId]?.let { return MemberState.LastSeen(at = it) }
        return MemberState.NotSharing
    }

    private fun stateText(state: MemberState, now: Long): String = when (state) {
        is MemberState.Sharing -> "Sharing · ${GroupFormat.ago(now - state.at)}"
        is MemberState.LastSeen -> "Last seen ${GroupFormat.ago(now - state.at)}"
        MemberState.NotSharing -> "Not sharing"
    }

    private fun coordinate(lat: Int, lon: Int): GroupCoordinate = GroupCoordinate(lat = MicroDegrees.degrees(lat), lon = MicroDegrees.degrees(lon))

    /** A pin's record, if it opens and its expiry follows the rule (§4.5). */
    private fun pinRecord(st: GroupState, p: GroupStore.StoredPin): PinRecord? {
        st.pinCache[p.pinId]?.let { (data, record) -> if (data.contentEquals(p.rec)) return record }
        var record: PinRecord? = null
        try {
            val plain = GroupCrypto.open(p.rec, st.keys.pin,
                                         GroupAAD.pin(st.id, pinId = p.pinId, memberId = p.memberId, createdAt = p.createdAt, expiresAt = p.expiresAt))
            val r = PinRecord.decode(plain)
            val expected = if (r.type == PinType.animal.rawValue) p.createdAt + GroupPolicy.animalPinLifetime else null
            if (p.expiresAt == expected) {
                record = r
            } else {
                GroupLog.note("a pin's expiry breaks the rule; dropped")
            }
        } catch (e: Exception) {
            GroupLog.note("a pin doesn't open (${e.javaClass.simpleName}); dropped")
        }
        st.pinCache[p.pinId] = p.rec to record
        return record
    }

    private fun openLive(st: GroupState, memberId: String, at: Long, rec: ByteArray): LivePosition? = try {
        val p = LivePosition.decode(GroupCrypto.open(rec, st.keys.live(memberId), GroupAAD.live(st.id, memberId, at = at)))
        if (p.time == at) {
            p
        } else {
            GroupLog.note("a live record's time differs from its at; dropped")
            null
        }
    } catch (e: Exception) {
        GroupLog.note("a live record doesn't open (${e.javaClass.simpleName}); dropped")
        null
    }

    /** A chunk's points (§8.9): other members' points before your `joinedAt` are cut. */
    private fun chunkPoints(st: GroupState, c: GroupStore.StoredChunk): List<TrailPoint>? {
        val key = "${c.memberId}|${c.start}"
        st.chunkCache[key]?.let { (seq, data, points) -> if (seq == c.seq && data.contentEquals(c.rec)) return points }
        var points: List<TrailPoint>? = null
        try {
            val plain = GroupCrypto.open(c.rec, st.keys.trail(c.memberId), GroupAAD.trail(st.id, c.memberId, start = c.start))
            points = visible(TrailChunk.decode(plain, windowStart = c.start), memberId = c.memberId, me = st.secret.memberId, joinedAt = st.row.joinedAt)
        } catch (e: Exception) {
            GroupLog.note("a trail chunk doesn't open (${e.javaClass.simpleName}); dropped")
        }
        st.chunkCache[key] = Triple(c.seq, c.rec, points)
        return points
    }

    /** The other members' trails for the map (§9.5): per the Trails setting, split into segments, simplified by the
     *  number of visible trails, at most the newest 4000 points per member. */
    private fun trails(st: GroupState, now: Long): List<MapTrail> {
        val mode = TrailsMode(st.row.trails) ?: TrailsMode.day
        if (mode == TrailsMode.off) return emptyList()
        val since: Long? = if (mode == TrailsMode.day) now - GroupPolicy.dayTrail else null
        val cacheKey = "${st.chunkRevision}|${mode.rawValue}|${st.blocked.sorted().joinToString(",")}|" +
            "${st.members.joinToString(",") { "${it.memberId}:${it.seq}" }}|${since?.let { it / 60 } ?: 0}"
        st.trailCache?.let { (key, trails) -> if (key == cacheKey) return trails }
        val me = st.secret.memberId
        val current = st.members.map { it.memberId }.toSet()
        val byMember = HashMap<String, MutableList<TrailPoint>>()
        val windowFrom = since?.let { TrailChunk.windowStart(it) }
        for (c in store.chunks(st.id, since = windowFrom)) {
            if (c.memberId == me || !current.contains(c.memberId) || st.blocked.contains(c.memberId)) continue
            val points = chunkPoints(st, c) ?: continue
            byMember.getOrPut(c.memberId) { mutableListOf() }.addAll(if (since != null) points.filter { it.time >= since } else points)
        }
        val segmentsByMember = mutableListOf<Pair<String, List<List<TrailPoint>>>>()
        for (m in st.members) {
            val points = byMember[m.memberId]?.sortedBy { it.time } ?: continue
            var segments = mutableListOf<List<TrailPoint>>()
            var currentSegment = mutableListOf<TrailPoint>()
            for (p in points) {
                val last = currentSegment.lastOrNull()
                if (last != null && (p.startsSegment || p.time - last.time > GroupPolicy.windowSeconds)) {
                    segments.add(currentSegment)
                    currentSegment = mutableListOf()
                }
                currentSegment.add(p)
            }
            segments.add(currentSegment)
            segments = segments.filter { it.size >= 2 }.toMutableList()
            if (segments.isNotEmpty()) segmentsByMember.add(m.memberId to segments)
        }
        val visibleCount = segmentsByMember.size
        val tolerance = if (visibleCount <= 10) 3.0 else if (visibleCount <= 30) 10.0 else 25.0
        val width = if (visibleCount > 10) 2.0 else 3.0
        val out = mutableListOf<MapTrail>()
        for ((memberId, segments) in segmentsByMember) {
            val simplified = segments.map { simplify(it, tolerance) }.toMutableList()
            // At most the newest 4000 points per member.
            var total = simplified.sumOf { it.size }
            while (total > 4000 && simplified.isNotEmpty()) {
                val excess = total - 4000
                if (simplified[0].size - excess >= 2) {
                    simplified[0] = simplified[0].drop(excess)
                    total -= excess
                } else {
                    total -= simplified[0].size
                    simplified.removeAt(0)
                }
            }
            val profile = st.members.firstOrNull { it.memberId == memberId }?.let { memberProfile(st, it) }
            out.add(MapTrail(memberId = memberId, colourHex = colourHex(profile), widthPt = width,
                             segments = simplified.map { segment -> segment.map { GroupCoordinate(lat = it.lat, lon = it.lon) } }))
        }
        st.trailCache = cacheKey to out
        return out
    }

    // Internals: text

    private fun checked(text: String, field: GroupTextField): String {
        val cleaned = GroupText.clean(text, field) ?: throw GroupActionError(GroupActionError.Kind.InvalidText(field))
        if (!words.allows(cleaned)) throw GroupActionError(GroupActionError.Kind.WordFilter(field))
        return cleaned
    }

    // Internals: failures

    /** The error to throw for a failed request, after running §8.10 when it says the membership ended. */
    private suspend fun failure(error: Exception, groupId: String): GroupActionError {
        val mapped = actionError(error)
        val gone = handleFailure(error, groupId)
        return if (gone) GroupActionError.gone else mapped
    }

    private fun actionError(error: Exception): GroupActionError {
        if (error is GroupActionError) return error
        if (error is GroupTransportError) {
            return GroupActionError(if (error is GroupTransportError.Offline) GroupActionError.Kind.Offline else GroupActionError.Kind.Server)
        }
        if (error !is GroupAPIError) return GroupActionError(GroupActionError.Kind.Server)
        val s = error.status
        val c = error.code
        return when {
            s == 429 -> GroupActionError(GroupActionError.Kind.RateLimited)
            s == 503 && c == "store_unavailable" -> GroupActionError(GroupActionError.Kind.StoreUnavailable)
            s == 402 -> GroupActionError(GroupActionError.Kind.ProNotConfirmed)
            s == 409 && c == "too_many_groups" -> GroupActionError(GroupActionError.Kind.TooManyGroups)
            s == 403 -> GroupActionError(GroupActionError.Kind.Forbidden)
            s == 404 && c == "invite_not_found" -> GroupActionError(GroupActionError.Kind.InviteNotFound)
            s == 409 && c == "terms_outdated" -> GroupActionError(GroupActionError.Kind.TermsOutdated)
            s == 409 && c == "group_paused" -> GroupActionError(GroupActionError.Kind.GroupPaused)
            s == 401 || (s == 404 && c == "group_not_found") || (s == 410 && c == "removed") || (s == 410 && c == "group_deleted") -> GroupActionError.gone
            s == 404 -> GroupActionError(GroupActionError.Kind.NotFound)
            else -> GroupActionError(GroupActionError.Kind.Server)
        }
    }

    /** Runs §8.10 for an answer that says the membership may have ended: `410 removed` or `group_deleted` at once; a
     *  `401` or `404 group_not_found` only when E2 says the same (a check is made unless this was E2). Returns whether
     *  the group is gone from the phone. */
    private suspend fun handleFailure(error: Exception, groupId: String, fromStatus: Boolean = false): Boolean {
        if (error !is GroupAPIError) return false
        val s = error.status
        val c = error.code
        return when {
            s == 410 && c == "removed" -> {
                wipe(groupId, alert = GroupAlert.Kind.removed)
                true
            }
            s == 410 && c == "group_deleted" -> {
                wipe(groupId, alert = GroupAlert.Kind.deleted)
                true
            }
            s == 401 || (s == 404 && c == "group_not_found") -> {
                if (fromStatus) {
                    wipe(groupId, alert = GroupAlert.Kind.gone)
                    true
                } else {
                    checkMembership(groupId)
                    states[groupId] == null
                }
            }
            s == 409 && c == "group_paused" -> {
                if (!fromStatus) refreshStatus(groupId)
                false
            }
            else -> false
        }
    }

    /** E2 for a group whose membership another endpoint put in doubt. */
    private suspend fun checkMembership(groupId: String) {
        refreshStatus(groupId)
    }

    private fun forgetSecretIfDefinite(secret: GroupSecret, error: Exception) {
        if (error is GroupAPIError && error.status in 400 until 500 && error.status != 429) {
            secrets.delete(secret.groupId)
            pendingSecrets.remove(secret.groupId)
        } else if (states[secret.groupId] == null) {
            // The answer may have been lost: E2 decides at the next start or activation (§8.3).
            pendingSecrets[secret.groupId] = secret
        }
    }

    /** Deletes a group from the phone: rows, secret, socket; with an alert when the server ended the membership. */
    private fun wipe(groupId: String, alert: GroupAlert.Kind?) {
        val name = states[groupId]?.let { groupName(it) } ?: unnamedGroup
        val wasShown = states[groupId]?.row?.shown ?: false
        if (socket?.groupId == groupId) closeSocket(code = 1000)
        store.deleteGroup(groupId)
        secrets.delete(groupId)
        states.remove(groupId)
        pendingSecrets.remove(groupId)
        emit(GroupHubEvent.Groups)
        if (wasShown) emit(GroupHubEvent.Map)
        if (alert != null) emit(GroupHubEvent.Alert(GroupAlert(kind = alert, groupId = groupId, groupName = name)))
    }

    // Internals: status, changes, live

    private suspend fun restorePending() {
        for (gid in pendingSecrets.keys.sorted()) {
            restore(gid)
        }
    }

    /** A membership with no local copy: E2, then the changes from 0 (§8.3). Only E2's 401, 404 or 410 deletes it. */
    private suspend fun restore(groupId: String) {
        val s = pendingSecrets[groupId] ?: return
        val key = Base64URL.decode(s.groupKey, count = 32) ?: return
        val gidBytes = Base64URL.decode(s.groupId, count = 16) ?: return
        try {
            val status = api.status(groupId, s.pass)
            if (pendingSecrets[groupId] == null || states[groupId] != null) return
            addGroup(s, GroupKeys(key, gidBytes), status)
            notify(groupId)
            pull(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is GroupAPIError && (e.status == 401 || e.status == 410 || (e.status == 404 && e.code == "group_not_found"))) {
                secrets.delete(groupId)
                pendingSecrets.remove(groupId)
            }
            // Otherwise offline or a server error: try again later.
        }
    }

    private suspend fun refreshStatus(groupId: String) {
        val st = states[groupId] ?: return
        try {
            val status = api.status(groupId, st.secret.pass)
            applyStatus(groupId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(e, groupId, fromStatus = true)
        }
    }

    private fun applyStatus(groupId: String, status: GroupStatusDTO) {
        val st = states[groupId] ?: return
        val wasPaused = st.isPaused
        st.status = status
        status.watchers?.let { st.watchers = it }
        store.updateGroup(groupId) { g ->
            g.statusJSON = status.json()
            g.role = status.you.role
            g.joinedAt = status.you.joinedAt
            val p = status.profile
            val seq = status.profileSeq
            val data = p?.let(Base64URL::decode)
            if (seq != null && data != null && seq >= g.profileSeq) {
                g.profile = data
                g.profileSeq = seq
            }
        }
        st.badTimeHold.clear()
        if (status.isPaused && !wasPaused) {
            // The server deleted every live position; nobody shares while paused.
            store.deleteAllLive(groupId)
            st.lastLiveSentAt = null
        } else if (!status.isPaused && wasPaused) {
            st.startSegmentNext = true
        }
        val s = socket
        if (status.isPaused != wasPaused && s != null && s.groupId == groupId && s.isOpen) {
            s.socket.send(SocketMessage.watching(isWatching(st)))
        }
        reload(groupId)
        notify(groupId)
    }

    private fun applyLive(st: GroupState, entries: List<LiveEntry>) {
        val now = clock()
        var changed = false
        for (e in entries) {
            val rec = e.rec
            if (rec == null) {
                if (st.live[e.memberId] != null) {
                    store.deleteLive(st.id, e.memberId)
                    st.live.remove(e.memberId)
                    changed = true
                }
                continue
            }
            if (now - e.at > GroupPolicy.liveHiddenAfter || (st.live[e.memberId]?.at ?: Long.MIN_VALUE) >= e.at) continue
            val env = Base64URL.decode(rec) ?: continue
            val p = openLive(st, e.memberId, e.at, env) ?: continue
            if (store.putLive(st.id, GroupStore.StoredLive(memberId = e.memberId, at = e.at, rec = env))) {
                st.live[e.memberId] = Live(e.at, p)
                changed = true
            }
        }
        if (changed) notify(st.id)
    }

    private suspend fun fetchLive(groupId: String) {
        val st = states[groupId] ?: return
        try {
            val page = api.live(groupId, st.secret.pass)
            val current = states[groupId] ?: return
            page.watchers?.let { current.watchers = it }
            applyLive(current, page.live)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(e, groupId)
        }
    }

    /** Applies one page (or tick) and reloads what it changed. */
    private fun applyPage(groupId: String, page: ChangesPage): GroupStore.Applied? {
        val st = states[groupId] ?: return null
        val applied = try {
            store.apply(page, groupId)
        } catch (e: GroupStore.StoreError) {
            GroupLog.note("couldn't store a page of changes (${e.errorDescription})")
            return null
        }
        if (applied.changed) {
            st.chunkRevision += 1
            reload(groupId)
            notify(groupId)
        } else {
            st.row = store.group(groupId) ?: st.row
        }
        if (applied.removedMe) {
            background { it.checkMembership(groupId) }
        }
        return applied
    }

    /** Pulls E19 from the cursor until `more` is false (§8.8), then fetches queued archive months. */
    private suspend fun pull(groupId: String) {
        val st = states[groupId] ?: return
        if (st.pulling) {
            st.pullAgain = true
            return
        }
        st.pulling = true
        try {
            var pages = 0
            do {
                st.pullAgain = false
                var more = true
                while (more && pages < 1000) {
                    val current = states[groupId] ?: return
                    try {
                        val page = api.changes(groupId, current.secret.pass, since = current.row.cursor)
                        if (states[groupId] !== current) return
                        pages += 1
                        val applied = applyPage(groupId, page) ?: return
                        more = applied.reset || page.more
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFailure(e, groupId)
                        return
                    }
                }
            } while (states[groupId]?.pullAgain == true)
            fetchArchives(groupId)
            val current = states[groupId] ?: return
            val s = socket
            if (s != null && s.groupId == groupId && s.welcomed) {
                s.socket.send(SocketMessage.ack(cursor = current.row.cursor))
            }
            if (current.needsColourCheck) {
                current.needsColourCheck = false
                fixColourClash(current)
            }
        } finally {
            states[groupId]?.pulling = false
        }
    }

    private suspend fun fetchArchives(groupId: String) {
        for (op in store.ops(groupId)) {
            if (op.kind != "archive") continue
            val st = states[groupId]
            val fetch = GroupStore.ArchiveFetch.decode(op.body)
            if (st == null || fetch == null) {
                store.deleteOp(op.id)
                continue
            }
            try {
                val data = api.archive(groupId, st.secret.pass, fetch.memberId, fetch.month)
                val current = states[groupId] ?: return
                val records = GroupArchive.parse(data)
                if (records != null && (fetch.memberId == current.secret.memberId || current.members.any { it.memberId == fetch.memberId })) {
                    try {
                        store.storeArchive(groupId, memberId = fetch.memberId, records = records, seq = fetch.seq)
                    } catch (e: GroupStore.StoreError) {
                        GroupLog.note("couldn't store an archive month (${e.errorDescription})")
                    }
                    current.chunkRevision += 1
                    notify(groupId)
                } else {
                    GroupLog.note("an archive month didn't parse or its member is gone; skipped")
                }
                store.deleteOp(op.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val definite = e is GroupAPIError && ((e.status == 404 && e.code == "not_found") ||
                    (e.status in 400 until 500 && e.status != 429 && e.status != 401 && e.status != 410 && e.code != "group_not_found"))
                if (definite) {
                    store.deleteOp(op.id)
                } else {
                    // Retried at the next pull; the cursor has moved on.
                    handleFailure(e, groupId)
                    return
                }
            }
        }
    }

    /** A joiner whose random colour another member already has takes the lowest free one, if any (§4.6). */
    private fun fixColourClash(st: GroupState) {
        val me = st.secret.memberId
        val mine = st.members.firstOrNull { it.memberId == me } ?: return
        val profile = memberProfile(st, mine) ?: return
        val others = st.members.filter { it.memberId != me }.mapNotNull { memberProfile(st, it)?.colour }
        if (!others.contains(profile.colour)) return
        val free = GroupPolicy.palette.indices.firstOrNull { !others.contains(it) } ?: return
        val env = orNull { GroupCrypto.seal(MemberProfile(name = profile.name, colour = free).json(), st.keys.member, GroupAAD.member(st.id, me)) } ?: return
        store.addOp(st.id, kind = "profile", body = Base64URL.encode(env), createdAt = clock())
        background { hub -> hub.flush(st.id, now = hub.clock(), force = true) }
    }

    // Internals: the outbox

    /** Sends the queued requests, then the trail chunks and live position that are due. */
    private suspend fun flush(groupId: String, now: Long, force: Boolean) {
        val st = states[groupId] ?: return
        if (st.flushing) return
        if (!force && now < st.retryAt) return
        st.flushing = true
        try {
            if (!sendOps(groupId, now)) return
            upload(groupId, now)
        } finally {
            states[groupId]?.flushing = false
        }
    }

    private fun noteRetry(st: GroupState, now: Long, error: Exception) {
        if (error is GroupAPIError && error.status == 429) {
            st.retryAt = now + 60
            return
        }
        st.retryAt = now + GroupPolicy.retryBackoff[minOf(st.retryStep, GroupPolicy.retryBackoff.size - 1)]
        st.retryStep += 1
    }

    private fun isRetryable(error: Exception): Boolean {
        if (error is GroupTransportError) return true
        if (error !is GroupAPIError) return true
        return error.status == 429 || error.status >= 500
    }

    /** Sends the queued ops in order, including those queued while it runs (such as the `unpin` for a pin deleted while
     *  its E23 was in flight). Returns false when it had to stop (offline, a server error, or the group gone). */
    private suspend fun sendOps(groupId: String, now: Long): Boolean {
        var after = 0L
        while (true) {
            val op = store.nextOp(groupId, after = after, except = "archive") ?: break
            after = op.id
            val st = states[groupId] ?: return false
            if (op.kind == "pin" && st.isPaused) continue // waits for the resume; deletes and the rest still go
            try {
                when (op.kind) {
                    "pin" -> sendPin(st, op)
                    "unpin" -> {
                        try {
                            api.deletePin(groupId, st.secret.pass, op.body)
                        } catch (e: GroupAPIError) {
                            // Already deleted.
                            if (!(e.status == 404 && e.code == "not_found")) throw e
                        }
                        store.deleteOp(op.id)
                    }
                    "profile" -> {
                        val seq = api.updateMyProfile(groupId, st.secret.pass, op.body).seq
                        store.deleteOp(op.id)
                        val env = Base64URL.decode(op.body)
                        val m = states[groupId]?.members?.firstOrNull { it.memberId == st.secret.memberId }?.copy()
                        if (m != null && env != null) {
                            m.profile = env
                            m.seq = seq
                            store.putMember(groupId, m)
                            reload(groupId)
                            notify(groupId)
                        }
                    }
                    "cleartrail" -> {
                        try {
                            api.clearTrail(groupId, st.secret.pass)
                        } catch (e: GroupAPIError) {
                            // Nothing to clear.
                            if (!(e.status == 404 && e.code == "not_found")) throw e
                        }
                        store.deleteOp(op.id)
                    }
                    else -> store.deleteOp(op.id)
                }
                st.retryStep = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRetryable(e)) {
                    noteRetry(st, now, e)
                    return false
                }
                if (handleFailure(e, groupId)) return false
                if (e is GroupAPIError && e.status == 409 && e.code == "group_paused") continue
                // A definite refusal: the op can never succeed.
                GroupLog.note("a queued ${op.kind} was refused (${(e as? GroupAPIError)?.code ?: "?"}); dropped")
                store.deleteOp(op.id)
                if (op.kind == "pin") {
                    PinOp.decode(op.body)?.let { pin ->
                        store.deletePin(groupId, pin.pinId)
                        reload(groupId)
                        notify(groupId)
                    }
                }
            }
        }
        return true
    }

    /** E23 for a queued pin. `pin_exists` gives it a new id once; `pin_deleted`, `pin_expired` and other refusals drop it.
     *  `deletePin` may remove the op while the E23 is in flight: if the server may then hold the pin (it said yes, or
     *  the answer never came), an `unpin` follows, so it doesn't stay for everyone; after a refusal nothing does. */
    private suspend fun sendPin(st: GroupState, op: GroupStore.QueuedOp) {
        var pin = PinOp.decode(op.body) ?: run {
            store.deleteOp(op.id)
            return
        }
        val plain = Base64URL.decode(pin.plain) ?: run {
            store.deleteOp(op.id)
            return
        }
        val gid = st.id
        val me = st.secret.memberId
        while (true) {
            val env = GroupCrypto.seal(plain, st.keys.pin, GroupAAD.pin(gid, pinId = pin.pinId, memberId = me, createdAt = pin.createdAt, expiresAt = pin.expiresAt))
            try {
                val seq = api.dropPin(gid, st.secret.pass, PinRequest(pinId = pin.pinId, createdAt = pin.createdAt, expiresAt = pin.expiresAt,
                                                                       rec = Base64URL.encode(env))).seq
                if (unpinIfDeletedMeanwhile(st, op, pin.pinId)) return
                store.deleteOp(op.id)
                val sentId = pin.pinId
                val stored = store.pins(gid).firstOrNull { it.pinId == sentId }
                if (stored != null && stored.seq < seq) {
                    store.putPin(gid, stored.copy(seq = seq))
                }
                reload(gid)
                notify(gid)
                return
            } catch (e: Exception) {
                // A refusal (a 4xx) stored nothing; any other outcome (a 5xx, no answer, cancelled) may have.
                if (!(e is GroupAPIError && e.status < 500)) unpinIfDeletedMeanwhile(st, op, pin.pinId)
                if (!(e is GroupAPIError && e.status == 409 && e.code == "pin_exists" && !pin.retried)) throw e
                // Deleted meanwhile: the id is another member's, so there is nothing to delete and nothing to resend.
                if (!store.opExists(op.id)) return
                val oldId = pin.pinId
                val renamed = pin.copy(pinId = GroupRandom.id(), retried = true)
                pin = renamed
                val newEnv = GroupCrypto.seal(plain, st.keys.pin,
                                              GroupAAD.pin(gid, pinId = renamed.pinId, memberId = me, createdAt = renamed.createdAt, expiresAt = renamed.expiresAt))
                try {
                    store.transaction {
                        store.deletePin(gid, oldId)
                        store.putPin(gid, GroupStore.StoredPin(pinId = renamed.pinId, memberId = me, createdAt = renamed.createdAt,
                                                               expiresAt = renamed.expiresAt, rec = newEnv, seq = 0))
                        store.updateOp(op.id, body = renamed.json())
                    }
                } catch (e: GroupStore.StoreError) {
                    GroupLog.note("couldn't give a queued pin a new id (${e.errorDescription})")
                }
                reload(gid)
            }
        }
    }

    /** After an E23 that may have stored the pin: if `deletePin` removed its op meanwhile (and the group is still
     *  here), queues an `unpin` for the id that was sent (a `404` counts as done, §6.7). Returns whether it did. */
    private fun unpinIfDeletedMeanwhile(st: GroupState, op: GroupStore.QueuedOp, sentId: String): Boolean {
        if (states[st.id] !== st || store.opExists(op.id)) return false
        store.addOp(st.id, kind = "unpin", body = sentId, createdAt = clock())
        return true
    }

    /** Uploads the due trail chunks and live position (§4.3, §8.6), over the socket when it is open for the shown
     *  group in the foreground, otherwise over HTTP; packed by 50 chunks or 192 KiB. */
    private suspend fun upload(groupId: String, now: Long) {
        val st = states[groupId] ?: return
        val paused = st.isPaused
        if (paused && !st.stopLivePending) return
        val sharing = isEffectivelySharing(st)
        val clearing = store.hasOp(groupId, kind = "cleartrail")
        if (!paused) {
            val pauses = st.status?.pauses
            if (!pauses.isNullOrEmpty()) store.dropOwnPoints(groupId, inside = pauses)
        }
        val windows = if (paused || clearing) emptyList() else store.ownWindows(groupId).filter { it.version > it.sentVersion && !st.badTimeHold.contains(it.start) }
        val viaSocket = isSocketReady(groupId)
        val chunkDue = windows.isNotEmpty() && (st.lastChunkUpload?.let { now - it >= GroupPolicy.uploadInterval } ?: true)
        var liveDue = false
        if (sharing) {
            val since = st.lastLiveSlot?.let { now - it } ?: Long.MAX_VALUE
            liveDue = if (viaSocket && st.watchers > 0) {
                (since >= GroupPolicy.liveWatchedForeground && movedEnough(st)) || since >= GroupPolicy.liveHeartbeat
            } else if (viaSocket) {
                since >= GroupPolicy.uploadInterval
            } else if (st.watchers > 0) {
                since >= GroupPolicy.liveWatchedBackground
            } else {
                since >= GroupPolicy.uploadInterval
            }
        }
        val stopDue = st.stopLivePending && !sharing
        if (!chunkDue && !liveDue && !stopDue) return

        var liveField: UploadRequest.LiveField = UploadRequest.LiveField.Unchanged
        var liveSent: Pair<Long, GeoPoint>? = null
        if (stopDue) {
            liveField = UploadRequest.LiveField.Stopped
        } else if (liveDue) {
            liveRecord(st)?.let { (live, position) ->
                liveField = UploadRequest.LiveField.Position(live)
                liveSent = live.at to position
            }
        }
        if (liveDue) st.lastLiveSlot = now
        if (chunkDue) st.lastChunkUpload = now

        val queue = if (chunkDue) windows.toMutableList() else mutableListOf()
        var first = true
        while (first || queue.isNotEmpty()) {
            first = false
            val (chunks, sent) = pack(st, queue, liveField, viaSocket)
            val body = UploadRequest(chunks, liveField)
            val s = socket
            if (viaSocket && s != null && s.groupId == groupId) {
                val id = s.nextId
                s.nextId += 1
                s.pending[id] = sent
                s.socket.send(body.json(socketId = id).toString(Charsets.UTF_8))
                if (stopDue) st.stopLivePending = false
            } else {
                try {
                    val result = api.upload(groupId, st.secret.pass, body)
                    if (states[groupId] !== st) return
                    st.retryStep = 0
                    if (stopDue) st.stopLivePending = false
                    applyUploadResult(st, result, sent, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val firstWindow = sent.windows.firstOrNull()
                    if (e is GroupAPIError && e.status == 413 && firstWindow != null) {
                        GroupLog.note("an upload was too large; its oldest chunk is dropped")
                        store.deleteOwnWindow(groupId, start = firstWindow.first)
                    } else if (isRetryable(e)) {
                        noteRetry(st, now, e)
                    } else {
                        handleFailure(e, groupId)
                    }
                    // Not sent: the live slot and chunk time go back so it is tried again.
                    if (liveDue) st.lastLiveSlot = null
                    if (chunkDue) st.lastChunkUpload = null
                    return
                }
            }
            liveSent?.let { (at, position) ->
                st.lastLiveSentAt = at
                st.lastLivePosition = position
                liveSent = null
            }
            liveField = UploadRequest.LiveField.Unchanged
        }
    }

    /** Takes chunks oldest first until 50 or the JSON body would pass 192 KiB (§4.3); each a fresh encryption. */
    private fun pack(st: GroupState, queue: MutableList<GroupStore.OwnWindow>, live: UploadRequest.LiveField,
                     socket: Boolean): Pair<List<UploadRequest.Chunk>, SentUpload> {
        val chunks = mutableListOf<UploadRequest.Chunk>()
        val sent = SentUpload()
        var size = UploadRequest(emptyList(), live).json(socketId = if (socket) 1_000_000 else null).size
        val me = st.secret.memberId
        while (queue.isNotEmpty() && chunks.size < GroupPolicy.maxChunksPerUpload) {
            val w = queue.first()
            val env = orNull {
                val points = TrailChunk.decode(w.points, windowStart = w.start)
                val plain = TrailChunk.encode(points, windowStart = w.start, compress = true)
                GroupCrypto.seal(plain, st.keys.trail(me), GroupAAD.trail(st.id, me, start = w.start))
            }
            if (env == null) {
                queue.removeAt(0)
                store.deleteOwnWindow(st.id, start = w.start)
                continue
            }
            val chunk = UploadRequest.Chunk(start = w.start, rec = Base64URL.encode(env))
            val added = "{\"start\":${chunk.start},\"rec\":\"${chunk.rec}\"}".toByteArray(Charsets.UTF_8).size + (if (chunks.isEmpty()) 0 else 1)
            if (chunks.isNotEmpty() && size + added > GroupPolicy.maxUploadBytes) break
            size += added
            chunks.add(chunk)
            sent.windows.add(w.start to w.version)
            queue.removeAt(0)
        }
        return chunks to sent
    }

    /** The live record to send: the newest fix, if location is authorised and precise, its accuracy known and at most
     *  100 m, and it is newer than the last one sent (§8.5, §8.6). */
    private fun liveRecord(st: GroupState): Pair<UploadRequest.Live, GeoPoint>? {
        if (!locationAuthorised || !locationPrecise) return null
        val f = latestFix ?: return null
        val acc = f.accuracy ?: return null
        if (acc < 0 || acc > GroupPolicy.liveMaxAccuracy) return null
        val p = LiveFix.position(lat = f.lat, lon = f.lon, accuracy = f.accuracy, course = f.course, speed = f.speed, heading = f.heading, time = f.time)
        val last = st.lastLiveSentAt
        if (last != null && p.time <= last) return null
        val me = st.secret.memberId
        val env = orNull { GroupCrypto.seal(p.encoded(), st.keys.live(me), GroupAAD.live(st.id, me, at = p.time)) } ?: return null
        return UploadRequest.Live(at = p.time, rec = Base64URL.encode(env)) to GeoPoint(lat = f.lat, lon = f.lon)
    }

    private fun movedEnough(st: GroupState): Boolean {
        val f = latestFix ?: return false
        val last = st.lastLivePosition ?: return true
        return Geo.distance(last, GeoPoint(lat = f.lat, lon = f.lon)) >= GroupPolicy.liveMinMove
    }

    /** Handles an upload's answer per window (§4.3). */
    private fun applyUploadResult(st: GroupState, result: UploadResult, sent: SentUpload, now: Long) {
        val gid = st.id
        val versions = HashMap<Long, Long>()
        for ((start, version) in sent.windows) versions.putIfAbsent(start, version)
        for (start in result.accepted) {
            val version = versions[start] ?: continue
            store.markOwnWindowSent(gid, start = start, version = version, closed = start + GroupPolicy.windowSeconds <= now)
        }
        for (r in result.rejected) {
            when (r.error) {
                "bad_time" -> {
                    val tries = store.ownWindows(gid).firstOrNull { it.start == r.start }?.badTimeTries ?: 0
                    if (tries >= 1) {
                        store.deleteOwnWindow(gid, start = r.start)
                    } else {
                        store.setBadTimeTries(gid, start = r.start, tries = tries + 1)
                        st.badTimeHold.add(r.start)
                    }
                }
                // before_join, window_paused, bad_record: it can never be accepted.
                else -> store.deleteOwnWindow(gid, start = r.start)
            }
        }
        result.watchers?.let { st.watchers = it }
        val status = result.status
        if (status != null && (status == "paused") != st.isPaused) {
            background { it.refreshStatus(gid) }
        }
    }

    // Internals: the socket

    private fun isSocketReady(groupId: String): Boolean {
        val s = socket ?: return false
        return s.groupId == groupId && s.isOpen && s.welcomed && foreground
    }

    /** Opens the socket for the shown group while in the foreground (§8.7), closing one for another group. */
    private fun connectSocketIfWanted() {
        val factory = sockets ?: return
        val wanted = if (foreground) shownGroupId else null
        val s = socket
        if (s != null && s.groupId != wanted) closeSocket(code = 1000)
        if (wanted == null || socket != null) return
        val st = states[wanted] ?: return
        val at = reconnectAt
        if (at != null && clock() < at) return
        reconnectAt = null
        socket = SocketState(wanted, factory.open(groupId = wanted, pass = st.secret.pass))
    }

    private fun closeSocket(code: Int) {
        val s = socket ?: return
        socket = null
        reconnectAttempt = 0
        reconnectAt = null
        s.socket.close(code)
    }

    private fun shouldSendPro(status: GroupStatusDTO, row: GroupStore.StoredGroup, pro: ProProof, productId: String, expiresAt: Long?): Boolean {
        val current = status.pro ?: return true
        val reason = status.pausedReason
        if (reason != null && proReasons.contains(reason)) return true
        if (current.productId != productId) return true
        val known = current.expiresAt
        if (expiresAt != null && known != null && expiresAt > known + 60) return true
        if (pro is ProProof.Google && pro.purchaseToken != row.proSent) return true
        return false
    }

    /** The value, or null for any failure (Swift's `try?`). Only for work that doesn't suspend. */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val unknownMember = "Unknown member"
        const val blockedMember = "Blocked member"
        const val unnamedGroup = "Unnamed group"
        private val proReasons = setOf("pro_ended", "pro_refunded", "pro_unverified")

        /** Other members' points earlier than your `joinedAt` are cut (R9); your own stay. */
        fun visible(points: List<TrailPoint>, memberId: String, me: String, joinedAt: Long): List<TrailPoint> =
            if (memberId == me) points else points.filter { it.time >= joinedAt }

        /** Douglas–Peucker in Web Mercator units, with the tolerance in metres at the segment's latitude. */
        private fun simplify(points: List<TrailPoint>, tolerance: Double): List<GeoPoint> {
            val geo = points.map { GeoPoint(lat = MicroDegrees.degrees(it.lat), lon = MicroDegrees.degrees(it.lon)) }
            if (geo.size <= 2) return geo
            val unit = tolerance / Geo.metersPerWorldUnit(geo[0].lat)
            val world = geo.map { WebMercator.world(it) }
            val kept = Simplify.douglasPeucker(world, tolerance = unit)
            return kept.map { WebMercator.geo(worldX = it.x, worldY = it.y) }
        }
    }
}
