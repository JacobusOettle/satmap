package com.jacobusoettle.satmap.offline

import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Shares the areas' own MBTiles files read-only, under readable names ("Farm - satellite.mbtiles"): no copy, as a
 *  15 GB area can't be copied (DESIGN.md D8). Only URIs registered in `ExportShares` open, and only for reading; while
 *  one is open its area doesn't download. */
class BackupFileProvider : FileProvider() {
    // M4: query (DISPLAY_NAME, SIZE), getType ("application/octet-stream") and openFile with the reader hold
    // (offline spec §2.8).
}

/** The files of the last export, by URI, and how many readers each area has open. Main thread only. */
object ExportShares {
    /** One shared file: its area, the file and the name the receiving app sees. */
    data class Share(val area: UUID, val file: File, val displayName: String)

    fun register(uri: Uri, share: Share) {
        // M4: kept until the next export or the area's deletion.
    }

    operator fun get(uri: Uri): Share? = null // M4: the registered share.

    fun opened(area: UUID) {
        // M4: one more reader.
    }

    fun closed(area: UUID) {
        // M4: one reader fewer; the last close lets the area download again.
    }

    fun readers(area: UUID): Int = 0 // M4: the open readers.

    fun clear() {
        // M4: forgets every share.
    }
}
