package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleBarTests {
    @Test
    fun testLongestRoundDistanceThatFits() {
        // XCTest's `line:` becomes the metres per point in each message.
        fun check(metersPerPoint: Double, meters: Double, label: String) {
            val s = ScaleBar.choose(metersPerPoint = metersPerPoint, maxWidth = 110.0)
            assertNotNull("No scale at $metersPerPoint", s)
            s!!
            assertEquals("at $metersPerPoint", meters, s.meters, 1e-9)
            assertEquals("at $metersPerPoint", meters / metersPerPoint, s.width, 1e-9)
            assertLessThanOrEqual(s.width, 110 + 1e-9, "at $metersPerPoint")
            assertGreaterThanOrEqual(s.width, 110 * 0.4 - 1e-9, "at $metersPerPoint") // 2 of 5, the biggest step down
            assertEquals("at $metersPerPoint", label, ScaleBar.label(s.meters))
        }
        check(300.0 / 393, 50.0, "50 m") // the screen 300 m across
        check(1.0, 100.0, "100 m") // exactly 100 m in 100 points; 110 m doesn't round
        check(1000.0 / 110, 1000.0, "1 km") // exactly the bar's length
        check(45.0, 2000.0, "2 km") // 4950 m fit
        check(0.004, 0.2, "20 cm") // closest zoom
        check(90_000.0, 5_000_000.0, "5000 km") // the world
    }

    @Test
    fun testNoScaleWithoutAZoom() {
        assertNull(ScaleBar.choose(metersPerPoint = 0.0, maxWidth = 110.0))
        assertNull(ScaleBar.choose(metersPerPoint = Double.NaN, maxWidth = 110.0))
        assertNull(ScaleBar.choose(metersPerPoint = Double.POSITIVE_INFINITY, maxWidth = 110.0))
    }
}
