package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The server's HTTP and WebSocket API (docs/groups/SPEC.md §6) as models that build and read their JSON by hand with
// kotlinx-serialization-json's `JsonObject` (no plugin), and `GroupAPI`, which builds each request and reads its answer
// over an injected `GroupTransport`. Envelopes travel as base64url strings. The iPhone core (GroupAPI.swift, Codable)
// builds and reads the same JSON.
//
// Each model's `toJson()` writes what Swift's `JSONEncoder` writes for it (optional values that aren't set left out,
// unless the Swift writes a `null`); `fromJson` reads what Swift's `JSONDecoder` reads: unknown keys are ignored, a
// missing required value or a wrong type throws `SerializationException`.

/** A non-2xx answer: its status and the `error` code from the body (§6.2). A 2xx answer whose body doesn't parse is
 *  `badResponse`. */
data class GroupAPIError(val status: Int, val code: String) : Exception("$status $code") {
    companion object {
        const val badResponse = "bad_response"
    }
}

// Shared objects (§6.3)

data class YouDTO(val memberId: String, val role: String, val joinedAt: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("memberId", memberId)
        put("role", role)
        put("joinedAt", joinedAt)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = YouDTO(f.string("memberId"), f.string("role"), f.long("joinedAt"))
    }
}

data class TransferDTO(val to: String, val expiresAt: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("to", to)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = TransferDTO(f.string("to"), f.long("expiresAt"))
    }
}

data class ProInfoDTO(
    val platform: String,
    val productId: String,
    /** Null for Lifetime. */
    val expiresAt: Long?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("platform", platform)
        put("productId", productId)
        expiresAt?.let { put("expiresAt", it) }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = ProInfoDTO(f.string("platform"), f.string("productId"), f.optLong("expiresAt"))
    }
}

data class GroupStatusDTO(
    val groupId: String,
    /** "active" or "paused". */
    val status: String,
    val pausedReason: String?,
    val pausedAt: Long?,
    val deleteAt: Long?,
    /** `[pausedAt, resumedAt or null]` for the pauses of the last 30 days and the current one. */
    val pausedIntervals: List<List<Long?>>?,
    val ownerId: String,
    val memberCount: Int,
    /** The group profile's envelope. */
    val profile: String?,
    val profileSeq: Long?,
    val you: YouDTO,
    val transfer: TransferDTO?,
    /** Shown only to the owner. */
    val pro: ProInfoDTO?,
    val watchers: Int?,
    val seq: Long,
    val serverTime: Long,
) {
    /** A pause as a closed or open range (an open one has no end). */
    data class Pause(val from: Long, val to: Long?)

    val isPaused: Boolean get() = status == "paused"

    /** The pauses as closed or open ranges. */
    val pauses: List<Pause>
        get() = (pausedIntervals ?: emptyList()).mapNotNull { pair ->
            val from = pair.firstOrNull() ?: return@mapNotNull null
            Pause(from, if (pair.size > 1) pair[1] else null)
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("groupId", groupId)
        put("status", status)
        pausedReason?.let { put("pausedReason", it) }
        pausedAt?.let { put("pausedAt", it) }
        deleteAt?.let { put("deleteAt", it) }
        pausedIntervals?.let { intervals ->
            put("pausedIntervals", buildJsonArray { intervals.forEach { pair -> add(buildJsonArray { pair.forEach { add(JsonPrimitive(it)) } }) } })
        }
        put("ownerId", ownerId)
        put("memberCount", memberCount)
        profile?.let { put("profile", it) }
        profileSeq?.let { put("profileSeq", it) }
        put("you", you.toJson())
        transfer?.let { put("transfer", it.toJson()) }
        pro?.let { put("pro", it.toJson()) }
        watchers?.let { put("watchers", it) }
        put("seq", seq)
        put("serverTime", serverTime)
    }

    /** The JSON text the store keeps (`status_json`). */
    fun json(): String = toJson().toString()

    companion object {
        fun fromJson(f: GroupJSON.Fields) = GroupStatusDTO(
            groupId = f.string("groupId"),
            status = f.string("status"),
            pausedReason = f.optString("pausedReason"),
            pausedAt = f.optLong("pausedAt"),
            deleteAt = f.optLong("deleteAt"),
            pausedIntervals = f.optArray("pausedIntervals")?.map { pair ->
                GroupJSON.arrayValue(pair).map { if (it is JsonNull) null else GroupJSON.longValue(it) }
            },
            ownerId = f.string("ownerId"),
            memberCount = f.int("memberCount"),
            profile = f.optString("profile"),
            profileSeq = f.optLong("profileSeq"),
            you = YouDTO.fromJson(f.obj("you")),
            transfer = f.optObj("transfer")?.let(TransferDTO::fromJson),
            pro = f.optObj("pro")?.let(ProInfoDTO::fromJson),
            watchers = f.optInt("watchers"),
            seq = f.long("seq"),
            serverTime = f.long("serverTime"),
        )

        /** Reads `json()`'s text (or any status JSON); null if it isn't one. */
        fun decode(json: String): GroupStatusDTO? = try {
            fromJson(GroupJSON.Fields(GroupJSON.parseObject(json.toByteArray(Charsets.UTF_8))))
        } catch (e: SerializationException) {
            null
        }
    }
}

