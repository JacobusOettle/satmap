package com.jacobusoettle.satmap.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `AppLifecycle` on the phone's process lifecycle: the notifications in registration order, and a clean slate for
 *  each test. The process lifecycle is driven through its registry, and left at CREATED (background) afterwards. */
@RunWith(AndroidJUnit4::class)
class AppLifecycleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val heard = mutableListOf<String>()

    private inner class Probe(val name: String) : AppLifecycle.Observer {
        override fun willEnterForeground() {
            heard += "$name willEnterForeground"
        }

        override fun didBecomeActive() {
            heard += "$name didBecomeActive"
        }

        override fun didEnterBackground() {
            heard += "$name didEnterBackground"
        }
    }

    @Before
    fun setUp() {
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    @Test
    fun initFollowsTheProcessOnceHoweverOftenItIsCalled() {
        val before = process.observerCount
        AppLifecycle.init()
        AppLifecycle.init()
        assertEquals(before + 1, process.observerCount)
        AppLifecycle.resetForTests()
        assertEquals(before, process.observerCount)
    }

    @Test
    fun aJumpToActiveSendsWillEnterForegroundFirstInRegistrationOrder() {
        AppLifecycle.init()
        AppLifecycle.addObserver(Probe("a"))
        AppLifecycle.addObserver(Probe("b"))
        assertFalse(AppLifecycle.isForeground)
        process.currentState = Lifecycle.State.RESUMED
        assertTrue(AppLifecycle.isActive)
        assertEquals(
            listOf("a willEnterForeground", "b willEnterForeground", "a didBecomeActive", "b didBecomeActive"),
            heard,
        )
        heard.clear()
        process.currentState = Lifecycle.State.STARTED
        assertEquals(AppLifecycle.AppState.inactive, AppLifecycle.state.value)
        assertEquals(emptyList<String>(), heard)
        process.currentState = Lifecycle.State.CREATED
        assertFalse(AppLifecycle.isForeground)
        assertEquals(listOf("a didEnterBackground", "b didEnterBackground"), heard)
    }

    @Test
    fun aRemovedObserverIsNoLongerTold() {
        AppLifecycle.init()
        val a = Probe("a")
        AppLifecycle.addObserver(a)
        AppLifecycle.addObserver(Probe("b"))
        AppLifecycle.removeObserver(a)
        process.currentState = Lifecycle.State.STARTED
        assertEquals(listOf("b willEnterForeground"), heard)
    }

    @Test
    fun resetForgetsTheObserversAndTheState() {
        AppLifecycle.init()
        AppLifecycle.addObserver(Probe("a"))
        process.currentState = Lifecycle.State.RESUMED
        AppLifecycle.resetForTests()
        assertEquals(AppLifecycle.AppState.background, AppLifecycle.state.value)
        assertEquals(emptyList<AppLifecycle.Observer>(), AppLifecycle.registered)
        heard.clear()
        process.currentState = Lifecycle.State.CREATED
        process.currentState = Lifecycle.State.RESUMED
        assertEquals(emptyList<String>(), heard)
        assertFalse(AppLifecycle.isForeground) // not following the process until init again
    }

    @Test
    fun theRecorderAndTheOfflineManagerAreToldBeforeTheModel() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        val model = AppModel(app, object : ElevationSource {
            override suspend fun elevation(p: GeoPoint): Double? = null
        })
        assertEquals(listOf(model.recorder, model.offline, model), AppLifecycle.registered)
    }
}
