package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The terrain button and elevation colour tests of the Swift ScaleBarTests.
class ElevationColoursTests {
    @Test
    fun testTerrainButtonCyclesThroughFiveModes() {
        var m = TerrainMode.contours
        val seen = ArrayList<TerrainMode>()
        repeat(5) {
            seen.add(m)
            m = m.next
        }
        assertEquals(
            listOf(TerrainMode.contours, TerrainMode.contoursAndColours, TerrainMode.colours, TerrainMode.drone, TerrainMode.off),
            seen,
        )
        assertEquals(TerrainMode.contours, m)
        assertEquals(listOf(TerrainMode.contours, TerrainMode.contoursAndColours), TerrainMode.entries.filter { it.showsContours })
        assertEquals(listOf(TerrainMode.contoursAndColours, TerrainMode.colours), TerrainMode.entries.filter { it.showsColours })
        assertEquals(listOf(TerrainMode.drone), TerrainMode.entries.filter { it.showsAviation })
    }

    @Test
    fun testTerrainModeFromSettingsOfEarlierVersions() {
        assertEquals(TerrainMode.contours, TerrainMode.saved(mode = null, showContours = null))
        assertEquals(TerrainMode.contours, TerrainMode.saved(mode = null, showContours = true))
        assertEquals(TerrainMode.off, TerrainMode.saved(mode = null, showContours = false))
        assertEquals(TerrainMode.colours, TerrainMode.saved(mode = "colours", showContours = false))
        assertEquals(TerrainMode.off, TerrainMode.saved(mode = "nonsense", showContours = false))
    }

    @Test
    fun testColourRampRunsFromBlueToRed() {
        val low = ElevationColours.colour(at = 0.0)
        val high = ElevationColours.colour(at = 1.0)
        assertGreaterThan(low.blue, 0.8)
        assertLessThan(low.red, 0.3)
        assertGreaterThan(high.red, 0.8)
        assertLessThan(high.blue, 0.3)
        // Outside the range: the ends; not a number: the lowest.
        assertEquals(low.blue, ElevationColours.colour(at = -3.0).blue, 0.0)
        assertEquals(high.red, ElevationColours.colour(at = 7.0).red, 0.0)
        assertEquals(low.blue, ElevationColours.colour(at = Double.NaN).blue, 0.0)
        val mid = ElevationColours.colour(at = 0.5)
        assertGreaterThan(mid.green, mid.blue)
    }

    @Test
    fun testRoundRangeAroundTheHeightsOnScreen() {
        val r = ElevationColours.range(low = 412.0, high = 587.0, current = null)
        assertEquals(ElevationRange(low = 400.0, high = 600.0), r)
        // Nearly flat ground: at least 10 m, so noise isn't coloured blue to red.
        val flat = ElevationColours.range(low = 100.2, high = 101.5, current = null)
        assertGreaterThanOrEqual(flat.high - flat.low, 10.0)
        assertLessThanOrEqual(flat.low, 100.2)
        assertGreaterThanOrEqual(flat.high, 101.5)
        val big = ElevationColours.range(low = 120.0, high = 2_870.0, current = null)
        assertEquals(ElevationRange(low = 0.0, high = 3_000.0), big)
    }

    @Test
    fun testRangeKeptWhilePanningAroundAndChangedOnZoomingIn() {
        val shown = ElevationRange(low = 400.0, high = 600.0)
        // Still within it and filling most of it: the same colours.
        assertEquals(shown, ElevationColours.range(low = 420.0, high = 590.0, current = shown))
        assertEquals(shown, ElevationColours.range(low = 405.0, high = 530.0, current = shown))
        // Beyond it: a new one.
        assertNotEquals(shown, ElevationColours.range(low = 380.0, high = 590.0, current = shown))
        // Zoomed in on a small part of it: a range around that part.
        val zoomed = ElevationColours.range(low = 450.0, high = 480.0, current = shown)
        assertEquals(ElevationRange(low = 450.0, high = 480.0), zoomed)
    }

