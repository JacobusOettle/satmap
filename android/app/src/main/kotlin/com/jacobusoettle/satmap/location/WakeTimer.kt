package com.jacobusoettle.satmap.location

import android.content.Context

/** A recorder timer that fires while the CPU sleeps: an `AlarmManager` listener alarm on `ELAPSED_REALTIME_WAKEUP`
 *  (iOS `Timer`s keep firing in the background because the location updates keep the app running; a Handler or
 *  coroutine delay would stop with the CPU). Exact where allowed, else `set()` (inexact). `SCHEDULE_EXACT_ALARM` is
 *  deliberately not declared. */
class WakeTimer(private val context: Context, private val tag: String, private val action: () -> Unit) {
    var scheduled: Boolean = false
        private set

    fun schedule(afterSeconds: Double) {
        // M1: AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, now + afterSeconds, "satmap:$tag", listener, main handler).
    }

    fun cancel() {
        scheduled = false
        // M1: AlarmManager.cancel(listener).
    }
}