data class MemberDTO(val memberId: String, val role: String, val joinedAt: Long, val profile: String, val lastLiveAt: Long?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("memberId", memberId)
        put("role", role)
        put("joinedAt", joinedAt)
        put("profile", profile)
        lastLiveAt?.let { put("lastLiveAt", it) }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = MemberDTO(f.string("memberId"), f.string("role"), f.long("joinedAt"), f.string("profile"), f.optLong("lastLiveAt"))
    }
}

/** A live record (§6.3); `rec` is null in a tick for a member who stopped sharing. */
data class LiveEntry(val memberId: String, val at: Long, val rec: String?) {
    /** `rec` is written as `null` when there is none, as the Swift writes it. */
    fun toJson(): JsonObject = buildJsonObject {
        put("memberId", memberId)
        put("at", at)
        put("rec", rec?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = LiveEntry(f.string("memberId"), f.long("at"), f.optString("rec"))

        fun listJson(entries: List<LiveEntry>): JsonArray = buildJsonArray { entries.forEach { add(it.toJson()) } }
    }
}

/** A Pro proof (§6.3). */
sealed interface ProProof {
    data class Apple(val signedTransaction: String) : ProProof

    data class Google(val productId: String, val purchaseToken: String) : ProProof

    fun toJson(): JsonObject = when (this) {
        is Apple -> buildJsonObject {
            put("platform", "apple")
            put("signedTransaction", signedTransaction)
        }
        is Google -> buildJsonObject {
            put("platform", "google")
            put("productId", productId)
            put("purchaseToken", purchaseToken)
        }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields): ProProof = when (val platform = f.string("platform")) {
            "apple" -> Apple(f.string("signedTransaction"))
            "google" -> Google(f.string("productId"), f.string("purchaseToken"))
            else -> throw SerializationException("unknown platform $platform")
        }
    }
}

/** One change item (§6.3). Kinds this version doesn't know are kept as `Unknown`, skipped, and still move the cursor. */
sealed interface ChangeItem {
    val seq: Long

    data class Group(override val seq: Long, val profile: String) : ChangeItem
    data class Member(override val seq: Long, val memberId: String, val role: String, val joinedAt: Long, val profile: String) : ChangeItem
    data class Chunk(override val seq: Long, val memberId: String, val start: Long, val rec: String) : ChangeItem
    data class Archive(override val seq: Long, val memberId: String, val month: String, val maxChunkSeq: Long, val archivedAt: Long) : ChangeItem
    data class Pin(override val seq: Long, val pinId: String, val memberId: String, val createdAt: Long, val expiresAt: Long?, val rec: String) : ChangeItem

