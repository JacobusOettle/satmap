package com.jacobusoettle.satmap.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.groups.AfterPro
import com.jacobusoettle.satmap.groups.GroupsModel
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.ui.groups.GroupItemSheet
import com.jacobusoettle.satmap.ui.groups.GroupsSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The groups screens drawn as the iPhone screenshots 28–40 show them (docs/groups/SPEC.md §9.7), in demo mode, into
 *  app/build/renders (see `render`), to compare with docs/screenshots side by side. Robolectric can't draw MapLibre, so
 *  for the map shots 36 and 40 only the overlays show (the Groups button, the banner); 37 and 38 are the sheets over
 *  the map. They check only that each screen is the one meant. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersGroupsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = 431.0
    }
    private lateinit var model: AppModel
    private val groups: GroupsModel get() = model.groups

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        Defaults.prefs.edit().clear().apply()
        model = AppModel(app, noHeights)
        groups.useDemo()
        waitFor { groups.groups.value.size == 2 }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
        Defaults.prefs.edit().clear().apply()
    }

    /** Runs what the hub posted to the main thread until `done`. */
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

    private val kruger: String get() = groups.groups.value.first { it.name == "Kruger trip" }.groupId
    private val drone: String get() = groups.groups.value.first { it.name == "Drone team" }.groupId

    private fun sheet(vararg routes: GroupsRoute) {
        if (routes.isNotEmpty()) groups.open(routes.toList())
        compose.setSheet { GroupsSheet(model, close = {}) }
        settle()
    }

    private fun mapScreen() {
        compose.setContent {
            SatMapTheme { Box(Modifier.fillMaxSize().background(Color(0xFF4F5931))) { MapScreen(model, model.offline, model.recorder, map = null) } }
        }
        settle()
    }

    /** You at the walkthrough's farm, where the demo's members are. */
    private fun atTheFarm() {
        model.recorder.setForTests(GpsFix(-33.921765, 18.923779, 5.0, 431.0, 3.0, 1_800_000_000.0, SystemClock.elapsedRealtime() / 1000.0))
    }

    @Test
    fun terms28() {
        sheet()
        compose.onNodeWithText("See your group on the map").assertExists()
        compose.render("28-groups-terms")
    }

    @Test
    fun list29() {
        groups.acceptTerms()
        sheet()
        compose.onNodeWithText("Kruger trip").assertExists()
        compose.onNodeWithText("Drone team").assertExists()
        compose.render("29-groups-list")
    }

    @Test
    fun paywall30() {
        groups.acceptTerms()
        sheet(GroupsRoute.List, GroupsRoute.Paywall(AfterPro.Create))
        waitFor { compose.onAllNodesText("R399.99 a year") }
        compose.onNodeWithText("Save 58%").assertExists()
        compose.render("30-paywall")
    }

    @Test
    fun createGroup31() {
        groups.acceptTerms()
        sheet(GroupsRoute.List, GroupsRoute.Create)
        compose.onNodeWithTag("groupName").performTextInput("Weekend braai")
        compose.onNodeWithTag("displayName").performTextClearance()
        compose.onNodeWithTag("displayName").performTextInput("Sam")
        settle()
        compose.render("31-create-group")
    }

    @Test
    fun groupDetail32() {
        groups.acceptTerms()
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger))
        waitFor { compose.onAllNodesText("MEMBERS (5)") }
        compose.render("32-group-detail")
    }

    @Test
    fun inviteQr33() {
        groups.acceptTerms()
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger), GroupsRoute.Invite(kruger, null))
        waitFor { compose.onAllNodesText("Share link") }
        compose.onNodeWithTag("inviteQR").assertExists()
        compose.render("33-invite-qr")
    }

    @Test
    fun joinGroup34() {
        groups.acceptTerms()
        val link = (InviteLink.parse(GroupDemo.inviteLink, GroupDemo.domain) as InviteLink.Parsed.Invite).link
        sheet(GroupsRoute.List, GroupsRoute.JoinLink, GroupsRoute.Join(link))
        waitFor { compose.onAllNodesText("Weekend ride") }
        compose.onNodeWithTag("joinGroup").assertExists()
        compose.render("34-join-group")
    }

    @Test
    fun shareConsent35() {
        groups.acceptTerms()
        val link = (InviteLink.parse(GroupDemo.inviteLink, GroupDemo.domain) as InviteLink.Parsed.Invite).link
        var joined: String? = null
        CoroutineScope(Dispatchers.Main).launch { joined = groups.join(link, "Sam") }
        waitFor { joined != null }
        sheet(GroupsRoute.List, GroupsRoute.Consent(joined!!, afterCreate = true))
        compose.onNodeWithTag("consentShare").assertExists()
        compose.render("35-share-consent")
    }

    @Test
    fun groupMap36() {
        atTheFarm()
        groups.setShown(kruger, true)
        waitFor { groups.shown.value != null }
        mapScreen()
        compose.onNodeWithTag("groupsButton").assertExists()
        compose.render("36-group-map")
    }

    @Test
    fun dropPin37() {
        atTheFarm()
        groups.setShown(kruger, true)
        waitFor { groups.shown.value != null }
        compose.setSheet { GroupItemSheet(model, GroupsRoute.DropPin(kruger, -33.9190, 18.9220), close = {}) }
        settle()
        compose.onNodeWithTag("pinType-animal").performClick()
        settle()
        compose.onNodeWithTag("pinAnimal").performTextInput("Rhino")
        settle()
        compose.render("37-drop-pin")
    }

    @Test
    fun pinDetail38() {
        groups.setShown(kruger, true)
        waitFor { groups.mapContent()?.pins?.any { it.label?.startsWith("Kudu") == true } == true }
        val kudu = groups.mapContent()!!.pins.first { it.label?.startsWith("Kudu") == true }
        compose.setSheet { GroupItemSheet(model, GroupsRoute.Pin(kruger, kudu.pinId), close = {}) }
        settle()
        compose.onNodeWithText("Three bulls").assertExists()
        compose.render("38-pin-detail")
    }

    @Test
    fun memberDetail39() {
        groups.acceptTerms()
        val thandi = groups.hub.detail(kruger)!!.members.first { it.name == "Thandi" }
        sheet(GroupsRoute.List, GroupsRoute.Detail(kruger), GroupsRoute.Member(kruger, thandi.memberId))
        compose.onNodeWithText("Admin").assertExists()
        compose.render("39-member-detail")
    }

    @Test
    fun groupPaused40() {
        atTheFarm()
        groups.setShown(drone, true)
        waitFor { groups.shown.value?.isPaused == true }
        mapScreen()
        compose.onNodeWithTag("groupPausedHint").assertExists()
        compose.render("40-group-paused")
    }

    /** Whether a text shows yet (for `waitFor`). */
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesText(text: String): Boolean {
        waitForIdle()
        return onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
}
