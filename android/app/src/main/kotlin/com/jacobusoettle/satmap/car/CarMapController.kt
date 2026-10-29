package com.jacobusoettle.satmap.car

import android.app.Presentation
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.view.doOnLayout
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.CarMapHost
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.AviationLayer
import com.jacobusoettle.satmap.map.BlockLayers
import com.jacobusoettle.satmap.map.DrawnImagery
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapImages
import com.jacobusoettle.satmap.map.MapStyle
import com.jacobusoettle.satmap.map.TileLevels
import com.jacobusoettle.satmap.map.TrackLayer
import com.jacobusoettle.satmap.map.UserDotLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/** The map on the car's screen (Android Auto, CarPlay's counterpart): the satellite imagery with contour lines and
 *  elevation colours (as on the phone), the blocks, the track and your position, following you until it is moved. It
 *  lies under the car's navigation template, which has the buttons (`CarMapScreen`): the car's screen doesn't take taps
 *  on the map itself.
 *
 *  Android has no window for it: the host gives a `Surface`, and the map is a `MapView` (`textureMode`) in a
 *  `Presentation` on a `VirtualDisplay` drawing into that surface (DESIGN.md D20), with its own `DrawnImagery` and
 *  overlays at the car's density. The tiles in memory are shared with the phone's map. Main thread only (the host's
 *  calls arrive there); collectors use `Dispatchers.Main`, the Swift `DispatchQueue.main.async` hop. */
class CarMapController(private val carContext: CarContext, private val model: AppModel) : SurfaceCallback, CarMapHost {
    /** What the controller does with the camera: MapLibre's on the car's surface, or a test's fake. */
    interface Camera {
        /** The camera now, kept for the next surface. */
        val position: CameraPosition?

        /** Without animation, a camera kept from an earlier surface. */
        fun restore(position: CameraPosition)

        /** Without animation, the region fitted to the map and centred on its centre (iOS `setRegion`). */
        fun show(region: MapController.Region)

        /** Animated, the middle of the map onto a point (iOS `setCenter(_:animated: true)`). */
        fun center(on: GeoPoint)

        /** Zooms in (positive) or out by levels: around `focus` (the surface's pixels; the point under it stays where
         *  it is), or around the middle. */
        fun zoomBy(levels: Double, focus: PointF?, animated: Boolean)

        /** Moves what the map shows by pixels, as a move of the camera (its hooks run, as for MapKit's `setCenter`). */
        fun scrollBy(dx: Float, dy: Float)

        /** The parts of the surface the host covers (its buttons and cards), in pixels from each edge: the middle of
         *  the map is the middle of the rest. */
        fun padding(left: Int, top: Int, right: Int, bottom: Int)
    }

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var camera: Camera? = null

    /** Collecting the model's flows while there is a map; cancelled with the surface. */
    private var scope: CoroutineScope? = null

    /** The imagery SatMap draws, with the contour lines and elevation colours. */
    private var imagery: DrawnImagery? = null
    private var blocks: BlockLayers? = null

    /** Drone mode: aerodromes, strips, helipads and navaids, with distances from you and the phone's tapped spot. */
    private var aviation: AviationLayer? = null
    private var track: TrackLayer? = null
    private var drawnTrackCount = 0
    private var userDot: UserDotLayer? = null

    /** Your position as the dot shows it (iOS: the user annotation's coordinate). */
    private var shownFix: GpsFix? = null

    /** The terrain mode the imagery shows. */
    private var builtTerrain: TerrainMode? = null

    /** The surface's size in pixels, and the part of it the host leaves to the map. */
    private var width = 0
    private var height = 0
    private var visibleArea: Rect? = null

    /** The camera when the last surface went: the next one starts there (CarPlay's map view lives on while the car
     *  shows something else; here the host takes the surface away). */
    private var savedCamera: CameraPosition? = null
    private var released = false

    /** Whether the map keeps you in the middle (until it is moved). */
    var following = true
        private set

    /** Whether the car's screen shows the map (the car screen is started): the map paints only while shown. */
    var isShown = false
        set(value) {
            val old = field
            field = value
            if (value && !old) {
                mapView?.onResume()
                imagery?.becameVisible()
            } else if (!value && old) {
                mapView?.onPause()
            }
        }

    // The car's surface

