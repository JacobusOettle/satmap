package com.jacobusoettle.satmap

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.jacobusoettle.satmap.core.GeoPoint

/** iOS's `XCUIDevice.location`: mock positions on the `gps` and `network` providers (the workflow allows mock
 *  locations with `appops set … android:mock_location allow`). */
object TestLocations {
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val manager: LocationManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LocationManager::class.java)

    fun setUp() {
        for (p in providers) {
            try {
                manager.removeTestProvider(p)
            } catch (e: IllegalArgumentException) {
                // not added yet
            }
            if (Build.VERSION.SDK_INT >= 31) {
                manager.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE,
                )
            } else {
                @Suppress("DEPRECATION")
                manager.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
            }
            manager.setTestProviderEnabled(p, true)
        }
    }

    /** Puts the phone at `point` (accuracy 5 m, now). */
    fun set(point: GeoPoint) {
        for (p in providers) {
            val l = Location(p).apply {
                latitude = point.lat
                longitude = point.lon
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(p, l)
        }
    }

    /** One point every 2.4 s, as the iOS walk. */
    fun walk(points: List<GeoPoint>) {
        for (pt in points) {
            set(pt)
            Thread.sleep(2400)
        }
    }

    fun tearDown() {
        for (p in providers) {
            try {
                manager.removeTestProvider(p)
            } catch (e: IllegalArgumentException) {
                // already gone
            }
        }
    }
}
