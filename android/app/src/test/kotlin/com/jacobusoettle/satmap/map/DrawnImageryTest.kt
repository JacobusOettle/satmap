package com.jacobusoettle.satmap.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.WorldRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What `DrawnImagery` decides apart from the map: the colours' range from the heights on screen (Swift's
 *  `updateColourRange`: kept while it suits, at least 10 m, dropped elsewhere, the first one waiting for the rest of the
 *  screen), and what the legends show (`reportKey`). */
@RunWith(AndroidJUnit4::class)
class DrawnImageryTest {
    private val here = WorldRect(0.5, 0.5, 0.501, 0.501)
    private val near = WorldRect(0.5005, 0.5, 0.5015, 0.501) // overlapping `here`
    private val far = WorldRect(0.6, 0.6, 0.601, 0.601)
    private val choice = ColourRangeChoice()
    private var keys = 0
    private var rechecks = 0

    private fun update(visible: WorldRect, low: Double, high: Double, complete: Boolean = true, now: Double = 100.0) =
        choice.update(visible, SatelliteRenderer.Extremes(low, high, complete), now, reportKey = { keys++ }, recheck = { rechecks++ })

    @Test
    fun theFirstRangeIsJustAroundTheHeightsAtRoundHeights() {
        assertEquals(ElevationRange(300.0, 900.0), update(here, 312.0, 887.0))
        assertEquals(ElevationRange(300.0, 900.0), choice.range)
        assertTrue(choice.heightsOnScreen)
        assertEquals(2, keys) // heights on screen, then the range
        assertEquals(0, rechecks)
    }

    @Test
    fun aRangeIsKeptWhileTheHeightsFitAndFillMostOfIt() {
        update(here, 312.0, 887.0) // 300–900
        keys = 0
        // They fit and fill 60 % of it: kept.
        assertNull(update(near, 400.0, 760.0))
        assertEquals(ElevationRange(300.0, 900.0), choice.range)
        assertEquals(0, keys)
        // They fit but fill less: a new one around them.
        assertEquals(ElevationRange(400.0, 750.0), update(near, 400.0, 740.0))
        assertEquals(1, keys)
        // They don't fit: a new one.
        assertEquals(ElevationRange(400.0, 800.0), update(near, 420.0, 760.0))
        assertEquals(ElevationRange(500.0, 1000.0), update(near, 510.0, 960.0))
    }

    @Test
    fun nearlyFlatGroundSpansAtLeast10MetresAndLandStaysAboveTheSea() {
        assertEquals(ElevationRange(97.0, 107.0), update(here, 101.0, 103.0))
        val coast = ColourRangeChoice()
        assertEquals(ElevationRange(0.0, 10.0), coast.update(here, SatelliteRenderer.Extremes(2.0, 4.0, true), 100.0, {}, {}))
    }

    @Test
    fun whileHeightsAreLoadingTheRangeOnlyGrows() {
        update(here, 312.0, 887.0) // 300–900
        // Fitting, however little they fill: kept.
        assertNull(update(near, 500.0, 520.0, complete = false))
        // Beyond it: it grows to take them in.
        assertEquals(ElevationRange(300.0, 1000.0), update(near, 500.0, 950.0, complete = false))
    }

    @Test
    fun aRangeFromSomewhereElseIsDropped() {
        update(here, 312.0, 887.0)
        keys = 0
        // Far away: the old range goes, and the heights there choose one of their own.
        assertEquals(ElevationRange(1000.0, 1500.0), update(far, 1000.0, 1500.0))
        assertEquals(2, keys)
        // Back, where those heights would fit in it: not kept either (it was chosen far away).
        assertEquals(ElevationRange(300.0, 900.0), update(here, 312.0, 887.0))
        // Far away with no heights: the range and the legend go.
        keys = 0
        assertNull(choice.update(far, null, 100.0, { keys++ }, { rechecks++ }))
        assertNull(choice.range)
        assertFalse(choice.heightsOnScreen)
        assertEquals(2, keys)
    }

    @Test
    fun theFirstRangeOfAPlaceWaitsForTheRestOfTheScreenFor1Point5Seconds() {
        assertNull(update(here, 300.0, 400.0, complete = false, now = 100.0))
        assertNull(choice.range)
        assertEquals(1, rechecks) // looked at again in 1.6 s
        assertNull(update(here, 300.0, 420.0, complete = false, now = 101.4))
        assertEquals(1, rechecks)
        // 1.5 s on: from the heights there are.
        assertEquals(ElevationRange(300.0, 460.0), update(here, 300.0, 450.0, complete = false, now = 101.5))
        assertEquals(1, rechecks)
    }

    @Test
    fun completeHeightsChooseTheFirstRangeAtOnce() {
        assertNull(update(here, 300.0, 400.0, complete = false, now = 100.0))
        assertEquals(ElevationRange(300.0, 460.0), update(here, 300.0, 450.0, complete = true, now = 100.2))
        assertEquals(1, rechecks)
    }

