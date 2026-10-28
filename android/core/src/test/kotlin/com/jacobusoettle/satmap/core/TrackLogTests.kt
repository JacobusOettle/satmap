package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

// In StorageTests in CoreTests.swift.
class TrackLogTests {
    @Test
    fun testTrackLog() {
        val file = File(tempDir(), "track.bin")
        val log = TrackLog(file)
        val pts = (0 until 5).map { TrackPoint(lat = -25 + it.toDouble() * 0.001, lon = 30.0, time = 1000 + it.toDouble(), startsSegment = it == 3) }
        pts.forEach(log::append)
        assertEquals(pts, log.loadAll())
        // A torn write leaves a partial record: it is ignored on load and trimmed on the next append.
        FileOutputStream(file, true).use { it.write(byteArrayOf(1, 2, 3)) }
        val log2 = TrackLog(file)
        assertEquals(pts, log2.loadAll())
        val extra = TrackPoint(lat = 1.0, lon = 2.0, time = 3.0, startsSegment = false)
        log2.append(extra)
        assertEquals(pts + extra, log2.loadAll())
        log2.clear()
        assertEquals(emptyList<TrackPoint>(), log2.loadAll())
    }
}
