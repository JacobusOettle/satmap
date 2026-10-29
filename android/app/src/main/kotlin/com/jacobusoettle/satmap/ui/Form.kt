package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Android only: iOS's inset-grouped `List`/`Form` look (UI spec §1.5, DESIGN.md D9): sections with upper-case
// headers and footers, rounded cards of rows, link, action, value, toggle, text-field and swipe-to-delete rows.

/** A section: header (upper-cased), a rounded card holding the rows, and a footer. Sections go in a `LazyColumn` on
 *  `SheetBackground`, 32 dp apart (`Arrangement.spacedBy(32.dp)`, content padding top 16, bottom the navigation bar
 *  + 24). `first`: the first section starts 2 dp lower with a header, 19 dp lower without, as iOS's list does. */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    first: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = if (!first) 0.dp else if (header != null) 2.dp else 19.dp
    Column(modifier.fillMaxWidth().padding(top = top)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = Footnote,
                color = LabelSecondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 7.dp),
            )
        }
        FormCard(content)
        if (footer != null) {
            Text(
                footer,
                style = Footnote,
                color = LabelSecondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp),
            )
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

/** A row's pressed look: iOS's grey cell highlight in place of a ripple. */
@Composable
private fun Modifier.formClickable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .background(if (pressed && enabled) CellPressed else Color.Transparent)
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** A plain row (at least 44 dp, padding 16 / 11; two-line rows 16 / 8). */
@Composable
fun FormRow(modifier: Modifier = Modifier, verticalPadding: Dp = 11.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** iOS `Label` in a list: a 28 dp box with the icon (22 dp), 16 dp, then the title. */
@Composable
private fun RowScope.LabelContent(title: String, icon: ImageVector?, iconTint: Color, textColor: Color) {
    if (icon != null) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = iconTint)
        }
        Spacer(Modifier.width(16.dp))
    }
    Text(title, style = Body, color = textColor, modifier = Modifier.weight(1f, fill = false))
}

/** A row that opens another screen: title (and icon), with a chevron. */
@Composable
fun FormLinkRow(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FormRow(modifier.dividerInset(if (icon != null) 60.dp else 16.dp).formClickable(onClick = onClick)) {
        LabelContent(title, icon, Accent, LabelPrimary)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = LabelTertiary,
        )
    }
}

/** A button in the list: text and icon in the accent colour (tertiary, and not tappable, when disabled). */
@Composable
fun FormActionRow(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    style: TextStyle = Body,
    onClick: () -> Unit,
) {
    val colour = if (enabled) Accent else LabelTertiary
    FormRow(modifier.dividerInset(if (icon != null) 60.dp else 16.dp).formClickable(enabled, onClick = onClick)) {
        if (icon != null) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = colour)
            }
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = style, color = colour)
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

/** Title and the green iOS-style switch; the whole row toggles. */
@Composable
fun FormToggleRow(title: String, checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    // The switch is 32 dp tall: 6 dp above and below make the iOS 44 dp row.
    FormRow(modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange), verticalPadding = 6.dp) {
        Text(title, style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, colors = FormSwitchColors())
    }
}

/** The iOS switch's colours: green when on, grey when off, a white thumb and no border (screenshots 04 and 12). */
@Composable
fun FormSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = SystemGreen,
    checkedThumbColor = Color.White,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = Color(0x29787880),
    uncheckedThumbColor = Color.White,
    uncheckedBorderColor = Color.Transparent,
)

/** A text field with no underline or outline, and a placeholder. */
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
) {
    FormRow {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.weight(1f),
            textStyle = Body.copy(color = LabelPrimary),
            cursorBrush = SolidColor(Accent),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = Body, color = LabelTertiary)
                    field()
                }
            },
        )
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
