package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.CrosshairAim
import com.jacobusoettle.satmap.app.DroneReport
import com.jacobusoettle.satmap.app.MapLegend
import com.jacobusoettle.satmap.app.MapScale
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapViewRepresentable
import com.jacobusoettle.satmap.offline.OfflineManager

/** The map with everything over it (DESIGN.md §4.1). `map` is null in Robolectric Compose tests (no MapLibre), and the
 *  compass then hides. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(model: AppModel, offline: OfflineManager, recorder: LocationRecorder, map: MapController?) {
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (map != null) MapViewRepresentable(map)
        // M1: Crosshair, the safe-area column (DrawingBar, DownloadBanner, AimPanel | MeasurePanel, my CoordinateRow,
        // DroneCard, Hint), the legends, scale bar and Controls, and the tapped spot (DESIGN.md §4.1).
        val sheet by model.sheet.collectAsStateWithLifecycle()
        SheetHost(sheet, onDismiss = { model.setSheet(null) }) { s ->
            val close = { model.setSheet(null) }
            when (s) {
                ActiveSheet.blocks -> BlocksSheet(model, model.kml, close)
                ActiveSheet.offline -> OfflineSheet(model, offline, model.kml, close)
                ActiveSheet.coordinates -> CoordinateEntryView(model, close)
            }
        }
        val pending by model.pendingArea.collectAsStateWithLifecycle()
        SheetHost(pending, onDismiss = { model.setPendingArea(null); model.setDraftArea(null) }) { area ->
            NewAreaView(model, area, showsCancel = true, close = { model.setPendingArea(null) })
        }
        ClearTrackDialog(model)
        ShareHost()
    }
}

/** The map's buttons, top to bottom: the compass slot, Terrain, Blocks, Offline maps, Go to coordinates, Measure and
 *  My location (50 dp circles, 12 dp apart), with the terrain toast and the long-press menus. */
@Composable
fun Controls(model: AppModel, offline: OfflineManager, map: MapController?) {
    // M1: UI spec §2.4.5.
}

/** MKCompassButton: shown while the map is turned; a tap points north up. */
@Composable
fun CompassButton(map: MapController?) {
    // M1: UI spec §2.4.6.
}

@Composable
fun MapButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    dimmed: Boolean = false,
    heightColours: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // M1: UI spec §2.4.4.
}

/** Centred on the whole window (the map's centre); takes no touches. */
@Composable
fun Crosshair() {
    // M3: UI spec §2.3.11.
}

@Composable
fun Hint(text: String, onClose: (() -> Unit)? = null) {
    // M1: UI spec §2.3.9.
}

@Composable
fun MeasurePanel(model: AppModel, recorder: LocationRecorder) {
    // M3: UI spec §2.3.4.
}

@Composable
fun ScaleBarView(scale: MapScale, onTap: () -> Unit) {
    // M1: UI spec §2.4.2.
}

/** The elevation colours' ramp, blue (low) to red (high), from core `ElevationColours.stops`. */
object ElevationKey {
    val colorStops: Array<Pair<Float, Color>> = ElevationColours.stops
        .map { it.position.toFloat() to Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) }
        .toTypedArray()
}

@Composable
fun ContourKeyView(legend: MapLegend) {
    // M2: UI spec §2.4.1.
}

@Composable
fun ContourSample(bold: Boolean) {
    // M2: UI spec §2.4.1.
}

@Composable
fun ColourKeyView(legend: MapLegend) {
    // M2: UI spec §2.4.3.
}

/** The scale bar's ⊔, `width` dp wide. */
@Composable
fun ScaleBarShape(width: Double) {
    // M1: UI spec §2.4.2.
}

@Composable
fun AimPanel(model: AppModel, aim: CrosshairAim, recorder: LocationRecorder) {
    // M3: UI spec §2.3.3.
}

@Composable
fun StopMeasuringButton(onClick: () -> Unit) {
    // M3: UI spec §2.3.5.
}

/** iOS `ViewThatFits`: the bars side by side when they fit, else stacked. */
@Composable
fun CoordinateRow(content: @Composable () -> Unit) {
    // M1: UI spec §2.3.6.
}

@Composable
fun HeightBar(elevation: Double?, gpsAltitude: Double? = null, unit: HeightUnit, id: String, onTap: () -> Unit) {
    // M1: UI spec §2.3.8.
}

/** A tap on a distance shows it in the next unit (iOS `UnitSwitch`); no ripple, as iOS shows no highlight. */
fun Modifier.unitSwitch(onTap: () -> Unit): Modifier = this // M1: UI spec §2.6.

@Composable
fun DroneCard(report: DroneReport) {
    // M5: UI spec §2.3.10.
}

@Composable
fun CoordinateBar(icon: ImageVector, text: String, id: String, onClose: (() -> Unit)? = null) {
    // M1: UI spec §2.3.7.
}

@Composable
fun DownloadBanner(area: OfflineArea, progress: DownloadProgress?, onPause: () -> Unit) {
    // M4: UI spec §2.3.2.
}

@Composable
fun DrawingBar(model: AppModel) {
    // M4: UI spec §2.3.1.
}

/** "Clear the recorded track?" from the My location long-press menu. */
@Composable
fun ClearTrackDialog(model: AppModel) {
    // M1: UI spec §2.7.
}
