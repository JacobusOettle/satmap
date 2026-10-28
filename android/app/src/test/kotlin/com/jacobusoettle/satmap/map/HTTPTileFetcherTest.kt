package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `HTTPTileFetcher`'s classification of what the server answers (rendering spec §1.4), against a local server. */
class HTTPTileFetcherTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private var state = Connectivity.State(isOnline = true, isExpensive = false)
    private lateinit var network: () -> Connectivity.State
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

    @Before
    fun setUp() {
        network = HTTPTileFetcher.network
        HTTPTileFetcher.network = { state }
        server.start()
    }

    @After
    fun tearDown() {
        HTTPTileFetcher.network = network
        server.shutdown()
    }

    private fun answer(response: MockResponse): TileFetchResult = runBlocking {
        server.enqueue(response)
        HTTPTileFetcher.fetch(server.url("/tile/1/2/3").toString(), client)
    }

    @Test
    fun anImageIsData() {
        val r = answer(MockResponse().setBody(Buffer().write(jpeg)))
        assertTrue(r is TileFetchResult.Data)
        assertArrayEquals(jpeg, (r as TileFetchResult.Data).data)
        assertTrue(answer(MockResponse().setBody(Buffer().write(png))) is TileFetchResult.Data)
        assertEquals("SatMap/1.0 (Android)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun theStatuses() {
        assertEquals(TileFetchResult.Missing, answer(MockResponse().setResponseCode(200)))
        assertEquals(TileFetchResult.Failed, answer(MockResponse().setBody("<html>Map data not yet available</html>")))
        assertEquals(TileFetchResult.Missing, answer(MockResponse().setResponseCode(204)))
        assertEquals(TileFetchResult.Missing, answer(MockResponse().setResponseCode(404)))
        assertEquals(TileFetchResult.Missing, answer(MockResponse().setResponseCode(410)))
        assertEquals(TileFetchResult.Failed, answer(MockResponse().setResponseCode(429)))
        assertEquals(TileFetchResult.Failed, answer(MockResponse().setResponseCode(500).setBody(Buffer().write(jpeg))))
        assertEquals(TileFetchResult.Failed, answer(MockResponse().setResponseCode(403)))
    }

    @Test
    fun noConnectionIsFailedOnlineAndOfflineOffline() = runBlocking {
        val url = server.url("/tile/1/2/3").toString()
        server.shutdown()
        assertEquals(TileFetchResult.Failed, HTTPTileFetcher.fetch(url, client))
        state = Connectivity.State(isOnline = false, isExpensive = false)
        assertEquals(TileFetchResult.Offline, HTTPTileFetcher.fetch(url, client))
    }

    @Test
    fun wifiOnlyIsOfflineOnAMeteredNetworkWithoutARequest() = runBlocking {
        state = Connectivity.State(isOnline = true, isExpensive = true)
        val fetcher = HTTPTileFetcher(client, { server.url("/tile/${it.z}/${it.y}/${it.x}").toString() }, wifiOnly = true)
        assertEquals(TileFetchResult.Offline, fetcher.fetch(TileID(1, 0, 0)))
        assertEquals(0, server.requestCount)
        // Any network may use it.
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
        val any = HTTPTileFetcher(client, { server.url("/tile/${it.z}/${it.y}/${it.x}").toString() })
        assertTrue(any.fetch(TileID(1, 0, 0)) is TileFetchResult.Data)
        assertEquals("/tile/1/0/0", server.takeRequest().path)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)).setHeadersDelay(2, TimeUnit.SECONDS))
        val started = System.nanoTime()
        var cancelled = false
        try {
            withTimeout(300) { HTTPTileFetcher.fetch(server.url("/slow").toString(), client) }
        } catch (e: TimeoutCancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
    }
}
