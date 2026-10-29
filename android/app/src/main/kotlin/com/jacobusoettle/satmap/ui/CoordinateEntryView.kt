package com.jacobusoettle.satmap.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.CoordinateParser
import com.jacobusoettle.satmap.core.Geo
import kotlinx.coroutines.flow.first

/** Type or paste a position in any common format: it is shown on the map as measuring point A, with the distance
 *  from you, as if the spot had been tapped while measuring. `sheetState`: the field takes the focus once the sheet
 *  is fully up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateEntryView(model: AppModel, close: () -> Unit, sheetState: SheetState? = null) {
    var text by rememberSaveable { mutableStateOf("") }
    val location by model.recorder.location.collectAsStateWithLifecycle()
    val distanceUnit by model.distanceUnit.collectAsStateWithLifecycle()
    val me = location?.point
    val result = remember(text, me) { CoordinateParser.parse(text, near = me) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    SheetScreen(ScreenChrome("Go to coordinates", ScreenChrome.Leading.Cancel(close))) {
        item {
            FormSection(
                footer = "Any format: -25.0969, 31.4371 · 25.0969 S 31.4371 E · 25° 05.814' S 31° 26.227' E · " +
                    "25°05'48.8\"S 31°26'13.6\"E · 2505.814,S,03126.227,E (GPS) · map and geo: links · " +
                    "UTM 36J 342401 7223411 · MGRS 36J UT 42401 23411 · plus code 5G6HWC3P+6R",
                first = true,
            ) {
                // Several lines (a pasted address or link may be long); the return key starts a new line.
                FormTextFieldRow(
                    text,
                    onValueChange = { text = it },
                    placeholder = "Paste or type coordinates",
                    modifier = Modifier.focusRequester(focus).testTag("coordinateText"),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                    ),
                )
                PasteRow(onPaste = { text = it })
            }
        }
        item {
            FormSection {
                val r = result
                when {
                    r != null -> {
                        FormValueRow("Position", Format.coordinate(r.point), wraps = true)
                        FormValueRow("Read as", r.format, wraps = true)
                        if (me != null) {
                            FormValueRow("From you", Format.distance(Geo.distance(me, r.point), distanceUnit), wraps = true)
                        }
                    }
                    text.isBlank() -> FormRow {
                        Text("The position shows here as you type or paste.", style = Body, color = LabelSecondary)
                    }
                    else -> FormRow { Text("Not a position yet.", style = Body, color = SystemOrange) }
                }
            }
        }
        item {
            FormSection {
                FormActionRow(
                    "Show and measure from here",
                    Icons.Outlined.Straighten,
                    enabled = result != null,
                    style = Headline,
                ) {
                    result?.let { model.measure(from = it.point) }
                    close()
                }
            }
        }
    }
    // iOS focuses the field as the sheet appears; here once it is fully up, so the keyboard doesn't push it up
    // halfway.
    LaunchedEffect(sheetState) {
        if (sheetState != null) snapshotFlow { sheetState.currentValue }.first { it == SheetValue.Expanded }
        // The list lays its rows out after this composition: the field is there a frame later.
        withFrameNanos {}
        focus.requestFocus()
        keyboard?.show()
    }
}

/** iOS's `PasteButton`: pastes the text on the clipboard; dimmed while there is none. Reading only the clipboard's
 *  description (whether it holds text) doesn't show Android's "pasted from" notice; the tap reads the text itself. */
@Composable
private fun PasteRow(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    var hasText by remember { mutableStateOf(clipboardHasText(clipboard)) }
    // Android 10+ tells only the app in front about clipboard changes, and reads nothing before the window has the
    // focus: a position copied in another app is seen when the sheet's window gets the focus back.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused) hasText = clipboardHasText(clipboard)
    }
    // Copies made in SatMap.
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hasText = clipboardHasText(clipboard) }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }
    // 44 dp as the row in screenshot 21: the 34 dp button 5 dp from the row's top and bottom.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (hasText) PasteFill else PasteFill.copy(alpha = 0.4f))
                .pressable(enabled = hasText) {
                    val clip = clipboard?.primaryClip
                    if (clip != null && clip.itemCount > 0) onPaste(clip.getItemAt(0).coerceToText(context).toString())
                }
                .heightIn(min = 34.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val colour = if (hasText) Color.White else Color.White.copy(alpha = 0.5f)
            Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
            Text("Paste", style = Body, color = colour)
        }
    }
}

private fun clipboardHasText(clipboard: ClipboardManager?): Boolean =
    clipboard != null && clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true
