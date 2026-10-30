package com.jacobusoettle.satmap.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.location.GpsFix
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** "Blocks" (BlocksSheet.swift, screenshot 04; UI spec §3.1): one row per file with its switch, the colours checkbox,
 *  a tap on a file showing it and zooming to it, deleting by swipe and by TalkBack, the empty list and the import
 *  error. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class BlocksSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel
    private lateinit var library: KMLLibrary
    private var closed = 0

    /** The map the sheet zooms (held here: the model holds it weakly). */
    private val map = object : MapHost {
        val zooms = ArrayList<GeoBounds>()
        override val centerCoordinate = GeoPoint(0.0, 0.0)
        override val isOnScreen = true
        override fun pointsApart(a: GeoPoint, b: GeoPoint) = 0.0
        override fun center(on: GeoPoint, animated: Boolean) {}
        override fun zoom(onto: GeoPoint, metersAcross: Double) {}
        override fun zoom(to: GeoBounds) {
            zooms.add(to)
        }
        override fun locationUpdated(fix: GpsFix) {}
        override fun headingUpdated(heading: Double?) {}
        override fun reloadImagery() {}
    }

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
        model = AppModel(app, noHeights)
        model.map = map
        // Files read and parsed at once, on the test's thread.
        library = KMLLibrary(app, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
    }

    private fun show() {
        compose.setContent { SatMapTheme { BlocksSheet(model, library, close = { closed++ }) } }
    }

    private fun import(name: String, kml: String) {
        val file = File(app.cacheDir, name).apply { writeText(kml) }
        compose.runOnUiThread { library.importFile(Uri.fromFile(file)) }
        compose.waitForIdle()
    }

    private val farm = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Demo farm</name>
          <Placemark><name>Launch 1</name><Point><coordinates>18.924000,-33.917500,0</coordinates></Point></Placemark>
          <Placemark><name>A1 Shiraz</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            18.921719,-33.918791,0 18.923242,-33.919361,0 18.923242,-33.921462,0 18.921719,-33.918791,0
          </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
          <Placemark><name>D1 Apples</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            18.915,-33.913,0 18.917,-33.913,0 18.917,-33.915,0 18.915,-33.913,0
          </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        </Document></kml>
    """.trimIndent()

    @Test
    fun withNoFilesItSaysWhatToImport() {
        show()
        compose.onNodeWithTag("screenTitle").assert(hasText("Blocks"))
        compose.onNodeWithText("Import a .kml, .kmz or .gpx file to show your blocks on the map.").assertExists()
        compose.onNodeWithText("Switch a file off to take its blocks off the map.").assertDoesNotExist()
        compose.onNodeWithTag("useFileColours").assertDoesNotExist()
        compose.onNodeWithTag("importKml").assert(hasContentDescription("Import KML or GPX")).assert(hasClickAction())
        compose.onNode(hasText("Done") and hasClickAction()).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun eachFileHasARowWithItsShapesAndASwitch() {
        show()
        import("Demo farm.kml", farm)
        compose.onNodeWithText("Import a .kml, .kmz or .gpx file to show your blocks on the map.").assertDoesNotExist()
        // The walkthrough taps the row by its label, "Demo farm, 3 shapes · …".
        compose.onNode(hasText("Demo farm") and hasText("3 shapes · tap to show on map") and hasClickAction())
            .assertExists()
        compose.onNodeWithText("Switch a file off to take its blocks off the map.").assertExists()
        compose.onNodeWithText("Off: every block is drawn in white.").assertExists()

        val switch = compose.onNodeWithContentDescription("Show Demo farm on the map")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
            .assertIsOn()
        switch.performClick()
        assertFalse(library.files.value.single().isShown)
        compose.onNodeWithContentDescription("Show Demo farm on the map").assertIsOff()
        assertEquals(0, closed)
    }

    @Test
    fun aTapOnAFileShowsItZoomsToItAndCloses() {
        show()
        import("Demo farm.kml", farm)
        val id = library.files.value.single().id
        compose.runOnUiThread { library.setShown(id, false) }
        compose.onNode(hasText("Demo farm") and hasClickAction()).performClick()
        assertTrue(library.files.value.single().isShown)
        assertEquals(1, closed)
        val b = map.zooms.single()
        assertEquals(-33.921462, b.minLat, 1e-9)
        assertEquals(-33.913, b.maxLat, 1e-9)
        assertEquals(18.915, b.minLon, 1e-9)
        assertEquals(18.924, b.maxLon, 1e-9)
    }

    @Test
    fun theColoursCheckboxSwitchesTheFilesColours() {
        show()
        import("Demo farm.kml", farm)
        assertTrue(library.useFileColours.value)
        val row = compose.onNodeWithTag("useFileColours")
            .assert(hasText("Use the colours in the files"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row.performClick()
        assertFalse(library.useFileColours.value)
        compose.onNodeWithTag("useFileColours")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        assertEquals(false, Defaults.bool("kmlFileColours"))
    }

    private val offersDelete = SemanticsMatcher("offers Delete") { n ->
        n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Delete" } == true
    }

    @Test
    fun aFileIsDeletedWithItsDeleteAction() {
        show()
        import("Demo farm.kml", farm)
        import("Roads.kml", farm.replace("Demo farm", "Roads"))
        assertEquals(2, library.files.value.size)
        // TalkBack's "Delete" (the swipe's counterpart) is on the node TalkBack lands on: the file's button.
        compose.onNode(hasText("Demo farm") and hasClickAction()).assert(offersDelete)
        val row = compose.onNode(hasText("Roads") and hasClickAction()).assert(offersDelete).fetchSemanticsNode()
        compose.runOnUiThread { row.config[SemanticsActions.CustomActions].single { it.label == "Delete" }.action() }
        compose.waitForIdle()
        assertEquals(listOf("Demo farm"), library.files.value.map { it.name })
        compose.onNodeWithText("Roads").assertDoesNotExist()
    }

    @Test
    fun aSwipeDeletesThatFileOnly() {
        show()
        import("Demo farm.kml", farm)
        import("Roads.kml", farm.replace("Demo farm", "Roads"))
        assertEquals(listOf("Demo farm", "Roads"), library.files.value.map { it.name })
        val left = compose.onNode(hasText("Roads") and hasClickAction()).getUnclippedBoundsInRoot().left
        compose.onNode(hasText("Demo farm") and hasClickAction()).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        // Roads moves up into the deleted row's place: it is neither deleted with it nor left swiped away.
        assertEquals(listOf("Roads"), library.files.value.map { it.name })
        compose.onNodeWithText("Demo farm").assertDoesNotExist()
        compose.onNode(hasText("Roads") and hasClickAction()).assertIsDisplayed().assertLeftPositionInRootIsEqualTo(left)
        compose.onNodeWithContentDescription("Show Roads on the map").assertIsOn()
    }

    @Test
    fun aFileThatCantBeReadSaysWhy() {
        show()
        import("Notes.kml", "not a kml file")
        val error = library.importError.value!!
        compose.onNodeWithText("Couldn't import").assertExists()
        compose.onNodeWithText(error).assertExists()
        compose.onNode(hasText("OK") and hasClickAction()).performClick()
        assertNull(library.importError.value)
        compose.onNodeWithText("Couldn't import").assertDoesNotExist()
    }
}
