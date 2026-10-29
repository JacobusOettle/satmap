package com.jacobusoettle.satmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Android only: iOS's inset-grouped `List`/`Form` look (UI spec §1.5, DESIGN.md D9): sections with upper-case
// headers and footers, rounded cards of rows, link, action, value, toggle, text-field and swipe-to-delete rows.

/** A section: header (upper-cased), a rounded card holding the rows, and a footer. Sections go in a `LazyColumn` on
 *  `SheetBackground`, 29.5 dp apart (`Arrangement.spacedBy(29.5.dp)`, content padding top 16, bottom the navigation
 *  bar + 15), and a section without a footer ends 5.5 dp below its card, iOS's empty footer: the next section starts
 *  35 dp below a card, 33 dp below a footer's last baseline (screenshots 06, 07, 21). `first`: the first section
 *  starts 1 dp lower with a header, 19 dp lower without, as iOS's list does. */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    first: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = if (!first) 0.dp else if (header != null) 1.dp else 19.dp
    Column(modifier.fillMaxWidth().padding(top = top)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = Footnote,
                color = LabelSecondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 6.dp),
            )
        }
        FormCard(content)
        if (footer != null) {
            Text(
                footer,
                style = Footnote,
                color = LabelSecondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            )
        } else {
            Spacer(Modifier.height(5.5.dp))
        }
    }
}

/** Where a row's divider starts: under its text (16 dp from the card's edge, 60 dp after an icon). */
@Immutable
private class DividerInset(val inset: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@DividerInset
}

/** The divider under this row starts `inset` from the card's left edge. */
fun Modifier.dividerInset(inset: Dp): Modifier = this.then(DividerInset(inset))

/** One divider: the bottom of the row above, in px, and where it starts. */
private data class DividerLine(val y: Int, val inset: Int)

/** The rounded card: the rows one under the other, a 0.5 dp divider between each two (none after the last), inset
 *  to the text of the row above. */
@Composable
private fun FormCard(content: @Composable () -> Unit) {
    val lines = remember { mutableStateOf(emptyList<DividerLine>()) }
    Layout(
        content = content,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CellBackground)
            .drawWithContent {
                drawContent()
                val h = 0.5.dp.toPx()
                for (l in lines.value) {
                    drawRect(Separator, Offset(l.inset.toFloat(), l.y - h), Size(size.width - l.inset, h))
                }
            },
    ) { measurables, constraints ->
        val rowConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(rowConstraints) }
        val insets = measurables.map { ((it.parentData as? DividerInset)?.inset ?: 16.dp).roundToPx() }
        val width = constraints.maxWidth
        val height = placeables.sumOf { it.height }
        layout(width, height) {
            var y = 0
            val found = ArrayList<DividerLine>()
            placeables.forEachIndexed { i, p ->
                p.place(0, y)
                y += p.height
                if (i < placeables.lastIndex) found.add(DividerLine(y, insets[i]))
            }
            if (lines.value != found) lines.value = found
        }
    }
}

/** A row's pressed look: iOS's grey cell highlight in place of a ripple. `onLongClick`: a long press (a row's menu). */
@Composable
private fun Modifier.formClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .background(if (pressed && enabled) CellPressed else Color.Transparent)
        .combinedClickable(
            interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** A row in a list of choices (a zoom level, two lines): a tap chooses it, and TalkBack reads it as selected when it
 *  is. */
@Composable
fun FormChoiceRow(
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = MultiLineRowPadding,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    FormRow(
        modifier
            .background(if (pressed && enabled) CellPressed else Color.Transparent)
            .selectable(selected, interaction, indication = null, enabled = enabled, onClick = onClick),
        verticalPadding = verticalPadding,
        content = content,
    )
}

/** iOS's `ProgressView()` in a list: a small grey spinner. */
@Composable
fun FormSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(20.dp), color = LabelSecondary, strokeWidth = 2.dp)
}

/** The vertical inset of a row of several lines: iOS's 11 pt, and a third of a dp more because SF's lines are a
 *  little taller than Roboto's (Compose trims a line to the font's ascent and descent, as SwiftUI sizes it to SF's):
 *  Body and Caption make a 58.3 dp row, iOS's 58.6, and three lines 75.3 dp, iOS's 75.7 (screenshots 07, 09, 26). */
val MultiLineRowPadding = 11.33.dp

/** A plain row: at least 44 dp, padding 16 / 11, iOS's list inset (rows of several lines `MultiLineRowPadding`).
 *  `endPadding`: 14 dp for a row ending in a switch. */
