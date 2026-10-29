package com.jacobusoettle.satmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.OfflineManager
import java.util.UUID

/** The Offline maps sheet's screens. Each route holds its screen's state, so a screen under another keeps it. */
sealed interface OfflineRoute {
    data object Root : OfflineRoute
    class PlaceSearch(val state: PlaceSearchState = PlaceSearchState()) : OfflineRoute
    class PlaceArea(val place: FoundPlace, val state: PlaceAreaState = PlaceAreaState()) : OfflineRoute
    data object KMLPicker : OfflineRoute
    class NewArea(val area: PendingArea, val state: NewAreaState) : OfflineRoute
    class Preset(val id: String, val state: NewAreaState) : OfflineRoute
    class Track(val state: TrackState = TrackState()) : OfflineRoute
    class AreaDetail(val id: UUID, val state: AreaDetailState = AreaDetailState()) : OfflineRoute
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
}

/** "Where I've been": the download, worked out once. */
class TrackState {
    var download by mutableStateOf<TrackDownload?>(null)
    var worked by mutableStateOf(false)
}

class AreaDetailState {
    var chosenZoom by mutableStateOf<Int?>(null)
    var counts by mutableStateOf<Map<Int, Long>?>(null)
    var savedDepth by mutableStateOf<Int?>(null)
}

/** "Offline maps": where areas come from, the areas, backup, free space and the credits. Owns its back stack. */
@Composable
fun OfflineSheet(model: AppModel, offline: OfflineManager, library: KMLLibrary, close: () -> Unit) {
    // M4: UI spec §3.2 (the root's list and the routes). Until then the root's bar, so the sheet closes with Done.
    SheetScreen(ScreenChrome("Offline maps", ScreenChrome.Leading.Done(close))) {}
}

@Composable
fun AreaRow(
    area: OfflineArea,
    progress: DownloadProgress?,
    canDownloadNow: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    // M4: UI spec §3.2 (AreaRow).
}

/** "Find a place". */
@Composable
fun PlaceSearchView(model: AppModel, state: PlaceSearchState, push: (OfflineRoute) -> Unit) {
    // M4: UI spec §3.3.
}

/** A found place's area, then `NewAreaView`. */
@Composable
fun PlaceAreaView(model: AppModel, place: FoundPlace, state: PlaceAreaState) {
    // M4: UI spec §3.3.
}

/** "Kruger National Park roads". */
@Composable
fun PresetAreaView(model: AppModel, presetID: String, state: NewAreaState) {
    // M4: UI spec §3.5.
}

/** "Where I've been". */
@Composable
fun TrackAreaView(model: AppModel, state: TrackState) {
    // M4: UI spec §3.6.
}

/** "Choose KML". */
@Composable
fun KMLAreaPicker(model: AppModel, library: KMLLibrary, push: (OfflineRoute) -> Unit) {
    // M4: UI spec §3.4.
}
