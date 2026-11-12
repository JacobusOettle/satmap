package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Place search when Photon is down: which service, how long Photon has, how long its failure is remembered, Nominatim
 *  once a second, the Nominatim requests, and Nominatim's answers read into the same places as Photon's. The answers
 *  are the iPhone tests' (Tests/SatMapCoreTests/Fixtures/places, recorded from Nominatim), copied byte for byte into
 *  `src/test/resources/places` with the places each must give (`expected.json`). */
class PlaceSourcesTests {
    private fun recorded(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/places/$name")) { "no fixture $name" }.use { it.readBytes() }

    @Test
    fun testTheTimesAreTheIPhonesToo() {
        assertEquals(5_000L, PlaceSources.photonTimeoutMillis)
        assertEquals(600_000L, PlaceSources.photonRestMillis)
        assertEquals(1_000L, PlaceSources.nominatimSpacingMillis)
        assertEquals(20, PlaceSources.searchLimit)
    }

    @Test
    fun testAPhotonFailureIsRememberedForTenMinutes() {
        var now = 1_000_000L
        val sources = PlaceSources { now }
        assertTrue(sources.usePhoton())
        sources.photonFailed()
        assertFalse(sources.usePhoton())
        now += PlaceSources.photonRestMillis - 1
        assertFalse(sources.usePhoton())
        now += 1
        assertTrue(sources.usePhoton())
        // Asked again and failing again: another rest.
        sources.photonFailed()
        now += 60_000
        assertFalse(sources.usePhoton())
        // Photon answering ends it.
        sources.photonWorked()
        assertTrue(sources.usePhoton())
    }

    @Test
    fun testNominatimAtMostOnceASecond() {
        var now = 50_000L
        val sources = PlaceSources { now }
        assertEquals(0L, sources.nominatimTurn())
        assertEquals(1_000L, sources.nominatimTurn())
        assertEquals(2_000L, sources.nominatimTurn())
        now += 5_000
        assertEquals(0L, sources.nominatimTurn())
        now += 400
        assertEquals(600L, sources.nominatimTurn())
    }

    @Test
    fun testTheNominatimRequestsAreTheIPhones() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val kruger = GeoPoint(lat = -24.987654, lon = 31.592134)
            val southernAfrica = listOf("za", "ls", "sz", "na", "bw", "zw", "mz")
            assertEquals(southernAfrica, PlaceSources.nominatimCountries(kruger))
            assertEquals(listOf(southernAfrica, null), PlaceSources.nominatimCountryPasses(kruger))
            assertEquals(
                listOf("q" to "Sabi Sand", "format" to "jsonv2", "addressdetails" to "1", "limit" to "20",
                       "viewbox" to "30.5921,-23.9877,32.5921,-25.9877", "countrycodes" to "za,ls,sz,na,bw,zw,mz"),
                PlaceSources.nominatimQuery("Sabi Sand", kruger, southernAfrica),
            )
            assertEquals(
                listOf("q" to "Sabi Sand", "format" to "jsonv2", "addressdetails" to "1", "limit" to "20",
                       "viewbox" to "30.5921,-23.9877,32.5921,-25.9877"),
                PlaceSources.nominatimQuery("Sabi Sand", kruger, null),
            )
            assertEquals(
                listOf("q" to "Hazyview", "format" to "jsonv2", "addressdetails" to "1", "limit" to "20"),
                PlaceSources.nominatimQuery("Hazyview", null, null),
            )
            // Elsewhere, or with no map: the whole world at once.
            assertNull(PlaceSources.nominatimCountries(GeoPoint(lat = 51.5, lon = -0.1)))
            assertNull(PlaceSources.nominatimCountries(GeoPoint(lat = -1.3, lon = 36.8)))
            assertNull(PlaceSources.nominatimCountries(null))
            assertEquals(listOf<List<String>?>(null), PlaceSources.nominatimCountryPasses(null))
            // The edges of southern Africa.
            assertEquals(southernAfrica, PlaceSources.nominatimCountries(GeoPoint(lat = -35.5, lon = 11.5)))
            assertEquals(southernAfrica, PlaceSources.nominatimCountries(GeoPoint(lat = -10.3, lon = 41.0)))
            assertNull(PlaceSources.nominatimCountries(GeoPoint(lat = -10.2, lon = 30.0)))
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** The places each recorded answer gives, in order, in both apps (`expected.json`). */
    @Test
    fun testTheRecordedAnswersGiveTheSamePlacesAsOnTheIPhone() {
        val expected = Json.parseToJsonElement(recorded("expected.json").decodeToString()).jsonObject["answers"]!!.jsonObject
        assertEquals(5, expected.size)
        for ((file, places) in expected) {
            val want = places.jsonArray.map { it.jsonObject }.map { p ->
                val extent = p["extent"]
                FoundPlace(
                    id = p.text("id"), name = p.text("name"), kind = p.text("kind"), tag = p.text("tag"), region = p.text("region"),
                    centre = GeoPoint(lat = p["lat"]!!.jsonPrimitive.double, lon = p["lon"]!!.jsonPrimitive.double),
                    extent = if (extent == null || extent is JsonNull) null else (extent as JsonArray).map { it.jsonPrimitive.double }
                        .let { GeoBounds(minLat = it[0], minLon = it[1], maxLat = it[2], maxLon = it[3]) },
                )
            }
            val got = if (file.startsWith("photon")) Places.photonAnswer(recorded(file)) else Places.nominatimResults(recorded(file))
            assertEquals(file, want, got)
        }
        // Photon's answer and Nominatim's for the same name: the same reserve, the same boundary to look up.
        assertEquals(Places.photonResults(recorded("photon-sabi-sand.json")), Places.nominatimResults(recorded("nominatim-sabi-sand.json")))
    }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun testANodeIsASpotAndStreetsAreLeftOut() {
        val kruger = Places.nominatimResults(recorded("nominatim-kruger.json"))!!
        // The park's node (a second "Kruger National Park", a nature reserve without an outline) is left out, as Photon's
        // would be; a locality's node is a spot, without the box Nominatim gives every result.
        assertEquals(1, kruger.count { it.name == "Kruger National Park" })
        val gate = kruger.single { it.name == "Kruger Gate" }
        assertFalse(gate.hasBoundary)
        assertNull(gate.extent)
        // The streets and the building in the Sabi Sand answer, and the car park in Skukuza's.
        assertEquals(listOf("R1751334"), Places.nominatimResults(recorded("nominatim-sabi-sand.json"))!!.map { it.id })
        assertFalse(Places.nominatimResults(recorded("nominatim-skukuza.json"))!!.any { it.tag == "parking" })
    }

    @Test
    fun testNothingFoundIsNotAFailure() {
        assertEquals(emptyList<FoundPlace>(), Places.nominatimResults("[]".toByteArray()))
        assertEquals(emptyList<FoundPlace>(), Places.photonAnswer("""{"type":"FeatureCollection","features":[]}""".toByteArray()))
    }

    @Test
    fun testAnAnswerThatIsntOneIsAFailure() {
        for (bad in listOf("", "<html>502 Bad Gateway</html>", """{"error":"Bad request"}""", "[1,2]", "null", "[{\"osm_id\":1")) {
            assertNull(bad, Places.nominatimResults(bad.toByteArray()))
        }
        for (bad in listOf("", "<html>502 Bad Gateway</html>", """{"message":"down"}""", "[]", """{"features":[""")) {
            assertNull(bad, Places.photonAnswer(bad.toByteArray()))
            assertEquals(emptyList<FoundPlace>(), Places.photonResults(bad.toByteArray()))
        }
        // Coordinates that aren't numbers: that result is left out.
        val odd = """[{"osm_type":"relation","osm_id":1,"lat":"x","lon":"31","category":"boundary","type":"protected_area","name":"P",
                      "boundingbox":["-1","1","-1","1"]},
                     {"osm_type":"relation","osm_id":2,"lat":"-1e1","lon":31.5,"category":"boundary","type":"protected_area","name":"Q",
                      "boundingbox":["-11","-9","31","32"]},
                     {"osm_type":"area","osm_id":3,"lat":"1","lon":"1","category":"place","type":"town","name":"R"}]"""
        val places = Places.nominatimResults(odd.toByteArray())!!
        assertEquals(listOf("R2"), places.map { it.id })
        assertEquals(GeoPoint(lat = -10.0, lon = 31.5), places[0].centre)
        assertEquals(GeoBounds(minLat = -11.0, minLon = 31.0, maxLat = -9.0, maxLon = 32.0), places[0].extent)
        assertEquals("Protected area", places[0].kind)
        assertEquals("", places[0].region)
    }
}
