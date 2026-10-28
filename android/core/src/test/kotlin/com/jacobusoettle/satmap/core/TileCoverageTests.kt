package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class TileCoverageTests {
    /** Brute force: does the polygon intersect the closed tile square? */
    private fun intersects(poly: List<Pair<Double, Double>>, tileX: Int, tileY: Int): Boolean {
        val x0 = tileX.toDouble()
        val y0 = tileY.toDouble()
        val x1 = x0 + 1
        val y1 = y0 + 1
        fun inside(px: Double, py: Double): Boolean {
            var c = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (xi, yi) = poly[i]
                val (xj, yj) = poly[j]
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c
                j = i
            }
            return c
        }
        for ((px, py) in poly) if (px >= x0 && px <= x1 && py >= y0 && py <= y1) return true
        for ((cx, cy) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) if (inside(cx, cy)) return true
        fun segs(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
            fun o(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>): Double =
                (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
            val d1 = o(c, d, a)
            val d2 = o(c, d, b)
            val d3 = o(a, b, c)
            val d4 = o(a, b, d)
            return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
        }
        val edges = listOf((x0 to y0) to (x1 to y0), (x1 to y0) to (x1 to y1), (x1 to y1) to (x0 to y1), (x0 to y1) to (x0 to y0))
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            for (e in edges) if (segs(a, b, e.first, e.second)) return true
        }
        return false
    }

    @Test
    fun testPolygonCoverageMatchesBruteForce() {
        val rng = Random.Default
        for (trial in 0 until 40) {
            // A random star-shaped polygon around Mbombela, ~2–6 km across.
            val c = GeoPoint(lat = -25.47, lon = 30.97)
            val n = rng.nextInt(3, 15)
            val ring = mutableListOf<GeoPoint>()
            for (k in 0 until n) {
                val a = k.toDouble() / n.toDouble() * 2 * PI + rng.nextDouble(-0.2, 0.2)
                val r = rng.nextDouble(0.005, 0.03)
                ring.add(GeoPoint(lat = c.lat + r * sin(a), lon = c.lon + r * cos(a)))
            }
            val shape = CoverageShape(polygons = listOf(ring))
            for (z in listOf(10, 13, 15)) {
                val cov = TileCoverage(shape = shape, zoom = z)
                val n2 = (1 shl z).toDouble()
                val poly = ring.map { p -> val w = WebMercator.world(p); w.x * n2 to w.y * n2 }
                val b = GeoBounds(points = ring)!!.tileRange(z = z)
                val expected = HashSet<TileID>()
                for (y in (b.minY - 1)..(b.maxY + 1)) {
                    for (x in (b.minX - 1)..(b.maxX + 1)) {
                        if (intersects(poly, tileX = x, tileY = y)) expected.add(TileID(z = z, x = x, y = y))
                    }
                }
                val got = HashSet<TileID>()
                cov.forEachTile { got.add(it) }
                // Coverage may include tiles that only touch the polygon at an edge/corner; it must never miss one.
                assertTrue("trial $trial z$z missed ${expected - got}", got.containsAll(expected))
                val extra = got - expected
                for (t in extra) {
                    // Any extra tile must at least touch the polygon's bounding box.
                    assertTrue(t.x >= b.minX - 1 && t.x <= b.maxX + 1 && t.y >= b.minY - 1 && t.y <= b.maxY + 1)
                }
                assertLessThanOrEqual(extra.size, max(2, expected.size / 10), "too many extra tiles z$z")
                assertEquals(got.size.toLong(), cov.tileCount)
                for (t in got) assertTrue(cov.contains(t))
            }
        }
    }

    @Test
    fun testBufferAndLinesAndPoints() {
        val p = GeoPoint(lat = -25.47, lon = 30.97)
        val pointOnly = TileCoverage(shape = CoverageShape(points = listOf(p)), zoom = 18)
        assertEquals(1L, pointOnly.tileCount)
        // 200 m buffer at z18 (~137 m tiles at this latitude) → roughly 4×4 tiles.
        val buffered = TileCoverage(shape = CoverageShape(points = listOf(p), bufferMeters = 200.0), zoom = 18)
        assertGreaterThanOrEqual(buffered.tileCount, 9L)
        assertLessThanOrEqual(buffered.tileCount, 25L)
        val line = TileCoverage(shape = CoverageShape(lines = listOf(listOf(p, GeoPoint(lat = -25.47, lon = 31.0)))), zoom = 16)
        assertGreaterThanOrEqual(line.tileCount, 5L)
        assertEquals(1, line.rows.size)
    }

    @Test
    fun testEmpty() {
        assertEquals(0L, TileCoverage(shape = CoverageShape(), zoom = 12).tileCount)
    }

    @Test
    fun testLassoThatLoopsPastItsStartCoversEverythingInside() {
        // A finger-drawn circle that overshoots its start by 15% of a turn and is closed with a chord.
        val c = GeoPoint(lat = -25.47, lon = 30.97)
        fun ring(turns: Double): List<GeoPoint> {
            val n = (120 * turns).toInt()
            return (0..n).map { k ->
                val a = k.toDouble() / 120 * 2 * PI
                GeoPoint(lat = c.lat + 0.005 * sin(a), lon = c.lon + 0.005 * cos(a))
            }
        }
        val single = TileCoverage(shape = CoverageShape(polygons = listOf(ring(turns = 1.0))), zoom = 18)
        val looped = TileCoverage(shape = CoverageShape(polygons = listOf(ring(turns = 1.15))), zoom = 18)
        var missing = 0
        single.forEachTile { if (!looped.contains(it)) missing += 1 }
        assertEquals(0, missing)
        assertGreaterThan(single.tileCount, 40L)
    }
}
