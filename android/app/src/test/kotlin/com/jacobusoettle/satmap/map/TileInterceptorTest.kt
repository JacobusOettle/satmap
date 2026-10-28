package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.PNGDecoder
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import kotlinx.coroutines.awaitCancellation
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The interceptor's answers through a real `OkHttpClient` (DESIGN.md §3.2): codes, `Cache-Control`, the bookkeeping of
 *  each layer, cancellation; and the imagery rows of the answer table from the real `SatelliteRenderer` with a fake
 *  network. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileInterceptorTest {
    private val t = TileID.containing(GeoPoint(-24.99, 31.59), 18)
    private lateinit var layer: TileLayer
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        layer = TileLayer(TileUrls.nextGeneration(), TileKind.imagery, DrawnMap())
        TileLayers.add(layer)
    }

    @After
    fun tearDown() {
        TileLayers.remove(layer.gen)
        SatelliteRenderer.resetForTests()
    }

    private fun client(answer: suspend (TileRoute, TileLayer) -> TileAnswer) =
        OkHttpClient.Builder().addInterceptor(TileInterceptor(answer)).build()

    private fun imageryURL(gen: Int = layer.gen, tile: TileID = t) = "https://satmap.local/imagery/$gen/${tile.z}/${tile.x}/${tile.y}"

    private fun OkHttpClient.get(url: String): Response = newCall(Request.Builder().url(url).build()).execute()

    // The interceptor, with a fake renderer

    @Test
    fun theTilesOwnData() {
        var asked: TileRoute? = null
        val r = client { route, l ->
            asked = route
            assertEquals(layer, l)
            TileAnswer(200, jpeg, "image/jpeg", maxAge = null, lacking = null)
        }.get(imageryURL())
        assertEquals(TileRoute.Imagery(layer.gen, t), asked)
        assertEquals(200, r.code)
        assertEquals("image/jpeg", r.header("Content-Type"))
        assertNull(r.header("Cache-Control"))
        assertArrayEquals(jpeg, r.body!!.bytes())
        assertTrue(t in layer.answered)
        assertFalse(layer.lacking.containsKey(t))
    }

    @Test
    fun aStandInIsAskedForAgainWhenItsRetryIsDue() {
        val r = client { _, _ -> TileAnswer(200, jpeg, "image/jpeg", maxAge = 600, lacking = true) }.get(imageryURL())
        assertEquals(200, r.code)
        assertEquals("max-age=600", r.header("Cache-Control"))
        assertEquals(true, layer.lacking[t])
        // Then its own tile: no longer lacking.
        client { _, _ -> TileAnswer(200, jpeg, "image/jpeg", maxAge = null, lacking = null) }.get(imageryURL()).close()
        assertFalse(layer.lacking.containsKey(t))
    }

    @Test
    fun nothingIsA204AskedForAgain() {
        val r = client { _, _ -> TileAnswer.empty(maxAge = 30) }.get(imageryURL())
        assertEquals(204, r.code)
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertEquals(0, r.body!!.bytes().size)
        assertEquals(false, layer.lacking[t])
        assertTrue(t in layer.answered)
    }

    @Test
    fun aBlankContourTileIsA1x1TransparentPNG() {
        val contours = TileLayer(TileUrls.nextGeneration(), TileKind.contours, DrawnMap())
        TileLayers.add(contours)
        try {
            val r = client { route, _ ->
                assertEquals(TileRoute.Contours(contours.gen, t, 2.63), route)
                TileAnswer(200, TileAnswer.BLANK_PNG, "image/png", maxAge = null, lacking = null)
            }.get("https://satmap.local/contours/${contours.gen}/263/${t.z}/${t.x}/${t.y}")
            assertEquals(200, r.code)
            assertNull(r.header("Cache-Control"))
            val png = PNGDecoder.decode(r.body!!.bytes())!!
            assertEquals(1, png.width)
            assertEquals(1, png.height)
            assertEquals(0, png.pixels[3].toInt()) // transparent
        } finally {
            TileLayers.remove(contours.gen)
        }
    }

    @Test
    fun aMalformedURLOrARemovedSourceIsA204ForGood() {
        val calls = AtomicInteger()
        val c = client { _, _ ->
            calls.incrementAndGet()
            TileAnswer.empty(maxAge = 30)
        }
        val gone = TileUrls.nextGeneration()
        for (url in listOf("https://satmap.local/imagery/${layer.gen}/23/0/0", "https://satmap.local/nothing", imageryURL(gen = gone))) {
            val r = c.get(url)
            assertEquals(url, 204, r.code)
            assertNull(url, r.header("Cache-Control"))
            r.close()
        }
        assertEquals(0, calls.get())
        assertTrue(layer.answered.isEmpty())
    }

    @Test
    fun aFailureIsNothingForNowNotAnError() {
        val r = client { _, _ -> throw IllegalStateException("broken") }.get(imageryURL())
        assertEquals(204, r.code)
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertEquals(false, layer.lacking[t])
    }

    @Test
    fun otherHostsPassThrough() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        try {
            val r = client { _, _ -> fail("not a tile"); TileAnswer.empty(null) }.get(server.url("/style.json").toString())
            assertEquals(200, r.code)
            assertEquals("hello", r.body!!.string())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aRequestMapLibreCancelsFailsAndItsAnswerIsCancelled() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val c = client { _, _ ->
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }
        val call = c.newCall(Request.Builder().url(imageryURL()).build())
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Result<Response>> { runCatching { call.execute() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val at = System.nanoTime()
        call.cancel()
        val failure = result.get(5, TimeUnit.SECONDS).exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Canceled", failure!!.message)
        assertTrue((System.nanoTime() - at) / 1_000_000 < 1000) // noticed within 50 ms or so
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertFalse(t in layer.answered)
        executor.shutdown()
    }

    // The imagery rows of the answer table, from SatelliteRenderer

    private var clock = 5000.0
    private val server = ConcurrentHashMap<TileID, TileFetchResult>()
    private val fetched = AtomicInteger()
    private val imageryPath = Regex("/tile/(\\d+)/(\\d+)/(\\d+)")

    private fun renderer(): OkHttpClient {
        TileRepository.shared = TileRepository(
            browseClient = OkHttpClient(),
            fetchURL = { url ->
                fetched.incrementAndGet()
                val (z, y, x) = imageryPath.find(url)!!.destructured
                server[TileID(z.toInt(), x.toInt(), y.toInt())] ?: TileFetchResult.Missing
            },
            isOnlineNow = { true },
            now = { clock },
            openStore = { null },
        )
        SatelliteRenderer.resetForTests()
        SatelliteRenderer.now = { clock }
        return OkHttpClient.Builder().addInterceptor(TileInterceptor()).build()
    }

    /** A real 256×256 JPEG of one colour. */
    private fun realJpeg(colour: Int): ByteArray {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        b.eraseColor(colour)
        return ByteArrayOutputStream().use {
            b.compress(Bitmap.CompressFormat.JPEG, 95, it)
            it.toByteArray()
        }
    }

    private fun assertStandIn(r: Response, maxAge: Int, colour: Int) {
        assertEquals(200, r.code)
        assertEquals("image/jpeg", r.header("Content-Type"))
        assertEquals("max-age=$maxAge", r.header("Cache-Control"))
        val bytes = r.body!!.bytes()
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        val p = image.getPixel(128, 128)
        assertTrue(Integer.toHexString(p), abs(Color.red(p) - Color.red(colour)) < 8 && abs(Color.green(p) - Color.green(colour)) < 8)
    }

    @Test
    fun imageryInMemoryIsAnsweredAtOnce() {
        val c = renderer()
        ImageryMemory.tiles.set(jpeg, t)
        val r = c.get(imageryURL())
        assertEquals(200, r.code)
        assertNull(r.header("Cache-Control"))
        assertArrayEquals(jpeg, r.body!!.bytes())
        assertEquals(0, fetched.get())
    }

    @Test
    fun imageryFromTheNetworkGoesIntoMemory() {
        val c = renderer()
        server[t] = TileFetchResult.Data(jpeg)
        val r = c.get(imageryURL())
        assertEquals(200, r.code)
        assertNull(r.header("Cache-Control"))
        assertArrayEquals(jpeg, r.body!!.bytes())
        assertArrayEquals(jpeg, ImageryMemory.tiles.peek(t))
        assertFalse(layer.lacking.containsKey(t))
    }

    @Test
    fun beyondTheImagerysDepthACropOfTheAncestorFor600Seconds() {
        val c = renderer()
        val green = Color.rgb(40, 200, 60)
        server[t.ancestor(2)] = TileFetchResult.Data(realJpeg(green))
        assertStandIn(c.get(imageryURL()), 600, green)
        assertEquals(true, layer.lacking[t])
        assertEquals(3, fetched.get()) // t, its parent and the one that exists
        // Asked again before the retry is due: the same crop, for what is left, without a request.
        clock += 100
        assertStandIn(c.get(imageryURL()), 500, green)
        assertEquals(3, fetched.get())
    }

    @Test
    fun afterAFailureACropOfAnAncestorInMemoryFor30Seconds() {
        val c = renderer()
        val red = Color.rgb(200, 30, 30)
        server[t] = TileFetchResult.Failed
        ImageryMemory.tiles.set(realJpeg(red), t.ancestor(5))
        assertStandIn(c.get(imageryURL()), 30, red)
        assertEquals(1, fetched.get()) // no ancestor asked of the network after a failure
        clock += 29.5
        assertStandIn(c.get(imageryURL()), 1, red)
    }

    @Test
    fun nothingAnywhereIsA204For30Seconds() {
        val c = renderer()
        server[t] = TileFetchResult.Failed
        val r = c.get(imageryURL())
        assertEquals(204, r.code)
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertEquals(false, layer.lacking[t])
        clock += 12
        assertEquals("max-age=18", c.get(imageryURL()).header("Cache-Control"))
        assertEquals(1, fetched.get())
        // Once due, asked again.
        clock += 18
        server[t] = TileFetchResult.Data(jpeg)
        val again = c.get(imageryURL())
        assertEquals(200, again.code)
        assertNull(again.header("Cache-Control"))
        assertFalse(layer.lacking.containsKey(t))
    }

    @Test
    fun reloadForgetsFailuresButNotTheImagerysDepth() {
        val c = renderer()
        server[t] = TileFetchResult.Failed
        c.get(imageryURL()).close()
        val deep = TileID(t.z, t.x + 1, t.y)
        server[deep.ancestor(1)] = TileFetchResult.Data(realJpeg(Color.BLUE))
        c.get(imageryURL(tile = deep)).close()
        assertEquals(3, fetched.get()) // t; deep (missing) and its parent
        SatelliteRenderer.reload()
        server[t] = TileFetchResult.Data(jpeg)
        assertEquals(200, c.get(imageryURL()).code)
        assertEquals("max-age=600", c.get(imageryURL(tile = deep)).header("Cache-Control"))
        assertEquals(4, fetched.get())
    }
}
