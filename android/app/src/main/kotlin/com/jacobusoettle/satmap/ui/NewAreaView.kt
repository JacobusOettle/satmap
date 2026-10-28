package com.jacobusoettle.satmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.core.GeoPoint

/** A "Where I've been" download: the lines to save, what is new since the last download, and the existing track
 *  area's settings. */
data class TrackDownload(
    val lines: List<List<GeoPoint>>,
    val newLines: List<List<GeoPoint>>,
    val previousLines: List<List<GeoPoint>>,
    val maxZoom: Int?,
    val allowCellular: Boolean?,
    /** The level the previous download completed to, if it did. */
    val completeTo: Int?,
)

/** "Download area"'s screen state (kept while another screen is pushed over it). */
class NewAreaState(name: String, trackZoom: Int?, allowCellular: Boolean, alongRoads: Boolean) {
    sealed interface RoadsState {
        data object NotLoaded : RoadsState
        data object Loading : RoadsState
        class Loaded(val lines: List<List<GeoPoint>>, val km: Double, val counts: Map<Int, Long>) : RoadsState
        class Failed(val message: String) : RoadsState
    }

    var name by mutableStateOf(name)
    var trackZoom by mutableStateOf(trackZoom)
    var picked by mutableStateOf(false)
    var allowCellular by mutableStateOf(allowCellular)
    var alongRoads by mutableStateOf(alongRoads)
    var roads by mutableStateOf<RoadsState>(RoadsState.NotLoaded)
    var counts by mutableStateOf<Map<Int, Long>?>(null)
    var freeSpace by mutableStateOf<Long?>(null)
}

/** "Download area": name, detail (zoom), roads, mobile data, the size, and Download. */
@Composable
fun NewAreaView(
    model: AppModel,
    area: PendingArea,
    showsCancel: Boolean = false,
    presetID: String? = null,
    track: TrackDownload? = null,
    state: NewAreaState = remember(area.id) {
        NewAreaState(area.name, track?.maxZoom, track?.allowCellular ?: false, presetID != null || track != null)
    },
    close: () -> Unit,
) {
    // M4: UI spec §3.7.
}
