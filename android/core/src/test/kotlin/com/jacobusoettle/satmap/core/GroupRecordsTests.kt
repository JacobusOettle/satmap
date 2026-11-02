package com.jacobusoettle.satmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Group payloads (docs/groups/SPEC.md §4): the same vectors as the iPhone core's GroupRecordsTests. */
class GroupRecordsTests {
    private val v = GroupVectors.load()

    // Trail chunks

    @Test
    fun testChunkEncodesToTheVector() {
        val c = v.trailChunk
        assertEquals(c.plainHex, hex(TrailChunk.encode(c.points, windowStart = c.windowStart, compress = false)))
        // A short body is never deflated.
        assertEquals(c.plainHex, hex(TrailChunk.encode(c.points, windowStart = c.windowStart, compress = true)))
        assertEquals(c.points, TrailChunk.decode(unhex(c.plainHex), windowStart = c.windowStart))
    }

    @Test
    fun testDeflatedChunkDecodes() {
        val c = v.trailChunkDeflated
        assertEquals(40, c.points.size)
        assertEquals(c.points, TrailChunk.decode(unhex(c.plainHex), windowStart = c.windowStart))
        // The body before compression is what an encoding-0 writer produces.
        val plain = TrailChunk.encode(c.points, windowStart = c.windowStart, compress = false)
        assertEquals("0100" + (c.bodyHex ?: ""), hex(plain))
    }

    @Test
    fun testChunkMustFailCases() {
        assertEquals(setOf("deflateLongerThanDeclared", "deflateTrailingInput", "deflateShorterThanDeclared"), v.chunkMustFail.keys)
        for ((name, plain) in v.chunkMustFail) {
            assertThrows(name, GroupRecordError::class.java) { TrailChunk.decode(unhex(plain), windowStart = v.trailChunkDeflated.windowStart) }
        }
    }

    @Test
    fun testLongChunkIsDeflatedAndReadBack() {
        val start = 1_793_613_600L
        val points = (0 until 600).map { i ->
            TrailPoint(time = start + i, lat = -24_996_352 + i * 3, lon = 31_592_345 - i * 2, startsSegment = i % 100 == 0)
        }
        val plain = TrailChunk.encode(points, windowStart = start, compress = true)
        assertArrayEquals(byteArrayOf(0x01, 0x01), plain.copyOf(2))
        val raw = TrailChunk.encode(points, windowStart = start, compress = false)
        assertLessThan(plain.size, raw.size)
        assertEquals(points, TrailChunk.decode(plain, windowStart = start))
        assertEquals(points, TrailChunk.decode(raw, windowStart = start))
    }

    @Test
    fun testChunkLimits() {
        val start = 1_793_613_600L
        val p = TrailPoint(time = start, lat = 0, lon = 0, startsSegment = true)
        fun encodeFails(points: List<TrailPoint>) {
            assertThrows(GroupRecordError::class.java) { TrailChunk.encode(points, windowStart = start, compress = false) }
        }
        encodeFails(emptyList())
        encodeFails(List(601) { p })
        TrailChunk.encode(List(600) { p }, windowStart = start, compress = false)
        encodeFails(listOf(TrailPoint(time = start + 600, lat = 0, lon = 0, startsSegment = false)))
        encodeFails(listOf(TrailPoint(time = start - 1, lat = 0, lon = 0, startsSegment = false)))
        encodeFails(listOf(TrailPoint(time = start + 5, lat = 0, lon = 0, startsSegment = false), p))
        encodeFails(listOf(TrailPoint(time = start, lat = 90_000_001, lon = 0, startsSegment = false)))
        encodeFails(listOf(TrailPoint(time = start, lat = 0, lon = -180_000_001, startsSegment = false)))
        TrailChunk.encode(listOf(TrailPoint(time = start + 599, lat = -90_000_000, lon = 180_000_000, startsSegment = false)), windowStart = start, compress = false)

        fun rejects(bytes: ByteArray, message: String) {
            assertThrows(message, GroupRecordError::class.java) { TrailChunk.decode(bytes, windowStart = start) }
        }
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val good = TrailChunk.encode(listOf(p), windowStart = start, compress = false)
        assertArrayEquals(bytes(0x01, 0x00, 0x01, 0x01, 0x00, 0x00), good)
        rejects(bytes(0x01, 0x00, 0x00), "no points")
        rejects(bytes(0x01, 0x00, 0xD9, 0x04) + ByteArray(601 * 3), "601 points")
        rejects(good + bytes(0x00), "a byte left over")
        rejects(good.copyOf(good.size - 1), "cut short")
        rejects(bytes(0x02) + good.copyOfRange(1, good.size), "another format")
        rejects(bytes(0x01, 0x02) + good.copyOfRange(2, good.size), "another encoding")
        rejects(bytes(0x01), "no encoding")
        // dt of 600 leaves the window; a latitude delta past 90°.
        rejects(bytes(0x01, 0x00, 0x01, 0xB0, 0x09, 0x00, 0x00), "time outside the window")
        val lat = ByteArrayOutputStream()
        GroupVarint.append(GroupVarint.zigzag(90_000_001), lat)
        rejects(bytes(0x01, 0x00, 0x01, 0x00) + lat.toByteArray() + bytes(0x00), "latitude out of range")
        // Two points whose sum leaves the range though each delta is fine.
        val big = ByteArrayOutputStream()
        big.write(bytes(0x01, 0x00, 0x02, 0x00))
        GroupVarint.append(GroupVarint.zigzag(90_000_000), big)
        big.write(0x00)
        big.write(0x00)
        GroupVarint.append(GroupVarint.zigzag(1), big)
        big.write(0x00)
        rejects(big.toByteArray(), "latitude sum out of range")
        // Encoding 1 with a declared length over 8192, or 0.
        val over = ByteArrayOutputStream()
        over.write(bytes(0x01, 0x01))
        GroupVarint.append(8193, over)
        rejects(over.toByteArray() + Deflate.raw(ByteArray(8193))!!, "body over 8192")
        rejects(bytes(0x01, 0x01, 0x00, 0x03, 0x00), "declared 0")
        // Encoding 0 with a body over 8192 bytes.
        rejects(bytes(0x01, 0x00) + good.copyOfRange(2, good.size) + ByteArray(8190), "body over 8192 bytes")
    }

