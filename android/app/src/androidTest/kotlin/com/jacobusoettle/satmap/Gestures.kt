package com.jacobusoettle.satmap

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt

/** Touches made as a finger makes them, through the system's input (so they reach whatever is on top, as on iOS). */
object Gestures {
    /** A tap at `at` (screen pixels), its down and up given to Android one straight after the other (`Touches.tap`).
     *  `UiDevice.click` gives the down, waits until SatMap has handled it, and gives the up 100 ms later: when SatMap
     *  handles the down late (the emulator draws in software and now and then stalls for seconds), the long-press time
     *  counted from the down's time has passed by then, so Android's gesture detector takes the touch for a long press
     *  before the up is even given, and the map, which does nothing on a long press, never hears of a tap. Given
     *  together, the up is there as soon as the down has been handled. On whole pixels, as `UiDevice.click` taps: the
     *  spot a tap selects, and the coordinates the screenshots show for it, stay those of the pixel tapped before.
     *  Returns whether both were taken. */
    fun tap(at: PointF): Boolean {
        val down = SystemClock.uptimeMillis()
        val pixel = PointF(at.x.roundToInt().toFloat(), at.y.roundToInt().toFloat())
        return Touches.tap(pixel).all { inject(down, SystemClock.uptimeMillis(), it.action, it.at, sync = false) }
    }

    /** iOS's `press(forDuration:thenDragTo:)`: down at `from`, held for `holdMs`, moved to `to` in 20 steps, then up.
     *  Points are screen pixels. */
    fun pressAndDrag(from: PointF, to: PointF, holdMs: Long) = perform(Touches.pressAndDrag(from, to, holdMs))

    /** iOS's `press(forDuration:thenDragTo:withVelocity:thenHoldForDuration:)` on the map: the map moves from `from` to
     *  `to` and stays there (`Touches.mapDrag`). Points are screen pixels. */
    fun dragMap(from: PointF, to: PointF, holdMs: Long, thenHoldMs: Long, density: Float) =
        perform(Touches.mapDrag(from, to, holdMs, thenHoldMs, density))

    /** The touches one after the other, each its `afterMs` after the one before. */
    fun perform(touches: List<Touch>) {
        var down = 0L
        for (t in touches) {
            if (t.afterMs > 0) SystemClock.sleep(t.afterMs)
            val now = SystemClock.uptimeMillis()
            if (t.action == MotionEvent.ACTION_DOWN) down = now
            inject(down, now, t.action, t.at)
        }
    }

    /** One finger event (pointer 0, a finger on the touch screen, as UiAutomator makes them). `sync`: returns once the
     *  app has handled it. */
    private fun inject(downTime: Long, eventTime: Long, action: Int, at: PointF, sync: Boolean = true): Boolean {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = at.x
            y = at.y
            pressure = 1f
            size = 1f
        }
        val e = MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val taken = InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(e, sync)
        e.recycle()
        return taken
    }
}
