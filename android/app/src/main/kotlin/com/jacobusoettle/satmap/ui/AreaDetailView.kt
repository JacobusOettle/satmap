package com.jacobusoettle.satmap.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.TileCoverage
import com.jacobusoettle.satmap.offline.OfflineManager
import com.jacobusoettle.satmap.offline.roadLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** An area's screen state (kept in its route). Main thread only. */
class AreaDetailState {
    var chosenZoom by mutableStateOf<Int?>(null)

    /** Imagery tile counts per zoom for the area (along its roads if it has them), for the levels it doesn't have. */
    var counts by mutableStateOf<Map<Int, Long>?>(null)
    var savedDepth by mutableStateOf<Int?>(null)

    /** Extra download for the levels after `from` up to `z`: about, where Esri's imagery usually reaches; an upper
     *  limit deeper down. */
    fun extraSize(z: Int, from: Int): String {
        val counts = counts ?: return "…"
        val tiles = ((from + 1)..z).fold(0L) { sum, level -> sum + (counts[level] ?: 0) }
        val bytes = Format.bytes((tiles.toDouble() * NewAreaState.imageryTileBytes).toLong())
        return if (z <= NewAreaState.typicalDepth) "+ ≈ $bytes" else "+ up to $bytes"
    }

    fun buttonTitle(area: OfflineArea): String {
        val z = chosenZoom ?: return "Choose a deeper level"
        if (z < area.maxZoom) return "Stop at zoom $z"
        return "Download zoom ${area.maxZoom + 1}" + if (z > area.maxZoom + 1) "–$z" else ""
    }

    fun footer(area: OfflineArea): String {
        var s = "Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded."
        if (area.status != OfflineArea.Status.complete) {
            s += " While it downloads, choose a lower level to stop there instead."
        }
        val d = savedDepth
        if (area.status == OfflineArea.Status.complete && d != null && d < area.maxZoom) {
            s += " Esri's imagery here stops at zoom $d, so deeper levels will add little or nothing."
        }
        return s
    }
}

/** One offline area: show it on the map, see how deep its imagery goes, and download it to a deeper zoom level (only
 *  the new levels are fetched; what is saved is kept). `back` returns to Offline maps. */
@Composable
fun AreaDetailView(
    model: AppModel,
    offline: OfflineManager,
    areaID: UUID,
    state: AreaDetailState,
    back: ScreenChrome.Leading.Back?,
) {
    val areas by offline.areas.collectAsStateWithLifecycle()
    val area = areas.firstOrNull { it.id == areaID }
    if (area == null) {
        SheetPlainScreen(ScreenChrome("", back)) {
            Text("This area has been deleted.", style = Body, color = LabelSecondary)
        }
        return
    }
    val askNotifications = rememberNotificationQuestion()
    val complete = area.status == OfflineArea.Status.complete
    SheetScreen(ScreenChrome(area.name, back)) {
        item {
            FormSection(first = true) {
                FormActionRow("Show on the map", Icons.Outlined.Map) {
                    area.shape.bounds?.let { model.map?.zoom(to = it) }
                    model.setSheet(null)
                }
                FormValueRow("Saved", Format.bytes(area.bytesOnDisk))
                FormValueRow(
                    if (complete) "Downloaded to" else "Downloading to",
                    "zoom ${area.maxZoom}" + if (area.alongRoads) ", along roads" else "",
                )
                state.savedDepth?.let { FormValueRow("Imagery found down to", "zoom $it") }
            }
        }
        item {
            FormSection(
                footer = if (complete) {
                    "Used when you add a deeper level."
                } else {
                    "Without mobile data the download waits for Wi-Fi. Switch it on to carry on over mobile data; what " +
                        "is saved is kept."
                },
            ) {
                FormToggleRow("Allow mobile data", area.allowCellular) { offline.setAllowCellular(areaID, it) }
            }
        }
        item {
            FormSection(header = "Detail", footer = state.footer(area)) {
                val latitude = area.shape.bounds?.center?.lat ?: 0.0
                for (z in NewAreaState.zoomChoices) {
                    val chosen = z == state.chosenZoom
                    // Deeper levels can be added; while a download runs, a lower one stops it there.
                    val enabled = !(z == area.maxZoom || (z < area.maxZoom && complete))
                    FormChoiceRow(chosen, enabled, onClick = { state.chosenZoom = z }) {
                        ZoomRow(
                            "Zoom $z",
                            if (z <= area.maxZoom) LabelSecondary else LabelPrimary,
                            Format.groundResolution(z, latitude),
                            if (z <= area.maxZoom) {
                                if (complete) "Downloaded" else "In this download"
                            } else {
                                state.extraSize(z, from = area.maxZoom)
                            },
                            checkShown = chosen || (z <= area.maxZoom && complete),
                            checkColour = if (chosen) Accent else LabelSecondary,
                        )
                    }
                }
            }
        }
        item {
            FormSection {
                FormActionRow(
                    state.buttonTitle(area),
                    SatIcons.ArrowDownCircleFill,
                    enabled = state.chosenZoom != null,
                    iconSize = 26.5.dp, // the disc 22 dp, as arrow.down.circle.fill's (screenshot 18)
                    style = Headline,
                ) {
                    askNotifications()
                    state.chosenZoom?.let { offline.changeMaxZoom(areaID, it) }
                    state.chosenZoom = null
                }
            }
        }
    }
    LaunchedEffect(area.maxZoom, area.status) {
        // A lower level to stop at only makes sense while the download runs.
        val z = state.chosenZoom
        if (area.status == OfflineArea.Status.complete && z != null && z < area.maxZoom) state.chosenZoom = null
        state.savedDepth = offline.savedImageryDepth(areaID)
        if (area.maxZoom >= TileSources.imageryMaxZoom) return@LaunchedEffect
        val shape = area.roadLines?.let(Roads::corridor) ?: area.shape
        val zooms = (area.maxZoom + 1)..TileSources.imageryMaxZoom
        state.counts = withContext(Dispatchers.Default) { TileCoverage.tileCounts(shape, zooms) }
    }
}
