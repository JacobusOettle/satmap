package com.jacobusoettle.satmap.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jacobusoettle.satmap.offline.BackupSharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** MBTiles has no registered type: providers report octet-stream, vnd.sqlite3 and others. */
const val MBTILES_MIME_TYPE = "application/octet-stream"

/** Files to share, and what to do when the share UI closes (iOS `completionWithItemsHandler`). `saveToFolder` copies
 *  them into a folder the user picks ("Save to a folder", which may be chosen in the chooser). */
class ShareRequest(val uris: List<Uri>, val saveToFolder: ((Uri) -> Unit)? = null, val completion: () -> Unit) {
    /** The chooser was opened for it (it isn't opened again when the activity is made again). */
    internal var launched = false
}

/** The system share sheet (Drive, Quick Share, Gmail, … and "Save to a folder"), asked for from outside any composable
 *  (`OfflineManager`): `ShareHost`, always composed with the map screen, opens it over whatever is on screen. Main
 *  thread only. */
object SharePresenter {
    private val _request = MutableStateFlow<ShareRequest?>(null)

    /** The share waiting for, or shown in, the chooser. */
    val request: StateFlow<ShareRequest?> = _request.asStateFlow()
    private val _folderWanted = MutableStateFlow(false)

    /** "Save to a folder" was chosen: `ShareHost` asks for the folder. */
    val folderWanted: StateFlow<Boolean> = _folderWanted.asStateFlow()

    /** The folder picker was opened for the current wish. */
    internal var folderLaunched = false

    /** `ShareHost`s of started activities: the chooser can be shown now. */
    private var hosts = 0

    /** The last share's copy into a folder, for "Save to a folder" (it comes after the chooser has closed). */
    private var saveToFolder: ((Uri) -> Unit)? = null

    /** `completion` runs when the chooser closes, after the chosen app has been handed the files. With no SatMap screen
     *  started there is nothing to show it over, so it runs at once (as Swift does with no window). */
    fun present(r: ShareRequest) {
        if (hosts == 0) return r.completion()
        _request.value?.let { old ->
            _request.value = null
            old.completion()
        }
        saveToFolder = r.saveToFolder
        _request.value = r
    }

    /** "Save to a folder" in the chooser (`MainActivity`, `ACTION_SAVE_TO_FOLDER`). */
    fun requestFolder() {
        if (saveToFolder == null) return
        folderLaunched = false
        _folderWanted.value = true
    }

    /** The chooser closed. */
    internal fun chooserClosed() {
        val r = _request.value ?: return
        _request.value = null
        r.completion()
    }

    /** The folder picker closed: `tree` is the folder picked, or null when it was cancelled. */
    internal fun folderChosen(tree: Uri?) {
        _folderWanted.value = false
        folderLaunched = false
        if (tree != null) saveToFolder?.invoke(tree)
    }

    /** Ends what is open (the chooser or the folder picker), as if closed. */
    fun finished() {
        chooserClosed()
        _folderWanted.value = false
        folderLaunched = false
    }

    internal fun attach() {
        hosts += 1
    }

    internal fun detach() {
        hosts = maxOf(0, hosts - 1)
    }
}

/** The chooser and folder-picker launchers, always composed in `MapScreen`. */
@Composable
fun ShareHost() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request by SharePresenter.request.collectAsState()
    val folderWanted by SharePresenter.folderWanted.collectAsState()
    // The chooser's result is the "share sheet closed" moment, whatever was chosen.
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SharePresenter.chooserClosed()
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        SharePresenter.folderChosen(tree)
    }
    // Shares can be shown while this screen is started.
    DisposableEffect(lifecycle) {
        var attached = false
        val observer = LifecycleEventObserver { _, _ ->
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (started != attached) {
                attached = started
                if (started) SharePresenter.attach() else SharePresenter.detach()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (attached) SharePresenter.detach()
        }
    }
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (r.launched) return@LaunchedEffect
        r.launched = true
        try {
            chooser.launch(BackupSharing.chooser(context, r.uris))
        } catch (e: ActivityNotFoundException) {
            SharePresenter.chooserClosed()
        }
    }
    LaunchedEffect(folderWanted) {
        if (!folderWanted || SharePresenter.folderLaunched) return@LaunchedEffect
        SharePresenter.folderLaunched = true
        try {
            folder.launch(null)
        } catch (e: ActivityNotFoundException) {
            SharePresenter.folderChosen(null)
        }
    }
}
