package com.jacobusoettle.satmap.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.PlaceSources
import com.jacobusoettle.satmap.core.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** `PlaceSearch` against local servers (offline spec §1.12, §2.14): the Photon and Nominatim requests (numbers with a
 *  decimal point in any language), the plural searched again, Nominatim's search while Photon is down, the boundary,
 *  and the error texts. */
@RunWith(AndroidJUnit4::class)
class PlaceSearchTest {
    private val photon = MockWebServer()
    private val nominatim = MockWebServer()
    private val saved = listOf(PlaceSearch.photon, PlaceSearch.nominatim, PlaceSearch.nominatimSearch)
    private val client = PlaceSearch.client
    private val isOnline = PlaceSearch.isOnline
    private val work = PlaceSearch.work
    private val sources = PlaceSearch.sources
    private val photonTimeout = PlaceSearch.photonTimeoutMillis
    private val locale = Locale.getDefault()
    private var online = true

    /** The clock of `PlaceSearch.sources`: a second on at each look, so Nominatim never waits unless a test stops it. */
    private var now = 1_000_000L
    private var ticking = true

    private val sabiSand = """
        {"type":"FeatureCollection","features":[
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.47,-24.82]},
          "properties":{"osm_type":"R","osm_id":1751334,"osm_key":"leisure","osm_value":"nature_reserve","name":"Sabi Sand Nature Reserve",
                        "extent":[31.32745,-24.6654308,31.6223272,-24.97716],"state":"Mpumalanga","country":"South Africa"}},
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.12,-25.05]},
          "properties":{"osm_type":"N","osm_id":244310590,"osm_key":"place","osm_value":"town","name":"Hazyview","state":"Mpumalanga","country":"South Africa"}}
        ]}
    """.trimIndent()