    @Test
    fun testWindowStart() {
        assertEquals(1_793_613_600L, TrailChunk.windowStart(1_793_613_600))
        assertEquals(1_793_613_600L, TrailChunk.windowStart(1_793_613_605))
        assertEquals(1_793_613_600L, TrailChunk.windowStart(1_793_614_199))
        assertEquals(1_793_614_200L, TrailChunk.windowStart(1_793_614_200))
        assertEquals(0L, TrailChunk.windowStart(0))
    }

    @Test
    fun testDeflateRoundTripAndExactness() {
        val data = ByteArray(5000) { ((it * 7) % 13).toByte() }
        val deflated = Deflate.raw(data)!!
        assertLessThan(deflated.size, data.size)
        assertArrayEquals(data, Deflate.inflateExact(deflated, size = data.size))
        assertNull(Deflate.inflateExact(deflated, size = data.size - 1))
        assertNull(Deflate.inflateExact(deflated, size = data.size + 1))
        assertNull(Deflate.inflateExact(deflated + byteArrayOf(0), size = data.size))
        assertNull(Deflate.inflateExact(deflated.copyOf(deflated.size - 1), size = data.size))
        assertNull(Deflate.inflateExact(byteArrayOf(-1, -1), size = 2))
        assertNull(Deflate.inflateExact(ByteArray(0), size = 0))
        assertNull(Deflate.raw(ByteArray(0)))
        // Random bytes don't shrink but still round trip.
        val noise = GroupRandom.bytes(3000)
        assertArrayEquals(noise, Deflate.inflateExact(Deflate.raw(noise)!!, size = noise.size))
    }

    // Live

    @Test
    fun testLiveVectors() {
        for (live in listOf(v.live, v.liveUnknowns)) {
            val plain = live.plainHex!!
            assertEquals(plain, hex(live.position.encoded()))
            assertEquals(live.position, LivePosition.decode(unhex(plain)))
        }
        assertNull(v.liveUnknowns.position.accuracy)
    }

    @Test
    fun testLiveRounding() {
        assertEquals(6, v.liveRounding.size)
        for ((f, live) in v.liveRounding) {
            val p = LiveFix.position(lat = f.lat, lon = f.lon, accuracy = f.accuracy, course = f.course, speed = f.speed,
                                     heading = f.heading, time = f.time)
            assertEquals("${f.time}", live, p)
        }
    }

    @Test
    fun testLiveRejections() {
        val good = v.live.position.encoded()
        assertThrows(GroupRecordError::class.java) { LivePosition.decode(good.copyOf(good.size - 1)) }
        assertThrows(GroupRecordError::class.java) { LivePosition.decode(good + byteArrayOf(0)) }
        val other = good.copyOf().also { it[0] = 2 }
        assertThrows(GroupRecordError.UnsupportedVersion::class.java) { LivePosition.decode(other) }
        val heading = good.copyOf().also {
            it[20] = 104 // 360
            it[21] = 1
        }
        assertThrows(GroupRecordError::class.java) { LivePosition.decode(heading) }
        val lat = good.copyOf().also {
            // 90 000 001
            it[10] = 0x81.toByte()
            it[11] = 0x4A
            it[12] = 0x5D
            it[13] = 0x05
        }
        assertThrows(GroupRecordError::class.java) { LivePosition.decode(lat) }
        // Unknown flag bits are ignored; a clear flag hides its field.
        val flags = good.copyOf().also { it[1] = 0x08 }
        val p = LivePosition.decode(flags)
        assertNull(p.accuracy)
        assertNull(p.heading)
        assertNull(p.speed)
        assertEquals(v.live.position.lat, p.lat)
    }

