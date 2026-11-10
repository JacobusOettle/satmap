package com.jacobusoettle.satmap

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.jacobusoettle.satmap.app.Connectivity

/** The emulator's Wi-Fi, which the walk's downloads need: like every new area they are Wi-Fi only, so on mobile data
 *  they wait ("Waiting for Wi-Fi"), as they should. Now and then the emulator's Wi-Fi is down for the whole walk: its
 *  access point gone from the scans, and the Wi-Fi it joined at boot without working DNS (every probe Android makes
 *  fails within milliseconds) while mobile data works. Android then makes mobile data the default network, and the
 *  walk's downloads waited for Wi-Fi for good (each wait 15 minutes, until the walk ran out of time). Turning Wi-Fi off
 *  and on again didn't bring it back. */
object TestNetwork {
    private const val TAG = "ScreenshotTest"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val manager: ConnectivityManager
        get() = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)

    /** The default network, as Android sees it ("WIFI validated", "CELLULAR validated", "none" …). */
    fun describe(): String {
        val n = manager.activeNetwork ?: return "none"
        val c = manager.getNetworkCapabilities(n) ?: return "unknown"
        val kind = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "other"
        }
        return kind + if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " validated" else " not validated"
    }

    /** Wi-Fi is the default network and reaches the internet (Android's VALIDATED: its probes got through). */
    private fun wifiWorks(): Boolean {
        val c = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Waits (a minute, ample after the emulator's boot) for Wi-Fi to be the network with the internet. When it isn't,
     *  mobile data counts as unmetered for the walk (`Connectivity.meteredAsUnmetered`, in SatMap's own process), so
     *  the downloads run on it: what the walk shows of them is the same. Returns what was done then, or null. */
    fun ensureWifi(): String? {
        val end = SystemClock.uptimeMillis() + 60_000
        while (!wifiWorks()) {
            if (SystemClock.uptimeMillis() >= end) {
                val what = "The emulator's Wi-Fi doesn't reach the internet (the network: ${describe()}): mobile data " +
                    "counts as unmetered for the walk"
                Log.w(TAG, "Network: $what")
                instrumentation.runOnMainSync { Connectivity.shared.meteredAsUnmetered = true }
                return what
            }
            Thread.sleep(500)
        }
        Log.i(TAG, "Network: ${describe()}")
        return null
    }
}
