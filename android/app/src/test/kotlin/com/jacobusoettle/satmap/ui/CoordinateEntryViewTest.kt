package com.jacobusoettle.satmap.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Go to coordinates" (CoordinateEntryView.swift, screenshot 21; UI spec §3.10): how the text is read as it is typed,
 *  the rows, Paste, and "Show and measure from here". */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class CoordinateEntryViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel
    private var closed = 0

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
        compose.setContent { SatMapTheme { CoordinateEntryView(model, close = { closed++ }) } }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    private val field get() = compose.onNodeWithTag("coordinateText")
    private val show get() = compose.onNode(hasText("Show and measure from here") and hasClickAction())

    @Test
    fun aPositionInAnyFormatIsReadAsItIsTyped() {
        compose.onNodeWithTag("screenTitle").assertTextEquals("Go to coordinates")
        compose.onNodeWithText("Paste or type coordinates").assertExists()
        compose.onNodeWithText("The position shows here as you type or paste.").assertExists()
        compose.onNodeWithText("Any format:", substring = true).assertExists()
        show.assertIsNotEnabled()

        // Screenshot 21.
        field.assert(hasSetTextAction()).performTextInput("33°55'10\"S 18°55'20\"E")
        compose.onNodeWithText("Position").assertExists()
        compose.onNodeWithText("-33.919444, 18.922222").assertExists()
        compose.onNodeWithText("Read as").assertExists()
        compose.onNodeWithText("degrees, minutes and seconds").assertExists()
        // No position of yours yet: no distance from you.
        compose.onNodeWithText("From you").assertDoesNotExist()
        compose.onNodeWithText("The position shows here as you type or paste.").assertDoesNotExist()
        show.assertIsEnabled()

        field.performTextClearance()
        field.performTextInput("Camp 1 25 05.8 S, 31 26.2 E")
        compose.onNodeWithText("Not a position yet.").assertExists()
        show.assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("  \n ")
        compose.onNodeWithText("The position shows here as you type or paste.").assertExists()
    }

    @Test
    fun showAndMeasureClosesTheSheet() {
        field.performTextInput("-34.093892, 18.620506")
        show.performClick()
        assertEquals(1, closed)
    }

    @Test
    fun cancelClosesTheSheet() {
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun theFieldHasTheFocusOnOpening() {
        compose.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun pasteTakesTheTextOnTheClipboard() {
        val paste = compose.onNode(hasText("Paste") and hasClickAction())
        paste.assertIsNotEnabled()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", "-25.0969, 31.4371")) }
        compose.waitForIdle()
        paste.assertIsEnabled().performClick()
        field.assertTextEquals("-25.0969, 31.4371")
        compose.onNodeWithText("-25.096900, 31.437100").assertExists()
    }
}