    /** `what` is "pin", "trail" or "member". */
    data class Removed(override val seq: Long, val what: String, val id: String) : ChangeItem
    data class Unknown(override val seq: Long) : ChangeItem

    fun toJson(): JsonObject = buildJsonObject {
        put("seq", seq)
        when (val item = this@ChangeItem) {
            is Group -> {
                put("kind", "group")
                put("profile", item.profile)
            }
            is Member -> {
                put("kind", "member")
                put("memberId", item.memberId)
                put("role", item.role)
                put("joinedAt", item.joinedAt)
                put("profile", item.profile)
            }
            is Chunk -> {
                put("kind", "chunk")
                put("memberId", item.memberId)
                put("start", item.start)
                put("rec", item.rec)
            }
            is Archive -> {
                put("kind", "archive")
                put("memberId", item.memberId)
                put("month", item.month)
                put("maxChunkSeq", item.maxChunkSeq)
                put("archivedAt", item.archivedAt)
            }
            is Pin -> {
                put("kind", "pin")
                put("pinId", item.pinId)
                put("memberId", item.memberId)
                put("createdAt", item.createdAt)
                put("expiresAt", item.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
                put("rec", item.rec)
            }
            is Removed -> {
                put("kind", "removed")
                put("what", item.what)
                put("id", item.id)
            }
            is Unknown -> put("kind", "unknown")
        }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields): ChangeItem {
            val seq = f.long("seq")
            return when (f.string("kind")) {
                "group" -> Group(seq, f.string("profile"))
                "member" -> Member(seq, f.string("memberId"), f.string("role"), f.long("joinedAt"), f.string("profile"))
                "chunk" -> Chunk(seq, f.string("memberId"), f.long("start"), f.string("rec"))
                "archive" -> Archive(seq, f.string("memberId"), f.string("month"), f.long("maxChunkSeq"), f.long("archivedAt"))
                "pin" -> Pin(seq, f.string("pinId"), f.string("memberId"), f.long("createdAt"), f.optLong("expiresAt"), f.string("rec"))
                "removed" -> Removed(seq, f.string("what"), f.string("id"))
                else -> Unknown(seq)
            }
        }

        fun listFromJson(json: JsonArray): List<ChangeItem> = json.map { fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }

        fun listJson(items: List<ChangeItem>): JsonArray = buildJsonArray { items.forEach { add(it.toJson()) } }
    }
}

/** An E19 page (§6.5), or a reset: the client deletes its copy of the group and pulls from 0. */
data class ChangesPage(val items: List<ChangeItem>, val cursor: Long, val more: Boolean, val serverTime: Long?, val reset: Boolean = false) {
    fun toJson(): JsonObject = buildJsonObject {
        if (reset) {
            put("reset", true)
            put("cursor", cursor)
            return@buildJsonObject
        }
        put("items", ChangeItem.listJson(items))
        put("cursor", cursor)
        put("more", more)
        serverTime?.let { put("serverTime", it) }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = ChangesPage(
            items = f.optArray("items")?.let(ChangeItem::listFromJson) ?: emptyList(),
            cursor = f.long("cursor"),
            more = f.optBool("more") ?: false,
            serverTime = f.optLong("serverTime"),
            reset = f.optBool("reset") ?: false,
        )
    }
}

/** An E18 body, or the body of a WebSocket `upload` (§6.5, §6.6). */
data class UploadRequest(val chunks: List<Chunk>, val live: LiveField) {
    data class Chunk(val start: Long, val rec: String)

    data class Live(val at: Long, val rec: String)

    sealed interface LiveField {
        /** No `live` key: no change (a heartbeat). */
        data object Unchanged : LiveField

        /** `"live": null`: I stopped sharing. */
        data object Stopped : LiveField

        data class Position(val live: Live) : LiveField
    }

