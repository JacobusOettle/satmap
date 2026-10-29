package com.jacobusoettle.satmap.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.core.stringFormat
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.OfflineManager
import com.jacobusoettle.satmap.offline.PlaceSearch
import com.jacobusoettle.satmap.offline.RoadPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The Offline maps sheet's screens. Each route holds its screen's state, so a screen under another keeps it (iOS
 *  `NavigationStack`). */
sealed interface OfflineRoute {
    data object Root : OfflineRoute
    class PlaceSearch(val state: PlaceSearchState = PlaceSearchState()) : OfflineRoute
    class PlaceArea(val place: FoundPlace, val state: PlaceAreaState = PlaceAreaState()) : OfflineRoute
    data object KMLPicker : OfflineRoute
    class NewArea(val state: NewAreaState) : OfflineRoute

    /** A road set shipped with the app. */
    class Preset(val id: String) : OfflineRoute {
        /** Its download screen, or null when the road list is missing (loaded when shown, as iOS's). */
        val state: NewAreaState? by lazy {
            RoadPresets.load(id)?.let { preset ->
                NewAreaState(PendingArea(name = preset.name, shape = CoverageShape(polygons = preset.boundary)), presetID = id)
            }
        }
    }

    class Track(val state: TrackState = TrackState()) : OfflineRoute
    class AreaDetail(val id: UUID, val state: AreaDetailState = AreaDetailState()) : OfflineRoute

    /** The title of the route's screen, which labels the back button of a screen pushed over it (nothing is pushed
     *  over the download and area screens). */
    val title: String
        get() = when (this) {
            Root -> "Offline maps"
            is PlaceSearch -> "Find a place"
            KMLPicker -> "Choose KML"
            else -> ""
        }
}

class PlaceSearchState {
    var text by mutableStateOf("")
    var results by mutableStateOf<List<FoundPlace>>(emptyList())
    var searching by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

class PlaceAreaState {
    var shape by mutableStateOf<CoverageShape?>(null)
    var failed by mutableStateOf<String?>(null)

    /** The download screen for the place, once its boundary is loaded. */
    var newArea by mutableStateOf<NewAreaState?>(null)

    /** The place's boundary (or the circle around a spot), from OpenStreetMap through `area`. */
    internal suspend fun load(place: FoundPlace, area: suspend (FoundPlace) -> CoverageShape = PlaceSearch::area) {
        failed = null
        try {
            val s = area(place)
            shape = s
            newArea = NewAreaState(PendingArea(name = place.name, shape = s))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swift shows a `LocalizedError`'s own text: here `PlaceSearch.Failure`'s.
            failed = (e as? PlaceSearch.Failure)?.message ?: "Couldn't get the boundary. Try again."
        }
    }
}

/** "Where I've been": the download, worked out once. */
class TrackState {
    var worked by mutableStateOf(false)

    /** The download screen for your track, or null when no track is recorded. */
    var newArea by mutableStateOf<NewAreaState?>(null)
}

/** Offline areas: choose a new area (lasso or KML), manage downloads, and back them up. Owns its screens' back stack
 *  (a new one each time the sheet is shown; a test passes its own); `close` slides the sheet away. */
@Composable
fun OfflineSheet(
    model: AppModel,
    offline: OfflineManager,
    library: KMLLibrary,
    close: () -> Unit,
    stack: RouteStack<OfflineRoute> = remember { RouteStack(OfflineRoute.Root) },
) {
    // For work that goes on while another screen is over the one that started it (a place search, as iOS's Task).
    val sheetScope = rememberCoroutineScope()
    val backupMessage by offline.backupMessage.collectAsStateWithLifecycle()
    // Several files at once, from any app or folder, under any name: the files say what they are (DESIGN.md D13).
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        offline.restore(uris)
    }
    SheetStack(stack) { route, below ->
        val back = below?.let { ScreenChrome.Leading.Back(it.title, stack::pop) }
        when (route) {
            OfflineRoute.Root -> OfflineRoot(
                model,
                offline,
                close,
                push = stack::push,
                restore = {
                    try {
                        importer.launch(OfflineManager.restoreTypes)
                    } catch (e: ActivityNotFoundException) {
                        // No document picker on this phone: nothing to restore from.
                    }
                },
            )
            is OfflineRoute.PlaceSearch -> PlaceSearchView(model, route.state, back, sheetScope, push = stack::push)
            is OfflineRoute.PlaceArea -> PlaceAreaView(model, route.place, route.state, back, close)
            OfflineRoute.KMLPicker -> KMLAreaPicker(library, back, push = stack::push)
            is OfflineRoute.NewArea -> NewAreaView(model, route.state, back = back, close = close)
            is OfflineRoute.Preset -> PresetAreaView(model, route.state, back, close)
            is OfflineRoute.Track -> TrackAreaView(model, route.state, back, close)
            is OfflineRoute.AreaDetail -> AreaDetailView(model, offline, route.id, route.state, back)
        }
    }
    // On whichever screen is up, as the outcome of an export or restore can come at any time.
    backupMessage?.let { BackupAlert(it, onDismiss = offline::clearBackupMessage) }
}

