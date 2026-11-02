package com.jacobusoettle.satmap.ui.groups

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateFormat
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupFormat
import com.jacobusoettle.satmap.core.GroupText
import com.jacobusoettle.satmap.core.GroupTextField
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Footnote
import com.jacobusoettle.satmap.ui.SystemYellow
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Caption
import com.jacobusoettle.satmap.ui.CellPressed
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormTextFieldRow
import com.jacobusoettle.satmap.ui.FormValueRow
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.TabularDigits
import com.jacobusoettle.satmap.ui.pressable
import com.jacobusoettle.satmap.ui.SatIcons
import kotlinx.coroutines.delay

/** A position's coordinates and height as for a tapped spot (the map's pills; tap the coordinates to copy them, the
 *  height for the other unit), outside the cards. */
@Composable
fun GroupPositionRow(model: AppModel, p: GeoPoint, id: String, height: Double?) {
    val unit by model.heightUnit.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            com.jacobusoettle.satmap.ui.CoordinateRow {
                com.jacobusoettle.satmap.ui.CoordinateBar(SatIcons.MapPin, Format.coordinate(p), id, iconSlot = 19.7.dp)
                com.jacobusoettle.satmap.ui.HeightBar(height, unit = unit, id = id + "Height", onTap = model::nextHeightUnit)
            }
        }
    }
}

/** A secondary line of a card (subheadline, grey). */
@Composable
fun SmallTextRow(text: String) {
    FormRow(verticalPadding = 8.dp) {
        Text(text, style = com.jacobusoettle.satmap.ui.Subheadline, color = LabelSecondary, modifier = Modifier.weight(1f))
    }
}

/** A pin type's badge: a circle in its colour with its white glyph (§9.4, as on the map). */
@Composable
fun PinBadge(type: PinType?, size: Dp = 30.dp) {
    Box(Modifier.size(size).background(Color(GroupTexts.pinColour(type)), CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Icon(pinIcon(type), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.53f))
    }
}

/** S10 Drop pin (docs/groups/SPEC.md §9.3): one of the six types (nothing chosen at first), the animal for an animal
 *  sighting, an optional note, and the spot. The pin shows on the map at once and is sent when online. */
