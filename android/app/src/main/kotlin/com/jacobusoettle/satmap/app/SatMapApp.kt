package com.jacobusoettle.satmap.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.map.ContourRenderer
import com.jacobusoettle.satmap.map.ImageryMemory
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.TileInterceptor
import com.jacobusoettle.satmap.map.TileRepository
import com.jacobusoettle.satmap.offline.AndroidSqlDriver
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/** iOS's `AppDelegate`: the process-wide set-up, in the Swift order, whether or not there is UI (recording, downloads
 *  and Android Auto run without an activity), and the memory trims. */
class SatMapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. Preferences, folders, the network, and the app state (ProcessLifecycleOwner is already set up by App
        //    Startup here). Then SQLite for every tile store (the read-write mode, DESIGN.md D17), before anything
        //    opens one: TileRepository's offline readers, OfflineManager's downloads, and OfflineBackup's export and
        //    restore.
        Defaults.init(this)
        AppPaths.init(this)
        Connectivity.init(this)
        AppLifecycle.init()
        MBTiles.driver = AndroidSqlDriver()
        // 2. The tile store, with its HTTP client and limiters.
        TileRepository.init(this)
        // 3. MapLibre. Its own "offline" stops every request, ours to satmap.local included, so offline maps would
        //    not show: whether to use the network is TileRepository's decision (Connectivity), and MapLibre always
        //    thinks it is online (DESIGN.md D19). Every tile goes through TileInterceptor.
        MapLibre.getInstance(this)
        MapLibre.setConnected(true)
        HttpRequestUtil.setOkHttpClient(TileInterceptor.mapClient)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        // 4. The model: the recorder, KML library and offline areas, and recording (from the first foreground).
        AppModel.init(this)
    }

    /** iOS's memory warning. From API 34 only UI_HIDDEN and BACKGROUND are delivered (DESIGN.md D15). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            trim()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        trim()
    }

    private fun trim() {
        TileRepository.shared.trimMemory()
        ImageryMemory.trimMemory()
        ContourRenderer.shared.clearCaches()
        // Each live map drops its own tile cache too (the car map's from M5).
        (AppModel.shared.map as? MapController)?.onLowMemory()
    }
}