    // JSON records

    @Test
    fun testWritersProduceTheVectorBytes() {
        val w = v.writers
        fun field(record: String, key: String) = (w[record] as kotlinx.serialization.json.JsonObject)[key]!!.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        val group = GroupProfile(name = field("groupProfile", "name")).json()
        assertEquals(field("groupProfile", "json"), group.toString(Charsets.UTF_8))
        assertEquals(0, group.size % 32)
        val member = MemberProfile(name = field("memberProfile", "name"), colour = field("memberProfile", "colour").toInt()).json()
        assertEquals(field("memberProfile", "json"), member.toString(Charsets.UTF_8))
        val pin = PinRecord(type = field("pinAnimal", "type"), animal = field("pinAnimal", "animal"), note = null,
                            lat = field("pinAnimal", "lat").toInt(), lon = field("pinAnimal", "lon").toInt()).json()
        assertEquals(field("pinAnimal", "json"), pin.toString(Charsets.UTF_8))
        assertEquals(128, pin.size)
        // The padded writer output is also what the padded record vectors seal.
        assertEquals(v.records["groupProfilePadded"]?.plainHex, hex(group))
        assertEquals(v.records["memberProfilePadded"]?.plainHex, hex(member))
        assertEquals(v.records["pinAnimalPadded"]?.plainHex, hex(pin))
        assertEquals(GroupProfile(name = "Kruger trip"), GroupProfile.decode(group))
        assertEquals(MemberProfile(name = "Thandi", colour = 3), MemberProfile.decode(member))
        assertEquals(PinRecord(type = "animal", animal = "Rhino", note = null, lat = -24_996_352, lon = 31_592_345), PinRecord.decode(pin))
    }

    @Test
    fun testPaddingAndEscaping() {
        for (name in listOf("A", "Zoë 🦏", "x".repeat(30), "Quote \" and \\ / slash")) {
            val json = MemberProfile(name = name, colour = 11).json()
            assertEquals(name, 0, json.size % 32)
            assertEquals(name, MemberProfile.decode(json).name)
        }
        val note = PinRecord(type = "danger", animal = null, note = "Loose gravel\non the bend", lat = 1, lon = -1).json()
        assertEquals(0, note.size % 64)
        assertTrue(note.toString(Charsets.UTF_8).startsWith("{\"lat\":1,\"lon\":-1,\"note\":\"Loose gravel\\non the bend\",\"pad\":\""))
        assertEquals("Loose gravel\non the bend", PinRecord.decode(note).note)
        // An empty note is left out.
        assertFalse(PinRecord(type = "water", animal = null, note = "", lat = 0, lon = 0).json().toString(Charsets.UTF_8).contains("note"))
        assertEquals("\"a\\u0001b/é\"", GroupJSON.quoted("a\u0001b/é"))
    }

    @Test
    fun testEveryRecordDecodes() {
        fun plain(name: String) = unhex(v.records[name]!!.plainHex)
        assertEquals(GroupProfile(name = "Kruger trip"), GroupProfile.decode(plain("groupProfile")))
        assertEquals(GroupProfile(name = "Kruger trip"), GroupProfile.decode(plain("groupProfilePadded")))
        assertEquals(MemberProfile(name = "Thandi", colour = 3), MemberProfile.decode(plain("memberProfile")))
        assertEquals(MemberProfile(name = "Thandi", colour = 3), MemberProfile.decode(plain("memberProfilePadded")))
        val zoe = MemberProfile.decode(plain("memberProfileUnicode"))
        assertEquals(MemberProfile(name = "Zoë 🦏", colour = 0), zoe)
        assertEquals(5, GroupText.codePoints(zoe.name))
        assertEquals(9, zoe.name.toByteArray().size)
        val animal = PinRecord.decode(plain("pinAnimal"))
        assertEquals(PinRecord(type = "animal", animal = "Rhino", note = "Two adults near the water hole", lat = -24_996_352, lon = 31_592_345), animal)
        assertEquals(PinType.animal, animal.knownType)
        assertEquals(animal, PinRecord.decode(v.pinJson.toByteArray()))
        assertEquals(PinRecord(type = "water", animal = null, note = null, lat = -24_990_001, lon = 31_600_000), PinRecord.decode(plain("pinWater")))
        assertEquals(PinRecord(type = "animal", animal = "Rhino", note = null, lat = -24_996_352, lon = 31_592_345), PinRecord.decode(plain("pinAnimalPadded")))
        assertEquals(GroupPolicy.animalPinLifetime, v.pinExpiresAt - v.pinCreatedAt)
    }

