package com.jacobusoettle.satmap.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.FileShapes
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.UUID

/** The Offline maps sheet (UI spec §3.2–3.6, DESIGN.md §8.1): its first screen, the file picker, the areas' rows, the
 *  backup alert, and the back stack keeping each screen's state. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class OfflineSheetTest {
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
        AppLifecycle.resetForTests()
    }

    private fun list() = compose.onNode(hasScrollAction())

    /** Runs what the manager posted to the main thread (its scope is `Dispatchers.Main`, which the compose clock
     *  doesn't drive), then lets the screen catch up. */
    private fun settle() {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test
    fun theFirstScreenListsWhereAreasComeFromTheAreasBackupAndTheCredits() {
        var closed = 0
        compose.setContent { SatMapTheme { OfflineSheet(model, model.offline, model.kml, close = { closed++ }) } }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
        for (title in listOf(
            "Find a park, reserve or town",
            "Draw an area on the map",
            "Use a GPX or KML file",
            "Where I've been",
        )) {
            button(title).assertIsEnabled()
        }
        // The Kruger roads row is gone (its screen stays in the code).
        compose.onNodeWithText("Kruger National Park roads").assertDoesNotExist()
        compose.onNodeWithText(
            "Saves satellite imagery at the highest detail available plus elevation data for contours and heights, so " +
                "the map works with no signal.",
        ).assertExists()
        compose.onNodeWithText("OFFLINE AREAS").assertExists()
        compose.onNodeWithText("No offline areas yet.").assertExists()
        list().performScrollToNode(hasText("Restore from a backup"))
        compose.onNodeWithText("BACKUP").assertExists()
        // Nothing to export yet.
        button("Export all offline maps").assertIsNotEnabled()
        button("Restore from a backup").assertIsEnabled()
        compose.onNodeWithText(
            "Saves each area as two MBTiles files, satellite and elevation, to Google Drive, a folder or a USB drive, " +
                "or send them to a computer. To restore, choose both files of each area. Long-press an area to export " +
                "only that one.",
        ).assertExists()
        val credits = "Map data: ${TileSources.attribution} · Roads and places: © OpenStreetMap contributors, found with " +
            "Photon (komoot) and Nominatim · Airfields and navaids: OurAirports"
        list().performScrollToNode(hasText(credits))
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Free space on this phone: ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        button("Done").performClick()
        assertEquals(1, closed)
    }

    private val place = FoundPlace(
        id = "N85412364",
        name = "Skukuza",
        kind = "Village",
        tag = "village",
        region = "Mpumalanga, South Africa",
        centre = GeoPoint(-24.9961, 31.5920),
        extent = null,
    )

    @Test
    fun aScreenUnderAnotherKeepsWhatItShows() {
        val stack = RouteStack<OfflineRoute>(OfflineRoute.Root)
        compose.setContent { SatMapTheme { OfflineSheet(model, model.offline, model.kml, close = {}, stack = stack) } }
        button("Find a park, reserve or town").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
        assertEquals("Offline maps", stack.below(stack.top)?.title)
        val search = stack.top as OfflineRoute.PlaceSearch
        button("Search").assertIsNotEnabled()
        compose.onNodeWithTag("placeSearchText").performTextInput("Skukuza")
        button("Search").assertIsEnabled()
        // What a search finds (the search itself is online).
        compose.runOnIdle { search.state.results = listOf(place) }
        compose.onNodeWithText("PLACES").assertExists()
        val detail = "Village · Mpumalanga, South Africa · 2 km around it"
        button(detail).performClick()
        // A spot has no boundary to fetch: the circle around it, on the download screen.
        compose.waitUntil(10_000) { (stack.top as? OfflineRoute.PlaceArea)?.state?.newArea != null }
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Download area")
        assertEquals("Find a place", stack.below(stack.top)?.title)
        compose.onNodeWithText("Skukuza").assertExists() // the name field

        compose.onNodeWithTag("back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Find a place")
        compose.onNodeWithTag("placeSearchText").assertTextContains("Skukuza")
        compose.onNodeWithText(detail).assertExists()
        assertTrue(stack.top === search)

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
        // A new search screen each time it is opened, as iOS's.
        button("Find a park, reserve or town").performClick()
        compose.waitForIdle()
        assertEquals("", (stack.top as OfflineRoute.PlaceSearch).state.text)
    }

    @Test
    fun aSecondTapOnALinkWhileItsScreenSlidesInOpensItOnce() {
        val stack = RouteStack<OfflineRoute>(OfflineRoute.Root)
        compose.setContent { SatMapTheme { OfflineSheet(model, model.offline, model.kml, close = {}, stack = stack) } }
        /** Taps `link`, then again 100 ms later while its screen slides in: iOS ignores the second tap. (Not the
         *  name field of the screen coming in.) */
        fun tapTwice(link: String) {
            val row = compose.onNode(hasText(link) and hasClickAction() and !hasSetTextAction())
            compose.mainClock.autoAdvance = false
            row.performClick()
            compose.mainClock.advanceTimeBy(100)
            row.performClick()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        for (link in listOf(
            "Use a GPX or KML file",
            "Where I've been",
            "Find a park, reserve or town",
        )) {
            tapTwice(link)
            assertEquals(link, 2, stack.size)
            assertTrue(link, stack.below(stack.top) === OfflineRoute.Root)
            if (link == "Find a park, reserve or town") break
            compose.onNodeWithTag("back").performClick()
            compose.waitForIdle()
            assertEquals(1, stack.size)
        }
        // A place opens its area once (and looks up its boundary once).
        val search = stack.top as OfflineRoute.PlaceSearch
        compose.runOnIdle { search.state.results = listOf(place) }
        tapTwice("Village · Mpumalanga, South Africa · 2 km around it")
        assertEquals(3, stack.size)
        assertTrue(stack.below(stack.top) === search)
    }

    @Test
    fun theFilePickerAsksForAFileFirst() {
        compose.setContent { SatMapTheme { OfflineSheet(model, model.offline, model.kml, close = {}) } }
        button("Use a GPX or KML file").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Choose a file")
        compose.onNodeWithText("Import a KML or GPX file first (Blocks button on the map).").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Offline maps")
    }

    @Test
    fun aFileInThePickerOpensTheDownloadOfItsShapes() {
        val gpx = File(app.cacheDir, "Farm drive.gpx").apply { writeBytes(File(AndroidSqlDriverTest.fixtures, "farm.gpx").readBytes()) }
        var imported: KMLLibrary.File? = null
        model.kml.importFile(Uri.fromFile(gpx)) { imported = it }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (imported == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val file = imported!!
        val stack = RouteStack<OfflineRoute>(OfflineRoute.Root)
        compose.setContent { SatMapTheme { OfflineSheet(model, model.offline, model.kml, close = {}, stack = stack) } }
        button("Use a GPX or KML file").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Choose a file")
        compose.onNodeWithText("Import a KML or GPX file first (Blocks button on the map).").assertDoesNotExist()
        // What the file holds: the 2 tracks it marks as areas, its route and its 2 other tracks, its 2 waypoints.
        compose.onNodeWithText("2 shapes · 3 lines · 2 points").assertExists()
        button("Farm drive").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Download area")
        assertEquals("Choose a file", stack.below(stack.top)?.title)
        // Inside the file's shapes and 15 m around its lines and points.
        val state = (stack.top as OfflineRoute.NewArea).state
        assertEquals(FileShapes(file.document).downloadShape, state.area.shape)
        assertEquals(FileShapes(file.document), state.file)
        assertTrue(state.offersAlongRoads)
    }

    private fun area(name: String, status: OfflineArea.Status, bytes: Long = 68_400_000, roads: Boolean = false) = OfflineArea(
        id = UUID.randomUUID(),
        name = name,
        created = 800_000_000.0,
        shape = CoverageShape(polygons = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.92, 18.93), GeoPoint(-33.93, 18.93)))),
        roads = if (roads) listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.925, 18.925))) else null,
        maxZoom = if (roads) 20 else 22,
        allowCellular = roads,
        status = status,
        failedTiles = 0,
        bytesOnDisk = bytes,
        problem = if (status == OfflineArea.Status.incomplete) OfflineManager.lowSpaceProblem else null,
    )

    /** A manager of the test's own with these areas and no network, so nothing downloads. */
    private fun managerWith(vararg areas: OfflineArea): OfflineManager {
        File(AppPaths.offline, "areas.json").writeText(areas.joinToString(",", "[", "]") { it.toJson().toString() })
        return OfflineManager(app, network = MutableStateFlow(Connectivity.State(isOnline = false, isExpensive = false)))
    }

    @Test
    fun theAreasShowHowTheyAreDoingAndTheirButtons() {
        val farm = area("Demo farm", OfflineArea.Status.complete)
        val roads = area("Farm roads", OfflineArea.Status.paused, bytes = 19_100_000, roads = true)
        val full = area("Kruger", OfflineArea.Status.incomplete)
        val offline = managerWith(farm, roads, full)
        compose.setContent { SatMapTheme { OfflineSheet(model, offline, model.kml, close = {}) } }
        // Opening the sheet measures the areas' folders again (empty here).
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            offline.areas.value[0].bytesOnDisk != farm.bytesOnDisk
        }
        compose.waitForIdle()
        val measured = offline.areas.value
        compose.onNodeWithText("Ready offline").assertExists()
        compose.onNode(hasText("Demo farm") and hasText(areaInfoText(measured[0]))).assertExists()
        assertEquals("${Format.bytes(measured[0].bytesOnDisk)} · up to zoom 22", areaInfoText(measured[0]))
        compose.onNodeWithText("Paused").assertExists()
        compose.onNode(hasText("Farm roads") and hasText(areaInfoText(measured[1]))).assertExists()
        assertTrue(areaInfoText(measured[1]).endsWith(" · up to zoom 20 · along roads · mobile data allowed"))
        compose.onNodeWithText("Stopped: ${OfflineManager.lowSpaceProblem} Tap ↻ to continue.").assertExists()
        // ↻ on the paused and the stopped one.
        compose.onAllNodesWithContentDescription("Resume").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Resume")[0].performClick()
        settle()
        assertNotEquals(OfflineArea.Status.paused, offline.areas.value.single { it.id == roads.id }.status)
        list().performScrollToNode(hasText("Export all offline maps"))
        button("Export all offline maps").assertIsEnabled()
    }

    @Test
    fun aLongPressExportsOneAreaAndTheOutcomeIsSaid() {
        val farm = area("Demo farm", OfflineArea.Status.complete)
        val offline = managerWith(farm)
        compose.setContent { SatMapTheme { OfflineSheet(model, offline, model.kml, close = {}) } }
        val row = compose.onNode(hasText("Demo farm") and hasClickAction())
        row.assert(
            SemanticsMatcher("long press says Export") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Export" },
        )
        row.performTouchInput { longClick() }
        compose.onNodeWithText("Export").performClick()
        // Its files were never downloaded here: nothing to share.
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            offline.backupMessage.value != null
        }
        compose.onNodeWithText("Backup").assertExists()
        compose.onNodeWithText("Nothing has been downloaded yet to export.").assertExists()
        button("OK").performClick()
        compose.waitForIdle()
        assertNull(offline.backupMessage.value)
        compose.onNodeWithText("Nothing has been downloaded yet to export.").assertDoesNotExist()
    }

    @Test
    fun anAreaIsDeletedFromItsRow() {
        val farm = area("Demo farm", OfflineArea.Status.complete)
        val offline = managerWith(farm)
        compose.setContent { SatMapTheme { OfflineSheet(model, offline, model.kml, close = {}) } }
        val row = compose.onNode(hasText("Demo farm") and hasClickAction())
        compose.runOnIdle {
            row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Delete" }.action()
        }
        compose.waitForIdle()
        assertTrue(offline.areas.value.isEmpty())
        compose.onNodeWithText("No offline areas yet.").assertExists()
    }

    @Test
    fun aTapOnAnAreaOpensItsScreen() {
        val farm = area("Demo farm", OfflineArea.Status.complete)
        val offline = managerWith(farm)
        compose.setContent { SatMapTheme { OfflineSheet(model, offline, model.kml, close = {}) } }
        compose.onNode(hasText("Demo farm") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Demo farm")
        compose.onNodeWithText("Show on the map").assertExists()
    }

    @Test
    fun anAreaRowSaysWhatItsDownloadIsDoing() {
        val a = area("Demo farm", OfflineArea.Status.downloading)
        val p = DownloadProgress(layer = "Satellite", zoom = 21, maxZoom = 22, levelDone = 1615, levelTotal = 5326)
        assertEquals("Downloading…", areaStatusText(a, null, online = true))
        assertEquals("Downloading…", areaStatusText(a, DownloadProgress(), online = true))
        assertEquals("Downloading satellite · zoom 21 of 22 · 1,615/5,326 tiles", areaStatusText(a, p, online = true))
        assertEquals("Waiting to start", areaStatusText(a.copy(status = OfflineArea.Status.queued), null, true))
        val waiting = a.copy(status = OfflineArea.Status.waitingForNetwork)
        assertEquals("Waiting for Wi-Fi", areaStatusText(waiting, null, online = true))
        assertEquals("Waiting for internet", areaStatusText(waiting, null, online = false))
        assertEquals("Waiting for internet", areaStatusText(waiting.copy(allowCellular = true), null, online = true))
        val failed = a.copy(status = OfflineArea.Status.incomplete, failedTiles = 1234)
        assertEquals("Incomplete: 1,234 tiles failed. Tap ↻ to retry.", areaStatusText(failed, null, true))
        val track = a.copy(isTrack = true, roads = listOf(listOf(GeoPoint(-33.92, 18.92))))
        assertEquals("68.4 MB · up to zoom 22 · along your track", areaInfoText(track))
        assertEquals("68.4 MB · up to zoom 22 · along roads", areaInfoText(a.copy(preset = "KrugerRoads")))
    }

    @Test
    fun aRowsPauseAndResumeButtonsAreLabelled() {
        var paused = 0
        var resumed = 0
        val downloading = area("Demo farm", OfflineArea.Status.downloading)
        var shown by mutableStateOf(downloading)
        compose.setContent {
            SatMapTheme { AreaRow(shown, null, canDownloadNow = true, onPause = { paused++ }, onResume = { resumed++ }, online = true) }
        }
        // The symbol sits at the button's right end (as iOS's against the chevron); the touch target stays 44 dp.
        compose.onNodeWithContentDescription("Pause").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp).performClick()
        assertEquals(1, paused)
        shown = downloading.copy(status = OfflineArea.Status.paused)
        compose.onNodeWithContentDescription("Resume").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp).performClick()
        assertEquals(1, resumed)
        shown = downloading.copy(status = OfflineArea.Status.complete)
        compose.onNode(hasContentDescription("Pause") or hasContentDescription("Resume")).assertDoesNotExist()
        compose.onNodeWithText("Ready offline").assertExists()
    }

    @Test
    fun aPlaceSaysItsKindRegionAndSize() {
        val reserve = FoundPlace(
            id = "R1751334", name = "Sabi Sand Nature Reserve", kind = "Nature reserve", tag = "nature_reserve",
            region = "Ehlanzeni District Municipality, Mpumalanga, South Africa", centre = GeoPoint(-24.85, 31.5),
            extent = GeoBounds(minLat = -25.0, minLon = 31.35, maxLat = -24.7, maxLon = 31.65),
        )
        val km2 = reserve.extentKm2!!
        assertEquals(
            "Nature reserve · Ehlanzeni District Municipality, Mpumalanga, South Africa · up to " +
                "${java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(Math.round(km2))} km²",
            placeDetail(reserve, DistanceUnit.metric),
        )
        val small = reserve.copy(region = "", extent = GeoBounds(minLat = -25.0, minLon = 31.5, maxLat = -24.99, maxLon = 31.52))
        assertTrue(placeDetail(small, DistanceUnit.metric).matches(Regex("Nature reserve · up to \\d\\.\\d km²")))
        assertEquals("Village · Mpumalanga, South Africa · 2 km around it", placeDetail(place, DistanceUnit.metric))
        assertEquals(
            "Village · Mpumalanga, South Africa · ${DistanceUnit.imperial.format(2000.0)} around it",
            placeDetail(place, DistanceUnit.imperial),
        )
    }
}
