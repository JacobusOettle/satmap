package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.DroneLinkModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.Aviation
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.DroneLink
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.core.stringFormat
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.map.DroneLinkLayer
import kotlin.math.max

// Drone link over the map in drone mode (android/docs/spec/drone-link.md §3): the button that opens its settings and,
// with a drone or aircraft tapped, its panel. The iPhone's `DroneLinkOverlay`, `DroneLinkBar` and `DroneLinkPanel`.

/** Drone mode's Drone link button and, with a drone or aircraft tapped, its panel (only in drone mode). */
@Composable
fun DroneLinkOverlay(model: AppModel) {
    val mode by model.terrainMode.collectAsStateWithLifecycle()
    val selection by model.droneLink.selection.collectAsStateWithLifecycle()
    if (!mode.showsAviation) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DroneLinkBar(model.droneLink) { model.setSheet(ActiveSheet.droneLink) }
        if (selection != null) DroneLinkPanel(model)
    }
}

/** What the link is doing; a tap opens its settings. */
@Composable
fun DroneLinkBar(link: DroneLinkModel, onTap: () -> Unit) {
    val enabled by link.isEnabled.collectAsStateWithLifecycle()
    val status by link.status.collectAsStateWithLifecycle()
    val colour = when (status) {
        DroneLink.Status.Off -> Color.White
        is DroneLink.Status.Waiting -> SystemYellow
        is DroneLink.Status.Receiving -> SystemGreen
        is DroneLink.Status.Silent, is DroneLink.Status.Failed -> Color(red = 1f, green = 0.45f, blue = 0.4f)
    }
    Row(
        Modifier
            .testTag("droneLinkButton")
            .blocksMap()
            .pressable(onClick = onTap)
            .semantics(mergeDescendants = true) {
                contentDescription = "Drone link"
                stateDescription = status.text
            }
            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = colour, modifier = Modifier.size(15.dp))
        Text(
            if (enabled) status.text else "Drone link",
            style = Footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(14.dp).alpha(0.7f),
        )
    }
}

/** A tapped drone or aircraft: what the link knows of it, the distances in nautical miles, and the disclaimer, at the
 *  top of the map like the measuring panel. */
@Composable
fun DroneLinkPanel(model: AppModel) {
    val link = model.droneLink
    val selection by link.selection.collectAsStateWithLifecycle()
    // Redrawn with the traffic (at least once a second while listening).
    @Suppress("UNUSED_VARIABLE")
    val revision by link.revision.collectAsStateWithLifecycle()
    val ground by link.selectedGroundM.collectAsStateWithLifecycle()
    val fix by model.recorder.location.collectAsStateWithLifecycle()
    val heightUnit by model.heightUnit.collectAsStateWithLifecycle()
    val distanceUnit by model.distanceUnit.collectAsStateWithLifecycle()
    val userElevation by model.userElevation.collectAsStateWithLifecycle()
    val me = fix?.point
    val now = link.now()
    Column(
        Modifier
            .fillMaxWidth()
            .blocksMap()
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("droneLinkPanel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val s = selection) {
            is DroneLinkModel.Selection.Drone -> link.traffic.drones[s.sysid]?.let { d ->
                DroneRows(link, d, now, me, ground, heightUnit, distanceUnit, model.aviationIndex())
            }
            is DroneLinkModel.Selection.Aircraft -> link.traffic.aircraft[s.icao]?.let { a ->
                AircraftRows(link, a, now, me, userElevation, heightUnit)
            }
            null -> {}
        }
        Text(
            DroneLink.disclaimer,
            style = Caption2,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.testTag("droneLinkPanelDisclaimer"),
        )
    }
}

@Composable
private fun Header(link: DroneLinkModel, title: String, colour: Color, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(colour, CircleShape))
        Text(title, style = Headline, color = Color.White, maxLines = 1)
        if (detail != null) {
            Text(
                detail,
                style = Subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.85f).weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.Close,
            contentDescription = "Close",
            tint = Color.White,
            modifier = Modifier.testTag("droneLinkPanelClose").pressable { link.select(null) }.padding(6.dp).size(14.dp),
        )
    }
}

@Composable
private fun PanelRow(name: String, value: String, colour: Color = Color.White) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name,
            style = Caption.copy(fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            modifier = Modifier.width(92.dp).alpha(0.7f),
        )
        Text(
            value,
            style = Subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TabularDigits),
            color = colour,
        )
    }
}

private val Orange = Color(0xFFFF9230)

