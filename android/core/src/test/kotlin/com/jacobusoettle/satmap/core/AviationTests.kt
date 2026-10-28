package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Drone mode: the tests at the end of the Swift ScaleBarTests (testPointInRing is in GeoTests).
class AviationTests {
    private val aviationSample = listOf(
        "# comment",
        "A\tM\tFASZ\tSkukuza Airport\t-24.96085\t31.58859\t1020\t156",
        "A\tS\tFASE\tSabi Sabi Airport\t-24.94738\t31.44885\t1276\t",
        "A\tH\t\tSomewhere Helipad\t-24.99000\t31.59000\t\t",
        "A\tL\tFAOR\tO. R. Tambo International Airport\t-26.13920\t28.24600\t5558\t30",
        "N\tB\tSZ\tSkukuza\t-24.98030\t31.59890\t233 kHz",
        "N\tV\tHGV\tHoedspruit\t-24.35000\t31.04000\t114.50 MHz",
        "A\tX\tBAD\tUnknown kind\t0\t0\t\t",
        "A\tM\tBAD\tNo coordinates\tx\ty\t\t",
    ).joinToString("\n")

    @Test
    fun testAviationLinesParse() {
        val index = AviationIndex(tsv = aviationSample)
        assertEquals(6, index.points.size)
        val fasz = index.points[0]
        assertEquals(AviationPoint.Kind.mediumAirport, fasz.kind)
        assertEquals("FASZ", fasz.code)
        assertEquals("FASZ Skukuza", fasz.label)
        assertEquals(1020, fasz.elevationFeet)
        assertEquals(156, fasz.runwayHeading)
        assertNull(index.points[1].runwayHeading)
        assertEquals("Somewhere", index.points[2].label) // no code, "Helipad" left off
        assertEquals("FAOR O. R. Tambo", index.points[3].label)
        assertEquals("233 kHz", index.points[4].frequency)
        assertFalse(index.points[4].kind.isAerodrome)
    }

    @Test
    fun testAviationPlacesInViewAndNearest() {
        val index = AviationIndex(tsv = aviationSample)
        val view = GeoBounds(minLat = -25.1, minLon = 31.3, maxLat = -24.8, maxLon = 31.7)
        val inView = index.points(view).map { index.points[it].code }.toSet()
        assertEquals(setOf("FASZ", "FASE", "", "SZ"), inView)
        // The nearest aerodrome to Skukuza camp is the helipad; the nearest airport with a runway is FASZ.
        val camp = GeoPoint(lat = -24.9955, lon = 31.5920)
        val nearest = index.nearest(camp)
        assertEquals("Somewhere Helipad", nearest?.let { index.points[it.index].name })
        val airport = index.nearest(camp, keep = { it.kind.isAerodrome && it.kind != AviationPoint.Kind.heliport })
        assertEquals("FASZ", airport?.let { index.points[it.index].code })
        assertEquals(3_850.0, airport?.meters ?: 0.0, 100.0)
        // Far from everything: nothing within the limit.
        assertNull(index.nearest(GeoPoint(lat = 10.0, lon = -30.0), maxMeters = 50_000.0))
        // Johannesburg, from Skukuza: the large airport 4° away is still found.
        assertEquals("FAOR", index.nearest(GeoPoint(lat = -26.2, lon = 28.0), maxMeters = 400_000.0)?.let { index.points[it.index].code })
    }

    @Test
    fun testPositionReportedAsPilotsDo() {
        val fasz = AviationPoint(
            kind = AviationPoint.Kind.mediumAirport, code = "FASZ", name = "Skukuza Airport",
            point = GeoPoint(lat = -24.96085, lon = 31.58859),
        )
        // 0.05° north-east of the field: about 3.9 NM north-east.
        val spot = GeoPoint(lat = -24.96085 + 0.04, lon = 31.58859 + 0.044)
        val report = Aviation.report(spot, place = fasz)
        assertTrue(report, report.endsWith("NE of FASZ Skukuza"))
        assertTrue(report, report.startsWith("3."))
        assertEquals("Over FASZ Skukuza", Aviation.report(fasz.point, place = fasz))
        assertEquals("S", Aviation.compass(GeoPoint(lat = 0.0, lon = 0.0), GeoPoint(lat = -1.0, lon = 0.0)))
        assertEquals("W", Aviation.compass(GeoPoint(lat = 0.0, lon = 0.0), GeoPoint(lat = 0.0, lon = -1.0)))
        assertEquals("12.4 NM", Aviation.nauticalMiles(23_000.0))
        assertEquals("140 NM", Aviation.nauticalMiles(260_000.0))
    }

    @Test
    fun testShippedAviationDataReads() {
        val index = AviationIndex(tsv = resource("Aviation.tsv").readText(Charsets.UTF_8))
        assertGreaterThan(index.points.size, 50_000)
        val skukuza = index.points.firstOrNull { it.code == "FASZ" }
        assertEquals(AviationPoint.Kind.mediumAirport, skukuza?.kind)
    }
}
