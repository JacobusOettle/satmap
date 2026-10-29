package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WorldRect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** The loupe's imagery (`SatelliteRenderer.drawImagery`, DESIGN.md D7): each spot's own tile in memory, else the
 *  nearest coarser one (up to 12 levels up) enlarged and cut to the spot; what is missing loaded urgently, what is in
 *  memory decoded off the main thread, and both announced. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawImageryTest {
    /** A z18 tile on the Kruger farm, at the west of its z14 ancestor (so the one east of it shares it). */
    private val t = TileID.containing(GeoPoint(-24.99, 31.59), 18).let { TileID(it.z, it.x and 15.inv(), it.y) }
    private val east = TileID(t.z, t.x + 1, t.y)
    private val green = Color.rgb(40, 200, 60)
    private val red = Color.rgb(200, 30, 30)

    /** What the server has, and the imagery tiles asked of it. */
    private val server = ConcurrentHashMap<TileID, TileFetchResult>()
    private val fetched = ConcurrentLinkedQueue<TileID>()
    private val imageryPath = Regex("/tile/(\\d+)/(\\d+)/(\\d+)")

    @Before
    fun setUp() {
        TileRepository.shared = TileRepository(
            browseClient = OkHttpClient(),
            fetchURL = { url ->
                val (z, y, x) = imageryPath.find(url)!!.destructured
                val tile = TileID(z.toInt(), x.toInt(), y.toInt())
                fetched.add(tile)
                server[tile] ?: TileFetchResult.Missing
            },
            isOnlineNow = { true },
            now = { 5000.0 },
            openStore = { null },
        )
        SatelliteRenderer.resetForTests()
    }

    @After
    fun tearDown() {
        SatelliteRenderer.resetForTests()
    }

    private fun jpeg(colour: Int): ByteArray {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        b.eraseColor(colour)
        return ByteArrayOutputStream().use {
            b.compress(Bitmap.CompressFormat.JPEG, 95, it)
            it.toByteArray()
        }
    }

    private fun decoded(colour: Int): Bitmap = jpeg(colour).let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun assertColour(expected: Int, p: Int) {
        assertTrue(
            "${Integer.toHexString(p)}, expected ${Integer.toHexString(expected)}",
            Color.alpha(p) == 255 && abs(Color.red(p) - Color.red(expected)) < 8 && abs(Color.green(p) - Color.green(expected)) < 8,
        )
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        for (i in 0 until 500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("waited for $what")
    }

    @Test
    fun eachSpotsOwnTileElseTheNearestCoarserOneCutToTheSpot() {
        SatelliteRenderer.decoded.set(decoded(green), t)
        ImageryMemory.tiles.set(jpeg(red), t.ancestor(4))
        // The two spots side by side into a 512 × 256 rect, 10 px in from the canvas's corner.
        val r = WorldRect(t.worldBounds.minX, t.worldBounds.minY, east.worldBounds.maxX, east.worldBounds.maxY)
        val out = Bitmap.createBitmap(532, 276, Bitmap.Config.ARGB_8888)
        SatelliteRenderer.drawImagery(r, 18, Canvas(out), RectF(10f, 10f, 522f, 266f))
        assertColour(green, out.getPixel(138, 138))
        assertColour(green, out.getPixel(264, 138)) // the ancestor enlarged doesn't cover the spot with its own tile
        assertColour(red, out.getPixel(270, 138))
        assertColour(red, out.getPixel(518, 262))
        assertEquals(0, Color.alpha(out.getPixel(5, 5))) // outside the rect
        assertEquals(0, Color.alpha(out.getPixel(527, 271)))
        // The ancestor was decoded to be drawn; the spot without its own tile is loaded, urgently.
        assertTrue(SatelliteRenderer.decoded.contains(t.ancestor(4)))
        waitFor("the east spot asked for") { east in fetched }
        assertTrue(t !in fetched)
    }

    @Test
    fun aTileInMemoryIsDecodedOffTheMainThreadAndAnnounced() {
        ImageryMemory.tiles.set(jpeg(green), t)
        val map = DrawnMap()
        val arrived = ConcurrentLinkedQueue<TileID>()
        map.onArrival = { tile, content -> if (content == SatelliteRenderer.Content.imagery) arrived.add(tile) }
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        // Not decoded yet, and nothing coarser in memory: nothing drawn this time.
        assertEquals(0, Color.alpha(out.getPixel(128, 128)))
        waitFor("the tile announced") { t in arrived }
        assertTrue(SatelliteRenderer.decoded.contains(t))
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        assertColour(green, out.getPixel(128, 128))
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aMissingTileArrivesAndIsAnnounced() {
        server[t] = TileFetchResult.Data(jpeg(green))
        val map = DrawnMap()
        val arrived = ConcurrentLinkedQueue<TileID>()
        map.onArrival = { tile, content -> if (content == SatelliteRenderer.Content.imagery) arrived.add(tile) }
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        waitFor("the tile loaded") { t in arrived && ImageryMemory.tiles.contains(t) }
        // In memory now: decoded for the next draw, then drawn.
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        waitFor("the tile decoded") { SatelliteRenderer.decoded.contains(t) }
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        assertColour(green, out.getPixel(128, 128))
    }

    @Test
    fun coarserTilesAtMost12LevelsUp() {
        ImageryMemory.tiles.set(jpeg(red), t.ancestor(13))
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        assertEquals(0, Color.alpha(out.getPixel(128, 128)))
        ImageryMemory.tiles.set(jpeg(red), t.ancestor(12))
        SatelliteRenderer.drawImagery(t.worldBounds, 18, Canvas(out), RectF(0f, 0f, 256f, 256f))
        assertColour(red, out.getPixel(128, 128))
    }
}
