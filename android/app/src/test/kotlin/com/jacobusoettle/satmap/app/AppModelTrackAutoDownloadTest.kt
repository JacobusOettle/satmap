package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.TrackAutoDownload
import com.jacobusoettle.satmap.core.TrackLog
import com.jacobusoettle.satmap.core.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.math.cos

/** "Where I've been"'s auto download in `AppModel`: the switch (on unless switched off, saved at once, no launch extra
 *  changes it), when a check runs (becoming active, the switch turned on, new track points; at most once every 10
 *  minutes), and what it does: the Download button's update, only for 500 m of new track or more, with 1 GB free, with
 *  the track area's own settings or the screen's level for a new one. The clock, free space and the new area's level
 *  are the test's. SatMap is in the background here, so the areas updated are queued and no download starts. */
@RunWith(AndroidJUnit4::class)
class AppModelTrackAutoDownloadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private val home = GeoPoint(-25.0, 31.0)
    private lateinit var model: AppModel
    private var clock = 1_800_000_000.0
    private var free: Long? = 40L shl 30
    private val zoomsAsked = mutableListOf<List<List<GeoPoint>>>()
    private var time = 1_800_000_000.0

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().remove(TrackAutoDownload.settingKey).commit()
        AppPaths.init(app)
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        TrackLog(AppPaths.track).apply {
            clear()
            loadAll()
        }
        Connectivity.init(app)
        AppLifecycle.init()
        model = newModel()
    }

    @After
    fun tearDown() {
        Defaults.setLaunchArguments(null)
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        TrackLog(AppPaths.track).apply {
            clear()
            loadAll()
        }
        AppLifecycle.resetForTests()
    }

    private fun newModel() = AppModel(app, noHeights).also { m ->
        m.wallClock = { clock }
        m.freeSpace = { free }
        m.newTrackAreaZoom = { lines, freeSpace ->
            synchronized(zoomsAsked) { zoomsAsked += lines }
            assertEquals(free, freeSpace)
            18
        }
    }

    /** A spot `north` and `east` metres from home. */
    private fun at(north: Double, east: Double = 0.0) = GeoPoint(
        home.lat + north / 111_195.0,
        home.lon + east / (111_195.0 * cos(home.lat * Math.PI / 180)),
    )

    /** Track points every 10 m from `from` to `to` metres north (as the recorder gives them), a second apart. */
    private fun points(from: Double, to: Double, east: Double = 0.0): List<TrackPoint> =
        generateSequence(from) { it + 10 }.takeWhile { it <= to }.map {
            time += 1
            TrackPoint(lat = at(it, east).lat, lon = at(it, east).lon, time = time, startsSegment = false)
        }.toList()

    /** The recorder passing on new track points; each may start a check. */
    private fun record(points: List<TrackPoint>) {
        for (p in points) model.recorder.onTrackPoint!!(p)
        settle()
    }

    /** Until the check under way (if any) has ended, with the main thread's queue run. */
    private fun settle() {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.trackCheckRunning) return
            if (System.nanoTime() > deadline) fail("The check didn't end")
            Thread.sleep(5)
        }
    }

    private fun minutesLater(m: Double) {
        clock += m * 60
    }

    private val trackArea: OfflineArea? get() = model.offline.areas.value.firstOrNull { it.isTrack == true }

    /** The track area's lines the Download button would save now. */
    private fun expectedLines(previous: List<List<GeoPoint>>) = Roads.mergeTrack(previous, Roads.trackLines(model.track))

    @Test
    fun onUnlessSwitchedOffSavedAtOnceAndNoLaunchExtraChangesIt() {
        assertTrue(model.trackAutoDownload.value)
        assertNull(Defaults.storedBool(TrackAutoDownload.settingKey))
        model.setTrackAutoDownload(false)
        assertFalse(model.trackAutoDownload.value)
        assertEquals(false, Defaults.storedBool(TrackAutoDownload.settingKey))
        assertFalse(newModel().trackAutoDownload.value)
        model.setTrackAutoDownload(true)
        assertEquals(true, Defaults.storedBool(TrackAutoDownload.settingKey))
        // Another app's intent can't switch it off (nor on): only what was saved counts.
        Defaults.setLaunchArguments(Bundle().apply { putBoolean(TrackAutoDownload.settingKey, false) })
        assertTrue(newModel().trackAutoDownload.value)
        Defaults.prefs.edit().putBoolean(TrackAutoDownload.settingKey, false).commit()
        Defaults.setLaunchArguments(Bundle().apply { putString(TrackAutoDownload.settingKey, "YES") })
        assertFalse(newModel().trackAutoDownload.value)
    }

    @Test
    fun aWalkOf500mMakesTheTrackAreaAtTheScreensLevelWaitingForWiFi() {
        // The first point is checked at once: nothing to save yet, and the check counts.
        record(points(0.0, 0.0))
        minutesLater(5.0)
        record(points(10.0, 600.0))
        assertNull(trackArea)
        // 10 minutes after the first check: the whole walk (600 m) is new.
        minutesLater(5.0)
        record(points(610.0, 610.0))
        val area = trackArea!!
        assertEquals("Where I've been", area.name)
        assertEquals(18, area.maxZoom)
        assertFalse(area.allowCellular)
        assertEquals(expectedLines(emptyList()), area.roads)
        assertEquals(Roads.corridor(area.roads!!), area.shape)
        assertEquals(listOf(area.roads), zoomsAsked)
        assertEquals(OfflineArea.Status.queued, area.status)
    }

    @Test
    fun lessThan500mOfNewTrackWaitsForMore() {
        record(points(0.0, 400.0))
        assertNull(trackArea)
        minutesLater(10.0)
        record(points(410.0, 490.0))
        assertNull(trackArea)
        assertEquals(emptyList<List<List<GeoPoint>>>(), zoomsAsked)
        minutesLater(10.0)
        record(points(510.0, 510.0))
        assertEquals(expectedLines(emptyList()), trackArea!!.roads)
    }

    @Test
    fun theTrackAreaGetsTheNewPartsOnceTheyAre500mWithItsOwnSettings() {
        record(points(0.0, 1_000.0))
        val first = expectedLines(emptyList())
        // Saved with the Download button, with settings of its own.
        model.startTrackDownload("My walks", first, maxZoom = 20, allowCellular = true)
        val saved = trackArea!!
        zoomsAsked.clear()
        // Back along the same road (nothing new), then 300 m on beyond its start: not enough.
        record(points(0.0, 990.0).reversed())
        record(points(-290.0, -10.0).reversed())
        minutesLater(10.0)
        record(points(-300.0, -300.0))
        assertEquals(saved, trackArea)
        // 300 m more: 600 m of new track.
        record(points(-590.0, -310.0).reversed())
        minutesLater(10.0)
        record(points(-600.0, -600.0))
        val updated = trackArea!!
        assertEquals(saved.id, updated.id)
        assertEquals("My walks", updated.name)
        assertEquals(20, updated.maxZoom)
        assertTrue(updated.allowCellular)
        assertEquals(expectedLines(first), updated.roads)
        assertEquals(emptyList<List<List<GeoPoint>>>(), zoomsAsked)
    }

    @Test
    fun atMostOneCheckEvery10Minutes() {
        record(points(0.0, 300.0))
        // Walked on to 800 m within the 10 minutes: no check until they are up.
        minutesLater(9.9)
        record(points(310.0, 800.0))
        assertNull(trackArea)
        minutesLater(0.1)
        record(points(810.0, 810.0))
        assertTrue(trackArea != null)
    }

    @Test
    fun aPausedTrackAreaIsLeftPausedAndUpdatedOnceResumed() {
        record(points(0.0, 1_000.0))
        val first = expectedLines(emptyList())
        model.startTrackDownload("Where I've been", first, maxZoom = 20, allowCellular = false)
        val id = trackArea!!.id
        model.offline.pause(id)
        val paused = trackArea!!
        assertEquals(OfflineArea.Status.paused, paused.status)
        // 600 m of new track: the pause stays.
        record(points(1_010.0, 1_590.0))
        minutesLater(10.0)
        record(points(1_600.0, 1_600.0))
        assertEquals(paused, trackArea)
        // Resumed (queued again): the next check adds the new parts.
        model.offline.resume(id)
        minutesLater(10.0)
        record(points(1_610.0, 1_610.0))
        assertEquals(expectedLines(first), trackArea!!.roads)
        assertEquals(OfflineArea.Status.queued, trackArea!!.status)
    }

    @Test
    fun notWithLessThan1GBFree() {
        free = (1L shl 30) - 1
        record(points(0.0, 800.0))
        assertNull(trackArea)
        free = null
        minutesLater(10.0)
        record(points(810.0, 810.0))
        assertNull(trackArea)
        free = 1L shl 30
        minutesLater(10.0)
        record(points(820.0, 820.0))
        assertTrue(trackArea != null)
    }

    @Test
    fun switchedOffNothingIsSavedAndSwitchedOnItChecksAtOnce() {
        model.setTrackAutoDownload(false)
        record(points(0.0, 800.0))
        minutesLater(30.0)
        record(points(810.0, 810.0))
        assertNull(trackArea)
        // No check ran while it was off, so turning it on checks straight away.
        model.setTrackAutoDownload(true)
        settle()
        assertEquals(expectedLines(emptyList()), trackArea!!.roads)
    }

    @Test
    fun becomingActiveChecks() {
        TrackLog(AppPaths.track).apply {
            points(0.0, 700.0).forEach(::append)
            loadAll()
        }
        model = newModel()
        assertEquals(71, model.track.size)
        model.didBecomeActive()
        settle()
        assertEquals(expectedLines(emptyList()), trackArea!!.roads)
    }

    @Test
    fun theWalkthroughsSuspensionKeepsTheSettingButSavesNothing() {
        model.trackAutoDownloadSuspended = true
        record(points(0.0, 800.0))
        minutesLater(10.0)
        record(points(810.0, 810.0))
        model.setTrackAutoDownload(false)
        model.setTrackAutoDownload(true)
        settle()
        assertNull(trackArea)
        assertTrue(model.trackAutoDownload.value)
        // Lifted: the next occasion checks.
        model.trackAutoDownloadSuspended = false
        record(points(820.0, 820.0))
        assertTrue(trackArea != null)
    }
}