    /** The host has a surface for the map: a virtual display on it, a presentation on that, and in it the map with
     *  SatMap's style. A new surface (the car's screen changed) replaces the map on the old one. */
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        teardown()
        if (released) return
        width = surfaceContainer.width
        height = surfaceContainer.height
        val surface = surfaceContainer.surface ?: return
        if (width <= 0 || height <= 0) return
        val dpi = surfaceContainer.dpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        val manager = carContext.getSystemService(DisplayManager::class.java) ?: return
        val vd = manager.createVirtualDisplay(
            "SatMap car", width, height, dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        display = vd
        // The presentation's context has the virtual display's metrics: the map's images are drawn at the car's
        // density.
        val p = Presentation(carContext, vd.display)
        presentation = p
        val context = p.context
        // setPrefetchesTiles is deprecated in favour of the prefetch zoom delta, set too, as on the phone.
        @Suppress("DEPRECATION")
        val view = MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                .pixelRatio(dpi / DisplayMetrics.DENSITY_DEFAULT.toFloat())
                .textureMode(true)
                .setPrefetchesTiles(true)
                .setPrefetchZoomDelta(3)
                .logoEnabled(false)
                .attributionEnabled(false)
                .compassEnabled(false)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .minZoomPreference(MIN_ZOOM)
                .maxZoomPreference(MAX_ZOOM)
                .foregroundLoadColor(MapStyle.BACKGROUND_COLOUR),
        )
        mapView = view
        p.setContentView(view)
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w("tiles", "car map: no display for the presentation (${e.message})")
            teardown()
            return
        }
        view.onCreate(null)
        view.onStart()
        if (isShown) view.onResume()
        view.getMapAsync { m -> mapReady(view, m) }
    }

    /** The host took the surface away (the car shows something else, or disconnected): the map goes, and its camera is
     *  kept for the next surface. */
    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        teardown()
    }

    /** The part of the surface not covered by the host's buttons and cards: the middle of the map is its middle. */
    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyPadding()
    }

    /** A drag on a touch screen: the map follows the finger (the host gives how far it moved since the last call, as
     *  `GestureDetector` does, so the map moves the other way, as MapLibre's own drag does). The host's pan-mode arrows
     *  and rotary controller come as scrolls too, so how far an arrow moves the map (CarPlay: a third of the screen) is
     *  the host's choice. Either way the map stops following you. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        following = false
        camera?.scrollBy(-distanceX, -distanceY)
    }

    /** A pinch or a double tap: zooms around where the fingers are, the point under them staying where it is, and the
     *  map stops following you, as for a drag (and as the phone's map does for any move of the finger). The host's own
     *  zoom (no focus) is around the middle of the map and keeps following, as CarPlay's zoom buttons do. */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        val focus = if (focusX < 0 || focusY < 0) null else PointF(focusX, focusY)
        if (focus != null) following = false
        camera?.zoomBy(log2(scaleFactor.toDouble()), focus, animated = false)
    }

    /** The map stops where the finger left it (CarPlay ignores the drag's velocity). */
    override fun onFling(velocityX: Float, velocityY: Float) {}

    /** The car's map takes no taps (the buttons are the template's). */
    override fun onClick(x: Float, y: Float) {}

    // Moving the map (the template's buttons)

    override fun locationUpdated(fix: GpsFix) {
        shownFix = fix
        showUser()
        if (following && isShown) camera?.center(on = fix.point)
        aviationDistances(model.selectedPoint.value)
    }

    /** Drone mode's labels: how far each place is from you and from the spot tapped on the phone. */
    private fun aviationDistances(pin: GeoPoint?) {
        aviation?.distancesChanged(me = model.recorder.location.value?.point, pin = pin)
    }

    /** Zooms in (+1, CarPlay's camera distance halved) or out (−1, doubled) around the middle of the map, within the
     *  zoom limits that stand in for MapKit's 150 m – 4,000 km camera distance. */
    fun zoom(levels: Double) {
        camera?.zoomBy(levels, focus = null, animated = true)
    }

    /** Back to following you. */
    fun recentre() {
        following = true
        model.recorder.location.value?.let { camera?.center(on = it.point) }
    }

    /** The host's pan mode began (CarPlay: a drag on a touch screen began): the map stays where it is moved. */
    fun dragBegan() {
        following = false
    }

    /** Tiles may be had again (the network is back, an offline area finished). */
    override fun reload() {
        imagery?.reload()
    }

    /** Memory is short: the map drops its own tile cache (the shared ones are trimmed by the application). */
    fun onLowMemory() {
        mapView?.onLowMemory()
    }

    /** The car screen is gone: no more maps. */
    fun release() {
        released = true
        teardown()
    }

    // The map

    private fun mapReady(view: MapView, m: MapLibreMap) {
        if (mapView !== view) return // that surface has gone
        map = m
        m.setPrefetchZoomDelta(3)
        m.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isCompassEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = false
        }
        m.setMinZoomPreference(MIN_ZOOM)
        m.setMaxZoomPreference(MAX_ZOOM)
        // Imagery with contours and colours, then the overlays: blocks (below the track), track, aviation, your dot.
        builtTerrain = model.terrainMode.value
        val drawn = DrawnImagery(view, m, { isShown }, { model.terrainMode.value }, TileLevels.contourScale(view.pixelRatio))
        imagery = drawn
        m.setStyle(MapStyle.builder(drawn)) { style -> styleLoaded(view, m, style) }
    }

    private fun styleLoaded(view: MapView, m: MapLibreMap, style: Style) {
        if (map !== m) return
        val context = view.context
        MapStyle.installOverlays(style, MapImages.base(context))
        userDot = UserDotLayer(m, style, context)
        track = TrackLayer(m, style, context).also { it.reset(model.track) }
        drawnTrackCount = model.track.size
        blocks = BlockLayers(m, style, context)
        aviation = AviationLayer(m, style, context).also { it.isOn = model.terrainMode.value.showsAviation }
        view.doOnLayout { if (map === m) start(m) }
    }

    /** The map is laid out: the camera hooks (the map's only listeners, DESIGN.md D18), then the camera, your position
     *  and the bindings. */
    private fun start(m: MapLibreMap) {
        m.addOnCameraMoveStartedListener { imagery?.regionWillChange() }
        m.addOnCameraMoveListener { imagery?.visibleRegionChanged() }
        m.addOnCameraIdleListener {
            imagery?.regionDidChange()
            aviation?.regionChanged()
        }
        start(MapLibreCamera(m))
        imagery?.visibleRegionChanged()
    }

    /** In the Swift `viewDidLoad`'s order: around you, or where the phone's map is, about 1.5 km across (or where the
     *  last surface left it); your position; then the bindings. */
    private fun start(c: Camera) {
        camera = c
        val saved = savedCamera
        if (saved != null) c.restore(saved) else c.show(MapController.Region.around(startCentre(), START_METERS))
        applyPadding()
        model.recorder.location.value?.let { locationUpdated(it) }
        bind()
    }

    /** You, or the middle of the phone's map, or the Swift default (Kruger). */
    private fun startCentre(): GeoPoint =
        model.recorder.location.value?.point ?: model.map?.centerCoordinate ?: DEFAULT_CENTRE

    private fun bind() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch { model.trackRevision.collect { trackChanged() } }
        s.launch {
            // Changes after the style was built with the mode then (the Swift dropFirst).
            model.terrainMode.collect { mode ->
                if (mode == builtTerrain) return@collect
                builtTerrain = mode
                imagery?.terrainChanged(mode)
                aviation?.isOn = mode.showsAviation
            }
        }
        s.launch { model.selectedPoint.collect { aviationDistances(it) } }
        s.launch {
            combine(model.kml.files, model.kml.useFileColours) { files, colours -> files to colours }
                .collect { (files, colours) -> blocks?.update(files, colours) }
        }
        // iOS greys the dot as MapKit makes its view; here it follows the recorder as the phone's dot does.
        s.launch { model.recorder.power.collect { showUser() } }
    }

    private fun applyPadding() {
        val c = camera ?: return
        val r = visibleArea ?: return
        if (width <= 0 || height <= 0) return
        c.padding(max(0, r.left), max(0, r.top), max(0, width - r.right), max(0, height - r.bottom))
    }

    /** Your dot, grey while the GPS sleeps. The car's map has no compass arrow (CarPlay's has none either). */
    private fun showUser() {
        val fix = shownFix ?: return
        userDot?.update(fix, model.recorder.power.value == LocationRecorder.Power.sleeping, heading = null)
    }

    // Track

    private fun trackChanged() {
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

    /** Ends the map on the current surface, keeping its camera: no more collecting, timers or frames; then the
     *  `MapView` (paused, stopped, destroyed), the presentation and the virtual display. */
    private fun teardown() {
        camera?.position?.let { savedCamera = it }
        camera = null
        scope?.cancel()
        scope = null
        imagery?.destroy()
        imagery = null
        userDot?.stop()
        userDot = null
        track = null
        blocks?.stop()
        blocks = null
        aviation = null
        map = null
        shownFix = null
        mapView?.let { view ->
            if (isShown) view.onPause()
            view.onStop()
            view.onDestroy()
        }
        mapView = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    /** Starts the controller's logic on a test's camera, as if a `width` × `height` surface's map had been laid out
     *  (MapLibre's native map can't run in unit tests). */
    @VisibleForTesting
    internal fun startForTests(c: Camera, width: Int, height: Int) {
        this.width = width
        this.height = height
        start(c)
    }

    /** The test's surface went (`onSurfaceDestroyed` without a map). */
    @VisibleForTesting
    internal fun surfaceDestroyedForTests() = teardown()

    companion object {
        /** Where the map starts with neither your position nor the phone's map (as iOS). */
        val DEFAULT_CENTRE = GeoPoint(-24.99, 31.59)

        /** How far across the map starts (iOS: a 1500 m × 1500 m region). */
        const val START_METERS = 1500.0

        /** The zoom limits, standing in for MapKit's 150 m – 4,000 km camera distance. */
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 20.0

        /** The point on the surface that becomes the middle of the map when it zooms from `fromZoom` to `toZoom` around
         *  `focus`, the point under the focus staying where it is (`centre`: where the middle of the map is now). */
        internal fun zoomedCentre(centre: PointF, focus: PointF, fromZoom: Double, toZoom: Double): PointF {
            val s = 2.0.pow(fromZoom - toZoom).toFloat()
            return PointF(focus.x + (centre.x - focus.x) * s, focus.y + (centre.y - focus.y) * s)
        }

        /** The point on the surface that becomes the middle of the map when what it shows moves by `dx`, `dy` pixels
         *  (`centre`: where the middle of the map is now). */
        internal fun scrolledCentre(centre: PointF, dx: Float, dy: Float): PointF = PointF(centre.x - dx, centre.y - dy)
    }
}

/** The car map's camera on MapLibre. */
private class MapLibreCamera(private val map: MapLibreMap) : CarMapController.Camera {
    override val position: CameraPosition get() = map.cameraPosition

    override fun restore(position: CameraPosition) {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    /** The zoom that fits the region in the map, centred on the region's centre as MapKit keeps it (MapLibre would
     *  centre a fit on the middle of its edges as drawn, nearer the pole in Mercator). */
    override fun show(region: MapController.Region) {
        val b = region.bounds
        val bounds = LatLngBounds.from(b.maxLat, b.maxLon, b.minLat, b.minLon)
        val fitted = map.getCameraForLatLngBounds(bounds, intArrayOf(0, 0, 0, 0)) ?: return
        map.moveCamera(CameraUpdateFactory.newCameraPosition(MapController.centred(fitted, region.center)))
    }

    override fun center(on: GeoPoint) {
        map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(on.lat, on.lon)))
    }

    /** Around a focus, the zoom and a new middle together, so the point under the focus stays where it is
     *  (MapLibre's `zoomBy(amount, focus)` makes the point under the focus the new middle of the map). */
    override fun zoomBy(levels: Double, focus: PointF?, animated: Boolean) {
        val update = if (focus == null) {
            CameraUpdateFactory.zoomBy(levels)
        } else {
            val c = map.cameraPosition
            val target = c.target ?: return
            val zoom = (c.zoom + levels).coerceIn(map.minZoomLevel, map.maxZoomLevel)
            val middle = CarMapController.zoomedCentre(map.projection.toScreenLocation(target), focus, c.zoom, zoom)
            val moved = map.projection.fromScreenLocation(middle).wrap()
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(c).target(moved).zoom(zoom).build())
        }
        if (animated) map.animateCamera(update) else map.moveCamera(update)
    }

    /** As a new middle for the camera: `MapLibreMap.scrollBy` moves the native map without telling the camera
     *  listeners, so the imagery, the colour range and drone mode's places would stay where the map was. */
    override fun scrollBy(dx: Float, dy: Float) {
        val target = map.cameraPosition.target ?: return
        val middle = CarMapController.scrolledCentre(map.projection.toScreenLocation(target), dx, dy)
        map.moveCamera(CameraUpdateFactory.newLatLng(map.projection.fromScreenLocation(middle).wrap()))
    }

    /** As camera padding (`MapLibreMap.setPadding` is deprecated): what was in the middle of the map stays in the
     *  middle of the part left to it. */
    override fun padding(left: Int, top: Int, right: Int, bottom: Int) {
        map.moveCamera(CameraUpdateFactory.paddingTo(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble()))
    }
}
