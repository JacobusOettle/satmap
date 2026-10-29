package com.jacobusoettle.satmap.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.CrosshairAim
import com.jacobusoettle.satmap.app.DroneReport
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.MapLegend
import com.jacobusoettle.satmap.app.MapScale
import com.jacobusoettle.satmap.app.MeasureMode
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.ContourInterval
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapViewRepresentable
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.shadow.Shadow as DropShadow

/** The map with everything over it (DESIGN.md §4.1). `map` is null in Robolectric Compose tests (no MapLibre), and the
 *  compass then hides. Each piece collects only what it shows, so a pan or a zoom recomposes only what changes. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(model: AppModel, offline: OfflineManager, recorder: LocationRecorder, map: MapController?) {
    var confirmClearTrack by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (map != null) MapViewRepresentable(map)
        MeasureCrosshair(model, Modifier.align(Alignment.Center))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            MapOverlay(model, offline, recorder, map, onClearTrack = { confirmClearTrack = true })
        }
        val sheet by model.sheet.collectAsStateWithLifecycle()
        SheetHost(sheet, onDismiss = { model.setSheet(null) }) { s ->
            when (s) {
                ActiveSheet.blocks -> BlocksSheet(model, model.kml, close)
                ActiveSheet.offline -> OfflineSheet(model, offline, model.kml, close)
                ActiveSheet.coordinates -> CoordinateEntryView(model, close, sheetState)
            }
        }
        val pending by model.pendingArea.collectAsStateWithLifecycle()
        // Swift's onDismiss: the drawn loop goes with the sheet.
        SheetHost(pending, onDismiss = { model.setPendingArea(null); model.setDraftArea(null) }) { area ->
            NewAreaView(model, remember(area.id) { NewAreaState(area) }, showsCancel = true, close = close)
        }
        if (confirmClearTrack) ClearTrackDialog(onClear = model::clearTrack, onDismiss = { confirmClearTrack = false })
        ShareHost()
        KeepScreenOn(offline)
    }
}

/** Two side buttons and the gap between them. */
private val controlsLift = (2 * (50 + 12)).dp

/** Everything over the map within the safe area (the status and navigation bars and the cutout; never the
 *  keyboard): the panels from the top, the legends and the buttons at the bottom, and a tapped spot under them. */
@Composable
private fun MapOverlay(
    model: AppModel,
    offline: OfflineManager,
    recorder: LocationRecorder,
    map: MapController?,
    onClearTrack: () -> Unit,
) {
    val drawing by model.isDrawingArea.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (drawing) DrawingBar(model)
            ActiveDownload(model, offline)
            Measuring(model, recorder)
            // Your own coordinates and height at the top; a tapped spot's at the bottom, within reach to copy.
            if (!drawing) MyCoordinates(model, recorder)
            // Drone mode: where you (and the tapped spot) are from the nearest airfield, as a pilot says it.
            if (!drawing) Drone(model)
            LocationHint(recorder)
            Spacer(Modifier.weight(1f))
            // Map credits are in the Offline maps sheet.
            if (!drawing) {
                // Two buttons higher than at the very bottom (My location where Offline maps was), leaving the
                // bottom of the screen to a tapped spot's coordinates.
                Row(Modifier.fillMaxWidth().padding(bottom = controlsLift), verticalAlignment = Alignment.Bottom) {
                    // The contour interval above the scale bar, the elevation colours' heights under it.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
                        ContourKeyView(model.legend)
                        ScaleBarView(model.scale, onTap = model::nextDistanceUnit)
                        ColourKeyView(model.legend)
                    }
                    Spacer(Modifier.weight(1f))
                    Controls(model, offline, map, onClearTrack)
                }
            }
        }
        if (!drawing) {
            TappedSpot(model, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }
}

/** While a point can be placed with the measure button. */
@Composable
private fun MeasureCrosshair(model: AppModel, modifier: Modifier) {
    val measure by model.measure.collectAsStateWithLifecycle()
    val shows = when (measure) {
        MeasureMode.Aiming, is MeasureMode.Placed, is MeasureMode.Path -> true
        MeasureMode.Off, is MeasureMode.Closed -> false
    }
    if (shows) Crosshair(modifier)
}

@Composable
private fun ActiveDownload(model: AppModel, offline: OfflineManager) {
    val id by offline.activeID.collectAsStateWithLifecycle()
    val areas by offline.areas.collectAsStateWithLifecycle()
    val progress by offline.progress.collectAsStateWithLifecycle()
    val area = id?.let { active -> areas.firstOrNull { it.id == active } } ?: return
    DownloadBanner(
        area,
        progress,
        onPause = { offline.pause(area.id) },
        modifier = Modifier.clickable(interactionSource = null, indication = null) {
            model.setSheet(ActiveSheet.offline)
        },
    )
}

@Composable
private fun Measuring(model: AppModel, recorder: LocationRecorder) {
    val measure by model.measure.collectAsStateWithLifecycle()
    when (measure) {
        MeasureMode.Aiming -> AimPanel(model, model.aim, recorder)
        is MeasureMode.Placed, is MeasureMode.Path, is MeasureMode.Closed -> MeasurePanel(model, recorder)
        MeasureMode.Off -> {}
    }
}

@Composable
private fun MyCoordinates(model: AppModel, recorder: LocationRecorder) {
    val fix by recorder.location.collectAsStateWithLifecycle()
    val power by recorder.power.collectAsStateWithLifecycle()
    val elevation by model.userElevation.collectAsStateWithLifecycle()
    val unit by model.heightUnit.collectAsStateWithLifecycle()
    val loc = fix ?: return
    CoordinateRow(Modifier.fillMaxWidth()) {
        CoordinateBar(
            icon = if (power == LocationRecorder.Power.sleeping) Icons.Outlined.NearMe else Icons.Filled.NearMe,
            text = Format.coordinate(loc.point),
            id = "myCoordinates",
            iconSlot = 18.7.dp,
        )
        HeightBar(elevation, gpsAltitude(loc), unit, id = "myHeight", onTap = model::nextHeightUnit)
    }
}

/** The GPS's own height for you, if it is good to 25 m: shown while the elevation data has none. (`GpsFix.altitude`
 *  is already above sea level.) */
fun gpsAltitude(fix: GpsFix): Double? {
    val accuracy = fix.verticalAccuracy ?: return null
    return if (accuracy > 0 && accuracy <= 25) fix.altitude else null
}

