package com.jacobusoettle.satmap.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jacobusoettle.satmap.offline.BackupSharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** MBTiles has no registered type: providers report octet-stream, vnd.sqlite3 and others. */
const val MBTILES_MIME_TYPE = "application/octet-stream"

/** Files to share, and what to do when the share UI closes (iOS `completionWithItemsHandler`, which runs once, when
 *  that sheet closes). `saveToFolder` copies them into a folder the user picks ("Save to a folder", which may be chosen
 *  in the chooser). */
class ShareRequest(val uris: List<Uri>, val saveToFolder: ((Uri) -> Unit)? = null, val completion: () -> Unit) {
    /** The `ShareHost` that opened the chooser for it, or null until then (it isn't opened again when the activity is
     *  made again). The chooser's result comes back only to that host. */
    internal var launchedBy: String? = null

    /** "Save to a folder" was chosen in its chooser: it ends when the folder picker closes, as iOS's sheet closes
     *  after Save to Files has written the files. */
    internal var toFolder = false

    private var completed = false

    /** Runs `completion`, once. */
    internal fun complete() {
        if (completed) return
        completed = true
        completion()
    }
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

    /** The `ShareHost` that opened the folder picker for the current wish, or null until then. */
    internal var folderLaunchedBy: String? = null

    /** `ShareHost`s of started activities: the chooser can be shown now. */
    private var hosts = 0

    /** The last share's copy into a folder, for "Save to a folder" (it can come after the chooser has closed). */
    private var saveToFolder: ((Uri) -> Unit)? = null

    /** The share whose chooser closed as "Save to a folder" was chosen: it ends when the folder picker closes. */
    private var waitingForFolder: ShareRequest? = null

    /** `completion` runs when the chooser closes, after the chosen app has been handed the files (with "Save to a
     *  folder", when the folder picker closes). With no SatMap screen started there is nothing to show it over, so it
     *  runs at once (as Swift does with no window). A share still open ends first. */
    fun present(r: ShareRequest) {
        if (hosts == 0) return r.complete()
        finished()
        saveToFolder = r.saveToFolder
        _request.value = r
    }

    /** "Save to a folder" in the chooser (`SaveToFolderActivity`, then `MainActivity` with `ACTION_SAVE_TO_FOLDER`):
     *  asked for once until the folder picker closes. Chosen while the chooser is open, the share ends when the folder
     *  picker closes, not when the chooser does. */
    fun requestFolder() {
        if (saveToFolder == null || _folderWanted.value) return
        _request.value?.toFolder = true
        folderLaunchedBy = null
        _folderWanted.value = true
    }

    /** The chooser closed. */
    internal fun chooserClosed() {
        val r = _request.value ?: return
        _request.value = null
        if (r.toFolder && _folderWanted.value) waitingForFolder = r else r.complete()
    }

    /** The folder picker closed: `tree` is the folder picked, or null when it was cancelled. The copy starts before the
     *  share ends. */
    internal fun folderChosen(tree: Uri?) {
        _folderWanted.value = false
        folderLaunchedBy = null
        if (tree != null) saveToFolder?.invoke(tree)
        waitingForFolder?.let { r ->
            waitingForFolder = null
            r.complete()
        }
    }

    /** Ends what is open (the chooser or the folder picker), as if closed. */
    fun finished() {
        val r = _request.value
        val w = waitingForFolder
        _request.value = null
        waitingForFolder = null
        _folderWanted.value = false
        folderLaunchedBy = null
        r?.complete()
        w?.complete()
    }

    /** The activity of `host` is gone for good (finished, not made again): what it opened can't report back, so it
     *  ends, and so does a folder wish no host has opened yet. */
    internal fun hostGone(host: String) {
        if (_request.value?.launchedBy == host) chooserClosed()
        val folderBy = folderLaunchedBy
        if (_folderWanted.value && (folderBy == null || folderBy == host)) folderChosen(null)
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
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request by SharePresenter.request.collectAsState()
    val folderWanted by SharePresenter.folderWanted.collectAsState()
    // Kept, as the launchers' own keys are, when the activity is made again (a rotation, or after Android ended it in
    // the background): results come back to those. New in an activity started afresh, which no earlier result reaches.
    val host = rememberSaveable { UUID.randomUUID().toString() }
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
            // Finished for good (SatMap removed from Recents while the chooser was open, with the process kept by a
            // service): the results of what it opened can't be delivered, and the export's hold must end.
            if (activity != null && activity.isFinishing && !activity.isChangingConfigurations) SharePresenter.hostGone(host)
        }
    }
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        when (r.launchedBy) {
            host -> return@LaunchedEffect // shown; its result comes back here
            null -> {
                r.launchedBy = host
                try {
                    chooser.launch(BackupSharing.chooser(context, r.uris))
                } catch (e: ActivityNotFoundException) {
                    SharePresenter.chooserClosed()
                }
            }
            else -> SharePresenter.chooserClosed() // shown by an activity that is gone: its result can't come
        }
    }
    LaunchedEffect(folderWanted) {
        if (!folderWanted) return@LaunchedEffect
        when (SharePresenter.folderLaunchedBy) {
            host -> return@LaunchedEffect
            null -> {
                SharePresenter.folderLaunchedBy = host
                try {
                    folder.launch(null)
                } catch (e: ActivityNotFoundException) {
                    SharePresenter.folderChosen(null)
                }
            }
            else -> SharePresenter.folderChosen(null)
        }
    }
}
