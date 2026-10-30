package com.jacobusoettle.satmap.kml

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.IntentCompat
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.core.KMLDocument
import com.jacobusoettle.satmap.core.KMLParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/** Imported KML/KMZ and GPX files (farm blocks, tracks, waypoints etc.), stored in the app and shown on the map (each
 *  can be switched off): `KML/index.json` plus `KML/<UUID>.kml|.kmz|.gpx`, as on the iPhone. Main thread only.
 *  (`File` here is the library's entry; files on disk are written `java.io.File` in full.) */
class KMLLibrary(
    private val context: Context,
    /** Where files are read and parsed (a test passes its own). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One record of `index.json`, as Swift encodes it: `{"id":"<UPPER-UUID>","name":…,"fileName":…}`, plus
     *  `"hidden":true` when switched off (offline spec §2.9). */
    data class Entry(
        val id: UUID,
        val name: String,
        val fileName: String,
        /** Switched off: not on the map. (Optional so the index of an earlier version still reads.) */
        val hidden: Boolean? = null,
    ) {
        /** Swift's `JSONEncoder` form: `hidden` left out when not set. */
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id.toString().uppercase())
            put("name", name)
            put("fileName", fileName)
            hidden?.let { put("hidden", it) }
        }

        companion object {
            /** Reads `toJson()`'s form, in any key order, the id in either case, and null for `hidden` not set. Throws
             *  if it isn't one. */
            fun fromJson(json: JsonElement): Entry {
                val o = json.jsonObject
                fun string(key: String): String {
                    val p = o[key]?.jsonPrimitive ?: throw IllegalArgumentException("No $key")
                    require(p.isString) { "Not a string: $p" }
                    return p.content
                }
                val id = string("id")
                require(Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}").matches(id)) { "Not a UUID: $id" }
                return Entry(
                    id = UUID.fromString(id),
                    name = string("name"),
                    fileName = string("fileName"),
                    hidden = o["hidden"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean,
                )
            }
        }
    }

    data class File(val id: UUID, val name: String, val fileName: String, val document: KMLDocument, val isShown: Boolean)

    companion object {
        /** The types the import picker offers (Blocks +, several files at once): iOS's kml, kmz, gpx, xml and zip,
         *  plus octet-stream, which many providers report for .kml, .kmz and .gpx (DESIGN.md D13). */
        val pickerTypes = arrayOf(
            "application/vnd.google-earth.kml+xml", "application/vnd.google-earth.kmz", "application/gpx+xml",
            "application/xml", "text/xml", "application/zip", "application/octet-stream",
        )

        /** The files another app sends SatMap (iOS `onOpenURL`): "Open with" (`ACTION_VIEW`, its data) and "Share"
         *  (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`, their streams). Each goes to `importFile`. */
        fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.filterNotNull()
                    ?: emptyList()
            else -> emptyList()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    /** Blocks in their files' own colours (on unless switched off); else all in the app's white. */
    private val _useFileColours = MutableStateFlow(Defaults.bool("kmlFileColours") ?: true)
    val useFileColours: StateFlow<Boolean> = _useFileColours.asStateFlow()

    fun setUseFileColours(v: Boolean) {
        _useFileColours.value = v
        Defaults.putBoolean("kmlFileColours", v)
    }

    private val indexFile = java.io.File(AppPaths.kml, "index.json")

    /** Every imported file, in order. Loaded at once at startup so an import arriving early ("Open with SatMap"
     *  launching the app) can't overwrite it; the documents are parsed in the background. */
    private var entries: List<Entry> = emptyList()

    init {
        readIndex()?.let { entries = it }
        val toLoad = entries
        scope.launch {
            val loaded = withContext(io) {
                toLoad.mapNotNull { e ->
                    try {
                        file(e, KMLParser.parseKMLOrGPX(java.io.File(AppPaths.kml, e.fileName).readBytes()))
                    } catch (x: Exception) {
                        null
                    }
                }
            }
            // Keep anything imported meanwhile, skip files deleted meanwhile, and take any change made meanwhile.
            val current = entries.associateBy { it.id }
            _files.value = loaded.mapNotNull { f -> current[f.id]?.let { file(it, f.document) } } +
                _files.value.filter { f -> loaded.none { it.id == f.id } }
        }
    }

    /** What reading an imported file came to, off the main thread. */
    private sealed interface Import {
        class Done(val entry: Entry, val file: File) : Import
        class Failed(val message: String) : Import
    }

    /** Imports a .kml/.kmz/.gpx picked in the app or shared to it ("Open with SatMap"). It is read at once, off the
     *  main thread, as the permission to read it lives with the activity that received it. */
    fun importFile(uri: Uri, completion: ((File?) -> Unit)? = null) {
        scope.launch {
            when (val result = withContext(io) { read(uri) }) {
                is Import.Done -> {
                    entries = entries + result.entry
                    _files.value = _files.value + result.file
                    saveIndex()
                    completion?.invoke(result.file)
                }
                is Import.Failed -> {
                    _importError.value = result.message
                    completion?.invoke(null)
                }
            }
        }
    }

    /** Reads, parses and stores the file (no copy to clean up afterwards, unlike iOS's inbox: a content URI is
     *  read in place). */
    private fun read(uri: Uri): Import {
        val fileName = fileName(uri)
        val displayName = fileName.lastIndexOf('.').let { if (it > 0) fileName.substring(0, it) else fileName }
        val data = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Import.Failed("Couldn't read $fileName: unknown error")
        } catch (e: Exception) {
            return Import.Failed("Couldn't read $fileName: ${e.localizedMessage ?: "unknown error"}")
        }
        return try {
            val doc = KMLParser.parseKMLOrGPX(data)
            val id = UUID.randomUUID()
            val isZip = data.size >= 2 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
            val ext = if (doc.format == KMLDocument.Format.gpx) "gpx" else if (isZip) "kmz" else "kml"
            val entry = Entry(id = id, name = displayName, fileName = "${id.toString().uppercase()}.$ext")
            write(java.io.File(AppPaths.kml, entry.fileName), data)
            Import.Done(entry, file(entry, doc))
        } catch (e: Exception) {
            Import.Failed("$fileName: ${e.localizedMessage ?: e.toString()}")
        }
    }

    /** The name the providing app gives the file ("Farm.kml"), else the last part of its address. */
    private fun fileName(uri: Uri): String {
        val shown = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return shown?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment ?: uri.toString()
    }

    /** Shows a file's blocks on the map, or takes them off. */
    fun setShown(id: UUID, shown: Boolean) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0 || (entries[i].hidden != true) == shown) return
        val entry = entries[i].copy(hidden = if (shown) null else true)
        entries = entries.toMutableList().also { it[i] = entry }
        val j = _files.value.indexOfFirst { it.id == id }
        if (j >= 0) _files.value = _files.value.toMutableList().also { it[j] = file(entry, it[j].document) }
        saveIndex()
    }

    fun delete(id: UUID) {
        val e = entries.firstOrNull { it.id == id } ?: return
        entries = entries.filter { it.id != id }
        _files.value = _files.value.filter { it.id != id }
        java.io.File(AppPaths.kml, e.fileName).delete()
        saveIndex()
    }

    private fun file(e: Entry, document: KMLDocument) =
        File(id = e.id, name = e.name, fileName = e.fileName, document = document, isShown = e.hidden != true)

    /** The saved index, or null if there is none or it can't be read (it is then written afresh on the next change,
     *  as on iOS). */
    private fun readIndex(): List<Entry>? = try {
        Json.parseToJsonElement(AtomicFile(indexFile).readFully().toString(Charsets.UTF_8)).jsonArray.map(Entry::fromJson)
    } catch (e: Exception) {
        null
    }

    private fun saveIndex() {
        try {
            write(indexFile, JsonArray(entries.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // As Swift's `try?`: the index stays as it was.
        }
    }

    /** Writes the whole file or nothing (Swift's `.atomic`). */
    private fun write(file: java.io.File, data: ByteArray) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(data)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }
}
