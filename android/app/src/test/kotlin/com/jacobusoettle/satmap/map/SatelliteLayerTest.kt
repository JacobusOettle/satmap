package com.jacobusoettle.satmap.map

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/** `SatelliteRenderer.level` on the plain JVM, against the Swift `level(pixelsPerMapPoint:)`. */
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
}
