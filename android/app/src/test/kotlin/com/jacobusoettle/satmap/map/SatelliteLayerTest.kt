package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** `SatelliteRenderer.level` on the plain JVM, against the Swift `level(pixelsPerMapPoint:)`; the tiles
 *  `ImageryPrefetch` loads and the geometry `DrawnImagery` works from. */
class SatelliteLayerTest {
    @Test
    fun levelIsTilesAbout256PixelsAcross() {
        assertEquals(0, SatelliteRenderer.level(256.0))
        assertEquals(1, SatelliteRenderer.level(512.0))
        assertEquals(15, SatelliteRenderer.level(256 * 2.0.pow(15)))
        assertEquals(15, SatelliteRenderer.level(256 * 2.0.pow(15.4)))
        assertEquals(16, SatelliteRenderer.level(256 * 2.0.pow(15.6)))
        assertEquals(22, SatelliteRenderer.level(256 * 2.0.pow(22)))
        assertEquals(22, SatelliteRenderer.level(256 * 2.0.pow(30))) // the deepest imagery
        assertEquals(0, SatelliteRenderer.level(1.0))
    }

    @Test
    fun aSizeThatIsNotPositiveAndFiniteIsLevel0AsInSwift() {
        assertEquals(0, SatelliteRenderer.level(Double.POSITIVE_INFINITY))
        assertEquals(0, SatelliteRenderer.level(Double.NaN))
        assertEquals(0, SatelliteRenderer.level(0.0))
        assertEquals(0, SatelliteRenderer.level(-512.0))
        assertEquals(0, SatelliteRenderer.level(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun prefetchTilesCoverTheRectAndWrapRoundTheWorld() {
        assertEquals(
            setOf(TileID(2, 1, 1), TileID(2, 2, 1), TileID(2, 1, 2), TileID(2, 2, 2)),
            ImageryPrefetch.tiles(2, WorldRect(0.3, 0.3, 0.6, 0.6)).toSet(),
        )
        // Across the date line: x wraps; y is clamped to the world.
        assertEquals(
            setOf(TileID(2, 3, 0), TileID(2, 0, 0)),
            ImageryPrefetch.tiles(2, WorldRect(-0.1, -0.5, 0.1, 0.2)).toSet(),
        )
        // A tile's edge isn't a tile more.
        assertEquals(listOf(TileID(3, 2, 2)), ImageryPrefetch.tiles(3, WorldRect(0.25, 0.25, 0.375, 0.375)))
    }

    @Test
    fun prefetchSkipsALevelOfMoreThan64TilesAcross() {
        assertEquals(64, ImageryPrefetch.tiles(6, WorldRect(0.0, 0.0, 1.0, 1.0 / 64)).size)
        assertTrue(ImageryPrefetch.tiles(7, WorldRect(0.0, 0.0, 1.0, 1.0 / 128)).isEmpty())
        assertTrue(ImageryPrefetch.tiles(7, WorldRect(0.0, 0.0, 1.0 / 128, 1.0)).isEmpty())
    }

    @Test
    fun aFocusIsItsLevelOneEitherSideUnderItsRect() {
        val f = DrawnMap.Focus(WorldRect(0.25, 0.25, 0.5, 0.5), 4)
        assertTrue(SatelliteRenderer.inFocus(TileID(4, 4, 4), f))
        assertTrue(SatelliteRenderer.inFocus(TileID(3, 2, 2), f))
        assertTrue(SatelliteRenderer.inFocus(TileID(5, 15, 15), f))
        assertFalse(SatelliteRenderer.inFocus(TileID(6, 16, 16), f)) // two levels down
        assertFalse(SatelliteRenderer.inFocus(TileID(4, 8, 4), f)) // only touching
        assertFalse(SatelliteRenderer.inFocus(TileID(4, 4, 4), DrawnMap.Focus(null, 4)))
    }

    @Test
    fun theVisibleRectIsOneRectAcrossTheDateLine() {
        val r = DrawnImagery.worldRect(
            listOf(GeoPoint(10.0, 179.0), GeoPoint(10.0, -179.0), GeoPoint(-10.0, 179.0), GeoPoint(-10.0, -179.0)),
            centre = GeoPoint(0.0, 180.0),
        )
        assertEquals(179.0 / 360 + 0.5, r.minX, 1e-12)
        assertEquals(181.0 / 360 + 0.5, r.maxX, 1e-12)
        assertTrue(r.minY < 0.5 && r.maxY > 0.5)
        val plain = DrawnImagery.worldRect(listOf(GeoPoint(1.0, 30.0), GeoPoint(-1.0, 32.0)), centre = GeoPoint(0.0, 31.0))
        assertEquals(210.0 / 360, plain.minX, 1e-12)
        assertEquals(212.0 / 360, plain.maxX, 1e-12)
    }

    @Test
    fun theTilesUnderPoints() {
        val points = listOf(XY(0.1, 0.1), XY(0.12, 0.1), XY(0.6, 0.9), XY(-0.1, 1.2))
        assertEquals(setOf(TileID(2, 0, 0), TileID(2, 2, 3), TileID(2, 0, 3)), DrawnImagery.tilesUnder(points, 2))
    }
}
