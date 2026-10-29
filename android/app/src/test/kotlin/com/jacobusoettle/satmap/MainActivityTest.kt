package com.jacobusoettle.satmap

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/** KML and KMZ files opened with SatMap or shared to it (iOS's `onOpenURL`): which intents bring which files, and the
 *  map zooming to an imported file or the Blocks sheet showing why it couldn't be imported. (`MainActivity` itself
 *  can't start here: its map needs MapLibre's native code.) */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var incoming: File

    private class FakeMap : MapHost {
        override val centerCoordinate = GeoPoint(0.0, 0.0)
        override val isOnScreen = true
        val zooms = mutableListOf<GeoBounds>()

        override fun pointsApart(a: GeoPoint, b: GeoPoint) = 0.0
        override fun center(on: GeoPoint, animated: Boolean) {}
        override fun zoom(onto: GeoPoint, metersAcross: Double) {}
        override fun zoom(to: GeoBounds) {
            zooms += to
        }

        override fun locationUpdated(fix: GpsFix) {}
        override fun headingUpdated(heading: Double?) {}
        override fun reloadImagery() {}
    }

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
        Connectivity.init(app)
        AppLifecycle.init()
        incoming = File(app.cacheDir, "incoming-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        incoming.deleteRecursively()
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
        AppLifecycle.resetForTests()
    }

    private fun shared(name: String, data: ByteArray): Uri = Uri.fromFile(File(incoming, name).apply { writeBytes(data) })

    private fun fixture(name: String) = File(AndroidSqlDriverTest.fixtures, name).readBytes()

    /** The import reads the file off the main thread and finishes on it. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("finished in time", done())
    }

    @Test
    fun openWithAndShareBringTheirFiles() {
        val a = Uri.parse("content://com.example.files/document/Farm.kml")
        val b = Uri.parse("content://com.example.files/document/Dam.kmz")
        assertEquals(listOf(a), MainActivity.incomingFiles(Intent(Intent.ACTION_VIEW, a)))
        assertEquals(
            listOf(a),
            MainActivity.incomingFiles(
                Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, a),
            ),
        )
        assertEquals(
            listOf(a, b),
            MainActivity.incomingFiles(
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/vnd.google-earth.kml+xml")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b)),
            ),
        )
        // The launcher, a share of text only, and an intent already handled bring none.
        assertEquals(emptyList<Uri>(), MainActivity.incomingFiles(Intent(Intent.ACTION_MAIN)))
        assertEquals(
            emptyList<Uri>(),
            MainActivity.incomingFiles(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi")),
        )
        assertEquals(emptyList<Uri>(), MainActivity.incomingFiles(Intent(Intent.ACTION_VIEW, a).setAction(null)))
    }

    @Test
    fun anImportedFileIsShownOnTheMap() {
        val model = AppModel(app, noHeights)
        val map = FakeMap()
        model.map = map
        assertTrue(MainActivity.openFiles(model, Intent(Intent.ACTION_VIEW, shared("Farm.kml", fixture("blocks.kml")))))
        waitFor { model.kml.files.value.isNotEmpty() && map.zooms.isNotEmpty() }
        val file = model.kml.files.value.single()
        assertEquals("Farm", file.name)
        assertEquals(listOf(file.document.bounds), map.zooms)
        assertNull(model.sheet.value)
        assertNull(model.kml.importError.value)
    }

    @Test
    fun aFileThatIsntKMLOpensTheBlocksSheetWithTheError() {
        val model = AppModel(app, noHeights)
        val map = FakeMap()
        model.map = map
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, shared("Photos.zip", byteArrayOf(0x50, 0x4B, 3, 4, 0, 0)))
        assertTrue(MainActivity.openFiles(model, send))
        waitFor { model.sheet.value != null }
        assertEquals(ActiveSheet.blocks, model.sheet.value)
        assertNotNull(model.kml.importError.value)
        assertTrue(model.kml.files.value.isEmpty())
        assertTrue(map.zooms.isEmpty())
        // Nothing to open: nothing happens.
        assertFalse(MainActivity.openFiles(model, Intent(Intent.ACTION_MAIN)))
    }
}
