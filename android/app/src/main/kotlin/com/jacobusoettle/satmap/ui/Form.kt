package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Android only: iOS's inset-grouped `List`/`Form` look (UI spec §1.5, DESIGN.md D9): sections with upper-case
// headers and footers, rounded cards of rows, link, action, value, toggle, text-field and swipe-to-delete rows.

/** A section: header (upper-cased), a rounded card holding the rows, and a footer. */
@Composable
fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    // M1: UI spec §1.5 (header, card, footer, 32 dp gaps).
    Column(content = content)
}

/** A plain row (at least 44 dp, padding 16 / 11). */
@Composable
fun FormRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    // M1: UI spec §1.5.
}

/** A row that opens another screen: title (and icon), with a chevron. */
@Composable
fun FormLinkRow(title: String, icon: ImageVector? = null, onClick: () -> Unit) {
    // M1: UI spec §1.5.
}

/** A button in the list: text and icon in the accent colour (tertiary when disabled). */
@Composable
fun FormActionRow(title: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    // M1: UI spec §1.5.
}

/** Title and a secondary value with tabular digits. */
@Composable
fun FormValueRow(title: String, value: String) {
    // M1: UI spec §1.5.
}

/** Title and the green iOS-style switch; the whole row toggles. */
@Composable
fun FormToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // M1: UI spec §1.5.
}

/** A text field with no underline or outline, and a placeholder. */
@Composable
fun FormTextFieldRow(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    // M3: UI spec §1.5.
}

/** A row deleted by swiping it to the left (red "Delete"), also offered as an accessibility action. */
@Composable
fun FormSwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    // M3: UI spec §1.5.
    content()
}
