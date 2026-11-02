package com.jacobusoettle.satmap.ui.groups

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GroupText
import com.jacobusoettle.satmap.core.GroupTextField
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormTextFieldRow
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** S4 Create group (docs/groups/SPEC.md §9.3): the group's name and yours (prefilled), then "Create group", enabled
 *  once both are valid (§2.5). On success S9 for the new group, then S5 (§9.2). */
@Composable
fun CreateGroupScreen(model: AppModel, nav: GroupsNav) {
    val groups = model.groups
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf(groups.lastDisplayName) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val valid = GroupText.clean(name, GroupTextField.groupName) != null && GroupText.clean(displayName, GroupTextField.displayName) != null
    SheetScreen(ScreenChrome("New group", ScreenChrome.Leading.Cancel(nav::pop))) {
        item {
            FormSection(header = "Group name", first = true) {
                FormTextFieldRow(
                    name,
                    { name = it },
                    placeholder = "e.g. Kruger trip",
                    modifier = Modifier.testTag("groupName"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
            }
        }
        item {
            FormSection(header = "Your name in the group", footer = "Members see these names. Names are encrypted on your phone.") {
                FormTextFieldRow(
                    displayName,
                    { displayName = it },
                    placeholder = "e.g. Sam",
                    modifier = Modifier.testTag("displayName"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
        }
        item {
            FormSection {
                ButtonRow("Create group", enabled = valid, busy = busy, busyText = "Creating…", tag = "createGroup") {
                    busy = true
                    scope.launch {
                        try {
                            val gid = groups.createGroup(name, displayName)
                            nav.replace(listOf(GroupsRoute.List, GroupsRoute.Consent(gid, afterCreate = true)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = errorText(e)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
    MessageAlert(message) { message = null }
}
