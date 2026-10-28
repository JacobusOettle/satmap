package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.app.AppModel
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** The zoom test (only with the `zoomScript` launch extra): from (−33.9183, 18.9212), after 20 s one zoom or pan step
 *  every 5 s, logged under "zoomscript", with the step shown in binary by four squares. */
object ZoomScript {
    fun runIfAsked(mapView: MapView, map: MapLibreMap, model: AppModel) {
        // M5: interaction spec §1.13 (ZoomScript zoom z is MapLibre zoom z − 1).
    }
}

/** Logs "main thread held up N ms" when a 0.1 s tick comes more than 500 ms late. */
class Watchdog {
    fun start() {
        // M5: a Handler ticking every 100 ms.
    }

    fun stop() {
        // M5: stops the ticks.
    }
}
