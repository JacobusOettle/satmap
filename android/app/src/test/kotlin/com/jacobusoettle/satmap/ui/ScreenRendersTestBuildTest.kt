package com.jacobusoettle.satmap.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.TestBuildSwitch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The test-build lock screen (`TestBuildLockScreen`) drawn into app/build/renders (see `render`), to compare with the
 *  iPhone's screenshot 41. It checks that the title, message and the Google Play and Website buttons are there. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersTestBuildTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testBuildOff() {
        val lock = TestBuildSwitch.Lock(
            title = "This test version of SatMap has been switched off",
            message = "Get SatMap from Google Play to keep using it.",
            appStoreURL = "https://apps.apple.com/app/satmap/id0000000000",
            playStoreURL = "https://play.google.com/store/apps/details?id=com.jacobusoettle.satmap",
            websiteURL = "https://github.com/JacobusOettle/satmap",
        )
        compose.setContent {
            SatMapTheme { TestBuildLockScreen(lock) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLockTitle").assertExists()
        compose.onNodeWithTag("testBuildLockMessage").assertExists()
        compose.onNodeWithTag("playStoreButton").assertExists()
        compose.onNodeWithTag("websiteButton").assertExists()
        compose.render("41-test-build-off")
    }
}