    /** The JSON body; with `socketId`, the WebSocket message (`type` "upload" and the client counter `id`). */
    fun json(socketId: Int? = null): ByteArray {
        val out = StringBuilder("{")
        if (socketId != null) out.append("\"type\":\"upload\",\"id\":").append(socketId).append(',')
        out.append("\"chunks\":[")
        out.append(chunks.joinToString(",") { "{\"start\":${it.start},\"rec\":\"${it.rec}\"}" })
        out.append("]")
        when (live) {
            LiveField.Unchanged -> {}
            LiveField.Stopped -> out.append(",\"live\":null")
            is LiveField.Position -> out.append(",\"live\":{\"at\":${live.live.at},\"rec\":\"${live.live.rec}\"}")
        }
        return out.append("}").toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** The size of the JSON body with these chunks: what packing (§4.3) keeps under 192 KiB. */
        fun size(chunks: List<Chunk>, live: LiveField): Int = UploadRequest(chunks, live).json().size

        fun fromJson(f: GroupJSON.Fields): UploadRequest {
            val chunks = f.optArray("chunks")?.map {
                val c = GroupJSON.Fields(GroupJSON.objectValue(it))
                Chunk(c.long("start"), c.string("rec"))
            } ?: emptyList()
            val live = when {
                !f.has("live") -> LiveField.Unchanged
                f.isNull("live") -> LiveField.Stopped
                else -> f.obj("live").let { LiveField.Position(Live(it.long("at"), it.string("rec"))) }
            }
            return UploadRequest(chunks, live)
        }
    }
}

data class UploadRejection(
    val start: Long,
    /** "before_join", "bad_time", "bad_record" or "window_paused". */
    val error: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("start", start)
        put("error", error)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = UploadRejection(f.long("start"), f.string("error"))
    }
}

/** An E18 answer, or a WebSocket `uploaded` (which has no `watchers`, `status` or `serverTime`). */
data class UploadResult(
    val accepted: List<Long>,
    val rejected: List<UploadRejection>,
    val liveAccepted: Boolean?,
    val watchers: Int?,
    val status: String?,
    val serverTime: Long?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("accepted", buildJsonArray { accepted.forEach { add(JsonPrimitive(it)) } })
        put("rejected", buildJsonArray { rejected.forEach { add(it.toJson()) } })
        liveAccepted?.let { put("liveAccepted", it) }
        watchers?.let { put("watchers", it) }
        status?.let { put("status", it) }
        serverTime?.let { put("serverTime", it) }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = UploadResult(
            accepted = f.array("accepted").map(GroupJSON::longValue),
            rejected = f.array("rejected").map { UploadRejection.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) },
            liveAccepted = f.optBool("liveAccepted"),
            watchers = f.optInt("watchers"),
            status = f.optString("status"),
            serverTime = f.optLong("serverTime"),
        )
    }
}

data class LivePage(val live: List<LiveEntry>, val watchers: Int?, val serverTime: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("live", LiveEntry.listJson(live))
        watchers?.let { put("watchers", it) }
        put("serverTime", serverTime)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = LivePage(
            live = f.array("live").map { LiveEntry.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) },
            watchers = f.optInt("watchers"),
            serverTime = f.long("serverTime"),
        )
    }
}

data class CreateGroupRequest(
    val groupId: String,
    val memberId: String,
    val pass: String,
    val joinProof: String,
    val groupProfile: String,
    val memberProfile: String,
    val termsVersion: Int,
    val pro: ProProof,
) {
    /** The pass, not in logs. */
    override fun toString(): String = "CreateGroupRequest(groupId=$groupId, memberId=$memberId)"

    fun toJson(): JsonObject = buildJsonObject {
        put("groupId", groupId)
        put("memberId", memberId)
        put("pass", pass)
        put("joinProof", joinProof)
        put("groupProfile", groupProfile)
        put("memberProfile", memberProfile)
        put("termsVersion", termsVersion)
        put("pro", pro.toJson())
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = CreateGroupRequest(
            f.string("groupId"), f.string("memberId"), f.string("pass"), f.string("joinProof"), f.string("groupProfile"),
            f.string("memberProfile"), f.int("termsVersion"), ProProof.fromJson(f.obj("pro")),
        )
    }
}

