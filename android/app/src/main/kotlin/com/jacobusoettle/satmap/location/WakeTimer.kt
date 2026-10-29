package com.jacobusoettle.satmap.location

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max

/** A recorder timer that fires while the CPU sleeps: an `AlarmManager` listener alarm on `ELAPSED_REALTIME_WAKEUP`
 *  (iOS `Timer`s keep firing in the background because the location updates keep the app running; a Handler or
 *  coroutine delay counts uptime, which stops while the CPU suspends). Fires on the main thread, once; a repeating
 *  timer schedules itself again in its action. Listener alarms are delivered only while the process lives, which the
 *  foreground service sees to. `SCHEDULE_EXACT_ALARM` is deliberately not declared: where Android refuses an exact
 *  alarm without it (Android 12–13), `set()` is used, which may fire up to about 75 % of the delay late. */
class WakeTimer(context: Context, private val tag: String, private val action: () -> Unit) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AlarmManager.OnAlarmListener {
        scheduled = false
        action()
    }

    /** Waiting to fire. */
    var scheduled: Boolean = false
        private set

    /** Fires once, `afterSeconds` from now (replacing a time set before). Android moves an alarm under 5 s ahead to
     *  5 s, which only delays such a timer a little. */
    fun schedule(afterSeconds: Double) {
        cancel()
        val at = SystemClock.elapsedRealtime() + (max(0.0, afterSeconds) * 1000).toLong()
        try {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "satmap:$tag", listener, handler)
        } catch (e: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "satmap:$tag", listener, handler)
        }
        scheduled = true
    }

    fun cancel() {
        am.cancel(listener)
        scheduled = false
    }
}