@Composable
private fun Drone(model: AppModel) {
    val mode by model.terrainMode.collectAsStateWithLifecycle()
    val report by model.droneReport.collectAsStateWithLifecycle()
    if (mode.showsAviation && (report.you != null || report.pin != null)) DroneCard(report)
}

/** Why your position is missing or not recorded. A tap opens the Settings page that changes it (Android only: iOS
 *  can't open its Location Services page). */
@Composable
private fun LocationHint(recorder: LocationRecorder) {
    val authorization by recorder.authorization.collectAsStateWithLifecycle()
    val reducedAccuracy by recorder.reducedAccuracy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when {
        authorization == LocationRecorder.Authorization.denied ->
            Hint(
                "Location is off for SatMap. Turn it on in Settings › Apps › SatMap › Permissions › Location.",
                onTap = { openSettings(context, appDetails(context)) },
            )
        authorization == LocationRecorder.Authorization.servicesOff ->
            Hint(
                "Location is off. Turn it on in Settings › Location.",
                onTap = { openSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        reducedAccuracy ->
            Hint(
                "Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › SatMap › " +
                    "Permissions › Location (Use precise location).",
                onTap = { openSettings(context, appDetails(context)) },
            )
    }
}

/** SatMap's page in Settings › Apps (its permissions are one tap further). */
private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some phones have no such page: the hint still says where to go.
    }
}

/** A tapped spot's coordinates and height, at the bottom within reach to copy; a KML point's name over them. */
@Composable
private fun TappedSpot(model: AppModel, modifier: Modifier) {
    val selected by model.selectedPoint.collectAsStateWithLifecycle()
    val name by model.selectedName.collectAsStateWithLifecycle()
    val elevation by model.selectedElevation.collectAsStateWithLifecycle()
    val unit by model.heightUnit.collectAsStateWithLifecycle()
    val p = selected ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A KML point's name over its coordinates.
        name?.let {
            Text(
                it,
                style = Subheadline.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("tappedName").blocksMap().pill(vertical = 5.dp),
            )
        }
        CoordinateRow {
            CoordinateBar(
                icon = if (name == null) SatIcons.MapPin else SatIcons.MapPinCircleFill,
                text = Format.coordinate(p),
                id = "tappedCoordinates",
                iconSlot = if (name == null) 19.7.dp else 19.1.dp,
                onClose = { model.setSelectedPoint(null) },
            )
            HeightBar(elevation, unit = unit, id = "tappedHeight", onTap = model::nextHeightUnit)
        }
    }
}