data class RedeemRequest(
    val token: String,
    val joinProof: String,
    val memberId: String,
    val pass: String,
    val profile: String,
    val termsVersion: Int,
) {
    /** The pass, not in logs. */
    override fun toString(): String = "RedeemRequest(memberId=$memberId)"

    fun toJson(): JsonObject = buildJsonObject {
        put("token", token)
        put("joinProof", joinProof)
        put("memberId", memberId)
        put("pass", pass)
        put("profile", profile)
        put("termsVersion", termsVersion)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = RedeemRequest(
            f.string("token"), f.string("joinProof"), f.string("memberId"), f.string("pass"), f.string("profile"), f.int("termsVersion"),
        )
    }
}

data class PreviewRequest(val token: String, val joinProof: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("token", token)
        put("joinProof", joinProof)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = PreviewRequest(f.string("token"), f.string("joinProof"))
    }
}

data class PreviewDTO(
    val groupId: String,
    val status: String,
    val profile: String?,
    val memberCount: Int,
    val inviter: Inviter?,
    val expiresAt: Long,
) {
    data class Inviter(val memberId: String, val profile: String) {
        fun toJson(): JsonObject = buildJsonObject {
            put("memberId", memberId)
            put("profile", profile)
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("groupId", groupId)
        put("status", status)
        profile?.let { put("profile", it) }
        put("memberCount", memberCount)
        inviter?.let { put("inviter", it.toJson()) }
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = PreviewDTO(
            groupId = f.string("groupId"),
            status = f.string("status"),
            profile = f.optString("profile"),
            memberCount = f.int("memberCount"),
            inviter = f.optObj("inviter")?.let { Inviter(it.string("memberId"), it.string("profile")) },
            expiresAt = f.long("expiresAt"),
        )
    }
}

data class CreatedInviteDTO(val inviteId: String, val token: String, val createdAt: Long, val expiresAt: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("inviteId", inviteId)
        put("token", token)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = CreatedInviteDTO(f.string("inviteId"), f.string("token"), f.long("createdAt"), f.long("expiresAt"))
    }
}

data class InviteDTO(val inviteId: String, val createdBy: String, val createdAt: Long, val expiresAt: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("inviteId", inviteId)
        put("createdBy", createdBy)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = InviteDTO(f.string("inviteId"), f.string("createdBy"), f.long("createdAt"), f.long("expiresAt"))
    }
}

data class PinRequest(val pinId: String, val createdAt: Long, val expiresAt: Long?, val rec: String) {
    /** `expiresAt` is sent as `null` for a pin that doesn't expire. */
    fun toJson(): JsonObject = buildJsonObject {
        put("pinId", pinId)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt?.let(::JsonPrimitive) ?: JsonNull)
        put("rec", rec)
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = PinRequest(f.string("pinId"), f.long("createdAt"), f.optLong("expiresAt"), f.string("rec"))
    }
}

data class ReportRequest(
    /** "pin", "member" or "group". */
    val targetKind: String,
    val targetId: String,
    val reason: String,
    val comment: String,
    /** The reporter's decrypted copy (SD28): `type`, `animal`, `note`, `name`. Never a position (SD10). */
    val content: Map<String, String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("targetKind", targetKind)
        put("targetId", targetId)
        put("reason", reason)
        put("comment", comment)
        put("content", buildJsonObject { content.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = ReportRequest(
            f.string("targetKind"), f.string("targetId"), f.string("reason"), f.string("comment"),
            f.obj("content").obj.mapValues { GroupJSON.stringValue(it.value) },
        )
    }
}

data class SeqDTO(val seq: Long) {
    fun toJson(): JsonObject = buildJsonObject { put("seq", seq) }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = SeqDTO(f.long("seq"))
    }
}

