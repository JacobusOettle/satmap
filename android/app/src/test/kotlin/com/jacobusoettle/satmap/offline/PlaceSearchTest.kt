package com.jacobusoettle.satmap.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
 *  decimal point in any language), the plural searched again, the boundary, and the error texts. */
@RunWith(AndroidJUnit4::class)
class PlaceSearchTest {
    private val photon = MockWebServer()
    private val nominatim = MockWebServer()
    private val saved = listOf(PlaceSearch.photon, PlaceSearch.nominatim)
    private val client = PlaceSearch.client
    private val isOnline = PlaceSearch.isOnline
    private val work = PlaceSearch.work
    private val locale = Locale.getDefault()
    private var online = true

    private val sabiSand = """
        {"type":"FeatureCollection","features":[
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.47,-24.82]},
          "properties":{"osm_type":"R","osm_id":1751334,"osm_key":"leisure","osm_value":"nature_reserve","name":"Sabi Sand Nature Reserve",
                        "extent":[31.32745,-24.6654308,31.6223272,-24.97716],"state":"Mpumalanga","country":"South Africa"}},
         {"type":"Feature","geometry":{"type":"Point","coordinates":[31.12,-25.05]},
          "properties":{"osm_type":"N","osm_id":244310590,"osm_key":"place","osm_value":"town","name":"Hazyview","state":"Mpumalanga","country":"South Africa"}}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        photon.start()
        nominatim.start()
        PlaceSearch.photon = photon.url("/api/")
        PlaceSearch.nominatim = nominatim.url("/lookup")
        PlaceSearch.client = client.newBuilder().proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build()
        PlaceSearch.isOnline = { online }
        PlaceSearch.work = Dispatchers.Unconfined
        // Germany writes 24,9877: the requests mustn't.
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
        PlaceSearch.photon = saved[0]
        PlaceSearch.nominatim = saved[1]
        PlaceSearch.client = client
        PlaceSearch.isOnline = isOnline
        PlaceSearch.work = work
        photon.shutdown()
        nominatim.shutdown()
    }

    private fun failure(body: suspend () -> Any): Throwable? = runBlocking { runCatching { body() }.exceptionOrNull() }

    @Test
    fun aSearchNearTheMapsMiddle() {
        photon.enqueue(MockResponse().setBody(sabiSand))
        val places = runBlocking { PlaceSearch.search("Skukuza camp", near = GeoPoint(lat = -24.987654, lon = 31.592134)) }
        assertEquals(Places.merged(listOf(Places.photonResults(sabiSand.toByteArray()))), places)
        assertEquals(listOf("Sabi Sand Nature Reserve", "Hazyview"), places.map { it.name })
        val request = photon.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/?q=Skukuza%20camp&limit=20&lat=-24.9877&lon=31.5921", request.path)
        assertEquals("SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)", request.getHeader("User-Agent"))

        // Without a place to search near.
        photon.enqueue(MockResponse().setBody(sabiSand))
        runBlocking { PlaceSearch.search("Hazyview", near = null) }
        assertEquals("/api/?q=Hazyview&limit=20", photon.takeRequest().path)
    }

    @Test
    fun aPluralIsAlsoSearchedWithoutIt() {
        photon.enqueue(MockResponse().setBody("""{"features":[]}"""))
        photon.enqueue(MockResponse().setBody(sabiSand))
        val places = runBlocking { PlaceSearch.search("Sabi Sands", near = null) }
        assertEquals("/api/?q=Sabi%20Sands&limit=20", photon.takeRequest().path)
        assertEquals("/api/?q=Sabi%20Sand&limit=20", photon.takeRequest().path)
        assertEquals("Sabi Sand Nature Reserve", places.first().name)

        // An error in the second search is left out.
        photon.enqueue(MockResponse().setBody(sabiSand))
        photon.enqueue(MockResponse().setResponseCode(503))
        val first = runBlocking { PlaceSearch.search("Sabi Sands", near = null) }
        assertEquals(listOf("Sabi Sand Nature Reserve", "Hazyview"), first.map { it.name })
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
        photon.enqueue(MockResponse().setResponseCode(500))
        val busy = failure { PlaceSearch.search("Kruger", near = null) }
        assertTrue(busy is PlaceSearch.Failure.Unavailable)
        assertEquals(unavailable, busy!!.message)

        val place = FoundPlace(id = "R1", name = "X", kind = "Park", tag = "park", region = "", centre = GeoPoint(lat = 0.0, lon = 0.0),
                               extent = GeoBounds(minLat = -1.0, minLon = -1.0, maxLat = 1.0, maxLon = 1.0))
        nominatim.enqueue(MockResponse().setResponseCode(429))
        assertEquals(unavailable, failure { PlaceSearch.area(place) }!!.message)

        // A connection that breaks while online: unavailable.
        repeat(3) { photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)

        // No network.
        online = false
        repeat(3) { photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val none = failure { PlaceSearch.search("Kruger", near = null) }
        assertTrue(none is PlaceSearch.Failure.Offline)
        assertEquals(offline, none!!.message)

        // Names that can't be looked up mean no internet too.
        online = true
        PlaceSearch.client = PlaceSearch.client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = throw UnknownHostException(hostname)
        }).build()
        assertTrue(failure { PlaceSearch.area(place) } is PlaceSearch.Failure.Offline)
    }

    @Test
    fun aSlowServerTimesOutAfter20s() {
        assertEquals(20_000, DownloadHttp.lookups.readTimeoutMillis)
        assertEquals(20_000, DownloadHttp.lookups.connectTimeoutMillis)
        assertEquals(60_000, RoadFetcher.client.readTimeoutMillis)
        // The answer that never comes ends as unavailable (a short timeout here).
        PlaceSearch.client = PlaceSearch.client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        photon.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(failure { PlaceSearch.search("Kruger", near = null) } is PlaceSearch.Failure.Unavailable)
    }
}
