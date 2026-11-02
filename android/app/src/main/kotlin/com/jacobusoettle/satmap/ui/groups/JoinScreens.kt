package com.jacobusoettle.satmap.ui.groups

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.GroupActionError
import com.jacobusoettle.satmap.core.GroupText
import com.jacobusoettle.satmap.core.GroupTextField
import com.jacobusoettle.satmap.core.InvitePreview
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.groups.QRScannerView
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Footnote
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormTextFieldRow
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetPlainScreen
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.SystemOrange
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import com.jacobusoettle.satmap.ui.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** S7 Join with an invite link (docs/groups/SPEC.md §9.3): paste the link (or the whole message holding it), then
 *  Continue to S8; or scan the QR code (S17). */
@Composable
fun JoinLinkScreen(model: AppModel, nav: GroupsNav, below: GroupsRoute?) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { InviteLink.parse(text, GroupConfig.domain) }
    val problem = GroupTexts.linkProblem(parsed, text)
    SheetScreen(ScreenChrome("Join a group", leading(model, nav, below))) {
        item {
            Column {
                FormSection(first = true, footer = "Invite links start with https://${GroupConfig.domain}/j/") {
                    FormTextFieldRow(
                        text,
                        { text = it },
                        placeholder = "Paste the invite link",
                        modifier = Modifier.testTag("inviteText"),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        trailing = {
                            Text(
                                "Paste",
                                style = Body,
                                color = Accent,
                                modifier = Modifier.align(Alignment.Top).pressable {
                                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!pasted.isNullOrEmpty()) text = pasted
                                },
                            )
                        },
                    )
                    problem?.let { NoticeRow(it) }
                }
            }
        }
        item {
            FormSection {
                // S8 opens in its own sheet, over this one (as a link from another app).
                ButtonRow("Continue", enabled = parsed is InviteLink.Parsed.Invite, tag = "continueJoin", style = Headline) {
                    model.groups.openInvite(parsed)
                }
                ButtonRow("Scan QR code", icon = GroupSymbols.qrViewfinder, tag = "scanQR") { nav.push(GroupsRoute.Scan) }
            }
        }
    }
}

/** What S8 shows while it checks the invite, and after. */
private sealed interface Checked {
    data object Checking : Checked
    data class Ready(val preview: InvitePreview) : Checked
    data class Failed(val error: GroupActionError?, val text: String) : Checked
}

/** S8 Join confirmation (§9.3, §5.6): the invite checked (E9), the group, who invited you and how many are in it, your
 *  name, then "Join group". Joining makes it the shown group; then S9 and S5 in the groups sheet (§9.2). Opening a
 *  link never joins by itself. */
