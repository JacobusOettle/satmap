package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.TestBuildSwitch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The map screen while a test build is switched off (android/docs/spec/test-builds.md): the lock alone, so nothing
 *  behind it (the map's buttons, a sheet, an invite's confirmation) is composed, and a sheet or invite asked for
 *  meanwhile shows only once it is switched on again. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenTestBuildTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var model: AppModel
    private val sample =
        TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, TestBuildSwitch.sample, TestBuildSwitch.Platform.android)!!

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
        compose.setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder, map = null) } }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
        Defaults.prefs.edit().clear().apply()
    }

    @Test
    fun theLockAloneWhileSwitchedOffAndTheMapAgainAfter() {
        compose.onNodeWithTag("terrainButton").assertExists()
        model.setSheet(ActiveSheet.blocks)
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertExists()

        model.testBuild.forceLockedForTests(sample)
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLock").assertExists()
        // Nothing behind it: no button, and the open sheet closed (not kept to come back).
        compose.onNodeWithTag("terrainButton").assertDoesNotExist()
        compose.onNodeWithTag("groupsButton").assertDoesNotExist()
        compose.onAllNodesWithTag("screenTitle").assertCountEquals(0)
        assertNull(model.sheet.value)

        // A sheet and an invite asked for while switched off (a failed "Open with", an invite link) don't show.
        model.setSheet(ActiveSheet.offline)
        model.groups.openInvite(InviteLink.Parsed.MissingKey)
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLock").assertExists()
        compose.onAllNodesWithTag("screenTitle").assertCountEquals(0)

        // Switched on: the map's buttons, and what was asked for meanwhile.
        model.testBuild.forceLockedForTests(null)
        compose.waitForIdle()
        compose.onNodeWithTag("testBuildLock").assertDoesNotExist()
        compose.onNodeWithTag("terrainButton").assertExists()
        assertEquals(ActiveSheet.offline, model.sheet.value)
        assertTrue(compose.onAllNodesWithTag("screenTitle").fetchSemanticsNodes().isNotEmpty())
    }
}
