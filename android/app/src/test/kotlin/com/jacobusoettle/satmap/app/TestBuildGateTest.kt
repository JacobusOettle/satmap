package com.jacobusoettle.satmap.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.TestBuildSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `TestBuildGate`: the saved state drives the lock before the network answers, a fresh fetch changes it, a store or
 *  debug build never locks, and the test hooks work. The decision itself is covered by the shared fixture
 *  (`TestBuildSwitchTests`); here the glue (channel gating, persistence, the immediate lock) is checked. */
@RunWith(AndroidJUnit4::class)
class TestBuildGateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val offJson = """{"schema":1,"testBuilds":"off","title":"Off","message":"Go to the store","playStoreURL":"https://play.google.com/store/apps/details?id=x","websiteURL":"https://github.com/JacobusOettle/satmap"}"""
    private val onJson = """{"schema":1,"testBuilds":"on"}"""

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().clear().commit()
    }

    private fun gate(
        channel: TestBuildSwitch.Channel?,
        fetch: suspend (String) -> String?,
    ) = TestBuildGate(
        channel = channel,
        urls = listOf("https://example.invalid/test-builds.json"),
        fetch = fetch,
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun testChannelLocksOnOffAndPersists() {
        val g = gate(TestBuildSwitch.Channel.test) { offJson }
        g.start()
        assertNull(g.lock.value, "no saved state yet: unlocked before the fetch")
        g.checkNowForTests()
        val lock = g.lock.value
        assertNotNull(lock, "an 'off' file locks a test build")
        assertEquals("Off", lock.title)
        assertEquals("https://play.google.com/store/apps/details?id=x", lock.playStoreURL)

        // A new build that can't reach the network keeps the saved "off": stays locked offline.
        val g2 = gate(TestBuildSwitch.Channel.test) { null }
        g2.start()
        assertNotNull(g2.lock.value, "the saved 'off' keeps it locked before any fetch")
    }

    @Test
    fun aLaterOnUnlocks() {
        val g = gate(TestBuildSwitch.Channel.test) { offJson }
        g.start(); g.checkNowForTests()
        assertNotNull(g.lock.value)
        // The owner turns the switch back on.
        val g2 = gate(TestBuildSwitch.Channel.test) { onJson }
        g2.start()
        assertNotNull(g2.lock.value, "before the fetch it still shows the saved 'off'")
        g2.checkNowForTests()
        assertNull(g2.lock.value, "a fresh 'on' unlocks")
    }

    @Test
    fun storeChannelNeverLocks() {
        val g = gate(TestBuildSwitch.Channel.store) { offJson }
        g.start()
        g.checkNowForTests()
        assertNull(g.lock.value, "a store build is never locked")
    }

    @Test
    fun debugBuildNeverChecks() {
        var fetched = false
        val g = gate(channel = null) { fetched = true; offJson }
        g.start()
        g.foregrounded()
        g.checkNowForTests()
        assertNull(g.lock.value, "a debug build never locks")
        assertEquals(false, fetched, "a debug build never fetches")
    }

    @Test
    fun badFileIsNoInformation() {
        // First lock it, then a malformed file arrives: the lock stays (keep the last known state).
        val g = gate(TestBuildSwitch.Channel.test) { offJson }
        g.start(); g.checkNowForTests()
        assertNotNull(g.lock.value)
        val g2 = gate(TestBuildSwitch.Channel.test) { "<<not json>>" }
        g2.start(); g2.checkNowForTests()
        assertNotNull(g2.lock.value, "an unreadable file keeps the saved state")
    }

    @Test
    fun forceAndDisableHooks() {
        val g = gate(TestBuildSwitch.Channel.test) { onJson }
        g.start()
        val sample = TestBuildSwitch.Lock("T", "M", null, "https://play.google.com/store/apps/details?id=x", null)
        g.forceLockedForTests(sample)
        assertEquals(sample, g.lock.value, "the test hook forces the lock on")
        g.forceLockedForTests(null)
        assertNull(g.lock.value, "clearing the forced lock goes back to the real (unlocked) state")

        g.disableForTests()
        g.checkNowForTests() // would normally fetch and could lock
        assertNull(g.lock.value, "a disabled gate never locks")
    }
}