/** Keeps the screen on while an area downloads (iOS `isIdleTimerDisabled`). */
@Composable
private fun KeepScreenOn(offline: OfflineManager) {
    val id by offline.activeID.collectAsStateWithLifecycle()
    val view = LocalView.current
    val on = id != null
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** The map's buttons, top to bottom: the compass slot, Terrain, Blocks, Offline maps, Go to coordinates, Measure and
 *  My location (50 dp circles, 12 dp apart), with the terrain toast and the long-press menus. `onClearTrack`: My
 *  location's menu chose "Clear recorded track". */
@Composable
fun Controls(model: AppModel, offline: OfflineManager, map: MapController?, onClearTrack: () -> Unit) {
    Column(
        Modifier.width(50.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (map != null) CompassButton(map)
        TerrainButton(model)
        BlocksButton(model)
        OfflineButton(model, offline)
        MapButton(
            SatIcons.LocationMagnifyingGlass,
            "Go to coordinates",
            onClick = { model.setSheet(ActiveSheet.coordinates) },
            modifier = Modifier.testTag("goToButton"),
        )
        MeasureButton(model)
        MyLocationButton(model, onClearTrack)
    }
}

/** Contour lines, contours with elevation colours, colours alone (in the colours: `heightColours`), drone mode, or
 *  none; a tap moves on to the next and says it beside the button for a moment. */
@Composable
private fun TerrainButton(model: AppModel) {
    val mode by model.terrainMode.collectAsStateWithLifecycle()
    var toast by remember { mutableStateOf<String?>(null) }
    // Each tap starts the toast's time again (iOS cancels the earlier work item).
    var toastKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(toastKey) {
        if (toastKey == 0) return@LaunchedEffect
        delay(1800)
        toast = null
    }
    Box {
        val icon = terrainIcon(mode)
        MapButton(
            icon,
            "Contour lines",
            dimmed = mode == TerrainMode.off,
            heightColours = mode.showsColours,
            glyph = when (icon) {
                Icons.Filled.Landscape, Icons.Outlined.Landscape -> 6f / 24..18f / 24
                Icons.Filled.Palette -> 2f / 24..22f / 24
                else -> 0f..1f
            },
            iconModifier = if (mode == TerrainMode.drone) Modifier.rotate(90f) else Modifier,
            onClick = {
                model.setTerrainMode(model.terrainMode.value.next)
                toast = model.terrainMode.value.title
                toastKey++
            },
            modifier = Modifier.testTag("terrainButton").semantics { stateDescription = mode.title },
        )
        TerrainToast(toast, Modifier.align(Alignment.CenterStart))
    }
}

private fun terrainIcon(mode: TerrainMode): ImageVector = when (mode) {
    TerrainMode.contours, TerrainMode.contoursAndColours -> Icons.Filled.Landscape
    TerrainMode.colours -> Icons.Filled.Palette
    // SF's airplane points right; Material's Flight points up.
    TerrainMode.drone -> Icons.Filled.Flight
    TerrainMode.off -> Icons.Outlined.Landscape
}

/** What the terrain button now shows, said for a moment beside it after a tap: laid out at no size, so it never widens
 *  the column, with its right edge 10 dp left of the button. It takes no touches and isn't read out. */
@Composable
private fun TerrainToast(text: String?, modifier: Modifier) {
    // The last text, kept while it fades.
    val last = remember { arrayOf("") }
    if (text != null) last[0] = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier.layout { measurable, _ ->
            val p = measurable.measure(Constraints(maxWidth = 230.dp.roundToPx()))
            layout(0, 0) { p.place(-p.width - 10.dp.roundToPx(), -p.height / 2) }
        },
        enter = EnterTransition.None,
        exit = fadeOut(tween(350)),
    ) {
        Text(
            last[0],
            style = Footnote.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier
                // Not read out (only a tag, for the tests).
                .clearAndSetSemantics { testTag = "terrainToast" }
                .panel(0.75f)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** A long press on Blocks: each file's blocks on or off the map, and their colours, without opening the list. */
@Composable
private fun BlocksButton(model: AppModel) {
    val files by model.kml.files.collectAsStateWithLifecycle()
    val useFileColours by model.kml.useFileColours.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Box {
        MapButton(
            SatIcons.SquareDashed,
            "Blocks",
            onClick = { model.setSheet(ActiveSheet.blocks) },
            // iOS shows no menu for an empty list.
            onLongClick = if (files.isEmpty()) null else ({ menu = true }),
            onLongClickLabel = "Show or hide files",
            modifier = Modifier.testTag("blocksButton"),
        )
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = CellBackground) {
            val colours = MenuDefaults.itemColors(textColor = Color.White, leadingIconColor = Color.White)
            for (f in files) {
                DropdownMenuItem(
                    text = { Text(f.name, style = Body) },
                    leadingIcon = {
                        Icon(if (f.isShown) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null)
                    },
                    onClick = {
                        menu = false
                        model.kml.setShown(f.id, !f.isShown)
                    },
                    colors = colours,
                )
            }
            if (files.isNotEmpty()) {
                HorizontalDivider(color = Separator)
                DropdownMenuItem(
                    text = { Text("Colours from the files", style = Body) },
                    leadingIcon = {
                        Icon(
                            if (useFileColours) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menu = false
                        model.kml.setUseFileColours(!useFileColours)
                    },
                    colors = colours,
                )
            }
        }
    }
}

@Composable
private fun OfflineButton(model: AppModel, offline: OfflineManager) {
    val activeID by offline.activeID.collectAsStateWithLifecycle()
    MapButton(
        // While downloading, arrow.down.circle.fill: the arrow cut out of a black disc, 20 dp across as on iOS
        // (screenshot 08). Material's Filled.ArrowCircleDown is the outlined ring.
        if (activeID == null) Icons.Outlined.ArrowCircleDown else SatIcons.ArrowDownCircleFill,
        "Offline maps",
        active = activeID != null,
        onClick = { model.setSheet(ActiveSheet.offline) },
        modifier = Modifier.testTag("offlineButton"),
    )
}

@Composable
private fun MeasureButton(model: AppModel) {
    val measure by model.measure.collectAsStateWithLifecycle()
    val icon = when (measure) {
        MeasureMode.Off -> Icons.Outlined.Straighten
        MeasureMode.Aiming, is MeasureMode.Placed, is MeasureMode.Path -> Icons.Filled.GpsFixed
        is MeasureMode.Closed -> Icons.Filled.Close
    }
    MapButton(
        icon,
        "Measure",
        active = measure != MeasureMode.Off,
        onClick = model::measureButtonTapped,
        modifier = Modifier.testTag("measureButton"),
    )
}

/** A tap follows you (and a second one zooms in); a long press offers to clear the recorded track. */
@Composable
private fun MyLocationButton(model: AppModel, onClearTrack: () -> Unit) {
    val following by model.following.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Box {
        MapButton(
            if (following) Icons.Filled.NearMe else Icons.Outlined.NearMe,
            "My location",
            onClick = model::centerOnUser,
            onLongClick = { menu = true },
            onLongClickLabel = "Clear recorded track",
            modifier = Modifier.testTag("myLocationButton"),
        )
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = CellBackground) {
            DropdownMenuItem(
                text = { Text("Clear recorded track", style = Body) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onClearTrack()
                },
                colors = MenuDefaults.itemColors(textColor = SystemRed, leadingIconColor = SystemRed),
            )
        }
    }
}

/** MKCompassButton: shown while the map is turned; a tap points north up. The 44 dp slot stays while it is hidden. */
@Composable
fun CompassButton(map: MapController) {
    val bearing by map.bearing.collectAsStateWithLifecycle()
    CompassButton(bearing, onTap = map::resetNorth)
}

/** `bearing`: the map's heading, degrees clockwise from north. */
@Composable
fun CompassButton(bearing: Double, onTap: () -> Unit) {
    val b = ((bearing % 360) + 360) % 360
    val turned = minOf(b, 360 - b) > 0.5
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(turned, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Canvas(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable(onClickLabel = "Point north up", role = Role.Button, onClick = onTap)
                    .semantics { contentDescription = "Compass" }
                    .testTag("compassButton"),
            ) {
                val c = center
                val half = 3.dp.toPx()
                val needle = 10.dp.toPx()
                rotate(-bearing.toFloat(), c) {
                    drawPath(
                        Path().apply {
                            moveTo(c.x, c.y - needle); lineTo(c.x + half, c.y); lineTo(c.x - half, c.y); close()
                        },
                        SystemRed,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(c.x, c.y + needle); lineTo(c.x + half, c.y); lineTo(c.x - half, c.y); close()
                        },
                        Color.White.copy(alpha = 0.85f),
                    )
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = 10.sp.toPx()
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        // Centred 14.5 dp above the middle, clear of the needle's tip.
                        val y = c.y - 14.5.dp.toPx() - (paint.ascent() + paint.descent()) / 2
                        canvas.nativeCanvas.drawText("N", c.x, y, paint)
                    }
                }
            }
        }
    }
}

/** A round button over the map. `glyph`: where the icon's drawing is, top to bottom (fractions of the icon), which
 *  `heightColours` spans, as SwiftUI spans a gradient over the symbol itself. */
@Composable
fun MapButton(
    icon: ImageVector,
    /** Spoken by TalkBack. */
    label: String,
    active: Boolean = false,
    /** A switch that is off: the icon is faded. */
    dimmed: Boolean = false,
    /** The icon in the elevation colours, low (blue) at the bottom to high (red) at the top. */
    heightColours: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    glyph: ClosedFloatingPointRange<Float> = 0f..1f,
    iconModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        active -> Color.Black
        heightColours -> Color.White // replaced by the colours
        else -> Color.White.copy(alpha = if (dimmed) 0.45f else 1f)
    }
    Box(
        modifier
            .size(50.dp)
            // SwiftUI's .shadow(color: .black.opacity(0.35), radius: 3): all round, not offset as Material's is.
            .dropShadow(CircleShape, DropShadow(radius = 5.dp, color = Color.Black.copy(alpha = 0.35f)))
            .clip(CircleShape)
            .background(if (active) SystemYellow else Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .combinedClickable(
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = iconModifier
                .size(mapButtonIconSize(icon))
                .then(if (heightColours && !active) Modifier.heightColours(glyph) else Modifier),
        )
    }
}

