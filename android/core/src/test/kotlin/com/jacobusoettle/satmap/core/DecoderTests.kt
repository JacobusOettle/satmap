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
}
