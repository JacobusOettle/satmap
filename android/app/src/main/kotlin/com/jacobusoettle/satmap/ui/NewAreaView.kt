package com.jacobusoettle.satmap.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.Terrarium
import com.jacobusoettle.satmap.core.TileCoverage
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.core.stringFormat
import com.jacobusoettle.satmap.offline.OfflineManager
import com.jacobusoettle.satmap.offline.RoadFetcher
import com.jacobusoettle.satmap.offline.RoadPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** "Where I've been": what a download along your track covers. */
data class TrackDownload(
    /** The area's lines after this download: your track plus the parts of earlier trips already in the area. */
    val lines: List<List<GeoPoint>>,
    /** The parts of your track away from the area's lines (for the distance shown), or all of `lines`. */
    val newLines: List<List<GeoPoint>>,
    /** The track area's lines before this download (empty without one). */
    val previousLines: List<List<GeoPoint>>,
    /** The track area's own settings, if it exists. */
    val maxZoom: Int?,
    val allowCellular: Boolean?,
    /** The zoom the track area is completely downloaded to, or null (no area yet, or its download didn't finish). */
    val completeTo: Int?,
)

/** What "Download area" shows and works out (Swift `NewAreaView`'s state), kept in the screen's route so it lasts
 *  while the screen is up. Main thread only. */
