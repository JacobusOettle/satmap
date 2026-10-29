package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

// Places for offline areas: the tests at the end of the Swift ScaleBarTests.
class PlacesTests {
    @Test
    fun testPhotonResultsKeepAreasAndSettlements() {
        val json = """
        {"type":"FeatureCollection","features":[
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.47,-24.82]},
          "properties":{"osm_type":"R","osm_id":1751334,"osm_key":"leisure","osm_value":"nature_reserve","name":"Sabi Sand Nature Reserve",
                        "extent":[31.32745,-24.6654308,31.6223272,-24.97716],"state":"Mpumalanga","country":"South Africa"}},
         {"type":"Feature","geometry":{"type":"Point","coordinates":[28.46,-26.10]},
          "properties":{"osm_type":"W","osm_id":338821179,"osm_key":"highway","osm_value":"residential","name":"Sabi Sand Street"}},
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.12,-25.05]},
          "properties":{"osm_type":"N","osm_id":244310590,"osm_key":"place","osm_value":"town","name":"Hazyview","state":"Mpumalanga","country":"South Africa"}},
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31,-25]},
          "properties":{"osm_type":"N","osm_id":1,"osm_key":"place","osm_value":"village","name":"Whole Degrees"}}
        ]}
        """.trimIndent()
        val places = Places.photonResults(json.toByteArray())
        assertEquals(listOf("Sabi Sand Nature Reserve", "Hazyview", "Whole Degrees"), places.map { it.name })
        // A spot that isn't a settlement or a camp (a peak, a hotel) is left out.
        val peak = """{"features":[{"geometry":{"coordinates":[-94,31]},"properties":{"osm_type":"N","osm_id":5,"osm_key":"natural","osm_value":"peak","name":"Sand Hills"}},{"geometry":{"coordinates":[31,-25]},"properties":{"osm_type":"N","osm_id":6,"osm_key":"tourism","osm_value":"camp_site","name":"Camp"}}]}"""
        assertEquals(listOf("Camp"), Places.photonResults(peak.toByteArray()).map { it.name })
        val sabi = places[0]
        assertEquals("R1751334", sabi.id)
        assertTrue(sabi.hasBoundary)
        assertEquals("Nature reserve", sabi.kind)
        assertEquals("Mpumalanga, South Africa", sabi.region)
        assertEquals(-24.97716, sabi.extent?.minLat ?: 0.0, 1e-9)
        assertEquals(-24.6654308, sabi.extent?.maxLat ?: 0.0, 1e-9)
        assertGreaterThan(sabi.extentKm2 ?: 0.0, 500.0)
        val town = places[1]
        assertFalse(town.hasBoundary)
        assertEquals(4_000.0, town.radiusMeters, 0.0)
        // A spot: the circle around it.
        val area = Places.area(town, rings = emptyList())
        assertEquals(1, area.points.size)
        assertEquals(4_000.0, area.bufferMeters, 0.0)
    }

    @Test
    fun testNominatimBoundaryRings() {
        val multi = """
        {"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"MultiPolygon","coordinates":[
          [[[31,-25],[31.5,-25],[31.5,-24.5],[31,-25]],[[31.1,-24.9],[31.2,-24.9],[31.2,-24.8],[31.1,-24.9]]],
          [[[32,-25],[32.1,-25],[32.1,-24.9],[32,-25]]]
        ]}}]}
        """.trimIndent()
        val rings = Places.outerRings(multi.toByteArray())
        assertEquals(2, rings.size) // the hole is left out
        assertEquals(4, rings[0].size)
        assertEquals(31.5, rings[0][1].lon, 0.0)
        val polygon = """{"type":"Polygon","coordinates":[[[1,1],[2,1],[2,2],[1,1]]]}"""
        assertEquals(1, Places.outerRings(polygon.toByteArray()).size)
        assertEquals(0, Places.outerRings("""{"type":"Point","coordinates":[1,1]}""".toByteArray()).size)
        val place = FoundPlace(
            id = "R1", name = "X", kind = "Park", tag = "park", region = "", centre = GeoPoint(lat = 0.0, lon = 0.0),
            extent = GeoBounds(minLat = -1.0, minLon = -1.0, maxLat = 1.0, maxLon = 1.0),
        )
        assertEquals(2, Places.area(place, rings = rings).polygons.size)
        // Grown by as much as the boundary was simplified, so nothing along its edge is lost.
        assertEquals(2.0 / 1500 * 111_320, Places.area(place, rings = rings).bufferMeters, 0.01)
        // A camp with an outline: with the area around it too.
        val camp = FoundPlace(
            id = "W2", name = "Skukuza", kind = "Camp", tag = "camp_site", region = "", centre = GeoPoint(lat = -25.0, lon = 31.6),
            extent = GeoBounds(minLat = -25.001, minLon = 31.599, maxLat = -24.999, maxLon = 31.601),
        )
        assertEquals(1_000.0, Places.area(camp, rings = rings).bufferMeters, 0.0)
        // No boundary to be had: its extent.
        assertEquals(4, Places.area(place, rings = emptyList()).polygons.firstOrNull()?.size)
        assertEquals(2.0 / 1500, Places.simplification(place), 1e-12)
    }

