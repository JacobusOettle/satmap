package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.MultiLineString

/** The track's chunks (iOS `TrackOverlay`'s rule) and the lines `TrackLayer` sends, on the plain JVM. */
class DynamicOverlaysTest {
    /** Point `i` of a walk east along the equator; `gap` starts a new segment. */
    private fun point(i: Int, gap: Boolean = false) =
        TrackPoint(lat = 0.0, lon = i * 0.0001, time = 1_700_000_000.0 + 2 * i, startsSegment = gap)

    private fun geo(i: Int) = GeoPoint(0.0, i * 0.0001)

    @Test
    fun aChunkHoldsUpTo512PointsThenTheSegmentGoesOnFromTheLastOne() {
        val c = TrackChunks()
        assertTrue(c.append(point(0))) // the first chunk
        for (i in 1 until 512) assertFalse(c.append(point(i)))
        assertEquals(1, c.chunks.size)
        assertEquals(512, c.chunks[0].points.size)
        assertTrue(c.append(point(512)))
        assertEquals(2, c.chunks.size)
        assertEquals(listOf(geo(511), geo(512)), c.chunks[1].points)
        assertTrue(c.chunks[1].continues)
        assertFalse(c.chunks[0].continues)
    }

    @Test
    fun aGapStartsANewChunkAtThePointItself() {
        val c = TrackChunks()
        c.append(point(0))
        c.append(point(1))
        assertTrue(c.append(point(2, gap = true)))
        assertFalse(c.append(point(3)))
        assertEquals(2, c.chunks.size)
        assertEquals(listOf(geo(2), geo(3)), c.chunks[1].points)
        assertFalse(c.chunks[1].continues)
        // A gap on the very first point is just the first chunk.
        val d = TrackChunks()
        d.append(point(0, gap = true))
        assertEquals(1, d.chunks.size)
    }

    @Test
    fun theFinishedPartIsEachSegmentAsOneLineAndTheLivePartTheLastChunk() {
        val c = TrackChunks()
        c.reset((0 until 1100).map { point(it) })
        // Chunks 0…511, 511…1022, 1022…1099.
        assertEquals(listOf(512, 512, 78), c.chunks.map { it.points.size })
        val finished = c.finishedLines()
        assertEquals(1, finished.size)
        assertEquals((0..1022).map { geo(it) }, finished[0])
        assertEquals(listOf((1022 until 1100).map { geo(it) }), c.liveLines())
    }

    @Test
    fun segmentsStayApartAndALonePointDrawsNothing() {
        val c = TrackChunks()
        c.reset(listOf(point(0), point(1), point(2, gap = true), point(3, gap = true), point(4), point(5)))
        assertEquals(listOf(listOf(geo(0), geo(1))), c.finishedLines()) // point 2 alone is no line
        assertEquals(listOf(listOf(geo(3), geo(4), geo(5))), c.liveLines())
        c.reset(listOf(point(7)))
        assertEquals(emptyList<List<GeoPoint>>(), c.finishedLines())
        assertEquals(emptyList<List<GeoPoint>>(), c.liveLines()) // one point: no line yet
    }

    @Test
    fun aSegmentLongerThanAChunkAfterAGapIsJoinedToItselfOnly() {
        val c = TrackChunks()
        c.reset((0 until 10).map { point(it) } + (10 until 1100).map { point(it, gap = it == 10) })
        val finished = c.finishedLines()
        assertEquals(2, finished.size)
        assertEquals((0 until 10).map { geo(it) }, finished[0])
        // 10…521, 521…1032 joined; 1032…1099 live.
        assertEquals((10..1032).map { geo(it) }, finished[1])
        assertEquals(listOf((1032 until 1100).map { geo(it) }), c.liveLines())
    }

    @Test
    fun resetForgetsTheTrack() {
        val c = TrackChunks()
        c.reset((0 until 600).map { point(it) })
        c.reset(emptyList())
        assertEquals(0, c.chunks.size)
        assertEquals(emptyList<List<GeoPoint>>(), c.finishedLines())
        assertEquals(emptyList<List<GeoPoint>>(), c.liveLines())
    }

    @Test
    fun theLinesGoToMapLibreAsOneMultiLineStringInLongitudeLatitudeOrder() {
        assertEquals(0, TrackLayer.geoJson(emptyList()).features()!!.size)
        val fc = TrackLayer.geoJson(listOf(listOf(GeoPoint(-33.9, 18.9), GeoPoint(-33.8, 18.8)), listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0))))
        val features = fc.features()!!
        assertEquals(1, features.size)
        val lines = (features[0].geometry() as MultiLineString).coordinates()
        assertEquals(2, lines.size)
        assertEquals(18.9, lines[0][0].longitude(), 0.0)
        assertEquals(-33.9, lines[0][0].latitude(), 0.0)
        assertEquals(4.0, lines[1][1].longitude(), 0.0)
        assertEquals(3.0, lines[1][1].latitude(), 0.0)
    }
}
