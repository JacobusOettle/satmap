package com.jacobusoettle.satmap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacobusoettle.satmap.core.TestBuildSwitch
import com.jacobusoettle.satmap.ui.groups.openLink

/** The full-screen lock a test build shows while the remote switch is off (android/docs/spec/test-builds.md), drawn
 *  over everything so the map and any sheet are out of reach. There is no way to dismiss it; Back does nothing. It
 *  shows the title and message from the status file (or the defaults), a "Get it on Google Play" button when a Play
 *  link was given (the App Store button is the iPhone's), and a "Website" button when a website link was given. It
 *  never touches any saved data; when the switch is turned on again the app carries on where it left off. */
@Composable
fun TestBuildLockScreen(lock: TestBuildSwitch.Lock) {
    val context = LocalContext.current
    // Swallow Back: there is no way out while the test build is switched off.
    BackHandler(enabled = true) {}
    Column(
        Modifier
            .testTag("testBuildLock")
            .fillMaxSize()
            // An opaque background and a pointer sink so nothing behind the lock is seen or touched.
            .background(SheetBackground)
            .pointerInput(Unit) {}
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.size(20.dp))
        Text(
            lock.title,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = LabelPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("testBuildLockTitle"),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            lock.message,
            style = Body,
            color = LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("testBuildLockMessage"),
        )
        Spacer(Modifier.size(28.dp))
        // The App Store button is the iPhone's; Android shows Google Play and the website.
        lock.playStoreURL?.let { url ->
            StoreButton("Get it on Google Play", tag = "playStoreButton") { openLink(context, url) }
        }
        lock.websiteURL?.let { url ->
            if (lock.playStoreURL != null) Spacer(Modifier.size(12.dp))
            StoreButton("Website", tag = "websiteButton", filled = false) { openLink(context, url) }
        }
    }
}

@Composable
private fun StoreButton(title: String, tag: String, filled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = if (filled) {
            ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        } else {
            ButtonDefaults.buttonColors(containerColor = CellBackground, contentColor = Accent)
        },
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).testTag(tag),
    ) {
        Text(title, style = Headline)
    }
}
