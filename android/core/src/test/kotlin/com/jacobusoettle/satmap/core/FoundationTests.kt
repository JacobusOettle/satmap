package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

// Not in CoreTests.swift: checks that the stand-ins for Swift's Foundation behave as on iOS (the expected strings are
// C printf's, which iOS uses).
class FoundationTests {
    @Test
    fun testRoundedTakesHalvesAwayFromZero() {
        assertEquals(3.0, 2.5.rounded(), 0.0)
        assertEquals(-3.0, (-2.5).rounded(), 0.0)
        assertEquals(1.0, 0.5.rounded(), 0.0)
        assertEquals(0.0, 0.49999999999999994.rounded(), 0.0)
        assertEquals(2.0, 2.4.rounded(), 0.0)
        assertEquals(4503599627370496.0, 4503599627370495.5.rounded(), 0.0)
        assertEquals(3f, 2.5f.rounded(), 0f)
    }

    @Test
    fun testStringFormatRoundsAsPrintfDoes() {
        assertEquals("0.12", stringFormat("%.2f", 0.125))
        assertEquals("0.1", stringFormat("%.1f", 0.15))
        assertEquals("1.00", stringFormat("%.2f", 1.005))
        assertEquals("2", stringFormat("%.0f", 2.5))
        assertEquals("0", stringFormat("%.0f", 0.5))
        assertEquals("-0", stringFormat("%.0f", -0.3))
        assertEquals("0.05", stringFormat("%g", 0.05))
        assertEquals("1e-05", stringFormat("%g", 1e-5))
        assertEquals("0.0001", stringFormat("%g", 0.0001))
        assertEquals("100000", stringFormat("%g", 100000.0))
        assertEquals("1e+06", stringFormat("%g", 1e6))
        assertEquals("1e+06", stringFormat("%g", 999999.5))
        assertEquals("0", stringFormat("%g", 0.0))
        assertEquals("12.5", stringFormat("%.3g", 12.5))
        assertEquals("1.234560e+02", stringFormat("%e", 123.456))
        assertEquals("0.00e+00", stringFormat("%.2e", 0.0))
        assertEquals("moved 3.2 m", stringFormat("moved %.1f m", 3.25))
        assertEquals("(1.000000,2.500000,-3.000001,4.000000);", stringFormat("(%.6f,%.6f,%.6f,%.6f);", 1.0, 2.5, -3.0000005, 4.0))
        assertEquals("100% 5", stringFormat("100%% %.0f", 5.0))
    }

    @Test
    fun testShapesReadTheSwiftCodableForm() {
        // As Swift's JSONEncoder writes a CoverageShape (whole numbers without ".0").
        val swift = """{"polygons":[[{"lat":-25.47,"lon":30.97},{"lat":-25.46,"lon":30.97},{"lat":-25.46,"lon":31}]],""" +
            """"lines":[],"points":[{"lat":-24.99,"lon":31.59}],"bufferMeters":150}"""
        val shape = CoverageShape.fromJson(Json.parseToJsonElement(swift))
        assertEquals(
            CoverageShape(
                polygons = listOf(listOf(GeoPoint(-25.47, 30.97), GeoPoint(-25.46, 30.97), GeoPoint(-25.46, 31.0))),
                points = listOf(GeoPoint(-24.99, 31.59)),
                bufferMeters = 150.0,
            ),
            shape,
        )
        assertEquals(shape, CoverageShape.fromJson(Json.parseToJsonElement(shape.toJson().toString())))
        val bounds = GeoBounds(minLat = -1.0, minLon = 2.0, maxLat = 3.5, maxLon = 4.0)
        assertEquals(bounds, GeoBounds.fromJson(bounds.toJson()))
        assertEquals(TileID(z = 3, x = 4, y = 5), TileID.fromJson(Json.parseToJsonElement("""{"z":3,"x":4,"y":5}""")))
    }

    @Test
    fun testUnitsReadTheirSavedForm() {
        // Swift's `DistanceUnit(rawValue:)` and `HeightUnit(rawValue:)`, with the same saved strings.
        assertEquals(DistanceUnit.nautical, DistanceUnit(rawValue = "nautical"))
        assertEquals(null, DistanceUnit(rawValue = ""))
        assertEquals("imperial", DistanceUnit.imperial.rawValue)
        assertEquals(HeightUnit.feet, HeightUnit(rawValue = "feet"))
        assertEquals(null, HeightUnit(rawValue = "Feet"))
        assertEquals("nautical miles", DistanceUnit.nautical.spokenName)
    }
}