@Composable
private fun DroneRows(
    link: DroneLinkModel,
    d: DroneLink.Drone,
    now: Double,
    me: GeoPoint?,
    groundM: Double?,
    hu: HeightUnit,
    du: DistanceUnit,
    index: AviationIndex?,
) {
    val stale = d.isStale(now)
    val colour = Color(if (stale) DroneLinkLayer.staleColour else DroneLinkLayer.colour(d.colourIndex))
    Header(link, d.label, colour, d.heartbeat?.let { if (it.armed) "Armed" else "Disarmed" })
    if (stale) {
        val age = d.positionAt?.let { "No position for ${(now - it).toInt()} s" } ?: "No position yet"
        PanelRow("Position", age, Orange)
    }
    d.position?.let { p ->
        PanelRow("Height", "${Format.height(p.relativeAltM, hu)} above take-off · ${Format.height(p.altMslM, hu)} above sea level")
        groundM?.let { PanelRow("≈ above ground", Format.height(p.altMslM - it, hu)) }
    }
    val motion = listOfNotNull(d.groundSpeedMs?.let { DroneLink.speed(it, du) }, d.climbMs?.let { "climb " + DroneLink.climb(it, hu) })
    if (motion.isNotEmpty()) PanelRow("Speed", motion.joinToString(" · "))
    val battery = listOfNotNull(d.batteryRemainingPct?.let { "$it %" }, d.batteryVoltageV?.let { stringFormat("%.1f V", it) })
    if (battery.isNotEmpty()) PanelRow("Battery", battery.joinToString(" · "))
    d.fixText?.let { fix -> PanelRow("GPS", fix + (d.gps?.satellitesVisible?.let { " · $it satellites" } ?: "")) }
    val p = d.point ?: return
    if (me != null) PanelRow("From you", DroneLink.rangeAndBearing(me, p), YouColour)
    if (index != null) {
        // "2.9 NM SW of FASH Stellenbosch" for the nearest, then "FACT Cape Town 17.8 NM" for the next two.
        val near = DroneLink.nearestAerodromes(index, p)
        for ((i, n) in near.withIndex()) {
            PanelRow(if (i == 0) "Airfields" else "", if (i == 0) Aviation.report(p, n.place) else "${n.place.label} ${Aviation.nauticalMiles(n.meters)}")
        }
    }
}

@Composable
private fun AircraftRows(
    link: DroneLinkModel,
    a: DroneLink.Aircraft,
    now: Double,
    me: GeoPoint?,
    meGroundM: Double?,
    hu: HeightUnit,
) {
    val drones = link.traffic.sortedDrones
    val low = DroneLink.isLowAndClose(a, references(me, meGroundM, drones))
    val highlight = Color(DroneLinkLayer.highlightColour)
    Header(link, a.label, if (low) highlight else Color.White, a.kind.title + if (a.callsign != null) " · ${a.icaoHex}" else "")
    if (low) {
        PanelRow(
            "Warning",
            "Below ${DroneLink.grouped(DroneLink.lowAltitudeFeet.toInt())} ft within ${DroneLink.closeNauticalMiles.toInt()} NM",
            highlight,
        )
    }
    a.altitudeM?.let { alt ->
        val metres = if (hu == HeightUnit.metres) " (${Format.height(alt, HeightUnit.metres)})" else ""
        PanelRow("Altitude", DroneLink.feet(alt) + metres + if (a.altitudeType == 1) " GPS" else " QNH")
    }
    val motion = listOfNotNull(a.speedText, a.climbMs?.let { DroneLink.climb(it, HeightUnit.feet) })
    if (motion.isNotEmpty()) PanelRow("Speed", motion.joinToString(" · "))
    a.headingDeg?.let { PanelRow("Heading", (it.rounded().toInt() % 360).toString().padStart(3, '0') + "°") }
    a.squawk?.let { PanelRow("Squawk", it.toString().padStart(4, '0')) }
    if (me != null) PanelRow("From you", DroneLink.rangeAndBearing(me, a.position), YouColour)
    for (d in drones) {
        val p = d.point ?: continue
        PanelRow("From ${d.label}", DroneLink.rangeAndBearing(p, a.position), Color(DroneLinkLayer.colour(d.colourIndex)))
    }
    PanelRow("Last heard", "${max(0, (now - a.lastSeen).toInt())} s ago", if (a.isStale(now)) Orange else Color.White)
}

/** You (with the ground's height under you) and each drone with a position (with the ground where it took off): what
 *  "low and close" is measured from, on the map and in the panel. */
fun references(me: GeoPoint?, meGroundM: Double?, drones: Collection<DroneLink.Drone>): List<DroneLink.Reference> =
    listOfNotNull(me?.let { DroneLink.Reference(it, meGroundM) }) +
        drones.sortedBy { it.sysid }.mapNotNull { d -> d.point?.let { DroneLink.Reference(it, d.takeOffGroundM) } }
