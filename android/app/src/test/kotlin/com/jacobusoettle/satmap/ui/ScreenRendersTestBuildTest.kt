package com.jacobusoettle.satmap.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.TestBuildSwitch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The test-build lock screen (`TestBuildLockScreen`) drawn into app/build/renders (see `render`), to compare with the
 *  iPhone's screenshot 41: the sample's texts with the Google Play and Website buttons. A file's longest message at
 *  twice the font size on a small phone scrolls, so its buttons can still be reached. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersTestBuildTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun lock(status: TestBuildSwitch.Status) =
        TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, status, TestBuildSwitch.Platform.android)!!

    @Test
    @Config(qualifiers = IPHONE_SCREEN)
    fun testBuildOff() {
        compose.setContent {
            SatMapTheme { TestBuildLockScreen(lock(TestBuildSwitch.sample)) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLockTitle").assertTextEquals(TestBuildSwitch.defaultTitle)
        compose.onNodeWithTag("testBuildLockMessage").assertTextEquals(TestBuildSwitch.sample.message!!)
        compose.onNodeWithTag("testBuildLink-googlePlay").assertTextEquals("Get it on Google Play")
        compose.onNodeWithTag("testBuildLink-website").assertTextEquals("Website")
        compose.onNodeWithTag("testBuildLink-appStore").assertDoesNotExist()
        // The buttons at the bottom, the website last, as on the iPhone.
        val play = compose.onNodeWithTag("testBuildLink-googlePlay").getUnclippedBoundsInRoot()
        val website = compose.onNodeWithTag("testBuildLink-website").getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(website.top > play.bottom)
        assertTrue(website.bottom > root.bottom * 0.8f)
        compose.render("41-test-build-off")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun theLongestTextsAtTwiceTheFontSizeScrollToTheButtons() {
        val longest = TestBuildSwitch.Status(
            on = false,
            title = "T".repeat(TestBuildSwitch.maxTitleLength / 2) + " " + "t".repeat(TestBuildSwitch.maxTitleLength / 2 - 1),
            message = "Thanks for testing Spoor. ".repeat(40).take(TestBuildSwitch.maxMessageLength),
            playStoreURL = TestBuildSwitch.sample.playStoreURL,
            websiteURL = TestBuildSwitch.sample.websiteURL,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SatMapTheme { TestBuildLockScreen(lock(longest)) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLink-website").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("testBuildLink-googlePlay").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("testBuildLockTitle").performScrollTo().assertIsDisplayed()
        compose.render("41-test-build-off-long")
    }
}
