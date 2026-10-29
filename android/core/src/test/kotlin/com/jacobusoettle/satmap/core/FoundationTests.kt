package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun testSwiftMinKeepsTheFirstArgumentUnlessTheSecondIsLess() {
        assertEquals(1.0, swiftMin(1.0, Double.NaN), 0.0)
        assertTrue(swiftMin(Double.NaN, 1.0).isNaN())
        assertEquals(0.5, swiftMin(1.0, 0.5), 0.0)
        assertEquals(0.5, swiftMin(0.5, 1.0), 0.0)
        assertEquals(0.0.toRawBits(), swiftMin(0.0, -0.0).toRawBits())
        assertEquals((-0.0).toRawBits(), swiftMin(-0.0, 0.0).toRawBits())
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
    fun testJSONNumbersReadOnlyAsSwiftsJSONDecoderReadsThem() {
        // A JSON number that a Double holds; not a string, NaN, hex, a number written another way, overflow or underflow.
        for (bad in listOf(""""1.5"""", """"NaN"""", """" 1.5 """", """"0x1p3"""", """"1.5d"""", "1e400", "-1e400", "1e-400",
                           "2e-324", "1.7976931348623159e308", "01", "+1", ".5", "1.", "NaN", "Infinity", "0x10", "1.5d", "true", "null")) {
            val json = Json.parseToJsonElement("""{"lat":$bad,"lon":2}""")
            assertThrows(bad, Exception::class.java) { GeoPoint.fromJson(json) }
            val shape = Json.parseToJsonElement("""{"polygons":[],"lines":[],"points":[],"bufferMeters":$bad}""")
            assertThrows(bad, Exception::class.java) { CoverageShape.fromJson(shape) }
            val bounds = Json.parseToJsonElement("""{"minLat":1,"minLon":2,"maxLat":3,"maxLon":$bad}""")
            assertThrows(bad, Exception::class.java) { GeoBounds.fromJson(bounds) }
        }
        fun lat(number: String) = GeoPoint.fromJson(Json.parseToJsonElement("""{"lat":$number,"lon":2}""")).lat
        assertEquals(25.0, lat("2.5e+1"), 0.0)
        assertEquals(100000.0, lat("1E5"), 0.0)
        assertEquals(0.0, lat("0e400"), 0.0)
        assertEquals(4.9e-324, lat("3e-324"), 0.0)
        assertEquals((-0.0).toRawBits(), lat("-0").toRawBits())
        assertEquals(1.7976931348623157e308, lat("1.7976931348623157e308"), 0.0)
    }

    @Test
    fun testZerosWithAnExponentReadAsSwiftsJSONDecoderReadsThem() {
        // Swift's isTrueZero looks at the last one to three characters backwards, so it takes some zeros for numbers
        // too small for a Double (and refuses them).
        fun lat(number: String) = GeoPoint.fromJson(Json.parseToJsonElement("""{"lat":$number,"lon":2}""")).lat
        for (refused in listOf("0e1", "0E1", "0.00e1", "-0.00e1", "0.000e1")) {
            assertThrows(refused, Exception::class.java) { lat(refused) }
        }
        assertEquals(0.0, lat("0e10"), 0.0)
        assertEquals(0.0, lat("0.0e1"), 0.0)
        assertEquals((-0.0).toRawBits(), lat("-0e1").toRawBits())
    }

    @Test
    fun testJSONNumbersWrittenAsSwiftsJSONEncoderWritesThem() {
        // Swift's JSONEncoder writes these numbers so (its own key order varies).
        val shape = CoverageShape(
            polygons = listOf(listOf(GeoPoint(-25.0, 31.5), GeoPoint(-25.5, 31.5), GeoPoint(-25.5, 32.0))),
            lines = listOf(listOf(GeoPoint(1e-7, 100.0))), points = listOf(GeoPoint(-24.0, 31.0)), bufferMeters = 500.0,
        )
        assertEquals(
            """{"polygons":[[{"lat":-25,"lon":31.5},{"lat":-25.5,"lon":31.5},{"lat":-25.5,"lon":32}]],""" +
                """"lines":[[{"lat":1e-07,"lon":100}]],"points":[{"lat":-24,"lon":31}],"bufferMeters":500}""",
            shape.toJson().toString(),
        )
        assertEquals("""{"minLat":-1,"minLon":2,"maxLat":3.5,"maxLon":1e-07}""",
                     GeoBounds(minLat = -1.0, minLon = 2.0, maxLat = 3.5, maxLon = 1e-7).toJson().toString())
        // Its `description`: the shortest digits that read back, the nearer (or even) of two; exponents below 1e-4
        // and above 2^53.
        for ((v, swift) in listOf(
            -0.0 to "-0", 0.000123 to "0.000123", 0.0001 to "0.0001", 0.00001 to "1e-05", 5e-324 to "5e-324",
            9007199254740992.0 to "9007199254740992", 9007199254740994.0 to "9.007199254740994e+15", 1e16 to "1e+16",
            1.2345678901234568e17 to "1.2345678901234568e+17", 7.120236347223045e-307 to "7.120236347223045e-307",
            -2.9802322387695312e-8 to "-2.9802322387695312e-08", 2251799813685247.75 to "2251799813685247.8",
            -1112339409777952.25 to "-1112339409777952.2", 1.7976931348623157e308 to "1.7976931348623157e+308",
            0.1 to "0.1", 1.0 / 3 to "0.3333333333333333", 783388800.25 to "783388800.25", 123456789.0 to "123456789",
        )) {
            assertEquals(swift, GeoPoint(lat = v, lon = 0.0).toJson().getValue("lat").toString())
        }
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
