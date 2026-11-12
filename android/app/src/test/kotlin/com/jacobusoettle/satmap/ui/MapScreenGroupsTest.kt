package com.jacobusoettle.satmap.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.location.GpsFix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The map screen's groups pieces (docs/groups/SPEC.md §9.1), in demo mode without a map: the Groups button at the
 *  top of the buttons on the right (in columns side by side where one doesn't fit); yellow while a group is shown; the
 *  paused banner; and the drop-pin capsule over a tapped spot. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenGroupsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        Defaults.prefs.edit().clear().apply()
        model = AppModel(app, object : ElevationSource {
            override suspend fun elevation(p: GeoPoint): Double? = null
        })
        model.groups.useDemo()
        waitFor { model.groups.groups.value.size == 2 }
        compose.setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder, map = null) } }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
        Defaults.prefs.edit().clear().apply()
    }

    private fun waitFor(done: () -> Boolean) {
        for (i in 0 until 400) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("timed out", done())
    }

    private fun id(name: String) = model.groups.groups.value.first { it.name == name }.groupId

    @Test
    fun theGroupsButtonIsTheTopButtonOfTheRightHandColumn() {
        val groups = compose.onNodeWithTag("groupsButton").getUnclippedBoundsInRoot()
        // No map here, so no compass slot above it: Groups, then the terrain button 12 dp under it, at the right edge.
        val terrain = compose.onNodeWithTag("terrainButton").getUnclippedBoundsInRoot()
        assertEquals(terrain.left, groups.left)
        assertEquals(terrain.top, groups.bottom + 12.dp)
        assertEquals(50.dp, groups.right - groups.left)
        assertEquals(402.dp - 12.dp, groups.right)
        assertEquals(
            "Groups",
            compose.onNodeWithTag("groupsButton").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
        )
    }

    @Test
    @Config(qualifiers = "w592dp-h336dp") // a 360 × 640 dp phone on its side, less its status bar
    fun whereTheColumnDoesntFitTheButtonsGoInColumnsBetweenThePanelsAndTheTappedSpot() {
        model.recorder.setForTests(GpsFix(-33.921765, 18.923779, 5.0, 431.0, 3.0, 1_800_000_000.0, SystemClock.elapsedRealtime() / 1000.0))
        model.groups.setShown(id("Drone team"), true)
        waitFor { model.groups.shown.value != null }
        model.setSelectedPoint(GeoPoint(-33.919, 18.922))
        compose.waitForIdle()
        val tags = listOf("groupsButton", "terrainButton", "blocksButton", "offlineButton", "goToButton", "measureButton", "myLocationButton")
        val buttons = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        // Under the paused banner, over the tapped spot, inside the screen's 12 dp margin.
        val top = compose.onNodeWithTag("groupPausedHint").getUnclippedBoundsInRoot().bottom
        val bottom = compose.onNodeWithTag("tappedHeight").getUnclippedBoundsInRoot().top
        for ((i, b) in buttons.withIndex()) {
            assertEquals(tags[i], 50.dp, b.right - b.left)
            assertEquals(tags[i], 50.dp, b.bottom - b.top)
            assertTrue(tags[i], b.top >= top && b.bottom < bottom && b.right <= 592.dp - 12.dp)
            // 12 dp or more from every other.
            for (o in buttons.take(i)) {
                assertTrue(tags[i], b.left >= o.right + 12.dp || o.left >= b.right + 12.dp || b.top >= o.bottom + 12.dp || o.top >= b.bottom + 12.dp)
            }
        }
        // My location at the bottom right, Groups at the top left.
        val myLocation = buttons.last()
        assertEquals(592.dp - 12.dp, myLocation.right)
        assertEquals(buttons.maxOf { it.bottom }, myLocation.bottom)
        assertEquals(buttons.minOf { it.left }, buttons[0].left)
        assertEquals(buttons.minOf { it.top }, buttons[0].top)
    }

    @Test
    @Config(qualifiers = "ar-ldrtl-w592dp-h336dp") // Arabic: Robolectric turns the views right to left only for an RTL locale
    fun inARightToLeftLanguageTheGridIsMirroredWithTheOverlay() {
        val tags = listOf("groupsButton", "terrainButton", "blocksButton", "offlineButton", "goToButton", "measureButton", "myLocationButton")
        val buttons = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        // The buttons at the left edge (the legends at the right), My location at the bottom left, Groups at the top
        // right, as iOS's Layout mirrors.
        val myLocation = buttons.last()
        assertEquals(12.dp, myLocation.left)
        assertEquals(buttons.maxOf { it.bottom }, myLocation.bottom)
        assertEquals(buttons.maxOf { it.right }, buttons[0].right)
        assertEquals(buttons.minOf { it.top }, buttons[0].top)
        assertTrue(buttons[0].left > myLocation.right)
    }

    @Test
    fun aTapOpensTheGroupsSheet() {
        compose.onNodeWithTag("groupsButton").performClick()
        assertEquals(ActiveSheet.groups, model.sheet.value)
        compose.waitForIdle()
        compose.onNodeWithText("See your group on the map").assertExists()
    }

    @Test
    fun aPausedShownGroupShowsItsBannerWhichOpensIt() {
        model.groups.acceptTerms()
        model.groups.setShown(id("Drone team"), true)
        waitFor { model.groups.shown.value != null }
        compose.waitForIdle()
        compose.onNodeWithText("Drone team is paused: the owner's Spoor Pro has ended.").assertExists()
        // Its close button reads "Hide" (as VoiceOver's).
        compose.onNodeWithContentDescription("Hide").assertExists()
        compose.onNodeWithTag("groupPausedHint").performClick()
        compose.waitForIdle()
        assertEquals(ActiveSheet.groups, model.sheet.value)
        compose.onNodeWithTag("screenTitle").assertTextEquals("Drone team")
        // S5 alone: "Done" closes the sheet, there is no "Back" to the list.
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(null, model.sheet.value)
    }

    @Test
    fun theBannersCloseButtonHidesIt() {
        model.groups.setShown(id("Drone team"), true)
        waitFor { model.groups.shown.value != null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("groupPausedHint").assertDoesNotExist()
    }

    @Test
    fun aTappedSpotOffersToDropAPinWhileAnActiveGroupIsShown() {
        model.setSelectedPoint(GeoPoint(-33.919, 18.922))
        compose.waitForIdle()
        compose.onNodeWithTag("dropPin").assertDoesNotExist()
        val kruger = id("Kruger trip")
        model.groups.setShown(kruger, true)
        waitFor { model.groups.shown.value != null }
        compose.waitForIdle()
        compose.onNodeWithTag("dropPin").performClick()
        assertEquals(GroupsRoute.DropPin(kruger, -33.919, 18.922), model.groups.groupItem.value)
    }
}
