package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.rounded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max

/** The levels MapLibre asks for with `TileLevels`' tile sizes, against iOS's formulas (rendering spec §2.4, §2.13). */
class TileLevelsTest {
    private val ratios = listOf(1f, 2f, 2.625f, 3f, 3.5f)

    /** Zooms 0 to 21 in steps of 0.01, and the same just off them. */
    private val zooms = (0..2100).flatMap { listOf(it / 100.0, it / 100.0 + 0.0037) }

    /** iOS's imagery level: tiles 256 pixels across, `round(Zp + log2 s)` (half away from zero), 0…22. */
    private fun iosImagery(zp: Double, s: Double) = (zp + log2(s)).rounded().toInt().coerceIn(0, 22)

    /** iOS's contour and colour level: the zoom MapKit paints at, `ceil(Zp − 1e-6)`, 0…22. */
    private fun iosTerrain(zp: Double) = ceil(zp - 1e-6).toInt().coerceIn(0, 22)

    @Test
    fun imageryLevelsAreIOSsExceptWithinAHundredthOfAHalf() {
        for (r in ratios) {
            val s = max(1.0, r.toDouble())
            val size = TileLevels.imageryTileSize(r)
            var checked = 0
            for (zoom in zooms) {
                val zp = zoom + 1 // the 256-point zoom
                val v = zp + log2(s)
                val level = TileLevels.level(zoom, size)
                if (abs(v - floor(v) - 0.5) <= 0.01) {
                    // The tile size is rounded to a whole dp: the boundary moves by less than 0.01.
                    assertTrue("s=$r zoom=$zoom", abs(level - iosImagery(zp, s)) <= 1)
                } else {
                    assertEquals("s=$r zoom=$zoom", iosImagery(zp, s), level)
                    checked++
                }
            }
            assertTrue(checked > 4000)
        }
    }

    @Test
    fun contourAndColourLevelsAreIOSsExceptJustAboveAWholeZoom() {
        for (zoom in zooms) {
            val zp = zoom + 1
            val above = zp - floor(zp)
            val level = TileLevels.level(zoom, TileLevels.TERRAIN_TILE_SIZE)
            if (above > 0 && above <= 0.008) {
                // log2(512 / 182) = 1.4922: MapLibre turns to the next level 0.0078 above a whole zoom.
                assertTrue("zoom=$zoom", level == iosTerrain(zp) || level == iosTerrain(zp) - 1)
            } else {
                assertEquals("zoom=$zoom", iosTerrain(zp), level)
            }
        }
    }

    @Test
    fun theSpecsExamples() {
        // Rendering spec §1.2: Zp 14.80, 14.95, 15.00 and 15.50 at s = 2, 2.625 and 3.
        val expected = mapOf(
            14.80 to listOf(16, 16, 16),
            14.95 to listOf(16, 16, 17),
            15.00 to listOf(16, 16, 17),
            15.50 to listOf(17, 17, 17),
        )
        for ((zp, levels) in expected) {
            for ((i, r) in listOf(2f, 2.625f, 3f).withIndex()) {
                assertEquals("Zp=$zp s=$r", levels[i], TileLevels.level(zp - 1, TileLevels.imageryTileSize(r)))
            }
        }
        // 22-contours-and-colours at Zp ≈ 14.8 draws z15 contours; 01-map at Zp ≈ 15.05, z16.
        assertEquals(15, TileLevels.level(13.8, TileLevels.TERRAIN_TILE_SIZE))
        assertEquals(16, TileLevels.level(14.05, TileLevels.TERRAIN_TILE_SIZE))
    }

    @Test
    fun levelsStayWithinTheImagery() {
        assertEquals(0, TileLevels.level(-3.0, 256))
        assertEquals(22, TileLevels.level(21.0, 85))
        assertEquals(22, TileLevels.level(30.0, TileLevels.TERRAIN_TILE_SIZE))
    }

    @Test
    fun tileSizesPerPixelRatio() {
        assertEquals(256, TileLevels.imageryTileSize(1f))
        assertEquals(256, TileLevels.imageryTileSize(0.75f))
        assertEquals(128, TileLevels.imageryTileSize(2f))
        assertEquals(98, TileLevels.imageryTileSize(2.625f))
        assertEquals(85, TileLevels.imageryTileSize(3f))
        assertEquals(73, TileLevels.imageryTileSize(3.5f))
    }

    @Test
    fun contourScaleAndMinZoom() {
        assertEquals(1.0, TileLevels.contourScale(0.75f), 0.0)
        assertEquals(2.0, TileLevels.contourScale(2f), 0.0)
        assertEquals(2.63, TileLevels.contourScale(2.625f), 0.0)
        assertEquals(3.0, TileLevels.contourScale(3.5f), 0.0)
        assertEquals(11, TileLevels.contourMinZoom(1.0))
        assertEquals(12, TileLevels.contourMinZoom(2.0))
        assertEquals(13, TileLevels.contourMinZoom(2.63))
        assertEquals(13, TileLevels.contourMinZoom(3.0))
    }
}