/** How big a map button draws `icon`: 24 dp for SF's 20 pt semibold (UI spec §1.3), more where the Material glyph
 *  fills less of its box than the SF symbol does of its frame, to draw it as big as iOS (screenshots 01, 02, 19):
 *  mountain.2 is 35 × 17.5 pt (Landscape fills 22 × 12 of its 24), scope 23.5 (GpsFixed 22), xmark 15.5 (Close 14) and
 *  location 18.8 (NearMe 18). */
private fun mapButtonIconSize(icon: ImageVector): Dp = when (icon) {
    Icons.Filled.Landscape, Icons.Outlined.Landscape -> 38.dp
    Icons.Filled.Close -> 27.dp
    Icons.Filled.GpsFixed, Icons.Filled.NearMe, Icons.Outlined.NearMe -> 26.dp
    else -> 24.dp
}

/** Paints what is drawn in the elevation colours, blue at `glyph`'s bottom to red at its top. */
private fun Modifier.heightColours(glyph: ClosedFloatingPointRange<Float>): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                *ElevationKey.colorStops.map { (f, c) -> (1 - f) to c }.reversed().toTypedArray(),
                startY = glyph.start * size.height,
                endY = glyph.endInclusive * size.height,
            ),
            blendMode = BlendMode.SrcIn,
        )
    }

/** Centred on the whole window (the map's centre); takes no touches. */
@Composable
fun Crosshair(modifier: Modifier = Modifier) {
    Spacer(modifier.size(56.dp).clearAndSetSemantics {}.drawShadowed { n, size -> drawCrosshair(n, size) })
}

/** The crosshair's ring, arms and red dot on a `size` canvas (px), without their shadow. */
internal fun Density.drawCrosshair(n: android.graphics.Canvas, size: Size) {
    val c = size.center
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 2.dp.toPx()
    n.drawCircle(c.x, c.y, 18.dp.toPx(), paint)
    paint.style = android.graphics.Paint.Style.FILL
    val w = 1.dp.toPx()
    n.drawRect(c.x - w, 0f, c.x + w, size.height, paint)
    n.drawRect(0f, c.y - w, size.width, c.y + w, paint)
    paint.color = SystemRed.toArgb()
    n.drawCircle(c.x, c.y, 3.dp.toPx(), paint)
}

/** A short iOS-style hint over the map. `onClose` shows a ✕ ("Stop measuring") after the text; `onTap` (Android
 *  only) makes the hint a button. */
@Composable
fun Hint(text: String, onClose: (() -> Unit)? = null, onTap: (() -> Unit)? = null) {
    Row(
        Modifier
            .blocksMap()
            .then(if (onTap != null) Modifier.pressable(onClick = onTap) else Modifier)
            .panel(0.65f, cornerRadius = 10.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, style = Footnote.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f, fill = false))
        if (onClose != null) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Stop measuring",
                modifier = Modifier.size(16.dp).pressable(onClick = onClose),
            )
        }
    }
}

/** Live distance and height from you to the marker; or the length of the line through the points, the height from
 *  A to the last point, and once closed, the shape's area and perimeter. */
@Composable
fun MeasurePanel(model: AppModel, recorder: LocationRecorder) {
    val measure by model.measure.collectAsStateWithLifecycle()
    val location by recorder.location.collectAsStateWithLifecycle()
    val markerElevation by model.markerElevation.collectAsStateWithLifecycle()
    val lastElevation by model.lastElevation.collectAsStateWithLifecycle()
    val userElevation by model.userElevation.collectAsStateWithLifecycle()
    val distanceUnit by model.distanceUnit.collectAsStateWithLifecycle()
    val heightUnit by model.heightUnit.collectAsStateWithLifecycle()
    // The model's derived values, read again whenever the points or your position change (the Swift view reads them
    // on every change of the model or the recorder).
    val markerDistance = remember(measure, location) { model.markerDistance }
    val measuredArea = remember(measure) { model.measuredArea }
    MeasurePanel(
        measure, markerDistance, measuredArea, markerElevation, lastElevation, userElevation, distanceUnit, heightUnit,
        online = connectivityOnline(),
        onNextDistanceUnit = model::nextDistanceUnit,
        onUndo = model::undoMeasurePoint,
        onCloseShape = model::closeMeasureShape,
        onStop = model::clearMeasure,
    )
}

/** The panel for what the model says: `markerDistance` and `measuredArea` as `AppModel`'s. */
@Composable
fun MeasurePanel(
    measure: MeasureMode,
    markerDistance: Double?,
    measuredArea: Double?,
    markerElevation: Double?,
    lastElevation: Double?,
    userElevation: Double?,
    distanceUnit: DistanceUnit,
    heightUnit: HeightUnit,
    online: Boolean,
    onNextDistanceUnit: () -> Unit,
    onUndo: () -> Unit,
    onCloseShape: () -> Unit,
    onStop: () -> Unit,
) {
    MeasuringPanel(onStop) {
        val points = measure.points
        when (measure) {
            is MeasureMode.Closed -> {
                Text(
                    "Shape ${MeasureMode.label(0)}–${MeasureMode.label(points.size - 1)} · ${points.size} corners",
                    style = panelTitle,
                    modifier = Modifier.alpha(0.85f),
                )
                Text(measuredArea?.let(Format::hectares) ?: "–", style = bigNumber)
                Text(
                    "Perimeter ${markerDistance?.let { Format.distance(it, distanceUnit) } ?: "–"}",
                    style = Headline,
                    modifier = Modifier.unitSwitch(onNextDistanceUnit),
                )
                if (measuredArea != null && measuredArea < 1_000_000) {
                    Text("${Format.count(measuredArea.rounded().toInt())} m²", style = Caption, modifier = Modifier.alpha(0.85f))
                }
                MeasureActions(close = false, onUndo, onCloseShape)
            }
            is MeasureMode.Path -> {
                val last = MeasureMode.label(points.size - 1)
                Text(
                    if (points.size == 2) "From A to B" else "From A to $last · ${points.size} points",
                    style = panelTitle,
                    modifier = Modifier.alpha(0.85f),
                )
                Text(
                    markerDistance?.let { Format.distance(it, distanceUnit) } ?: "–",
                    style = bigNumber,
                    modifier = Modifier.unitSwitch(onNextDistanceUnit).testTag("measuredDistance"),
                )
                Text(measureHeightText(measure, markerElevation, lastElevation, userElevation, heightUnit, online), style = Headline)
                if (points.size >= 3) {
                    val leg = Geo.distance(points[points.size - 2], points[points.size - 1])
                    Text(
                        "Last leg ${MeasureMode.label(points.size - 2)} → $last: ${Format.distance(leg, distanceUnit)}",
                        style = Caption,
                        modifier = Modifier.alpha(0.85f),
                    )
                }
                if (markerElevation != null && lastElevation != null) {
                    Text(
                        "A ${Format.height(markerElevation, heightUnit)} · $last ${Format.height(lastElevation, heightUnit)} above sea level",
                        style = Caption,
                        modifier = Modifier.alpha(0.85f),
                    )
                }
                MeasureActions(close = points.size >= 3, onUndo, onCloseShape)
                Text(
                    if (points.size >= 3) {
                        "Move the crosshair and tap the map for more points, or tap A to close the shape and see its area."
                    } else {
                        "Move the crosshair and tap the map for more points."
                    },
                    style = Caption2,
                    modifier = Modifier.alpha(0.7f),
                )
            }
            else -> {
                Text(
                    markerDistance?.let { Format.distance(it, distanceUnit) } ?: "Waiting for GPS…",
                    style = bigNumber,
                    modifier = Modifier.unitSwitch(onNextDistanceUnit),
                )
                Text(measureHeightText(measure, markerElevation, lastElevation, userElevation, heightUnit, online), style = Headline)
                if (markerElevation != null && userElevation != null) {
                    Text(
                        "Marker ${Format.height(markerElevation, heightUnit)} · You ${Format.height(userElevation, heightUnit)} above sea level",
                        style = Caption,
                        modifier = Modifier.alpha(0.85f),
                    )
                }
                Text(
                    "Move the crosshair to the next spot and tap the map for point B.",
                    style = Caption2,
                    modifier = Modifier.alpha(0.7f),
                )
            }
        }
    }
}

