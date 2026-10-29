package com.jacobusoettle.satmap.offline

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.service.chooser.ChooserAction
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.R
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Sharing backups: the `ACTION_SEND_MULTIPLE` chooser with a "Save to a folder" entry, and the copy into a folder
 *  picked with the Storage Access Framework (stock Android has no "Save to Files" target for a USB drive or a folder
 *  on the phone). */
object BackupSharing {
    /** "Save to a folder" in the chooser arrives at `MainActivity` with this action. */
    const val ACTION_SAVE_TO_FOLDER = "com.jacobusoettle.satmap.SAVE_TO_FOLDER"

    /** The chooser for the shared files: Drive, Gmail, Quick Share (the AirDrop of Android), Files … and SatMap's own
     *  "Save to a folder" (a custom action from API 34, an extra target below). */
    fun chooser(context: Context, uris: List<Uri>): Intent {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = BackupFileProvider.mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            if (uris.isNotEmpty()) {
                clipData = ClipData.newUri(context.contentResolver, "SatMap", uris[0]).also { c ->
                    uris.drop(1).forEach { c.addItem(ClipData.Item(it)) }
                }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null)
        val label = context.getString(R.string.save_to_folder)
        if (Build.VERSION.SDK_INT >= 34) {
            val action = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java).setAction(ACTION_SAVE_TO_FOLDER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            chooser.putExtra(
                Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS,
                arrayOf(ChooserAction.Builder(Icon.createWithResource(context, R.drawable.ic_folder), label, action).build()),
            )
        } else {
            chooser.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf(LabeledIntent(Intent(context, SaveToFolderActivity::class.java), context.packageName, label,
                                      R.drawable.ic_folder)),
            )
        }
        return chooser
    }

    /** Copies `files` into the folder `tree` picked with `OpenDocumentTree`, each in 4 MiB pieces, telling `progress`
     *  how many bytes are done. A file is held open (`ExportShares`), so its area doesn't download meanwhile. Returns
     *  the display names of the files that couldn't be saved. */
    fun copyInto(context: Context, tree: Uri, files: List<ExportShares.Share>, progress: (Long) -> Unit): List<String> {
        val cr = context.contentResolver
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val failed = mutableListOf<String>()
        var before = 0L
        for (share in files) {
            ExportShares.opened(share.area)
            var created: Uri? = null
            try {
                created = DocumentsContract.createDocument(cr, folder, BackupFileProvider.mimeType, share.displayName)
                    ?: throw IOException("Not created")
                val output = cr.openOutputStream(created) ?: throw IOException("Can't write")
                output.use { out ->
                    share.file.inputStream().use { input ->
                        copy(input, out) { progress(before + it) }
                    }
                }
            } catch (e: Exception) {
                // Not left half written.
                created?.let { runCatching { DocumentsContract.deleteDocument(cr, it) } }
                failed += share.displayName
            } finally {
                ExportShares.closed(share.area)
            }
            before += share.file.length()
            progress(before)
        }
        return failed
    }

    /** Copies a stream in 4 MiB pieces, telling `progress` how many bytes are done. */
    internal fun copy(input: InputStream, output: OutputStream, progress: (Long) -> Unit) {
        val buffer = ByteArray(4 shl 20)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            progress(done)
        }
        output.flush()
    }
}
