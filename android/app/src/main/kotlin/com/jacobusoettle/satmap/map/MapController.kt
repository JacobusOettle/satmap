package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.app.MeasureMode
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.round

/** Owns the MapLibre `MapView`: the satellite imagery with contour lines and elevation colours, the recorded track, the
 *  measuring line, imported blocks, your position with the compass and the lasso used to choose offline areas. One per
 *  `MainActivity`; it forwards the activity's lifecycle to the `MapView` (`onCreate` here, the rest as a lifecycle
 *  observer, `onSaveInstanceState` and `onLowMemory` from the activity and the application). Main thread only;
 *  collectors use `Dispatchers.Main` (DESIGN.md §2.4). It registers the map's only camera listeners and calls
 *  `DrawnImagery`'s hooks itself (D18). */
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
            .compassEnabled(false) // shown in the controls column instead, where panels don't cover it
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

    /** The imagery SatMap draws, with the contour lines and elevation colours. */
    private var imagery: DrawnImagery? = null
    private var userDot: UserDotLayer? = null
    private var track: TrackLayer? = null
    private var drawnTrackCount = 0

    /** The terrain mode the imagery shows. */
    private var builtTerrain: TerrainMode? = null

    /** Your position as the dot shows it (iOS: the user annotation's coordinate). */
    private var shownFix: GpsFix? = null

    /** Where the phone points (the compass), in degrees from north. */
    private var heading: Double? = null

    /** Whether the phone's own screen shows the map (`MainActivity` started), as iOS's window scene. With Android Auto
     *  showing SatMap the app stays in the foreground while the phone is locked. */
    var isShown = false
        private set

    /** No saved camera yet: zoom in on the first GPS fix. */
    private val firstLaunch = Defaults.string(CAMERA_KEY) == null
    private var hasCentredOnFirstFix = false

    /** Until then (uptime ms) a new position doesn't recentre the map, which would cut short a zoom onto you. */
    private var keepCameraUntil = 0L

    /** The map is laid out and its camera restored: from then on it is moved, saved and followed. Camera moves asked
     *  for before then wait for it (MapKit applies a region set before layout once the map is laid out). */
    private var started = false
    private val whenStarted = ArrayList<(MapLibreMap) -> Unit>()
    private var destroyed = false

    /** A turn from landscape to the other landscape (90° to 270°) is no configuration change: the display says so. */
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == container.display?.displayId) rotationChanged()
        }
    }

    init {
        // Laid out again when the screen turns: the compass follows its top, and the camera hooks see the new shape.
        container.onLaidOut = { changed -> containerLaidOut(changed) }
        mapView.onCreate(savedState)
        mapView.getMapAsync(::mapReady)
        activity.lifecycle.addObserver(this)
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        model.map = this
        model.recorder.headingWanted = isShown
    }

    private fun mapReady(m: MapLibreMap) {
        if (destroyed) return
        map = m
        m.setPrefetchZoomDelta(3)
        m.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isCompassEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = true
        }
        // Later layers draw on top: imagery with contours (DrawnImagery), then the overlays (MapStyle).
        builtTerrain = model.terrainMode.value
        val drawn = DrawnImagery(mapView, m, { isShown }, { model.terrainMode.value })
        drawn.onKey = { key -> model.legend.update(key) }
        imagery = drawn
        m.setStyle(MapStyle.builder(drawn)) { style -> styleLoaded(m, style) }
    }

    private fun styleLoaded(m: MapLibreMap, style: Style) {
        if (map !== m) return
        MapStyle.installOverlays(style, MapImages.base(activity))
        userDot = UserDotLayer(m, style, activity)
        track = TrackLayer(m, style, activity).also { it.reset(model.track) }
        drawnTrackCount = model.track.size
        // M3: BlockLayers, MeasureMarkerLayer, MeasureLineLayer, SelectedPointLayer; M4: DraftAreaLayer; M5:
        // AviationLayer (isOn = model.terrainMode.value.showsAviation).
        mapView.doOnLayout { start(m) }
    }

    /** The map has a size: the camera, the listeners, the bindings and your position, in the Swift init's order. */
    private fun start(m: MapLibreMap) {
        if (map !== m || started) return
        m.addOnCameraMoveStartedListener { reason -> regionWillChange(reason) }
        m.addOnCameraMoveListener { visibleRegionChanged() }
        m.addOnCameraIdleListener { regionDidChange() }
        restoreCamera(m)
        started = true
        val waiting = whenStarted.toList()
        whenStarted.clear()
        for (move in waiting) move(m)
        visibleRegionChanged()
        bind()
        ZoomScript.runIfAsked(mapView, m, model)
        model.recorder.location.value?.let { locationUpdated(it) }
    }

    private fun bind() {
        scope.launch { model.trackRevision.collect { trackChanged() } }
        scope.launch {
            // Changes after the style was built with the mode then (the Swift dropFirst).
            model.terrainMode.collect { mode ->
                if (mode == builtTerrain) return@collect
                builtTerrain = mode
                imagery?.terrainChanged(mode)
                // M5: aviation.isOn = mode.showsAviation.
            }
        }
        scope.launch { model.recorder.power.collect { powerChanged() } }
        // M3: measure → measureChanged; selectedPoint with measure == off → selectedPointChanged; kml.files with
        // kml.useFileColours → blocks.update. M4: the lasso's visibility and reset, draftArea → draftChanged.
        // M5: selectedPoint → aviationDistances.
    }

    /** Tiles may be had again (`AppModel.requestReload`): a new layer asks again for those shown with a stand-in. */
    override fun reloadImagery() {
        imagery?.reload()
    }

    /** The compass button: north up again. */
    fun resetNorth() {
        map?.resetNorth()
    }

    /** The coordinate under the crosshair (the middle of the view). Before the map is laid out: the middle of the
     *  camera it will restore. */
    override val centerCoordinate: GeoPoint
        get() {
            val m = map
            if (m == null || !started) return savedRegion().center
            val c = m.projection.fromScreenLocation(PointF(mapView.width / 2f, mapView.height / 2f)).wrap()
            return GeoPoint(c.latitude, c.longitude)
        }

    /** How far apart two places are on the screen, in dp (iOS points). Without a map: infinitely far. */
    override fun pointsApart(a: GeoPoint, b: GeoPoint): Double {
        val m = map ?: return Double.POSITIVE_INFINITY
        val pa = m.projection.toScreenLocation(LatLng(a.lat, a.lon))
        val pb = m.projection.toScreenLocation(LatLng(b.lat, b.lon))
        return hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble()) / density
    }

    // Camera

    override fun center(on: GeoPoint, animated: Boolean) {
        afterStart { m ->
            val update = CameraUpdateFactory.newLatLng(LatLng(on.lat, on.lon))
            if (animated) m.easeCamera(update) else m.moveCamera(update)
        }
    }

    /** Centres on a point (the map is already near it) with the screen this many metres across, however the map is
     *  turned: the zoom changed by how far the screen reaches now, keeping the heading. */
    override fun zoom(onto: GeoPoint, metersAcross: Double) {
        val m = map ?: return
        if (!started || mapView.width <= 0 || mapView.height <= 0) return
        keepCameraUntil = SystemClock.uptimeMillis() + 1000
        val perPoint = metersPerPoint ?: return
        val across = perPoint * mapView.width / density
        val camera = m.cameraPosition
        val target = CameraPosition.Builder(camera)
            .target(LatLng(onto.lat, onto.lon))
            .zoom(zoomOnto(camera.zoom, across, metersAcross))
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(target))
    }

    override fun zoom(to: GeoBounds) {
        val r = Region.fitting(to)
        model.setFollowing(false)
        afterStart { m -> camera(m, r)?.let { m.easeCamera(CameraUpdateFactory.newCameraPosition(it)) } }
    }

    /** Runs a camera move now, or once the map is laid out and its camera restored. */
    private fun afterStart(move: (MapLibreMap) -> Unit) {
        val m = map
        if (m != null && started) move(m) else whenStarted.add(move)
    }

    /** The saved camera, or South Africa until the first GPS fix arrives (first launch). */
    private fun savedRegion(): Region = Region.parse(Defaults.string(CAMERA_KEY)) ?: Region.southAfrica

    /** Without animation, the saved region fitted to the view. */
    private fun restoreCamera(m: MapLibreMap) {
        val c = camera(m, savedRegion()) ?: return
        m.moveCamera(CameraUpdateFactory.newCameraPosition(c))
    }

    /** The camera iOS's `setRegion` gives: the zoom that fits the region in the view, keeping the heading, centred on
     *  the region's centre. (MapLibre would centre a fit on the middle of its edges as drawn, which in Mercator lies
     *  nearer the pole: 26 km south for South Africa on first launch, and a saved camera would creep poleward at each
     *  launch.) Null before the map has a size. */
    private fun camera(m: MapLibreMap, region: Region): CameraPosition? {
        val fitted = m.getCameraForLatLngBounds(region.latLngBounds(), intArrayOf(0, 0, 0, 0)) ?: return null
        return centred(fitted, region.center)
    }

    private fun saveCamera() {
        val m = map ?: return
        if (!started) return // nothing restored yet: keep what was saved
        val r = visibleRegion(m) ?: return
        Defaults.putString(CAMERA_KEY, r.saved())
    }

    /** The region the view shows: the centre and the span of its four corners (iOS `mapView.region`). */
    private fun visibleRegion(m: MapLibreMap): Region? {
        val v = m.projection.visibleRegion
        val corners = listOf(v.farLeft, v.farRight, v.nearLeft, v.nearRight)
        if (corners.any { it == null }) return null
        return Region.of(centerCoordinate, corners.map { GeoPoint(it!!.latitude, it.longitude) })
    }

    /** A camera move starts: one the user's finger started stops following you (iOS: the finger on the map). */
    private fun regionWillChange(reason: Int) {
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) model.setFollowing(false)
        imagery?.regionWillChange()
    }

    private fun regionDidChange() {
        saveCamera()
        updateAim()
        updateScale()
        imagery?.regionDidChange()
        // M5: aviation.regionChanged().
    }

    /** Every camera frame (iOS mapViewDidChangeVisibleRegion). Your compass arrow and the runway rings are turned
     *  against the map's north, so MapLibre turns them with the map: nothing to redo here as the map turns. */
    private fun visibleRegionChanged() {
        val m = map ?: return
        updateAim()
        updateScale()
        imagery?.visibleRegionChanged()
        _bearing.value = m.cameraPosition.bearing
    }

    private fun updateScale() {
        metersPerPoint?.let { model.scale.update(it) }
    }

    /** Ground metres per dp at the crosshair, measured across 100 dp around it: right however the map is turned, and
     *  at any zoom (edge to edge, a zoomed-out map's sides can be half the globe apart or more). */
    private val metersPerPoint: Double?
        get() {
            val m = map ?: return null
            if (mapView.width / density <= 100) return null
            val cx = mapView.width / 2f
            val cy = mapView.height / 2f
            val l = m.projection.fromScreenLocation(PointF(cx - 50 * density, cy))
            val r = m.projection.fromScreenLocation(PointF(cx + 50 * density, cy))
            val d = Geo.distance(GeoPoint(l.latitude, l.longitude), GeoPoint(r.latitude, r.longitude))
            return if (d > 0) d / 100 else null
        }

    /** While aiming: the spot under the crosshair (for the live distance and height). */
    private fun updateAim() {
        if (model.measure.value != MeasureMode.Aiming) {
            // M3: no aim line.
            return
        }
        model.aim.set(centerCoordinate)
        // M3: the line from you to the crosshair (AimLineView, with entry and the dash phase).
    }

    // Location

    override fun locationUpdated(fix: GpsFix) {
        if (!isShown) return
        val m = map
        if (m == null || !started) return // start() shows the last fix
        shownFix = fix
        showUser()
        if (model.following.value) {
            if (firstLaunch && !hasCentredOnFirstFix) {
                val c = camera(m, Region.around(fix.point, 1500.0))
                if (c != null) m.moveCamera(CameraUpdateFactory.newCameraPosition(c))
            } else if (SystemClock.uptimeMillis() >= keepCameraUntil) {
                m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)))
            }
            hasCentredOnFirstFix = true
        }
        // M3: updateMeasureLine().
        updateAim()
        // M5: aviationDistances(model.selectedPoint.value).
    }

    /** Your dot is grey while the GPS sleeps. */
    private fun powerChanged() {
        showUser()
    }

    /** The compass: the arrow by your dot points where the phone points (null: no arrow). */
    override fun headingUpdated(heading: Double?) {
        this.heading = heading
        showUser()
    }

    private fun showUser() {
        val fix = shownFix ?: return
        userDot?.update(fix, model.recorder.power.value == LocationRecorder.Power.sleeping, heading)
    }

    // Track

    private fun trackChanged() {
        if (!isShown) return
        val layer = track ?: return
        val points = model.track
        if (points.size < drawnTrackCount) {
            // Cleared.
            layer.reset(points)
            drawnTrackCount = points.size
            return
        }
        if (points.size <= drawnTrackCount) return
        layer.append(points.subList(drawnTrackCount, points.size))
        drawnTrackCount = points.size
    }

    // App state

    /** From `MainActivity.onStart` (iOS sceneWillEnterForeground): the phone's screen shows the map again. */
    fun sceneWillEnterForeground() {
        isShown = true
        model.recorder.headingWanted = true
        imagery?.becameVisible()
        trackChanged()
        model.recorder.location.value?.let { locationUpdated(it) }
    }

    /** From `MainActivity.onStop` (iOS sceneDidEnterBackground). The app-wide work for the background is in
     *  `AppModel`. */
    fun sceneDidEnterBackground() {
        isShown = false
        model.recorder.headingWanted = false
        saveCamera()
    }

    /** The container was laid out (as when the screen turns): the compass follows the screen's top. When its size
     *  changed (turned, or another window beside it) MapLibre has resized the map but sends no camera callbacks, so
     *  the camera hooks run here as MapKit sends them when its bounds change: the imagery's focus and prefetch, the
     *  saved camera, the aim and the scale follow the new shape. */
    private fun containerLaidOut(changed: Boolean) {
        rotationChanged()
        if (!changed || !started) return
        imagery?.regionWillChange()
        visibleRegionChanged()
        regionDidChange()
    }

    private fun rotationChanged() {
        val display = container.display ?: return
        model.recorder.screenRotation = display.rotation
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
        destroyed = true
        scope.cancel()
        owner.lifecycle.removeObserver(this)
        displayManager?.unregisterDisplayListener(displayListener)
        if (model.map === this) model.map = null
        userDot?.stop()
        userDot = null
        track = null
        imagery = null
        map = null
        started = false
        whenStarted.clear()
        container.onLaidOut = null
        mapView.onDestroy()
    }

    override val isOnScreen: Boolean get() = isShown

    /** The camera as iOS saves and sets it (`MKCoordinateRegion`): the centre, and the latitude and longitude the view
     *  spans. */
    data class Region(val center: GeoPoint, val latDelta: Double, val lonDelta: Double) {
        /** As saved under "camera": "lat,lon,latDelta,lonDelta" (iOS saves the same four numbers as an array; Kotlin
         *  writes a Double the same in every locale, as `Locale.ROOT`). */
        fun saved(): String = "${center.lat},${center.lon},$latDelta,$lonDelta"

        /** The region's edges, its latitudes kept on the map (MapLibre refuses a latitude beyond ±90°). */
        val bounds: GeoBounds
            get() {
                val limit = WebMercator.maxLatitude
                return GeoBounds(
                    minLat = (center.lat - latDelta / 2).coerceIn(-limit, limit),
                    minLon = center.lon - lonDelta / 2,
                    maxLat = (center.lat + latDelta / 2).coerceIn(-limit, limit),
                    maxLon = center.lon + lonDelta / 2,
                )
            }

        companion object {
            /** First launch: South Africa until the first GPS fix arrives. */
            val southAfrica = Region(GeoPoint(-28.5, 24.7), 14.0, 14.0)

            /** What `saved()` wrote; null when there is nothing (first launch) or it isn't a region. */
            fun parse(saved: String?): Region? {
                val v = saved?.split(",")?.map { it.trim().toDoubleOrNull() } ?: return null
                if (v.size != 4 || v.any { it == null || !it.isFinite() }) return null
                val (lat, lon, latDelta, lonDelta) = v.map { it!! }
                if (abs(lat) > 90 || latDelta <= 0 || lonDelta <= 0) return null
                return Region(GeoPoint(lat, lon), latDelta, lonDelta)
            }

            /** `meters` across both ways around a point (iOS `MKCoordinateRegion(center:latitudinalMeters:
             *  longitudinalMeters:)`). */
            fun around(p: GeoPoint, meters: Double): Region {
                val latDelta = meters / 111_320.0
                return Region(p, latDelta, latDelta / cos(p.lat * PI / 180))
            }

            /** `zoom(to:)`: the bounds and a quarter more, at least 0.002° each way. */
            fun fitting(b: GeoBounds): Region =
                Region(b.center, max(0.002, (b.maxLat - b.minLat) * 1.25), max(0.002, (b.maxLon - b.minLon) * 1.25))

            /** The region of a view whose middle is `center`, from its corners; their longitudes are taken on the
             *  centre's side of the date line, so a view across it spans it rather than the rest of the world. */
            fun of(center: GeoPoint, corners: List<GeoPoint>): Region {
                val lats = corners.map { it.lat }
                val lons = corners.map { it.lon + 360 * round((center.lon - it.lon) / 360) }
                return Region(center, lats.max() - lats.min(), lons.max() - lons.min())
            }
        }
    }

    companion object {
        /** The saved camera's key, as iOS's. */
        const val CAMERA_KEY = "camera"

        /** The zoom that makes the screen `metersAcross` wide, from `zoom` where it is `across` metres wide (each zoom
         *  level halves the ground the screen shows). */
        fun zoomOnto(zoom: Double, across: Double, metersAcross: Double): Double = zoom + log2(across / metersAcross)

        /** The camera MapLibre fitted to a region (its zoom, heading and tilt), centred on the region's centre instead,
         *  as MapKit keeps a region's centre in the middle of the map. */
        fun centred(fitted: CameraPosition, center: GeoPoint): CameraPosition =
            CameraPosition.Builder(fitted).target(LatLng(center.lat, center.lon)).build()
    }
}

/** The region's edges as MapLibre bounds. */
private fun MapController.Region.latLngBounds(): LatLngBounds {
    val b = bounds
    return LatLngBounds.from(b.maxLat, b.maxLon, b.minLat, b.minLon)
}

/** The map and the views over it, which says when it is laid out (as when the screen turns). Its
 *  `dispatchTouchEvent` will see every touch before the `MapView` (taps, press and hold to move a point). */
class MapContainerView(context: Context) : FrameLayout(context) {
    /** After each layout, with the views already laid out: `changed` when the container's frame changed (its size, as
     *  when the screen turns). */
    var onLaidOut: ((changed: Boolean) -> Unit)? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLaidOut?.invoke(changed)
    }

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