@Composable
fun JoinScreen(model: AppModel, route: GroupsRoute.Join, nav: GroupsNav) {
    val groups = model.groups
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf<Checked>(Checked.Checking) }
    var displayName by rememberSaveable { mutableStateOf(groups.lastDisplayName) }
    var busy by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<GroupActionError?>(null) }
    val link = route.link
    LaunchedEffect(link, attempt) {
        link ?: return@LaunchedEffect
        checked = Checked.Checking
        checked = try {
            groups.ready()
            Checked.Ready(groups.hub.preview(link))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Checked.Failed(e as? GroupActionError, errorText(e))
        }
    }
    val cancel = ScreenChrome.Leading.Cancel {
        groups.closeInvite()
        nav.pop()
    }
    SheetScreen(ScreenChrome("Join group", cancel)) {
        if (link == null) {
            item {
                FormSection(first = true) {
                    NoticeRow("This invite link is incomplete. Copy the whole link from the message, or scan the QR code.")
                }
            }
            return@SheetScreen
        }
        when (val c = checked) {
            Checked.Checking -> item { FormSection(first = true) { BusyRow("Checking the invite…") } }
            is Checked.Failed -> item {
                FormSection(first = true) {
                    NoticeRow(c.text)
                    when (val kind = c.error?.kind) {
                        is GroupActionError.Kind.AlreadyMember -> ButtonRow("Open group") {
                            groups.closeInvite()
                            groups.open(listOf(GroupsRoute.Detail(kind.groupId)))
                        }
                        GroupActionError.Kind.Offline, GroupActionError.Kind.Server, GroupActionError.Kind.RateLimited, null ->
                            ButtonRow("Try again") { attempt += 1 }
                        else -> {}
                    }
                }
            }
            is Checked.Ready -> {
                val p = c.preview
                item {
                    FormSection(first = true) {
                        FormRow(verticalPadding = 14.dp) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = LabelPrimary)
                                p.inviterName?.let { Text("Invited by $it", style = Body, color = LabelPrimary) }
                                Text(GroupTexts.members(p.memberCount), style = Body, color = LabelSecondary)
                                if (p.isPaused) Text("This group is paused at the moment.", style = Body, color = SystemOrange)
                            }
                        }
                    }
                }
                item {
                    FormSection(header = "Your name in the group") {
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
                        FormRow(verticalPadding = MultiLineRowPadding) {
                            Text(
                                "Members will see your name. If you share your location, they also see where you are and the trail you leave.",
                                style = Footnote,
                                color = LabelSecondary,
                            )
                        }
                        PrimaryButtonRow(
                            "Join group",
                            enabled = GroupText.clean(displayName, GroupTextField.displayName) != null,
                            busy = busy,
                            tag = "joinGroup",
                        ) {
                            joinError = null
                            busy = true
                            scope.launch {
                                try {
                                    val gid = groups.join(link, displayName)
                                    groups.open(listOf(GroupsRoute.List, GroupsRoute.Consent(gid, afterCreate = true)))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    joinError = (e as? GroupActionError) ?: GroupActionError(GroupActionError.Kind.Server)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        joinError?.let { e ->
                            NoticeRow(e.message)
                            val kind = e.kind
                            if (kind is GroupActionError.Kind.AlreadyMember) {
                                ButtonRow("Open group") {
                                    groups.closeInvite()
                                    groups.open(listOf(GroupsRoute.Detail(kind.groupId)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** S17 Scan QR code (§9.3, §5.3): the camera with a square guide; an invite found closes the scanner and opens S8.
 *  A code that is an incomplete invite link, or no invite link, says so in orange above the text (S7's words, as
 *  iOS; the spec is silent) and scanning goes on. The camera permission is asked on the first visit; without it, the
 *  way to Settings. */
@Composable
fun ScanScreen(model: AppModel, nav: GroupsNav) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        refused = !granted
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    var done by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    SheetPlainScreen(ScreenChrome("Scan QR code", ScreenChrome.Leading.Cancel(nav::pop))) {
        if (allowed) {
            Box(Modifier.fillMaxSize()) {
                QRScannerView(
                    onText = { text ->
                        if (done) return@QRScannerView
                        val parsed = InviteLink.parse(text, GroupConfig.domain)
                        if (parsed is InviteLink.Parsed.Invite) {
                            // The scanner closes; S8 opens in its own sheet.
                            done = true
                            nav.pop()
                            model.groups.openInvite(parsed)
                        } else {
                            hint = GroupTexts.linkProblem(parsed, text)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hint?.let {
                        Text(it, style = Body, color = SystemOrange, textAlign = TextAlign.Center, modifier = Modifier.testTag("scanHint"))
                    }
                    Text("Point the camera at the invite QR code.", style = Body, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        } else if (refused) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Camera access is off for SatMap. Turn it on in Settings to scan a code.",
                    style = Body,
                    color = LabelPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Open Settings",
                    style = Body,
                    color = Accent,
                    modifier = Modifier.pressable {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } catch (e: android.content.ActivityNotFoundException) {
                            // No settings page: the text says where to go.
                        }
                    },
                )
            }
        }
    }
}
