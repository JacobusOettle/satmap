package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageDifferenceTests {
    /** Swift's `Roads.corridor`: every road with `Roads.corridorMeters` (15 m) on each side. Roads.kt is ported
     *  separately, so this builds the same shape here; `Roads.corridor` can take its place once it exists. */
    private fun corridor(roads: List<List<GeoPoint>>) = CoverageShape(lines = roads, bufferMeters = 15.0)

    @Test
    fun testTilesNotInAnotherCoverage() {
        val road = listOf(GeoPoint(lat = -24.99, lon = 31.59), GeoPoint(lat = -24.99, lon = 31.60))
        val same = TileCoverage(shape = corridor(listOf(road)), zoom = 22)
        assertEquals(0L, same.count(same))
        // The same road recorded 12 m to the side: a strip of new tiles (about 9 m each at zoom 22), not nothing.
        val shifted = road.map { GeoPoint(lat = it.lat + 12 / 110_574.0, lon = it.lon) }
        val moved = TileCoverage(shape = corridor(listOf(shifted)), zoom = 22)
        val extra = moved.count(same)
        assertGreaterThan(extra, 0L)
        assertLessThan(extra, moved.tileCount / 3)
        // Somewhere else entirely: all of it is new.
        val elsewhere = TileCoverage(shape = corridor(listOf(road.map { GeoPoint(lat = it.lat - 0.1, lon = it.lon) })), zoom = 22)
        assertEquals(elsewhere.tileCount, elsewhere.count(same))
    }

    @Test
    fun testBufferNearThePoleStaysSmall() {
        // A KML line to the South Pole with the usual 150 m margin must not cover the whole world.
        val line = CoverageShape(lines = listOf(listOf(GeoPoint(lat = -89.9, lon = 0.0), GeoPoint(lat = -90.0, lon = 0.0))), bufferMeters = 150.0)
        assertLessThan(TileCoverage(shape = line, zoom = 8).tileCount, (1 shl 10).toLong())
    }
}