/** Marker vs you, or the last point vs A. */
private fun measureHeightText(
    measure: MeasureMode,
    markerElevation: Double?,
    lastElevation: Double?,
    userElevation: Double?,
    unit: HeightUnit,
    online: Boolean,
): String {
    val isPath = measure is MeasureMode.Path
    val m = markerElevation
    val u = if (isPath) lastElevation else userElevation
    if (m == null || u == null) return missingHeightText(online)
    if (isPath) {
        val last = MeasureMode.label(measure.points.size - 1)
        val diff = u - m
        if (unit.whole(abs(diff)) == 0) return "$last is level with A"
        val d = Format.heightDifference(diff, unit)
        return if (diff > 0) "▲ $last is $d higher than A" else "▼ $last is $d lower than A"
    }
    val diff = m - u
    if (unit.whole(abs(diff)) == 0) return "Marker is level with you"
    val d = Format.heightDifference(diff, unit)
    return if (diff > 0) "▲ Marker is $d higher than you" else "▼ Marker is $d lower than you"
}

/** A height that isn't known (yet): being looked up, or not to be had offline. */
private fun missingHeightText(online: Boolean): String =
    if (online) "Height: loading elevation…" else "Height: no elevation data here offline (download this area)"

/** The panels' small title: caption, semibold. */
private val panelTitle = Caption.copy(fontWeight = FontWeight.SemiBold)

/** The distance or area: 30 bold, tabular digits. */
private val bigNumber = BigNumber.copy(fontFeatureSettings = TabularDigits)

/** The measuring panels: black 70 %, 12 dp corners, the full width, their lines 4 dp apart, with the ✕ (stop
 *  measuring) over their top right corner. */
@Composable
private fun MeasuringPanel(onStop: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxWidth().blocksMap().panel(0.7f)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start,
                content = content,
            )
            StopMeasuringButton(onStop, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Undo the last point; close the shape (three or more points). */
@Composable
private fun MeasureActions(close: Boolean, onUndo: () -> Unit, onCloseShape: () -> Unit) {
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelButton("Undo", Icons.AutoMirrored.Filled.Undo, onUndo)
        if (close) PanelButton("Close shape", Icons.Outlined.Pentagon, onCloseShape)
    }
}

/** SwiftUI's `.bordered` button tinted white: its label (subheadline, semibold) on a light rounded box, 32 dp tall as
 *  in screenshot 16; it dims while pressed. */
@Composable
private fun PanelButton(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.25f))
            .pressable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(title, style = Subheadline.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** The map's scale, bottom left: a round distance and its length on the map, changing as the map zooms. A tap on
 *  the distance: distances (and the bar) in the next unit. */
@Composable
fun ScaleBarView(scale: MapScale, onTap: () -> Unit) {
    val label by scale.label.collectAsStateWithLifecycle()
    val width by scale.width.collectAsStateWithLifecycle()
    ScaleBarView(label, width, onTap)
}

@Composable
fun ScaleBarView(label: String, width: Double, onTap: () -> Unit) {
    if (!(width > 0)) return
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Only the distance takes a tap (for the next unit); the bar leaves the map under it to the map.
        Text(
            label,
            style = Caption.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = TabularDigits,
                shadow = mapTextShadow,
            ),
            modifier = Modifier
                .unitSwitch(onTap)
                .clearAndSetSemantics {
                    contentDescription = "Scale"
                    stateDescription = label
                }
                .padding(top = 3.dp, bottom = 3.dp, end = 6.dp),
        )
        ScaleBarShape(width)
    }
}

/** The elevation colours' ramp, blue (low) to red (high), from core `ElevationColours.stops`. */
object ElevationKey {
    val colorStops: Array<Pair<Float, Color>> = ElevationColours.stops
        .map { it.position.toFloat() to Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) }
        .toTypedArray()
}

/** The contour lines' interval at this zoom, above the scale bar: a thin line every so many metres, a bold one (with
 *  its height on it) every so many more. Shown while contour lines are drawn. */
@Composable
fun ContourKeyView(legend: MapLegend) {
    val key by legend.key.collectAsStateWithLifecycle()
    ContourKeyView(key.contours)
}

