package com.jacobusoettle.satmap

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.jacobusoettle.satmap.core.GeoPoint

/** iOS's `XCUIDevice.location`: the phone's position, given to the app through test providers for `gps` and `network`
 *  (the workflow lets SatMap give mock positions: `appops set … android:mock_location allow`). As a GPS receiver (and
 *  the emulator's own GPS) does, the position is reported once a second, each time with the time of the report. */
object TestLocations {
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val lock = Any()
    private var current: GeoPoint? = null
    private var ticker: Thread? = null

    private val manager: LocationManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LocationManager::class.java)

    /** Puts the test providers in place of the real ones, and starts reporting once a second. Throws a
     *  `SecurityException` when SatMap may not give mock positions. */
    fun setUp() {
        for (p in providers) {
            try {
                manager.removeTestProvider(p)
            } catch (e: IllegalArgumentException) {
                // not added yet
            }
            if (Build.VERSION.SDK_INT >= 31) {
                manager.addTestProvider(
                    p,
                    ProviderProperties.Builder()
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                manager.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
            }
            manager.setTestProviderEnabled(p, true)
        }
        ticker = Thread {
            try {
                while (true) {
                    Thread.sleep(1000)
                    synchronized(lock) { current?.let(::report) }
                }
            } catch (e: InterruptedException) {
                // torn down
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Puts the phone at `point`: altitude 0 (above sea level) and accuracy 5 m, as the iOS test sets it, now. */
    fun set(point: GeoPoint) {
        synchronized(lock) {
            current = point
            report(point)
        }
    }

    /** Walks along `points`, one every 2.4 s (the app uses one fix every 2 s on screen); `wait` lets the time pass. */
    fun walk(points: List<GeoPoint>, wait: (Long) -> Unit = { Thread.sleep(it) }) {
        for (p in points) {
            set(p)
            wait(2400)
        }
    }

    fun tearDown() {
        ticker?.interrupt()
        ticker = null
        synchronized(lock) { current = null }
        for (p in providers) {
            try {
                manager.removeTestProvider(p)
            } catch (e: IllegalArgumentException) {
                // already gone
            }
        }
    }

    private fun report(point: GeoPoint) {
        for (p in providers) {
            val l = Location(p).apply {
                latitude = point.lat
                longitude = point.lon
                altitude = 0.0
                accuracy = 5f
                verticalAccuracyMeters = 5f
                // The height above sea level itself, as iOS gives it (DESIGN.md §5.1: used when present).
                if (Build.VERSION.SDK_INT >= 34) {
                    mslAltitudeMeters = 0.0
                    mslAltitudeAccuracyMeters = 5f
                }
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try {
                manager.setTestProviderLocation(p, l)
            } catch (e: IllegalArgumentException) {
                // the provider was removed meanwhile
            } catch (e: SecurityException) {
                // mock positions not allowed: setUp has said so already
            }
        }
    }
}