    /** A Nominatim answer recorded in core's test resources (places/). */
    private fun recorded(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/places/$name")) { "no fixture $name" }.use { it.readBytes().decodeToString() }

    private fun nominatimAnswer(name: String) = MockResponse().setBody(Buffer().write(recorded(name).toByteArray()))

    private val kruger = GeoPoint(lat = -24.987654, lon = 31.592134)
    private val krugerBox = "viewbox=30.5921%2C-23.9877%2C32.5921%2C-25.9877"
    private val southernAfrica = "countrycodes=za%2Cls%2Csz%2Cna%2Cbw%2Czw%2Cmz%2Czm%2Cmw%2Cao%2Ccd%2Ctz"

    @Before
    fun setUp() {
        photon.start()
        nominatim.start()
        PlaceSearch.photon = photon.url("/api/")
        PlaceSearch.nominatim = nominatim.url("/lookup")
        PlaceSearch.nominatimSearch = nominatim.url("/search")
        PlaceSearch.client = client.newBuilder().proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build()
        PlaceSearch.isOnline = { online }
        PlaceSearch.work = Dispatchers.Unconfined
        PlaceSearch.sources = PlaceSources { if (ticking) now += 1_000; now }
        // Germany writes 24,9877: the requests mustn't.
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
        PlaceSearch.photon = saved[0]
        PlaceSearch.nominatim = saved[1]
        PlaceSearch.nominatimSearch = saved[2]
        PlaceSearch.client = client
        PlaceSearch.isOnline = isOnline
        PlaceSearch.work = work
        PlaceSearch.sources = sources
        PlaceSearch.photonTimeoutMillis = photonTimeout
        photon.shutdown()
        nominatim.shutdown()
    }

    private fun failure(body: suspend () -> Any): Throwable? = runBlocking { runCatching { body() }.exceptionOrNull() }

    @Test
    fun aSearchNearTheMapsMiddle() {
        photon.enqueue(MockResponse().setBody(sabiSand))
        val places = runBlocking { PlaceSearch.search("Skukuza camp", near = kruger) }
        assertEquals(Places.merged(listOf(Places.photonResults(sabiSand.toByteArray()))), places)
        assertEquals(listOf("Sabi Sand Nature Reserve", "Hazyview"), places.map { it.name })
        val request = photon.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/?q=Skukuza%20camp&limit=20&lat=-24.9877&lon=31.5921", request.path)
        assertEquals("Spoor/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)", request.getHeader("User-Agent"))

        // Without a place to search near.
        photon.enqueue(MockResponse().setBody(sabiSand))
        runBlocking { PlaceSearch.search("Hazyview", near = null) }
        assertEquals("/api/?q=Hazyview&limit=20", photon.takeRequest().path)
        assertEquals(0, nominatim.requestCount)
    }

    @Test
    fun aPluralIsAlsoSearchedWithoutIt() {
        photon.enqueue(MockResponse().setBody("""{"features":[]}"""))
        photon.enqueue(MockResponse().setBody(sabiSand))
        val places = runBlocking { PlaceSearch.search("Sabi Sands", near = null) }
        assertEquals("/api/?q=Sabi%20Sands&limit=20", photon.takeRequest().path)
        assertEquals("/api/?q=Sabi%20Sand&limit=20", photon.takeRequest().path)
        assertEquals("Sabi Sand Nature Reserve", places.first().name)

        // An error in the second search is left out (Nominatim, asked in its place, fails too).
        photon.enqueue(MockResponse().setBody(sabiSand))
        photon.enqueue(MockResponse().setResponseCode(503))
        nominatim.enqueue(MockResponse().setResponseCode(503))
        val first = runBlocking { PlaceSearch.search("Sabi Sands", near = null) }
        assertEquals(listOf("Sabi Sand Nature Reserve", "Hazyview"), first.map { it.name })
    }

    @Test
    fun photonFindingNothingIsNothingFound() {
        photon.enqueue(MockResponse().setBody("""{"type":"FeatureCollection","features":[]}"""))
        assertEquals(emptyList<FoundPlace>(), runBlocking { PlaceSearch.search("Zzxqv nowhere", near = kruger) })
        assertEquals(0, nominatim.requestCount)
        // Photon is still asked next time.
        photon.enqueue(MockResponse().setBody(sabiSand))
        assertEquals(2, runBlocking { PlaceSearch.search("Hazyview", near = kruger) }.size)
        assertEquals(2, photon.requestCount)
    }

    @Test
    fun nominatimWhilePhotonIsDown() {
        // Photon's error: the same search with Nominatim, in the countries around the map, read into the same places.
        photon.enqueue(MockResponse().setResponseCode(502))
        nominatim.enqueue(nominatimAnswer("nominatim-sabi-sand.json"))
        val places = runBlocking { PlaceSearch.search("Sabi Sand", near = kruger) }
        assertEquals(listOf("R1751334"), places.map { it.id })
        assertEquals("Sabi Sand Nature Reserve", places[0].name)
        assertEquals("Nature reserve", places[0].kind)
        assertEquals("Ehlanzeni District Municipality, Mpumalanga, South Africa", places[0].region)
        photon.takeRequest()
        val request = nominatim.takeRequest()
        assertEquals("/search?q=Sabi%20Sand&format=jsonv2&addressdetails=1&limit=20&$krugerBox&$southernAfrica", request.path)
        assertEquals(DownloadHttp.lookupUserAgent, request.getHeader("User-Agent"))
        assertEquals(null, request.getHeader("Accept-Language"))

        // Its boundary, as from Photon's place.
        nominatim.enqueue(MockResponse().setBody("""{"type":"FeatureCollection","features":[]}"""))
        runBlocking { PlaceSearch.area(places[0]) }
        assertTrue(nominatim.takeRequest().path!!.startsWith("/lookup?osm_ids=R1751334&"))

        // For the next ten minutes, straight to Nominatim; the plural searched again there too. Nothing in the countries
        // around the map: the whole world.
        nominatim.enqueue(MockResponse().setBody("[]"))
        nominatim.enqueue(nominatimAnswer("nominatim-sabi-sands.json"))
        nominatim.enqueue(nominatimAnswer("nominatim-sabi-sand.json"))
        val plural = runBlocking { PlaceSearch.search("Sabi Sands", near = kruger) }
        assertEquals(1, photon.requestCount)
        assertEquals("/search?q=Sabi%20Sands&format=jsonv2&addressdetails=1&limit=20&$krugerBox&$southernAfrica", nominatim.takeRequest().path)
        assertEquals("/search?q=Sabi%20Sands&format=jsonv2&addressdetails=1&limit=20&$krugerBox", nominatim.takeRequest().path)
        assertEquals("/search?q=Sabi%20Sand&format=jsonv2&addressdetails=1&limit=20&$krugerBox&$southernAfrica", nominatim.takeRequest().path)
        assertEquals(listOf("R1751334", "W1238644991", "W1238637922"), plural.map { it.id })

        // Then Photon again.
        now += PlaceSources.photonRestMillis
        photon.enqueue(MockResponse().setBody(sabiSand))
        assertEquals(2, runBlocking { PlaceSearch.search("Hazyview", near = kruger) }.size)
        assertEquals(2, photon.requestCount)
        assertEquals(5, nominatim.requestCount)
    }

    @Test
    fun nominatimWhenPhotonIsSlowOrAnswersNonsense() {
        assertEquals(5_000L, PlaceSearch.photonTimeoutMillis)
        PlaceSearch.photonTimeoutMillis = 300
        photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        nominatim.enqueue(nominatimAnswer("nominatim-skukuza.json"))
        val started = System.nanoTime()
        val camp = runBlocking { PlaceSearch.search("Skukuza", near = kruger) }
        assertTrue(System.nanoTime() - started < 10_000_000_000L)
        assertEquals(listOf("W521049369", "R13515582", "N262716119"), camp.map { it.id })
        assertEquals(listOf("Camp", "Airfield", "Village"), camp.map { it.kind })

        // A Photon that answers with something else (a proxy's page). With no map: the whole world at once.
        now += PlaceSources.photonRestMillis
        photon.enqueue(MockResponse().setBody("<html>Bad gateway</html>"))
        nominatim.enqueue(nominatimAnswer("nominatim-skukuza.json"))
        assertEquals("Skukuza", runBlocking { PlaceSearch.search("Skukuza", near = null) }.first().name)
        photon.takeRequest()
        photon.takeRequest()
        nominatim.takeRequest()
        assertEquals("/search?q=Skukuza&format=jsonv2&addressdetails=1&limit=20", nominatim.takeRequest().path)
        assertEquals(2, photon.requestCount)
        assertEquals(2, nominatim.requestCount)
    }

    @Test
    fun aPhotonRefusalSendsOnlyThatSearchToNominatim() {
        // A 4xx other than 408 and 429: this search with Nominatim, but Photon is asked again next time.
        for (refusal in listOf(400, 404, 414)) {
            photon.enqueue(MockResponse().setResponseCode(refusal))
            nominatim.enqueue(nominatimAnswer("nominatim-sabi-sand.json"))
            assertEquals(listOf("R1751334"), runBlocking { PlaceSearch.search("Sabi Sand", near = kruger) }.map { it.id })
            assertTrue("$refusal", PlaceSearch.sources.usePhoton())
        }
        assertEquals(3, photon.requestCount)
        assertEquals(3, nominatim.requestCount)
        // 408, 429 and 5xx are Photon failing: the 10-minute rest.
        for (busy in listOf(408, 429, 500)) {
            now += PlaceSources.photonRestMillis
            // OkHttp itself asks once more after a 408.
            repeat(if (busy == 408) 2 else 1) { photon.enqueue(MockResponse().setResponseCode(busy)) }
            nominatim.enqueue(nominatimAnswer("nominatim-sabi-sand.json"))
            assertEquals(listOf("R1751334"), runBlocking { PlaceSearch.search("Sabi Sand", near = kruger) }.map { it.id })
            assertTrue("$busy", !PlaceSearch.sources.usePhoton())
        }
        assertEquals(7, photon.requestCount)
    }

    @Test
    fun nominatimAtMostOnceASecond() {
        ticking = false
        PlaceSearch.sources.photonFailed()
        nominatim.enqueue(MockResponse().setBody("[]"))
        nominatim.enqueue(MockResponse().setBody("[]"))
        val started = System.nanoTime()
        assertEquals(emptyList<FoundPlace>(), runBlocking { PlaceSearch.search("Zzxqv nowhere", near = kruger) })
        // In the countries around the map, then the whole world a second later.
        assertEquals(2, nominatim.requestCount)
        assertTrue(System.nanoTime() - started >= 1_000_000_000L)
        assertEquals(0, photon.requestCount)
    }

    @Test
    fun theBoundaryOfAPlace() {
        val reserve = Places.photonResults(sabiSand.toByteArray()).first()
        val geoJSON = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon",""" +
            """"coordinates":[[[31.33,-24.97],[31.62,-24.97],[31.62,-24.67],[31.33,-24.97]]]}}]}"""
        nominatim.enqueue(MockResponse().setBody(geoJSON))
        val area = runBlocking { PlaceSearch.area(reserve) }
        assertEquals(Places.area(reserve, Places.outerRings(geoJSON.toByteArray())), area)
        assertEquals(1, area.polygons.size)
        val request = nominatim.takeRequest()
        val threshold = String.format(Locale.ROOT, "%.5f", Places.simplification(reserve))
        assertTrue(threshold, threshold.contains('.'))
        assertEquals("/lookup?osm_ids=R1751334&format=geojson&polygon_geojson=1&polygon_threshold=$threshold", request.path)
        assertEquals(DownloadHttp.lookupUserAgent, request.getHeader("User-Agent"))

        // A spot has no boundary: the circle around it, with no request.
        val town = FoundPlace(id = "N244310590", name = "Hazyview", kind = "Town", tag = "town", region = "",
                              centre = GeoPoint(lat = -25.05, lon = 31.12), extent = null)
        assertEquals(Places.area(town, rings = emptyList()), runBlocking { PlaceSearch.area(town) })
        assertEquals(1, nominatim.requestCount)
    }

    @Test
    fun theErrorTexts() {
        val unavailable = "The place search didn't answer. Try again in a moment."
        val offline = "No internet connection. Places are looked up online; try again when connected."
        // Photon and Nominatim both fail.
        photon.enqueue(MockResponse().setResponseCode(500))
        nominatim.enqueue(MockResponse().setResponseCode(500))
        val busy = failure { PlaceSearch.search("Kruger", near = null) }
        assertTrue(busy is PlaceSearch.Failure.Unavailable)
        assertEquals(unavailable, busy!!.message)
        // Nominatim answers with something else.
        nominatim.enqueue(MockResponse().setBody("""{"error":"Bad request"}"""))
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)
        assertEquals(1, photon.requestCount)
        now += PlaceSources.photonRestMillis

        val place = FoundPlace(id = "R1", name = "X", kind = "Park", tag = "park", region = "", centre = GeoPoint(lat = 0.0, lon = 0.0),
                               extent = GeoBounds(minLat = -1.0, minLon = -1.0, maxLat = 1.0, maxLon = 1.0))
        nominatim.enqueue(MockResponse().setResponseCode(429))
        assertEquals(unavailable, failure { PlaceSearch.area(place) }!!.message)

        // A connection that breaks while online: Photon failing (Nominatim, and the rest), then unavailable.
        repeat(3) { photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        repeat(3) { nominatim.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val nominatimBefore = nominatim.requestCount
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)
        assertTrue(nominatim.requestCount > nominatimBefore)
        assertTrue(!PlaceSearch.sources.usePhoton())
        now += PlaceSources.photonRestMillis

        // No network on the phone: offline at once, Nominatim not asked, and not remembered as Photon's failure.
        online = false
        repeat(3) { photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val asked = nominatim.requestCount
        val none = failure { PlaceSearch.search("Kruger", near = null) }
        assertTrue(none is PlaceSearch.Failure.Offline)
        assertEquals(offline, none!!.message)
        assertEquals(asked, nominatim.requestCount)
        assertTrue(PlaceSearch.sources.usePhoton())

        // A name that can't be looked up while the phone is online is that service failing, not no internet: Photon's
        // search goes to Nominatim and the rest starts.
        online = true
        PlaceSearch.client = PlaceSearch.client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = throw UnknownHostException(hostname)
        }).build()
        assertTrue(failure { PlaceSearch.area(place) } is PlaceSearch.Failure.Unavailable)
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)
        assertTrue(!PlaceSearch.sources.usePhoton())
    }

    @Test
    fun photonsNameNotFoundWhileOnlineGoesToNominatim() {
        // Photon's host can't be looked up (the phone is online): Nominatim answers, and the rest starts.
        PlaceSearch.photon = "http://photon.invalid/api/".toHttpUrl()
        PlaceSearch.client = PlaceSearch.client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) =
                if (hostname == "photon.invalid") throw UnknownHostException(hostname) else Dns.SYSTEM.lookup(hostname)
        }).build()
        nominatim.enqueue(nominatimAnswer("nominatim-sabi-sand.json"))
        assertEquals(listOf("R1751334"), runBlocking { PlaceSearch.search("Sabi Sand", near = kruger) }.map { it.id })
        assertTrue(!PlaceSearch.sources.usePhoton())
    }

    @Test
    fun aSlowServerTimesOutAfter20s() {
        assertEquals(20_000, DownloadHttp.lookups.readTimeoutMillis)
        assertEquals(20_000, DownloadHttp.lookups.connectTimeoutMillis)
        assertEquals(60_000, RoadFetcher.client.readTimeoutMillis)
        // The answer that never comes ends as unavailable (short timeouts here).
        PlaceSearch.client = PlaceSearch.client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        PlaceSearch.photonTimeoutMillis = 200
        photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        nominatim.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)
    }
}