data class ErrorDTO(val error: String, val message: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("error", error)
        message?.let { put("message", it) }
    }

    companion object {
        fun fromJson(f: GroupJSON.Fields) = ErrorDTO(f.string("error"), f.optString("message"))
    }
}

// WebSocket messages (§6.6)

/** A message from the server on the WebSocket. */
sealed interface SocketMessage {
    data class Welcome(val status: GroupStatusDTO, val live: List<LiveEntry>) : SocketMessage
    data class Uploaded(val id: Int, val result: UploadResult) : SocketMessage
    data class Error(val id: Int?, val code: String) : SocketMessage

    /** The one combined frame per tick: only the keys that have news. */
    data class Tick(
        val serverTime: Long?,
        val watchers: Int?,
        val live: List<LiveEntry>?,
        val changes: Changes?,
        val status: GroupStatusDTO?,
    ) : SocketMessage {
        data class Changes(val from: Long, val items: List<ChangeItem>, val cursor: Long, val more: Boolean)
    }

    data object Unknown : SocketMessage

    companion object {
        /** The message in a text frame, or null if it isn't JSON this version understands ("pong" included). */
        fun parse(text: String): SocketMessage? = try {
            val f = GroupJSON.Fields(GroupJSON.parseObject(text.toByteArray(Charsets.UTF_8)))
            // Every key is read (and checked) first, as Swift decodes the whole frame before looking at its type.
            val type = f.string("type")
            val id = f.optInt("id")
            val status = f.optObj("status")?.let(GroupStatusDTO::fromJson)
            val live = f.optArray("live")?.map { LiveEntry.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }
            val accepted = f.optArray("accepted")?.map(GroupJSON::longValue)
            val rejected = f.optArray("rejected")?.map { UploadRejection.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }
            val liveAccepted = f.optBool("liveAccepted")
            val error = f.optString("error")
            val serverTime = f.optLong("serverTime")
            val watchers = f.optInt("watchers")
            val changes = f.optObj("changes")?.let {
                Tick.Changes(it.long("from"), ChangeItem.listFromJson(it.array("items")), it.long("cursor"), it.bool("more"))
            }
            when (type) {
                "welcome" -> status?.let { Welcome(it, live ?: emptyList()) }
                "uploaded" -> id?.let {
                    Uploaded(it, UploadResult(accepted ?: emptyList(), rejected ?: emptyList(), liveAccepted, watchers = null, status = null, serverTime = null))
                }
                "error" -> Error(id, error ?: "")
                "tick" -> Tick(serverTime, watchers, live, changes, status)
                else -> Unknown
            }
        } catch (e: SerializationException) {
            null
        }

        fun hello(watching: Boolean, cursor: Long): String = "{\"type\":\"hello\",\"v\":1,\"watching\":$watching,\"cursor\":$cursor}"

        fun watching(on: Boolean): String = "{\"type\":\"watching\",\"on\":$on}"

        fun ack(cursor: Long): String = "{\"type\":\"ack\",\"cursor\":$cursor}"

        const val ping = "ping"
    }
}

// Archive objects (§4.9)

object GroupArchive {
    /** One window of an archive object: its start and the chunk's envelope, exactly as uploaded. */
    class Record(val start: Long, rec: ByteArray) {
        private val bytes = rec.copyOf()
        val rec: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is Record && other.start == start && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = start.hashCode() * 31 + bytes.contentHashCode()
        override fun toString(): String = "Record(start=$start, ${bytes.size} bytes)"
    }

    val magic: ByteArray get() = "SMGA".toByteArray(Charsets.US_ASCII)