class NewAreaState(
    val area: PendingArea,
    /** A road set shipped with the app (`RoadPresets`): imagery is always downloaded along its roads. */
    val presetID: String? = null,
    /** Your recorded track ("Where I've been"): imagery along it, updating the one track area. */
    val track: TrackDownload? = null,
) {
    sealed interface RoadsState {
        data object NotLoaded : RoadsState
        data object Loading : RoadsState

        /** Roads clipped to the area, their length, and imagery tile counts per zoom along them. */
        class Loaded(val lines: List<List<GeoPoint>>, val km: Double, val counts: Map<Int, Long>) : RoadsState
        class Failed(val message: String) : RoadsState
    }

    /** A download size: tiles (a `Long`, as Swift's `Int`) and bytes. */
    data class Estimate(val tiles: Long, val bytes: Long)

    var name by mutableStateOf(area.name)

    /** The deepest zoom level to download; remembered for the next area (`@AppStorage("offlineMaxZoom")`). */
    var maxZoom by mutableIntStateOf(Defaults.int(maxZoomKey) ?: TileSources.imageryMaxZoom)
        private set

    /** For your track: the track area's own zoom, kept apart from the one remembered for other areas. */
    var trackZoom by mutableStateOf(track?.maxZoom)
        private set

    /** A level was tapped here: it is downloaded as chosen, however big (the remembered one may be lowered). */
    var picked by mutableStateOf(false)
        private set
    var allowCellular by mutableStateOf(track?.allowCellular ?: false)
    var alongRoads by mutableStateOf(presetID != null || track != null)
    var roads by mutableStateOf<RoadsState>(RoadsState.NotLoaded)

    /** Upper-bound tile counts per zoom level for the whole area (computed in the background). */
    var counts by mutableStateOf<Map<Int, Long>?>(null)
    var freeSpace by mutableStateOf<Long?>(null)

    /** Imagery always goes along given lines (a shipped road list or your track), never the whole area. */
    val fixedLines: Boolean get() = presetID != null || track != null

    /** A level tapped: downloaded as chosen, and remembered (for your track, as the track area's own). */
    fun choose(z: Int) {
        picked = true
        if (track != null) {
            trackZoom = z
        } else {
            maxZoom = z
            Defaults.putInt(maxZoomKey, z)
        }
    }

    /** The level to download: the one remembered from the last area (or tapped here), lowered until it takes at most
     *  half the free space unless it was tapped, so a whole park doesn't start out as hundreds of gigabytes. Your track
     *  area keeps its own level (it is saved as the area's, and holds tiles to that level already). */
    val chosenZoom: Int
        get() {
            val fits = deepestFitting
            if (picked || track?.maxZoom != null || fits == null) return rememberedZoom
            return min(rememberedZoom, fits)
        }

    val rememberedZoom: Int
        get() = min(max(trackZoom ?: maxZoom, zoomChoices.last()), zoomChoices.first())

    /** Once the sizes are known, the most detailed level taking at most half the free space (above what downloads
     *  stop at); the least detailed when none does. None below that space: nothing fits, as the warning says. */
    val deepestFitting: Int?
        get() {
            val counts = counts ?: return null
            val imagery = imageryCounts ?: return null
            val free = freeSpace ?: return null
            if (free <= OfflineManager.minimumFreeSpace) return null
            val room = (free - OfflineManager.minimumFreeSpace) / 2
            return zoomChoices.firstOrNull { estimate(imagery, counts, depth = it).bytes <= room } ?: zoomChoices.last()
        }

    /** Download size up to zoom z: about, where Esri's imagery usually reaches; an upper limit deeper down. */
    fun sizeText(z: Int): String {
        val counts = counts
        val imagery = imageryCounts
        if (counts == null || imagery == null) {
            if (alongRoads && roads is RoadsState.Failed) return "–"
            return "…"
        }
        val e = estimate(imagery, counts, depth = z)
        if (e.tiles == 0L) return "Nothing new" // your track, already saved to this level
        val bytes = Format.bytes(e.bytes)
        return if (z <= typicalDepth) "≈ $bytes" else "up to $bytes"
    }

    val chosenEstimate: Estimate?
        get() {
            val counts = counts ?: return null
            val imagery = imageryCounts ?: return null
            return estimate(imagery, counts, depth = chosenZoom)
        }

    val roadLines: List<List<GeoPoint>>?
        get() = (roads as? RoadsState.Loaded)?.lines

    /** Imagery tile counts per zoom: the whole area, or only along the roads. */
    val imageryCounts: Map<Int, Long>?
        get() {
            if (!alongRoads) return counts
            return (roads as? RoadsState.Loaded)?.counts
        }

    /** The Download button: a name, the roads when along them, and the sizes known (the level may yet be lowered to
     *  fit). */
    val canDownload: Boolean
        get() = name.trim().isNotEmpty() && !(alongRoads && roadLines.isNullOrEmpty()) && chosenEstimate != null

    /** The area's size, when it has one (lines and points have none). Worked out once: a park's outline is long. */
    val areaSquareMeters: Double? by lazy {
        val a = area.shape.polygonAreaSquareMeters
        if (a > 0) a else null
    }

    /** The middle of the area's latitude, for the ground size of a pixel. */
    private val centreLatitude: Double by lazy { area.shape.bounds?.center?.lat ?: 0.0 }

    /** Ground size of one image pixel at zoom z in the middle of the area. */
    fun resolution(z: Int): String = Format.groundResolution(z, centreLatitude)

    /** The area's tile counts, and the free space, in the background. */
    internal suspend fun count() {
        val shape = area.shape
        // Elevation already saved for your track isn't downloaded again.
        val saved = if (track?.completeTo != null) Roads.corridor(track.previousLines) else null
        // Along a road list or your track the whole area only matters for elevation; its imagery counts for a big
        // park would take long to work out.
        val zooms = if (fixedLines) 0..Terrarium.maxZoom else 0..TileSources.imageryMaxZoom
        val result = withContext(Dispatchers.Default) {
            val counts = HashMap<Int, Long>()
            for (z in zooms) {
                val c = TileCoverage(shape = shape, zoom = z)
                counts[z] = saved?.let { c.count(TileCoverage(shape = it, zoom = z)) } ?: c.tileCount
            }
            Pair(counts, AppPaths.freeDiskSpace())
        }
        freeSpace = result.second
        counts = result.first
    }

    /** The roads: your track, the shipped road list, or those in the area (shipped for Kruger, else from
     *  OpenStreetMap through `fetch`), with the imagery tile counts along them. */
    internal suspend fun loadRoads(fetch: suspend (CoverageShape) -> List<List<GeoPoint>> = RoadFetcher::roads) {
        roads = RoadsState.Loading
        val shape = area.shape
        val preset = RoadPresets.load(presetID)
        try {
            val lines: List<List<GeoPoint>> = when {
                track != null -> track.lines
                preset != null -> preset.roads
                else -> withContext(Dispatchers.Default) {
                    // Inside Kruger (all of it, or a camp's surroundings): the road list shipped with the app.
                    RoadPresets.covering(shape)?.let { Roads.clip(it.roads, shape) }
                } ?: fetch(shape)
            }
            // For your track, the levels the area already has are only needed where its corridor is new.
            val saved = track?.let { Roads.corridor(it.previousLines) }
            val savedTo = track?.completeTo ?: -1
            val result = withContext(Dispatchers.Default) {
                val whole = Roads.corridor(lines)
                val counts = HashMap<Int, Long>()
                for (z in 0..TileSources.imageryMaxZoom) {
                    val c = TileCoverage(shape = whole, zoom = z)
                    counts[z] = if (z <= savedTo && saved != null) c.count(TileCoverage(shape = saved, zoom = z)) else c.tileCount
                }
                Pair(Roads.length(lines) / 1000, counts)
            }
            roads = RoadsState.Loaded(lines, km = result.first, counts = result.second)
        } catch (e: CancellationException) {
            roads = RoadsState.NotLoaded
            throw e
        } catch (e: Exception) {
            // Swift shows a `LocalizedError`'s own text: here `RoadFetcher.Failure`'s.
            roads = RoadsState.Failed((e as? RoadFetcher.Failure)?.message ?: "Couldn't get the roads. Try again.")
        }
    }

    companion object {
        const val maxZoomKey = "offlineMaxZoom"

        /** Zoom levels offered, most detailed first: from about 3 cm to 5 m per pixel. */
        val zoomChoices: List<Int> = (15..TileSources.imageryMaxZoom).reversed().toList()

        /** Rough average sizes from Esri JPEG and Terrarium PNG tiles. */
        const val imageryTileBytes = 17_000.0
        const val terrainTileBytes = 45_000.0
        const val typicalDepth = 19

        fun estimate(imageryCounts: Map<Int, Long>, terrainCounts: Map<Int, Long>, depth: Int): Estimate {
            val imagery = (0..depth).fold(0L) { sum, z -> sum + (imageryCounts[z] ?: 0) }
            val terrain = (0..Terrarium.maxZoom).fold(0L) { sum, z -> sum + (terrainCounts[z] ?: 0) }
            val bytes = imagery.toDouble() * imageryTileBytes + terrain.toDouble() * terrainTileBytes
            return Estimate(imagery + terrain, bytes.toLong())
        }
    }
}