/** The sheet's first screen: where areas come from, the areas, backup, free space and the map credits. */
@Composable
private fun OfflineRoot(
    model: AppModel,
    offline: OfflineManager,
    close: () -> Unit,
    push: (OfflineRoute) -> Unit,
    restore: () -> Unit,
) {
    val areas by offline.areas.collectAsStateWithLifecycle()
    val progress by offline.progress.collectAsStateWithLifecycle()
    val activeID by offline.activeID.collectAsStateWithLifecycle()
    val completedRevision by offline.completedRevision.collectAsStateWithLifecycle()
    val backgroundStatus by offline.backgroundStatus.collectAsStateWithLifecycle()
    val backupStatus by offline.backupStatus.collectAsStateWithLifecycle()
    val backupProgress by offline.backupProgress.collectAsStateWithLifecycle()
    val online = connectivityOnline()
    // Kept while another screen is over this one, and shown again as it is measured anew.
    var freeSpace by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { offline.refreshSizes() }
    LaunchedEffect(completedRevision, areas.size) {
        // The free-space query can be slow: run it off the main thread, not in composition.
        freeSpace = withContext(Dispatchers.IO) { AppPaths.freeDiskSpace() }
    }
    SheetScreen(ScreenChrome("Offline maps", ScreenChrome.Leading.Done(close))) {
        item {
            FormSection(
                footer = "Saves satellite imagery at the highest detail available plus elevation data for contours and " +
                    "heights, so the map works with no signal.",
                first = true,
            ) {
                FormLinkRow("Find a park, reserve or town", Icons.Filled.Search) { push(OfflineRoute.PlaceSearch()) }
                FormActionRow("Draw an area on the map", SatIcons.Lasso, onClick = model::startDrawingArea)
                FormLinkRow("Use the blocks in a KML file", SatIcons.SquareDashed) { push(OfflineRoute.KMLPicker) }
                FormLinkRow("Kruger National Park roads", Icons.Filled.DirectionsCar) {
                    push(OfflineRoute.Preset(RoadPresets.kruger))
                }
                FormLinkRow("Where I've been", Icons.AutoMirrored.Filled.DirectionsWalk) { push(OfflineRoute.Track()) }
            }
        }
        item {
            FormSection(header = "Offline areas", footer = backgroundStatus) {
                if (areas.isEmpty()) {
                    FormRow { Text("No offline areas yet.", style = Body, color = LabelSecondary) }
                }
                // Keyed by the area, as iOS's ForEach: a swipe deletes that area only.
                for (area in areas) key(area.id) {
                    FormSwipeToDeleteRow(onDelete = { offline.delete(area.id) }) { deleteAction ->
                        AreaLink(
                            area,
                            progress = if (activeID == area.id) progress else null,
                            canDownloadNow = offline.canDownloadNow(area),
                            online = online,
                            exportEnabled = backupStatus == null,
                            modifier = deleteAction,
                            onOpen = { push(OfflineRoute.AreaDetail(area.id)) },
                            onPause = { offline.pause(area.id) },
                            onResume = { offline.resume(area.id) },
                            onExport = { offline.export(listOf(area.id)) },
                        )
                    }
                }
            }
        }
        item {
            // Android: Google Drive, a folder (Storage Access Framework) or a USB drive, or another app (DESIGN.md D12).
            FormSection(
                header = "Backup",
                footer = "Saves each area as two MBTiles files, satellite and elevation, to Google Drive, a folder or a " +
                    "USB drive, or send them to a computer. To restore, choose both files of each area. Long-press an " +
                    "area to export only that one.",
            ) {
                FormActionRow(
                    "Export all offline maps",
                    Icons.Outlined.IosShare,
                    enabled = areas.isNotEmpty() && backupStatus == null,
                ) {
                    offline.export(areas.map { it.id })
                }
                FormActionRow("Restore from a backup", Icons.Outlined.SaveAlt, enabled = backupStatus == null, onClick = restore)
                backupStatus?.let { busy -> BackupProgressRow(busy, backupProgress) }
            }
        }
        item {
            FormSection(
                footer = "Map data: ${TileSources.attribution} · Roads and places: © OpenStreetMap contributors, found " +
                    "with Photon (komoot) and Nominatim · Airfields and navaids: OurAirports",
            ) {
                freeSpace?.let { free ->
                    // iOS: "…on this iPhone".
                    FormRow { Text("Free space on this phone: ${Format.bytes(free)}", style = Footnote, color = LabelSecondary) }
                }
            }
        }
    }
}