@Composable
fun DropPinScreen(model: AppModel, route: GroupsRoute.DropPin, nav: GroupsNav) {
    val groups = model.groups
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var animal by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val chosen = type?.let { PinType(it) }
    val name = remember(route.groupId) { groups.hub.detail(route.groupId)?.name ?: "" }
    val ready = chosen != null && (chosen != PinType.animal || GroupText.clean(animal, GroupTextField.animal) != null)
    SheetScreen(ScreenChrome("Drop pin", ScreenChrome.Leading.Cancel(nav::pop))) {
        item {
            FormSection(first = true) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in PinType.entries.chunked(3)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (t in row) {
                                val selected = chosen == t
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .background(
                                            if (selected) SystemYellow.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                            RoundedCornerShape(10.dp),
                                        )
                                        .border(2.dp, if (selected) SystemYellow else Color.Transparent, RoundedCornerShape(10.dp))
                                        .testTag("pinType-${t.rawValue}")
                                        .semantics { this.selected = selected }
                                        .pressable { type = t.rawValue }
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PinBadge(t, 36.dp)
                                    Text(GroupTexts.pinShortLabel(t), style = Footnote.copy(fontWeight = FontWeight.SemiBold), color = LabelPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (chosen == PinType.animal) {
            item {
                FormSection(header = "Which animal?") {
                    FormTextFieldRow(
                        animal,
                        { animal = it },
                        placeholder = "e.g. Rhino",
                        modifier = Modifier.testTag("pinAnimal"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    )
                }
            }
        }
        item {
            FormSection(header = "Note (optional)") {
                FormTextFieldRow(
                    note,
                    { note = it },
                    placeholder = "e.g. Two adults near the water hole",
                    modifier = Modifier.testTag("pinNote"),
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
        item {
            val footer = if (chosen == PinType.animal) {
                "Animal sightings disappear after 12 hours. Everyone in $name sees this pin."
            } else {
                "Everyone in $name sees this pin until it's deleted."
            }
            FormSection(footer = footer) {
                FormRow {
                    Icon(SatIcons.MapPin, contentDescription = null, tint = LabelSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Format.coordinate(GeoPoint(route.lat, route.lon)), style = Body.copy(fontFeatureSettings = TabularDigits), color = LabelPrimary)
                }
            }
        }
        item {
            FormSection {
                ButtonRow("Drop pin", enabled = ready, tag = "dropPinConfirm", style = com.jacobusoettle.satmap.ui.Headline) {
                    message = null
                    val t = chosen ?: return@ButtonRow
                    try {
                        groups.hub.dropPin(route.groupId, t, if (t == PinType.animal) animal else null, note, route.lat, route.lon)
                        // The tapped spot's pin gives way to the group's.
                        if (model.selectedPoint.value == GeoPoint(route.lat, route.lon)) model.setSelectedPoint(null)
                        nav.pop()
                    } catch (e: Exception) {
                        message = errorText(e)
                    }
                }
                message?.let { NoticeRow(it) }
            }
        }
    }
}

/** S11 Pin detail (§9.3): what it is, its note, who dropped it and when, when an animal sighting disappears, its
 *  coordinates (tap to copy) and height, and Delete, Report and Block as allowed. */
@Composable
fun PinDetailScreen(model: AppModel, route: GroupsRoute.Pin, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val rev by groups.revision.collectAsStateWithLifecycle()
    val heightUnit by model.heightUnit.collectAsStateWithLifecycle()
    val now = rememberNow()
    val context = LocalContext.current
    val pin = remember(rev, route) { groups.hub.pinDetail(route.groupId, route.pinId) }
    var seen by remember { mutableStateOf(false) }
    if (pin != null) seen = true
    LaunchedEffect(pin == null, seen) { if (pin == null && seen) nav.pop() }
    var confirmDelete by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }
    val height by produceState<Double?>(null, pin?.position) {
        val p = pin?.position ?: return@produceState
        value = model.height(GeoPoint(p.lat, p.lon))
    }
    val use24 = DateFormat.is24HourFormat(context)
    SheetScreen(ScreenChrome(GroupTexts.pinLabel(pin?.knownType), leading(model, nav, below))) {
        val p = pin ?: return@SheetScreen
        item {
            FormSection(first = true) {
                FormRow(verticalPadding = 12.dp) {
                    PinBadge(p.knownType, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (p.knownType == PinType.animal) p.animal ?: GroupTexts.pinLabel(p.knownType) else GroupTexts.pinLabel(p.knownType),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabelPrimary,
                    )
                }
                p.note?.let { TextRow(it) }
                SmallTextRow(GroupTexts.droppedBy(p.dropperName, GroupTexts.time(p.createdAt, use24), GroupFormat.ago(now - p.createdAt)))
                if (p.knownType == PinType.animal) {
                    p.expiresAt?.let { SmallTextRow("Disappears at ${GroupTexts.time(it, use24)}") }
                }
            }
        }
        item { GroupPositionRow(model, GeoPoint(p.position.lat, p.position.lon), "pinCoordinates", height) }
        if (p.canDelete || p.canReport || p.canBlock) {
            item {
                FormSection {
                    if (p.canDelete) ButtonRow("Delete pin", destructive = true) { confirmDelete = true }
                    if (p.canReport) ButtonRow("Report pin") { nav.push(GroupsRoute.Report(route.groupId, ReportTarget.Pin(p.pinId))) }
                    if (p.canBlock) ButtonRow("Block ${p.dropperName}") { block = true }
                }
            }
        }
    }
    val p = pin ?: return
    if (confirmDelete) {
        AlertBox(
            "Delete this pin for everyone?",
            null,
            listOf(AlertButton("Delete", destructive = true) {
                groups.hub.deletePin(p.pinId, route.groupId)
                nav.pop()
            }, AlertButton("Cancel") {}),
        ) { confirmDelete = false }
    }
    if (block) BlockAlert(model, route.groupId, p.dropperId, p.dropperName, onBlocked = nav::pop) { block = false }
}

/** The Block alert (§9.3, §8.11, SD27): one-way and on this phone only; while you share in the group it also offers
 *  "Block and stop sharing". */
@Composable
fun BlockAlert(model: AppModel, groupId: String, memberId: String, name: String, onBlocked: () -> Unit, onDismiss: () -> Unit) {
    val groups = model.groups
    val sharing = remember(groupId) { groups.hub.detail(groupId)?.isSharing == true }
    val buttons = buildList {
        add(AlertButton("Block", destructive = true) {
            groups.hub.block(memberId, groupId)
            onBlocked()
        })
        if (sharing) {
            add(AlertButton("Block and stop sharing", destructive = true, tag = "blockStopSharing") {
                groups.hub.block(memberId, groupId)
                groups.hub.setSharing(false, groupId)
                onBlocked()
            })
        }
        add(AlertButton("Cancel") {})
    }
    AlertBox(
        "Block $name?",
        "You won't see $name's position, trail, pins or name on this phone. They won't be told, and they can still see you while you share in this group.",
        buttons,
        onDismiss,
    )
}
