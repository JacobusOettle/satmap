package com.jacobusoettle.satmap.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.DroneLinkModel
import com.jacobusoettle.satmap.core.DroneLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Drone link's settings (android/docs/spec/drone-link.md §1), from drone mode's Drone link button: the switch, the
// phone's address and the port, what to put in QGroundControl, and what the link hears now. The iPhone's
// `DroneLinkSheet`, section for section.

@Composable
fun DroneLinkSheet(link: DroneLinkModel, close: () -> Unit) {
    val enabled by link.isEnabled.collectAsStateWithLifecycle()
    val port by link.port.collectAsStateWithLifecycle()
    val status by link.status.collectAsStateWithLifecycle()
    val addresses by link.addresses.collectAsStateWithLifecycle()
    val asking by link.askingDisclaimer.collectAsStateWithLifecycle()
    var portText by rememberSaveable { mutableStateOf(port.toString()) }
    var copied by remember { mutableStateOf<String?>(null) }
    // The phone's addresses when the sheet opens, and again every few seconds (Wi-Fi or the hotspot may come up).
    LaunchedEffect(Unit) {
        while (true) {
            link.refreshAddresses()
            delay(5_000)
        }
    }
    val portValid = DroneLink.port(portText) != null
    val first = addresses.firstOrNull()?.address
    SheetScreen(ScreenChrome("Drone link", ScreenChrome.Leading.Done(close))) {
        item {
            FormSection(
                footer = "Shows your drone and the aircraft its ADS-B receiver hears, from the MAVLink your ground " +
                    "station forwards to this phone over Wi-Fi. For ArduPilot and PX4 drones; DJI drones don't use MAVLink.",
                first = true,
            ) {
                FormToggleRow("Drone link", enabled, Modifier.testTag("droneLinkSwitch")) { link.setEnabled(it) }
                FormRow(Modifier.testTag("droneLinkStatus").semantics(mergeDescendants = true) {}) {
                    Box(Modifier.size(10.dp).background(statusColour(status), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(status.text, style = Subheadline, color = if (enabled) LabelPrimary else LabelSecondary)
                }
            }
        }
        item {
            FormSection(
                header = "This phone",
                footer = if (portValid) "QGroundControl forwards to port ${DroneLink.defaultPort} unless you change it there."
                else "Use a port from 1024 to 65535.",
                footerColour = if (portValid) LabelSecondary else SystemRed,
            ) {
                if (addresses.isEmpty()) {
                    FormRow(verticalPadding = MultiLineRowPadding) {
                        Text(
                            "Not on Wi-Fi or a hotspot. Join the ground station's Wi-Fi network, or turn on the phone's " +
                                "hotspot and join it from the ground station.",
                            style = Subheadline,
                            color = LabelSecondary,
                            modifier = Modifier.testTag("droneLinkNoAddress"),
                        )
                    }
                }
                for (a in addresses) {
                    val hostPort = "${a.address}:$port"
                    FormRow(verticalPadding = MultiLineRowPadding) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.label, style = Caption, color = LabelSecondary)
                            Text(hostPort, style = Body.copy(fontFeatureSettings = TabularDigits), color = LabelPrimary)
                        }
                        CopyButton(hostPort, "Copy", "droneLinkCopyAddress", copied) { copied = it }
                    }
                }
                val keyboard = LocalSoftwareKeyboardController.current
                FormRow {
                    Text("Listen port", style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
                    BasicTextField(
                        value = portText,
                        onValueChange = { t ->
                            portText = t.filter { it in '0'..'9' }.take(5)
                            DroneLink.port(portText)?.let { link.setPort(it) }
                        },
                        modifier = Modifier.widthIn(max = 100.dp).testTag("droneLinkPort"),
                        textStyle = Body.copy(color = LabelPrimary, textAlign = TextAlign.End, fontFeatureSettings = TabularDigits),
                        cursorBrush = SolidColor(Accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (portText.isEmpty()) Text("14445", style = Body, color = LabelTertiary)
                                field()
                            }
                        },
                    )
                }
            }
        }
        item {
            FormSection(
                header = "In QGroundControl",
                footer = "QGroundControl forwards MAVLink 2 only. Mission Planner and MAVProxy can forward too: in " +
                    "MAVProxy, --out=udp:${first ?: "<phone IP>"}:$port. Nothing is sent back: SatMap only listens, and " +
                    "only while it is open.",
            ) {
                val steps = DroneLink.qgcInstructions(addresses.map { it.address }, port)
                FormRow(verticalPadding = MultiLineRowPadding) {
                    Text(steps, style = Subheadline, color = LabelPrimary, modifier = Modifier.testTag("droneLinkQGC"))
                }
                FormRow {
                    CopyButton(steps, "Copy these steps", "droneLinkCopySteps", copied) { copied = it }
                }
            }
        }
        item {
            FormSection {
                FormRow(Modifier.testTag("droneLinkDisclaimer").semantics(mergeDescendants = true) {}, verticalPadding = MultiLineRowPadding) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = SystemYellow, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(DroneLink.disclaimer, style = Footnote, color = LabelPrimary)
                }
            }
        }
    }
    if (asking) DroneLinkDisclaimer(onAccept = link::acceptDisclaimer, onDismiss = link::declineDisclaimer)
}

/** The status line's dot: grey off, yellow waiting, green receiving, red silent or the port taken. */
fun statusColour(status: DroneLink.Status): Color = when (status) {
    DroneLink.Status.Off -> Color(0xFF8E8E93)
    is DroneLink.Status.Waiting -> SystemYellow
    is DroneLink.Status.Receiving -> SystemGreen
    is DroneLink.Status.Silent, is DroneLink.Status.Failed -> SystemRed
}

/** A borderless accent button that puts `text` on the clipboard and says "Copied" (with a tick) for 2 s. */
@Composable
private fun CopyButton(text: String, label: String, id: String, copied: String?, setCopied: (String?) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val done = copied == id
    Row(
        Modifier
            .testTag(id)
            .pressable {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                setCopied(id)
                scope.launch {
                    delay(2_000)
                    setCopied(null)
                }
            }
            .semantics { contentDescription = if (done) "Copied" else label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (done) Icons.Filled.Check else Icons.Filled.ContentCopy, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        Text(if (done) "Copied" else label, style = Body, color = Accent)
    }
}

/** Asked the first time the switch is turned on. */
@Composable
fun DroneLinkDisclaimer(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("droneLinkDisclaimerAlert"),
        containerColor = CellBackground,
        title = { Text("Drone link", style = Headline) },
        text = { Text(DroneLink.disclaimer, style = Subheadline, color = LabelPrimary) },
        confirmButton = {
            TextButton(onAccept, modifier = Modifier.testTag("droneLinkAccept")) { Text("I understand", style = Body, color = Accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = Body, color = Accent) } },
    )
}
