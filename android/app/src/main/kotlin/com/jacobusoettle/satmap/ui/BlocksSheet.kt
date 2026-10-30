package com.jacobusoettle.satmap.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.FileShapes
import com.jacobusoettle.satmap.kml.KMLLibrary

/** The types the import offers: iOS's kml, kmz, xml and zip, plus octet-stream, which many providers report for
 *  `.kml` and `.kmz` (offline spec §2.9, DESIGN.md D13). */
private val kmlTypes = arrayOf(
    "application/vnd.google-earth.kml+xml",
    "application/vnd.google-earth.kmz",
    "application/xml",
    "text/xml",
    "application/zip",
    "application/octet-stream",
)

/** Imported KML/KMZ files: each one's blocks switched on or off the map, and whether they keep the files' colours. */
@Composable
fun BlocksSheet(model: AppModel, library: KMLLibrary, close: () -> Unit) {
    val files by library.files.collectAsStateWithLifecycle()
    val useFileColours by library.useFileColours.collectAsStateWithLifecycle()
    val importError by library.importError.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { library.importFile(it) }
    }
    SheetScreen(
        ScreenChrome(
            "Blocks",
            ScreenChrome.Leading.Done(close),
            trailing = {
                IconButton(onClick = { importer.launch(kmlTypes) }, modifier = Modifier.testTag("importKml")) {
                    // Material's cross is 14/24 of its icon: 30 dp makes it 17.5 dp, iOS's plus (screenshot 04).
                    Icon(Icons.Filled.Add, contentDescription = "Import KML", tint = Accent, modifier = Modifier.size(30.dp))
                }
            },
        ),
    ) {
        if (files.isEmpty()) {
            item {
                FormSection(first = true) {
                    FormRow {
                        Text("Import a .kml or .kmz file to show your blocks on the map.", style = Body, color = LabelSecondary)
                    }
                }
            }
        } else {
            item {
                FormSection(footer = "Switch a file off to take its blocks off the map.", first = true) {
                    // Keyed by the file, as iOS's ForEach: a swipe deletes that file only.
                    for (f in files) key(f.id) {
                        FormSwipeToDeleteRow(onDelete = { library.delete(f.id) }) { deleteAction ->
                            FileRow(
                                f,
                                deleteAction,
                                onShow = {
                                    library.setShown(f.id, true)
                                    f.document.bounds?.let { model.map?.zoom(to = it) }
                                    close()
                                },
                                onShownChange = { library.setShown(f.id, it) },
                            )
                        }
                    }
                }
            }
            item {
                FormSection(footer = "Off: every block is drawn in white.") {
                    ColoursRow(useFileColours, onChange = library::setUseFileColours)
                }
            }
        }
    }
    importError?.let { ImportErrorAlert(it, onDismiss = library::clearImportError) }
}

/** A file: its name and what it holds (`Format.fileContents`; a tap shows it and zooms to it), and its switch.
 *  `deleteAction` goes on the name's button, where TalkBack lands. */
@Composable
private fun FileRow(f: KMLLibrary.File, deleteAction: Modifier, onShow: () -> Unit, onShownChange: (Boolean) -> Unit) {
    val contents = remember(f.document) { Format.fileContents(FileShapes(f.document)) }
    // The texts' button fills the row's height (58 dp, as iOS's); the switch has a 48 dp tall touch area and ends
    // 14 dp in from the card's edge, as iOS's does.
    FormRow(verticalPadding = 0.dp, endPadding = 14.dp) {
        Column(
            Modifier.weight(1f).then(deleteAction).pressable(onClick = onShow).padding(vertical = MultiLineRowPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(f.name, style = Body, color = LabelPrimary)
            Text("$contents · tap to show on map", style = Caption, color = LabelSecondary)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(
            f.isShown,
            Modifier
                .minimumInteractiveComponentSize()
                .toggleable(f.isShown, interactionSource = null, indication = null, role = Role.Switch, onValueChange = onShownChange)
                .semantics { contentDescription = "Show ${f.name} on the map" },
        )
    }
}

/** "Use the colours in the files": a checkbox (a filled square with a check, or an empty one); the whole row toggles
 *  it, greyed while pressed as an iOS list button. */
@Composable
private fun ColoursRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    FormRow(
        Modifier
            .dividerInset(60.dp)
            .background(if (pressed) CellPressed else Color.Transparent)
            .toggleable(on, interaction, indication = null, role = Role.Checkbox, onValueChange = onChange)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .testTag("useFileColours"),
    ) {
        // checkmark.square.fill at title3 is a 23 dp square (screenshot 04): Material's is 3/4 of the icon. It
        // overflows its line-tall box, so the row is 44 dp.
        LabelIcon(if (on) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, Accent, size = 30.dp)
        Spacer(Modifier.width(16.dp))
        Text("Use the colours in the files", style = Body, color = LabelPrimary)
    }
}

/** "Couldn't import" with what went wrong. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportErrorAlert(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = CellBackground,
        title = { Text("Couldn't import", style = Headline) },
        text = { Text(message, style = Footnote) },
        confirmButton = { TextButton(onDismiss) { Text("OK", style = Body) } },
    )
}
