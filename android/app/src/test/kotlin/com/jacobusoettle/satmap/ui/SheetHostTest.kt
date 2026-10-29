package com.jacobusoettle.satmap.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sheets' chrome and navigation (DESIGN.md §4.2, UI spec §3.0): a sheet shows its screen with iOS's bar, Done
 *  slides it away before it is cleared, the model clearing it keeps the content while it goes, and a screen under
 *  another keeps its state (§8.1: the back stack keeping route state). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class SheetHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSheetShowsItsScreenAndDoneSlidesItAwayBeforeItIsCleared() {
        var shown by mutableStateOf<String?>(null)
        var dismissed = 0
        compose.setContent {
            SatMapTheme {
                SheetHost(shown, onDismiss = { dismissed++; shown = null }) { title ->
                    SheetScreen(ScreenChrome(title, ScreenChrome.Leading.Done(close))) {
                        item { FormSection(first = true) { FormValueRow("Saved", "68.4 MB") } }
                    }
                }
            }
        }
        compose.onNodeWithTag("screenTitle").assertDoesNotExist()
        shown = "Blocks"
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Blocks")
        assertTrue(compose.onNodeWithTag("screenTitle").fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        compose.onNodeWithText("68.4 MB").assertExists()

        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("Done") and hasClickAction()).performClick()
        compose.mainClock.advanceTimeByFrame()
        // Still there while it slides down; cleared once it is down.
        assertEquals(0, dismissed)
        compose.onNodeWithTag("screenTitle").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertNull(shown)
        compose.onNodeWithTag("screenTitle").assertDoesNotExist()
    }

    @Test
    fun whenTheItemIsClearedTheLastScreenStaysWhileTheSheetSlidesAway() {
        var shown by mutableStateOf<String?>("Go to coordinates")
        var dismissed = 0
        compose.setContent {
            SatMapTheme {
                SheetHost(shown, onDismiss = { dismissed++ }) { title ->
                    SheetScreen(ScreenChrome(title, ScreenChrome.Leading.Cancel(close))) {}
                }
            }
        }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Go to coordinates")
        compose.onNode(hasText("Cancel") and hasClickAction()).assertExists()

        compose.mainClock.autoAdvance = false
        shown = null
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Go to coordinates")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertDoesNotExist()
        // The model cleared it: nothing to tell it.
        assertEquals(0, dismissed)

        // Shown again later: a sheet of its own.
        shown = "Blocks"
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Blocks")
    }

    @Test
    fun theBarHasItsTrailingButton() {
        var taps = 0
        compose.setContent {
            SatMapTheme {
                SheetScreen(
                    ScreenChrome("Blocks", ScreenChrome.Leading.Done {}, trailing = {
                        Button({ taps++ }, Modifier.testTag("importKml")) { Text("+") }
                    }),
                ) {
                    items(40) { FormSection { FormValueRow("Row $it", "") } }
                }
            }
        }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Blocks")
        compose.onNodeWithTag("importKml").performClick()
        assertEquals(1, taps)
    }

    /** A test sheet's screens: the root holds what is typed in it; a detail screen can be pushed over it. */
    private sealed interface Route {
        val title: String

        class Root : Route {
            override val title = "Offline maps"
            var text by mutableStateOf("")
        }

        class Detail(override val title: String) : Route
    }

    @Test
    fun aScreenUnderAnotherKeepsItsStateAndBackGoesBackToIt() {
        val root = Route.Root()
        val stack = RouteStack<Route>(root)
        lateinit var rootList: LazyListState
        compose.setContent {
            SatMapTheme {
                SheetStack(stack) { r, below ->
                    val back = below?.let { ScreenChrome.Leading.Back(it.title, stack::pop) }
                    when (r) {
                        is Route.Root -> {
                            // Remembered by the screen itself, not its route: kept too.
                            var taps by rememberSaveable { mutableIntStateOf(0) }
                            rootList = rememberLazyListState()
                            SheetScreen(ScreenChrome(r.title, back ?: ScreenChrome.Leading.Done {}), rootList) {
                                item {
                                    Column {
                                        TextField(r.text, { r.text = it }, Modifier.testTag("rootText"))
                                        Button({ taps++ }, Modifier.testTag("taps")) { Text("Tapped $taps") }
                                        Button({ stack.push(Route.Detail("Find a place")) }) { Text("Open") }
                                    }
                                }
                                items(40) { Text("Row $it") }
                            }
                        }
                        is Route.Detail -> SheetScreen(ScreenChrome(r.title, back!!)) {
                            item { Button({ stack.push(Route.Detail("Download area")) }) { Text("Deeper") } }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("rootText").performTextInput("Sabi Sand")
        compose.onNodeWithTag("taps").performClick()
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
        compose.onNodeWithTag("back")
            .assertContentDescriptionEquals("Back")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        // The back button is labelled with the screen under it.
        assertEquals("Offline maps", stack.below(stack.top)?.title)
        assertEquals(2, stack.size)

        compose.onNodeWithTag("back").performClick()
        compose.waitForIdle()
        assertEquals(1, stack.size)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
        compose.onNodeWithTag("rootText").assertTextContains("Sabi Sand")
        compose.onNodeWithTag("taps").assertTextEquals("Tapped 1")

        // Scrolled down, then a screen pushed and popped with system back: still scrolled.
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { rootList.scrollToItem(20) } }
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(Route.Detail("Find a place")) }
        compose.waitForIdle()
        compose.onNodeWithText("Deeper").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Download area")
        assertEquals("Find a place", stack.below(stack.top)?.title)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
        assertEquals(20, rootList.firstVisibleItemIndex)
        assertTrue(stack.routes.single() === root)
    }

    /** Two screens to go through: each has a link to the next. */
    private fun setLinkedScreens(stack: RouteStack<Route>) {
        compose.setContent {
            SatMapTheme {
                SheetStack(stack) { r, below ->
                    val back = below?.let { ScreenChrome.Leading.Back(it.title, stack::pop) }
                    SheetScreen(ScreenChrome(r.title, back ?: ScreenChrome.Leading.Done {})) {
                        item {
                            FormSection(first = true) {
                                when (r) {
                                    is Route.Root -> FormLinkRow("Open") { stack.push(Route.Detail("Find a place")) }
                                    is Route.Detail -> FormLinkRow("Deeper") { stack.push(Route.Detail("Download area")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun link(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test
    fun aSecondTapWhileAScreenSlidesInOpensItOnce() {
        val stack = RouteStack<Route>(Route.Root())
        setLinkedScreens(stack)
        compose.mainClock.autoAdvance = false
        link("Open").performClick()
        compose.mainClock.advanceTimeBy(100)
        // The first screen is still sliding away: a tap on it does nothing, as iOS ignores touches during the slide.
        link("Open").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, stack.size)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
        assertEquals("Offline maps", stack.below(stack.top)?.title)

        // Once it is in, its own link works.
        link("Deeper").performClick()
        compose.waitForIdle()
        assertEquals(3, stack.size)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Download area")
    }

    @Test
    fun aTapOnAScreenSlidingAwayDoesNothing() {
        val stack = RouteStack<Route>(Route.Root())
        setLinkedScreens(stack)
        link("Open").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("back").performClick()
        compose.mainClock.advanceTimeBy(100)
        link("Deeper").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, stack.size)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
    }

    @Test
    fun aSecondTapOnBackWhileTheScreenSlidesAwayGoesBackOnce() {
        val stack = RouteStack<Route>(Route.Root())
        setLinkedScreens(stack)
        link("Open").performClick()
        compose.waitForIdle()
        link("Deeper").performClick()
        compose.waitForIdle()
        assertEquals(3, stack.size)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("back").performClick()
        compose.mainClock.advanceTimeBy(100)
        // Both back buttons are there while the screens slide: neither takes the tap.
        compose.onAllNodesWithTag("back").onFirst().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onAllNodesWithTag("back").onLast().performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, stack.size)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
    }
}
