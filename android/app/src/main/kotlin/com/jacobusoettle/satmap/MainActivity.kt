package com.jacobusoettle.satmap

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.app.PermissionFlow
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.offline.BackupSharing
import com.jacobusoettle.satmap.ui.MapScreen
import com.jacobusoettle.satmap.ui.SatMapTheme
import com.jacobusoettle.satmap.ui.SharePresenter

/** iOS's `WindowGroup` (`SatMapApp.swift`): the map screen, edge to edge and always dark, with white status bar icons
 *  over the imagery. It creates the map and the permission flow, handles the files and actions other apps send, and
 *  reads the launch extras (iOS's launch arguments). `singleTask`, so a share lands in the open map. */
class MainActivity : ComponentActivity() {
    private lateinit var mapController: MapController
    private lateinit var permissions: PermissionFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val model = AppModel.shared
        // Read once, as iOS reads its argument domain: a recreated activity keeps what was chosen since.
        if (savedInstanceState == null) {
            Defaults.setLaunchArguments(launchArguments(intent))
            model.applyLaunchArguments()
        }
        mapController = MapController(this, model, savedInstanceState)
        permissions = PermissionFlow(this, model.recorder)
        setContent {
            SatMapTheme { MapScreen(model, model.offline, model.recorder, mapController) }
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        mapController.sceneWillEnterForeground()
    }

    /** iOS asks for location (then motion) as the app becomes active. */
    override fun onResume() {
        super.onResume()
        permissions.next()
    }

    override fun onStop() {
        mapController.sceneDidEnterBackground()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapController.onSaveInstanceState(outState)
    }

    /** A group invite link (the join confirmation), KML/KMZ or GPX opened or shared from another app (zoom to it, or
     *  the Blocks sheet with the error), and "Save to a folder" from the backup chooser. Once handled, a recreated
     *  activity (its intent kept) doesn't handle it again. */
    private fun handleIncoming(intent: Intent) {
        if (handleIncoming(AppModel.shared, intent)) setIntent(Intent(intent).setAction(null))
    }

    companion object {
        /** The launch extras (iOS's launch arguments), or none when a browser sent the intent: a web page's
         *  `intent://` link (an invite's landing page is one) can carry any `S.` extra, and a page must never choose
         *  groups' server or demo mode, nor any other setting. Browsers add `CATEGORY_BROWSABLE` to every intent they
         *  send; adb, the tests and the launcher don't. (iOS can't be reached this way: opening a URL carries no
         *  launch arguments.) */
        fun launchArguments(intent: Intent): Bundle? =
            if (intent.hasCategory(Intent.CATEGORY_BROWSABLE)) null else intent.extras

        /** An intent Android hands over again when SatMap is reopened from Recents: the one that started its task
         *  (before Android 12 Back finishes the activity but leaves the task there, with that intent). Its files were
         *  imported, or its folder chosen, when it first came, and iOS hands each opened file to the app only once. */
        fun isReplay(intent: Intent): Boolean = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

        /** What another app sent SatMap, as it first comes: the files of an "Open with" or a share (`openFiles`), and
         *  "Save to a folder" chosen in the backup chooser, which asks for the folder (`ShareHost` shows the picker;
         *  `SaveToFolderActivity` has asked already, and asking again changes nothing). False when there was nothing
         *  to do. */
        fun handleIncoming(model: AppModel, intent: Intent): Boolean {
            if (isReplay(intent)) return false
            if (intent.action == BackupSharing.ACTION_SAVE_TO_FOLDER) {
                SharePresenter.requestFolder()
                return true
            }
            // A group invite first (groups §5.6): it opens the join confirmation, never a file import.
            val invite = invite(intent)
            if (invite != InviteLink.Parsed.NotInvite) {
                model.groups.openInvite(invite)
                return true
            }
            return openFiles(model, intent)
        }

        /** The invite an intent brings (groups §5.6): an App Link `https://{domain}/j/…#key` or `satmap://join/…#key`
         *  in its data, or the landing page's `intent://` form, which arrives as `satmap://join/<token>` without its
         *  fragment and the key in the extra `groupKey` (§5.4). `NotInvite` for anything else. */
        fun invite(intent: Intent): InviteLink.Parsed {
            if (intent.action != Intent.ACTION_VIEW) return InviteLink.Parsed.NotInvite
            val data = intent.dataString ?: return InviteLink.Parsed.NotInvite
            val parsed = InviteLink.parse(data, GroupConfig.domain)
            val key = intent.getStringExtra("groupKey")
            val uri = intent.data
            if (parsed !is InviteLink.Parsed.Invite && key != null && uri?.scheme == "satmap" && uri.host == "join" &&
                uri.fragment.isNullOrEmpty()
            ) {
                val token = uri.path?.trim('/') ?: return parsed
                val fromExtra = InviteLink.parse("satmap://join/$token#$key", GroupConfig.domain)
                if (fromExtra is InviteLink.Parsed.Invite) return fromExtra
            }
            return parsed
        }

        /** iOS's `onOpenURL`: each .kml/.kmz/.gpx shared into the app ("Open with SatMap" / "Share to SatMap") is
         *  imported at once (the permission to read it lives with the activity that received it); the map then zooms
         *  to it, or the Blocks sheet shows why the import failed. False when the intent brings no file. */
        fun openFiles(model: AppModel, intent: Intent): Boolean {
            val uris = KMLLibrary.incomingUris(intent)
            for (uri in uris) {
                model.kml.importFile(uri) { file ->
                    val b = file?.document?.bounds
                    if (b != null) {
                        model.map?.zoom(to = b)
                    } else {
                        model.setSheet(ActiveSheet.blocks) // shows why the import failed
                    }
                }
            }
            return uris.isNotEmpty()
        }
    }
}
