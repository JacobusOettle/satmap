package com.jacobusoettle.satmap.offline

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.jacobusoettle.satmap.ui.MBTILES_MIME_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Shares the areas' own MBTiles files read-only, under readable names ("Farm - satellite.mbtiles"): no copy, as a
 *  15 GB area can't be copied (DESIGN.md D8; iOS shares an APFS clone, which takes no space). The guarantee a clone
 *  gives comes from not writing while they are read: the export folds the write-ahead log into the file, and while
 *  another app has one of an area's files open, that area doesn't download (`ExportShares`). Only URIs registered in
 *  `ExportShares` open, and only for reading. */
class BackupFileProvider : FileProvider() {
    companion object {
        /** MBTiles has no registered type: providers report octet-stream, vnd.sqlite3 and others. */
        const val mimeType = MBTILES_MIME_TYPE

        fun authority(context: Context) = context.packageName + ".backup"

        /** The address another app reads `file` at, under `displayName`. */
        fun uriFor(context: Context, file: File, displayName: String): Uri =
            getUriForFile(context, authority(context), file, displayName)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        // Nothing for what isn't shared.
        val share = ExportShares[uri] ?: return MatrixCursor(columns, 0)
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        for (c in columns) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> values += share.displayName
                OpenableColumns.SIZE -> values += share.file.length()
                else -> continue
            }
            names += c
        }
        return MatrixCursor(names.toTypedArray(), 1).apply { addRow(values.toTypedArray()) }
    }

    override fun getType(uri: Uri): String = mimeType

    /** Read-only, and only what was exported. The area holds its download while the file is open; the close (also
     *  when the reading app's process ends) lets it go on. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Backups are shared read-only")
        val share = ExportShares[uri] ?: throw FileNotFoundException("Not shared: $uri")
        ExportShares.opened(share.area)
        try {
            return ParcelFileDescriptor.open(share.file, ParcelFileDescriptor.MODE_READ_ONLY,
                                             Handler(Looper.getMainLooper())) { ExportShares.closed(share.area) }
        } catch (e: Exception) {
            ExportShares.closed(share.area)
            throw e
        }
    }
}

/** The files of the last export, by URI, and how many readers each area has open. The provider's calls come on binder
 *  threads, so it is thread-safe; `OfflineManager` follows `readerCounts` on the main thread. */
object ExportShares {
    /** One shared file: its area, the file and the name the receiving app sees. */
    data class Share(val area: UUID, val file: File, val displayName: String)

    private val shares = LinkedHashMap<Uri, Share>()
    private val _readerCounts = MutableStateFlow<Map<UUID, Int>>(emptyMap())

    /** The open readers per area (areas with none left out). */
    val readerCounts: StateFlow<Map<UUID, Int>> = _readerCounts.asStateFlow()

    /** Kept until the next export or the area's deletion. */
    @Synchronized
    fun register(uri: Uri, share: Share) {
        shares[uri] = share
    }

    @Synchronized
    operator fun get(uri: Uri): Share? = shares[uri]

    /** Every registered file, in the order of the export. */
    @Synchronized
    fun all(): List<Share> = shares.values.toList()

    /** One more reader. */
    fun opened(area: UUID) {
        _readerCounts.update { it + (area to (it[area] ?: 0) + 1) }
    }

    /** One reader fewer; the last close lets the area download again. */
    fun closed(area: UUID) {
        _readerCounts.update {
            val n = (it[area] ?: 0) - 1
            if (n > 0) it + (area to n) else it - area
        }
    }

    /** The open readers. */
    fun readers(area: UUID): Int = _readerCounts.value[area] ?: 0

    /** Forgets every share (a new export). Readers still open keep their files, and hold their areas until they close. */
    @Synchronized
    fun clear() {
        shares.clear()
    }

    /** Forgets an area's shares (the area is deleted): its readers no longer hold anything. */
    @Synchronized
    fun forget(area: UUID) {
        shares.values.removeAll { it.area == area }
        _readerCounts.update { it - area }
    }

    /** Forgets everything, the readers too (tests: the object outlives each test). */
    @Synchronized
    internal fun resetForTests() {
        shares.clear()
        _readerCounts.value = emptyMap()
    }
}
