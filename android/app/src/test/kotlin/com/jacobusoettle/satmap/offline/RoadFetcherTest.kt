package com.jacobusoettle.satmap.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Roads
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** `RoadFetcher` against four local servers (offline spec §1.10, §2.14): the 12 s stagger on the test clock, the first
 *  good answer winning, the error texts and the request itself. And `RoadPresets` with the shipped Kruger roads. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RoadFetcherTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mocks = List(4) { MockWebServer() }
    private val servers = RoadFetcher.servers
    private val client = RoadFetcher.client
    private val isOnline = RoadFetcher.isOnline
    private val work = RoadFetcher.work
    private var online = true

    /** About 200 m across, in Mpumalanga, with a road through the middle. */
    private val farm = CoverageShape(polygons = listOf(listOf(
        GeoPoint(lat = -25.4700, lon = 30.9700), GeoPoint(lat = -25.4700, lon = 30.9720),
        GeoPoint(lat = -25.4718, lon = 30.9720), GeoPoint(lat = -25.4718, lon = 30.9700),
    )))
    private val road = listOf(GeoPoint(lat = -25.4709, lon = 30.9690), GeoPoint(lat = -25.4709, lon = 30.9730))
    private val answer = """{"version":0.6,"elements":[{"type":"way","id":1,"geometry":[{"lat":-25.4709,"lon":30.969},""" +
        """{"lat":-25.4709,"lon":30.973}]},{"type":"node","id":2}]}"""

    @Before
    fun setUp() {
        mocks.forEach { it.start() }
        RoadFetcher.servers = mocks.map { it.url("/api/interpreter") }
        RoadFetcher.client = client.newBuilder().proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build()
        RoadFetcher.isOnline = { online }
        RoadFetcher.work = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        RoadFetcher.servers = servers
        RoadFetcher.client = client
        RoadFetcher.isOnline = isOnline
        RoadFetcher.work = work
        mocks.forEach { it.shutdown() }
    }

    /** Starts the lookup on the test clock, which moves only when the test says. (Its failure is its result, not the
     *  test scope's.) */
    private fun TestScope.start(area: CoverageShape = farm): Deferred<Result<List<List<GeoPoint>>>> {
        val result = async { runCatching { RoadFetcher.roads(area) } }
        runCurrent()
        return result
    }

    /** Waits (in real time, the servers answering) until the lookup is done, without moving the test clock. */
    private fun <T> TestScope.finish(result: Deferred<Result<T>>): Result<T> {
        val end = System.currentTimeMillis() + 10_000
        while (!result.isCompleted) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            runCurrent()
            Thread.sleep(5)
        }
        return result.getCompleted()
    }

    private fun asked(server: Int, waitMs: Long = 2_000) = mocks[server].takeRequest(waitMs, TimeUnit.MILLISECONDS)

    @Test
    fun theNextServerIsAskedAfter12sWithoutAnAnswerAndTheFirstAnswerWins() = runTest {
        mocks[0].enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        mocks[1].enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        mocks[2].enqueue(MockResponse().setBody(answer))
        val result = start()
        assertNotNull(asked(0))
        advanceTimeBy(11_999)
        assertNull(asked(1, 300))
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(asked(1))
        assertEquals(12_000, currentTime)
        advanceTimeBy(12_000)
        runCurrent()
        assertNotNull(asked(2)) // this one answers
        val roads = finish(result).getOrThrow()
        assertEquals(Roads.clip(listOf(road), farm), roads)
        assertTrue(roads.isNotEmpty())
        // The rest are cancelled: the last server is never asked, even after its time.
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(asked(3, 300))
    }

    @Test
    fun aFailedServerPassesOnAtOnce() = runTest {
        mocks[0].enqueue(MockResponse().setResponseCode(504).setBody("Gateway timeout"))
        mocks[1].enqueue(MockResponse().setBody("<html>The server is probably too busy</html>")) // unreadable
        mocks[2].enqueue(MockResponse().setBody("""{"elements":[],"remark":"runtime error: Query timed out"}"""))
        mocks[3].enqueue(MockResponse().setBody(answer))
        val roads = finish(start()).getOrThrow()
        assertEquals(Roads.clip(listOf(road), farm), roads)
        assertEquals(0, currentTime) // no waiting
        assertEquals(listOf(1, 1, 1, 1), mocks.map { it.requestCount })
    }

    @Test
    fun theErrorTexts() = runTest {
        // Every server busy.
        for (m in mocks) m.enqueue(MockResponse().setResponseCode(429))
        val busy = finish(start()).exceptionOrNull()
        assertTrue(busy is RoadFetcher.Failure.Unavailable)
        assertEquals("The OpenStreetMap road server is busy or unreachable. Try again in a minute.", busy!!.message)

        // No network: requests fail, and the phone says it is offline.
        online = false
        // (OkHttp tries a failed connection again.)
        for (m in mocks) repeat(3) { m.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val offline = finish(start()).exceptionOrNull()
        assertTrue(offline is RoadFetcher.Failure.Offline)
        assertEquals("No internet connection. Roads are looked up online; try again when connected.", offline!!.message)

        // Names that can't be looked up mean no internet too, whatever the phone says.
        online = true
        RoadFetcher.client = RoadFetcher.client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = throw UnknownHostException(hostname)
        }).build()
        assertTrue(finish(start()).exceptionOrNull() is RoadFetcher.Failure.Offline)
    }

    @Test
    fun theQueryIsPostedPercentEncoded() = runTest {
        mocks[0].enqueue(MockResponse().setBody(answer))
        finish(start()).getOrThrow()
        val request = asked(0)!!
        assertEquals("POST", request.method)
        assertEquals("/api/interpreter", request.path)
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
        assertEquals(DownloadHttp.lookupUserAgent, request.getHeader("User-Agent"))
        val body = request.body.readUtf8()
        assertEquals("data=" + RoadFetcher.percentEncode(Roads.overpassQuery(farm)!!), body)
        assertTrue(body, Regex("data=[A-Za-z0-9%._~-]+").matches(body))
        // Only ASCII letters, digits and -._~ stay as they are; the rest is %XX of the UTF-8 bytes, in capitals.
        assertEquals("%5Bout%3Ajson%5D%28-25.1%2C30.2%29%3B%22a%7Cb%22%24%20%C3%A9~_-.",
                     RoadFetcher.percentEncode("[out:json](-25.1,30.2);\"a|b\"$ é~_-."))
    }

    @Test
    fun noGeometryAsksNoServer() = runTest {
        assertEquals(emptyList<List<GeoPoint>>(), finish(start(CoverageShape())).getOrThrow())
        assertEquals(listOf(0, 0, 0, 0), mocks.map { it.requestCount })
    }

    @Test
    fun theKrugerRoadsAreShippedAndHoldAnAreaInThePark() {
        RoadPresets.init(app)
        val kruger = RoadPresets.load(RoadPresets.kruger)
        assertNotNull(kruger)
        assertEquals("Kruger National Park roads", kruger!!.name)
        assertTrue(kruger.roads.size > 1000)
        assertTrue(RoadPresets.load("KrugerRoads") === kruger) // read once
        assertNull(RoadPresets.load("NoSuchRoads"))
        assertNull(RoadPresets.load(null))
        // A block around Skukuza is in the park; the farm near Nelspruit isn't.
        val skukuza = CoverageShape(polygons = listOf(listOf(
            GeoPoint(lat = -24.98, lon = 31.58), GeoPoint(lat = -24.98, lon = 31.62),
            GeoPoint(lat = -25.01, lon = 31.62), GeoPoint(lat = -25.01, lon = 31.58),
        )))
        assertTrue(RoadPresets.covering(skukuza) === kruger)
        assertNull(RoadPresets.covering(farm))
        assertNull(RoadPresets.covering(CoverageShape()))
    }
}
