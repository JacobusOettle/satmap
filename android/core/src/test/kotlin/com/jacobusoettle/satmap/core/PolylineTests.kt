package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PolylineTests {
    @Test
    fun testGoogleExample() {
        // From Google's polyline format documentation.
        val pts = listOf(GeoPoint(lat = 38.5, lon = -120.2), GeoPoint(lat = 40.7, lon = -120.95), GeoPoint(lat = 43.252, lon = -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(pts))
        assertEquals(pts, Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test
    fun testRoundTripAndGarbage() {
        val pts = listOf(GeoPoint(lat = -24.99512, lon = 31.59212), GeoPoint(lat = -24.99511, lon = 31.59299), GeoPoint(lat = -25.1, lon = 31.6))
        assertEquals(pts, Polyline.decode(Polyline.encode(pts)))
        assertEquals(emptyList<GeoPoint>(), Polyline.decode(""))
        assertNull(Polyline.decode("_p~iF~ps|U_")) // cut off half way through a point
        assertNull(Polyline.decode("\u0010abc"))
    }

    @Test
    fun testPreset() {
        val boundary = Polyline.encode(listOf(GeoPoint(lat = -24.0, lon = 31.0), GeoPoint(lat = -24.0, lon = 32.0), GeoPoint(lat = -25.0, lon = 32.0)))
        val roads = Polyline.encode(listOf(GeoPoint(lat = -24.5, lon = 31.5), GeoPoint(lat = -24.6, lon = 31.6)))
        val json = """{"name":"Park","note":"OSM","boundary":["$boundary"],"roads":["$roads"]}"""
        val p = RoadPreset(json = json.toByteArray())
        assertEquals("Park", p.name)
        assertEquals(3, p.boundary[0].size)
        assertEquals(listOf(listOf(GeoPoint(lat = -24.5, lon = 31.5), GeoPoint(lat = -24.6, lon = 31.6))), p.roads)
        assertThrows(Exception::class.java) {
            RoadPreset(json = """{"name":"P","note":"","boundary":[],"roads":[]}""".toByteArray())
        }
    }

    @Test
    fun testPresetStringsMustBeValidUnicodeWhereTheyAreRead() {
        fun json(before: String, after: String) =
            before.toByteArray() + byteArrayOf(0xFF.toByte()) + after.toByteArray()
        val tail = ""","note":"OSM","boundary":["??"],"roads":[]}"""
        // Lone surrogates and bytes that aren't UTF-8, in a string Swift reads.
        for (bad in listOf(
            """{"name":"P","note":"OSM","boundary":["\ud800\ud800"],"roads":[]}""".toByteArray(),
            """{"name":"P\ud800"$tail""".toByteArray(),
            json("""{"name":"P""", "\"$tail"),
        )) {
            assertThrows(SerializationException::class.java) { RoadPreset(json = bad) }
        }
        // Elsewhere they don't matter.
        assertEquals(
            RoadPreset(name = "P", note = "OSM", boundary = listOf(listOf(GeoPoint(lat = 0.0, lon = 0.0))), roads = emptyList()),
            RoadPreset(json = json("""{"name":"P","x":"""", "\"$tail")),
        )
        assertEquals("P\uD83D\uDE00", RoadPreset(json = """{"name":"P\ud83d\ude00"$tail""".toByteArray()).name)
    }
}