    @Test
    fun testReadersRejectWrongTypes() {
        fun pin(s: String) = PinRecord.decode(s.toByteArray())
        for (bad in listOf(
            """{"lat":"1","lon":2,"type":"water","v":1}""",
            """{"lat":1.5,"lon":2,"type":"water","v":1}""",
            """{"lon":2,"type":"water","v":1}""",
            """{"lat":1,"lon":2,"type":7,"v":1}""",
            """{"lat":1,"lon":2,"type":"animal","v":1}""",
            """{"animal":"","lat":1,"lon":2,"type":"animal","v":1}""",
            """{"lat":90000001,"lon":2,"type":"water","v":1}""",
            """{"lat":3000000000,"lon":2,"type":"water","v":1}""",
            """{"lat":1,"lon":2,"note":"${"x".repeat(141)}","type":"water","v":1}""",
            "not json",
            "[1]",
        )) {
            assertThrows(bad, GroupRecordError::class.java) { pin(bad) }
        }
        assertThrows(GroupRecordError.UnsupportedVersion::class.java) { pin("""{"lat":1,"lon":2,"type":"water","v":2}""") }
        // Invalid UTF-8 is refused.
        assertThrows(GroupRecordError::class.java) {
            PinRecord.decode("""{"lat":1,"lon":2,"type":"water","v":1,"note":"""".toByteArray() + byteArrayOf(-1) + "\"}".toByteArray())
        }
        // Any key order, unknown keys and an unknown type are fine; a whole number written with a fraction too.
        val other = pin("""{"v":1,"photo":"no","type":"campsite","lon":2,"lat":1.0}""")
        assertEquals("campsite", other.type)
        assertNull(other.knownType)
        assertThrows(GroupRecordError::class.java) { MemberProfile.decode("""{"colour":"3","name":"Thandi","v":1}""".toByteArray()) }
        assertThrows(GroupRecordError::class.java) { MemberProfile.decode("""{"colour":3,"name":"","v":1}""".toByteArray()) }
        assertThrows(GroupRecordError::class.java) { MemberProfile.decode("""{"colour":3,"name":true,"v":1}""".toByteArray()) }
        assertEquals(14, MemberProfile.decode("""{"colour":14,"name":"Thandi","v":1}""".toByteArray()).colour)
        assertThrows(GroupRecordError::class.java) { GroupProfile.decode("""{"name":"Kruger trip"}""".toByteArray()) }
        assertThrows(GroupRecordError::class.java) { GroupProfile.decode("""{"name":"${"a".repeat(41)}","v":1}""".toByteArray()) }
    }

    // Numbers

    @Test
    fun testCoordinates() {
        assertEquals(13, v.coordinates.size)
        for ((deg, e6) in v.coordinates) {
            assertEquals("$deg", e6, MicroDegrees.from(deg))
            assertEquals(e6.toDouble() / 1e6, MicroDegrees.degrees(e6), 0.0)
        }
        assertEquals(0, MicroDegrees.from(Double.NaN))
        assertEquals(Int.MAX_VALUE, MicroDegrees.from(1e9))
    }

    @Test
    fun testVarints() {
        for ((value, expected) in v.varints) {
            val out = ByteArrayOutputStream()
            GroupVarint.append(value, out)
            assertEquals(expected, hex(out.toByteArray()))
            val cursor = GroupVarint.Cursor()
            assertEquals(value, GroupVarint.read(unhex(expected), cursor))
            assertEquals(out.size(), cursor.index)
        }
        fun read(vararg b: Int) = GroupVarint.read(ByteArray(b.size) { b[it].toByte() }, GroupVarint.Cursor())
        assertNull("2^32", read(0xFF, 0xFF, 0xFF, 0xFF, 0x10))
        assertNull("a sixth byte", read(0x80, 0x80, 0x80, 0x80, 0x80, 0x01))
        assertNull("cut short", read(0x80))
        assertNull(read())
    }

    @Test
    fun testZigzag() {
        for ((value, zigzag) in v.zigzag) {
            assertEquals(zigzag, GroupVarint.zigzag(value))
            assertEquals(value, GroupVarint.unzigzag(zigzag))
        }
    }
}