    @Test
    fun testPlaceSearchMergesPluralsAndPutsWholeReservesFirst() {
        assertEquals("Sabi Sand", Places.singular("Sabi Sands"))
        assertNull(Places.singular("Kruger National Park"))
        assertNull(Places.singular("Bass"))
        fun reserve(id: String, km: Double): FoundPlace {
            val d = sqrt(km) / 111 / 2
            return FoundPlace(
                id = id, name = id, kind = "Nature reserve", tag = "nature_reserve", region = "", centre = GeoPoint(lat = 0.0, lon = 0.0),
                extent = GeoBounds(minLat = -d, minLon = -d, maxLat = d, maxLon = d),
            )
        }
        val town = FoundPlace(id = "N9", name = "Town", kind = "Town", tag = "town", region = "", centre = GeoPoint(lat = 0.0, lon = 0.0), extent = null)
        val merged = Places.merged(listOf(listOf(reserve("W1", 170.0), town, reserve("W2", 431.0)), listOf(reserve("R3", 1_032.0), reserve("W1", 170.0))))
        assertEquals(listOf("R3", "W2", "W1", "N9"), merged.map { it.id })
    }

    @Test
    fun testPhotonResultsAndRingsRefuseWhatJSONSerializationRefuses() {
        fun doc(name: String = "\"A\"", id: String = "1", coordinates: String = "1,2", extra: String = "") =
            """{"features":[{"properties":{"name":$name,"osm_key":"place","osm_value":"town","osm_type":"N","osm_id":$id},""" +
                """"geometry":{"coordinates":[$coordinates]}}]$extra}"""
        val places = Places.photonResults(doc().toByteArray())
        assertEquals(listOf("N1"), places.map { it.id })
        assertEquals(GeoPoint(lat = 2.0, lon = 1.0), places[0].centre)
        // Numbers JSON doesn't write, bare words, a lone surrogate, a raw control character, a byte that isn't UTF-8.
        val (before, after) = doc(name = "\"A#\"").split("#")
        val refused = listOf(
            doc(coordinates = "NaN,2"), doc(coordinates = "Infinity,2"), doc(id = "NaN"), doc(id = "01"), doc(extra = ",\"x\":abc"),
            doc(extra = ",\"x\":1d"), doc(name = "\"A\\ud800\""), doc(name = "\"A\u0001\""),
        ).map { it.toByteArray() } + listOf(before.toByteArray() + byteArrayOf(0xFF.toByte()) + after.toByteArray())
        for (json in refused) assertEquals(json.toString(Charsets.UTF_8), emptyList<FoundPlace>(), Places.photonResults(json))

        val polygon = """{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]"""
        assertEquals(1, Places.outerRings("$polygon}".toByteArray()).size)
        assertEquals(0, Places.outerRings("$polygon,\"x\":NaN}".toByteArray()).size)
        assertEquals(0, Places.outerRings("$polygon,\"x\":\"\\udc00\"}".toByteArray()).size)
    }

    @Test
    fun testSingularWorksOnWholeCharacters() {
        // A space followed by a combining mark is a character of a word, not a space between words.
        assertNull(Places.singular("Sabi Sands \u0301x"))
        assertEquals("a\u0130 \u0301 \u0130c s", Places.singular("a\u0130 \u0301s \u0130c s "))
        assertEquals("c  \u0301sa", Places.singular("c  \u0301sas"))
        assertEquals("Lion Den", Places.singular("Lions  Den"))
        // An Arabic number sign makes one character with the letter after it: "\u0600s" isn't an "s".
        assertNull(Places.singular("abc\u0600s"))
        assertEquals("abc\u0600s", Places.singular("abc\u0600ss"))
    }
}