    @Test
    fun aWaitOlderThan5SecondsIsForgotten() {
        assertNull(update(here, 300.0, 400.0, complete = false, now = 100.0))
        // Much later (the colours were off, or the map elsewhere): a new wait.
        assertNull(update(here, 300.0, 400.0, complete = false, now = 106.0))
        assertEquals(2, rechecks)
        assertNull(update(here, 300.0, 400.0, complete = false, now = 107.0))
        assertNotNull(update(here, 300.0, 400.0, complete = false, now = 107.6))
    }

    // The legends

    private val range = ElevationRange(300.0, 900.0)

    @Test
    fun theContourIntervalIsTheOneDrawnAtTheContourLevel() {
        // 2.63 (a 2.625 screen): z16 is point zoom 14.6, z15 13.6.
        assertEquals(TerrainKey(ContourInterval(10.0, 50.0)), DrawnImagery.key(TerrainMode.contours, 16, 2.63, true, range))
        assertEquals(TerrainKey(ContourInterval(20.0, 100.0)), DrawnImagery.key(TerrainMode.contours, 15, 2.63, false, null))
        assertEquals(TerrainKey(ContourInterval(5.0, 25.0)), DrawnImagery.key(TerrainMode.contours, 22, 2.63, false, null))
        // z12 has none at 2.63 (point zoom 10.6), 100 m at 2 (point zoom 11).
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.contours, 12, 2.63, false, null))
        assertEquals(TerrainKey(ContourInterval(100.0, 500.0)), DrawnImagery.key(TerrainMode.contours, 12, 2.0, false, null))
        // Below z11 never.
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.contours, 10, 1.0, false, null))
    }

    @Test
    fun thePhonesContoursAreDrawnAtTheIPhonesScaleWhateverItsDensity() {
        val s = TileLevels.PHONE_CONTOUR_SCALE
        assertEquals(3.0, s, 0.0)
        // z16 is point zoom 14.4 (01-map), z15 13.4 (22-contours-and-colours), as on the iPhone.
        assertEquals(TerrainKey(ContourInterval(10.0, 50.0)), DrawnImagery.key(TerrainMode.contours, 16, s, false, null))
        assertEquals(TerrainKey(ContourInterval(20.0, 100.0)), DrawnImagery.key(TerrainMode.contours, 15, s, false, null))
        // z18 (16-measure-two-points, 19-measure-area): point zoom 16.4, the iPhone's 10 m / 50 m. At a 2.625 phone's
        // own scale it would be 16.6: 5 m / 25 m.
        assertEquals(TerrainKey(ContourInterval(10.0, 50.0)), DrawnImagery.key(TerrainMode.contours, 18, s, false, null))
        assertEquals(TerrainKey(ContourInterval(5.0, 25.0)), DrawnImagery.key(TerrainMode.contours, 18, 2.63, false, null))
        assertEquals(TerrainKey(ContourInterval(5.0, 25.0)), DrawnImagery.key(TerrainMode.contours, 19, s, false, null))
        // The tiles say the same scale in their URL, so they are drawn with that interval and the iPhone's line widths
        // and labels; the source starts at z13, as the iPhone's contours do.
        assertEquals("https://satmap.local/contours/7/300/{z}/{x}/{y}", TileUrls.contours(7, s))
        assertEquals(13, TileLevels.contourMinZoom(s))
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.contours, 12, s, false, null))
    }

    @Test
    fun theColoursRangeWhileHeightsAreOnScreen() {
        assertEquals(TerrainKey(ContourInterval(10.0, 50.0), range), DrawnImagery.key(TerrainMode.contoursAndColours, 16, 2.63, true, range))
        assertEquals(TerrainKey(null, range), DrawnImagery.key(TerrainMode.colours, 16, 2.63, true, range))
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.colours, 16, 2.63, false, range))
        assertEquals(TerrainKey(null, range), DrawnImagery.key(TerrainMode.contoursAndColours, 8, 2.63, true, range))
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.contoursAndColours, 7, 2.63, true, range))
    }

    @Test
    fun nothingWhenTheModeShowsNeitherOrTheMapIsTooNarrow() {
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.off, 16, 2.63, true, range))
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.drone, 16, 2.63, true, range))
        assertEquals(TerrainKey(), DrawnImagery.key(TerrainMode.contoursAndColours, null, 2.63, true, range))
    }

    @Test
    fun whichLayersAModeShows() {
        assertTrue(DrawnImagery.shows(TileKind.imagery, TerrainMode.off))
        assertTrue(DrawnImagery.shows(TileKind.contours, TerrainMode.contoursAndColours))
        assertFalse(DrawnImagery.shows(TileKind.colours, TerrainMode.contours))
        assertTrue(DrawnImagery.shows(TileKind.colours, TerrainMode.colours))
        assertFalse(DrawnImagery.shows(TileKind.contours, TerrainMode.drone))
    }
}