@Composable
fun ContourKeyView(c: ContourInterval?) {
    c ?: return
    // In metres, as the lines are drawn (the Swift passes no unit).
    val step = Format.height(c.step)
    val index = Format.height(c.index)
    // Takes no touches: the map under it gets them.
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Contour lines"
            stateDescription = "every $step, bold every $index"
            testTag = "contourKey"
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "Contours",
            style = Caption2.copy(fontWeight = FontWeight.SemiBold, shadow = mapTextShadow),
            color = Color.White,
            modifier = Modifier.alpha(0.9f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = keyText
            ContourSample(bold = false)
            Text(step, style = style, color = Color.White)
            ContourSample(bold = true, Modifier.padding(start = 4.dp))
            Text(index, style = style, color = Color.White)
        }
    }
}

/** A short piece of contour line in the map's colours (`ContourRenderer`), with its dark edge. */
@Composable
fun ContourSample(bold: Boolean, modifier: Modifier = Modifier) {
    Spacer(modifier.size(20.dp, if (bold) 4.5.dp else 3.dp).drawShadowed { n, size -> drawContourSample(n, size, bold) })
}

/** The sample's two capsules, centred on a `size` canvas (px): the dark edge 20 dp long, the line 18 dp. */
internal fun Density.drawContourSample(n: android.graphics.Canvas, size: Size, bold: Boolean) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    fun capsule(width: Float, height: Float, colour: Color) {
        paint.color = colour.toArgb()
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        n.drawRoundRect(left, top, left + width, top + height, height / 2, height / 2, paint)
    }
    capsule(size.width, size.height, Color.Black.copy(alpha = 0.4f))
    capsule(18.dp.toPx(), (if (bold) 3.dp else 1.6.dp).toPx(), if (bold) ContourBold else ContourThin.copy(alpha = 0.9f))
}

/** The legends' heights: caption, semibold, tabular digits, with the map's text shadow. */
private val keyText: TextStyle
    @Composable @ReadOnlyComposable
    get() = Caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TabularDigits, shadow = mapTextShadow)

/** The heights the elevation colours run between, under the scale bar: blue the lowest, red the highest. Shown while
 *  the colours are drawn. */
@Composable
fun ColourKeyView(legend: MapLegend) {
    val key by legend.key.collectAsStateWithLifecycle()
    ColourKeyView(key.colours)
}

@Composable
fun ColourKeyView(r: ElevationRange?) {
    r ?: return
    // In metres, as the Swift (it passes no unit).
    val low = Format.height(r.low)
    val high = Format.height(r.high)
    // Takes no touches: the map under it gets them.
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Elevation colours"
            stateDescription = "blue $low to red $high"
            testTag = "colourKey"
        },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Spacer(
            Modifier
                .size(MapScale.maxWidth.dp, 8.dp)
                .background(Brush.horizontalGradient(*ElevationKey.colorStops), RoundedCornerShape(2.dp))
                // A 1 dp white border centred on the bar's edge, as SwiftUI's `stroke` draws it: half of it outside,
                // so the key is 111 × 9 dp over all (screenshot 23). `Modifier.border` would draw all of it inside.
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        Color.White.copy(alpha = 0.85f),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = Stroke(1.dp.toPx()),
                    )
                },
        )
        Row(Modifier.width(MapScale.maxWidth.dp)) {
            Text(low, style = keyText, color = Color.White)
            Spacer(Modifier.weight(1f).widthIn(min = 6.dp))
            Text(high, style = keyText, color = Color.White)
        }
    }
}

/** The scale bar's ⊔: a line with a tick up at each end, `width` dp wide. Takes no touches. */
@Composable
fun ScaleBarShape(width: Double) {
    Spacer(Modifier.size(width.dp, 6.dp).drawShadowed { n, size -> drawScaleBar(n, size) })
}

/** The ⊔ across a `size` canvas (px), without its shadow. */
internal fun Density.drawScaleBar(n: android.graphics.Canvas, size: Size) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.SQUARE
        color = android.graphics.Color.WHITE
        strokeWidth = 2.dp.toPx()
    }
    val path = android.graphics.Path()
    path.moveTo(0f, 0f)
    path.lineTo(0f, size.height)
    path.lineTo(size.width, size.height)
    path.lineTo(size.width, 0f)
    n.drawPath(path, paint)
}

/** While aiming: the live distance and height from you to the spot under the crosshair, as the map moves. */
@Composable
fun AimPanel(model: AppModel, aim: CrosshairAim, recorder: LocationRecorder) {
    val location by recorder.location.collectAsStateWithLifecycle()
    val point by aim.point.collectAsStateWithLifecycle()
    val elevation by aim.elevation.collectAsStateWithLifecycle()
    val elevationPoint by aim.elevationPoint.collectAsStateWithLifecycle()
    val userElevation by model.userElevation.collectAsStateWithLifecycle()
    val distanceUnit by model.distanceUnit.collectAsStateWithLifecycle()
    val heightUnit by model.heightUnit.collectAsStateWithLifecycle()
    AimPanel(
        location?.point, point, elevation, elevationPoint, userElevation, distanceUnit, heightUnit,
        online = connectivityOnline(),
        onNextDistanceUnit = model::nextDistanceUnit,
        onStop = model::clearMeasure,
    )
}

/** `you`: your position (null before the first fix); `point`, `elevation` and `elevationPoint` as `CrosshairAim`'s. */
@Composable
fun AimPanel(
    you: GeoPoint?,
    point: GeoPoint?,
    elevation: Double?,
    elevationPoint: GeoPoint?,
    userElevation: Double?,
    distanceUnit: DistanceUnit,
    heightUnit: HeightUnit,
    online: Boolean,
    onNextDistanceUnit: () -> Unit,
    onStop: () -> Unit,
) {
    // Swift: CLLocation.distance(from:); Geo.distance here, as everywhere on Android (state spec §2.11).
    val distanceText = when {
        you == null -> "Waiting for GPS…"
        point == null -> "–"
        else -> Format.distance(Geo.distance(you, point), distanceUnit)
    }
    val heightText = if (elevation == null || userElevation == null) {
        missingHeightText(online)
    } else {
        val diff = elevation - userElevation
        val d = Format.heightDifference(diff, heightUnit)
        when {
            heightUnit.whole(abs(diff)) == 0 -> "Crosshair is level with you"
            diff > 0 -> "▲ Crosshair is $d higher than you"
            else -> "▼ Crosshair is $d lower than you"
        }
    }
    MeasuringPanel(onStop) {
        Text("You to the crosshair", style = panelTitle, modifier = Modifier.alpha(0.85f))
        Text(distanceText, style = bigNumber, modifier = Modifier.unitSwitch(onNextDistanceUnit))
        // While the map moves, the height is an earlier spot's until the lookup catches up: dimmed.
        val current = elevationPoint == point
        Text(heightText, style = Headline, modifier = Modifier.alpha(if (current) 1f else 0.6f))
        if (elevation != null && userElevation != null) {
            Text(
                "Crosshair ${Format.height(elevation, heightUnit)} · You ${Format.height(userElevation, heightUnit)} above sea level",
                style = Caption,
                modifier = Modifier.alpha(if (current) 0.85f else 0.5f),
            )
        }
        Text(
            "Move the crosshair onto the spot and tap the map (or the measure button) for point A.",
            style = Caption2,
            modifier = Modifier.alpha(0.7f),
        )
    }
}

