package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.offline.OfflineManager
import com.jacobusoettle.satmap.offline.RoadFetcher
import kotlinx.coroutines.runBlocking
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
import java.io.IOException

/** "Download area" (UI spec §3.7, DESIGN.md §8.1): the level chosen (the remembered one, lowered to fit half the free
 *  space unless one is tapped; the track area's own), the size texts, the roads, and the screen itself. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class NewAreaViewTest {
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

    /** A farm about 900 m across. */
    private val farm = CoverageShape(
        polygons = listOf(
            listOf(GeoPoint(-33.920, 18.920), GeoPoint(-33.920, 18.930), GeoPoint(-33.928, 18.930), GeoPoint(-33.928, 18.920)),
        ),
    )

    private fun newState(track: TrackDownload? = null) = NewAreaState(PendingArea(name = "Demo farm", shape = farm), track = track)

    /** One tile at each level above 15, then 10 at 15, 4 times more at each level after. */
    private val counts: Map<Int, Long> = (0..22).associateWith { z -> if (z < 15) 1L else 10L shl (2 * (z - 15)) }

    /** The sizes `counts` give, worked out by hand: imagery tiles up to the level × 17 000 B, plus the 25 elevation
     *  tiles (zoom 0–15) × 45 000 B. */
    private val imageryTo = mapOf(15 to 25L, 16 to 65L, 17 to 225L, 18 to 865L, 19 to 3425L, 20 to 13665L, 21 to 54625L, 22 to 218465L)

    private fun bytes(z: Int) = imageryTo.getValue(z) * 17_000 + 25 * 45_000

    /** Free space that leaves `room` bytes for a download: half of what is above the 500 MiB downloads stop at. */
    private fun freeFor(room: Long) = OfflineManager.minimumFreeSpace + 2 * room

    @Test
    fun theEstimateIsImageryToTheLevelAndElevationToZoom15() {
        for (z in 15..22) {
            assertEquals(NewAreaState.Estimate(imageryTo.getValue(z) + 25, bytes(z)), NewAreaState.estimate(counts, counts, z))
        }
    }

    @Test
    fun theRememberedLevelIsLoweredToTheDeepestThatTakesHalfTheFreeSpace() {
        val s = newState()
        // Nothing stored: zoom 22.
        assertEquals(22, s.rememberedZoom)
        assertNull(s.deepestFitting)
        assertEquals(22, s.chosenZoom)
        s.counts = counts
        assertNull("not before the free space is known", s.deepestFitting)
        // Room for zoom 20 (233 MB) but not 21 (930 MB).
        s.freeSpace = freeFor(300_000_000)
        assertEquals(20, s.deepestFitting)
        assertEquals(20, s.chosenZoom)
        // Exactly the room for zoom 21.
        s.freeSpace = freeFor(bytes(21))
        assertEquals(21, s.chosenZoom)
        // Room for nothing: the least detailed level.
        s.freeSpace = freeFor(1)
        assertEquals(15, s.deepestFitting)
        assertEquals(15, s.chosenZoom)
        // No more than 500 MB free: nothing fits, and the remembered level stays (the warning says why).
        s.freeSpace = OfflineManager.minimumFreeSpace
        assertNull(s.deepestFitting)
        assertEquals(22, s.chosenZoom)
    }

    @Test
    fun aRememberedLevelBelowWhatFitsIsKept() {
        Defaults.putInt("offlineMaxZoom", 18)
        val s = newState()
        s.counts = counts
        s.freeSpace = freeFor(300_000_000)
        assertEquals(18, s.rememberedZoom)
        assertEquals(18, s.chosenZoom)
    }

    @Test
    fun theRememberedLevelIsClampedTo15Through22() {
        Defaults.putInt("offlineMaxZoom", 25)
        assertEquals(22, newState().rememberedZoom)
        Defaults.putInt("offlineMaxZoom", 3)
        assertEquals(15, newState().rememberedZoom)
    }

    @Test
    fun aTappedLevelIsDownloadedAsChosenAndRememberedForTheNextArea() {
        val s = newState()
        s.counts = counts
        s.freeSpace = freeFor(300_000_000)
        assertEquals(20, s.chosenZoom)
        s.choose(22)
        assertTrue(s.picked)
        // However big: it was tapped.
        assertEquals(22, s.chosenZoom)
        assertEquals(22, Defaults.int("offlineMaxZoom"))
        s.choose(17)
        assertEquals(17, s.chosenZoom)
        assertEquals(17, Defaults.int("offlineMaxZoom"))
        assertEquals(17, newState().rememberedZoom)
    }

    @Test
    fun yourTrackAreaKeepsItsOwnLevelApartFromTheRememberedOne() {
        Defaults.putInt("offlineMaxZoom", 19)
        val track = TrackDownload(
            lines = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.925, 18.925))),
            newLines = emptyList(),
            previousLines = emptyList(),
            maxZoom = 21,
            allowCellular = true,
            completeTo = null,
        )
        val s = newState(track)
        assertTrue(s.alongRoads)
        assertTrue(s.allowCellular)
        s.counts = counts
        s.freeSpace = freeFor(1)
        // The track area's level, not lowered to fit (it already holds tiles to it).
        assertEquals(21, s.chosenZoom)
        s.choose(22)
        assertEquals(22, s.chosenZoom)
        assertEquals(19, Defaults.int("offlineMaxZoom"))
        // A first track download (no track area yet) starts from the remembered level, lowered to fit.
        val first = newState(track.copy(maxZoom = null, allowCellular = null))
        assertFalse(first.allowCellular)
        first.counts = counts
        first.roads = NewAreaState.RoadsState.Loaded(track.lines, km = 0.7, counts = counts)
        first.freeSpace = freeFor(300_000_000)
        assertEquals(19, first.rememberedZoom)
        assertEquals(19, first.chosenZoom)
    }

    @Test
    fun theSizeTextsAreAboutUpToZoom19AndAnUpperLimitDeeper() {
        val s = newState()
        assertEquals("…", s.sizeText(22))
        s.counts = counts
        assertEquals("≈ ${Format.bytes(bytes(15))}", s.sizeText(15))
        assertEquals("≈ ${Format.bytes(bytes(19))}", s.sizeText(19))
        assertEquals("up to ${Format.bytes(bytes(20))}", s.sizeText(20))
        assertEquals("up to ${Format.bytes(bytes(22))}", s.sizeText(22))
        // Your track, already saved to that level.
        val saved = newState()
        saved.counts = (0..22).associateWith { 0L }
        assertEquals("Nothing new", saved.sizeText(18))
    }

    @Test
    fun alongRoadsTheImageryCountsAreTheRoadsOnesAndAFailedLookupShowsADash() {
        val s = newState()
        s.counts = counts
        s.freeSpace = freeFor(300_000_000)
        s.alongRoads = true
        // Roads not there yet: no sizes, no Download.
        assertEquals("…", s.sizeText(20))
        assertNull(s.chosenEstimate)
        assertFalse(s.canDownload)
        s.roads = NewAreaState.RoadsState.Failed("The OpenStreetMap road server is busy or unreachable. Try again in a minute.")
        assertEquals("–", s.sizeText(20))
        assertFalse(s.canDownload)
        // Along the roads: 1 imagery tile per level, the whole area's elevation.
        val road = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.925, 18.925)))
        s.roads = NewAreaState.RoadsState.Loaded(road, km = 0.7, counts = (0..22).associateWith { 1L })
        assertEquals(NewAreaState.Estimate(23 + 25, 23 * 17_000L + 25 * 45_000L), s.chosenEstimate)
        assertEquals(22, s.chosenZoom)
        assertTrue(s.canDownload)
        // Roads found, but none mapped there.
        s.roads = NewAreaState.RoadsState.Loaded(emptyList(), km = 0.0, counts = (0..22).associateWith { 0L })
        assertFalse(s.canDownload)
        s.alongRoads = false
        assertTrue(s.canDownload)
        s.name = "   "
        assertFalse(s.canDownload)
    }

    @Test
    fun theRoadsAreLookedUpAndTheirErrorsSaidAsOnIOS() = runBlocking {
        val s = newState()
        val road = listOf(listOf(GeoPoint(-33.921, 18.921), GeoPoint(-33.927, 18.929)))
        s.loadRoads(fetch = { road })
        val loaded = s.roads as NewAreaState.RoadsState.Loaded
        assertEquals(road, loaded.lines)
        assertEquals(Roads.length(road) / 1000, loaded.km, 1e-9)
        assertEquals(23, loaded.counts.size)
        assertTrue(loaded.counts.getValue(22) > loaded.counts.getValue(15))

        s.loadRoads(fetch = { throw RoadFetcher.Failure.Offline() })
        assertEquals(
            "No internet connection. Roads are looked up online; try again when connected.",
            (s.roads as NewAreaState.RoadsState.Failed).message,
        )
        s.loadRoads(fetch = { throw IOException("reset") })
        assertEquals("Couldn't get the roads. Try again.", (s.roads as NewAreaState.RoadsState.Failed).message)
    }

    @Test
    fun yourTrackIsItsOwnRoadsAndLevelsSavedBeforeCountOnlyWhereItIsNew() = runBlocking {
        val before = listOf(listOf(GeoPoint(-33.920, 18.920), GeoPoint(-33.920, 18.930)))
        val after = before + listOf(listOf(GeoPoint(-33.925, 18.920), GeoPoint(-33.925, 18.930)))
        val track = TrackDownload(after, newLines = after.drop(1), previousLines = before, maxZoom = 20, allowCellular = false, completeTo = 20)
        val s = newState(track)
        s.loadRoads(fetch = { error("not asked for a track") })
        val loaded = s.roads as NewAreaState.RoadsState.Loaded
        assertEquals(after, loaded.lines)
        val fresh = newState(track.copy(completeTo = null))
        fresh.loadRoads(fetch = { error("not asked for a track") })
        val all = (fresh.roads as NewAreaState.RoadsState.Loaded).counts
        // Up to the level the area has, only the new line's tiles; deeper, all of them.
        assertTrue(loaded.counts.getValue(20) < all.getValue(20))
        assertEquals(all.getValue(21), loaded.counts.getValue(21))
    }

    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }

    @Test
    fun theScreenShowsEveryLevelWithItsSizeAndTheChosenOneAndWhy() {
        val model = AppModel(app, noHeights)
        val s = newState()
        // Already counted: the screen doesn't count again.
        s.counts = counts
        s.freeSpace = freeFor(300_000_000)
        var closed = 0
        compose.setContent { SatMapTheme { NewAreaView(model, s, showsCancel = true, close = { closed++ }) } }
        compose.onNodeWithTag("screenTitle").assertTextEquals("Download area")
        compose.onNode(hasText("Cancel") and hasClickAction()).assertExists()
        compose.onNodeWithText("Demo farm").assertExists()
        compose.onNodeWithText("Zoom 22").assertExists()
        compose.onNodeWithText("up to ${Format.bytes(bytes(22))}").assertExists()
        compose.onNodeWithText("3 cm per pixel").assertExists()
        assertTrue(isSelected("Zoom 20"))
        assertFalse(isSelected("Zoom 22"))
        list().performScrollToNode(hasText("Tiles (most)"))
        compose.onNodeWithText("Up to zoom 20").assertExists()
        compose.onNodeWithText(countText(imageryTo.getValue(20) + 25)).assertExists()
        compose.onNodeWithText(Format.bytes(s.freeSpace!!)).assertExists()
        compose.onNodeWithText(
            "Zoom 22 needs up to ${Format.bytes(bytes(22))}, more than half the free space, so less detail is chosen. " +
                "Tap a level above to choose another.",
        ).assertExists()

        // A tapped level is downloaded however big: it may not fit.
        list().performScrollToNode(hasText("Zoom 21"))
        compose.onNodeWithText("Zoom 21").performClick()
        assertTrue(isSelected("Zoom 21"))
        assertEquals(21, s.chosenZoom)
        list().performScrollToNode(hasText("Tiles (most)"))
        compose.onNodeWithText("Up to zoom 21").assertExists()
        compose.onNodeWithText(
            "This may not fit. The download stops when less than 500 MB is free; choose a lower zoom or a smaller area.",
        ).assertExists()

        list().performScrollToNode(hasText("Download"))
        compose.onNode(hasText("Download") and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
        // Back to the map; notifications were asked for, once.
        assertEquals(1, closed)
        assertEquals(true, Defaults.bool("notificationPermissionAsked"))
    }

    @Test
    fun downloadWaitsForTheSizesAndCancelDiscardsTheArea() {
        val model = AppModel(app, noHeights)
        val s = newState()
        // The counting has started (not yet done): the sizes are being worked out.
        s.alongRoads = true
        s.roads = NewAreaState.RoadsState.Failed("No internet connection. Roads are looked up online; try again when connected.")
        var closed = 0
        compose.setContent { SatMapTheme { NewAreaView(model, s, showsCancel = true, close = { closed++ }) } }
        list().performScrollToNode(hasText("Try again"))
        compose.onNodeWithText("No internet connection. Roads are looked up online; try again when connected.").assertExists()
        list().performScrollToNode(hasText("Needs the roads first."))
        list().performScrollToNode(hasText("Download"))
        compose.onNode(hasText("Download") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun theTrackScreenShowsYourTrackAndWhatIsNew() {
        val model = AppModel(app, noHeights)
        val before = listOf(listOf(GeoPoint(-33.920, 18.920), GeoPoint(-33.920, 18.930)))
        val new = listOf(listOf(GeoPoint(-33.925, 18.920), GeoPoint(-33.925, 18.930)))
        val track = TrackDownload(before + new, newLines = new, previousLines = before, maxZoom = 20, allowCellular = false, completeTo = 20)
        val s = newState(track)
        compose.setContent {
            SatMapTheme { NewAreaView(model, s, back = ScreenChrome.Leading.Back("Offline maps") {}, close = {}) }
        }
        compose.onNodeWithTag("back").assertExists()
        // The track's lines are its roads: loaded at once, no lookup.
        compose.waitUntil(10_000) { s.roads is NewAreaState.RoadsState.Loaded }
        list().performScrollToNode(hasText("Your track"))
        compose.onNodeWithText(kilometres(Roads.length(before + new) / 1000)).assertExists()
        compose.onNodeWithText("New since the last download").assertExists()
        compose.onNodeWithText(
            "Satellite imagery 15 m to each side of everywhere you've been (your dotted track), with elevation under " +
                "it. Come back here after a trip to add the new parts: what is already saved is skipped.",
        ).assertExists()
        // No switch for roads: imagery always goes along the track.
        compose.onNodeWithText("Only along roads").assertDoesNotExist()
    }

    @Test
    fun kilometresHaveADecimalUnder10() {
        assertEquals("0.8 km", kilometres(0.8))
        assertEquals("9.9 km", kilometres(9.94))
        assertEquals("15 km", kilometres(15.2))
        assertEquals("7,930 km", kilometres(7930.4))
    }

    private fun list() = compose.onNode(hasScrollAction())

    /** Whether the level's row (a choice, its texts merged into it) is read as selected. */
    private fun isSelected(title: String): Boolean =
        compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
}
