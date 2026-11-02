package com.jacobusoettle.satmap.ui.groups

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.MadeInvite
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.groups.QRCode
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.SharePresenter
import kotlinx.coroutines.CancellationException

/** S6 Invite (docs/groups/SPEC.md §9.3, §5.3): a new invite is made when the screen opens (E6); its QR code (240 dp,
 *  nearest-neighbour), the group's name, Share link (the system share sheet, with the share text) and Copy link (kept
 *  out of the clipboard preview: the link holds the group key). */
@Composable
fun InviteScreen(model: AppModel, route: GroupsRoute.Invite, nav: GroupsNav) {
    val groups = model.groups
    val context = LocalContext.current
    var made by remember { mutableStateOf(route.made) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        if (made != null) return@LaunchedEffect
        try {
            groups.ready()
            made = groups.hub.createInvite(route.groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2000)
            toast = null
        }
    }
    val invite = made
    Box {
        SheetScreen(ScreenChrome("Invite", ScreenChrome.Leading.Done(nav::pop))) {
            when {
                invite != null -> inviteItems(invite, share = {
                    SharePresenter.presentText(GroupTexts.shareText(invite.groupName, invite.link))
                }, copy = {
                    copyText(context, "Invite link", invite.link, sensitive = true)
                    toast = "Link copied"
                }, footer = GroupTexts.inviteFooter(
                    GroupTexts.date(invite.expiresAt),
                    GroupTexts.time(invite.expiresAt, DateFormat.is24HourFormat(context)),
                ))
                failed -> item {
                    FormSection(first = true) { TextRow("Couldn't make an invite. Check your connection and try again.") }
                }
                else -> item { FormSection(first = true) { BusyRow("Making an invite…") } }
            }
        }
        Toast(toast)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inviteItems(
    invite: MadeInvite,
    share: () -> Unit,
    copy: () -> Unit,
    footer: String,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val qr = remember(invite.link) { QRCode.bitmap(invite.link)?.asImageBitmap() }
            if (qr != null) {
                Image(
                    qr,
                    contentDescription = "Invite QR code",
                    filterQuality = FilterQuality.None,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .testTag("inviteQR"),
                )
            }
            Text(invite.groupName, style = Headline, color = LabelPrimary, textAlign = TextAlign.Center)
            Text(
                "Ask them to scan this code with their phone's camera, or send them the link.",
                style = Body,
                color = LabelSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
    item {
        FormSection(footer = footer) {
            ButtonRow("Share link", icon = Icons.Outlined.IosShare, tag = "shareInvite", onClick = share)
            ButtonRow("Copy link", icon = Icons.Outlined.ContentCopy, tag = "copyInvite", onClick = copy)
        }
    }
}

