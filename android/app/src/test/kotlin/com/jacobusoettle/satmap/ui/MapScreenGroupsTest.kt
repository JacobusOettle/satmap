package com.jacobusoettle.satmap.ui

import android.app.Application
import android.os.Looper
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
 *  left, level with the top of the buttons on the right and adding no height; yellow while a group is shown; the paused
 *  banner; and the drop-pin capsule over a tapped spot. */
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
    fun theGroupsButtonIsLevelWithTheTopOfTheRightHandButtons() {
        val groups = compose.onNodeWithTag("groupsButton").getUnclippedBoundsInRoot()
        // No map here, so no compass slot: the column starts with the terrain button.
        val terrain = compose.onNodeWithTag("terrainButton").getUnclippedBoundsInRoot()
        assertEquals(terrain.top, groups.top)
        assertEquals(50.dp, groups.right - groups.left)
        assertEquals(12.dp, groups.left)
        assertEquals(
            "Groups",
            compose.onNodeWithTag("groupsButton").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
        )
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
        compose.onNodeWithText("Drone team is paused: the owner's SatMap Pro has ended.").assertExists()
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
