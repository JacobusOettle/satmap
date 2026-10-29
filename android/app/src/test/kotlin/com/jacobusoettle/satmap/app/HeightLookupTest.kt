package com.jacobusoettle.satmap.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `HeightLookup` and `CrosshairAim` on virtual time (the main dispatcher is a test dispatcher): one lookup at a time
 *  for the latest spot, 31 s retries capped at 4 and only while active, and a session bump dropping a late answer. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HeightLookupTest {
    private val main = StandardTestDispatcher()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val a = GeoPoint(-25.0, 31.0)
    private val b = GeoPoint(-25.001, 31.0)
    private val c = GeoPoint(-25.002, 31.0)

    /** Answers after a second of virtual time; counts the lookups under way. */
    private class Heights(var answer: (GeoPoint) -> Double? = { null }) : ElevationSource {
        val asked = mutableListOf<GeoPoint>()
        var underWay = 0
        var mostUnderWay = 0

        override suspend fun elevation(p: GeoPoint): Double? {
            asked += p
            underWay += 1
            mostUnderWay = maxOf(mostUnderWay, underWay)
            delay(1_000)
            underWay -= 1
            return answer(p)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        AppLifecycle.init()
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
        Dispatchers.resetMain()
    }

    private fun active() {
        process.currentState = Lifecycle.State.RESUMED
    }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun oneLookupAtATimeForTheLatestSpot() = runTest(main) {
        val heights = Heights { if (it == a) 100.0 else 300.0 }
        val found = mutableListOf<Pair<GeoPoint, Double?>>()
        val lookup = HeightLookup(heights) { p, e -> found += p to e }
        lookup.set(a)
        runCurrent()
        lookup.set(b)
        lookup.set(c)
        runCurrent()
        assertEquals(listOf(a), heights.asked)
        pass(1_000)
        // a's answer is told (it is for a spot asked about), then the latest spot, c, is looked up; b never.
        assertEquals(listOf(a to 100.0), found)
        assertEquals(listOf(a, c), heights.asked)
        pass(1_000)
        assertEquals(listOf(a to 100.0, c to 300.0), found)
        assertEquals(1, heights.mostUnderWay)
        // The same spot again: nothing new.
        lookup.set(c)
        pass(5_000)
        assertEquals(2, heights.asked.size)
    }

    @Test
    fun aFailedLookupIsRetriedEvery31sAtMost4Times() = runTest(main) {
        active()
        val heights = Heights()
        var told = 0
        val lookup = HeightLookup(heights) { _, _ -> told += 1 }
        lookup.set(a)
        pass(1_000)
        assertEquals(1, heights.asked.size)
        pass(30_000)
        assertEquals(1, heights.asked.size)
        pass(1_000) // 31 s after the answer
        assertEquals(2, heights.asked.size)
        pass(10 * 60_000L)
        assertEquals(5, heights.asked.size)
        assertEquals(5, told)
    }

    @Test
    fun noRetryWhileTheAppIsNotActive() = runTest(main) {
        val heights = Heights()
        val lookup = HeightLookup(heights) { _, _ -> }
        lookup.set(a)
        pass(1_000)
        pass(10 * 60_000L)
        assertEquals(1, heights.asked.size)
        // Asked again by hand (the network is back, the app was opened): it is.
        lookup.retry()
        pass(1_000)
        assertEquals(2, heights.asked.size)
    }

    @Test
    fun aRetryIsForTheSameSpotOnly() = runTest(main) {
        active()
        val heights = Heights()
        val lookup = HeightLookup(heights) { _, _ -> }
        lookup.set(a)
        pass(1_000)
        lookup.set(b) // b's own lookup, and its own retries
        pass(1_000)
        assertEquals(listOf(a, b), heights.asked)
        pass(31_000)
        assertEquals(listOf(a, b, b), heights.asked)
    }

    @Test
    fun anAnswerForASpotThatWentIsDropped() = runTest(main) {
        active()
        val heights = Heights { 100.0 }
        val found = mutableListOf<Pair<GeoPoint, Double?>>()
        val lookup = HeightLookup(heights) { p, e -> found += p to e }
        lookup.set(a)
        runCurrent()
        lookup.set(null)
        pass(1_000)
        assertEquals(emptyList<Pair<GeoPoint, Double?>>(), found)
        assertNull(lookup.point)
        // Asked again for a: a new lookup, told this time.
        lookup.set(a)
        pass(1_000)
        assertEquals(listOf(a to 100.0), found)
    }

    @Test
    fun theAimShowsTheHeightAndTheSpotItIsFor() = runTest(main) {
        val heights = Heights { if (it == a) 100.0 else 300.0 }
        val aim = CrosshairAim(heights)
        aim.set(a)
        runCurrent()
        aim.set(b)
        assertEquals(b, aim.point.value)
        assertNull(aim.elevation.value)
        pass(1_000)
        // While the map moves the height is an earlier spot's (the panel dims it).
        assertEquals(100.0, aim.elevation.value)
        assertEquals(a, aim.elevationPoint.value)
        pass(1_000)
        assertEquals(300.0, aim.elevation.value)
        assertEquals(b, aim.elevationPoint.value)
        assertEquals(1, heights.mostUnderWay)
    }

    @Test
    fun aimingEndedDropsALateAnswerAndForgetsTheHeight() = runTest(main) {
        val heights = Heights { 100.0 }
        val aim = CrosshairAim(heights)
        aim.set(a)
        pass(1_000)
        aim.set(b)
        runCurrent()
        aim.set(null)
        assertNull(aim.elevation.value)
        assertNull(aim.elevationPoint.value)
        pass(1_000)
        assertNull(aim.elevation.value)
        assertNull(aim.elevationPoint.value)
    }

    @Test
    fun theAimRetriesEvery31sAtMost4TimesWhileActiveAndOnlyWithoutAHeight() = runTest(main) {
        active()
        var answer: Double? = null
        val heights = Heights { answer }
        val aim = CrosshairAim(heights)
        aim.set(a)
        pass(1_000)
        pass(10 * 60_000L)
        assertEquals(5, heights.asked.size)
        // A height known: retry() does nothing.
        answer = 100.0
        aim.retry()
        pass(1_000)
        assertEquals(100.0, aim.elevation.value)
        aim.retry()
        pass(1_000)
        assertEquals(6, heights.asked.size)
    }
}
