package com.jacobusoettle.satmap.map

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.location.GpsFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/** The phone's map: the MapLibre `MapView` with SatMap's style, its camera, gestures and overlays, bound to the
 *  model. One per `MainActivity`; it forwards the activity's lifecycle to the `MapView` (`onCreate` here, the rest as a
 *  lifecycle observer, `onSaveInstanceState` and `onLowMemory` from the activity and the application). Main thread
 *  only; collectors use `Dispatchers.Main` (DESIGN.md §2.4). */
class MapController(
    private val activity: ComponentActivity,
    private val model: AppModel,
    savedState: Bundle?,
) : MapHost, DefaultLifecycleObserver {
    private val density = activity.resources.displayMetrics.density

    // setPrefetchesTiles is deprecated in favour of the prefetch zoom delta, set too; DESIGN.md §3.1 sets both.
    @Suppress("DEPRECATION")
    private val mapView = MapView(
        activity,
        MapLibreMapOptions.createFromAttributes(activity)
            .pixelRatio(density)
            .textureMode(false)
            .setPrefetchesTiles(true)
            .setPrefetchZoomDelta(3)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .tiltGesturesEnabled(false)
            .rotateGesturesEnabled(true)
            .foregroundLoadColor(MapStyle.BACKGROUND_COLOUR),
    )

    /** The map and its views (aim line, lasso), as the Compose screen shows them. */
    val container = MapContainerView(activity).apply {
        addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private val _bearing = MutableStateFlow(0.0)

    /** The camera's heading, degrees, for `CompassButton`. */
    val bearing: StateFlow<Double> = _bearing.asStateFlow()

    /** Collecting the model's flows; cancelled in `onDestroy`. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var map: MapLibreMap? = null
    private var imagery: DrawnImagery? = null

    /** Whether the phone's screen shows the map (`MainActivity` started), as iOS's window scene. */
    var isShown = false
        private set

    init {
        container.controller = this
        mapView.onCreate(savedState)
        mapView.getMapAsync(::mapReady)
        activity.lifecycle.addObserver(this)
        model.map = this
    }

    private fun mapReady(m: MapLibreMap) {
        map = m
        m.setPrefetchZoomDelta(3)
        m.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isCompassEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = true
        }
        val drawn = DrawnImagery(mapView, m, { isShown }, { model.terrainMode.value })
        imagery = drawn
        m.setStyle(MapStyle.builder(drawn)) { style ->
            MapStyle.installOverlays(style, MapImages.base(activity))
            // M1: camera restore, the camera listeners (DrawnImagery hooks, scale, bearing), the model bindings.
        }
    }

    fun resetNorth() {
        map?.resetNorth()
    }

    /** From `MainActivity.onStart` (iOS sceneWillEnterForeground). */
    fun sceneWillEnterForeground() {
        isShown = true
        // M1: headingWanted, imagery.becameVisible(), catching up on the track, locationUpdated(last fix).
    }

    /** From `MainActivity.onStop` (iOS sceneDidEnterBackground). */
    fun sceneDidEnterBackground() {
        isShown = false
        // M1: headingWanted = false, save the camera.
    }

    fun onSaveInstanceState(out: Bundle) {
        mapView.onSaveInstanceState(out)
    }

    fun onLowMemory() {
        mapView.onLowMemory()
    }

    override fun onStart(owner: LifecycleOwner) {
        mapView.onStart()
    }

    override fun onResume(owner: LifecycleOwner) {
        mapView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        mapView.onPause()
    }

    override fun onStop(owner: LifecycleOwner) {
        mapView.onStop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
        owner.lifecycle.removeObserver(this)
        if (model.map === this) model.map = null
        map = null
        imagery = null
        mapView.onDestroy()
    }

    // MapHost

    override val centerCoordinate: GeoPoint
        get() = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(0.0, 0.0) // M1: fromScreenLocation(view centre).

    override val isOnScreen: Boolean get() = isShown

    override fun pointsApart(a: GeoPoint, b: GeoPoint): Double = 0.0 // M3: toScreenLocation, in dp.

    override fun center(on: GeoPoint, animated: Boolean) {
        // M1: easeCamera / moveCamera(newLatLng).
    }

    override fun zoom(onto: GeoPoint, metersAcross: Double) {
        // M1: as DESIGN.md §3.12.
    }

    override fun zoom(to: GeoBounds) {
        // M1: span max(0.002°, 1.25 × the bounds'), easeCamera(newLatLngBounds), following = false.
    }

    override fun locationUpdated(fix: GpsFix) {
        // M1: your dot, following, the first-fix region.
    }

    override fun headingUpdated(heading: Double?) {
        // M5: the compass arrow.
    }

    override fun reloadImagery() {
        // M2: imagery.reload().
    }
}

/** The map and the views over it. Its `dispatchTouchEvent` will see every touch before the `MapView` (taps, press
 *  and hold to move a point). */
class MapContainerView(context: Context) : FrameLayout(context) {
    var controller: MapController? = null
    // M3: touch routing and the press detector (DESIGN.md §3.10).
}

/** While aiming: the dashed line from your dot to the crosshair, which follows a pan frame by frame. */
class AimLineView(context: Context) : View(context) {
    // M3: draws the line (MapController.entry gives its start and dash phase).
}

/** The map in the Compose screen (iOS `MapViewRepresentable`): the controller's container, detached from an old
 *  parent first. */
@Composable
fun MapViewRepresentable(controller: MapController) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { controller.container.also { (it.parent as? ViewGroup)?.removeView(it) } },
    )
}
