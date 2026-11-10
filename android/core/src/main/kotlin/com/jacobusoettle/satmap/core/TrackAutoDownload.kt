package com.jacobusoettle.satmap.core

/** "Auto download" on the Where I've been screen: while it is on (the default, for the whole app), SatMap updates the
 *  track area by itself, exactly as that screen's Download button would, once you've been somewhere new. The rule is
 *  here, the same in both apps; the app says when to look, gives it what it knows, and starts what it returns. */
object TrackAutoDownload {
    /** The setting (UserDefaults on the iPhone, the app's SharedPreferences on Android); on while never set. */
    const val settingKey = "trackAutoDownload"
    const val enabledByDefault = true

    /** New parts of the track shorter than this in total wait for more. */
    const val minimumNewMeters = 500.0

    /** At most one check this often, in seconds (wall clock), whatever it finds. */
    const val checkInterval = 10 * 60.0

    /** Nothing starts with less than this free (downloads stop at 500 MB: `OfflineManager.minimumFreeSpace`). */
    const val minimumFreeBytes: Long = 1L shl 30

    /** A new track area's name, as the screen offers it. */
    const val defaultName = "Where I've been"

    /** The track area, as far as the rule needs it. */
    data class Area(
        val name: String,
        val lines: List<List<GeoPoint>>,
        val maxZoom: Int,
        val allowCellular: Boolean,
        /** Downloading right now (queued or waiting for the network doesn't count). */
        val isDownloading: Boolean,
    )

    /** What the Download button would be given (`updateTrackArea`). */
    data class Update(val name: String, val lines: List<List<GeoPoint>>, val maxZoom: Int, val allowCellular: Boolean)

    /** Whether to check now: the setting is on and the last check began at least `checkInterval` ago (or never, or the
     *  clock has been put back since). Times in seconds. */
    fun isDue(enabled: Boolean, lastCheck: Double?, now: Double): Boolean {
        if (!enabled) return false
        val last = lastCheck ?: return true
        val since = now - last
        return since >= checkInterval || since < 0
    }

    /** The check: the update to start, or null when there is nothing to do. Not while the track area is downloading
     *  (that run is left alone), not with less than `minimumFreeBytes` free (or free space unknown), and only once the
     *  parts of the track away from the area's lines (`Roads.newParts`; with no track area, the whole track) are at
     *  least `minimumNewMeters` long in total. The settings are the track area's, or for a new one the screen's: its
     *  name, `newAreaZoom` (the zoom the screen would choose for a track area along these lines; asked only then) and no
     *  mobile data. Slow for a long track: not on the main thread. */
    fun update(
        track: List<TrackPoint>,
        area: Area?,
        freeBytes: Long?,
        newAreaZoom: (List<List<GeoPoint>>) -> Int,
    ): Update? {
        if (area?.isDownloading == true) return null
        if (freeBytes == null || freeBytes < minimumFreeBytes) return null
        val latest = Roads.trackLines(track)
        val previous = area?.lines ?: emptyList()
        if (!(Roads.length(Roads.newParts(latest, others = previous)) >= minimumNewMeters)) return null
        val lines = Roads.mergeTrack(previous, latest)
        if (area == null) return Update(name = defaultName, lines = lines, maxZoom = newAreaZoom(lines), allowCellular = false)
        return Update(name = area.name, lines = lines, maxZoom = area.maxZoom, allowCellular = area.allowCellular)
    }
}
