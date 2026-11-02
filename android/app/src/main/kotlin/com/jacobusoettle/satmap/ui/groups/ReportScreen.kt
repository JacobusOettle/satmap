package com.jacobusoettle.satmap.ui.groups

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GroupHub
import com.jacobusoettle.satmap.core.ReportReason
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.FormChoiceRow
import com.jacobusoettle.satmap.ui.Footnote
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormTextFieldRow
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** S13 Report (docs/groups/SPEC.md §9.3, SD10, SD28): why, more details, then "Send report": the reporter's own copy
 *  of the text goes to the developer, never a position. Afterwards, for a pin or a member, the offer to block them. */
@Composable
fun ReportScreen(model: AppModel, route: GroupsRoute.Report, nav: GroupsNav) {
    val groups = model.groups
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var thanks by remember { mutableStateOf(false) }
    // Whom the offer to block after the report would hide: the member (not you, not blocked already), or the pin's
    // dropper (not you); nobody for the group name, and then "Thanks for reporting" has OK only.
    val who = remember(route) { blockable(groups.hub, route.groupId, route.target) }
    SheetScreen(ScreenChrome("Report", ScreenChrome.Leading.Cancel(nav::pop))) {
        item {
            FormSection(header = GroupTexts.reportQuestion(route.target), first = true) {
                for (r in ReportReason.entries) {
                    FormChoiceRow(reason == r.rawValue, modifier = Modifier.testTag("reportReason-${r.rawValue}"), onClick = { reason = r.rawValue }) {
                        Text(GroupTexts.reportReason(r), style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
                        if (reason == r.rawValue) Icon(Icons.Filled.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        item {
            FormSection {
                FormTextFieldRow(comment, { comment = it }, placeholder = "More details (optional)", singleLine = false, maxLines = 5)
            }
        }
        item {
            FormSection {
                FormRow(verticalPadding = MultiLineRowPadding) {
                    Text(GroupTexts.reportNote(route.target), style = Footnote, color = LabelSecondary)
                }
                ButtonRow("Send report", enabled = reason != null, busy = busy, tag = "sendReport", style = Headline) {
                    message = null
                    val r = ReportReason.entries.firstOrNull { it.rawValue == reason } ?: return@ButtonRow
                    busy = true
                    scope.launch {
                        try {
                            groups.hub.report(route.groupId, route.target, r, comment)
                            thanks = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = errorText(e)
                        } finally {
                            busy = false
                        }
                    }
                }
                message?.let { NoticeRow(it) }
            }
        }
    }
    if (thanks) {
        val w = who
        if (w == null) {
            // OK and a tap outside both dismiss it, and back to the screen reported from (once: a button's tap
            // dismisses the alert first).
            AlertBox("Thanks for reporting", null, listOf(AlertButton("OK") {})) {
                thanks = false
                nav.pop()
            }
        } else {
            AlertBox(
                "Thanks for reporting",
                "Do you also want to block ${w.second}? You won't see them on this phone. They can still see you while you share.",
                listOf(
                    AlertButton("Block", destructive = true) {
                        groups.hub.block(w.first, route.groupId)
                        nav.pop()
                    },
                    AlertButton("Not now") { nav.pop() },
                ),
            ) { thanks = false }
        }
    }
}

/** Whom "Block" after a report would block, as (member id, name): a pin's dropper when the pin can be blocked, a
 *  member who can be blocked and isn't yet; null for the group name or when nobody can be (iOS's `blockable`). */
fun blockable(hub: GroupHub, groupId: String, target: ReportTarget): Pair<String, String>? = when (target) {
    is ReportTarget.Pin -> hub.pinDetail(groupId, target.pinId)?.takeIf { it.canBlock }?.let { it.dropperId to it.dropperName }
    is ReportTarget.Member ->
        hub.memberDetail(groupId, target.memberId)?.takeIf { it.canBlock && !it.row.isBlocked }?.let { it.row.memberId to it.row.name }
    ReportTarget.Group -> null
}