/** Confirm an offline area: name, detail (deepest zoom level, with the size of each), only along roads, mobile data,
 *  and the size of the chosen download. `showsCancel`: presented on its own (lasso) rather than pushed inside the
 *  Offline sheet (KML), which gives `back`. `close` closes the sheet it is in. */
@Composable
fun NewAreaView(
    model: AppModel,
    state: NewAreaState,
    showsCancel: Boolean = false,
    back: ScreenChrome.Leading.Back? = null,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val askNotifications = rememberNotificationQuestion()
    val leading = if (showsCancel) {
        ScreenChrome.Leading.Cancel {
            model.discardPendingArea()
            close()
        }
    } else {
        back
    }
    SheetScreen(ScreenChrome("Download area", leading)) {
        item {
            FormSection(header = "Name", first = true) {
                FormTextFieldRow(
                    state.name,
                    onValueChange = { state.name = it },
                    placeholder = "Area name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
            }
        }
        item { DetailSection(state) }
        item { RoadsSection(state, onRetry = { scope.launch { state.loadRoads() } }) }
        item {
            // Android: the download goes on in a foreground service, its progress in a notification (iOS: the Lock
            // Screen's).
            FormSection(
                footer = "Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, " +
                    "with its progress in a notification. If Android stops it, it continues when you open SatMap again.",
            ) {
                FormToggleRow("Allow mobile data", state.allowCellular) { state.allowCellular = it }
            }
        }
        item { SizeSection(state) }
        item {
            FormSection {
                // Not before the sizes are known: the level may yet be lowered to fit.
                FormActionRow(
                    "Download",
                    Icons.Filled.ArrowCircleDown,
                    enabled = state.canDownload,
                    iconSize = 24.dp,
                    style = Headline,
                ) {
                    askNotifications()
                    val trimmed = state.name.trim()
                    val track = state.track
                    if (track != null) {
                        model.startTrackDownload(trimmed, track.lines, maxZoom = state.chosenZoom, allowCellular = state.allowCellular)
                    } else {
                        model.startDownload(
                            PendingArea(name = trimmed, shape = state.area.shape),
                            roads = if (state.presetID == null && state.alongRoads) state.roadLines else null,
                            preset = state.presetID,
                            maxZoom = state.chosenZoom,
                            allowCellular = state.allowCellular,
                        )
                    }
                    // Back to the map, where the download banner shows progress.
                    model.setSheet(null)
                    close()
                }
            }
        }
    }
    LaunchedEffect(state) {
        // Once per screen (it is never under another one, so this runs when it is shown).
        if (state.counts == null) state.count()
    }
    LaunchedEffect(state, state.alongRoads) {
        if (state.alongRoads && state.roads == NewAreaState.RoadsState.NotLoaded) state.loadRoads()
    }
}

/** Every zoom level with its detail and download size; tap one to choose it. */
@Composable
private fun DetailSection(state: NewAreaState) {
    FormSection(
        header = "Detail",
        footer = "Each zoom level has twice the detail of the one before and takes about 4 times the space. Esri's " +
            "imagery often stops at zoom 18–19 on farms and 20–21 in towns; the download stops where the imagery " +
            "does, so deeper levels can add little. Sizes include elevation for contours and heights.",
    ) {
        val chosen = state.chosenZoom
        for (z in NewAreaState.zoomChoices) {
            FormChoiceRow(z == chosen, verticalPadding = 9.dp, onClick = { state.choose(z) }) {
                ZoomRow("Zoom $z", LabelPrimary, state.resolution(z), state.sizeText(z), checkShown = z == chosen, checkColour = Accent)
            }
        }
    }
}

/** A zoom level's row: its name and the ground size of a pixel, its size, and the check mark (shown or not, its room
 *  kept either way). Also `AreaDetailView`'s. */
@Composable
internal fun RowScope.ZoomRow(
    title: String,
    titleColour: Color,
    resolution: String,
    value: String,
    checkShown: Boolean,
    checkColour: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Body, color = titleColour)
        Text(resolution, style = Caption, color = LabelSecondary)
    }
    Spacer(Modifier.weight(1f).width(8.dp))
    Text(value, style = Body.copy(fontFeatureSettings = TabularDigits), color = LabelSecondary, maxLines = 1)
    Spacer(Modifier.width(8.dp))
    // The row is read as selected instead.
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = checkColour,
        modifier = Modifier.size(20.dp).alpha(if (checkShown) 1f else 0f).clearAndSetSemantics {},
    )
}

