package com.jacobusoettle.satmap.offline

import android.net.Network
import okhttp3.OkHttpClient

/** The HTTP clients of downloads (iOS `OfflineManager.makeSession`), separate from MapLibre's: one for any network,
 *  one bound to the Wi-Fi network a Wi-Fi-only run started on (its sockets fail when Wi-Fi goes instead of moving to
 *  mobile data), and one for the road and place lookups. */
object DownloadHttp {
    /** No cache, 20 s timeouts, 32 connections per host, User-Agent "SatMap/1.0 (Android)". */
    val anyNetwork: OkHttpClient by lazy { OkHttpClient() } // M4: offline spec §2.2.

    /** The same client bound to `network` (`socketFactory` and `dns`). */
    fun wifiOnly(network: Network): OkHttpClient = anyNetwork // M4: offline spec §2.2.

    /** Roads and places: 20 s timeouts, User-Agent "SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)". */
    val lookups: OkHttpClient by lazy { OkHttpClient() } // M4: offline spec §2.11.
}