    /** The records of an archive object: `SMGA`, version 1, then per window its start (Int64), length (UInt32) and
     *  envelope. Null if it doesn't follow the format. */
    fun parse(data: ByteArray): List<Record>? {
        if (data.size < 5 || !data.copyOf(4).contentEquals(magic) || data[4] != 0x01.toByte()) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Record>()
        var i = 5
        while (i < data.size) {
            if (i + 12 > data.size) return null
            val start = b.getLong(i)
            val length = b.getInt(i + 8).toLong() and 0xFFFF_FFFFL
            i += 12
            if (length > data.size - i) return null
            out.add(Record(start, data.copyOfRange(i, i + length.toInt())))
            i += length.toInt()
        }
        return out
    }

    fun build(records: List<Record>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic)
        out.write(0x01)
        for (r in records.sortedBy { it.start }) {
            val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            head.putLong(r.start)
            head.putInt(r.rec.size)
            out.write(head.array())
            out.write(r.rec)
        }
        return out.toByteArray()
    }
}

// Requests

/** Builds each request of §6.5 and reads its answer. Throws `GroupTransportError` when the request didn't reach the
 *  server, `GroupAPIError` for any other answer than the expected 2xx. */
class GroupAPI(val transport: GroupTransport) {
    private fun path(groupId: String, rest: String = ""): String = "/v1/groups/$groupId$rest"

    /** E1 */
    suspend fun create(body: CreateGroupRequest): GroupStatusDTO = call("POST", "/v1/groups", null, body.toJson(), GroupStatusDTO::fromJson)

    /** E2 */
    suspend fun status(groupId: String, pass: String): GroupStatusDTO = call("GET", path(groupId), pass, null, GroupStatusDTO::fromJson)

    /** E3 */
    suspend fun renameGroup(groupId: String, pass: String, profile: String): SeqDTO =
        call("PUT", path(groupId, "/profile"), pass, buildJsonObject { put("profile", profile) }, SeqDTO::fromJson)

    /** E4 */
    suspend fun deleteGroup(groupId: String, pass: String) = callEmpty("DELETE", path(groupId), pass)

    /** E5 */
    suspend fun updatePro(groupId: String, pass: String, pro: ProProof): GroupStatusDTO =
        call("PUT", path(groupId, "/pro"), pass, buildJsonObject { put("pro", pro.toJson()) }, GroupStatusDTO::fromJson)

    /** E6 */
    suspend fun createInvite(groupId: String, pass: String): CreatedInviteDTO =
        call("POST", path(groupId, "/invites"), pass, JsonObject(emptyMap()), CreatedInviteDTO::fromJson)

    /** E7 */
    suspend fun invites(groupId: String, pass: String): List<InviteDTO> = call("GET", path(groupId, "/invites"), pass, null) { f ->
        f.array("invites").map { InviteDTO.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }
    }

    /** E8 */
    suspend fun cancelInvite(groupId: String, pass: String, inviteId: String) = callEmpty("DELETE", path(groupId, "/invites/$inviteId"), pass)

    /** E9 */
    suspend fun preview(token: String, joinProof: String): PreviewDTO =
        call("POST", "/v1/invites/preview", null, PreviewRequest(token, joinProof).toJson(), PreviewDTO::fromJson)

    /** E10 */
    suspend fun redeem(body: RedeemRequest): GroupStatusDTO = call("POST", "/v1/invites/redeem", null, body.toJson(), GroupStatusDTO::fromJson)

    /** E11 */
    suspend fun members(groupId: String, pass: String): List<MemberDTO> = call("GET", path(groupId, "/members"), pass, null) { f ->
        f.array("members").map { MemberDTO.fromJson(GroupJSON.Fields(GroupJSON.objectValue(it))) }
    }

    /** E12 */
    suspend fun updateMyProfile(groupId: String, pass: String, profile: String): SeqDTO =
        call("PUT", path(groupId, "/members/me/profile"), pass, buildJsonObject { put("profile", profile) }, SeqDTO::fromJson)

    /** E13 */
    suspend fun setRole(groupId: String, pass: String, memberId: String, role: String): SeqDTO =
        call("PUT", path(groupId, "/members/$memberId/role"), pass, buildJsonObject { put("role", role) }, SeqDTO::fromJson)

