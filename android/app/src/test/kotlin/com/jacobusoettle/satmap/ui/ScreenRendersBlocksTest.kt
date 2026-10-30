package com.jacobusoettle.satmap.ui

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Blocks (screenshot 04) and an area's screen with a deeper level chosen (screenshot 18), drawn as the iPhone
 *  screenshots show them, into app/build/renders (see `render`), to compare with docs/screenshots side by side. They
 *  check nothing themselves. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersBlocksTest {
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

    @Test
    fun blocksList() {
        // The demo farm the iPhone screenshots show (SatMapUITests/DemoFarm.kml, 7 shapes and a point), shown, with its
        // colours.
        val library = KMLLibrary(app, Dispatchers.Unconfined)
        val file = File(app.cacheDir, "Demo farm.kml")
        File(repo, "SatMapUITests/DemoFarm.kml").copyTo(file, overwrite = true)
        compose.setSheet { BlocksSheet(model, library, close = {}) }
        compose.runOnUiThread { library.importFile(Uri.fromFile(file)) }
        compose.waitForIdle()
        check(library.files.value.single().document.features.size == 8)
        check(library.files.value.single().isShown && library.useFileColours.value)
        compose.render("04-blocks-list")
    }

    @Test
    fun areaDeeperLevel() {
        // "Farm roads": downloaded along its roads to zoom 20, with mobile data off; zoom 21 chosen.
        val area = OfflineArea(
            id = UUID.fromString("6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F"),
            name = "Farm roads",
            created = 800_000_000.0,
            shape = CoverageShape(
                polygons = listOf(listOf(GeoPoint(-33.913, 18.915), GeoPoint(-33.913, 18.926), GeoPoint(-33.923, 18.926))),
            ),
            roads = listOf(listOf(GeoPoint(-33.915, 18.917), GeoPoint(-33.922, 18.925))),
            maxZoom = 20,
            allowCellular = false,
            status = OfflineArea.Status.complete,
            failedTiles = 0,
            bytesOnDisk = 19_100_000,
        )
        File(AppPaths.offline, "areas.json").writeText("[${area.toJson()}]")
        val offline = OfflineManager(app, network = MutableStateFlow(Connectivity.State(isOnline = false, isExpensive = false)))
        val state = AreaDetailState()
        compose.setSheet {
            AreaDetailView(model, offline, area.id, state, back = ScreenChrome.Leading.Back("Offline maps") {})
        }
        // Once the screen has counted the deeper levels' tiles: the iPhone's counts (+ up to 45.8 MB for zoom 21,
        // 202.7 MB for 21–22 at 17 kB a tile) and the depth of its imagery (zoom 20), then zoom 21 chosen.
        compose.waitUntil(10_000) { state.counts != null }
        compose.runOnUiThread {
            state.counts = mapOf(21 to 2_694L, 22 to 9_230L)
            state.savedDepth = 20
            state.chosenZoom = 21
        }
        compose.waitForIdle()
        // Scrolled to the end, as the iPhone's list is (it scrolls until the Download button shows; its last card then
        // ends 54 pt above the bottom: the home indicator's 34 and the list's margin).
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.waitForIdle()
        compose.render("18-area-deeper-level")
    }
}