@Composable
fun FormRow(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 11.dp,
    endPadding: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = endPadding, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** An SF Symbol in a list `Label` drawn as a Material icon: Material's glyphs fill about 3/4 of their box, so 28 dp
 *  draws them at the symbols' 21–26 pt (screenshot 06). The 1.2·X rule of UI spec §1.3 makes them too small here. */
val ListIconSize = 28.dp

/** A list `Label`'s icon, centred in a box 28 dp wide and one Body line (22 dp) tall: a larger icon overflows the box,
 *  as an SF Symbol overflows its line, so the row stays 44 dp. */
@Composable
internal fun LabelIcon(icon: ImageVector, tint: Color, size: Dp = ListIconSize) {
    Box(Modifier.size(28.dp, 22.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.requiredSize(size), tint = tint)
    }
}

/** iOS `Label` in a list: the icon (`LabelIcon`), 16 dp, then the title: the row's whole width up to the chevron
 *  (`fill`), or only its own. */
@Composable
private fun RowScope.LabelContent(
    title: String,
    icon: ImageVector?,
    iconTint: Color,
    textColor: Color,
    iconSize: Dp = ListIconSize,
    style: TextStyle = Body,
    fill: Boolean = true,
) {
    if (icon != null) {
        LabelIcon(icon, iconTint, iconSize)
        Spacer(Modifier.width(16.dp))
    }
    Text(title, style = style, color = textColor, modifier = if (fill) Modifier.weight(1f) else Modifier)
}

/** A row that opens another screen: title (and icon), with a chevron. */
@Composable
fun FormLinkRow(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FormRow(modifier.dividerInset(if (icon != null) 60.dp else 16.dp).formClickable(onClick = onClick)) {
        LabelContent(title, icon, Accent, LabelPrimary)
        Chevron()
    }
}

/** A row that opens another screen, showing `content` (several lines, so `MultiLineRowPadding`), with a chevron.
 *  `onLongClick`: a long press opens the row's menu. */
@Composable
fun FormLinkRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = MultiLineRowPadding,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FormRow(
        modifier.formClickable(onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick),
        verticalPadding = verticalPadding,
    ) {
        content()
        Chevron()
    }
}

/** The disclosure chevron at the end of a link row: iOS's is 12 pt tall, its right edge 19 pt in from the card's
 *  (screenshot 06). Drawn at 24 dp and 3.5 dp to the right over a 20 dp box, which keeps the rows' layout. */
@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.offset(x = 3.5.dp).size(20.dp).requiredSize(24.dp),
        tint = LabelTertiary,
    )
}

/** A button in the list: text and icon in the accent colour. Disabled, it isn't tappable and its text loses the
 *  accent (white, as "Export all offline maps" with no areas in screenshot 06); its icon keeps it. */
@Composable
fun FormActionRow(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconSize: Dp = ListIconSize,
    style: TextStyle = Body,
    onClick: () -> Unit,
) {
    FormRow(modifier.dividerInset(if (icon != null) 60.dp else 16.dp).formClickable(enabled, onClick = onClick)) {
        LabelContent(title, icon, Accent, if (enabled) Accent else LabelPrimary, iconSize, style, fill = false)
    }
}

/** Title and a secondary value with tabular digits. `wraps`: a long value wraps and keeps to the right (the
 *  coordinate entry's reading). */
@Composable
fun FormValueRow(title: String, value: String, wraps: Boolean = false, modifier: Modifier = Modifier) {
    FormRow(modifier) {
        Text(title, style = Body, color = LabelPrimary)
        if (wraps) Spacer(Modifier.width(16.dp)) else Spacer(Modifier.weight(1f).width(16.dp))
        Text(
            value,
            style = Body.copy(fontFeatureSettings = TabularDigits),
            color = LabelSecondary,
            textAlign = TextAlign.End,
            maxLines = if (wraps) Int.MAX_VALUE else 1,
            modifier = if (wraps) Modifier.weight(1f) else Modifier,
        )
    }
}

/** Title and the iOS switch; the whole row toggles. */
@Composable
fun FormToggleRow(title: String, checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    // The switch is 31 dp tall: the row's 44 dp minimum makes the iOS row. It ends 14 dp in from the card's edge.
    FormRow(
        modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalPadding = 6.dp,
        endPadding = 14.dp,
    ) {
        Text(title, style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
        IosSwitch(checked)
    }
}

/** iOS's switch (`UISwitch`, screenshots 04, 12 and 18): a 51 × 31 dp capsule, green when on and grey
 *  (`SecondaryFill`) when off, with a 27 dp white knob 2 dp in from its ends that slides between them. Material's
 *  `Switch` can't be drawn so: its track and thumb sizes are fixed. It only draws: the row (`FormToggleRow`) or a
 *  `toggleable` modifier passed in makes it a switch for touch and TalkBack. */
@Composable
fun IosSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, label = "switch knob")
    val track by animateColorAsState(if (checked) SystemGreen else SecondaryFill, label = "switch track")
    Box(modifier.size(51.dp, 31.dp).background(track, CircleShape), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = knob).size(27.dp).shadow(1.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** A text field with no underline or outline, and a placeholder. `trailing`: what follows the field in its row (the
 *  place search's Search button). */
@Composable
fun FormTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // The line keeps its whole 22 dp (Compose would trim it to the font's height), so the cursor is as tall as iOS's;
    // the placeholder has the same style, so it sits where the typed text will.
    val fieldStyle = Body.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
    FormRow {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.weight(1f),
            textStyle = fieldStyle.copy(color = LabelPrimary),
            cursorBrush = SolidColor(Accent),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = fieldStyle, color = LabelTertiary)
                    field()
                }
            },
        )
        trailing?.invoke(this)
    }
}

/** A row deleted by swiping it to the left (red "Delete"), also offered as TalkBack's "Delete" action (iOS's
 *  `.onDelete`). `content` gets that action as a modifier for the row's tappable node (its button, or the row when it
 *  is one): TalkBack only lands on nodes that can be tapped or have text, never on the swipe box itself.
 *  Callers key each row by its item's id, `key(item.id) { FormSwipeToDeleteRow(...) }`: the swipe stays settled at
 *  "deleted", so without the key the row that moves up into a deleted row's place takes over its swipe and is
 *  deleted too, and so on down the list. */
@Composable
fun FormSwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (deleteAction: Modifier) -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val deleteAction = Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().background(SystemRed).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", style = Body.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
    ) {
        Box(Modifier.background(CellBackground)) { content(deleteAction) }
    }
}
