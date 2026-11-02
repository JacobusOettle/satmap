package com.jacobusoettle.satmap.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.jacobusoettle.satmap.ui.Footnote
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.groups.AfterPro
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Caption
import com.jacobusoettle.satmap.ui.FormLinkRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen

/** S2 Groups list (docs/groups/SPEC.md §9.3): your groups oldest first, Create and Join, and the links. "Create group"
 *  opens the paywall first without Pro (§10.5). */
@Composable
fun GroupListScreen(model: AppModel, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val list by groups.groups.collectAsStateWithLifecycle()
    val isPro by groups.pro.isPro.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SheetScreen(ScreenChrome("Groups", leading(model, nav, below))) {
        item {
            if (list.isEmpty()) {
                // The empty first section: only its footer shows.
                Text(
                    "You're not in a group yet.",
                    style = Footnote,
                    color = LabelSecondary,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 19.dp),
                )
            } else {
                FormSection(header = "Your groups", first = true) {
                    for (g in list) key(g.groupId) {
                        FormLinkRow(onClick = { nav.push(GroupsRoute.Detail(g.groupId)) }, modifier = Modifier.testTag("groupRow")) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(g.name, style = Body, color = LabelPrimary)
                                Text(GroupTexts.listSubtitle(g), style = Caption, color = LabelSecondary)
                            }
                            if (g.isShown) {
                                Spacer(Modifier.width(8.dp))
                                Icon(GroupSymbols.map, contentDescription = "Shown on the map", tint = Accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        item {
            FormSection(
                footer = "To join a group, open the invite link someone in the group sent you, or scan their QR code with your camera.",
            ) {
                CreateAndJoinRows(nav, isPro)
            }
        }
        item {
            FormSection {
                ButtonRow("Terms of use") { openLink(context, GroupConfig.termsURL) }
                ButtonRow("Privacy notice") { openLink(context, GroupConfig.privacyURL) }
                ButtonRow("Contact us") { openLink(context, "mailto:${GroupConfig.contactEmail}") }
            }
        }
    }
}

/** "Create group" (the paywall first without Pro) and "Join with an invite link". */
@Composable
private fun CreateAndJoinRows(nav: GroupsNav, isPro: Boolean) {
    FormLinkRow("Create group", GroupSymbols.plusCircle, Modifier.testTag("createGroupRow")) {
        nav.push(if (isPro) GroupsRoute.Create else GroupsRoute.Paywall(AfterPro.Create))
    }
    FormLinkRow("Join with an invite link", GroupSymbols.link, Modifier.testTag("joinLinkRow")) { nav.push(GroupsRoute.JoinLink) }
}
