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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapViewRepresentable
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
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
            // mountain.2 is a wide symbol: Landscape at 34 dp is as wide and tall as it is on iOS (screenshot 01).
            iconSize = if (icon == Icons.Filled.Landscape || icon == Icons.Outlined.Landscape) 34.dp else 24.dp,
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
        if (activeID == null) Icons.Outlined.ArrowCircleDown else Icons.Filled.ArrowCircleDown,
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
    iconSize: Dp = 24.dp,
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
                .size(iconSize)
                .then(if (heightColours && !active) Modifier.heightColours(glyph) else Modifier),
        )
    }
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
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.size(56.dp).clearAndSetSemantics {}) {
        val c = center
        paint.setShadowLayer(ShadowBlur.toPx(), 0f, 0f, android.graphics.Color.BLACK)
        drawIntoCanvas { canvas ->
            val n = canvas.nativeCanvas
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
    }
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

@Composable
fun MeasurePanel(model: AppModel, recorder: LocationRecorder) {
    // M3: UI spec §2.3.4.
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

/** The scale bar's ⊔: a line with a tick up at each end, `width` dp wide. Takes no touches. */
@Composable
fun ScaleBarShape(width: Double) {
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.SQUARE
            color = android.graphics.Color.WHITE
        }
    }
    val path = remember { android.graphics.Path() }
    Canvas(Modifier.size(width.dp, 6.dp)) {
        paint.strokeWidth = 2.dp.toPx()
        paint.setShadowLayer(ShadowBlur.toPx(), 0f, 0f, android.graphics.Color.BLACK)
        path.rewind()
        path.moveTo(0f, 0f)
        path.lineTo(0f, size.height)
        path.lineTo(size.width, size.height)
        path.lineTo(size.width, 0f)
        drawIntoCanvas { it.nativeCanvas.drawPath(path, paint) }
    }
}

@Composable
fun AimPanel(model: AppModel, aim: CrosshairAim, recorder: LocationRecorder) {
    // M3: UI spec §2.3.3.
}

@Composable
fun StopMeasuringButton(onClick: () -> Unit) {
    // M3: UI spec §2.3.5.
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
        // mountain.2.fill at caption size is 20 × 10 pt (screenshot 01): Landscape at 20 dp is as tall.
        Icon(Icons.Filled.Landscape, contentDescription = null, modifier = Modifier.size(20.dp))
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

/** iOS's plain button: what it shows dims while pressed (no ripple). */
@Composable
private fun Modifier.pressable(onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .clickable(interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        .graphicsLayer { alpha = if (pressed) 0.35f else 1f }
}

@Composable
fun DroneCard(report: DroneReport) {
    // M5: UI spec §2.3.10.
}

/** Coordinates you can copy: tap to put them on the clipboard ("Copied" for 2.5 s). `id`: the copy button's test
 *  tag, for the UI test. */
@Composable
fun CoordinateBar(icon: ImageVector, text: String, id: String, onClose: (() -> Unit)? = null) {
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
            // The symbol at the button's body size: 20 dp tall (screenshots 01 and 15); a narrow one (the pin) keeps
            // its narrow frame, as SF's does.
            Icon(icon, contentDescription = null, modifier = Modifier.height(20.dp))
            Text(
                if (copied) "Copied" else text,
                style = barText,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.5.sp, maxFontSize = 15.sp, stepSize = 0.25.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!copied) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp).alpha(0.8f))
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

@Composable
fun DownloadBanner(area: OfflineArea, progress: DownloadProgress?, onPause: () -> Unit, modifier: Modifier = Modifier) {
    // M4: UI spec §2.3.2.
}

@Composable
fun DrawingBar(model: AppModel) {
    // M4: UI spec §2.3.1.
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