/** Imagery along your track, along the shipped road list, or a switch for along the area's roads. */
@Composable
private fun RoadsSection(state: NewAreaState, onRetry: () -> Unit) {
    val corridor = Roads.corridorMeters.toInt()
    val preset = remember(state.presetID) { RoadPresets.load(state.presetID) }
    when {
        state.track != null -> FormSection(
            footer = "Satellite imagery $corridor m to each side of everywhere you've been (your dotted track), with " +
                "elevation under it. Come back here after a trip to add the new parts: what is already saved is skipped.",
        ) {
            RoadsStatus(state, onRetry)
        }
        preset != null -> FormSection(
            footer = "Satellite imagery $corridor m to each side of every tar and gravel road in the park, including " +
                "management tracks: ${preset.note}. Elevation for contours and heights is saved for the whole park.",
        ) {
            RoadsStatus(state, onRetry)
        }
        else -> FormSection(
            footer = "Saves satellite imagery only $corridor m to each side of every road and track in the area, " +
                "including gravel and farm roads, as mapped on OpenStreetMap. Elevation for contours and heights is " +
                "still saved for the whole area.",
        ) {
            FormToggleRow("Only along roads", state.alongRoads) { state.alongRoads = it }
            if (state.alongRoads) RoadsStatus(state, onRetry)
        }
    }
}

