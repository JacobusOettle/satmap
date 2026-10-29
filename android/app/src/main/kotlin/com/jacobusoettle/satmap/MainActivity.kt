package com.jacobusoettle.satmap

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.PermissionFlow
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.ui.MapScreen
import com.jacobusoettle.satmap.ui.SatMapTheme

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
            Defaults.setLaunchArguments(intent.extras)
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

    /** KML/KMZ opened or shared from another app (zoom to it, or the Blocks sheet with the error), and "Save to a
     *  folder" from the backup chooser. */
    private fun handleIncoming(intent: Intent) {
        if (isReplay(intent)) return
        // M4: BackupSharing.ACTION_SAVE_TO_FOLDER → SharePresenter.requestFolder().
        // Once imported, a recreated activity (its intent kept) doesn't import the files again.
        if (openFiles(AppModel.shared, intent)) setIntent(Intent(intent).setAction(null))
    }

    companion object {
        /** An intent Android hands over again when SatMap is reopened from Recents: the one that started its task
         *  (before Android 12 Back finishes the activity but leaves the task there, with that intent). Its files were
         *  imported, or its folder chosen, when it first came, and iOS hands each opened file to the app only once. */
        fun isReplay(intent: Intent): Boolean = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

        /** iOS's `onOpenURL`: each .kml/.kmz shared into the app ("Open with SatMap" / "Share to SatMap") is imported
         *  at once (the permission to read it lives with the activity that received it); the map then zooms to it, or
         *  the Blocks sheet shows why the import failed. False when the intent brings no file. */
        fun openFiles(model: AppModel, intent: Intent): Boolean {
            val uris = incomingFiles(intent)
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

        /** The files an "Open with" (`ACTION_VIEW`) or a share (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) brings: the
         *  manifest's filters let in KML, KMZ and the types other apps give them (DESIGN.md D13); parsing turns away
         *  anything else with "Couldn't import". */
        fun incomingFiles(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            else -> emptyList()
        }
    }
}
