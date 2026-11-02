package com.jacobusoettle.satmap.ui

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.groups.GroupsModel
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.ui.groups.GroupItemSheet
import com.jacobusoettle.satmap.ui.groups.GroupsSheet
import com.jacobusoettle.satmap.ui.groups.LOCATION_OFF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the groups screens do on a tap, in demo mode (docs/groups/SPEC.md §9.3): the name typed in S4 kept for S8,
 *  S5's "Invite someone" making the invite before S6 opens, S5's sharing switch with location off, S8's button while
 *  it joins, S10's animal checked on the tap. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
class GroupScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var model: AppModel
    private val groups: GroupsModel get() = model.groups

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
        groups.useDemo()
        waitFor { groups.groups.value.size == 2 }
        groups.acceptTerms()
    }

    @After
    fun tearDown() {
        groups.demoServer?.offline = false
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

    private fun settle() {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    private fun shows(text: String): Boolean {
        settle()
        return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private val kruger: String get() = groups.groups.value.first { it.name == "Kruger trip" }.groupId

    /** Scrolls the screen's list to the row tagged `tag` and returns it. */
    private fun row(tag: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

    private fun sheet(vararg routes: GroupsRoute) {
        groups.open(routes.toList())
        compose.setSheet { GroupsSheet(model, close = {}) }
        settle()
    }

    @Test
    fun theNameTypedInCreateGroupPrefillsTheJoinConfirmation() {
        sheet(GroupsRoute.List, GroupsRoute.Create)
        compose.onNodeWithTag("displayName").performTextClearance()
        compose.onNodeWithTag("displayName").performTextInput("Sam")
        settle()
        // Kept as it is typed, though no group is made (the walkthrough cancels S4).
        assertEquals("Sam", groups.lastDisplayName)
        val link = (InviteLink.parse(GroupDemo.inviteLink, GroupDemo.domain) as InviteLink.Parsed.Invite).link
        groups.open(listOf(GroupsRoute.List, GroupsRoute.Join(link)))
        waitFor { shows("Weekend ride") }
        compose.onNodeWithTag("displayName").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Sam")))
        compose.onNodeWithTag("joinGroup").assertIsEnabled()
        compose.onNodeWithTag("joinGroup").assert(hasText("Join group"))
    }

    @Test
    fun inviteSomeoneMakesTheInviteThenShowsIt() {
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger))
        row("inviteSomeone").performClick()
        waitFor { shows("Share link") }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Invite")
        compose.onNodeWithTag("inviteQR").assertExists()
    }

    @Test
    fun anInviteThatCantBeMadeSaysSoOnTheGroupsScreen() {
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger))
        groups.demoServer!!.offline = true
        row("inviteSomeone").performClick()
        waitFor { shows("Couldn't make an invite. Check your connection and try again.") }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Kruger trip")
        compose.onNodeWithText("OK").performClick()
        settle()
        assertFalse(shows("Couldn't make an invite. Check your connection and try again."))
    }

    @Test
    fun sharingSwitchedOnWithLocationOffSaysSoAndStaysOff() {
        model.recorder.setForTests(null, authorization = LocationRecorder.Authorization.denied)
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger))
        // Kruger trip shares (consent recorded): off first, keeping the trail.
        row("shareLocation").performClick()
        waitFor { shows("Keep trail") }
        compose.onNodeWithText("Keep trail").performClick()
        settle()
        assertFalse(groups.hub.detail(kruger)!!.isSharing)
        compose.onNodeWithTag("shareLocation").performClick()
        waitFor { shows(LOCATION_OFF) }
        assertFalse(groups.hub.detail(kruger)!!.isSharing)
        val on = compose.onNodeWithTag("shareLocation").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        assertEquals(ToggleableState.Off, on)
        compose.onNodeWithText("OK").performClick()
        settle()
        // Allowed: it switches on.
        model.recorder.setForTests(null, authorization = LocationRecorder.Authorization.authorized)
        compose.onNodeWithTag("shareLocation").performClick()
        settle()
        assertTrue(groups.hub.detail(kruger)!!.isSharing)
    }

    @Test
    fun anAnimalTooLongIsCheckedOnTheTap() {
        groups.setShown(kruger, true)
        compose.setSheet { GroupItemSheet(model, GroupsRoute.DropPin(kruger, -33.9190, 18.9220), close = {}) }
        settle()
        compose.onNodeWithTag("pinType-animal").performClick()
        settle()
        compose.onNodeWithTag("pinAnimal").performTextInput("x".repeat(41))
        settle()
        row("dropPinConfirm").assertIsEnabled().performClick()
        waitFor { shows("Type the animal, up to 40 characters.") }
    }
}
