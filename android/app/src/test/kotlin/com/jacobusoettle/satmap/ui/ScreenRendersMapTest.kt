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
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.map.ContourInterval
import com.jacobusoettle.satmap.map.TerrainKey
import com.jacobusoettle.satmap.offline.AndroidSqlDriver
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

/** The map screen's buttons, banner, pills and legends drawn as the iPhone screenshots 01, 08, 10, 15 and 23 show them,
 *  into app/build/renders (see `render`), to compare with docs/screenshots. The map can't be drawn here (a field's
 *  colour stands in for it), and the window has no status bar, so the panels start at the top. They check nothing
 *  themselves. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersMapTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    /** You at the walkthrough's farm near Stellenbosch, 431 m up by GPS. */
    private fun atTheFarm() {
        model.recorder.setForTests(
            GpsFix(-33.921765, 18.923779, 5.0, 431.0, 3.0, 1_800_000_000.0, SystemClock.elapsedRealtime() / 1000.0),
        )
    }

    /** Over the imagery's typical colour (the farm's fields, screenshot 01) in place of the map. */
    private fun show(offline: OfflineManager = model.offline) {
        compose.setContent {
            SatMapTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF4F5931))) { MapScreen(model, offline, model.recorder, map = null) }
            }
        }
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    /** Screenshot 01: your coordinates and height, the contour key over the scale bar, and the buttons. */
    @Test
    fun map() {
        atTheFarm()
        model.scale.update(1.0)
        model.legend.update(TerrainKey(ContourInterval(10.0, 50.0), null))
        show()
        compose.render("01-map")
    }

    /** Screenshot 08: an area downloading (the banner, and Offline maps in yellow) while a point is measured. Its run
     *  never starts (`io` takes no work), so it stays at "Starting…". */
    @Test
    fun downloading() {
        MBTiles.driver = AndroidSqlDriver()
        // SatMap on screen, so downloads start. Told to the manager only: the model would start the map's tile store.
        AppLifecycle.resetForTests()
        AppLifecycle.init()
        process.currentState = Lifecycle.State.RESUMED
        // Work given to it never runs.
        val never = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {}
        }
        val offline = OfflineManager(
            app,
            network = MutableStateFlow(Connectivity.State(isOnline = true, isExpensive = false)),
            io = never,
        )
        val farm = CoverageShape(
            polygons = listOf(listOf(GeoPoint(-33.9165, 18.9195), GeoPoint(-33.9165, 18.9265), GeoPoint(-33.9260, 18.9265))),
        )
        offline.add(name = "Demo farm", shape = farm, roads = null, preset = null, maxZoom = 22, allowCellular = false)
        atTheFarm()
        model.measure(GeoPoint(-33.9188, 18.9240))
        show(offline)
        compose.onNodeWithText("Downloading Demo farm").assertExists()
        compose.render("08-downloading")
    }

    /** Screenshot 10: choosing an area, Draw chosen. */
    @Test
    fun drawArea() {
        model.startDrawingArea()
        show()
        compose.render("10-draw-area")
    }

    /** Screenshot 15: your coordinates at the top, a tapped spot's at the bottom. */
    @Test
    fun coordinates() {
        atTheFarm()
        model.setSelectedPoint(GeoPoint(-33.916591, 18.920568))
        show()
        compose.render("15-coordinates")
    }

    /** Screenshot 23: the elevation colours alone, their key under the scale bar. */
    @Test
    fun elevationColours() {
        atTheFarm()
        model.setTerrainMode(TerrainMode.colours)
        model.scale.update(1.0)
        model.legend.update(TerrainKey(null, ElevationRange(300.0, 900.0)))
        show()
        compose.render("23-elevation-colours")
    }
}
