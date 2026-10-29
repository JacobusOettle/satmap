package com.jacobusoettle.satmap.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jacobusoettle.satmap.R
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.TerrainMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** SatMap on the car's screen (Android Auto, CarPlay's counterpart): the map (`CarMapController`) under the car's
 *  navigation template, with buttons to zoom in and out, follow you again, move the map and change what it shows of the
 *  terrain (as the phone's terrain button). A navigation-category car app: the only kind that draws its own map on the
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

/** The car's map screen (CarPlay's map template): in the bar along the top, the terrain (contour lines, elevation
 *  colours, both, drone mode, neither: each tap the next, the title says what is shown); on the map, zoom in, zoom out,
 *  follow you, and move the map (the host's pan mode, with its own way back). The map is the model's car map while the
 *  screen lives, and paints while it is started. */
class CarMapScreen(carContext: CarContext) : Screen(carContext) {
    private val model = AppModel.shared

    /** The map under the template; it draws once the host gives a surface. */
    @VisibleForTesting
    internal val map = CarMapController(carContext, model)

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)
        model.carMap = map
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                map.isShown = true
            }

            override fun onStop(owner: LifecycleOwner) {
                map.isShown = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                map.isShown = false
                if (model.carMap === map) model.carMap = null
                map.release()
            }
        })
        // The terrain button's title follows the mode, also when it is changed on the phone.
        lifecycleScope.launch(Dispatchers.Main) {
            model.terrainMode.drop(1).collect { invalidate() }
        }
    }

    /** Zoom in, zoom out and follow you on the map, and the host's pan mode to move it; the terrain in the bar along
     *  the top. */
    override fun onGetTemplate(): Template {
        val terrain = Action.Builder()
            .setTitle(shortTitle(model.terrainMode.value))
            .setOnClickListener { model.setTerrainMode(model.terrainMode.value.next) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(terrain).build())
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(button(R.drawable.ic_car_add) { map.zoom(1.0) }) // CarPlay: zoom(by: 0.5)
                    .addAction(button(R.drawable.ic_car_remove) { map.zoom(-1.0) }) // CarPlay: zoom(by: 2)
                    .addAction(button(R.drawable.ic_car_near_me) { map.recentre() })
                    .addAction(Action.PAN) // CarPlay's "Move"; the host shows its own way out of pan mode
                    .build(),
            )
            .setPanModeListener { panning -> if (panning) map.dragBegan() }
            .build()
    }

    private fun button(@DrawableRes icon: Int, tapped: () -> Unit): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .setOnClickListener { tapped() }
        .build()

    companion object {
        /** What the map shows of the terrain, short enough for the car's bar. */
        fun shortTitle(mode: TerrainMode): String = when (mode) {
            TerrainMode.contours -> "Contours"
            TerrainMode.contoursAndColours -> "Contours + colours"
            TerrainMode.colours -> "Colours"
            TerrainMode.drone -> "Drone"
            TerrainMode.off -> "No contours"
        }
    }
}