/** What the backup is doing, with a spinner while how far it has got can't be told, else a bar. */
@Composable
private fun BackupProgressRow(busy: String, done: Double?) {
    FormRow {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (done == null) FormSpinner()
                Text(busy, style = Body, color = LabelSecondary)
            }
            if (done != null) {
                LinearProgressIndicator(
                    progress = { done.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = ProgressTrack,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** iOS's progress bar track (the system fill). */
private val ProgressTrack = Color(0x5C787880)

/** An area's row: a tap opens it, a long press offers "Export", a swipe to the left deletes it. */
@Composable
private fun AreaLink(
    area: OfflineArea,
    progress: DownloadProgress?,
    canDownloadNow: Boolean,
    online: Boolean,
    exportEnabled: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        FormLinkRow(onClick = onOpen, modifier = modifier, onLongClick = { menu = true }, onLongClickLabel = "Export") {
            AreaRow(area, progress, canDownloadNow, onPause, onResume, online, Modifier.weight(1f))
        }
        // iOS's context menu: the item's icon after its title.
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = CellBackground) {
            DropdownMenuItem(
                text = { Text("Export", style = Body) },
                trailingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                enabled = exportEnabled,
                onClick = {
                    menu = false
                    onExport()
                },
                colors = MenuDefaults.itemColors(
                    textColor = Color.White,
                    trailingIconColor = Color.White,
                    disabledTextColor = LabelTertiary,
                    disabledTrailingIconColor = LabelTertiary,
                ),
            )
        }
    }
}

/** An area: its name, how its download is doing, its size and settings, and the pause / ↻ button or the check mark.
 *  (`canDownloadNow` is passed as iOS passes it; the status reads `online` itself, as the Swift reads
 *  `Connectivity`.) */
@Composable
fun AreaRow(
    area: OfflineArea,
    progress: DownloadProgress?,
    canDownloadNow: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    online: Boolean = connectivityOnline(),
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(area.name, style = Body.copy(fontWeight = FontWeight.Medium), color = LabelPrimary)
            Text(areaStatusText(area, progress, online), style = Caption, color = areaStatusColour(area))
            Text(areaInfoText(area), style = Caption2, color = LabelSecondary)
        }
        // The symbol at title2 size (26 dp), in a 44 dp button.
        when (area.status) {
            OfflineArea.Status.downloading, OfflineArea.Status.queued, OfflineArea.Status.waitingForNetwork ->
                StatusButton("Pause", onPause) {
                    Icon(Icons.Outlined.PauseCircle, contentDescription = null, tint = Accent, modifier = Modifier.size(26.dp))
                }
            OfflineArea.Status.paused, OfflineArea.Status.incomplete ->
                StatusButton("Resume", onResume) { RetryCircle(Accent) }
            OfflineArea.Status.complete -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SystemGreen, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** A borderless button in a row (iOS's in an area row): its symbol, dimmed while pressed. */
@Composable
private fun StatusButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .pressable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** The area's status line. */
internal fun areaStatusText(area: OfflineArea, progress: DownloadProgress?, online: Boolean): String =
    when (area.status) {
        OfflineArea.Status.queued -> "Waiting to start"
        OfflineArea.Status.downloading -> {
            val p = progress
            if (p == null || p.layer.isEmpty()) {
                "Downloading…"
            } else {
                "Downloading ${p.layer.lowercase()} · zoom ${p.zoom} of ${p.maxZoom} · " +
                    "${Format.count(p.levelDone)}/${Format.count(p.levelTotal)} tiles"
            }
        }
        OfflineArea.Status.paused -> "Paused"
        OfflineArea.Status.waitingForNetwork ->
            if (area.allowCellular || !online) "Waiting for internet" else "Waiting for Wi-Fi"
        OfflineArea.Status.complete -> "Ready offline"
        OfflineArea.Status.incomplete -> area.problem?.let { "Stopped: $it Tap ↻ to continue." }
            ?: "Incomplete: ${Format.count(area.failedTiles)} tiles failed. Tap ↻ to retry."
    }

private fun areaStatusColour(area: OfflineArea): Color = when (area.status) {
    OfflineArea.Status.complete -> SystemGreen
    OfflineArea.Status.incomplete -> SystemOrange
    else -> LabelSecondary
}

/** "68.4 MB · up to zoom 22", then how the imagery goes (along your track or roads) and whether mobile data may be
 *  used. */
internal fun areaInfoText(area: OfflineArea): String =
    "${Format.bytes(area.bytesOnDisk)} · up to zoom ${area.maxZoom}" +
        (if (area.isTrack == true) " · along your track" else if (area.alongRoads) " · along roads" else "") +
        (if (area.allowCellular) " · mobile data allowed" else "")

/** "Backup" with what an export or restore did (or what went wrong). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BackupAlert(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = CellBackground,
        title = { Text("Backup", style = Headline) },
        text = { Text(message, style = Footnote) },
        confirmButton = { TextButton(onDismiss) { Text("OK", style = Body) } },
    )
}

/** Finds a place by name (a park, a reserve, a town, a camp …) to download: its whole area or only along its roads.
 *  `scope`: the sheet's, so a search goes on while a found place's screen is over this one (as iOS's Task). */
@Composable
fun PlaceSearchView(
    model: AppModel,
    state: PlaceSearchState,
    back: ScreenChrome.Leading.Back?,
    scope: CoroutineScope,
    push: (OfflineRoute) -> Unit,
) {
    val distanceUnit by model.distanceUnit.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val transition = LocalScreenTransition.current
    fun search() {
        val q = state.text.trim()
        if (q.isEmpty() || state.searching) return
        state.searching = true
        state.message = null
        // As iOS's search key: the keyboard goes away while the results come.
        focusManager.clearFocus()
        keyboard?.hide()
        val near = model.map?.centerCoordinate
        scope.launch {
            try {
                val found = PlaceSearch.search(q, near)
                state.results = found
                state.message = if (found.isEmpty()) "No park, reserve, town or camp called “$q” was found. Try another spelling." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swift shows a `LocalizedError`'s own text: here `PlaceSearch.Failure`'s.
                state.message = (e as? PlaceSearch.Failure)?.message ?: "The search didn't work. Try again."
            } finally {
                state.searching = false
            }
        }
    }
    SheetScreen(ScreenChrome("Find a place", back)) {
        item {
            FormSection(
                footer = "Parks and reserves are saved within their boundary; towns, villages and camps with the area " +
                    "around them. Next you choose the detail, and whether to save the whole area or only along its roads.",
                first = true,
            ) {
                FormTextFieldRow(
                    state.text,
                    onValueChange = { state.text = it },
                    placeholder = "Kruger, Sabi Sand, Skukuza, Hazyview …",
                    modifier = Modifier.focusRequester(focus).testTag("placeSearchText"),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                ) {
                    Spacer(Modifier.width(8.dp))
                    if (state.searching) {
                        FormSpinner()
                    } else {
                        val enabled = state.text.trim().isNotEmpty()
                        Text(
                            "Search",
                            style = Body,
                            color = if (enabled) Accent else LabelTertiary,
                            modifier = Modifier.pressable(enabled = enabled) { search() },
                        )
                    }
                }
            }
        }
        state.message?.let { message ->
            item { FormSection { FormRow { Text(message, style = Body, color = LabelSecondary) } } }
        }
        if (state.results.isNotEmpty()) {
            item {
                FormSection(header = "Places") {
                    for (place in state.results) {
                        FormLinkRow(onClick = { push(OfflineRoute.PlaceArea(place)) }) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(place.name, style = Body, color = LabelPrimary)
                                Text(placeDetail(place, distanceUnit), style = Caption, color = LabelSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // On first show, ready to type (not on coming back to the results).
        if (state.results.isNotEmpty()) return@LaunchedEffect
        // Once the screen has slid in, so the keyboard doesn't open over the slide.
        transition.awaitShown()
        // The list lays its rows out after this composition: the field is there a frame later.
        withFrameNanos {}
        focus.requestFocus()
        keyboard?.show()
    }
}

/** "Nature reserve · Mpumalanga, South Africa · about 650 km²", or for a spot "Town · … · 4 km around it". */
internal fun placeDetail(p: FoundPlace, unit: DistanceUnit): String {
    val parts = mutableListOf(p.kind)
    if (p.region.isNotEmpty()) parts.add(p.region)
    val km2 = p.extentKm2
    if (p.hasBoundary && km2 != null) {
        parts.add(if (km2 >= 10) "up to ${Format.count(km2.rounded().toInt())} km²" else stringFormat("up to %.1f km²", km2))
    } else {
        val r = if (unit == DistanceUnit.metric) "${(p.radiusMeters / 1000).toInt()} km" else Format.distance(p.radiusMeters, unit)
        parts.add("$r around it")
    }
    return parts.joinToString(" · ")
}

/** The chosen place's boundary, once loaded, on the usual download screen (detail, whole area or along its roads).
 *  Its bar has no title until then. */
@Composable
fun PlaceAreaView(
    model: AppModel,
    place: FoundPlace,
    state: PlaceAreaState,
    back: ScreenChrome.Leading.Back?,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val newArea = state.newArea
    val failed = state.failed
    when {
        newArea != null -> NewAreaView(model, newArea, back = back, close = close)
        failed != null -> SheetPlainScreen(ScreenChrome("", back)) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(failed, style = Body, color = LabelSecondary, textAlign = TextAlign.Center)
                Text(
                    "Try again",
                    style = Body,
                    color = Accent,
                    modifier = Modifier.pressable { scope.launch { state.load(place) } },
                )
            }
        }
        else -> SheetPlainScreen(ScreenChrome("", back)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FormSpinner()
                Text("Getting the boundary of ${place.name} …", style = Body, color = LabelSecondary)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (state.shape == null) state.load(place)
    }
}

/** A road set shipped with the app (Kruger), on the download screen: imagery along its roads. `state` is null when
 *  the road list is missing from the build. */
@Composable
fun PresetAreaView(model: AppModel, state: NewAreaState?, back: ScreenChrome.Leading.Back?, close: () -> Unit) {
    if (state != null) {
        NewAreaView(model, state, back = back, close = close)
    } else {
        SheetPlainScreen(ScreenChrome("", back)) {
            Text("The road list is missing from this build of SatMap.", style = Body, color = LabelSecondary)
        }
    }
}

/** "Where I've been": the download screen for imagery along the recorded track, plus the earlier trips already in
 *  the track area (the track may have been cleared since). Worked out once when shown. */
@Composable
fun TrackAreaView(model: AppModel, state: TrackState, back: ScreenChrome.Leading.Back?, close: () -> Unit) {
    val newArea = state.newArea
    when {
        newArea != null -> NewAreaView(model, newArea, back = back, close = close)
        state.worked -> SheetPlainScreen(ScreenChrome("", back)) {
            Text(
                "No track recorded yet. Your dotted track is recorded while SatMap knows where you are.",
                style = Body,
                color = LabelSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
        else -> SheetPlainScreen(ScreenChrome("", back)) { FormSpinner() }
    }
    LaunchedEffect(Unit) {
        if (state.worked) return@LaunchedEffect
        // The track is read on the main thread only: a copy goes to the background.
        val track = ArrayList(model.track)
        val existing = model.offline.areas.value.firstOrNull { it.isTrack == true }
        val previous = existing?.roads ?: emptyList()
        val completeTo = if (existing != null && existing.status == OfflineArea.Status.complete) existing.maxZoom else null
        val result = withContext(Dispatchers.Default) {
            val latest = Roads.trackLines(track)
            val lines = Roads.mergeTrack(previous, latest)
            if (lines.isEmpty()) {
                null
            } else {
                TrackDownload(
                    lines = lines,
                    newLines = if (completeTo == null) lines else Roads.newParts(latest, others = previous),
                    previousLines = previous,
                    maxZoom = existing?.maxZoom,
                    allowCellular = existing?.allowCellular,
                    completeTo = completeTo,
                )
            }
        }
        state.newArea = result?.let { download ->
            val name = model.offline.areas.value.firstOrNull { it.isTrack == true }?.name ?: "Where I've been"
            NewAreaState(PendingArea(name = name, shape = Roads.corridor(download.lines)), track = download)
        }
        state.worked = true
    }
}

/** Pick an imported KML file to download the area around its blocks. */
@Composable
fun KMLAreaPicker(library: KMLLibrary, back: ScreenChrome.Leading.Back?, push: (OfflineRoute) -> Unit) {
    val files by library.files.collectAsStateWithLifecycle()
    SheetScreen(ScreenChrome("Choose KML", back)) {
        item {
            FormSection(first = true) {
                if (files.isEmpty()) {
                    FormRow { Text("Import a KML file first (Blocks button on the map).", style = Body, color = LabelSecondary) }
                }
                for (f in files) {
                    FormLinkRow(onClick = {
                        val shape = f.document.coverageShape(bufferMeters = 150.0)
                        push(OfflineRoute.NewArea(NewAreaState(PendingArea(name = f.name, shape = shape))))
                    }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(f.name, style = Body, color = LabelPrimary)
                            Text(
                                "${f.document.features.size} shapes · includes 150 m around them",
                                style = Caption,
                                color = LabelSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
