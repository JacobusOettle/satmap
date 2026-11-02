package com.jacobusoettle.satmap.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One example of Fixtures/groups/api-examples.json (docs/groups/SPEC.md §13.1), recorded by the server's integration
 *  tests from real requests to the Worker. */
class APIExample(val id: String, val method: String, val path: String, val request: JsonElement?, val status: Int, val response: JsonElement?) {
    /** "E1", "E26", "A3" … */
    val endpoint: String get() = id.substringBefore('-')

    /** The path's segments without the query: ["v1", "groups", groupId, …]. */
    val segments: List<String> get() = path.substringBefore('?').split('/').filter { it.isNotEmpty() }

    val query: Map<String, String> get() = queryOf(path)

    override fun toString(): String = id

    companion object {
        fun loadAll(): List<APIExample> {
            val file = Json.parseToJsonElement(fixture("groups/api-examples.json").toString(Charsets.UTF_8)).jsonObject
            return file.getValue("examples").jsonArray.map {
                val o = it.jsonObject
                APIExample(
                    id = o.text("id")!!,
                    method = o.text("method")!!,
                    path = o.text("path")!!,
                    request = o["request"].takeUnless { r -> r is JsonNull },
                    status = (o.getValue("status") as JsonPrimitive).intOrNull!!,
                    response = o["response"].takeUnless { r -> r is JsonNull },
                )
            }
        }

        fun queryOf(path: String): Map<String, String> {
            if (!path.contains('?')) return emptyMap()
            return path.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        }
    }
}

/** The value with every object member whose value is null left out, at any depth (nulls in arrays stay): the server
 *  writes `"key": null` where a model leaves an unset optional out, and both mean the same. */
fun JsonElement.withoutNullMembers(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterValues { it !is JsonNull }.mapValues { it.value.withoutNullMembers() })
    is JsonArray -> JsonArray(map { it.withoutNullMembers() })
    else -> this
}

