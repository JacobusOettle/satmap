package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The PNG and Terrarium tests of the Swift DecoderTests. Its KML tests (testKMLAndKMZ, testKMLStyles and the
// KMLParser line of testRejectsGarbage) go with the KML port.
class DecoderTests {
    @Test
    fun testPNGColourTypesAgainstPillow() {
        for (name in listOf("rgba", "palette", "gray")) {
            val img = PNGDecoder.decode(fixture("$name.png"))
            assertNotNull(name, img)
            img!!
            val ref = fixture("$name.rgba")
            assertEquals(37, img.width)
            assertEquals(23, img.height)
            assertArrayEquals(name, ref, img.pixels)
        }
    }

    @Test
    fun testTerrariumAgainstPillow() {
        for (name in listOf("terrarium_12_2390_2342", "terrarium_12_2391_2342")) {
            val tile = Terrarium.decode(fixture("$name.png"), id = TileID(z = 12, x = 0, y = 0))
            assertNotNull(tile)
            tile!!
            assertEquals(256, tile.size)
            val ref = Json.parseToJsonElement(fixture("$name.json").decodeToString()).jsonObject
            for ((k, v) in ref) {
                val xy = k.split(",").map { it.toInt() }
                assertEquals("$name $k", v.jsonPrimitive.double, tile.values[xy[1] * 256 + xy[0]].toDouble(), 0.001)
            }
        }
    }

    @Test
    fun testRejectsGarbage() {
        assertNull(PNGDecoder.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun testRejectsDeflateStreamCutShort() {
        // As on iOS (compression_decode_buffer can't write bytes that aren't in the input): nil, not a zero byte.
        assertNull(Inflate.raw(bytes(0x01, 0x01, 0x00, 0xFE, 0xFF), decompressedSize = 1))
        assertArrayEquals(bytes(0x41), Inflate.raw(bytes(0x01, 0x01, 0x00, 0xFE, 0xFF, 0x41), decompressedSize = 1))
        // A 4x4 black RGB PNG whose IDAT (a stored zlib stream) lacks the last byte of its checksum, so one byte of the
        // image data is missing: no image, and no Terrarium tile (not 16 heights of -32768 m).
        val idat = bytes(0x78, 0x01, 0x01, 0x34, 0x00, 0xCB, 0xFF) + ByteArray(52) + bytes(0x00, 0x34, 0x00, 0x01)
        val good = png(idat)
        val cut = png(idat.copyOf(idat.size - 1))
        assertEquals(119, cut.size)
        assertNotNull(PNGDecoder.decode(good))
        assertEquals(-32768f, Terrarium.decode(good, id = TileID(z = 12, x = 0, y = 0))!!.values[15])
        assertNull(PNGDecoder.decode(cut))
        assertNull(Terrarium.decode(cut, id = TileID(z = 12, x = 0, y = 0)))
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A 4x4 8-bit RGB PNG with the given IDAT data (the decoder doesn't check the chunks' CRCs: zeros). */
    private fun png(idat: ByteArray): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val n = data.size
            return bytes(n ushr 24, (n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF) + type.encodeToByteArray() +
                data + ByteArray(4)
        }
        return bytes(137, 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", bytes(0, 0, 0, 4, 0, 0, 0, 4, 8, 2, 0, 0, 0)) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }
}
