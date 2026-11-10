package com.jacobusoettle.satmap.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.TestBuildSwitch
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The map screen while a test build is switched off (android/docs/spec/test-builds.md): the lock alone, so nothing
 *  behind it (the map's buttons, a sheet, a dialog) is composed; what was open (a sheet, a drawn area, the "Clear
 *  track" question) is kept and shows again once it is switched on, and links and files opened meanwhile are ignored,
 *  as on the iPhone. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenTestBuildTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var model: AppModel
    private val sample =
        TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, TestBuildSwitch.sample, TestBuildSwitch.Platform.android)!!
    private val ring = listOf(GeoPoint(-25.0, 31.0), GeoPoint(-25.0, 31.01), GeoPoint(-25.01, 31.01))

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
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
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
    }

    private fun lock(on: Boolean) {
        model.testBuild.forceLockedForTests(if (on) sample else null)
        compose.waitForIdle()
    }

    /** Only the lock is on screen: no map button, sheet or dialog. */
    private fun assertLockAlone() {
        compose.onNodeWithTag("testBuildLock").assertExists()
        compose.onNodeWithTag("terrainButton").assertDoesNotExist()
        compose.onNodeWithTag("groupsButton").assertDoesNotExist()
        compose.onAllNodesWithTag("screenTitle").assertCountEquals(0)
        compose.onNodeWithText("Clear the recorded track?").assertDoesNotExist()
    }

    @Test
    fun theLockAloneWhileSwitchedOffAndTheOpenSheetAgainAfter() {
        compose.onNodeWithTag("terrainButton").assertExists()
        model.setSheet(ActiveSheet.blocks)
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertExists()

        lock(true)
        assertLockAlone()
        // The sheet isn't composed behind the lock, but it is kept for after.
        assertEquals(ActiveSheet.blocks, model.sheet.value)

        // Switched on: the map's buttons, and the sheet that was open.
        lock(false)
        compose.onNodeWithTag("testBuildLock").assertDoesNotExist()
        compose.onNodeWithTag("terrainButton").assertExists()
        assertEquals(ActiveSheet.blocks, model.sheet.value)
        compose.onNodeWithTag("screenTitle").assertExists()
    }

    @Test
    fun aDrawnAreaWaitsBehindTheLock() {
        model.startDrawingArea()
        model.finishDrawing(ring)
        model.confirmDrawnArea()
        compose.waitForIdle()
        compose.onNodeWithText("Download area").assertExists()

        lock(true)
        assertLockAlone()
        // The area drawn but not saved yet isn't thrown away.
        assertEquals(ring, model.draftArea.value)
        assertNotNull(model.pendingArea.value)

        lock(false)
        compose.onNodeWithText("Download area").assertExists()
        assertEquals(ring, model.draftArea.value)
    }

    @Test
    fun theClearTrackQuestionWaitsBehindTheLock() {
        compose.onNodeWithTag("myLocationButton").performTouchInput { longClick() }
        compose.onNodeWithText("Clear recorded track").performClick()
        compose.onNodeWithText("Clear the recorded track?").assertExists()

        lock(true)
        assertLockAlone()

        lock(false)
        compose.onNodeWithText("Clear the recorded track?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear the recorded track?").assertDoesNotExist()
    }

    @Test
    fun linksAndFilesOpenedWhileSwitchedOffAreIgnored() {
        lock(true)
        // An invite link (from another app or a web page), a scanned or pasted one, and a shared KML file: each
        // dropped (handled, so the activity doesn't keep it), with nothing imported or waiting for the unlock.
        assertTrue(MainActivity.handleIncoming(model, Intent(Intent.ACTION_VIEW, Uri.parse(GroupDemo.inviteLink))))
        model.groups.openInvite(InviteLink.Parsed.MissingKey)
        val kml = File(app.cacheDir, "Farm.kml").apply { writeBytes(File(AndroidSqlDriverTest.fixtures, "blocks.kml").readBytes()) }
        assertTrue(MainActivity.handleIncoming(model, Intent(Intent.ACTION_VIEW, Uri.fromFile(kml))))
        compose.waitForIdle()
        assertLockAlone()
        assertNull(model.groups.pendingInvite.value)
        assertTrue(model.kml.files.value.isEmpty())
        assertNull(model.sheet.value)

        // Switched on: the map alone, nothing of the above.
        lock(false)
        compose.onNodeWithTag("terrainButton").assertExists()
        compose.onAllNodesWithTag("screenTitle").assertCountEquals(0)
        assertNull(model.groups.pendingInvite.value)
        assertTrue(model.kml.files.value.isEmpty())
        assertNull(model.sheet.value)

        // From then on a link opens its confirmation again.
        model.groups.openInvite(InviteLink.Parsed.MissingKey)
        assertNotNull(model.groups.pendingInvite.value)
    }
}