/** The ✕ in the corner of the measuring hint and panel. Its cross is as big as iOS's (13 dp across in screenshot 02,
 *  so a 22 dp Close), centred 21 dp from the panel's corner. */
@Composable
fun StopMeasuringButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(42.dp)
            .pressable(onClick = onClick)
            .semantics { contentDescription = "Stop measuring" }
            .testTag("stopMeasuring"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** A spot's coordinates with its height beside them, or under them when they don't fit side by side (large text):
 *  iOS `ViewThatFits`. Each piece is measured at its natural width; side by side 8 dp apart if they all fit, else
 *  one under the other 6 dp apart; centred either way. */
@Composable
fun CoordinateRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val fits = widths.sum() + gap * (measurables.size - 1) <= constraints.maxWidth
        if (fits) {
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
            val used = placeables.sumOf { it.width } + gap * (placeables.size - 1)
            val height = max(placeables.maxOfOrNull { it.height } ?: 0, constraints.minHeight)
            val width = max(used, constraints.minWidth)
            layout(width, height) {
                var x = (width - used) / 2
                for (p in placeables) {
                    p.place(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
        } else {
            val vGap = 6.dp.roundToPx()
            val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
            val width = max(placeables.maxOfOrNull { it.width } ?: 0, constraints.minWidth)
            val used = placeables.sumOf { it.height } + vGap * (placeables.size - 1)
            layout(width, max(used, constraints.minHeight)) {
                var y = 0
                for (p in placeables) {
                    p.place((width - p.width) / 2, y)
                    y += p.height + vGap
                }
            }
        }
    }
}

/** Whether the network is up, for the "loading" or "no data offline" texts. */
@Composable
internal fun connectivityOnline(): Boolean {
    val state by Connectivity.shared.state.collectAsStateWithLifecycle()
    return state.isOnline
}

/** The height above sea level of a spot, beside its coordinates: from the elevation data (as the contour lines), or for
 *  you, while there is none, the GPS's own (marked). A tap: heights in the other unit (metres or feet). `id`: the test
 *  tag, for the UI test. */
@Composable
fun HeightBar(
    elevation: Double?,
    gpsAltitude: Double? = null,
    unit: HeightUnit,
    id: String,
    onTap: () -> Unit,
    online: Boolean = connectivityOnline(),
) {
    val h = elevation ?: gpsAltitude
    val text = if (h != null) Format.height(h, unit) else if (online) "… ${unit.symbol}" else "– ${unit.symbol}"
    val spoken = if (h == null) {
        if (online) "Loading" else "No elevation data here offline"
    } else {
        "${Format.height(h, unit)} above sea level" + if (elevation == null) ", from GPS" else ""
    }
    Row(
        Modifier
            .pressable(onClickLabel = "Shows heights in ${unit.next.spokenName}", onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = "Height"
                stateDescription = spoken
                testTag = id
            }
            .pill(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mountain.2.fill at caption size is 20 × 10.5 pt (screenshots 01, 15, 24): Landscape at 22 dp, which fills
        // 22 × 12 of its 24. It is laid out only as tall as its drawing, so the text line sets the pill's height
        // (34 dp), as on iOS.
        Icon(Icons.Filled.Landscape, contentDescription = null, modifier = Modifier.size(22.dp, 12.dp).requiredSize(22.dp))
        Text(text, style = barText, maxLines = 1, softWrap = false)
        if (elevation == null && gpsAltitude != null) {
            Text("GPS", style = Caption2.copy(fontWeight = FontWeight.Bold), modifier = Modifier.alpha(0.75f))
        }
    }
}

/** The coordinates' and heights' text: subheadline, semibold, tabular digits. */
private val barText = Subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TabularDigits)

/** A distance (or the scale bar) that a tap shows in the next unit: kilometres, miles, nautical miles. No ripple, as
 *  iOS shows no highlight. (The Swift's `name` is always "distances".) */
fun Modifier.unitSwitch(onTap: () -> Unit): Modifier = clickable(
    interactionSource = null,
    indication = null,
    onClickLabel = "Tap to show distances in other units",
    role = Role.Button,
    onClick = onTap,
)

/** Drone mode's reporting lines, as a pilot says where they are: you (cyan, as the distances from you on the map) and
 *  the tapped spot (amber, as the distances from it), each from the nearest airfield. */
@Composable
fun DroneCard(report: DroneReport) {
    Column(
        Modifier
            .blocksMap()
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .panel(0.72f)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("droneCard"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        report.you?.let { DroneLine(Icons.Filled.NearMe, DpSize(17.dp, 17.dp), "You", it, YouColour) }
        // The pin keeps its narrow frame (16 × 24).
        report.pin?.let { DroneLine(SatIcons.MapPin, DpSize(12.dp, 18.dp), "Pin", it, PinColour) }
    }
}

/** One party's line: its symbol (caption bold, in a 14 dp frame it overflows as SF's does), who, and where. The
 *  symbols are as big as iOS's in screenshot 25: the arrow about 13 dp across, the pin 15 dp tall. */
@Composable
private fun DroneLine(icon: ImageVector, iconSize: DpSize, who: String, text: String, colour: Color) {
    CompositionLocalProvider(LocalContentColor provides colour) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.requiredSize(iconSize))
            }
            Text(who, style = Caption.copy(fontWeight = FontWeight.ExtraBold), color = colour, modifier = Modifier.alpha(0.8f))
            Text(
                text,
                style = Subheadline.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = TabularDigits),
                color = colour,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.25.sp, maxFontSize = 15.sp, stepSize = 0.25.sp),
            )
        }
    }
}

/** Coordinates you can copy: tap to put them on the clipboard ("Copied" for 2.5 s). `id`: the copy button's test
 *  tag, for the UI test. `iconSlot`: how tall SF lays out the symbol at body size, which with the text sets the pill's
 *  height (screenshots 01, 15, 24): `location.fill` 18.7, `mappin` 19.7, `mappin.circle.fill` 19.1. */
