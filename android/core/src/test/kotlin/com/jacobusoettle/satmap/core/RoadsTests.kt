package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsTests {
    /** About 1.1 km × 1.1 km. */
    private val square = CoverageShape(
        polygons = listOf(
            listOf(
                GeoPoint(lat = -33.910, lon = 18.910), GeoPoint(lat = -33.910, lon = 18.922),
                GeoPoint(lat = -33.920, lon = 18.922), GeoPoint(lat = -33.920, lon = 18.910),
            )
        )
    )

    @Test
    fun testQueryHasOneBoxPerPartAndOnlyVehicleRoads() {
        val q = Roads.overpassQuery(square)
        assertNotNull(q)
        q!!
        assertTrue(q.startsWith("[out:json]"))
        assertTrue(q.endsWith("out geom;"))
        assertTrue(q.contains("track|"))
        assertFalse(q.contains("footway"))
        assertEquals(1, q.split("way[").size - 1)
        // The box is the square plus the corridor margin (15 m ≈ 0.000135° of latitude).
        assertTrue(q.contains("(-33.920135,"))

        val twoBlocks = CoverageShape(
            polygons = square.polygons + listOf(square.polygons[0].map { GeoPoint(lat = it.lat - 1, lon = it.lon) }),
            bufferMeters = 150.0,
        )
        val q2 = Roads.overpassQuery(twoBlocks)
        assertNotNull(q2)
        assertEquals(2, q2!!.split("way[").size - 1)
        assertNull(Roads.overpassQuery(CoverageShape()))
    }

    @Test
    fun testParsesWaysAndSkipsMissingNodes() {
        val json = """
        {"version":0.6,"elements":[
          {"type":"way","id":1,"tags":{"highway":"track","surface":"gravel"},
           "geometry":[{"lat":-33.91,"lon":18.91},{"lat":-33.911,"lon":18.912},null,{"lat":-33.912,"lon":18.913},{"lat":-33.913,"lon":18.914}]},
          {"type":"way","id":2,"geometry":[{"lat":-33.915,"lon":18.915}]},
          {"type":"node","id":3,"lat":-33.9,"lon":18.9}
        ]}
        """.trimIndent()
        val lines = Roads.parse(json.toByteArray())
        assertEquals(2, lines.size)
        assertEquals(listOf(GeoPoint(lat = -33.91, lon = 18.91), GeoPoint(lat = -33.911, lon = 18.912)), lines[0])
        assertEquals(2, lines[1].size)

        val partial = """{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 121 seconds."}"""
        assertThrows(Exception::class.java) { Roads.parse(partial.toByteArray()) }
        assertThrows(Exception::class.java) { Roads.parse("<html>Too many requests</html>".toByteArray()) }
    }

    @Test
    fun testClipKeepsOnlyThePartsInsideTheArea() {
        // West to east through the middle, starting and ending about 1 km outside, with a bend inside.
        val through = listOf(
            GeoPoint(lat = -33.915, lon = 18.899), GeoPoint(lat = -33.915, lon = 18.916),
            GeoPoint(lat = -33.914, lon = 18.933),
        )
        val outside = listOf(GeoPoint(lat = -33.930, lon = 18.910), GeoPoint(lat = -33.930, lon = 18.930))
        val clipped = Roads.clip(listOf(through, outside), square)
        assertEquals(1, clipped.size)
        // The square is 0.012° (about 1.11 km) wide; tiles at zoom 21 are about 16 m.
        val width = Geo.distance(GeoPoint(lat = -33.915, lon = 18.910), GeoPoint(lat = -33.915, lon = 18.922))
        assertEquals(width, Roads.length(clipped), 45.0)
        for (p in clipped[0]) {
            assertGreaterThan(p.lon, 18.910 - 0.0004)
            assertLessThan(p.lon, 18.922 + 0.0004)
        }
        // Collinear points from the 10 m checks are simplified away; the bend stays.
        assertLessThanOrEqual(clipped[0].size, 4)
        assertTrue(clipped[0].contains(GeoPoint(lat = -33.915, lon = 18.916)))

        // A road leaving and re-entering a U-shaped area becomes two pieces.
        val u = CoverageShape(
            polygons = listOf(
                listOf(
                    GeoPoint(lat = -33.910, lon = 18.910), GeoPoint(lat = -33.910, lon = 18.914), GeoPoint(lat = -33.918, lon = 18.914),
                    GeoPoint(lat = -33.918, lon = 18.918), GeoPoint(lat = -33.910, lon = 18.918), GeoPoint(lat = -33.910, lon = 18.922),
                    GeoPoint(lat = -33.920, lon = 18.922), GeoPoint(lat = -33.920, lon = 18.910),
                )
            )
        )
        assertEquals(2, Roads.clip(listOf(listOf(GeoPoint(lat = -33.912, lon = 18.905), GeoPoint(lat = -33.912, lon = 18.927))), u).size)
    }

    @Test
    fun testTrackLinesSplitAtBreaksAndSimplify() {
        // 40 points walking east every ~10 m in a straight line, a break, then a short second walk and a lone point.
        val track = (0 until 40).map {
            TrackPoint(lat = -24.99, lon = 31.59 + it.toDouble() * 0.0001, time = it.toDouble(), startsSegment = it == 0)
        }.toMutableList()
        track += (0 until 3).map {
            TrackPoint(lat = -25.0 - it.toDouble() * 0.0001, lon = 31.6, time = 100 + it.toDouble(), startsSegment = it == 0)
        }
        track.add(TrackPoint(lat = -25.01, lon = 31.61, time = 200.0, startsSegment = true))
        val lines = Roads.trackLines(track)
        assertEquals(3, lines.size)
        assertEquals(listOf(track[0].geo, track[39].geo), lines[0]) // a straight walk keeps only its ends
        assertEquals(2, lines[1].size)
        assertEquals(listOf(track[43].geo), lines[2])
        assertEquals(39 * 10.09 + 2 * 11.06, Roads.length(lines), 3.0)
        assertEquals(emptyList<List<GeoPoint>>(), Roads.trackLines(emptyList()))
        // The corridor covers a lone point too.
        assertGreaterThan(TileCoverage(shape = Roads.corridor(listOf(lines[2])), zoom = 19).tileCount, 0L)
    }

    @Test
    fun testTrackAreaKeepsEarlierTripsAfterTheTrackIsCleared() {
        val east = listOf(GeoPoint(lat = -24.99, lon = 31.59), GeoPoint(lat = -24.99, lon = 31.594))
        val longer = listOf(GeoPoint(lat = -24.99, lon = 31.59), GeoPoint(lat = -24.99, lon = 31.596))
        val elsewhere = listOf(GeoPoint(lat = -25.05, lon = 31.70), GeoPoint(lat = -25.05, lon = 31.702))
        // The same track, grown: nothing earlier is kept twice.
        assertEquals(listOf(longer), Roads.mergeTrack(previous = listOf(east), latest = listOf(longer)))
        // The track was cleared and a new trip recorded: the earlier trip stays in the area.
        val merged = Roads.mergeTrack(previous = listOf(east), latest = listOf(elsewhere))
        assertEquals(2, merged.size)
        assertEquals(Roads.length(listOf(east, elsewhere)), Roads.length(merged), 1.0)
        // A new trip that partly overlaps: only the part not covered again is kept.
        val partly = Roads.mergeTrack(
            previous = listOf(east),
            latest = listOf(listOf(GeoPoint(lat = -24.99, lon = 31.592), GeoPoint(lat = -24.99, lon = 31.596))),
        )
        assertEquals(Roads.length(listOf(longer)), Roads.length(partly), 40.0)
        assertEquals(listOf(east), Roads.mergeTrack(previous = emptyList(), latest = listOf(east)))
        assertEquals(listOf(east), Roads.mergeTrack(previous = listOf(east), latest = emptyList()))

        // A lone point from an earlier trip stays, unless the new track passes it.
        val lone = listOf(GeoPoint(lat = -25.10, lon = 31.80))
        assertEquals(listOf(lone), Roads.mergeTrack(previous = listOf(east, lone), latest = listOf(elsewhere)).filter { it.size == 1 })
        val through = listOf(GeoPoint(lat = -25.10, lon = 31.799), GeoPoint(lat = -25.10, lon = 31.801))
        assertEquals(listOf(through), Roads.mergeTrack(previous = listOf(lone), latest = listOf(through)))
        // An earlier path 20 m beside the new one (another track, the other side of a river) is kept whole;
        // one 3 m off (the same path, recorded again) is not.
        val beside = east.map { GeoPoint(lat = it.lat + 20.0 / 110_574, lon = it.lon) }
        assertEquals(2 * Roads.length(listOf(east)), Roads.length(Roads.mergeTrack(previous = listOf(beside), latest = listOf(east))), 2.0)
        val same = east.map { GeoPoint(lat = it.lat + 3.0 / 110_574, lon = it.lon) }
        assertEquals(listOf(east), Roads.mergeTrack(previous = listOf(same), latest = listOf(east)))
        // What a download adds: only the part of the track away from what the area has.
        assertEquals(
            Roads.length(listOf(longer)) - Roads.length(listOf(east)),
            Roads.length(Roads.newParts(listOf(longer), others = listOf(east))),
            12.0,
        )
        assertEquals(listOf(east), Roads.newParts(listOf(east), others = emptyList()))
    }

    @Test
    fun testCorridorWidthDoesNotDependOnOtherPartsOfTheShape() {
        // One track area can hold trips far apart: the corridor near London must not narrow because of a trip in
        // the Kruger (the buffer used to be worked out at the middle latitude of the whole shape).
        val london = listOf(GeoPoint(lat = 51.5, lon = -0.12), GeoPoint(lat = 51.5, lon = -0.11))
        val kruger = listOf(GeoPoint(lat = -24.99, lon = 31.59), GeoPoint(lat = -24.99, lon = 31.60))
        for (z in listOf(19, 21)) {
            val alone = TileCoverage(shape = Roads.corridor(listOf(london)), zoom = z).tileCount
            val both = TileCoverage(shape = Roads.corridor(listOf(london, kruger)), zoom = z).tileCount
            val krugerAlone = TileCoverage(shape = Roads.corridor(listOf(kruger)), zoom = z).tileCount
            assertEquals("zoom $z", alone + krugerAlone, both)
        }
    }
}
