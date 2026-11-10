package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacobusoettle.satmap.core.TestBuildSwitch
import com.jacobusoettle.satmap.ui.groups.openLink

/** iOS's title2, bold: the lock's title. */
private val LockTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)

/** The full-screen lock shown while test builds are switched off (`TestBuildGate`), as the iPhone's
 *  `TestBuildLockView`: the title, the message and the buttons for the links the file allowed (Google Play, then the
 *  website). The map screen shows it alone (`MapScreen`), so nothing behind it can be seen, touched or reached with a
 *  keyboard or TalkBack, and it can't be dismissed; Back leaves SatMap, as on the bare map. The text block sits in the
 *  middle of the space above the buttons; a long message or a large font scrolls, so the buttons are always reachable. */
@Composable
fun TestBuildLockScreen(lock: TestBuildSwitch.Lock) {
    val context = LocalContext.current
    BoxWithConstraints(
        Modifier
            .testTag("testBuildLock")
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // At least the screen's height, for the spacing; more when the texts need it, which then scroll.
                .heightIn(min = maxHeight)
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Three parts spaced evenly: nothing at the top, the texts, the buttons at the bottom (iOS's two Spacers).
            Column {}
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = SystemYellow, modifier = Modifier.size(56.dp))
                Text(
                    lock.title,
                    style = LockTitle,
                    color = LabelPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("testBuildLockTitle"),
                )
                Text(
                    lock.message,
                    style = Body,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("testBuildLockMessage"),
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (button in lock.buttons) {
                    LockButton(button) { openLink(context, button.url) }
                }
            }
        }
    }
}

/** A store button in yellow, the website in a light grey, as the iPhone's. */
@Composable
private fun LockButton(button: TestBuildSwitch.Button, onClick: () -> Unit) {
    val website = button.kind == TestBuildSwitch.Button.Kind.website
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (website) Color.White.copy(alpha = 0.14f) else SystemYellow,
            contentColor = if (website) Color.White else Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = Modifier.fillMaxWidth().testTag("testBuildLink-" + button.kind.name),
    ) {
        Text(button.title, style = Headline, textAlign = TextAlign.Center)
    }
}
