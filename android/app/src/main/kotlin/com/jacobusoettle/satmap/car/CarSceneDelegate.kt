package com.jacobusoettle.satmap.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.TerrainMode

/** Android Auto (CarPlay's counterpart): a navigation-category car app, the only kind that draws its own map on the
 *  car's surface. */
class SatMapCarAppService : CarAppService() {
    /** Any host in debuggable builds; otherwise the hosts in the library's allow list (Android Auto's signatures, so
     *  the sideloaded release APK works in real cars). */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = CarSession()
}

/** One connection to the car. While its screen shows, the app counts as in the foreground (recording starts, nothing
 *  is trimmed), as with CarPlay. */
class CarSession : Session() {
    init {
        AppLifecycle.observeCar(this)
    }

    override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext)
}

/** The car's map screen (CarPlay's map template): the terrain button, and on the map zoom in, zoom out, follow and
 *  pan. */
class CarMapScreen(carContext: CarContext) : Screen(carContext) {
    private val model = AppModel.shared

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle(shortTitle(model.terrainMode.value))
                        .setOnClickListener {
                            // M5: model.setTerrainMode(next), the map strip, pan mode, the CarMapController surface.
                        }
                        .build(),
                )
                .build(),
        )
        .build()

    companion object {
        /** The terrain button's title. */
        fun shortTitle(mode: TerrainMode): String = when (mode) {
            TerrainMode.contours -> "Contours"
            TerrainMode.contoursAndColours -> "Contours + colours"
            TerrainMode.colours -> "Colours"
            TerrainMode.drone -> "Drone"
            TerrainMode.off -> "No contours"
        }
    }
}
