package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContourTests {
    @Test
    fun testConeGivesConcentricClosedRings() {
        val n = 101
        val v = FloatArray(n * n)
        for (j in 0 until n) {
            for (i in 0 until n) v[j * n + i] = (100 - hypot((i - 50).toDouble(), (j - 50).toDouble())).toFloat()
        }
        val lines = MarchingSquares.contours(values = v, width = n, height = n, step = 10.0)
        val closed = lines.filter { it.isClosed }
        // Levels 50...90 fit inside the grid (radius ≤ 50, just inside the border); 100 is the single peak node so no ring.
        assertEquals(setOf(50.0, 60.0, 70.0, 80.0, 90.0), closed.map { it.level }.toSet())
        for (l in closed) {
            val r = 100 - l.level
            for (p in l.points) {
                assertEquals(r, hypot(p.x - 50, p.y - 50), 0.6)
            }
        }
        // Levels 10...40 hit the grid border and must be open lines with ends on the border.
        for (l in lines.filter { !it.isClosed }) {
            for (e in listOf(l.points.first(), l.points.last())) {
                assertTrue(e.x == 0.0 || e.y == 0.0 || e.x == (n - 1).toDouble() || e.y == (n - 1).toDouble())
            }
        }
    }

    @Test
    fun testSaddleProducesTwoSegments() {
        // tl=1, tr=0, bl=0, br=1 at level 0.5 → two separate segments, each cutting off one low corner
        val lines = MarchingSquares.contours(values = floatArrayOf(1f, 0f, 0f, 1f), width = 2, height = 2, step = 0.5)
            .filter { it.level == 0.5 }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.points.size == 2 && !it.isClosed })
        // A node exactly on a level never yields a corner-to-corner diagonal.
        val exact = MarchingSquares.contours(values = floatArrayOf(1f, 0f, 0f, 1f), width = 2, height = 2, step = 1.0)
            .filter { it.level == 1.0 }
        assertTrue(exact.isEmpty())
    }

    @Test
    fun testPolicy() {
        // A 3x iPhone asks for z17 tiles when showing roughly 1 km across: web zoom ≈ 15.4 → 10 m lines.
        val zp = ContourPolicy.pointZoom(tileZoom = 17, screenScale = 3.0)
        assertEquals(15.415, zp, 0.01)
        assertEquals(10.0, ContourPolicy.intervals(forPointZoom = zp)?.step)
        assertNull(ContourPolicy.intervals(forPointZoom = 10.9))
        assertEquals(5.0, ContourPolicy.intervals(forPointZoom = 18.0)?.step)
        assertEquals(25.0, ContourPolicy.intervals(forPointZoom = 18.0)?.index)
        assertEquals(15, ContourPolicy.demZoom(forTileZoom = 19))
        assertEquals(128, ContourPolicy.gridCells(forTileZoom = 19))
    }

    @Test
    fun testLabelAnchor() {
        val pts = listOf(XY(0.0, 100.0), XY(200.0, 100.0))
        val a = ContourGeometry.labelAnchor(pts, minLength = 100.0, bounds = ContourGeometry.Bounds(256.0, 256.0), margin = 10.0)
        assertNotNull(a)
        assertEquals(100.0, a!!.x, 1e-9)
        assertEquals(0.0, a.angle, 1e-9)
        val reversed = ContourGeometry.labelAnchor(
            pts.reversed(), minLength = 100.0, bounds = ContourGeometry.Bounds(256.0, 256.0), margin = 10.0,
        )
        assertEquals(0.0, reversed!!.angle, 1e-9) // kept upright
        assertNull(ContourGeometry.labelAnchor(pts, minLength = 300.0, bounds = ContourGeometry.Bounds(256.0, 256.0), margin = 10.0))
    }

    @Test
    fun testChaikinKeepsEnds() {
        val s = ContourGeometry.chaikin(listOf(XY(0.0, 0.0), XY(10.0, 0.0), XY(10.0, 10.0)), closed = false)
        assertEquals(0.0, s.first().x, 0.0)
        assertEquals(10.0, s.last().y, 0.0)
        assertEquals(6, s.size)
    }
}
