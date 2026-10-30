package com.jacobusoettle.satmap.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.BackgroundDownload
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

/** The Offline maps sheet's screens drawn as the iPhone screenshots 06, 09, 13, 17 and 26 show them, into
 *  app/build/renders (see `render`), to compare with docs/screenshots side by side. They check nothing themselves. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersOfflineTest {
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
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
        model = AppModel(app, noHeights)
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
    }

    /** The repository's root (the fixtures are in Tests/SatMapCoreTests/Fixtures). */
    private val repo = File(System.getProperty("satmap.fixtures")!!).parentFile!!.parentFile!!.parentFile!!

    /** Runs what the manager posted to the main thread, then lets the screen catch up. */
    private fun settle() {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    /** The walkthrough's farm near Stellenbosch (where DemoFarm.kml's blocks are), as a box. */
    private val farm = CoverageShape(
        polygons = listOf(
            listOf(GeoPoint(-33.9165, 18.9195), GeoPoint(-33.9165, 18.9265), GeoPoint(-33.9260, 18.9265), GeoPoint(-33.9260, 18.9195)),
        ),
    )

    /** A finished area of `bytes`. */
    private fun downloaded(name: String, bytes: Long, maxZoom: Int, roads: List<List<GeoPoint>>? = null) = OfflineArea(
        id = UUID.randomUUID(),
        name = name,
        created = 800_000_000.0,
        shape = farm,
        roads = roads,
        maxZoom = maxZoom,
        allowCellular = false,
        status = OfflineArea.Status.complete,
        failedTiles = 0,
        bytesOnDisk = bytes,
    )

    /** Work given to it never runs: the sheet's measuring of the areas' folders (empty here, and Robolectric counts no
     *  blocks) never ends, so the areas keep the sizes they were given. */
    private val never = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    }

    /** A manager of the test's own with these areas, no network (nothing downloads) and, as after the walkthrough's
     *  download, the background status under the areas. */
    private fun managerWith(vararg areas: OfflineArea): OfflineManager {
        File(AppPaths.offline, "areas.json").writeText(areas.joinToString(",", "[", "]") { it.toJson().toString() })
        val offline = OfflineManager(
            app,
            network = MutableStateFlow(Connectivity.State(isOnline = false, isExpensive = false)),
            io = never,
        )
        offline.background.onStatusChange?.invoke(BackgroundDownload.keepsGoing)
        return offline
    }

    @Test
    fun offlineMaps() {
        compose.setSheet { OfflineSheet(model, model.offline, model.kml, close = {}) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Free space on this phone: ", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.render("06-offline-maps")
    }

    @Test
    fun chooseFile() {
        // The demo farm the walkthrough imported (SatMapUITests/DemoFarm.kml: 7 shapes and a point).
        val library = KMLLibrary(app, Dispatchers.Unconfined)
        val file = File(app.cacheDir, "Demo farm.kml")
        File(repo, "SatMapUITests/DemoFarm.kml").copyTo(file, overwrite = true)
        val stack = RouteStack<OfflineRoute>(OfflineRoute.Root).apply { push(OfflineRoute.KMLPicker) }
        compose.setSheet { OfflineSheet(model, model.offline, library, close = {}, stack = stack) }
        compose.runOnUiThread { library.importFile(Uri.fromFile(file)) }
        compose.waitForIdle()
        compose.onNodeWithText("Demo farm").assertExists()
        compose.onNodeWithText("7 shapes · 1 point").assertExists()
        compose.render("17-choose-file")
    }

    @Test
    fun offlineAreas() {
        val demo = downloaded("Demo farm", 68_400_000, maxZoom = 22)
        assertEquals("68.4 MB", Format.bytes(demo.bytesOnDisk))
        val offline = managerWith(demo)
        compose.setSheet { OfflineSheet(model, offline, model.kml, close = {}) }
        settle()
        compose.onNodeWithText("Ready offline").assertExists()
        compose.render("09-offline-areas")
    }

    @Test
    fun offlineAlongRoads() {
        val demo = downloaded("Demo farm", 68_400_000, maxZoom = 22)
        val road = listOf(listOf(GeoPoint(-33.9183, 18.9212), GeoPoint(-33.9218, 18.9238), GeoPoint(-33.9245, 18.9232)))
        val roads = downloaded("Farm roads", 19_100_000, maxZoom = 20, roads = road)
        assertEquals("19.1 MB", Format.bytes(roads.bytesOnDisk))
        val offline = managerWith(demo, roads)
        compose.setSheet { OfflineSheet(model, offline, model.kml, close = {}) }
        settle()
        compose.onNodeWithText("19.1 MB · up to zoom 20 · along roads").assertExists()
        compose.render("13-offline-along-roads")
    }

    /** A reserve with an extent of about `km2` (shown rounded), around the Sabi Sand. */
    private fun reserve(id: String, name: String, km2: Long): FoundPlace {
        val centre = GeoPoint(-24.85, 31.5)
        fun place(half: Double) = FoundPlace(
            id = id, name = name, kind = "Nature reserve", tag = "nature_reserve",
            region = "Ehlanzeni District Municipality, Mpumalanga, South Africa", centre = centre,
            extent = GeoBounds(minLat = centre.lat - half, minLon = centre.lon - half, maxLat = centre.lat + half, maxLon = centre.lon + half),
        )
        var lo = 0.001
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (place(mid).extentKm2!! < km2) lo = mid else hi = mid
        }
        return place(hi).also { assertEquals(km2, it.extentKm2!!.roundToLong()) }
    }

    @Test
    fun findPlace() {
        val search = OfflineRoute.PlaceSearch()
        search.state.text = "Sabi Sand"
        search.state.results = listOf(
            reserve("R1751334", "Sabi Sand Nature Reserve", 1032),
            reserve("R3497362", "Sabi Sands Game Reserve", 431),
            reserve("W229431517", "Sabi-Sands Game Reserve", 170),
        )
        val stack = RouteStack<OfflineRoute>(OfflineRoute.Root).apply { push(search) }
        compose.setSheet { OfflineSheet(model, model.offline, model.kml, close = {}, stack = stack) }
        compose.waitForIdle()
        compose.onNodeWithText("Mpumalanga, South Africa · up to 1,032 km²", substring = true).assertExists()
        compose.render("26-find-place")
    }
}
