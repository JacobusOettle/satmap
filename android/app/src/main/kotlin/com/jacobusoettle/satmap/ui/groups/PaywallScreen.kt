package com.jacobusoettle.satmap.ui.groups

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import com.jacobusoettle.satmap.ui.Subheadline
import com.jacobusoettle.satmap.ui.SystemYellow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.groups.AfterPro
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.groups.ProPlan
import com.jacobusoettle.satmap.groups.ProStore
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Caption
import com.jacobusoettle.satmap.ui.Caption2
import com.jacobusoettle.satmap.ui.FormChoiceRow
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormSpinner
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.SatIcons
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.SystemGreen
import com.jacobusoettle.satmap.ui.dividerInset
import com.jacobusoettle.satmap.ui.pressable
import kotlinx.coroutines.launch

/** S3 Paywall (docs/groups/SPEC.md §9.3, §10.5): what Pro gives, the three plans with the store's prices (Yearly
 *  first, with its saving), Subscribe or Buy, and Restore purchases. On success it closes and the flow goes on:
 *  creating a group, renewing (E5) or accepting an ownership offer (E17). */
@Composable
fun PaywallScreen(model: AppModel, route: GroupsRoute.Paywall, nav: GroupsNav) {
    val groups = model.groups
    val store = groups.pro
    val prices by store.prices.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by rememberSaveable { mutableStateOf(ProPlan.yearly) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { store.loadPrices() }

    /** The purchase went through: on with what the paywall was for. */
    suspend fun done() {
        when (val then = route.then) {
            AfterPro.Create -> nav.replaceTop(GroupsRoute.Create)
            is AfterPro.Renew -> {
                try {
                    groups.renewPro()
                    nav.pop()
                } catch (e: Exception) {
                    message = errorText(e)
                }
            }
            is AfterPro.Accept -> {
                try {
                    groups.acceptOwnership(then.groupId)
                    nav.pop()
                } catch (e: Exception) {
                    message = errorText(e)
                }
            }
        }
    }

    fun handle(r: ProStore.Result) {
        when (r) {
            ProStore.Result.Done -> scope.launch { done() }
            ProStore.Result.Cancelled -> {}
            is ProStore.Result.Message -> message = r.text
        }
    }

    SheetScreen(ScreenChrome("Spoor Pro", ScreenChrome.Leading.Cancel(nav::pop))) {
        item {
            FormSection(first = true) {
                HeadlineRow("Create your own groups")
                IconTextRow("Create groups and invite as many people as you like", SatIcons.Person3, iconSize = Person3RowSize)
                IconTextRow("Members join free", GroupSymbols.gift)
                IconTextRow("See everyone's position and trail, and share pins", GroupSymbols.map)
                IconTextRow("Encrypted on your phone: we can't read it", GroupSymbols.lock)
            }
        }
        item {
            FormSection(header = "Choose a plan") {
                when (val p = prices) {
                    ProStore.Prices.Loading -> FormRow {
                        FormSpinner()
                        Spacer(Modifier.width(12.dp))
                        Text("Loading prices…", style = Body, color = LabelSecondary)
                    }
                    ProStore.Prices.Failed -> NoticeRow(ProStore.STORE_UNAVAILABLE)
                    is ProStore.Prices.Loaded -> {
                        val yearly = p.prices.getValue(ProPlan.yearly)
                        val monthly = p.prices.getValue(ProPlan.monthly)
                        val lifetime = p.prices.getValue(ProPlan.lifetime)
                        val saving = ProStore.savingPercent(yearly.micros, monthly.micros)
                        PlanRow("Yearly", "${yearly.formatted} a year", saving?.let { "Save $it%" }, plan == ProPlan.yearly, "planYearly") {
                            plan = ProPlan.yearly
                        }
                        PlanRow("Monthly", "${monthly.formatted} a month", null, plan == ProPlan.monthly, "planMonthly") {
                            plan = ProPlan.monthly
                        }
                        PlanRow("Lifetime", "${lifetime.formatted} once", null, plan == ProPlan.lifetime, "planLifetime") {
                            plan = ProPlan.lifetime
                        }
                    }
                }
            }
        }
        item {
            FormSection(
                footer = "Subscriptions renew automatically until you cancel them in your Google Play settings, at least 24 hours " +
                    "before the end of the period. If Pro ends, groups you own pause until you renew or hand them to a member " +
                    "who has Pro. Pro bought on an iPhone doesn't carry over to Android, or the other way round.",
            ) {
                PrimaryButtonRow(
                    if (plan == ProPlan.lifetime) "Buy" else "Subscribe",
                    enabled = prices is ProStore.Prices.Loaded,
                    busy = busy,
                    tag = "buyPro",
                ) {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            handle(store.buy(activity, plan))
                        } finally {
                            busy = false
                        }
                    }
                }
                ButtonRow("Restore purchases", tag = "restorePurchases", enabled = !busy) {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            handle(store.restore())
                        } finally {
                            busy = false
                        }
                    }
                }
                message?.let { NoticeRow(it) }
            }
        }
        item {
            FormSection {
                LinkRow("Terms of use", GroupConfig.termsURL)
                LinkRow("Privacy notice", GroupConfig.privacyURL)
            }
        }
    }
}

/** A plan: its name (with the saving capsule), its price below, a check when chosen. */
@Composable
private fun PlanRow(title: String, detail: String, badge: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    FormChoiceRow(selected, modifier = Modifier.testTag(tag), onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Body.copy(fontWeight = FontWeight.SemiBold), color = LabelPrimary)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = Caption.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black,
                        modifier = Modifier.background(SystemYellow, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(detail, style = Subheadline, color = LabelSecondary)
        }
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) SystemYellow else LabelSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}
