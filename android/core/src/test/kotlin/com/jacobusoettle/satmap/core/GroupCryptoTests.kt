package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared group vectors (docs/groups/SPEC.md §3.6, §4.8; made by docs/groups/vectors.py), read from
 *  Tests/SatMapCoreTests/Fixtures/groups/vectors.json, which the iPhone core's tests (GroupCryptoTests.swift and the
 *  other Group*Tests.swift) check too. */
class GroupVectors(private val root: JsonObject) {
    class Record(val key: String, val aad: String, val nonceHex: String, val plainHex: String, val envelopeHex: String, val envelope: String)
    class Chunk(val windowStart: Long, val points: List<TrailPoint>, val plainHex: String, val bodyHex: String?)
    class Live(val position: LivePosition, val plainHex: String?)
    class Fix(val lat: Double, val lon: Double, val accuracy: Double?, val course: Double?, val speed: Double?, val heading: Double?, val time: Double)
    class ParseCase(val text: String, val result: String, val token: String?, val groupIdHex: String?, val groupKeyHex: String?)
    class TextCase(val field: String, val input: String, val output: String?)
    class WordCase(val text: String, val allows: Boolean, val masked: String)

    private fun obj(key: String) = root[key]!!.jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.optStr(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonElement?.optInt() = this?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
    private fun JsonElement?.optDouble() = this?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    val inputs = obj("inputs")
    fun input(key: String): String = inputs.str(key)
    val windowStart: Long = inputs["windowStart"]!!.jsonPrimitive.long

    val subkeyInfo: Map<String, String> = obj("subkeyInfo").mapValues { it.value.jsonPrimitive.content }
    val subkeys: Map<String, String> = obj("subkeys").mapValues { it.value.jsonPrimitive.content }
    val joinProof: String = obj("hashes").str("joinProof")

    val inviteToken: String = obj("invite").str("token")
    val inviteDomain: String = obj("invite").str("domain")
    val inviteUrl: String = obj("invite").str("url")
    val inviteSchemeUrl: String = obj("invite").str("schemeUrl")

    val records: Map<String, Record> = obj("records").mapValues { (_, v) ->
        val r = v.jsonObject
        Record(r.str("key"), r.str("aad"), r.str("nonceHex"), r.str("plainHex"), r.str("envelopeHex"), r.str("envelope"))
    }

    val writers = obj("jsonWriters")

    private fun chunk(key: String): Chunk {
        val c = obj(key)
        val points = c["points"]!!.jsonArray.map {
            val p = it.jsonObject
            TrailPoint(p["time"]!!.jsonPrimitive.long, p["lat"]!!.jsonPrimitive.int, p["lon"]!!.jsonPrimitive.int,
                       p["startsSegment"]!!.jsonPrimitive.boolean)
        }
        return Chunk(c["windowStart"]!!.jsonPrimitive.long, points, c.str("plainHex"), c.optStr("bodyHex"))
    }

    private fun live(o: JsonObject) = Live(
        LivePosition(o["time"]!!.jsonPrimitive.long, o["lat"]!!.jsonPrimitive.int, o["lon"]!!.jsonPrimitive.int,
                     o["accuracy"].optInt(), o["heading"].optInt(), o["speed"].optInt()),
        o.optStr("plainHex"),
    )

    val trailChunk = chunk("trailChunk")
    val trailChunkDeflated = chunk("trailChunkDeflated")
    val live = live(obj("live"))
    val liveUnknowns = live(obj("liveUnknowns"))
    val pinCreatedAt: Long = obj("pin")["pinCreatedAt"]!!.jsonPrimitive.long
    val pinExpiresAt: Long = obj("pin")["pinExpiresAt"]!!.jsonPrimitive.long
    val pinJson: String = obj("pin").str("json")

    val mustFail = obj("mustFail")
    fun mustFail(key: String): String = mustFail.str(key)
    val badBase64url: List<String> = mustFail["badBase64url"]!!.jsonArray.map { it.jsonPrimitive.content }
    val nonCanonicalIds: List<String> = mustFail["nonCanonicalIds"]!!.jsonArray.map { it.jsonPrimitive.content }
    val chunkMustFail: Map<String, String> = mustFail["chunkPlainHex"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    val coordinates: List<Pair<Double, Int>> = root["coordinates"]!!.jsonArray.map {
        it.jsonObject["deg"]!!.jsonPrimitive.double to it.jsonObject["e6"]!!.jsonPrimitive.int
    }
    val varints: List<Pair<Long, String>> = root["varints"]!!.jsonArray.map {
        it.jsonObject["value"]!!.jsonPrimitive.long to it.jsonObject.str("hex")
    }
    val zigzag: List<Pair<Int, Long>> = root["zigzag"]!!.jsonArray.map {
        it.jsonObject["value"]!!.jsonPrimitive.int to it.jsonObject["zigzag"]!!.jsonPrimitive.long
    }

    val inviteParseDomain: String = obj("inviteParse").str("domain")
    val inviteParse: List<ParseCase> = obj("inviteParse")["cases"]!!.jsonArray.map {
        val c = it.jsonObject
        ParseCase(c.str("text"), c.str("result"), c.optStr("token"), c.optStr("groupIdHex"), c.optStr("groupKeyHex"))
    }
    val groupText: List<TextCase> = root["groupText"]!!.jsonArray.map {
        val c = it.jsonObject
        TextCase(c.str("field"), c.str("input"), c.optStr("output"))
    }
    val wordList: String = obj("wordFilter").str("list")
    val wordCases: List<WordCase> = obj("wordFilter")["cases"]!!.jsonArray.map {
        val c = it.jsonObject
        WordCase(c.str("text"), c["allows"]!!.jsonPrimitive.boolean, c.str("masked"))
    }
    val ago: List<Pair<Long, String>> = root["ago"]!!.jsonArray.map { it.jsonObject["seconds"]!!.jsonPrimitive.long to it.jsonObject.str("text") }
    val initials: List<Pair<String, String>> = root["initials"]!!.jsonArray.map { it.jsonObject.str("name") to it.jsonObject.str("initials") }
    val liveRounding: List<Pair<Fix, LivePosition>> = root["liveRounding"]!!.jsonArray.map {
        val f = it.jsonObject["fix"]!!.jsonObject
        Fix(f["lat"]!!.jsonPrimitive.double, f["lon"]!!.jsonPrimitive.double, f["accuracy"].optDouble(), f["course"].optDouble(),
            f["speed"].optDouble(), f["heading"].optDouble(), f["time"]!!.jsonPrimitive.double) to live(it.jsonObject["live"]!!.jsonObject).position
    }

    companion object {
        fun load(): GroupVectors = GroupVectors(Json.parseToJsonElement(fixture("groups/vectors.json").toString(Charsets.UTF_8)).jsonObject)
    }
}

/** The bytes of hex digits (the tests' `unhex`). */
fun unhex(text: String): ByteArray = bytesFromHex(text)

/** Crypto (docs/groups/SPEC.md §3): subkeys, every record vector, and the cases that must fail, as the iPhone core's
 *  GroupCryptoTests. */
class GroupCryptoTests {
    private val v = GroupVectors.load()
    private val groupKey get() = unhex(v.input("groupKeyHex"))
    private val groupId get() = unhex(v.input("groupIdHex"))

    @Test
    fun testInputsAreBase64URL() {
        assertEquals(v.input("groupKey"), Base64URL.encode(groupKey))
        assertEquals(v.input("groupId"), Base64URL.encode(groupId))
        assertEquals(v.input("memberId"), Base64URL.encode(unhex(v.input("memberIdHex"))))
        assertEquals(v.input("pinId"), Base64URL.encode(unhex(v.input("pinIdHex"))))
        assertEquals(v.input("pass"), Base64URL.encode(unhex(v.input("passHex"))))
        assertArrayEquals(groupKey, Base64URL.decode(v.input("groupKey")))
        assertArrayEquals(unhex(v.input("memberIdHex")), Base64URL.decode(v.input("memberId"), count = 16))
        assertNull(Base64URL.decode(v.input("memberId"), count = 32))
        assertArrayEquals(ByteArray(0), Base64URL.decode(""))
        for (n in 0 until 40) {
            val data = ByteArray(n) { ((it * 37 + 11) % 256).toByte() }
            val text = Base64URL.encode(data)
            assertFalse(text.contains("="))
            assertArrayEquals(data, Base64URL.decode(text))
        }
        // Non-ASCII is outside the alphabet too.
        assertNull(Base64URL.decode("oKGio6SlpqeoqaqrrK2uré"))
    }

    @Test
    fun testSubkeysFromTheirInfo() {
        val prefix = "satmap.group.v1|key|"
        assertEquals(v.subkeys.keys, v.subkeyInfo.keys)
        for ((name, info) in v.subkeyInfo) {
            assertTrue(name, info.startsWith(prefix))
            val purpose = info.removePrefix(prefix)
            assertEquals(name, v.subkeys[name], hex(GroupCrypto.subkey(groupKey, groupId, purpose)))
        }
    }

    @Test
    fun testGroupKeys() {
        val keys = GroupKeys(groupKey = groupKey, groupId = groupId)
        assertEquals(v.input("groupId"), keys.groupId)
        assertEquals(v.subkeys["group"], hex(keys.group))
        assertEquals(v.subkeys["member"], hex(keys.member))
        assertEquals(v.subkeys["pin"], hex(keys.pin))
        assertEquals(v.subkeys["join"], hex(keys.joinProof))
        assertEquals(v.joinProof, Base64URL.encode(keys.joinProof))
        assertEquals(v.subkeys["trail"], hex(keys.trail(v.input("memberId"))))
        assertEquals(v.subkeys["live"], hex(keys.live(v.input("memberId"))))
        // Cached, and per member.
        assertEquals(v.subkeys["trail"], hex(keys.trail(v.input("memberId"))))
        assertFalse(keys.trail(v.input("pinId")).contentEquals(keys.trail(v.input("memberId"))))
        assertFalse(keys.live(v.input("memberId")).contentEquals(keys.trail(v.input("memberId"))))
    }

    @Test
    fun testAssociatedData() {
        val g = v.input("groupId")
        val m = v.input("memberId")
        val p = v.input("pinId")
        val w = v.windowStart
        assertEquals(v.records["groupProfile"]?.aad, GroupAAD.group(g))
        assertEquals(v.records["memberProfile"]?.aad, GroupAAD.member(g, m))
        assertEquals(v.records["trailChunk"]?.aad, GroupAAD.trail(g, m, start = w))
        assertEquals(v.records["live"]?.aad, GroupAAD.live(g, m, at = v.live.position.time))
        assertEquals(v.records["liveUnknowns"]?.aad, GroupAAD.live(g, m, at = v.liveUnknowns.position.time))
        assertEquals(v.records["pinAnimal"]?.aad, GroupAAD.pin(g, pinId = p, memberId = m, createdAt = v.pinCreatedAt, expiresAt = v.pinExpiresAt))
        assertEquals(v.records["pinWater"]?.aad, GroupAAD.pin(g, pinId = p, memberId = m, createdAt = v.pinCreatedAt, expiresAt = null))
        assertEquals(v.mustFail("trailWrongWindowAad"), GroupAAD.trail(g, m, start = w + 600))
    }

    @Test
    fun testSealsAndOpensEveryRecord() {
        assertEquals(12, v.records.size)
        for ((name, r) in v.records) {
            val key = unhex(v.subkeys[r.key]!!)
            val sealed = GroupCrypto.seal(unhex(r.plainHex), key = key, aad = r.aad, nonce = unhex(r.nonceHex))
            assertEquals(name, r.envelopeHex, hex(sealed))
            assertEquals(name, r.envelope, Base64URL.encode(sealed))
            assertEquals(name, r.plainHex, hex(GroupCrypto.open(unhex(r.envelopeHex), key = key, aad = r.aad)))
            assertArrayEquals(name, unhex(r.plainHex), GroupCrypto.open(Base64URL.decode(r.envelope)!!, key = key, aad = r.aad))
        }
        assertEquals(140, unhex(v.records["pinAnimal"]!!.envelopeHex).size)
        assertEquals(157, unhex(v.records["pinAnimalPadded"]!!.envelopeHex).size)
    }

    @Test
    fun testRandomNonces() {
        val key = GroupRandom.bytes(32)
        val plain = "Kruger trip".toByteArray()
        val a = GroupCrypto.seal(plain, key = key, aad = "x")
        val b = GroupCrypto.seal(plain, key = key, aad = "x")
        assertEquals(1 + 12 + plain.size + 16, a.size)
        assertEquals(0x01.toByte(), a[0])
        assertFalse(a.copyOfRange(1, 13).contentEquals(b.copyOfRange(1, 13)))
        assertArrayEquals(plain, GroupCrypto.open(a, key = key, aad = "x"))
        assertArrayEquals(ByteArray(0), GroupCrypto.open(GroupCrypto.seal(ByteArray(0), key = key, aad = ""), key = key, aad = ""))
        assertFalse(GroupRandom.bytes(16).contentEquals(GroupRandom.bytes(16)))
        assertEquals(22, GroupRandom.id().length)
        assertEquals(16, Base64URL.decode(GroupRandom.id(), count = 16)?.size)
    }

    @Test
    fun testMustFail() {
        val trail = v.records["trailChunk"]!!
        val key = unhex(v.subkeys["trail"]!!)
        fun failure(envelope: ByteArray, aad: String): GroupCrypto.Failure? = try {
            GroupCrypto.open(envelope, key = key, aad = aad)
            null
        } catch (e: GroupCrypto.Failure) {
            e
        }
        assertTrue(failure(unhex(v.mustFail("tamperedTrailEnvelopeHex")), trail.aad) is GroupCrypto.Failure.Unreadable)
        assertTrue(failure(unhex(trail.envelopeHex), v.mustFail("trailWrongWindowAad")) is GroupCrypto.Failure.Unreadable)
        assertTrue(failure(unhex(v.mustFail("unknownVersionEnvelopeHex")), trail.aad) is GroupCrypto.Failure.UnsupportedVersion)
        assertEquals(28, unhex(v.mustFail("shortEnvelopeHex")).size)
        assertTrue(failure(unhex(v.mustFail("shortEnvelopeHex")), trail.aad) is GroupCrypto.Failure.Unreadable)
        assertTrue(failure(ByteArray(0), trail.aad) is GroupCrypto.Failure.Unreadable)
        // Another key, another member's trail key.
        assertThrows(GroupCrypto.Failure::class.java) { GroupCrypto.open(unhex(trail.envelopeHex), key = unhex(v.subkeys["live"]!!), aad = trail.aad) }
        assertThrows(GroupCrypto.Failure::class.java) { GroupCrypto.open(unhex(trail.envelopeHex), key = ByteArray(16), aad = trail.aad) }
        assertThrows(GroupCrypto.Failure::class.java) { GroupCrypto.seal(ByteArray(0), key = ByteArray(31), aad = "") }
        assertThrows(GroupCrypto.Failure::class.java) { GroupCrypto.seal(ByteArray(0), key = key, aad = "", nonce = ByteArray(11)) }
        for (text in v.badBase64url) {
            assertNull(text, Base64URL.decode(text))
        }
        // Clients may accept non-canonical ids (only the server must refuse them); they still decode to 16 bytes.
        for (text in v.nonCanonicalIds) {
            assertEquals(text, 16, Base64URL.decode(text, count = 16)?.size)
            assertNotEquals(text, Base64URL.encode(Base64URL.decode(text)!!))
        }
    }
}
