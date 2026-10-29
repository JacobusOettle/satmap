package com.jacobusoettle.satmap.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLParser
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** `AppModel`'s offline areas, as `AppModel.swift`: drawing one with the lasso (the draft, then the pending area named
 *  "Area <n>"), a KML file's area with its 150 m margin, and a download clearing what led to it. SatMap is in the
 *  background here, so the areas added are queued and no download starts. */
@RunWith(AndroidJUnit4::class)
class AppModelAreaTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private val ring = listOf(GeoPoint(-25.0, 31.0), GeoPoint(-25.0, 31.01), GeoPoint(-25.01, 31.01))
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
    }

    @After
    fun tearDown() {
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        AppLifecycle.resetForTests()
    }

    @Test
    fun drawingStartsWithTheSheetClosedAndTheMapNotFollowing() {
        model.setSheet(ActiveSheet.offline)
        model.setDraftArea(ring)
        model.setLassoActive(false)
        model.startDrawingArea()
        assertNull(model.sheet.value)
        assertNull(model.draftArea.value)
        assertFalse(model.following.value)
        assertTrue(model.lassoActive.value)
        assertTrue(model.isDrawingArea.value)
        assertNull(model.pendingArea.value)
    }

    @Test
    fun aLoopOfThreePointsOrMoreIsTheDraft() {
        model.startDrawingArea()
        model.finishDrawing(ring.take(2))
        assertNull(model.draftArea.value)
        model.finishDrawing(ring)
        assertEquals(ring, model.draftArea.value)
        assertTrue(model.isDrawingArea.value)
        // "Redraw", or a loop too small: none.
        model.finishDrawing(emptyList())
        assertNull(model.draftArea.value)
        assertTrue(model.isDrawingArea.value)
    }

    @Test
    fun cancellingEndsDrawingAndDropsTheDraft() {
        model.startDrawingArea()
        model.finishDrawing(ring)
        model.cancelDrawing()
        assertFalse(model.isDrawingArea.value)
        assertNull(model.draftArea.value)
        assertNull(model.pendingArea.value)
    }

    @Test
    fun continuingAsksToDownloadTheDrawnAreaNamedAfterTheAreasThereAre() {
        model.startDrawingArea()
        // Nothing drawn yet: nothing to continue with.
        model.confirmDrawnArea()
        assertTrue(model.isDrawingArea.value)
        assertNull(model.pendingArea.value)
        model.finishDrawing(ring)
        model.confirmDrawnArea()
        assertFalse(model.isDrawingArea.value)
        val pending = model.pendingArea.value!!
        assertEquals("Area 1", pending.name)
        assertEquals(CoverageShape(polygons = listOf(ring)), pending.shape)
        // The loop stays on the map until the download is started or the sheet closed.
        assertEquals(ring, model.draftArea.value)
        // With one area saved, the next is "Area 2".
        model.startDownload(pending, roads = null, preset = null, maxZoom = 18, allowCellular = false)
        model.startDrawingArea()
        model.finishDrawing(ring.reversed())
        model.confirmDrawnArea()
        assertEquals("Area 2", model.pendingArea.value!!.name)
        assertNotEquals(pending.id, model.pendingArea.value!!.id)
    }

    @Test
    fun startingTheDownloadAddsTheAreaAndClearsThePendingAreaAndTheDraft() {
        model.startDrawingArea()
        model.finishDrawing(ring)
        model.confirmDrawnArea()
        val pending = model.pendingArea.value!!
        // The name as typed in the sheet.
        model.startDownload(pending.copy(name = "Home farm"), roads = null, maxZoom = 17, allowCellular = true)
        assertNull(model.pendingArea.value)
        assertNull(model.draftArea.value)
        val area = model.offline.areas.value.single()
        assertEquals("Home farm", area.name)
        assertEquals(pending.shape, area.shape)
        assertNull(area.roads)
        assertNull(area.preset)
        assertEquals(17, area.maxZoom)
        assertTrue(area.allowCellular)
        assertEquals(OfflineArea.Status.queued, area.status)
    }

    @Test
    fun aDownloadAlongRoadsKeepsTheRoadsAndThePreset() {
        val road = listOf(GeoPoint(-25.0, 31.0), GeoPoint(-25.005, 31.005))
        model.setPendingArea(PendingArea(name = "Kruger", shape = CoverageShape(polygons = listOf(ring))))
        model.startDownload(model.pendingArea.value!!, roads = listOf(road), preset = "kruger", maxZoom = 16,
                            allowCellular = false)
        val area = model.offline.areas.value.single()
        assertEquals(listOf(road), area.roads)
        assertEquals("kruger", area.preset)
        assertFalse(area.allowCellular)
        assertNull(model.pendingArea.value)
    }

    @Test
    fun cancellingTheDownloadSheetDropsThePendingAreaAndTheDraft() {
        model.startDrawingArea()
        model.finishDrawing(ring)
        model.confirmDrawnArea()
        model.discardPendingArea()
        assertNull(model.pendingArea.value)
        assertNull(model.draftArea.value)
        assertEquals(emptyList<OfflineArea>(), model.offline.areas.value)
    }

    @Test
    fun aKMLFilesAreaIsEveryShapeInItWithA150mMargin() {
        val document = KMLParser.parse(File(AndroidSqlDriverTest.fixtures, "blocks.kml").readBytes())
        val file = KMLLibrary.File(UUID.randomUUID(), "Farm blocks", "farm.kml", document, isShown = true)
        model.setSheet(ActiveSheet.offline)
        model.prepareArea(from = file)
        assertNull(model.sheet.value)
        val pending = model.pendingArea.value!!
        assertEquals("Farm blocks", pending.name)
        assertEquals(document.coverageShape(bufferMeters = 150.0), pending.shape)
        assertEquals(150.0, pending.shape.bufferMeters, 0.0)
        assertFalse(pending.shape.isEmpty)
    }

    @Test
    fun theTrackDownloadMakesTheTrackAreaThenUpdatesIt() {
        val walk = listOf(GeoPoint(-25.0, 31.0), GeoPoint(-25.002, 31.001), GeoPoint(-25.004, 31.0))
        model.startTrackDownload("Where I've been", listOf(walk), maxZoom = 18, allowCellular = false)
        val track = model.offline.areas.value.single()
        assertEquals(true, track.isTrack)
        assertEquals("Where I've been", track.name)
        assertEquals(listOf(walk), track.roads)
        assertEquals(18, track.maxZoom)
        // Walked further: the same area, with the longer track.
        val further = walk + GeoPoint(-25.006, 31.002)
        model.startTrackDownload("My walks", listOf(further), maxZoom = 17, allowCellular = true)
        val updated = model.offline.areas.value.single()
        assertEquals(track.id, updated.id)
        assertEquals("My walks", updated.name)
        assertEquals(listOf(further), updated.roads)
        assertEquals(17, updated.maxZoom)
        assertTrue(updated.allowCellular)
        assertNotNull(updated.shape)
    }
}
