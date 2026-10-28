package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippedDataTests {
    /** SatMap/Resources/KrugerRoads.json (built by tools/build_kruger_roads.py) decodes, and its roads lie inside the park. */
    @Test
    fun testKrugerRoadList() {
        val preset = RoadPreset(json = resource("KrugerRoads.json").readBytes())
        assertEquals("Kruger National Park roads", preset.name)
        val park = CoverageShape(polygons = preset.boundary)
        val bounds = park.geometryBounds
        assertNotNull(bounds)
        bounds!!
        // Kruger is about 19,500 km² (1.95 million ha), 22.3–25.5° S, 30.9–32.0° E.
        assertEquals(19_500.0, park.polygonAreaSquareMeters / 1e6, 1_500.0)
        assertTrue(bounds.minLat > -25.6 && bounds.maxLat < -22.2 && bounds.minLon > 30.8 && bounds.maxLon < 32.1)
        val km = Roads.length(preset.roads) / 1000
        assertGreaterThan(km, 3_000.0)
        // Roads were cut with the exact boundary; the shipped one is simplified to within 20 m.
        val coverage = TileCoverage(shape = CoverageShape(polygons = preset.boundary, bufferMeters = 100.0), zoom = 16)
        val points = preset.roads.flatten()
        val outside = points.filter { !coverage.contains(TileID.containing(it, z = 16)) }
        assertEquals("road points outside the park", 0, outside.size)
        println("Kruger road list: ${preset.roads.size} roads, ${km.toInt()} km, ${points.size} points")
    }
}
