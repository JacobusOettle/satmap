package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.ContourLine
import com.jacobusoettle.satmap.core.ContourPolicy
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.ElevationGrid
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.XY
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The contour and colour tiles drawn on the phone (rendering spec §1.8, §1.9, §2.8): the colours and widths of
 *  `ContourRenderer.swift`, halo then line, round caps, labels on checkerboard tiles over a cleared box; the colour
 *  tiles' nodes centred on their pixels; and both drawn from the heights of a fake elevation server. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileOverlaysTest {
    private val terrain = TerrainTiles()

    /** A z15 tile on the Kruger farm (the screenshots' place). */
    private val t = TileID.containing(GeoPoint(-24.99, 31.59), 15)

    @Before
    fun setUp() {
        terrain.install()
    }

    @After
    fun tearDown() {
        SatelliteRenderer.resetForTests()
    }

    private fun decode(png: ByteArray): Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)

    private fun assertNear(message: String, expected: Double, actual: Int, tolerance: Double) {
        assertTrue("$message: $actual, expected $expected", abs(actual - expected) <= tolerance)
    }

    // Contour lines

    @Test
    fun theContourColoursAreIOSs() {
        // UIColor(red: 1.0, green: 0.84, blue: 0.32, alpha: 0.85) and the others, in 8 bits.
        assertEquals(0xD9FFD652.toInt(), ContourRenderer.MINOR)
        assertEquals(0xFFFFBD29.toInt(), ContourRenderer.INDEX)
        assertEquals(0x61000000, ContourRenderer.HALO)
        assertEquals(0xFFFFEDB3.toInt(), ContourRenderer.LABEL_FILL)
        assertEquals(0xBF000000.toInt(), ContourRenderer.LABEL_STROKE)
    }

    @Test
    fun theWidthsAreIOSsTimesTheScale() {
        assertEquals(2.1, ContourRenderer.minorWidth(3.0), 1e-12)
        assertEquals(3.9, ContourRenderer.indexWidth(3.0), 1e-12)
        assertEquals(2.7, ContourRenderer.haloExtra(3.0), 1e-12)
        assertEquals(1.4, ContourRenderer.minorWidth(2.0), 1e-12)
        assertEquals(2.6, ContourRenderer.indexWidth(2.0), 1e-12)
        assertEquals(1.8, ContourRenderer.haloExtra(2.0), 1e-12)
        assertEquals(22.355, ContourRenderer.labelSize(2.63), 1e-12)
        assertTrue(ContourRenderer.isIndex(1500.0, 50.0))
        assertTrue(ContourRenderer.isIndex(-100.0, 100.0))
        assertTrue(ContourRenderer.isIndex(0.0, 25.0))
        assertFalse(ContourRenderer.isIndex(1510.0, 50.0))
        assertFalse(ContourRenderer.isIndex(75.0, 50.0))
    }

    /** A line across the tile (or from `x0` to `x1`, in pixels) along pixel row `row` (through the pixels' middle). The
     *  grid has 128 cells, so 2 pixels a cell. */
    private fun across(level: Double, row: Int, x0: Double = -2.0, x1: Double = 258.0) =
        ContourLine(level, listOf(XY(x0 / 2, (row + 0.5) / 2), XY(x1 / 2, (row + 0.5) / 2)), isClosed = false)

    private fun draw(lines: List<ContourLine>, s: Double = 3.0, labels: Boolean = false): Bitmap {
        val png = ContourRenderer().draw(lines, cells = 128, ContourPolicy.Intervals(20.0, 100.0), s, smooth = false, labels = labels)
        assertNotNull(png)
        return decode(png!!)
    }

    @Test
    fun aHaloThenTheLineAtTheirWidths() {
        val b = draw(listOf(across(1020.0, 64), across(1100.0, 192)))
        assertEquals(256, b.width)
        assertEquals(256, b.height)
        fun alpha(y: Int) = Color.alpha(b.getPixel(128, y))
        // The minor line, 2.1 px (63.45 to 65.55), in its halo, 4.8 px (62.1 to 66.9).
        assertEquals(0, alpha(61))
        assertEquals(0, alpha(67))
        val haloOnly = b.getPixel(128, 62) // 90 % covered by the halo alone
        assertNear("halo alpha", 0.38 * 0.9 * 255, Color.alpha(haloOnly), 12.0)
        assertTrue(Integer.toHexString(haloOnly), Color.red(haloOnly) < 8 && Color.green(haloOnly) < 8)
        // Its middle: the minor colour (85 %) over the halo (38 %).
        val minor = b.getPixel(128, 64)
        assertNear("minor alpha", (0.85 + 0.38 * 0.15) * 255, Color.alpha(minor), 4.0)
        assertNear("minor red", 0.85 / 0.907 * 255, Color.red(minor), 5.0)
        assertNear("minor green", 0.85 * 0.84 / 0.907 * 255, Color.green(minor), 5.0)
        assertNear("minor blue", 0.85 * 0.32 / 0.907 * 255, Color.blue(minor), 5.0)
        // The index line, 3.9 px (190.55 to 194.45), in its halo, 6.6 px (189.2 to 195.8): opaque, its own colour.
        assertEquals(0, alpha(188))
        assertEquals(0, alpha(196))
        assertTrue(alpha(189) in 40..110)
        assertTrue(alpha(195) in 40..110)
        for (y in 191..193) {
            val p = b.getPixel(128, y)
            assertEquals(Integer.toHexString(p), 255, Color.alpha(p))
            assertNear("index red", 255.0, Color.red(p), 1.0)
            assertNear("index green", 0xBD.toDouble(), Color.green(p), 1.0)
            assertNear("index blue", 0x29.toDouble(), Color.blue(p), 1.0)
        }
    }

    @Test
    fun linesHaveRoundEnds() {
        // From 40 to 100 px: the ends (and their halo, 2.4 px around) are round, beyond the line's own length.
        val b = draw(listOf(across(1020.0, 64, x0 = 40.0, x1 = 100.0)))
        assertTrue(Color.alpha(b.getPixel(101, 64)) > 0)
        assertTrue(Color.alpha(b.getPixel(38, 64)) > 0)
        assertEquals(0, Color.alpha(b.getPixel(103, 64)))
        assertEquals(0, Color.alpha(b.getPixel(36, 64)))
        // Round, not square: off the line's axis the end is narrower (a square end would cover a third of this pixel).
        assertTrue(Color.alpha(b.getPixel(102, 62)) < 12)
    }

    /** Pixels like the labels' fill (pale yellow, opaque). */
    private fun labelPixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 200 && Color.red(p) > 240 && Color.green(p) in 220..250 && Color.blue(p) in 150..200) n++
            }
        }
        return n
    }

    @Test
    fun indexLinesAreLabelledOverAClearedBox() {
        val line = across(1100.0, 127)
        val plain = draw(listOf(line), s = 2.63)
        val labelled = draw(listOf(line), s = 2.63, labels = true)
        assertEquals(0, labelPixels(plain))
        assertTrue(labelPixels(labelled) > 20)
        // Along the line, the label's box is cleared: a gap of transparent pixels left of the text (2·s wide), where
        // the line runs on unlabelled.
        val row = (0 until 128).map { Color.alpha(labelled.getPixel(it, 127)) }
        assertTrue(row.toString(), row.any { it == 0 })
        assertTrue((0 until 128).all { Color.alpha(plain.getPixel(it, 127)) == 255 })
        // Minor lines aren't labelled.
        assertEquals(0, labelPixels(draw(listOf(across(1120.0, 127)), s = 2.63, labels = true)))
    }

    // Contour tiles from the heights

    /** Heights rising 0.5 m a pixel eastwards from 990 m at the tile's west edge: lines every 20 m, 1000 m and 1100 m
     *  bold. */
    private fun eastwards(px: Int, @Suppress("UNUSED_PARAMETER") py: Int): Double = 990.0 + 0.5 * (px - t.x * 256)

    @Test
    fun aContourTileIsDrawnFromItsHeightsAndKept() = runBlocking {
        terrain.serve(terrain.around(t), ::eastwards)
        val tile = ContourRenderer.shared.tile(t, 2.63)
        assertTrue(tile is ContourRenderer.Tile.Lines)
        tile as ContourRenderer.Tile.Lines
        assertTrue(tile.complete)
        val b = decode(tile.png)
        // 20 m apart at s = 2.63 and z15 (point zoom 13.6), 40 px: the 1040 m line at x = 100, in the minor colour.
        val p = b.getPixel(100, 64)
        assertTrue(Integer.toHexString(p), Color.alpha(p) > 200 && Color.red(p) > 230 && Color.blue(p) < 100)
        assertEquals(0, Color.alpha(b.getPixel(80, 64))) // between the lines
        // Complete: kept, the same PNG for the next request.
        assertSame(tile.png, (ContourRenderer.shared.tile(t, 2.63) as ContourRenderer.Tile.Lines).png)
        // At another scale (the car's), drawn again.
        val other = ContourRenderer.shared.tile(t, 2.0) as ContourRenderer.Tile.Lines
        assertFalse(other.png === tile.png)
    }

    @Test
    fun withoutTheHeightsAroundItATileIsApproximatedAndNotKept() = runBlocking {
        terrain.serve(listOf(t), ::eastwards)
        val tile = ContourRenderer.shared.tile(t, 2.63) as ContourRenderer.Tile.Lines
        assertFalse(tile.complete)
        terrain.serve(terrain.around(t), ::eastwards)
        terrain.clock += 600 // the missing elevation tiles are asked for again
        val again = ContourRenderer.shared.tile(t, 2.63) as ContourRenderer.Tile.Lines
        assertTrue(again.complete)
    }

    @Test
    fun flatGroundIsBlankAndNoHeightsUnavailable() = runBlocking {
        terrain.serve(terrain.around(t)) { _, _ -> 300.0 }
        assertEquals(ContourRenderer.Tile.Blank, ContourRenderer.shared.tile(t, 2.63))
        val elsewhere = TileID(15, t.x + 5, t.y)
        assertEquals(ContourRenderer.Tile.Unavailable, ContourRenderer.shared.tile(elsewhere, 2.63))
    }

    @Test
    fun aZoomWithoutContoursIsBlankWithoutHeights() = runBlocking {
        // z12 at 2.63: point zoom 10.6, below the first interval.
        assertEquals(ContourRenderer.Tile.Blank, ContourRenderer.shared.tile(t.ancestor(3), 2.63))
        assertEquals(0, terrain.fetched.get())
    }

    @Test
    fun labelsOnlyOnCheckerboardTiles() = runBlocking {
        // Heights rising southwards: a bold 1100 m line across the middle of a tile and its neighbour east.
        val east = TileID(15, t.x + 1, t.y)
        terrain.serve((terrain.around(t) + terrain.around(east)).distinct()) { _, py -> 1036.0 + 0.5 * (py - t.y * 256) }
        val even = if ((t.x + t.y) % 2 == 0) t else east
        val odd = if (even == t) east else t
        val labelled = decode((ContourRenderer.shared.tile(even, 2.63) as ContourRenderer.Tile.Lines).png)
        val plain = decode((ContourRenderer.shared.tile(odd, 2.63) as ContourRenderer.Tile.Lines).png)
        assertTrue(labelPixels(labelled) > 20)
        assertEquals(0, labelPixels(plain))
    }

    @Test
    fun heightsOnA65NodeGridFromEdgeToEdge() = runBlocking {
        terrain.serve(terrain.around(t), ::eastwards)
        val tile = HeightGrids.shared.tile(t) as HeightGrids.Tile.Grid
        assertTrue(tile.complete)
        assertEquals(65, tile.grid.nodes)
        // Node i is at pixel 4i, between the pixel centres either side: 990 + 2i − 0.25 m.
        assertEquals(989.75, tile.grid.values[0].toDouble(), 0.01)
        assertEquals(989.75 + 64, tile.grid.values[32].toDouble(), 0.01)
        assertEquals(989.75 + 128, tile.grid.values[64 * 65 + 64].toDouble(), 0.01)
        assertEquals(HeightGrids.Tile.Unavailable, HeightGrids.shared.tile(TileID(15, t.x + 5, t.y)))
    }

    // Colour tiles

    private fun assertColour(p: Int, at: Double, message: String) {
        val c = ElevationColours.colour(at)
        assertNear("$message alpha", 128.0, Color.alpha(p), 1.0)
        assertNear("$message red", c.red * 255, Color.red(p), 3.0)
        assertNear("$message green", c.green * 255, Color.green(p), 3.0)
        assertNear("$message blue", c.blue * 255, Color.blue(p), 3.0)
    }

    @Test
    fun colourTilesPutTheirEdgeNodesOnTheTilesEdges() {
        // 0 m (blue) everywhere but the east column of nodes, 1000 m (red).
        val n = 65
        val grid = ElevationGrid(n, FloatArray(n * n) { if (it % n == n - 1) 1000f else 0f })
        val b = decode(colourPng(grid, ElevationRange(0.0, 1000.0))!!)
        assertEquals(256, b.width)
        assertEquals(256, b.height)
        // Each node's pixel is centred on it (4 px apart, node 64 on the east edge), so the colours change over the
        // last 4 px only: the last pixel column is 7/8 of the way to the edge node's colour.
        assertColour(b.getPixel(0, 0), 0.0, "west edge")
        assertColour(b.getPixel(251, 100), 0.0, "before the last cell's middle")
        val last = b.getPixel(255, 100)
        val red = ElevationColours.colour(1.0)
        val blue = ElevationColours.colour(0.0)
        assertNear("last red", (0.875 * red.red + 0.125 * blue.red) * 255, Color.red(last), 6.0)
        assertNear("last blue", (0.875 * red.blue + 0.125 * blue.blue) * 255, Color.blue(last), 6.0)
        val third = b.getPixel(253, 100)
        assertNear("3 px from the edge", (0.375 * red.red + 0.625 * blue.red) * 255, Color.red(third), 6.0)
    }

    @Test
    fun colourTilesAreHalfTransparentAndClearOverTheSea() {
        val n = 65
        // North half at sea (below 0 m), south half 500 m: the middle of the range 0–1000.
        val grid = ElevationGrid(n, FloatArray(n * n) { if (it / n < 32) -3f else 500f })
        val b = decode(colourPng(grid, ElevationRange(0.0, 1000.0))!!)
        assertEquals(0, Color.alpha(b.getPixel(128, 10)))
        assertColour(b.getPixel(128, 200), 0.5, "land")
        assertColour(b.getPixel(255, 255), 0.5, "south-east corner")
    }
}
