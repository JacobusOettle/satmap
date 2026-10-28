package com.jacobusoettle.satmap

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry

/** iOS's `press(forDuration:thenDragTo:)`: down, hold, 20 moves, up, injected as real touches (screen pixels). */
object Gestures {
    fun pressAndDrag(from: PointF, to: PointF, holdMs: Long) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, at: PointF, time: Long) {
            val e = MotionEvent.obtain(down, time, action, at.x, at.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(e, true)
            e.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, from, down)
        SystemClock.sleep(holdMs)
        val steps = 20
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            inject(MotionEvent.ACTION_MOVE, PointF(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f), SystemClock.uptimeMillis())
            SystemClock.sleep(16)
        }
        inject(MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis())
    }
}
