package com.jacobusoettle.satmap.map

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.ElevationGrid
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** The contour and colour rows of the answer table (DESIGN.md §3.2) from the real `SatelliteRenderer`, through a real
 *  `OkHttpClient` with the interceptor, with a fake elevation server and clock: codes, `Cache-Control`, the 1×1 blank,
 *  what each layer lacks; and the heights on screen (`heightExtremes`). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerrainAnswersTest {
    private val terrain = TerrainTiles()

    /** A z15 tile on the Kruger farm (the screenshots' place). */
    private val t = TileID.containing(GeoPoint(-24.99, 31.59), 15)

    /** The map the layers belong to (held here, as its `DrawnImagery` holds it). */
    private val drawnMap = DrawnMap()
    private lateinit var contours: TileLayer
    private lateinit var colours: TileLayer
    private val client = OkHttpClient.Builder().addInterceptor(TileInterceptor()).build()

    @Before
    fun setUp() {
        terrain.install()
        contours = TileLayer(TileUrls.nextGeneration(), TileKind.contours, drawnMap).also { TileLayers.add(it) }
        colours = TileLayer(TileUrls.nextGeneration(), TileKind.colours, drawnMap).also { TileLayers.add(it) }
    }

    @After
    fun tearDown() {
        TileLayers.remove(contours.gen)
        TileLayers.remove(colours.gen)
        SatelliteRenderer.resetForTests()
    }

    private fun get(url: String): Response = client.newCall(Request.Builder().url(url).build()).execute()

    private fun contours(tile: TileID = t, scale: Int = 263) =
        get("https://satmap.local/contours/${contours.gen}/$scale/${tile.z}/${tile.x}/${tile.y}")

    private fun colours(range: ElevationRange?, tile: TileID = t) =
        get(TileUrls.colours(colours.gen, range).replace("{z}/{x}/{y}", "${tile.z}/${tile.x}/${tile.y}"))

    /** Heights rising 0.5 m a pixel eastwards from 990 m at the tile's west edge. */
    private fun eastwards(px: Int, @Suppress("UNUSED_PARAMETER") py: Int): Double = 990.0 + 0.5 * (px - t.x * 256)

    private fun assertPNG(r: Response): ByteArray {
        assertEquals(200, r.code)
        assertEquals("image/png", r.header("Content-Type"))
        val bytes = r.body!!.bytes()
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(256, b.width)
        assertEquals(256, b.height)
        return bytes
    }

    private fun assertBlank(r: Response) {
        assertEquals(200, r.code)
        assertEquals("image/png", r.header("Content-Type"))
        assertNull(r.header("Cache-Control"))
        assertArrayEquals(TileAnswer.BLANK_PNG, r.body!!.bytes())
    }

    // Contour lines

    @Test
    fun completeLinesAreAPNGForGood() {
        terrain.serve(terrain.around(t), ::eastwards)
        val r = contours()
        assertNull(r.header("Cache-Control"))
        val png = assertPNG(r)
        assertTrue(t in contours.answered)
        assertFalse(contours.lacking.containsKey(t))
        // From memory the next time.
        val fetched = terrain.fetched.get()
        assertArrayEquals(png, contours().body!!.bytes())
        assertEquals(fetched, terrain.fetched.get())
    }

    @Test
    fun linesApproximatedAtTheEdgesAreAskedForAgainIn30Seconds() {
        // The tile's own heights, and three around it; the other five fail (fewer than the 8 that pause the network).
        val around = terrain.around(t)
        terrain.serve(around.take(4) + t, ::eastwards)
        for (d in around.drop(4)) if (d != t) terrain.server[d] = TileFetchResult.Failed
        val first = contours()
        assertEquals("max-age=30", first.header("Cache-Control"))
        val png = assertPNG(first)
        assertEquals(true, contours.lacking[t])
        // Before then, what there is, for the time left.
        terrain.clock += 10
        val again = contours()
        assertEquals("max-age=20", again.header("Cache-Control"))
        assertArrayEquals(png, again.body!!.bytes())
        // Then asked for again, with every height there now: complete.
        terrain.clock += 20
        terrain.serve(around, ::eastwards)
        val complete = contours()
        assertNull(complete.header("Cache-Control"))
        assertPNG(complete)
        assertFalse(contours.lacking.containsKey(t))
    }

    @Test
    fun flatGroundIsTheBlankPNGNever204() {
        terrain.serve(terrain.around(t)) { _, _ -> 300.0 }
        assertBlank(contours())
        assertFalse(contours.lacking.containsKey(t))
        val fetched = terrain.fetched.get()
        assertBlank(contours())
        assertEquals(fetched, terrain.fetched.get())
    }

    @Test
    fun aZoomWithoutContoursIsTheBlankPNG() {
        assertBlank(contours(t.ancestor(3))) // z12 at 2.63: no interval
        assertEquals(0, terrain.fetched.get())
    }

    @Test
    fun noHeightsIsA204AskedForAgainIn30Seconds() {
        val r = contours()
        assertEquals(204, r.code)
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertEquals(false, contours.lacking[t])
        terrain.clock += 12
        assertEquals("max-age=18", contours().header("Cache-Control"))
    }

    @Test
    fun reloadForgetsTheContourAndHeightGates() {
        for (d in terrain.around(t)) terrain.server[d] = TileFetchResult.Failed
        assertEquals(204, contours().code)
        assertEquals(204, colours(ElevationRange(900.0, 1200.0)).code)
        // The network is back (`AppModel.requestReload`): asked for again at once.
        terrain.serve(terrain.around(t), ::eastwards)
        TileRepository.shared.resetFailureState()
        SatelliteRenderer.reload()
        assertNull(contours().header("Cache-Control"))
        assertEquals(200, colours(ElevationRange(900.0, 1200.0)).code)
    }

    // Elevation colours

    @Test
    fun withoutARangeTheHeightsAreLoadedAndTheTileIsBlank() {
        terrain.serve(terrain.around(t), ::eastwards)
        val arrived = ArrayList<Pair<TileID, SatelliteRenderer.Content>>()
        drawnMap.onArrival = { tile, content -> arrived.add(Pair(tile, content)) }
        assertBlank(colours(null))
        assertFalse(colours.lacking.containsKey(t))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Pair(t, SatelliteRenderer.Content.colours)), arrived)
        val e = SatelliteRenderer.heightExtremes(t.worldBounds, 15, listOf(XY((t.worldBounds.minX + t.worldBounds.maxX) / 2, t.worldBounds.minY)))
        assertNotNull(e)
        assertEquals(989.75, e!!.low, 0.01)
        assertEquals(1117.75, e.high, 0.01)
        assertTrue(e.complete)
    }

    @Test
    fun aRangesColoursAreAPNGForGood() {
        terrain.serve(terrain.around(t), ::eastwards)
        val r = colours(ElevationRange(990.0, 1118.0))
        assertNull(r.header("Cache-Control"))
        val bytes = assertPNG(r)
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val west = b.getPixel(2, 128)
        val east = b.getPixel(253, 128)
        assertEquals(128, Color.alpha(west))
        assertTrue(Integer.toHexString(west), Color.blue(west) > Color.red(west)) // low: blue
        assertTrue(Integer.toHexString(east), Color.red(east) > Color.blue(east)) // high: red
        assertFalse(colours.lacking.containsKey(t))
    }

    @Test
    fun coloursApproximatedAtTheEdgesAreAskedForAgainIn30Seconds() {
        terrain.serve(listOf(t), ::eastwards)
        val r = colours(ElevationRange(990.0, 1118.0))
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertPNG(r)
        assertEquals(true, colours.lacking[t])
        // Without a range, the same heights: blank, but lacking them still (a new layer may get them: `reload`).
        assertBlank(colours(null))
        assertEquals(true, colours.lacking[t])
    }

    @Test
    fun noHeightsIsA204ForColoursAndBlankWithoutARange() {
        val r = colours(ElevationRange(0.0, 100.0))
        assertEquals(204, r.code)
        assertEquals("max-age=30", r.header("Cache-Control"))
        assertEquals(false, colours.lacking[t])
        assertBlank(colours(null))
        assertEquals(false, colours.lacking[t])
    }

    // The heights on screen

    @Test
    fun heightExtremesTakeTheLandUnderThePointsWithinTheRect() {
        val n = 65
        val a = TileID(15, t.x, t.y)
        val b = TileID(15, t.x + 1, t.y)
        // a: 100 m in its west half, 400 m in its east half, a sea node (−20 m) in the middle; b: 1000 m.
        SatelliteRenderer.heightGrids.set(
            SatelliteRenderer.HeightGrid(ElevationGrid(n, FloatArray(n * n) { i -> if (i == 32 * n + 32) -20f else if (i % n < 32) 100f else 400f }), true),
            a,
        )
        SatelliteRenderer.heightGrids.set(SatelliteRenderer.HeightGrid(ElevationGrid(n, FloatArray(n * n) { 1000f }), true), b)
        val ab = a.worldBounds
        val w = ab.maxX - ab.minX
        val mid = XY(ab.minX + w / 2, ab.minY + w / 2)
        // Only a under the points; the whole of a: 100–400 m, the sea left out.
        assertEquals(SatelliteRenderer.Extremes(100.0, 400.0, true), SatelliteRenderer.heightExtremes(ab, 15, listOf(mid)))
        // The west quarter of a only.
        val west = WorldRect(ab.minX, ab.minY, ab.minX + w / 4, ab.maxY)
        assertEquals(SatelliteRenderer.Extremes(100.0, 100.0, true), SatelliteRenderer.heightExtremes(west, 15, listOf(mid)))
        // Both under the points: up to b's 1000 m.
        val both = WorldRect(ab.minX, ab.minY, ab.maxX + w, ab.maxY)
        val e = SatelliteRenderer.heightExtremes(both, 15, listOf(mid, XY(ab.maxX + w / 2, mid.y)))
        assertEquals(SatelliteRenderer.Extremes(100.0, 1000.0, true), e)
        // A tile under the points without heights: incomplete, until it has given up for now (unavailable offline).
        val c = TileID(15, t.x + 2, t.y)
        val points = listOf(mid, XY(c.worldBounds.minX + w / 2, mid.y))
        assertEquals(false, SatelliteRenderer.heightExtremes(both, 15, points)!!.complete)
        assertEquals(204, colours(ElevationRange(0.0, 100.0), c).code)
        assertEquals(true, SatelliteRenderer.heightExtremes(both, 15, points)!!.complete)
        // Only sea, or nothing in memory: none.
        assertNull(SatelliteRenderer.heightExtremes(both, 15, listOf(XY(c.worldBounds.minX + w / 2, mid.y))))
    }
}