@Composable
private fun RoadsStatus(state: NewAreaState, onRetry: () -> Unit) {
    when (val roads = state.roads) {
        NewAreaState.RoadsState.NotLoaded, NewAreaState.RoadsState.Loading -> FormRow {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FormSpinner()
                Text("Finding roads…", style = Body, color = LabelSecondary)
            }
        }
        is NewAreaState.RoadsState.Loaded -> {
            val track = state.track
            if (track != null) {
                FormValueRow("Your track", kilometres(roads.km))
                if (track.completeTo != null) {
                    val new = remember(track) { Roads.length(track.newLines) / 1000 }
                    FormValueRow("New since the last download", kilometres(new))
                }
            } else if (roads.km > 0) {
                FormValueRow("Roads and tracks", kilometres(roads.km))
            } else {
                FormRow { Text("No roads or tracks are mapped in this area.", style = Body, color = SystemOrange) }
            }
        }
        is NewAreaState.RoadsState.Failed -> {
            FormRow { Text(roads.message, style = Body, color = SystemOrange) }
            FormActionRow("Try again", onClick = onRetry)
        }
    }
}

@Composable
private fun SizeSection(state: NewAreaState) {
    FormSection(header = "Download size") {
        state.areaSquareMeters?.let { FormValueRow("Area", Format.hectares(it)) }
        val e = state.chosenEstimate
        when {
            e != null -> {
                val chosen = state.chosenZoom
                FormValueRow("Up to zoom $chosen", state.sizeText(chosen))
                FormValueRow("Tiles (most)", Format.count(e.tiles))
                state.freeSpace?.let { free ->
                    FormValueRow("Free space", Format.bytes(free))
                    if (e.bytes > free - OfflineManager.minimumFreeSpace) {
                        FormRow {
                            Text(
                                "This may not fit. The download stops when less than 500 MB is free; choose a lower " +
                                    "zoom or a smaller area.",
                                style = Body,
                                color = SystemOrange,
                            )
                        }
                    } else if (chosen < state.rememberedZoom) {
                        val r = state.rememberedZoom
                        FormRow {
                            Text(
                                "Zoom $r needs ${state.sizeText(r)}, more than half the free space, so less detail is " +
                                    "chosen. Tap a level above to choose another.",
                                style = Body,
                                color = LabelSecondary,
                            )
                        }
                    }
                }
            }
            state.alongRoads && state.roads is NewAreaState.RoadsState.Failed -> FormRow {
                Text("Needs the roads first.", style = Body, color = LabelSecondary)
            }
            else -> FormRow {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormSpinner()
                    Text("Calculating…", style = Body, color = LabelSecondary)
                }
            }
        }
    }
}

/** "%.1f km" under 10 km, else whole kilometres grouped ("7,930 km"). */
internal fun kilometres(km: Double): String =
    if (km < 10) stringFormat("%.1f km", km) else "${Format.count(km.rounded().toInt())} km"

/** Android only: notifications are asked for at the first tap of a Download button (DESIGN.md D10), so a download's
 *  progress can show in one. Asked once per install (`notificationPermissionAsked`); the download starts whatever the
 *  answer, and the background status says whether its progress shows. */
@Composable
internal fun rememberNotificationQuestion(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 && Defaults.bool("notificationPermissionAsked") != true &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Defaults.putBoolean("notificationPermissionAsked", true)
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: ActivityNotFoundException) {
                    // No permission screen on this phone: the download goes on without the question.
                }
            }
        }
    }
}
