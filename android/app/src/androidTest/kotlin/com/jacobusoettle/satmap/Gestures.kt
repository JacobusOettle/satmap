package com.jacobusoettle.satmap

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry

/** Touches made as a finger makes them, through the system's input (so they reach whatever is on top, as on iOS). */
object Gestures {
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

    /** One finger event (pointer 0, a finger on the touch screen, as UiAutomator makes them). */
    private fun inject(downTime: Long, eventTime: Long, action: Int, at: PointF) {
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }
}
