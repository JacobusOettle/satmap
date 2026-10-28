package com.jacobusoettle.satmap.car

import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.CarMapHost
import com.jacobusoettle.satmap.location.GpsFix

/** The map on the car's screen: a `VirtualDisplay` on the car's surface with a `Presentation` holding a `MapView`
 *  (`textureMode`) that draws the phone's style with its own `DrawnImagery` and overlays (DESIGN.md §6, D20). It starts
 *  around you (or the phone map's centre) about 1.5 km across and follows you until moved. */
class CarMapController(private val carContext: CarContext, private val model: AppModel) : SurfaceCallback, CarMapHost {
    /** The car screen is started: the map paints only while shown. */
    var isShown: Boolean = false

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        // M5: the VirtualDisplay, Presentation and MapView with SatMap's style.
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // M5: MapView pause/stop/destroy, dismiss the presentation, release the display.
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // M5: setPadding (the car map only).
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // M5: following = false; scrollBy(-dx, -dy).
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // M5: zoomBy(log2 f, focus); a negative focus is the host's own zoom, around the centre.
    }

    /** Zoom in (+1) or out (−1) by levels. */
    fun zoom(levels: Double) {
        // M5: animateCamera(zoomBy(levels)).
    }

    /** Follow you again. */
    fun recentre() {
        // M5: following = true, ease to your position.
    }

    /** Pan mode began: stop following. */
    fun dragBegan() {
        // M5: following = false.
    }

    override fun locationUpdated(fix: GpsFix) {
        // M5: the dot, following, the aviation distances.
    }

    override fun reload() {
        // M5: imagery.reload().
    }
}
