package com.jacobusoettle.satmap.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** MBTiles has no registered type: providers report octet-stream, vnd.sqlite3 and others. */
const val MBTILES_MIME_TYPE = "application/octet-stream"

/** Files to share, and what to do when the share UI closes (iOS `completionWithItemsHandler`). */
class ShareRequest(val uris: List<Uri>, val completion: () -> Unit)

/** The share flow, asked for from outside any composable (`OfflineManager`): `ShareHost` launches each request.
 *  Main thread only. */
object SharePresenter {
    private val _request = MutableStateFlow<ShareRequest?>(null)
    val request: StateFlow<ShareRequest?> = _request.asStateFlow()
    private val _folderWanted = MutableStateFlow(false)

    /** "Save to a folder" was chosen: `ShareHost` asks for the folder. */
    val folderWanted: StateFlow<Boolean> = _folderWanted.asStateFlow()

    fun present(r: ShareRequest) {
        // M4: published for ShareHost; with no started activity the completion runs at once, as Swift does with no
        // window. Until then nothing is shown, so it completes at once.
        r.completion()
    }

    fun requestFolder() {
        // M4: _folderWanted = true.
    }

    /** The chooser or the folder copy closed. */
    fun finished() {
        _request.value = null
        _folderWanted.value = false
    }
}

/** The chooser and folder-picker launchers, always composed in `MapScreen`. */
@Composable
fun ShareHost() {
    // M4: rememberLauncherForActivityResult(StartActivityForResult / OpenDocumentTree) for SharePresenter's requests.
}
