package com.jacobusoettle.satmap.groups

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupActionError
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.PausedReason
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.ui.groups.blockable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** `GroupsModel` over the core's hub in demo mode (docs/groups/SPEC.md §8.15): what the screens and the map read
 *  (rows, the shown group, the notification's groups), invite links, terms, names, the camera fit, Pro, and the
 *  Stop action. */
@RunWith(AndroidJUnit4::class)
class GroupsModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel
    private val groups: GroupsModel get() = model.groups

    private class FakeMap : MapHost {
        override val centerCoordinate = GeoPoint(0.0, 0.0)
        override val isOnScreen = true
        val fits = mutableListOf<List<GeoPoint>>()
        override fun pointsApart(a: GeoPoint, b: GeoPoint) = 100.0
        override fun center(on: GeoPoint, animated: Boolean) {}
        override fun zoom(onto: GeoPoint, metersAcross: Double) {}
        override fun zoom(to: GeoBounds) {}
        override fun locationUpdated(fix: GpsFix) {}
        override fun headingUpdated(heading: Double?) {}
        override fun reloadImagery() {}
        override fun fit(points: List<GeoPoint>) {
            fits += points
        }
    }

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

    private fun waitFor(done: () -> Boolean) {
        for (i in 0 until 400) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("timed out", done())
    }

    private fun id(name: String) = groups.groups.value.first { it.name == name }.groupId

    @Test
    fun theDemoGroupsAreListedOldestFirstWithTheirState() {
        val list = groups.groups.value
        assertEquals(listOf("Kruger trip", "Drone team"), list.map { it.name })
        assertTrue(list[0].isSharing)
        assertFalse(list[0].isPaused)
        assertTrue(list[1].isPaused)
        assertNull("nothing is on the map at first (shots 01-27)", groups.shown.value)
        assertTrue(groups.isDemo)
    }

    @Test
    fun theShownGroupDrivesTheMapScreen() {
        groups.setShown(id("Drone team"), true)
        waitFor { groups.shown.value != null }
        val shown = groups.shown.value!!
        assertEquals("Drone team", shown.name)
        assertTrue(shown.isPaused)
        assertEquals(PausedReason.proEnded, shown.pausedReason)
        assertFalse("Pieter owns it", shown.isOwner)
        groups.setShown(id("Drone team"), false)
        waitFor { groups.shown.value == null }
    }

    @Test
    fun showingAGroupFitsTheMapToItsMembersAndYou() {
        val map = FakeMap()
        model.map = map
        model.recorder.setForTests(GpsFix(-33.92, 18.92, 5.0, null, null, 1_800_000_000.0, 0.0))
        groups.setShown(id("Kruger trip"), true)
        waitFor { map.fits.isNotEmpty() }
        val points = map.fits.single()
        // The four members' live positions (Sipho's is stale but under 24 h) and yours.
        assertEquals(5, points.size)
        assertTrue(points.contains(GeoPoint(-33.92, 18.92)))
    }

    @Test
    fun theNotificationNamesTheGroupsSharedWithAndStopEndsSharingEverywhere() {
        waitFor { groups.sharingNames.value == listOf("Kruger trip") }
        groups.stopSharingEverywhere()
        waitFor { groups.sharingNames.value.isEmpty() }
        assertFalse(groups.groups.value.first { it.name == "Kruger trip" }.isSharing)
    }

    @Test
    fun anInviteForAGroupYouAreInOpensItsScreen() {
        val kruger = id("Kruger trip")
        val made = groups.hub.detail(kruger)!!.invites
        assertNotNull(made)
        // An invite link to "Kruger trip" itself: the link the demo made.
        CoroutineScope(Dispatchers.Main).launch { groups.hub.invites(kruger) }
        waitFor { groups.hub.detail(kruger)?.invites?.firstOrNull()?.link != null }
        val link = groups.hub.detail(kruger)!!.invites!!.first().link!!
        groups.openInvite(InviteLink.parse(link, GroupConfig.domain))
        waitFor { groups.navigation.value != null }
        // S5 alone (its leading button is Done), and the alert with OK (§5.6).
        assertEquals(listOf(GroupsRoute.Detail(kruger)), groups.navigation.value!!.routes)
        assertEquals("You're already in Kruger trip.", groups.notice.value)
        assertNull(groups.pendingInvite.value)
        groups.dismissNotice()
        assertNull(groups.notice.value)
    }

    @Test
    fun theReportOffersToBlockOnlyWhoCanStillBeBlocked() {
        val kruger = id("Kruger trip")
        val members = groups.hub.detail(kruger)!!.members
        val me = members.first { it.isYou }
        val thandi = members.first { it.name == "Thandi" }
        assertEquals(thandi.memberId to "Thandi", blockable(groups.hub, kruger, ReportTarget.Member(thandi.memberId)))
        assertNull(blockable(groups.hub, kruger, ReportTarget.Member(me.memberId)))
        assertNull(blockable(groups.hub, kruger, ReportTarget.Group))
        // A pin another member dropped: its dropper.
        groups.setShown(kruger, true)
        waitFor { groups.mapContent()?.pins?.isNotEmpty() == true }
        val pin = groups.mapContent()!!.pins.first()
        val dropper = groups.hub.pinDetail(kruger, pin.pinId)!!
        assertEquals(dropper.dropperId to dropper.dropperName, blockable(groups.hub, kruger, ReportTarget.Pin(pin.pinId)))
        // Blocked already: "Thanks for reporting" with OK only (the row's name now reads "Blocked member").
        groups.hub.block(thandi.memberId, kruger)
        assertNull(blockable(groups.hub, kruger, ReportTarget.Member(thandi.memberId)))
    }

    @Test
    fun aValidNameTypedIsKeptToPrefillTheNextCreateOrJoin() {
        assertEquals("", groups.lastDisplayName)
        groups.rememberDisplayName("  Sam ")
        assertEquals("Sam", groups.lastDisplayName)
        // An empty or too long name keeps the last good one.
        groups.rememberDisplayName("   ")
        groups.rememberDisplayName("x".repeat(41))
        assertEquals("Sam", groups.lastDisplayName)
    }

    @Test
    fun anInviteForAnotherGroupAsksToJoinAndAnIncompleteOneSaysSo() {
        groups.openInvite(InviteLink.parse(GroupDemo.inviteLink, GroupConfig.domain))
        waitFor { groups.pendingInvite.value != null }
        assertNotNull(groups.pendingInvite.value!!.link)
        groups.closeInvite()
        groups.openInvite(InviteLink.Parsed.MissingKey)
        assertNull(groups.pendingInvite.value!!.link)
        groups.closeInvite()
        groups.openInvite(InviteLink.Parsed.NotInvite)
        assertNull(groups.pendingInvite.value)
    }

    @Test
    fun joiningShowsTheGroupAndRemembersTheName() {
        val link = (InviteLink.parse(GroupDemo.inviteLink, GroupDemo.domain) as InviteLink.Parsed.Invite).link
        var joined: String? = null
        CoroutineScope(Dispatchers.Main).launch { joined = groups.join(link, "  Thabo ") }
        waitFor { joined != null }
        assertEquals("Thabo", groups.lastDisplayName)
        waitFor { groups.shown.value?.groupId == joined }
        assertEquals("Weekend ride", groups.shown.value!!.name)
        assertEquals(3, groups.groups.value.size)
    }

    @Test
    fun termsAreAcceptedOncePerInstall() {
        assertTrue(groups.needsTerms)
        groups.acceptTerms()
        assertFalse(groups.needsTerms)
        assertEquals(GroupConfig.termsVersion, Defaults.int(GroupsModel.TERMS_KEY))
        assertEquals(GroupConfig.termsVersion, groups.hub.acceptedTermsVersion)
    }

    @Test
    fun theDemoStoreSellsYearlyProAtTheFixedPrices() {
        assertFalse(groups.pro.isPro.value)
        var result: ProStore.Result? = null
        CoroutineScope(Dispatchers.Main).launch {
            groups.pro.loadPrices()
            result = groups.pro.buy(null, ProPlan.lifetime)
        }
        waitFor { result != null }
        assertEquals(ProStore.Result.Done, result)
        assertTrue(groups.pro.isPro.value)
        val prices = (groups.pro.prices.value as ProStore.Prices.Loaded).prices
        assertEquals(listOf("R399.99", "R79.99", "R1,199.99"), ProPlan.entries.map { prices.getValue(it).formatted })
        assertEquals(GroupConfig.proProducts.subscription, groups.pro.productId)
        assertNotNull(Defaults.string(ProStore.CACHE_KEY))
    }

    /** S5's Renew and Accept, like S4's create, send their proof only when the store has confirmed Pro; otherwise
     *  the paywall comes first (and renewPro says why rather than doing nothing). */
    @Test
    fun renewingNeedsProTheStoreConfirmed() {
        assertFalse(groups.hasConfirmedPro)
        var error: Exception? = null
        var done = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                groups.renewPro()
            } catch (e: Exception) {
                error = e
            }
            done = true
        }
        waitFor { done }
        assertEquals(GroupActionError.Kind.ProNotConfirmed, (error as GroupActionError).kind)
        done = false
        CoroutineScope(Dispatchers.Main).launch {
            groups.pro.buy(null, ProPlan.yearly)
            groups.renewPro()
            done = true
        }
        waitFor { done }
        assertTrue(groups.hasConfirmedPro)
    }

    @Test
    fun aClosedPausedBannerStaysHiddenUntilTheNextLaunch() {
        val drone = id("Drone team")
        groups.hidePausedHint(drone)
        assertTrue(drone in groups.hiddenPausedHints.value)
    }

    @Test
    fun alertsQueueAndGoOneByOne() {
        val drone = id("Drone team")
        // The owner deletes "Drone team" on the server: the next status check says so.
        CoroutineScope(Dispatchers.Main).launch {
            groupsServerDelete(drone)
            groups.hub.refreshDetail(drone)
        }
        waitFor { groups.alerts.value.isNotEmpty() }
        assertEquals("Drone team", groups.alerts.value.first().groupName)
        groups.dismissAlert()
        assertTrue(groups.alerts.value.isEmpty())
        assertEquals(listOf("Kruger trip"), groups.groups.value.map { it.name })
    }

    @Test
    fun aHubInstalledLaterKeepsTheLocationAccessAndTheFixSoSharingSendsYourPosition() {
        // Location allowed and a fix before demo mode replaces the hub (as at launch with -groupsDemo).
        val fresh = GroupsModel(app)
        fresh.setLocationAccess(authorised = true, precise = true)
        val now = System.currentTimeMillis() / 1000.0
        fresh.fix(GpsFix(-33.9217, 18.9237, 5.0, null, null, now, 0.0))
        fresh.useDemo()
        waitFor { fresh.groups.value.size == 2 }
        val kruger = fresh.groups.value.first { it.name == "Kruger trip" }.groupId
        val me = fresh.hub.detail(kruger)!!.members.first { it.isYou }.memberId
        var ticked = false
        CoroutineScope(Dispatchers.Main).launch {
            fresh.hub.tick(foreground = true)
            ticked = true
        }
        waitFor { ticked }
        assertNotNull("your live position reached the server", fresh.demoServer!!.liveAt(kruger, me))
    }

    /** Deletes a group on the demo's fake server, as its owner would. */
    private fun groupsServerDelete(groupId: String) {
        groups.demoServer!!.deleteGroup(groupId)
    }
}
