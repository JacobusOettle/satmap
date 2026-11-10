package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrackAutoDownloadTests {
    private val now = 1_800_000_000.0
    private val gigabyte: Long = 1L shl 30

    /** A walk east along -24.99° from `start` (in steps of 0.0001°, about 10.09 m), one point a step. */
    private fun walk(start: Int = 0, steps: Int, newSegment: Boolean = true): List<TrackPoint> =
        (0..steps).map { i ->
            TrackPoint(lat = -24.99, lon = 31.59 + (start + i).toDouble() * 0.0001, time = (start + i).toDouble(),
                       startsSegment = newSegment && i == 0)
        }

    private fun area(lines: List<List<GeoPoint>>, leaveAlone: Boolean = false) =
        TrackAutoDownload.Area(name = "My trips", lines = lines, maxZoom = 19, allowCellular = true, leaveAlone = leaveAlone)

    private val noNewArea: (List<List<GeoPoint>>) -> Int = {
        fail("A track area has its own zoom")
        0
    }

    @Test
    fun testChecksAtMostEveryTenMinutes() {
        assertEquals("trackAutoDownload", TrackAutoDownload.settingKey)
        assertTrue(TrackAutoDownload.enabledByDefault)
        assertTrue(TrackAutoDownload.isDue(enabled = true, lastCheck = null, now = now))
        assertFalse(TrackAutoDownload.isDue(enabled = false, lastCheck = null, now = now))
        assertFalse(TrackAutoDownload.isDue(enabled = true, lastCheck = now - 599, now = now))
        assertTrue(TrackAutoDownload.isDue(enabled = true, lastCheck = now - 600, now = now))
        assertFalse(TrackAutoDownload.isDue(enabled = false, lastCheck = now - 3600, now = now))
        // The clock was put back since the last check: not held up until it catches up.
        assertTrue(TrackAutoDownload.isDue(enabled = true, lastCheck = now + 3600, now = now))
    }

    @Test
    fun testNewTrackAreaOnceTheTrackIsHalfAKilometre() {
        val short = walk(steps = 48) // about 484 m
        assertNull(TrackAutoDownload.update(track = short, area = null, freeBytes = 50 * gigabyte) { 20 })

        val track = walk(steps = 52) // about 525 m
        var asked: List<List<GeoPoint>>? = null
        val update = TrackAutoDownload.update(track = track, area = null, freeBytes = 50 * gigabyte) { lines ->
            asked = lines
            20
        }
        val lines = Roads.trackLines(track)
        assertEquals(TrackAutoDownload.Update(name = "Where I've been", lines = lines, maxZoom = 20, allowCellular = false), update)
        assertEquals("The new area's zoom is chosen for its own lines", lines, asked)
        // A lone point has no length.
        assertNull(TrackAutoDownload.update(track = walk(steps = 0), area = null, freeBytes = 50 * gigabyte) { 20 })
    }

    @Test
    fun testTrackAreaUpdatedOnlyForHalfAKilometreAwayFromItsLines() {
        val saved = Roads.trackLines(walk(steps = 60))
        // The same walk, 300 m further: not enough yet.
        val little = walk(steps = 90)
        assertNull(TrackAutoDownload.update(track = little, area = area(saved), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea))
        // 600 m further: the area's own settings, its lines and the new ones together.
        val more = walk(steps = 120)
        val update = TrackAutoDownload.update(track = more, area = area(saved), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea)
        assertEquals(
            TrackAutoDownload.Update(
                name = "My trips",
                lines = Roads.mergeTrack(saved, Roads.trackLines(more)),
                maxZoom = 19,
                allowCellular = true,
            ),
            update,
        )
        // Nothing new at all (the track is what the area has).
        assertNull(TrackAutoDownload.update(track = walk(steps = 60), area = area(saved), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea))
    }

    @Test
    fun testEarlierTripsStayBesideANewOne() {
        val earlier = listOf(listOf(GeoPoint(lat = -25.05, lon = 31.70), GeoPoint(lat = -25.05, lon = 31.71)))
        val track = walk(steps = 60)
        val update = TrackAutoDownload.update(track = track, area = area(earlier), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea)
        assertNotNull(update)
        assertEquals(2, update!!.lines.size)
        assertEquals(Roads.length(earlier + Roads.trackLines(track)), Roads.length(update.lines), 1.0)
    }

    @Test
    fun testNothingWithoutATrack() {
        // Cleared, or no location yet: the track area keeps its lines (nothing to update), and none is made.
        val earlier = Roads.trackLines(walk(steps = 120))
        assertNull(TrackAutoDownload.update(track = emptyList(), area = area(earlier), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea))
        assertNull(TrackAutoDownload.update(track = emptyList(), area = null, freeBytes = 50 * gigabyte) { 20 })
    }

    @Test
    fun testLeavesARunningOrPausedDownloadAndLowStorageAlone() {
        val saved = Roads.trackLines(walk(steps = 10))
        val far = walk(steps = 200)
        // Downloading right now (that run is left alone), or paused (the pause stays): nothing, however far you went.
        assertNull(TrackAutoDownload.update(track = far, area = area(saved, leaveAlone = true), freeBytes = 50 * gigabyte, newAreaZoom = noNewArea))
        // Under 1 GB free, or not known.
        assertNull(TrackAutoDownload.update(track = far, area = area(saved), freeBytes = gigabyte - 1, newAreaZoom = noNewArea))
        assertNull(TrackAutoDownload.update(track = far, area = null, freeBytes = gigabyte - 1) { 20 })
        assertNull(TrackAutoDownload.update(track = far, area = area(saved), freeBytes = null, newAreaZoom = noNewArea))
        assertNotNull(TrackAutoDownload.update(track = far, area = area(saved), freeBytes = gigabyte, newAreaZoom = noNewArea))
    }
}
