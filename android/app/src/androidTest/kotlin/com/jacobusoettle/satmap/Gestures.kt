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
    fun pressAndDrag(from: PointF, to: PointF, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, from)
        SystemClock.sleep(holdMs)
        val steps = 20
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            val at = PointF(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f)
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, at)
            SystemClock.sleep(16)
        }
        inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, to)
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
