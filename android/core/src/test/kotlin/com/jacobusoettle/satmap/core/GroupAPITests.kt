package com.jacobusoettle.satmap.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A transport that records requests and answers with what the test queued. */
class RecordingTransport : GroupTransport {
    class Request(val method: String, val path: String, val pass: String?, val json: ByteArray?)

    val requests = mutableListOf<Request>()
    val answers = ArrayDeque<GroupResponse>()
    var error: GroupTransportError? = null

    override suspend fun request(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse {
        requests.add(Request(method, path, pass, json))
        error?.let { throw it }
        return answers.removeFirstOrNull() ?: GroupResponse(204, ByteArray(0))
    }

    fun answer(status: Int, body: String) {
        answers.addLast(GroupResponse(status, body.toByteArray()))
    }

    fun jsonBody(i: Int): JsonObject? = requests[i].json?.let { jsonObject(it) }
}

/** The JSON object in bytes (for looking at request bodies). */
fun jsonObject(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

/** The fields of a JSON text, as the API models read them. */
fun fields(json: String): GroupJSON.Fields = GroupJSON.Fields(GroupJSON.parseObject(json.toByteArray()))

/** The API models and request building (docs/groups/SPEC.md §6), checked against the spec's own examples, as the
 *  iPhone core's GroupAPITests. */
class GroupAPITests {
    @Test
    fun testStatusDecodesTheSpecExample() {
        val json = """
            {"groupId":"oKGio6SlpqeoqaqrrK2urw","status":"active","pausedReason":null,"pausedAt":null,"deleteAt":null,"pausedIntervals":[],
             "ownerId":"sLGys7S1tre4ubq7vL2-vw","memberCount":5,"profile":"AREREREREREREREREV_yTwsC5LIDzG7sS6sW_icQlP6dKzC1hZkPTH6_brQ5KAIFCwbmb1tOUemc",
             "profileSeq":1,"you":{"memberId":"sLGys7S1tre4ubq7vL2-vw","role":"owner","joinedAt":1793613000},"transfer":null,
             "pro":{"platform":"apple","productId":"pro.yearly","expiresAt":1825149600},"watchers":2,"seq":812,"serverTime":1793613622,"future":true}
            """
        val s = GroupStatusDTO.fromJson(fields(json))
        assertEquals(5, s.memberCount)
        assertEquals("owner", s.you.role)
        assertEquals(1_825_149_600L, s.pro?.expiresAt)
        assertEquals(2, s.watchers)
        assertFalse(s.isPaused)
        assertTrue(s.pauses.isEmpty())
        val paused = GroupStatusDTO.fromJson(fields(json.replace("\"status\":\"active\"", "\"status\":\"paused\"")
            .replace("\"pausedIntervals\":[]", "\"pausedIntervals\":[[100,200],[300,null]]")))
        assertTrue(paused.isPaused)
        assertEquals(listOf(100L, 300L), paused.pauses.map { it.from })
        assertEquals(listOf(200L, null), paused.pauses.map { it.to })
        // It survives the store's JSON round trip.
        assertEquals(paused, GroupStatusDTO.decode(paused.json()))
        assertEquals(s, GroupStatusDTO.decode(s.json()))
        assertNull(GroupStatusDTO.decode("{}"))
        // A wrong type is refused.
        assertThrows(Exception::class.java) { GroupStatusDTO.fromJson(fields(json.replace("\"memberCount\":5", "\"memberCount\":\"5\""))) }
        assertThrows(Exception::class.java) { GroupStatusDTO.fromJson(fields(json.replace("\"seq\":812", "\"seq\":812.5"))) }
    }

    @Test
    fun testChangeItems() {
        val json = """
            {"items":[{"kind": "group",   "seq": 1,  "profile": "e1"},
             {"kind": "member",  "seq": 2,  "memberId": "m", "role": "owner", "joinedAt": 1793613000, "profile": "e2"},
             {"kind": "chunk",   "seq": 40, "memberId": "m", "start": 1793613600, "rec": "e3"},
             {"kind": "archive", "seq": 41, "memberId": "m", "month": "2026-09", "maxChunkSeq": 39, "archivedAt": 1793581200},
             {"kind": "pin",     "seq": 42, "pinId": "p", "memberId": "m", "createdAt": 1793613900, "expiresAt": 1793657100, "rec": "e4"},
             {"kind": "pin",     "seq": 43, "pinId": "q", "memberId": "m", "createdAt": 1793613900, "expiresAt": null, "rec": "e5"},
             {"kind": "removed", "seq": 50, "what": "pin",    "id": "p"},
             {"kind": "photo",   "seq": 60, "url": "x"}]}
            """
        val items = ChangeItem.listFromJson(fields(json).array("items"))
        assertEquals(listOf(
            ChangeItem.Group(seq = 1, profile = "e1"),
            ChangeItem.Member(seq = 2, memberId = "m", role = "owner", joinedAt = 1_793_613_000, profile = "e2"),
            ChangeItem.Chunk(seq = 40, memberId = "m", start = 1_793_613_600, rec = "e3"),
            ChangeItem.Archive(seq = 41, memberId = "m", month = "2026-09", maxChunkSeq = 39, archivedAt = 1_793_581_200),
            ChangeItem.Pin(seq = 42, pinId = "p", memberId = "m", createdAt = 1_793_613_900, expiresAt = 1_793_657_100, rec = "e4"),
            ChangeItem.Pin(seq = 43, pinId = "q", memberId = "m", createdAt = 1_793_613_900, expiresAt = null, rec = "e5"),
            ChangeItem.Removed(seq = 50, what = "pin", id = "p"),
            ChangeItem.Unknown(seq = 60),
        ), items)
        assertEquals(listOf(1L, 2, 40, 41, 42, 43, 50, 60), items.map { it.seq })
        val known = items.dropLast(1)
        assertEquals(known, ChangeItem.listFromJson(GroupJSON.arrayValue(Json.parseToJsonElement(ChangeItem.listJson(known).toString()))))
        val page = ChangesPage.fromJson(fields("{\"items\":[],\"cursor\":812,\"more\":false,\"serverTime\":5}"))
        assertEquals(ChangesPage(items = emptyList(), cursor = 812, more = false, serverTime = 5), page)
        val reset = ChangesPage.fromJson(fields("{\"reset\":true,\"cursor\":0}"))
        assertTrue(reset.reset)
        assertEquals(0L, reset.cursor)
        assertEquals(reset, ChangesPage.fromJson(GroupJSON.Fields(reset.toJson())))
        val full = ChangesPage(items = known, cursor = 50, more = true, serverTime = 9)
        assertEquals(full, ChangesPage.fromJson(GroupJSON.Fields(full.toJson())))
    }

    @Test
    fun testUploadBodies() {
        val chunk = UploadRequest.Chunk(start = 1_793_613_600, rec = "AQID")
        val heartbeat = jsonObject(UploadRequest(emptyList(), UploadRequest.LiveField.Unchanged).json())
        assertEquals(listOf("chunks"), heartbeat.keys.sorted())
        val stopped = jsonObject(UploadRequest(emptyList(), UploadRequest.LiveField.Stopped).json())
        assertTrue(stopped["live"] is JsonNull)
        val full = UploadRequest(listOf(chunk), UploadRequest.LiveField.Position(UploadRequest.Live(at = 1_793_613_620, rec = "BAUG")))
        val body = jsonObject(full.json())
        assertEquals(1_793_613_620L, body["live"]!!.jsonObject["at"]!!.jsonPrimitive.long)
        assertEquals(1_793_613_600L, body["chunks"]!!.jsonArray[0].jsonObject["start"]!!.jsonPrimitive.long)
        val socket = jsonObject(full.json(socketId = 17))
        assertEquals("upload", socket["type"]!!.jsonPrimitive.content)
        assertEquals(17, socket["id"]!!.jsonPrimitive.int)
        // The fake server reads what the hub writes.
        for (request in listOf(full, UploadRequest(emptyList(), UploadRequest.LiveField.Stopped), UploadRequest(listOf(chunk), UploadRequest.LiveField.Unchanged))) {
            assertEquals(request, UploadRequest.fromJson(GroupJSON.Fields(GroupJSON.parseObject(request.json()))))
        }
        assertEquals(full.json().size - ",\"live\":{\"at\":1793613620,\"rec\":\"BAUG\"}".length,
                     UploadRequest.size(listOf(chunk), UploadRequest.LiveField.Unchanged))
    }

    @Test
    fun testSmallModels() {
        val pin = jsonObject(PinRequest(pinId = "p", createdAt = 1, expiresAt = null, rec = "r").toJson().toString().toByteArray())
        assertTrue("expiresAt is sent as null", pin["expiresAt"] is JsonNull)
        assertEquals(43_201L, PinRequest.fromJson(GroupJSON.Fields(PinRequest(pinId = "p", createdAt = 1, expiresAt = 43_201, rec = "r").toJson())).expiresAt)
        val live = LiveEntry(memberId = "m", at = 5, rec = null).toJson()
        assertTrue(live["rec"] is JsonNull)
        assertEquals(mapOf("platform" to "apple", "signedTransaction" to "jws"),
                     ProProof.Apple(signedTransaction = "jws").toJson().mapValues { it.value.jsonPrimitive.content })
        assertEquals(mapOf("platform" to "google", "productId" to "pro", "purchaseToken" to "t"),
                     ProProof.Google(productId = "pro", purchaseToken = "t").toJson().mapValues { it.value.jsonPrimitive.content })
        assertEquals(ProProof.Google(productId = "pro", purchaseToken = "t"),
                     ProProof.fromJson(fields("""{"platform":"google","productId":"pro","purchaseToken":"t"}""")))
        assertThrows(Exception::class.java) { ProProof.fromJson(fields("""{"platform":"web"}""")) }
        // Requests round trip through the fake server's readers; passes stay out of logs.
        val create = CreateGroupRequest("g", "m", "secret-pass", "j", "gp", "mp", 1, ProProof.Apple("jws"))
        assertEquals(create, CreateGroupRequest.fromJson(GroupJSON.Fields(create.toJson())))
        assertFalse(create.toString().contains("secret-pass"))
        val redeem = RedeemRequest("t", "j", "m", "secret-pass", "p", 2)
        assertEquals(redeem, RedeemRequest.fromJson(GroupJSON.Fields(redeem.toJson())))
        assertFalse(redeem.toString().contains("secret-pass"))
        val report = ReportRequest("pin", "p", "spam", "", mapOf("type" to "animal", "name" to "Thandi"))
        assertEquals(report, ReportRequest.fromJson(GroupJSON.Fields(report.toJson())))
        // Secrets: the JSON array the app's secret store keeps.
        val secret = GroupSecret(groupId = "g", memberId = "m", pass = "p", groupKey = "k")
        assertEquals(listOf(secret, secret.copy(groupId = "h")), GroupSecret.decodeAll(GroupSecret.encodeAll(listOf(secret, secret.copy(groupId = "h")))))
        assertFalse(secret.toString().contains("pass=") || secret.toString().contains("groupKey="))
        assertThrows(Exception::class.java) { GroupSecret.decodeAll("{}".toByteArray()) }
    }

    @Test
    fun testSocketMessages() {
        assertEquals("""{"type":"hello","v":1,"watching":true,"cursor":812}""", SocketMessage.hello(watching = true, cursor = 812))
        assertEquals("""{"type":"watching","on":false}""", SocketMessage.watching(false))
        assertEquals("""{"type":"ack","cursor":830}""", SocketMessage.ack(cursor = 830))
        assertEquals("ping", SocketMessage.ping)
        assertNull(SocketMessage.parse("pong"))
        assertEquals(SocketMessage.Unknown, SocketMessage.parse("""{"type":"news"}"""))
        assertEquals(SocketMessage.Uploaded(id = 17, result = UploadResult(accepted = listOf(1_793_613_600), rejected = listOf(UploadRejection(start = 1_793_000_000, error = "before_join")),
                                                                         liveAccepted = true, watchers = null, status = null, serverTime = null)),
                     SocketMessage.parse("""{"type":"uploaded","id":17,"accepted":[1793613600],"rejected":[{"start":1793000000,"error":"before_join"}],"liveAccepted":true}"""))
        assertEquals(SocketMessage.Error(id = 17, code = "group_paused"), SocketMessage.parse("""{"type":"error","id":17,"error":"group_paused","message":"…"}"""))
        assertEquals(SocketMessage.Error(id = null, code = "bad_request"), SocketMessage.parse("""{"type":"error","error":"bad_request"}"""))
        // A frame with a mistyped key isn't understood.
        assertNull(SocketMessage.parse("""{"type":"uploaded","id":"17"}"""))
        assertNull(SocketMessage.parse("""{"type":"welcome"}"""))
        val tick = SocketMessage.parse("""
            {"type":"tick","serverTime":7,"watchers":2,"live":[{"memberId":"m","at":5,"rec":null}],
             "changes":{"from":812,"items":[{"kind":"removed","seq":813,"what":"trail","id":"m"}],"cursor":813,"more":false}}
            """)
        if (tick !is SocketMessage.Tick) return fail("not a tick")
        assertEquals(7L, tick.serverTime)
        assertEquals(2, tick.watchers)
        assertEquals(listOf(LiveEntry(memberId = "m", at = 5, rec = null)), tick.live)
        assertEquals(listOf(ChangeItem.Removed(seq = 813, what = "trail", id = "m")), tick.changes?.items)
        assertNull(tick.status)
    }

    @Test
    fun testArchiveObjects() {
        val records = listOf(GroupArchive.Record(1_793_614_200, byteArrayOf(1, 2, 3)), GroupArchive.Record(1_793_613_600, ByteArray(40) { 9 }))
        val built = GroupArchive.build(records)
        assertArrayEquals("SMGA".toByteArray() + byteArrayOf(1), built.copyOf(5))
        val parsed = GroupArchive.parse(built)!!
        assertEquals(listOf(1_793_613_600L, 1_793_614_200L), parsed.map { it.start })
        assertArrayEquals(ByteArray(40) { 9 }, parsed[0].rec)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed[1].rec)
        assertEquals(0, GroupArchive.parse("SMGA".toByteArray() + byteArrayOf(1))?.size)
        assertNull(GroupArchive.parse("SMGB".toByteArray() + byteArrayOf(1)))
        assertNull(GroupArchive.parse("SMGA".toByteArray() + byteArrayOf(2)))
        assertNull(GroupArchive.parse(built.copyOf(built.size - 1)))
        assertNull(GroupArchive.parse(built + byteArrayOf(0, 0, 0)))
        // A length past the end, even one that only fits as an unsigned number.
        assertNull(GroupArchive.parse("SMGA".toByteArray() + byteArrayOf(1) + ByteArray(8) + byteArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun testRequestsAndErrors() = runBlocking {
        val t = RecordingTransport()
        val api = GroupAPI(t)
        t.answer(200, """{"items":[],"cursor":7,"more":false,"serverTime":9}""")
        api.changes("G", pass = "P", since = 7)
        t.requests.last().let {
            assertEquals("GET", it.method)
            assertEquals("/v1/groups/G/changes?since=7&limit=500", it.path)
            assertEquals("P", it.pass)
            assertNull(it.json)
        }
        api.removeMember("G", pass = "P", memberId = "me")
        assertEquals("DELETE", t.requests.last().method)
        assertEquals("/v1/groups/G/members/me", t.requests.last().path)
        api.clearTrail("G", pass = "P")
        assertEquals("/v1/groups/G/trail/me", t.requests.last().path)
        t.answer(200, """{"seq":3}""")
        api.setRole("G", pass = "P", memberId = "M", role = "admin")
        assertEquals("/v1/groups/G/members/M/role", t.requests.last().path)
        assertEquals(mapOf("role" to "admin"), t.jsonBody(t.requests.size - 1)?.mapValues { it.value.jsonPrimitive.content })
        t.answer(201, """{"inviteId":"i","token":"t","createdAt":1,"expiresAt":2}""")
        api.createInvite("G", pass = "P")
        assertArrayEquals("{}".toByteArray(), t.requests.last().json)
        // Errors: the code from the body; a body that doesn't parse gives an empty code; a 2xx that doesn't parse is
        // bad_response; transport errors pass through.
        t.answer(404, """{"error":"invite_not_found","message":"x"}""")
        try {
            api.preview(token = "t", joinProof = "j")
            fail("no error")
        } catch (e: GroupAPIError) {
            assertEquals(GroupAPIError(status = 404, code = "invite_not_found"), e)
        }
        assertEquals("/v1/invites/preview", t.requests.last().path)
        assertNull(t.requests.last().pass)
        t.answer(502, "<html>")
        try {
            api.deletePin("G", pass = "P", pinId = "p")
            fail("no error")
        } catch (e: GroupAPIError) {
            assertEquals(GroupAPIError(status = 502, code = ""), e)
        }
        t.answer(200, "[]")
        try {
            api.status("G", pass = "P")
            fail("no error")
        } catch (e: GroupAPIError) {
            assertEquals(GroupAPIError.badResponse, e.code)
        }
        t.error = GroupTransportError.Offline()
        try {
            api.live("G", pass = "P")
            fail("no error")
        } catch (e: GroupTransportError) {
            assertTrue(e is GroupTransportError.Offline)
        }
    }
}