    /** E14; `memberId` "me" leaves. */
    suspend fun removeMember(groupId: String, pass: String, memberId: String) = callEmpty("DELETE", path(groupId, "/members/$memberId"), pass)

    /** E15 */
    suspend fun offerOwnership(groupId: String, pass: String, to: String): GroupStatusDTO =
        call("POST", path(groupId, "/transfer"), pass, buildJsonObject { put("to", to) }, GroupStatusDTO::fromJson)

    /** E16 */
    suspend fun cancelOwnershipOffer(groupId: String, pass: String) = callEmpty("DELETE", path(groupId, "/transfer"), pass)

    /** E17 */
    suspend fun acceptOwnership(groupId: String, pass: String, pro: ProProof): GroupStatusDTO =
        call("POST", path(groupId, "/transfer/accept"), pass, buildJsonObject { put("pro", pro.toJson()) }, GroupStatusDTO::fromJson)

    /** E18 */
    suspend fun upload(groupId: String, pass: String, body: UploadRequest): UploadResult {
        val answer = transport.request("POST", path(groupId, "/upload"), pass, body.json())
        return decode(answer, UploadResult::fromJson)
    }

    /** E19 */
    suspend fun changes(groupId: String, pass: String, since: Long, limit: Int = 500): ChangesPage =
        call("GET", path(groupId, "/changes?since=$since&limit=$limit"), pass, null, ChangesPage::fromJson)

    /** E20: the §4.9 object. */
    suspend fun archive(groupId: String, pass: String, memberId: String, month: String): ByteArray {
        val answer = transport.request("GET", path(groupId, "/archive/$memberId/$month"), pass, null)
        if (answer.status !in 200 until 300) throw error(answer.status, answer.body)
        return answer.body
    }

    /** E21 */
    suspend fun live(groupId: String, pass: String): LivePage = call("GET", path(groupId, "/live"), pass, null, LivePage::fromJson)

    /** E22 */
    suspend fun clearTrail(groupId: String, pass: String) = callEmpty("DELETE", path(groupId, "/trail/me"), pass)

    /** E23 */
    suspend fun dropPin(groupId: String, pass: String, body: PinRequest): SeqDTO = call("POST", path(groupId, "/pins"), pass, body.toJson(), SeqDTO::fromJson)

    /** E24 */
    suspend fun deletePin(groupId: String, pass: String, pinId: String) = callEmpty("DELETE", path(groupId, "/pins/$pinId"), pass)

    /** E25: the report id. */
    suspend fun report(groupId: String, pass: String, body: ReportRequest): String =
        call("POST", path(groupId, "/reports"), pass, body.toJson()) { it.string("reportId") }

    // Plumbing

    private suspend fun <T> call(method: String, path: String, pass: String?, body: JsonElement?, read: (GroupJSON.Fields) -> T): T {
        val answer = transport.request(method, path, pass, body?.toString()?.toByteArray(Charsets.UTF_8))
        return decode(answer, read)
    }

    private suspend fun callEmpty(method: String, path: String, pass: String?) {
        val answer = transport.request(method, path, pass, null)
        if (answer.status !in 200 until 300) throw error(answer.status, answer.body)
    }

    private fun <T> decode(answer: GroupResponse, read: (GroupJSON.Fields) -> T): T {
        if (answer.status !in 200 until 300) throw error(answer.status, answer.body)
        return try {
            read(GroupJSON.Fields(GroupJSON.parseObject(answer.body)))
        } catch (e: SerializationException) {
            throw GroupAPIError(answer.status, GroupAPIError.badResponse)
        }
    }

    companion object {
        fun error(status: Int, data: ByteArray): GroupAPIError {
            val code = try {
                ErrorDTO.fromJson(GroupJSON.Fields(GroupJSON.parseObject(data))).error
            } catch (e: SerializationException) {
                ""
            }
            return GroupAPIError(status, code)
        }
    }
}
