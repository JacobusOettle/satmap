package com.jacobusoettle.satmap.offline

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Sharing backups: the `ACTION_SEND_MULTIPLE` chooser with a "Save to a folder" entry, and the copy into a folder
 *  picked with the Storage Access Framework (stock Android has no "Save to Files" target). */
object BackupSharing {
    /** "Save to a folder" in the chooser arrives at `MainActivity` with this action. */
    const val ACTION_SAVE_TO_FOLDER = "com.jacobusoettle.satmap.SAVE_TO_FOLDER"

    /** The chooser for the shared files. */
    fun chooser(context: Context, uris: List<Uri>): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE), null) // M4: offline spec §2.8.

    /** Copies each shared file into the picked folder in 4 MiB pieces, with backupStatus and backupProgress. */
    suspend fun saveToFolder(context: Context, tree: Uri) {
        // M4: offline spec §2.8.
    }
}
