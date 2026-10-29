package com.jacobusoettle.satmap.kml

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** `KMLLibrary`: the iPhone's storage layout and `index.json`, imports, switching files off, deleting, the colours
 *  setting, and an import arriving while the saved files are still being read. */
@RunWith(AndroidJUnit4::class)
class KMLLibraryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fixtures = AndroidSqlDriverTest.fixtures
    private lateinit var incoming: File
    private val index get() = File(AppPaths.kml, "index.json")

    /** Files to and from the library are read on the test's thread, at once. */
    private val now: CoroutineDispatcher = Dispatchers.Unconfined

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().clear().commit()
        AppPaths.init(app)
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
        incoming = File(app.cacheDir, "incoming-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Defaults.setLaunchArguments(null)
        incoming.deleteRecursively()
        AppPaths.kml.deleteRecursively()
        AppPaths.kml.mkdirs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A file another app hands over, under this name. */
    private fun shared(name: String, data: ByteArray): Uri = Uri.fromFile(File(incoming, name).apply { writeBytes(data) })

    private fun fixture(name: String) = File(fixtures, name).readBytes()

    private fun savedIndex(): List<JsonObject> = Json.parseToJsonElement(index.readText()).jsonArray.map { it.jsonObject }

    private fun import(library: KMLLibrary, uri: Uri): KMLLibrary.File? {
        var result: KMLLibrary.File? = null
        var called = false
        library.importFile(uri) {
            result = it
            called = true
        }
        idle()
        assertTrue("completion called", called)
        return result
    }

    @Test
    fun aKMZIsStoredAsKmzAndAKMLAsKml() {
        val library = KMLLibrary(app, now)
        val kmz = fixture("blocks.kmz")
        val zipped = import(library, shared("Farm blocks.kmz", kmz))!!
        assertEquals("Farm blocks", zipped.name) // the file's name without its extension
        assertTrue(zipped.fileName, zipped.fileName.endsWith(".kmz"))
        assertEquals(zipped.id.toString().uppercase() + ".kmz", zipped.fileName)
        assertArrayEquals(kmz, File(AppPaths.kml, zipped.fileName).readBytes())
        assertTrue(zipped.isShown)
        assertTrue(zipped.document.features.isNotEmpty())

        val plain = import(library, shared("blocks.v2.kml", fixture("blocks.kml")))!!
        assertEquals("blocks.v2", plain.name)
        assertEquals(plain.id.toString().uppercase() + ".kml", plain.fileName)
        assertEquals(listOf(zipped, plain), library.files.value)
        assertNull(library.importError.value)

        // index.json as the iPhone writes it: the id in capitals, no "hidden" while shown.
        val saved = savedIndex()
        assertEquals(2, saved.size)
        assertEquals(setOf("id", "name", "fileName"), saved[0].keys)
        assertEquals(zipped.id.toString().uppercase(), saved[0]["id"]!!.jsonPrimitive.content)
        assertEquals("Farm blocks", saved[0]["name"]!!.jsonPrimitive.content)
        assertEquals(zipped.fileName, saved[0]["fileName"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSavedFilesAreLoadedAgain() {
        val library = KMLLibrary(app, now)
        val first = import(library, shared("North.kml", fixture("blocks.kml")))!!
        val second = import(library, shared("South.kmz", fixture("blocks.kmz")))!!
        val again = KMLLibrary(app, now)
        idle()
        assertEquals(listOf(first.id, second.id), again.files.value.map { it.id })
        assertEquals(listOf("North", "South"), again.files.value.map { it.name })
        assertEquals(first.document, again.files.value[0].document)
    }

    @Test
    fun aFileSwitchedOffIsHiddenInTheIndexAndStaysOff() {
        val library = KMLLibrary(app, now)
        val f = import(library, shared("Farm.kml", fixture("blocks.kml")))!!
        library.setShown(f.id, false)
        assertFalse(library.files.value.single().isShown)
        assertEquals("true", savedIndex().single()["hidden"]?.jsonPrimitive?.content)

        val again = KMLLibrary(app, now)
        idle()
        assertFalse(again.files.value.single().isShown)
        again.setShown(f.id, true)
        assertTrue(again.files.value.single().isShown)
        assertEquals(setOf("id", "name", "fileName"), savedIndex().single().keys) // left out when shown
    }

    @Test
    fun theIndexOfTheIPhoneAndOfTheScreenshotWorkflowReads() {
        File(AppPaths.kml, "6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F.kml").writeBytes(fixture("blocks.kml"))
        File(AppPaths.kml, "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.kmz").writeBytes(fixture("blocks.kmz"))
        index.writeText(
            """[{"id":"6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F","name":"Demo farm","fileName":"6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F.kml"},""" +
                """{"fileName":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.kmz","hidden":true,"name":"Block\/2","id":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"}]""",
        )
        val library = KMLLibrary(app, now)
        idle()
        val files = library.files.value
        assertEquals(listOf("Demo farm", "Block/2"), files.map { it.name })
        assertEquals(listOf(true, false), files.map { it.isShown })
        assertEquals(UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), files[1].id)
    }

    @Test
    fun aDeleteRemovesTheStoredFile() {
        val library = KMLLibrary(app, now)
        val keep = import(library, shared("Keep.kml", fixture("blocks.kml")))!!
        val gone = import(library, shared("Gone.kmz", fixture("blocks.kmz")))!!
        val stored = File(AppPaths.kml, gone.fileName)
        assertTrue(stored.exists())
        library.delete(gone.id)
        assertFalse(stored.exists())
        assertEquals(listOf(keep), library.files.value)
        assertEquals(listOf(keep.id.toString().uppercase()), savedIndex().map { it["id"]!!.jsonPrimitive.content })
        assertTrue(File(AppPaths.kml, keep.fileName).exists())
    }

    @Test
    fun theFilesOwnColoursAreUsedUntilSwitchedOff() {
        val library = KMLLibrary(app, now)
        assertTrue(library.useFileColours.value) // never set
        library.setUseFileColours(false)
        assertFalse(library.useFileColours.value)
        assertFalse(Defaults.prefs.getBoolean("kmlFileColours", true))
        assertFalse(KMLLibrary(app, now).useFileColours.value)
        library.setUseFileColours(true)
        assertTrue(KMLLibrary(app, now).useFileColours.value)
        // A launch extra counts first, as iOS's argument domain.
        Defaults.setLaunchArguments(Bundle().apply { putBoolean("kmlFileColours", false) })
        assertFalse(KMLLibrary(app, now).useFileColours.value)
    }

    @Test
    fun anImportDuringTheInitialLoadIsKept() {
        // Two files saved earlier; one is deleted before the saved files are read.
        val saved = KMLLibrary(app, now)
        val north = import(saved, shared("North.kml", fixture("blocks.kml")))!!
        val old = import(saved, shared("Old.kml", fixture("blocks.kml")))!!

        val io = Held()
        val library = KMLLibrary(app, io) // reading the saved files is held up …
        library.delete(old.id)
        var imported: KMLLibrary.File? = null
        library.importFile(shared("South.kmz", fixture("blocks.kmz"))) { imported = it } // … while a file arrives
        io.runLast() // the new file is read first
        idle()
        assertEquals(listOf("South"), library.files.value.map { it.name })
        assertEquals(listOf(north.id, imported!!.id), savedIndex().map { UUID.fromString(it["id"]!!.jsonPrimitive.content) })

        io.runLast() // then the saved ones
        idle()
        assertEquals(listOf("North", "South"), library.files.value.map { it.name })
        assertEquals(north.id, library.files.value[0].id)
        assertSame(imported, library.files.value[1])
        assertEquals(listOf(north.id, imported!!.id), savedIndex().map { UUID.fromString(it["id"]!!.jsonPrimitive.content) })
    }

    @Test
    fun importErrorsReadAsOnTheIPhone() {
        val library = KMLLibrary(app, now)
        assertNull(import(library, shared("notes.txt", "hello".toByteArray())))
        assertEquals("notes.txt: The file could not be read as KML.", library.importError.value)
        library.clearImportError()
        assertNull(library.importError.value)

        val empty = """<?xml version="1.0"?><kml><Document><name>x</name></Document></kml>"""
        assertNull(import(library, shared("empty.kml", empty.toByteArray())))
        assertEquals("empty.kml: The file contains no points, lines or polygons.", library.importError.value)

        assertNull(import(library, Uri.fromFile(File(incoming, "gone.kml"))))
        assertTrue(library.importError.value!!, library.importError.value!!.startsWith("Couldn't read gone.kml: "))
        assertEquals(emptyList<KMLLibrary.File>(), library.files.value)
        assertEquals(emptyList<String>(), AppPaths.kml.list()!!.filter { it != "index.json" })
    }

    @Test
    fun aSharedFileIsNamedAsTheProvidingAppNamesIt() {
        // As Gmail or WhatsApp share a file: a content URI whose address says nothing of its name.
        val file = File(incoming, "attachment-1").apply { writeBytes(fixture("blocks.kmz")) }
        Provider.file = file
        Robolectric.buildContentProvider(Provider::class.java).create("com.example.mail.files")
        val uri = Uri.parse("content://com.example.mail.files/attachments/1")
        val library = KMLLibrary(app, now)
        val f = import(library, uri)!!
        assertEquals("Block plan 2024", f.name)
        assertTrue(f.fileName.endsWith(".kmz"))
        assertArrayEquals(file.readBytes(), File(AppPaths.kml, f.fileName).readBytes())
    }

    /** Another app's files: "Block plan 2024.kmz", whatever the address. */
    class Provider : ContentProvider() {
        companion object {
            lateinit var file: File
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                           sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("Block plan 2024.kmz", file.length()))
            }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    /** Runs the work given to it when told, newest first. */
    private class Held : CoroutineDispatcher() {
        private val waiting = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            waiting.addLast(block)
        }

        fun runLast() {
            waiting.removeLast().run()
        }
    }
}
