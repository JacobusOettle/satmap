package com.jacobusoettle.satmap.kml

import android.content.Context
import android.net.Uri
import com.jacobusoettle.satmap.core.KMLDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The imported KML/KMZ files (the farm blocks): `KML/index.json` plus `KML/<UUID>.kml|.kmz`, each parsed into a
 *  `KMLDocument` and shown or hidden on the map. Main thread only. (`File` here is the library's entry; files on
 *  disk are written `java.io.File` in full.) */
class KMLLibrary(private val context: Context) {
    data class File(val id: UUID, val name: String, val fileName: String, val document: KMLDocument, val isShown: Boolean)

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    /** The preference `kmlFileColours`, true when never set. */
    private val _useFileColours = MutableStateFlow(true)
    val useFileColours: StateFlow<Boolean> = _useFileColours.asStateFlow()

    fun setUseFileColours(v: Boolean) {
        _useFileColours.value = v
        // M3: written to kmlFileColours.
    }

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    /** Reads (at once, as the URI grant lives with the receiving activity) and parses off the main thread, stores the
     *  file and calls back with it, or sets `importError` and calls back with null. */
    fun importFile(uri: Uri, completion: ((File?) -> Unit)? = null) {
        // M3: offline spec §1.14 and §2.9.
        completion?.invoke(null)
    }

    fun setShown(id: UUID, shown: Boolean) {
        // M3: updates the entry and its File, saves the index.
    }

    fun delete(id: UUID) {
        // M3: removes the entry, the File and the stored file, saves the index.
    }
}