    @Test
    fun testRangeKeptWhilePanningOverFlatGround() {
        // Under the least span, small shifts of the heights on screen keep the colours.
        val shown = ElevationColours.range(low = 100.2, high = 102.4, current = null)
        assertEquals(shown, ElevationColours.range(low = 101.1, high = 103.2, current = shown))
        assertEquals(shown, ElevationColours.range(low = 100.9, high = 103.0, current = shown))
        // Low ground by the sea: the range starts at 0 m, so the lowest land is blue.
        val coast = ElevationColours.range(low = 0.0, high = 2.0, current = null)
        assertEquals(0.0, coast.low, 0.0)
        assertGreaterThanOrEqual(coast.high, 10.0)
    }

    @Test
    fun testRangeOnlyGrowsWhileHeightsAreStillComing() {
        val shown = ElevationRange(low = 400.0, high = 600.0)
        // Only some tiles in: a narrow spread keeps the range.
        assertEquals(shown, ElevationColours.range(low = 450.0, high = 470.0, current = shown, complete = false))
        // Higher ground among them: it grows, still covering what it covered.
        val grown = ElevationColours.range(low = 450.0, high = 720.0, current = shown, complete = false)
        assertLessThanOrEqual(grown.low, 400.0)
        assertGreaterThanOrEqual(grown.high, 720.0)
    }

    @Test
    fun testNiceSteps() {
        assertEquals(20.0, ElevationColours.niceStep(17.5), 0.0)
        assertEquals(5.0, ElevationColours.niceStep(3.0), 0.0)
        assertEquals(1.0, ElevationColours.niceStep(1.0), 0.0)
        assertEquals(0.2, ElevationColours.niceStep(0.13), 1e-12)
        assertEquals(1_000.0, ElevationColours.niceStep(600.0), 0.0)
        assertEquals(1.0, ElevationColours.niceStep(0.0), 0.0)
        assertEquals(1.0, ElevationColours.niceStep(Double.NaN), 0.0)
    }

    @Test
    fun testGridExtremesOnlyWithinTheRectAndOnLand() {
        // 3 × 3 nodes over the tile 0..1: the middle column 10, 20, 30; the left column below sea level.
        val g = ElevationGrid(
            nodes = 3,
            values = floatArrayOf(
                -50f, 10f, 100f,
                -60f, 20f, 200f,
                -70f, 30f, 300f,
            ),
        )
        val tile = WorldRect(minX = 0.0, minY = 0.0, maxX = 1.0, maxY = 1.0)
        val all = g.extremes(tile = tile, within = tile)
        assertEquals(10f, all?.low)
        assertEquals(300f, all?.high)
        // The left half: the sea and the middle column.
        val left = g.extremes(tile = tile, within = WorldRect(minX = -1.0, minY = 0.0, maxX = 0.5, maxY = 1.0))
        assertEquals(10f, left?.low)
        assertEquals(30f, left?.high)
        // Only the sea, or outside the tile: nothing.
        assertNull(g.extremes(tile = tile, within = WorldRect(minX = 0.0, minY = 0.0, maxX = 0.2, maxY = 1.0)))
        assertNull(g.extremes(tile = tile, within = WorldRect(minX = 2.0, minY = 2.0, maxX = 3.0, maxY = 3.0)))
    }

    @Test
    fun testColourPixelsPremultipliedAndClearOverTheSea() {
        val g = ElevationGrid(nodes = 2, values = floatArrayOf(-5f, 0f, 50f, 100f))
        // The bytes read as Swift's UInt8, 0…255.
        val px = ElevationColours.pixels(g, range = ElevationRange(low = 0.0, high = 100.0), alpha = 0.5)
            .map { it.toInt() and 0xFF }
        assertEquals(16, px.size)
        assertEquals(listOf(0, 0, 0, 0), px.subList(0, 4)) // the sea
        for (n in 1 until 4) {
            val a = px[n * 4 + 3]
            assertEquals(128, a)
            assertLessThanOrEqual(px[n * 4], a)
            assertLessThanOrEqual(px[n * 4 + 1], a)
            assertLessThanOrEqual(px[n * 4 + 2], a)
        }
        // The lowest blue, the highest red.
        assertGreaterThan(px[4 + 2], px[4])
        assertGreaterThan(px[12], px[12 + 2])
    }
}
