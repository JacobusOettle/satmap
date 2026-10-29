package com.jacobusoettle.satmap.car

import android.app.Application
import android.content.Intent
import android.graphics.PointF
import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.map.MapController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.robolectric.Shadows.shadowOf

/** The car's map screen (CarPlaySceneDelegate.swift) through the Car App Library's test host: the navigation template
 *  with the terrain title and the map buttons, the title following the mode, the buttons reaching the map, and the
 *  screen's and the session's lifecycles. */
@RunWith(AndroidJUnit4::class)
class CarMapScreenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private val done = object : OnDoneCallback {}
    private lateinit var model: AppModel
    private lateinit var carContext: TestCarContext

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
        AppModel.shared = model
        carContext = TestCarContext.createCarContext(app)
        // The car app runs (as when the host shows it): a screen pushed then is started and asked for its template.
        carContext.lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun shown(): Pair<CarMapScreen, ScreenController> {
        val screen = CarMapScreen(carContext)
        val controller = ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        idle()
        return screen to controller
    }

    private fun ScreenController.lastTemplate(): NavigationTemplate = templatesReturned.last() as NavigationTemplate

    private fun NavigationTemplate.terrainTitle(): String =
        actionStrip!!.actions.single().title!!.toCharSequence().toString()

    @Test
    fun oneNavigationTemplateWithTheTerrainAndFourMapButtonsThePanLast() {
        val (_, controller) = shown()
        val templates = controller.templatesReturned
        assertEquals(1, templates.size)
        val template = templates.single() as NavigationTemplate
        assertEquals("Contours", template.terrainTitle())
        val buttons = template.mapActionStrip!!.actions
        assertEquals(4, buttons.size)
        assertEquals(Action.TYPE_PAN, buttons.last().type)
        for (b in buttons.dropLast(1)) {
            assertEquals(Action.TYPE_CUSTOM, b.type)
            assertNotNull(b.icon)
            assertNotNull(b.onClickDelegate)
        }
        assertNotNull(template.panModeDelegate)
    }

    @Test
    fun theTerrainButtonShowsTheNextModeEachTap() {
        val (_, controller) = shown()
        controller.lastTemplate().actionStrip!!.actions.single().onClickDelegate!!.sendClick(done)
        idle()
        assertEquals(TerrainMode.contoursAndColours, model.terrainMode.value)
        assertEquals("Contours + colours", controller.lastTemplate().terrainTitle())
        val titles = ArrayList<String>()
        repeat(4) {
            controller.lastTemplate().actionStrip!!.actions.single().onClickDelegate!!.sendClick(done)
            idle()
            titles += controller.lastTemplate().terrainTitle()
        }
        assertEquals(listOf("Colours", "Drone", "No contours", "Contours"), titles)
        assertEquals(TerrainMode.contours, model.terrainMode.value)
    }

    @Test
    fun theTitleFollowsAModeChangedOnThePhone() {
        val (_, controller) = shown()
        model.setTerrainMode(TerrainMode.colours)
        idle()
        assertEquals(2, controller.templatesReturned.size)
        assertEquals("Colours", controller.lastTemplate().terrainTitle())
    }

    @Test
    fun theTitlesAreCarPlays() {
        assertEquals(
            listOf("Contours", "Contours + colours", "Colours", "Drone", "No contours"),
            TerrainMode.entries.map { CarMapScreen.shortTitle(it) },
        )
    }

    @Test
    fun theMapButtonsZoomFollowAndPanTheCarMap() {
        val (screen, controller) = shown()
        val moves = ArrayList<String>()
        val camera = object : CarMapController.Camera {
            override val position: CameraPosition? = null
            override fun restore(position: CameraPosition) {}
            override fun show(region: MapController.Region) {}
            override fun center(on: GeoPoint) {
                moves += "centre ${on.lat},${on.lon}"
            }

            override fun zoomBy(levels: Double, focus: PointF?, animated: Boolean) {
                moves += "zoom $levels $animated"
            }

            override fun scrollBy(dx: Float, dy: Float) {}
            override fun padding(left: Int, top: Int, right: Int, bottom: Int) {}
        }
        screen.map.startForTests(camera, 800, 480)
        idle()
        val template = controller.lastTemplate()
        val buttons = template.mapActionStrip!!.actions
        buttons[0].onClickDelegate!!.sendClick(done)
        buttons[1].onClickDelegate!!.sendClick(done)
        idle()
        assertEquals(listOf("zoom 1.0 true", "zoom -1.0 true"), moves)

        template.panModeDelegate!!.sendPanModeChanged(true, done)
        idle()
        assertFalse(screen.map.following)
        template.panModeDelegate!!.sendPanModeChanged(false, done) // leaving pan mode doesn't follow again
        idle()
        assertFalse(screen.map.following)

        model.recorder.setForTests(com.jacobusoettle.satmap.location.GpsFix(-25.0, 31.0, 5.0, null, null, 1.8e9, 1.0))
        idle()
        moves.clear()
        buttons[2].onClickDelegate!!.sendClick(done)
        idle()
        assertTrue(screen.map.following)
        assertEquals(listOf("centre -25.0,31.0"), moves)
    }

    @Test
    fun whileTheScreenLivesItsMapIsTheModelsCarMapAndPaintsWhileStarted() {
        val (screen, controller) = shown()
        val appManager = carContext.getCarService(TestAppManager::class.java)
        assertSame(screen.map, appManager.surfaceCallback)
        assertSame(screen.map, model.carMap)
        assertTrue(screen.map.isShown)
        controller.moveToState(Lifecycle.State.CREATED)
        assertFalse(screen.map.isShown)
        controller.moveToState(Lifecycle.State.STARTED)
        assertTrue(screen.map.isShown)
        controller.moveToState(Lifecycle.State.DESTROYED)
        assertFalse(screen.map.isShown)
        assertNull(model.carMap)
    }

    @Test
    fun aCarSessionKeepsTheAppInTheForegroundAndShowsTheMap() {
        // Only the session's effect on the app state: the model's own lifecycle work needs the tile repository.
        AppLifecycle.resetForTests()
        AppLifecycle.init()
        val told = ArrayList<String>()
        AppLifecycle.addObserver(object : AppLifecycle.Observer {
            override fun willEnterForeground() {
                told += "willEnterForeground"
            }

            override fun didBecomeActive() {
                told += "didBecomeActive"
            }

            override fun didEnterBackground() {
                told += "didEnterBackground"
            }
        })
        val session = CarSession()
        // A car context of its own: the session controller drives the session through the context's lifecycle.
        val controller = SessionController(session, TestCarContext.createCarContext(app), Intent())
        controller.moveToState(Lifecycle.State.RESUMED)
        idle()
        assertEquals(AppLifecycle.AppState.active, AppLifecycle.state.value)
        assertTrue(model.carMap is CarMapController)
        controller.moveToState(Lifecycle.State.CREATED)
        assertEquals(AppLifecycle.AppState.background, AppLifecycle.state.value)
        assertEquals(listOf("willEnterForeground", "didBecomeActive", "didEnterBackground"), told)
    }
}
