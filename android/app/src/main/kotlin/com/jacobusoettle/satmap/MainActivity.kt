package com.jacobusoettle.satmap

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // M3: the KML intents (DESIGN.md §5.9); M4: BackupSharing.ACTION_SAVE_TO_FOLDER → SharePresenter.requestFolder().
    }
}
