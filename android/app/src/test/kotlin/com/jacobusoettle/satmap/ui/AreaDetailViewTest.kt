package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** An area's screen (UI spec §3.8, DESIGN.md §8.1): the button's titles, the extra sizes, the footer, and the screen
 *  with a downloaded area. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class AreaDetailViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    private val road = listOf(listOf(GeoPoint(-33.920, 18.920), GeoPoint(-33.926, 18.931)))

    private fun area(maxZoom: Int = 20, status: OfflineArea.Status = OfflineArea.Status.complete) = OfflineArea(
        id = UUID.fromString("6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F"),
        name = "Farm roads",
        created = 800_000_000.0,
        shape = CoverageShape(polygons = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.92, 18.935), GeoPoint(-33.93, 18.935)))),
        roads = road,
        maxZoom = maxZoom,
        allowCellular = false,
        status = status,
        failedTiles = 0,
        bytesOnDisk = 19_100_000,
    )

    @Test
    fun theButtonSaysWhatATapDoes() {
        val s = AreaDetailState()
        val a = area(maxZoom = 20)
        assertEquals("Choose a deeper level", s.buttonTitle(a))
        s.chosenZoom = 21
        assertEquals("Download zoom 21", s.buttonTitle(a))
        s.chosenZoom = 22
        assertEquals("Download zoom 21–22", s.buttonTitle(a))
        // While it downloads, a lower level stops it there.
        s.chosenZoom = 18
        assertEquals("Stop at zoom 18", s.buttonTitle(area(maxZoom = 20, status = OfflineArea.Status.downloading)))
    }

    @Test
    fun theExtraSizesAddTheNewLevelsImagery() {
        val s = AreaDetailState()
        assertEquals("…", s.extraSize(21, from = 18))
        s.counts = mapOf(19 to 100L, 20 to 400L, 21 to 1600L, 22 to 6400L)
        assertEquals("+ ≈ ${Format.bytes(100L * 17_000)}", s.extraSize(19, from = 18))
        assertEquals("+ up to ${Format.bytes(500L * 17_000)}", s.extraSize(20, from = 18))
        assertEquals("+ up to ${Format.bytes(8000L * 17_000)}", s.extraSize(22, from = 20))
    }

    @Test
    fun theFooterSaysWhatALevelDoesAndWhereTheImageryStops() {
        val s = AreaDetailState()
        val choose = "Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded."
        assertEquals(choose, s.footer(area()))
        assertEquals(
            "$choose While it downloads, choose a lower level to stop there instead.",
            s.footer(area(status = OfflineArea.Status.paused)),
        )
        s.savedDepth = 19
        assertEquals("$choose Esri's imagery here stops at zoom 19, so deeper levels will add little or nothing.", s.footer(area()))
        s.savedDepth = 20
        assertEquals(choose, s.footer(area()))
    }

    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }

    @Test
    fun aDownloadedAreaOffersTheDeeperLevelsAndDownloadsThem() {
        val model = AppModel(app, noHeights)
        // The area, then a manager of its own with no network, so the deeper level waits rather than downloads.
        File(AppPaths.offline, "areas.json").writeText("[${area().toJson()}]")
        val offline = OfflineManager(app, network = MutableStateFlow(Connectivity.State(isOnline = false, isExpensive = false)))
        val id = area().id
        val state = AreaDetailState()
        compose.setContent {
            SatMapTheme { AreaDetailView(model, offline, id, state, back = ScreenChrome.Leading.Back("Offline maps") {}) }
        }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Farm roads")
        compose.onNodeWithText("Downloaded to").assertExists()
        compose.onNodeWithText("zoom 20, along roads").assertExists()
        compose.onNodeWithText(Format.bytes(19_100_000)).assertExists()
        compose.onNodeWithText("Used when you add a deeper level.").assertExists()
        // The deeper levels' sizes along the roads, once counted.
        compose.waitUntil(10_000) { state.counts != null }
        list().performScrollToNode(hasText("Zoom 15"))
        row("Zoom 20").assertIsNotEnabled()
        row("Zoom 18").assertIsNotEnabled()
        compose.onNode(hasText("Zoom 18") and hasText("Downloaded")).assertExists()
        list().performScrollToNode(hasText("Zoom 22"))
        row("Zoom 21").assertIsEnabled().performClick()
        assertEquals(21, state.chosenZoom)
        list().performScrollToNode(hasText("Download zoom 21"))
        compose.onNode(hasText("Download zoom 21") and hasClickAction()).assertIsEnabled()
        list().performScrollToNode(hasText("Zoom 22"))
        row("Zoom 22").performClick()
        list().performScrollToNode(hasText("Download zoom 21–22"))
        compose.onNode(hasText("Download zoom 21–22") and hasClickAction()).performClick()
        compose.waitForIdle()
        // Downloading to 22 now (waiting for the network here), and nothing chosen.
        assertEquals(22, offline.areas.value.single().maxZoom)
        assertNull(state.chosenZoom)
        list().performScrollToNode(hasText("Choose a deeper level"))
        compose.onNode(hasText("Choose a deeper level") and hasClickAction()).assertIsNotEnabled()
        list().performScrollToNode(hasText("Downloading to"))
        compose.onNodeWithText("zoom 22, along roads").assertExists()
    }

    @Test
    fun aDeletedAreaSaysSo() {
        val model = AppModel(app, noHeights)
        compose.setContent {
            SatMapTheme { AreaDetailView(model, model.offline, UUID.randomUUID(), AreaDetailState(), back = null) }
        }
        compose.onNodeWithText("This area has been deleted.").assertExists()
    }

    private fun list() = compose.onNode(hasScrollAction())

    /** A level's row: a choice, its texts merged into it. */
    private fun row(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
}
