package com.jacobusoettle.satmap.ui.groups

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacobusoettle.satmap.core.GroupActionError
import com.jacobusoettle.satmap.core.GroupPolicy
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.CellBackground
import com.jacobusoettle.satmap.ui.Footnote
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSpinner
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.LabelIcon
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import com.jacobusoettle.satmap.ui.SystemOrange
import com.jacobusoettle.satmap.ui.SystemRed
import com.jacobusoettle.satmap.ui.SystemYellow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import com.jacobusoettle.satmap.ui.dividerInset
import com.jacobusoettle.satmap.ui.pill
import com.jacobusoettle.satmap.ui.pressable

// The groups screens' shared pieces, in the house style of Form.kt (docs/groups/SPEC.md §9.2): rows with icons,
// buttons in the list, alerts driven by an optional message, the colour dot with initials, and a toast.

/** A member's colour ("#RRGGBB") as a colour; grey when it isn't one. */
fun hexColour(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF8E8E93)
}

/** The colour dot with initials (S5's rows, S12's header, S15's choices): white bold initials on the member's colour. */
@Composable
fun MemberDot(initials: String, colourHex: String, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(hexColour(colourHex), CircleShape).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
            maxLines = 1,
        )
    }
}

/** A list `Label` of several lines: the icon (28 dp, yellow), then the text, which wraps (S1, S3, S9). */
@Composable
fun IconTextRow(text: String, icon: ImageVector, modifier: Modifier = Modifier, iconTint: Color = SystemYellow) {
    FormRow(modifier.dividerInset(60.dp), verticalPadding = MultiLineRowPadding) {
        Box(Modifier.align(Alignment.Top)) { LabelIcon(icon, iconTint) }
        Spacer(Modifier.width(16.dp))
        Text(text, style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
    }
}

/** A plain text row of several lines. */
@Composable
fun TextRow(text: String, modifier: Modifier = Modifier, colour: Color = LabelPrimary) {
    FormRow(modifier, verticalPadding = MultiLineRowPadding) {
        Text(text, style = Body, color = colour, modifier = Modifier.weight(1f))
    }
}

/** A button row in the list: accent text (red when `destructive`), an optional icon; a spinner and `busyText` while
 *  it works. Disabled, its text is white and it takes no taps. */
@Composable
fun ButtonRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
    busyText: String? = null,
    tag: String? = null,
    style: TextStyle = Body,
    onClick: () -> Unit,
) {
    val colour = when {
        !enabled -> LabelSecondary
        destructive -> SystemRed
        else -> Accent
    }
    FormRow(
        modifier
            .dividerInset(if (icon != null) 60.dp else 16.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .pressable(enabled = enabled && !busy, onClick = onClick),
    ) {
        if (busy) {
            FormSpinner()
            Spacer(Modifier.width(12.dp))
            Text(busyText ?: title, style = style, color = LabelSecondary)
        } else {
            if (icon != null) {
                LabelIcon(icon, if (destructive) SystemRed else if (enabled) Accent else LabelSecondary)
                Spacer(Modifier.width(16.dp))
            }
            Text(title, style = style, color = colour)
        }
    }
}

/** A form's main button (S3's Subscribe, S4's Create group …): its title in the headline style, a spinner at the end
 *  while it works. */
@Composable
fun PrimaryButtonRow(title: String, enabled: Boolean = true, busy: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    FormRow(
        Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .pressable(enabled = enabled && !busy, onClick = onClick),
    ) {
        Text(title, style = Headline, color = if (enabled && !busy) Accent else LabelSecondary, modifier = Modifier.weight(1f))
        if (busy) FormSpinner()
    }
}

/** An error or notice under a form's button, in orange (iOS's `GroupsNotice`). */
@Composable
fun NoticeRow(text: String) {
    FormRow(verticalPadding = MultiLineRowPadding) {
        Text(text, style = Body, color = SystemOrange, modifier = Modifier.weight(1f).testTag("groupsError"))
    }
}

/** A row that opens a page or a mail in another app: its title in the accent colour, a small arrow at the end. */
@Composable
fun LinkRow(title: String, url: String) {
    val context = LocalContext.current
    FormRow(Modifier.pressable { openLink(context, url) }) {
        Text(title, style = Body, color = Accent, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.NorthEast, contentDescription = null, tint = LabelSecondary, modifier = Modifier.size(14.dp))
    }
}

/** A big title as the first row of a screen's first card (S1, S3). */
@Composable
fun HeadlineRow(text: String) {
    FormRow(verticalPadding = 14.dp) {
        Text(text, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = LabelPrimary, modifier = Modifier.weight(1f))
    }
}

/** A text above a section's card, as written (not upper-cased): S1's sentence above "Agree and continue". */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Footnote, color = LabelSecondary, modifier = modifier.padding(start = 32.dp, end = 32.dp, bottom = 6.dp))
}

