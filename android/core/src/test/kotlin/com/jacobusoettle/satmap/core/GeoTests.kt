package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class GeoTests {
    @Test
    fun testMercatorRoundTrip() {
        for (p in listOf(GeoPoint(lat = -25.47, lon = 30.97), GeoPoint(lat = 60.0, lon = -120.0), GeoPoint(lat = 0.0, lon = 0.0))) {
            val w = WebMercator.world(p)
            val q = WebMercator.geo(worldX = w.x, worldY = w.y)
            assertEquals(p.lat, q.lat, 1e-9)
            assertEquals(p.lon, q.lon, 1e-9)
        }
    }

    @Test
    fun testTileContaining() {
        // Same tile as the Esri probe used while designing (z18, Mbombela).
        val t = TileID.containing(GeoPoint(lat = -25.47, lon = 30.97), z = 18)
        assertEquals(153623, t.x)
        assertEquals(150261, t.y)
        assertEquals(TileID(z = 17, x = 76811, y = 75130), t.parent)
        assertEquals(TileID(z = 16, x = 38405, y = 37565), t.ancestor(levels = 2))
    }

    @Test
    fun testDistance() {
        // One degree of latitude ≈ 111.2 km on the mean sphere.
        assertEquals(111_195.0, Geo.distance(GeoPoint(lat = 0.0, lon = 0.0), GeoPoint(lat = 1.0, lon = 0.0)), 5.0)
    }

    @Test
    fun testPathLengthAndShapeArea() {
        // A 100 m × 100 m square near Skukuza (1 ha), measured corner to corner.
        val lat = -24.99
        val lon = 31.59
        val dLat = 100 / 110_574.0
        val dLon = 100 / (111_320.0 * cos(lat * PI / 180))
        val square = listOf(
            GeoPoint(lat = lat, lon = lon), GeoPoint(lat = lat, lon = lon + dLon),
            GeoPoint(lat = lat + dLat, lon = lon + dLon), GeoPoint(lat = lat + dLat, lon = lon),
        )
        assertEquals(300.0, Geo.pathLength(square), 1.0)
        assertEquals(400.0, Geo.pathLength(square + square[0]), 1.5)
        assertEquals(10_000.0, Geo.ringArea(square), 100.0)
        assertEquals(10_000.0, Geo.ringArea(square.reversed()), 100.0) // either direction round
        assertEquals(0.0, Geo.pathLength(listOf(square[0])), 0.0)
    }

    @Test
    fun testLabelPointInsideConcaveBlock() {
        // An L-shaped block whose centroid lies outside it.
        val l = listOf(0.0 to 0.0, 0.01 to 0.0, 0.01 to 0.002, 0.002 to 0.002, 0.002 to 0.01, 0.0 to 0.01)
            .map { GeoPoint(lat = -25.47 + it.second, lon = 30.97 + it.first) }
        val p = PolygonLabel.interiorPoint(outer = l)!!
        val inX = p.lon - 30.97
        val inY = p.lat + 25.47
        assertTrue(
            "label at $inX, $inY is outside the L",
            (inX >= 0 && inX <= 0.01 && inY >= 0 && inY <= 0.002) || (inX >= 0 && inX <= 0.002 && inY >= 0 && inY <= 0.01),
        )
        // A convex block keeps its centroid.
        val sq = listOf(0.0 to 0.0, 0.01 to 0.0, 0.01 to 0.01, 0.0 to 0.01).map { GeoPoint(lat = -25.47 + it.second, lon = 30.97 + it.first) }
        val c = PolygonLabel.interiorPoint(outer = sq)!!
        assertEquals(30.975, c.lon, 1e-9)
        assertEquals(-25.465, c.lat, 1e-9)
    }

    @Test
    fun testDouglasPeucker() {
        val pts = (0..100).map { XY(it.toDouble(), if (it == 50) 10.0 else 0.0) }
        val s = Simplify.douglasPeucker(pts, tolerance = 1.0)
        assertEquals(5, s.size)
        assertEquals(10.0, s[2].y, 0.0)
    }

    @Test
    fun testPointInRing() {
        val square = listOf(GeoPoint(lat = 0.0, lon = 0.0), GeoPoint(lat = 0.0, lon = 1.0), GeoPoint(lat = 1.0, lon = 1.0), GeoPoint(lat = 1.0, lon = 0.0))
        assertTrue(Geo.contains(square, GeoPoint(lat = 0.5, lon = 0.5)))
        assertFalse(Geo.contains(square, GeoPoint(lat = 1.5, lon = 0.5)))
        assertEquals(90.0, Geo.bearing(GeoPoint(lat = 0.0, lon = 0.0), GeoPoint(lat = 0.0, lon = 1.0)), 1e-9)
    }
}
