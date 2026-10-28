package com.jacobusoettle.satmap.app

import com.jacobusoettle.satmap.core.TileID

/** Map data sources. Change the URL templates here to use a different provider.
 *
 *  Licensing: check that your imagery source allows offline storage. Esri's terms only allow taking World Imagery
 *  offline through Esri's own SDKs, so for licensed offline imagery point `imageryTemplate` at a source you are
 *  licensed to cache (or see README.md). */
object TileSources {
    /** Optional ArcGIS Location Platform API key. When set, imagery is requested from Esri's authenticated
     *  endpoint (counts toward your account's free monthly basemap tiles). */
    const val esriAPIKey = ""

    /** Esri World Imagery. `blankTile=false` makes the server answer 404 where it has no imagery at a zoom
     *  level, instead of a grey "Map data not yet available" picture, so those tiles are skipped. */
    val imageryTemplate: String = if (esriAPIKey.isEmpty())
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"
    else
        "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false&token=$esriAPIKey"

    /** Deepest imagery zoom ever requested. Rural areas typically stop at z18–19 and towns go to z20–21;
     *  the downloader discovers the real depth per place (see `DownloadLayer.pruneByParent`). */
    const val imageryMaxZoom = 22

    /** AWS Open Data "Terrain Tiles" (Terrarium encoding), used for contour lines and height differences. */
    const val terrainTemplate = "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png"

    const val attribution = "Powered by Esri · Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community · Elevation: Mapzen Terrain Tiles on AWS (USGS, NOAA, Copernicus and others)"

    fun imageryURL(t: TileID): String = url(imageryTemplate, t)

    fun terrainURL(t: TileID): String = url(terrainTemplate, t)

    private fun url(template: String, t: TileID): String = template
        .replace("{z}", t.z.toString())
        .replace("{x}", t.x.toString())
        .replace("{y}", t.y.toString())
}

/** GPS power policy. Times are seconds. */
object LocationPolicy {
    /** Process (draw / record) at most one fix per interval while the app is on screen. */
    const val foregroundInterval = 2.0
    /** Record one fix per interval while the app is in the background. Between fixes the GPS is
     *  allowed to power down (on Android its request is removed) and is woken shortly before the next fix. */
    const val backgroundInterval = 10.0
    /** How long before the next background fix the GPS is woken (hot-start time). */
    const val backgroundWakeLead = 3.0
    /** No movement for this long → sleep mode (GPS off). */
    const val stationaryTimeout = 120.0
    /** Movement threshold used for stationary detection, metres (raised to the fix accuracy if worse). */
    const val movementThreshold = 15.0
    /** While sleeping, briefly turn the GPS on this often to check whether we moved
     *  (backup for when motion detection misses walking, e.g. phone held steady in hand). */
    const val sleepPeekIntervalForeground = 60.0
    const val sleepPeekIntervalBackground = 120.0
    const val sleepPeekIntervalMeasuring = 15.0
    /** Give up a peek after this long without a usable fix. */
    const val peekTimeout = 15.0
    /** Fixes worse than this are ignored for the track. */
    const val maxTrackAccuracy = 20.0
    /** Don't add a track point unless we moved at least this far, metres. */
    const val minTrackSpacing = 3.0
    /** Start a new dotted-line segment instead of joining across a gap this long and far. */
    const val segmentBreakSeconds = 300.0
    const val segmentBreakMeters = 200.0
}