/** A big title over a screen's list (S1, S3). */
@Composable
fun ScreenHeadline(text: String) {
    Text(
        text,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        color = LabelPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
    )
}

/** An alert with a title, an optional message and its buttons (the iOS `.alert`), each a text button; the last is
 *  the bolder one when it isn't destructive. */
class AlertButton(val title: String, val destructive: Boolean = false, val tag: String? = null, val onClick: () -> Unit)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AlertBox(title: String, message: String?, buttons: List<AlertButton>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = CellBackground,
        title = { Text(title, style = Headline) },
        text = message?.let { { Text(it, style = Footnote) } },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                for (b in buttons) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            b.onClick()
                        },
                        modifier = if (b.tag != null) Modifier.testTag(b.tag) else Modifier,
                    ) {
                        Text(b.title, style = Body, color = if (b.destructive) SystemRed else Accent)
                    }
                }
            }
        },
    )
}

/** An error or result shown as an alert with "OK" (house style: alerts driven by an optional message). */
@Composable
fun MessageAlert(message: String?, onDismiss: () -> Unit) {
    message ?: return
    AlertBox(message, null, listOf(AlertButton("OK") {}), onDismiss)
}

/** The text of an error from a groups action: its §8.14 text, or the generic one. */
fun errorText(e: Throwable): String = (e as? GroupActionError)?.message ?: GroupActionError.standardMessage(GroupActionError.Kind.Server)

/** A short message at the bottom of a sheet ("Link copied", "Support code copied"). */
@Composable
fun BoxScope.Toast(text: String?) {
    text ?: return
    Text(
        text,
        style = Footnote.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).pill().semantics { },
    )
}

/** Opens a web page or a mail address in another app. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser or mail app: nothing opens.
    }
}

/** Copies plain text; `sensitive` hides it from the clipboard's preview (an invite link holds the group key, §9.3 S6:
 *  `EXTRA_IS_SENSITIVE`, the string itself below API 33). */
fun copyText(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** The 12 member colours (§4.6). */
val palette: List<String> get() = GroupPolicy.palette

/** A row of the colour choices (S15): the dots, the chosen one ringed. */
@Composable
fun ColourChoices(chosen: Int, initials: String, onChoose: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowStart in palette.indices step 6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in rowStart until minOf(rowStart + 6, palette.size)) {
                    val ring = if (i == chosen) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier
                    Box(
                        Modifier.size(42.dp).then(ring).padding(4.dp).pressable(onClickLabel = "Colour ${i + 1}") { onChoose(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        MemberDot(if (i == chosen) initials else "", palette[i], size = 34.dp)
                    }
                }
            }
        }
    }
}

/** A busy screen: a spinner with a text (S8's "Checking the invite…"). */
@Composable
fun BusyRow(text: String) {
    FormRow {
        FormSpinner()
        Spacer(Modifier.width(12.dp))
        Text(text, style = Body, color = LabelSecondary)
    }
}
