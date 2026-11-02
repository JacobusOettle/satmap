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

/** S6 Invite (docs/groups/SPEC.md §9.3, §5.3): an invite S5 made (E6) or one this phone made before; its QR code
 *  (240 dp, nearest-neighbour), the group's name, Share link (the system share sheet, with the share text) and Copy
 *  link (kept out of the clipboard preview: the link holds the group key). */
@Composable
fun InviteScreen(route: GroupsRoute.Invite, nav: GroupsNav) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2000)
            toast = null
        }
    }
    val invite = route.made
    Box {
        SheetScreen(ScreenChrome("Invite", ScreenChrome.Leading.Done(nav::pop))) {
            inviteItems(invite, share = {
                SharePresenter.presentText(GroupTexts.shareText(invite.groupName, invite.link))
            }, copy = {
                copyText(context, "Invite link", invite.link, sensitive = true)
                toast = "Link copied"
            }, footer = GroupTexts.inviteFooter(
                GroupTexts.date(invite.expiresAt),
                GroupTexts.time(invite.expiresAt, DateFormat.is24HourFormat(context)),
            ))
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
        FormSection(first = true) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val qr = remember(invite.link) { QRCode.bitmap(invite.link)?.asImageBitmap() }
                if (qr != null) {
                    Image(
                        qr,
                        contentDescription = "Invite QR code",
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(240.dp).background(Color.White).testTag("inviteQR"),
                    )
                }
                Text(invite.groupName, style = Headline, color = LabelPrimary, textAlign = TextAlign.Center)
            }
            TextRow("Ask them to scan this code with their phone's camera, or send them the link.")
        }
    }
    item {
        FormSection(footer = footer) {
            ButtonRow("Share link", icon = Icons.Outlined.IosShare, tag = "shareInvite", onClick = share)
            ButtonRow("Copy link", icon = Icons.Outlined.ContentCopy, tag = "copyInvite", onClick = copy)
        }
    }
}