@Composable
fun CoordinateBar(icon: ImageVector, text: String, id: String, iconSlot: Dp, onClose: (() -> Unit)? = null) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.blocksMap().pill(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .pressable {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    copied = true
                    // Each tap ends its own "Copied", as the Swift's asyncAfter does.
                    scope.launch {
                        delay(2500)
                        copied = false
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = if (copied) "Copied" else "Copy $text"
                    testTag = id
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The symbol at the button's body size: 20 dp tall (screenshots 01 and 15), in its slot; a narrow one (the
            // pin) keeps its narrow frame, as SF's does.
            Icon(icon, contentDescription = null, modifier = Modifier.height(iconSlot).requiredHeight(20.dp))
            Text(
                if (copied) "Copied" else text,
                style = barText,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.5.sp, maxFontSize = 15.sp, stepSize = 0.25.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!copied) {
                // doc.on.doc at caption size is 12 × 14.7 pt (screenshot 01): ContentCopy fills 19 × 22 of its 24.
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp).alpha(0.8f))
            }
        }
        if (onClose != null) {
            // xmark at caption bold is 9 pt across (screenshot 15): Close at 16 dp. Compose gives a small target a
            // 48 dp touch area of its own.
            Icon(
                Icons.Filled.Close,
                contentDescription = "Hide coordinates",
                modifier = Modifier.size(16.dp).pressable(onClick = onClose).testTag("hideCoordinates"),
            )
        }
    }
}

/** The area downloading, over the map: its layer, level and tiles, a bar for the level, and Pause. `modifier`: a tap
 *  on the banner (outside Pause) opens Offline maps. */
@Composable
fun DownloadBanner(area: OfflineArea, progress: DownloadProgress?, onPause: () -> Unit, modifier: Modifier = Modifier) {
    val p = progress
    val fraction = if (p != null && p.levelTotal > 0) p.levelDone.toFloat() / p.levelTotal.toFloat() else 0f
    val detail = if (p == null || p.layer.isEmpty()) {
        "Starting…"
    } else {
        "${p.layer} · zoom ${p.zoom} of up to ${p.maxZoom}: ${Format.count(p.levelDone)}/${Format.count(p.levelTotal)} · " +
            "${Format.count(p.fetched)} tiles, ${Format.bytes(p.bytes)} saved"
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            modifier
                .fillMaxWidth()
                .blocksMap()
                .panel(0.7f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Downloading ${area.name}",
                    style = Subheadline.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // One line, cut short with "…" (screenshot 08).
                Text(
                    detail,
                    style = Caption.copy(fontFeatureSettings = TabularDigits),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.85f),
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = SystemYellow,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            // Swift's Spacer() at its least, 8 pt: the column takes the rest of the width.
            Spacer(Modifier.width(8.dp))
            // pause.fill at body size with 8 pt round it is 13.4 pt tall in a button 31 pt wide (screenshot 08): Pause
            // at 23 dp is as tall, and 4 dp at its sides make the button as wide (its frame is wider than SF's).
            Box(
                Modifier
                    .pressable(onClick = onPause)
                    .semantics { contentDescription = "Pause" }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(23.dp))
            }
        }
    }
}

/** Shown while choosing an offline area with the lasso. */
@Composable
fun DrawingBar(model: AppModel) {
    val draft by model.draftArea.collectAsStateWithLifecycle()
    val lassoActive by model.lassoActive.collectAsStateWithLifecycle()
    DrawingBar(
        drawn = draft != null,
        lassoActive = lassoActive,
        onLassoActive = model::setLassoActive,
        onCancel = model::cancelDrawing,
        onRedraw = { model.setDraftArea(null) },
        onContinue = model::confirmDrawnArea,
    )
}

/** `drawn`: a loop has been drawn (the model's `draftArea`). */
@Composable
fun DrawingBar(
    drawn: Boolean,
    lassoActive: Boolean,
    onLassoActive: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRedraw: () -> Unit,
    onContinue: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .blocksMap()
                .panel(0.75f)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!drawn) {
                SegmentedControl(listOf("Move map", "Draw"), selected = if (lassoActive) 1 else 0) { onLassoActive(it == 1) }
                Text(
                    if (lassoActive) "Draw a loop around the area with one finger." else "Pan and zoom to the area, then switch to Draw.",
                    style = Footnote,
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.fillMaxWidth()) {
                    BarButton("Cancel", onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Text("Download this area?", style = Headline)
                Row(Modifier.fillMaxWidth()) {
                    BarButton("Cancel", onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    BarButton("Redraw", onClick = onRedraw)
                    Spacer(Modifier.weight(1f))
                    BarButton("Continue", weight = FontWeight.SemiBold, onClick = onContinue)
                }
            }
        }
    }
}

/** A plain text button on a panel over the map: body text, dimmed while pressed. */
@Composable
private fun BarButton(title: String, weight: FontWeight = FontWeight.Normal, onClick: () -> Unit) {
    Text(title, style = Body.copy(fontWeight = weight), modifier = Modifier.pressable(onClick = onClick))
}

/** iOS's segmented `Picker` (screenshot 10): a 32 dp track, the chosen segment a lighter rounded box 2 dp inside it,
 *  its title semibold and the others' regular, as UISegmentedControl's. (Material's segmented buttons are outlined
 *  capsule halves, not this.) Each segment is a choice TalkBack reads as selected or not. */
@Composable
private fun SegmentedControl(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color(0x3D767680), RoundedCornerShape(9.dp))
            .padding(2.dp)
            .selectableGroup(),
    ) {
        titles.forEachIndexed { i, title ->
            val chosen = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // See-through, as iOS's: grey 99 over the bar, with the map's contour lines in it (screenshot 10).
                    .background(if (chosen) Color.White.copy(alpha = 0.27f) else Color.Transparent, RoundedCornerShape(7.dp))
                    .selectable(chosen, interactionSource = null, indication = null, role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = Footnote.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Clear the recorded track?", from My location's long-press menu (iOS `confirmationDialog`). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ClearTrackDialog(onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = CellBackground,
        title = { Text("Clear the recorded track?", style = Headline) },
        confirmButton = {
            TextButton({
                onClear()
                onDismiss()
            }) { Text("Clear track", style = Body, color = SystemRed) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = Body) } },
    )
}