private fun JsonElement?.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.number(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonElement?.flag(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** Contract tests (docs/groups/SPEC.md §13.3 step 4), as the iPhone core's GroupContractTests. Every example the
 *  server recorded goes through `GroupAPI`: the core must build the request the server accepted (method, path, Bearer
 *  or not, and the body as the server reads it), and read every answer: a success body back to the same JSON, every
 *  error to its status and §6.2 code. The WebSocket frames go through `SocketMessage` the same way. */
class GroupContractTests {
    private val examples = APIExample.loadAll()

    @Test
    fun testTheFileCoversEveryEndpointAndErrorCode() {
        assertEquals("ids are unique", examples.size, examples.map { it.id }.toSet().size)
        val endpoints = examples.map { it.endpoint }.toSet()
        // E27 can't be recorded: Apple's signed payload carries a fresh ECDSA signature on every run.
        for (n in 1..26) {
            assertTrue("no example for E$n", "E$n" in endpoints)
            assertTrue("no success example for E$n", examples.any { it.endpoint == "E$n" && (it.method == "WS" || it.status in 200 until 300) })
        }
        val codes = examples.filter { it.status !in 100 until 300 }.mapNotNull { it.response.text("error") }.toSet()
        assertEquals("codes the spec doesn't list", emptySet<String>(), codes - errorCodes)
        assertEquals("§6.2 codes with no example", emptySet<String>(), errorCodes - codes)
        for (e in examples.filter { it.status !in 100 until 300 }) assertNotNull("$e: an error answer has an error code", e.response.text("error"))
        val frames = examples.filter { it.method == "WS" }.mapNotNull { it.response.text("type") }.toSet()
        assertEquals("every server frame of §6.6", setOf("welcome", "uploaded", "error", "tick"), frames)
    }

    @Test
    fun testEveryHTTPExampleBuildsTheSameRequestAndReadsTheAnswer() = runBlocking {
        var checked = 0
        for (e in examples.filter { it.method != "WS" && it.endpoint !in notForApps }) {
            check(e)
            checked++
        }
        assertTrue(checked > 70)
    }

    @Test
    fun testEveryWebSocketFrame() {
        val frames = examples.filter { it.method == "WS" }
        assertTrue(frames.isNotEmpty())
        for (e in frames) {
            e.request?.let { request ->
                assertEquals("$e: the frame the core sends", normalised(e.endpoint, request),
                             normalised(e.endpoint, Json.parseToJsonElement(sentFrame(e, request))))
            }
            e.response?.let { checkServerFrame(e, it) }
        }
    }

    // HTTP

    /** Runs the example's request through GroupAPI, answering with the recorded status and body. */
    private suspend fun check(e: APIExample) {
        val t = RecordingTransport()
        t.answers.addLast(GroupResponse(e.status, e.response?.toString()?.toByteArray() ?: ByteArray(0)))
        val api = GroupAPI(t)
        val outcome = try {
            Result.success(call(e, api, t))
        } catch (x: Exception) {
            Result.failure(x)
        }
        val sent = t.requests.firstOrNull()
        if (sent != null) {
            assertEquals("$e: one request", 1, t.requests.size)
            assertEquals("$e: method", e.method, sent.method)
            assertEquals("$e: path", normalisedPath(e.path), normalisedPath(sent.path))
            assertEquals("$e: Bearer pass sent only where §6.1 asks for it", e.endpoint in setOf("E1", "E9", "E10"), sent.pass == null)
            val request = e.request
            when {
                e.id in rawBodies -> assertNotNull("$e: a body is sent", sent.json)
                request != null -> {
                    val body = Json.parseToJsonElement(sent.json?.toString(Charsets.UTF_8) ?: return fail("$e: no body sent"))
                    assertEquals("$e: body", normalised(e.endpoint, request), normalised(e.endpoint, body))
                }
                else -> assertNull("$e: no body", sent.json)
            }
        } else {
            assertTrue("$e: the core sent nothing", e.id in notSentByTheCore)
        }
        if (e.status in 200 until 300) {
            val read = outcome.getOrElse { return fail("$e: $it") }
            e.response?.let { response ->
                assertEquals("$e: what the core read", response.withoutNullMembers(), (read ?: return fail("$e: the core read nothing")).withoutNullMembers())
            }
        } else {
            val code = e.response.text("error") ?: return fail("$e: no error code")
            val error = outcome.exceptionOrNull() ?: return fail("$e: an error answer was taken for a success")
            assertEquals(e.id, GroupAPIError(e.status, code), error)
        }
    }

    /** Calls the GroupAPI function for the example's endpoint with the values in its path and body; returns what the
     *  core read, as JSON (null for an answer without a JSON body). */
    private suspend fun call(e: APIExample, api: GroupAPI, t: RecordingTransport): JsonElement? {
        val pass = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8"
        val p = e.segments
        val groupId = p.getOrElse(2) { "" }
        val body = e.request
        if (e.id in notSentByTheCore) throw GroupAPI.error(e.status, e.response?.toString()?.toByteArray() ?: ByteArray(0))
        return when (e.endpoint) {
            "E1" -> {
                val source = if (e.id in rawBodies) examples.first { it.id == "E1-created" }.request else body
                api.create(CreateGroupRequest(
                    groupId = source.text("groupId")!!, memberId = source.text("memberId")!!, pass = source.text("pass")!!,
                    joinProof = source.text("joinProof")!!, groupProfile = source.text("groupProfile")!!,
                    memberProfile = source.text("memberProfile")!!, termsVersion = source.number("termsVersion")!!.toInt(),
                    pro = proof(source!!.jsonObject["pro"]),
                )).toJson()
            }
            "E2" -> api.status(groupId, pass).toJson()
            "E3" -> api.renameGroup(groupId, pass, body.text("profile")!!).toJson()
            "E4" -> api.deleteGroup(groupId, pass).let { null }
            "E5" -> api.updatePro(groupId, pass, proof(body!!.jsonObject["pro"])).toJson()
            "E6" -> {
                assertEquals("$e: E6 sends {}", JsonObject(emptyMap()), body)
                api.createInvite(groupId, pass).toJson()
            }
            "E7" -> {
                val invites = api.invites(groupId, pass)
                buildJsonObject { put("invites", buildJsonArray { invites.forEach { add(it.toJson()) } }) }
            }
            "E8" -> api.cancelInvite(groupId, pass, p[4]).let { null }
            "E9" -> api.preview(token = body.text("token")!!, joinProof = body.text("joinProof")!!).toJson()
            "E10" -> api.redeem(RedeemRequest(
                token = body.text("token")!!, joinProof = body.text("joinProof")!!, memberId = body.text("memberId")!!,
                pass = body.text("pass")!!, profile = body.text("profile")!!, termsVersion = body.number("termsVersion")!!.toInt(),
            )).toJson()
            "E11" -> {
                val members = api.members(groupId, pass)
                buildJsonObject { put("members", buildJsonArray { members.forEach { add(it.toJson()) } }) }
            }
            "E12" -> {
                assertEquals("me", p[4])
                api.updateMyProfile(groupId, pass, body.text("profile")!!).toJson()
            }
            "E13" -> api.setRole(groupId, pass, memberId = p[4], role = body.text("role")!!).toJson()
            "E14" -> api.removeMember(groupId, pass, memberId = p[4]).let { null }
            "E15" -> api.offerOwnership(groupId, pass, to = body.text("to")!!).toJson()
            "E16" -> api.cancelOwnershipOffer(groupId, pass).let { null }
            "E17" -> api.acceptOwnership(groupId, pass, proof(body!!.jsonObject["pro"])).toJson()
            "E18" -> api.upload(groupId, pass, upload(body!!)).toJson()
            "E19" -> {
                val since = e.query["since"]?.toLongOrNull() ?: if (e.query.containsKey("since")) -1L else 0L
                val limit = e.query["limit"]?.toIntOrNull() ?: if (e.query.containsKey("limit")) -1 else 500
                api.changes(groupId, pass, since = since, limit = limit).toJson()
            }
            "E20" -> {
                // The answer is the §4.9 object, which the recorder keeps as null: answer with an empty archive.
                val archive = GroupArchive.build(emptyList())
                if (e.status in 200 until 300) {
                    t.answers.clear()
                    t.answers.addLast(GroupResponse(e.status, archive))
                }
                assertArrayEquals("$e: the archive bytes as sent", archive, api.archive(groupId, pass, memberId = p[4], month = p[5]))
                null
            }
            "E21" -> api.live(groupId, pass).toJson()
            "E22" -> {
                assertEquals("me", p[4])
                api.clearTrail(groupId, pass).let { null }
            }
            "E23" -> api.dropPin(groupId, pass, PinRequest(
                pinId = body.text("pinId")!!, createdAt = body.number("createdAt")!!, expiresAt = body.number("expiresAt"), rec = body.text("rec")!!,
            )).toJson()
            "E24" -> api.deletePin(groupId, pass, pinId = p[4]).let { null }
            "E25" -> {
                val content = (body!!.jsonObject["content"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }?.toMap()
                val reportId = api.report(groupId, pass, ReportRequest(
                    targetKind = body.text("targetKind")!!, targetId = body.text("targetId")!!, reason = body.text("reason")!!,
                    comment = body.text("comment") ?: "", content = content ?: emptyMap(),
                ))
                buildJsonObject { put("reportId", reportId) }
            }
            else -> {
                fail("$e: no core call for ${e.endpoint}")
                null
            }
        }
    }

    // WebSocket

    /** The text the core sends for the example's client frame. */
    private fun sentFrame(e: APIExample, request: JsonElement): String = when (val type = request.text("type")) {
        "hello" -> {
            assertEquals(e.id, 1L, request.number("v"))
            SocketMessage.hello(watching = request.flag("watching")!!, cursor = request.number("cursor")!!)
        }
        "watching" -> SocketMessage.watching(request.flag("on")!!)
        "ack" -> SocketMessage.ack(cursor = request.number("cursor")!!)
        "upload" -> upload(request).json(socketId = request.number("id")!!.toInt()).toString(Charsets.UTF_8)
        else -> {
            fail("$e: unknown client frame $type")
            ""
        }
    }

    /** The server frame through `SocketMessage.parse`, and what it read back as JSON. */
    private fun checkServerFrame(e: APIExample, response: JsonElement) {
        val message = SocketMessage.parse(response.toString()) ?: return fail("$e: the core can't read the frame")
        var expected = response.jsonObject
        val read: JsonObject = when (message) {
            is SocketMessage.Welcome -> {
                // The core doesn't read `v`: there is only version 1.
                assertEquals(e.id, 1L, response.number("v"))
                expected = JsonObject(expected - "v")
                buildJsonObject {
                    put("type", "welcome")
                    put("status", message.status.toJson())
                    put("live", LiveEntry.listJson(message.live))
                }
            }
            is SocketMessage.Uploaded -> JsonObject(message.result.toJson() + mapOf("type" to JsonPrimitive("uploaded"), "id" to JsonPrimitive(message.id)))
            is SocketMessage.Error -> {
                // `message` is for logs; apps never show it (§6.1).
                expected = JsonObject(expected - "message")
                buildJsonObject {
                    put("type", "error")
                    put("error", message.code)
                    message.id?.let { put("id", it) }
                }
            }
            is SocketMessage.Tick -> buildJsonObject {
                put("type", "tick")
                message.serverTime?.let { put("serverTime", it) }
                message.watchers?.let { put("watchers", it) }
                message.live?.let { put("live", LiveEntry.listJson(it)) }
                message.changes?.let {
                    put("changes", buildJsonObject {
                        put("from", it.from)
                        put("items", ChangeItem.listJson(it.items))
                        put("cursor", it.cursor)
                        put("more", it.more)
                    })
                }
                message.status?.let { put("status", it.toJson()) }
            }
            SocketMessage.Unknown -> return fail("$e: the core doesn't know this frame")
        }
        assertEquals("$e: what the core read", expected.withoutNullMembers(), read.withoutNullMembers())
    }

    // Helpers

    private fun proof(v: JsonElement?): ProProof = when (val platform = v.text("platform")) {
        "apple" -> ProProof.Apple(v.text("signedTransaction")!!)
        "google" -> ProProof.Google(v.text("productId")!!, v.text("purchaseToken")!!)
        else -> throw AssertionError("unknown platform $platform")
    }

    private fun upload(body: JsonElement): UploadRequest {
        val o = body.jsonObject
        val chunks = (o["chunks"] as? JsonArray ?: JsonArray(emptyList())).map { UploadRequest.Chunk(it.number("start")!!, it.text("rec")!!) }
        val live = when (val l = o["live"]) {
            null -> UploadRequest.LiveField.Unchanged
            is JsonNull -> UploadRequest.LiveField.Stopped
            else -> UploadRequest.LiveField.Position(UploadRequest.Live(l.number("at")!!, l.text("rec")!!))
        }
        return UploadRequest(chunks, live)
    }

    companion object {
        /** Endpoints the apps never call: Apple's and Google's notifications, the health check, and the developer's
         *  admin endpoints (A1–A6). */
        private val notForApps = setOf("E27", "E28", "E29", "A1", "A2", "A3", "A4", "A5", "A6")

        /** Every code in §6.2. The file must hold an example of each, so the cores are checked against what the server
         *  really sends for it. */
        private val errorCodes = setOf(
            "bad_request", "bad_record", "unauthorized", "pro_invalid", "pro_inactive", "forbidden", "not_found",
            "invite_not_found", "group_not_found", "method_not_allowed", "group_exists", "member_exists", "pin_exists",
            "group_paused", "owner_must_hand_over", "not_allowed", "too_many_groups", "terms_outdated", "removed",
            "group_deleted", "pin_deleted", "too_large", "unsupported_media_type", "bad_time", "pin_expired",
            "upgrade_required", "rate_limited", "internal", "store_unavailable",
        )

        /** Examples whose body the server recorded as null because it was raw bytes: a body over 64 KiB and one that
         *  isn't JSON. The core sends E1-created's body; only the answer is checked against them. */
        private val rawBodies = setOf("E1-too_large", "E1-unsupported_media_type")

        /** Requests the core never makes (PATCH; a WebSocket path without `Upgrade`, which only the app's socket layer
         *  could send): only their error answers are read. */
        private val notSentByTheCore = setOf("E2-method_not_allowed", "E26-upgrade_required")

        /** The body as the server reads it (§6.5): defaults filled in, so two bodies that mean the same compare equal.
         *  An E18 body (or WebSocket upload) without `chunks` has none; an E25 report without `comment` has an empty
         *  one, and its `content` keeps only string values. */
        fun normalised(endpoint: String, body: JsonElement): JsonElement {
            if (body !is JsonObject) return body
            val o = body.toMutableMap()
            if (endpoint == "E18" || (endpoint == "E26" && o["type"] == JsonPrimitive("upload"))) {
                if (o["chunks"] == null) o["chunks"] = JsonArray(emptyList())
            }
            if (endpoint == "E25") {
                if (o["comment"] == null || o["comment"] is JsonNull) o["comment"] = JsonPrimitive("")
                o["content"] = JsonObject((o["content"] as? JsonObject ?: JsonObject(emptyMap())).filterValues { it !is JsonNull })
            }
            return JsonObject(o)
        }

        /** The path with its query in a fixed order and E19's defaults (`since` 0, `limit` 500) filled in. */
        fun normalisedPath(path: String): String {
            val base = path.substringBefore('?')
            val query = APIExample.queryOf(path).toMutableMap()
            if (base.endsWith("/changes")) {
                query.putIfAbsent("since", "0")
                query.putIfAbsent("limit", "500")
            }
            return if (query.isEmpty()) base else base + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        }
    }
}
