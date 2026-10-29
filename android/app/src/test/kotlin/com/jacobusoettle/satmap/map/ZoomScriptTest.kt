package com.jacobusoettle.satmap.map

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.pow

/** The zoom test's steps as iOS's `ZoomScript` runs them (its zoom z is MapLibre's z − 1; shifts in screens), and the
 *  watchdog's log when the main thread was held up. */
@RunWith(AndroidJUnit4::class)
class ZoomScriptTest {
    @Test
    fun theStepsAreIOSs() {
        assertEquals(13, ZoomScript.steps.size)
        assertEquals(
            listOf(14.0, 15.0, 16.0, 17.0, 18.0, 17.0, 16.0, 15.0, 15.0, 17.0, 14.0, 14.0, 15.15),
            ZoomScript.steps.map { it.zoom },
        )
        assertEquals(1.0, ZoomScript.steps[8].dx, 0.0)
        assertEquals(-1.0, ZoomScript.steps[11].dy, 0.0)
        assertEquals(2, ZoomScript.steps.count { it.dx != 0.0 || it.dy != 0.0 })
        assertEquals(-33.9183, ZoomScript.start.lat, 0.0)
        assertEquals(18.9212, ZoomScript.start.lon, 0.0)
    }

    @Test
    fun aStepShowsTheZoomOnMapLibresScaleAndShiftsByScreens() {
        val start = WebMercator.world(ZoomScript.start)
        // In place: the same centre, MapLibre's zoom one less (its world is 512 dp per tile, iOS's 256 points).
        val (c0, z0) = ZoomScript.camera(start, ZoomScript.Step(14.0, 0.0, 0.0), 400.0, 800.0)
        assertEquals(start, c0)
        assertEquals(13.0, z0, 0.0)
        // One screen east at zoom 15 on a view 400 dp wide: 400 / 256 tiles of 1/2^15 of the world.
        val (c1, z1) = ZoomScript.camera(start, ZoomScript.Step(15.0, 1.0, 0.0), 400.0, 800.0)
        assertEquals(start.x + 400.0 / 256 / 2.0.pow(15), c1.x, 1e-15)
        assertEquals(start.y, c1.y, 0.0)
        assertEquals(14.0, z1, 0.0)
        // One screen north: as high as the view (twice its width here).
        val (c2, z2) = ZoomScript.camera(start, ZoomScript.Step(14.0, 0.0, -1.0), 400.0, 800.0)
        assertEquals(start.y - 2 * 400.0 / 256 / 2.0.pow(14), c2.y, 1e-15)
        assertEquals(13.0, z2, 0.0)
        assertEquals(14.15, ZoomScript.camera(start, ZoomScript.steps.last(), 400.0, 800.0).second, 1e-12)
    }

    @Test
    fun theWatchdogLogsWhenTheMainThreadWasHeldUp() {
        ShadowLog.clear()
        val watch = Watchdog()
        watch.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)) // ticks every 100 ms: nothing to say
        assertEquals(0, ShadowLog.getLogsForTag("zoomscript").size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(700)) // held up: no tick meanwhile
        watch.tick()
        val logs = ShadowLog.getLogsForTag("zoomscript")
        assertEquals(1, logs.size)
        assertEquals("main thread held up 700 ms", logs[0].msg)
        watch.tick()
        assertEquals(1, ShadowLog.getLogsForTag("zoomscript").size)
        watch.stop()
    }
}
