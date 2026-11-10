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
import kotlin.test.assertTrue

/** `TestBuildGate`: only a test build that isn't a debug build fetches, keeps or locks; the kept state drives the lock
 *  before the network answers; a fresh file changes it; anything unreadable keeps it; the addresses are tried in order
 *  with `?t=`; and the walkthrough's hooks. The rule itself is the shared fixture's (`TestBuildSwitchTests`). */
@RunWith(AndroidJUnit4::class)
class TestBuildGateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val off = """{"schema":1,"testBuilds":"off","title":"Off","message":"Go to the store","appStoreURL":"https://apps.apple.com/app/satmap/id0000000000","playStoreURL":"https://play.google.com/store/apps/details?id=x","websiteURL":"https://github.com/JacobusOettle/satmap","updated":"2026-12-01T08:00:00Z"}"""
    private val on = """{"schema":1,"testBuilds":"on"}"""
    private val urls = listOf("https://status.example.invalid/test-builds.json", "https://raw.example.invalid/test-builds.json?x=1")

    /** Every address asked for, in order. */
    private val asked = ArrayList<String>()

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().clear().commit()
    }

    private fun gate(
        channel: TestBuildSwitch.Channel = TestBuildSwitch.Channel.test,
        debug: Boolean = false,
        answer: (String) -> String?,
    ) = TestBuildGate(
        channel = channel,
        isDebugBuild = debug,
        urls = urls,
        fetch = { url -> asked += url; answer(url)?.encodeToByteArray() },
        clock = { 1_800_000_000.5 },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    )

    private val kept: String? get() = Defaults.string(TestBuildSwitch.savedKey)

    @Test
    fun anOffFileLocksWithItsTextsAndGooglePlaysButtonAndIsKept() {
        val g = gate { off }
        g.start()
        assertNull(g.lock.value, "never fetched: on")
        g.checkNowForTests()
        val lock = assertNotNull(g.lock.value)
        assertEquals("Off", lock.title)
        assertEquals("Go to the store", lock.message)
        assertEquals(listOf(TestBuildSwitch.Button.Kind.googlePlay, TestBuildSwitch.Button.Kind.website), lock.buttons.map { it.kind })
        assertEquals("https://play.google.com/store/apps/details?id=x", lock.buttons.first().url)
        // Kept as a status file, which the next launch reads before any answer: locked offline too.
        assertEquals(TestBuildSwitch.parse(off.encodeToByteArray()), TestBuildSwitch.saved(kept))
        val offline = gate { null }
        offline.start()
        assertEquals(lock, offline.lock.value)
        offline.checkNowForTests()
        assertEquals(lock, offline.lock.value, "no answer keeps the last known state")
    }

    @Test
    fun aLaterOnUnlocks() {
        gate { off }.apply { start(); checkNowForTests() }
        val g = gate { on }
        g.start()
        assertNotNull(g.lock.value, "before the answer, the kept off")
        g.checkNowForTests()
        assertNull(g.lock.value, "a fresh on unlocks")
        assertEquals(TestBuildSwitch.Status(on = true), TestBuildSwitch.saved(kept))
    }

    @Test
    fun anUnreadableFileKeepsTheLastKnownState() {
        gate { off }.apply { start(); checkNowForTests() }
        val before = kept
        for (bad in listOf("<html>404</html>", """{"schema":2,"testBuilds":"on"}""", """{"schema":1,"testBuilds":"ON"}""", "")) {
            val g = gate { bad }
            g.start()
            g.checkNowForTests()
            assertNotNull(g.lock.value, bad)
            assertEquals(before, kept, bad)
        }
    }

    @Test
    fun theAddressesAreTriedInOrderWithTheTimeAddedAndNothingElse() {
        val g = gate { url -> if (url.startsWith(urls[0])) null else off }
        g.start()
        g.checkNowForTests()
        assertEquals(
            listOf(
                "https://status.example.invalid/test-builds.json?t=1800000000",
                "https://raw.example.invalid/test-builds.json?x=1&t=1800000000",
            ),
            asked,
        )
        assertNotNull(g.lock.value, "the second address answered")
        // The first that answers is used; the rest aren't asked.
        asked.clear()
        gate { on }.apply { start(); checkNowForTests() }
        assertEquals(1, asked.size)
    }

    @Test
    fun aStoreBuildNeverFetchesKeepsOrLocks() {
        Defaults.putString(TestBuildSwitch.savedKey, TestBuildSwitch.savedText(TestBuildSwitch.Status(on = false)))
        val g = gate(channel = TestBuildSwitch.Channel.store) { off }
        g.start()
        g.foregrounded()
        g.checkNowForTests()
        g.foregrounded()
        assertNull(g.lock.value, "a store build is never locked, whatever was kept")
        assertTrue(asked.isEmpty(), "a store build never fetches: $asked")
        assertEquals("""{"schema":1,"testBuilds":"off"}""", kept, "nor writes what it keeps")
        g.backgrounded()
    }

    @Test
    fun aDebugBuildNeverFetchesKeepsOrLocks() {
        Defaults.putString(TestBuildSwitch.savedKey, TestBuildSwitch.savedText(TestBuildSwitch.Status(on = false)))
        val g = gate(debug = true) { off }
        g.start()
        g.foregrounded()
        g.checkNowForTests()
        assertNull(g.lock.value)
        assertTrue(asked.isEmpty(), "a debug build never fetches: $asked")
        g.backgrounded()
    }

    @Test
    fun aLaunchThatIsAlsoAForegroundingFetchesOnce() {
        val g = gate { on }
        g.start()
        g.foregrounded()
        g.foregrounded()
        assertEquals(1, asked.size)
        g.backgrounded()
    }

    @Test
    fun theWalkthroughsHooks() {
        val sample = TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, TestBuildSwitch.sample, TestBuildSwitch.Platform.android)!!
        // Forced on a debug build too (the walkthrough's), never fetched or kept.
        val debug = gate(debug = true) { off }
        debug.forceLockedForTests(sample)
        assertEquals(sample, debug.lock.value)
        debug.forceLockedForTests(null)
        assertNull(debug.lock.value)
        assertNull(kept)

        // Disabled: the real switch never locks, even with "off" kept or answered.
        gate { off }.apply { start(); checkNowForTests() }
        asked.clear()
        val g = gate { off }
        g.start()
        assertNotNull(g.lock.value)
        g.disableForTests()
        assertNull(g.lock.value)
        g.foregrounded()
        g.checkNowForTests()
        assertNull(g.lock.value)
        assertTrue(asked.isEmpty())
        g.forceLockedForTests(sample)
        assertEquals(sample, g.lock.value)
        g.forceLockedForTests(null)
        assertNull(g.lock.value)
    }
}
