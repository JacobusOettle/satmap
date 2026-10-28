package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// In ScaleBarTests (MARK: - Units) in CoreTests.swift.
class UnitsTests {
    @Test
    fun testDistancesInEachUnit() {
        assertEquals("850 m", DistanceUnit.metric.format(850.0))
        assertEquals("1.25 km", DistanceUnit.metric.format(1_250.0))
        assertEquals("12.5 km", DistanceUnit.metric.format(12_540.0))
        assertEquals("125 km", DistanceUnit.metric.format(125_400.0))
        assertEquals("328 ft", DistanceUnit.imperial.format(100.0))
        assertEquals("1.24 mi", DistanceUnit.imperial.format(2_000.0))
        assertEquals("1.00 NM", DistanceUnit.nautical.format(1_852.0))
        assertEquals("12.4 NM", DistanceUnit.nautical.format(23_000.0))
        assertEquals("0.000 NM", DistanceUnit.nautical.format(Double.NaN))
        assertEquals("0.005 NM", DistanceUnit.nautical.format(9.0))
        assertEquals(DistanceUnit.imperial, DistanceUnit.metric.next)
        assertEquals(DistanceUnit.nautical, DistanceUnit.imperial.next)
        assertEquals(DistanceUnit.metric, DistanceUnit.nautical.next)
    }

    @Test
    fun testScaleBarInEachUnit() {
        // 1 m per point, at most 110 points: 100 m, 200 ft, 0.05 NM.
        val metric = DistanceUnit.metric.scale(metersPerPoint = 1.0, maxWidth = 110.0)
        assertEquals("100 m", metric?.label)
        val imperial = DistanceUnit.imperial.scale(metersPerPoint = 1.0, maxWidth = 110.0)
        assertEquals("200 ft", imperial?.label)
        assertEquals(60.96, imperial?.meters ?: 0.0, 0.001)
        val nautical = DistanceUnit.nautical.scale(metersPerPoint = 1.0, maxWidth = 110.0)
        assertEquals("0.05 NM", nautical?.label)
        assertEquals(92.6, nautical?.width ?: 0.0, 0.01)
        // Zoomed out: whole miles and nautical miles.
        assertEquals("5 mi", DistanceUnit.imperial.scale(metersPerPoint = 100.0, maxWidth = 110.0)?.label)
        assertEquals("5 NM", DistanceUnit.nautical.scale(metersPerPoint = 100.0, maxWidth = 110.0)?.label)
        assertNull(DistanceUnit.nautical.scale(metersPerPoint = 0.0, maxWidth = 110.0))
    }

    @Test
    fun testHeightsInMetresAndFeet() {
        assertEquals(431, HeightUnit.metres.whole(431.4))
        assertEquals(1415, HeightUnit.feet.whole(431.4))
        assertEquals(HeightUnit.feet, HeightUnit.metres.next)
        assertEquals(HeightUnit.metres, HeightUnit.feet.next)
    }
}
