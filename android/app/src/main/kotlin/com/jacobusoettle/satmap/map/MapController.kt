package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
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
import com.jacobusoettle.satmap.core.KMLGeometry
import com.jacobusoettle.satmap.core.Simplify
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Point
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

    /** While aiming the measuring tool: the dashed line from you to the crosshair. Drawn over the map rather than in
     *  it, so it follows a pan frame by frame. */
    private val aimLine = AimLineView(activity)

    /** While a measuring point is moved: the measuring line over the map (iOS's `dragLine`). */
    private val dragLine = DragLineView(activity)

    /** Choosing an offline area: the finger draws a loop over the map (shown only while it does). */
    private val lasso = LassoView(activity).apply { visibility = View.GONE }

    /** The map and its views (aim line, lasso), as the Compose screen shows them. */
    val container = MapContainerView(activity).apply {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        addView(mapView, FrameLayout.LayoutParams(fill, fill))
        addView(aimLine, FrameLayout.LayoutParams(fill, fill))
        addView(dragLine, FrameLayout.LayoutParams(fill, fill))
        addView(lasso, FrameLayout.LayoutParams(fill, fill))
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
    private var blocks: BlockLayers? = null

    /** Drone mode: aerodromes, strips, helipads and navaids, with distances from you and the tapped spot. */
    private var aviation: AviationLayer? = null
    private var markerLayer: MeasureMarkerLayer? = null
    private var measureLine: MeasureLineLayer? = null
    private var selectedLayer: SelectedPointLayer? = null
    private var draft: DraftAreaLayer? = null

    /** The zoom test, when the launch setting asks for it. */
    private var zoomScript: ZoomScript.Run? = null

    /** Pressed and held, the tapped spot's pin or a measuring point is moved precisely; taps right after are the
     *  finger being lifted, not taps. */
    private var adjuster: PointAdjuster? = null
    private var lastAdjustUpdate = 0L
    private var tapsIgnoredUntil = 0L

    /** The point being moved and where it is now: the map shows it there, whatever the model says meanwhile (the model
     *  hears of it up to 15 times a second). */
    private var adjusting: Pair<MovablePoint, GeoPoint>? = null

    /** A measuring point is being moved: its line is drawn over the map meanwhile (`updateDragLine`), not in it. */
    private var dragLineShown = false

    /** The measuring mode the markers show, and its points as the map shows them (one may be being moved). */
    private var shownMeasure: MeasureMode = MeasureMode.Off
    private val markers = ArrayList<GeoPoint>()

    /** Where the finger was lifted after a tap, and the coordinate under it then (the map may move before the tap is
     *  told, e.g. following you). MapLibre's tap is where the finger went down, or came up: both are kept. */
    private class Touch(val down: PointF, val downCoordinate: GeoPoint, val up: PointF, val upCoordinate: GeoPoint)

    private var lastTouch: Touch? = null

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

    /** The touches on the map that aren't the map's own gestures: taps, and press and hold to move a point. */
    private val touches = object : MapContainerView.Touches {
        override fun pressable(x: Float, y: Float) = !model.isDrawingArea.value && movablePoint(PointF(x, y)) != null

        override fun pressBegan(x: Float, y: Float) = adjustPressBegan(PointF(x, y))

        override fun pressMoved(x: Float, y: Float) {
            adjuster?.moved(rootPoint(x, y))
        }

        override fun pressEnded(cancelled: Boolean) = adjustPressEnded(cancelled)

        override fun touchEnded(down: PointF, up: PointF) = touchRecorded(down, up)
    }

    init {
        // Laid out again when the screen turns: the compass follows its top, and the camera hooks see the new shape.
        container.onLaidOut = { changed -> containerLaidOut(changed) }
        container.touches = touches
        container.labels = { mapLabels() }
        container.onLabelClicked = { label -> tapped(label.spot) }
        lasso.onFinish = { pts -> lassoFinished(pts) }
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
        blocks = BlockLayers(m, style, activity).also { it.onChanged = { container.labelsChanged() } }
        aviation = AviationLayer(m, style, activity).also { it.isOn = model.terrainMode.value.showsAviation }
        markerLayer = MeasureMarkerLayer(m, style, activity)
        measureLine = MeasureLineLayer(m, style, activity)
        selectedLayer = SelectedPointLayer(m, style, activity)
        adjuster = PointAdjuster(activity, mapView, m).also {
            it.onMove = { which, p, done -> adjusted(which, p, done) }
        }
        draft = DraftAreaLayer(m, style, activity)
        mapView.doOnLayout { start(m) }
    }

    /** The map has a size: the camera, the listeners, the bindings and your position, in the Swift init's order. */
    private fun start(m: MapLibreMap) {
        if (map !== m || started) return
        m.addOnCameraMoveStartedListener { reason -> regionWillChange(reason) }
        m.addOnCameraMoveListener { visibleRegionChanged() }
        m.addOnCameraIdleListener { regionDidChange() }
        // A single tap, once it can't be a double tap, a two-finger tap or the start of a one-finger zoom (MapLibre
        // tells it from onSingleTapConfirmed, as iOS's tap recognizer waits for the others to fail).
        m.addOnMapClickListener { latLng ->
            mapTapped(latLng)
            true
        }
        // The names the map shows have been placed (for accessibility).
        mapView.addOnDidBecomeIdleListener { container.labelsChanged() }
        restoreCamera(m)
        started = true
        val waiting = whenStarted.toList()
        whenStarted.clear()
        for (move in waiting) move(m)
        visibleRegionChanged()
        bind()
        zoomScript = ZoomScript.runIfAsked(container, mapView, m, model)
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
                aviation?.isOn = mode.showsAviation
            }
        }
        scope.launch { model.measure.collect { measureChanged(it) } }
        scope.launch {
            combine(model.selectedPoint, model.measure.map { it == MeasureMode.Off }) { p, notMeasuring ->
                if (notMeasuring) p else null
            }.collect { selectedPointChanged(it) }
        }
        scope.launch {
            combine(model.kml.files, model.kml.useFileColours) { files, colours -> files to colours }
                .collect { (files, colours) -> blocks?.update(files, colours) }
        }
        // Drone mode's distances: from you and from the tapped spot.
        scope.launch { model.selectedPoint.collect { aviationDistances(it) } }
        scope.launch {
            combine(model.isDrawingArea, model.lassoActive, model.draftArea.map { it != null }) { drawing, lassoActive, hasDraft ->
                drawing && lassoActive && !hasDraft
            }.collect { shows -> lasso.visibility = if (shows) View.VISIBLE else View.GONE }
        }
        scope.launch { model.isDrawingArea.collect { lasso.reset() } }
        scope.launch { model.draftArea.collect { draftChanged(it) } }
        scope.launch { model.recorder.power.collect { powerChanged() } }
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
        aviation?.regionChanged()
        container.labelsChanged()
    }

    /** Drone mode's labels: how far each place is from you and from the tapped spot. */
    private fun aviationDistances(pin: GeoPoint?) {
        aviation?.distancesChanged(model.recorder.location.value?.point, pin)
    }

    /** Every camera frame (iOS mapViewDidChangeVisibleRegion). Your compass arrow and the runway rings are turned
     *  against the map's north, so MapLibre turns them with the map; the rings follow the cards it shows. */
    private fun visibleRegionChanged() {
        val m = map ?: return
        updateAim()
        if (dragLineShown) updateDragLine() // the map scrolls while a point is moved
        updateScale()
        aviation?.headingChanged()
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

    /** While aiming: the spot under the crosshair (for the live distance and height) and the line from you to it. */
    private fun updateAim() {
        if (model.measure.value != MeasureMode.Aiming) {
            aimLine.show(null)
            return
        }
        model.aim.set(centerCoordinate)
        val m = map
        val me = model.recorder.location.value
        if (m == null || !started || me == null) {
            aimLine.show(null)
            return
        }
        val a = m.projection.toScreenLocation(LatLng(me.lat, me.lon))
        val b = PointF(aimLine.width / 2f, aimLine.height / 2f)
        aimLine.show(aimLine(a, b, RectF(0f, 0f, aimLine.width.toFloat(), aimLine.height.toFloat()), density))
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
        updateMeasureLine()
        updateAim()
        aviationDistances(model.selectedPoint.value)
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

    // Tapping the map

    /** A one-finger touch that didn't move came up: where the finger was, and the coordinate under it now. */
    private fun touchRecorded(down: PointF, up: PointF) {
        val m = map ?: return
        if (!started) return
        lastTouch = Touch(down, coordinate(m, down), up, coordinate(m, up))
    }

    /** A single tap (not the first tap of a double-tap zoom) on the map, outside lasso drawing. */
    private fun mapTapped(latLng: LatLng) {
        val m = map ?: return
        if (model.isDrawingArea.value || SystemClock.uptimeMillis() < tapsIgnoredUntil || adjuster?.isActive == true) {
            return
        }
        // MapLibre turned the tap's pixel into the coordinate with the map as it is now: back to the pixel.
        val point = m.projection.toScreenLocation(latLng)
        val wrapped = latLng.wrap()
        var c = GeoPoint(wrapped.latitude, wrapped.longitude)
        lastTouch?.let { t ->
            if (apart(t.up, point) < 1) {
                c = t.upCoordinate
            } else if (apart(t.down, point) < 1) {
                c = t.downCoordinate
            }
        }
        lastTouch = null
        tapped(point, c)
    }

    /** A tap at `point` (the map view's pixels) on the spot `at`: where the finger was on the map, which may have moved
     *  since (following you); by default the spot under `point`. */
    private fun tapped(point: PointF, at: GeoPoint? = null) {
        val m = map ?: return
        val c = at ?: coordinate(m, point)
        // A tap on a point in a KML file (a launch point …) selects it: its own position, with its name.
        val spot = m.projection.toScreenLocation(LatLng(c.lat, c.lon))
        val measure = model.measure.value
        if (measure == MeasureMode.Off) {
            kmlPoint(m, spot)?.let { k ->
                model.pointTapped(k.coordinate, k.name)
                return
            }
        }
        // In drone mode, an aerodrome or navaid likewise: the distances to the others are then from it.
        if (measure == MeasureMode.Off) {
            aviation?.place(spot)?.let { a ->
                model.pointTapped(a.point, "${a.label} · ${a.kind.title}")
                return
            }
        }
        // A tap on point A, with three or more points, closes the shape.
        if (measure is MeasureMode.Path && measure.pts.size >= 3) {
            val a = m.projection.toScreenLocation(LatLng(measure.pts[0].lat, measure.pts[0].lon))
            if (apart(a, point) < 30) {
                model.closeMeasureShape()
                return
            }
        }
        model.mapTapped(c)
    }

    /** The KML point whose dot is nearest a spot on the screen: within a fingertip of the dot, or on its name (MapLibre
     *  says which names it shows, and where). */
    private fun kmlPoint(m: MapLibreMap, spot: PointF): BlockLayers.Point? {
        val b = blocks ?: return null
        if (b.pointLayerIds.isEmpty()) return null // (no layers would ask every layer)
        val onName by lazy {
            // Its frame enlarged by 6 dp: MapLibre's box has the labels' room round it already.
            val r = (6 - MapStyle.LABEL_PADDING) * density
            val near = RectF(spot.x - r, spot.y - r, spot.x + r, spot.y + r)
            m.queryRenderedFeatures(near, *b.pointLayerIds).mapNotNull { f ->
                val file = f.getStringProperty("file") ?: return@mapNotNull null
                val i = f.getNumberProperty("i") ?: return@mapNotNull null
                file to i.toInt()
            }.toSet()
        }
        var best: Pair<BlockLayers.Point, Double>? = null
        for (k in b.points) {
            val p = m.projection.toScreenLocation(LatLng(k.coordinate.lat, k.coordinate.lon))
            var d = apart(p, spot)
            if (d >= 26 && (k.file.toString() to k.index) in onName) d = 25.0 // on its name
            if (d < 26 && d < (best?.second ?: Double.POSITIVE_INFINITY)) best = k to d
        }
        return best?.first
    }

    private fun selectedPointChanged(p: GeoPoint?) {
        // A pin being moved stays under the finger.
        val moving = adjusting?.takeIf { it.first == MovablePoint.Selected }?.second
        selectedLayer?.update(if (p != null && moving != null) moving else p)
    }

    // Moving a point precisely

    /** The point a press would move: a measuring point, or the tapped spot's pin, within a fingertip of the spot (the
     *  map view's pixels). */
    private fun movablePoint(spot: PointF): Pair<MovablePoint, GeoPoint>? {
        val m = map ?: return null
        if (!started) return null
        fun apart(p: GeoPoint) = apart(m.projection.toScreenLocation(LatLng(p.lat, p.lon)), spot)
        val points = model.measure.value.points
        val i = points.indices.minByOrNull { apart(points[it]) }
        if (i != null && apart(points[i]) < 30) return MovablePoint.Measure(i) to points[i]
        val p = model.selectedPoint.value
        if (model.measure.value == MeasureMode.Off && p != null && apart(p) < 30) return MovablePoint.Selected to p
        return null
    }

    /** The finger has held a point for 0.35 s: the map holds still under the finger (it scrolls only with the point:
     *  `PointAdjuster`) and the square appears. False when there is no point there any more. */
    private fun adjustPressBegan(spot: PointF): Boolean {
        val m = map ?: return false
        val a = adjuster ?: return false
        if (model.isDrawingArea.value) return false
        val (which, point) = movablePoint(spot) ?: return false
        gestures(m, on = false)
        model.setFollowing(false)
        if (which is MovablePoint.Measure) {
            dragLineShown = true
            updateMeasureLine()
            updateDragLine()
        }
        container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) // iOS: a medium impact
        a.begin(which, point, rootPoint(spot.x, spot.y), loupeScene(point, which))
        return true
    }

    /** The finger was lifted (or the press cancelled: the point goes back). */
    private fun adjustPressEnded(cancelled: Boolean) {
        adjuster?.end(cancelled)
        map?.let { gestures(it, on = true) }
        tapsIgnoredUntil = SystemClock.uptimeMillis() + 400
    }

    /** The map's own gestures: off while a point is moved. Tilt stays off. */
    private fun gestures(m: MapLibreMap, on: Boolean) {
        m.uiSettings.apply {
            isScrollGesturesEnabled = on
            isZoomGesturesEnabled = on
            isRotateGesturesEnabled = on
            isDoubleTapGesturesEnabled = on
            isQuickZoomGesturesEnabled = on
        }
    }

    /** The point moved: on the map at once; for the panels (distances, coordinates, heights) up to 15 times a second,
     *  and when the finger is lifted. */
    private fun adjusted(which: MovablePoint, p: GeoPoint, done: Boolean) {
        adjusting = if (done) null else which to p
        when (which) {
            is MovablePoint.Measure -> {
                if (which.i in markers.indices) {
                    markers[which.i] = p
                    markerLayer?.update(markers)
                }
                if (done) dragLineShown = false
                updateDragLine()
            }
            MovablePoint.Selected -> selectedLayer?.update(p)
        }
        val now = SystemClock.uptimeMillis()
        if (!done && now - lastAdjustUpdate < 1000.0 / 15) return
        lastAdjustUpdate = now
        model.movePoint(which, p, done)
        if (done) {
            updateMeasureLine()
            container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) // iOS: a light impact
        }
    }

    /** What the square shows besides the imagery: the blocks near the point, the measuring line and points, and you. */
    private fun loupeScene(around: GeoPoint, moving: MovablePoint): LoupeView.Scene {
        val m = WebMercator.world(around)
        val reach = 2_000 / Geo.metersPerWorldUnit(around.lat) // far enough for the map to scroll a while
        val near = WorldRect(m.x - reach, m.y - reach, m.x + reach, m.y + reach)
        val lines = ArrayList<LoupeView.Line>()
        val dots = ArrayList<LoupeView.Dot>()
        val colours = model.kml.useFileColours.value
        for (f in model.kml.files.value) {
            if (!f.isShown) continue
            for (feature in f.document.features) {
                val style = if (colours) feature.style else null
                val line = style?.lineColor?.argb() ?: MapColours.blockStroke
                when (val g = feature.geometry) {
                    // By their boxes: a long edge passing the point counts, though its corners are far away.
                    is KMLGeometry.Polygon -> for (ring in listOf(g.outer) + g.holes) {
                        val pts = ring.map(WebMercator::world)
                        val b = LoupeView.box(pts)
                        if (LoupeView.overlap(b, near)) lines += LoupeView.Line(pts, line, true, b)
                    }
                    is KMLGeometry.Line -> {
                        val pts = g.points.map(WebMercator::world)
                        val b = LoupeView.box(pts)
                        if (LoupeView.overlap(b, near)) lines += LoupeView.Line(pts, line, false, b)
                    }
                    is KMLGeometry.Point -> {
                        val q = WebMercator.world(g.point)
                        if (q.x >= near.minX && q.x <= near.maxX && q.y >= near.minY && q.y <= near.maxY) {
                            dots += LoupeView.Dot(q, style?.iconColor?.argb() ?: Color.WHITE)
                        }
                    }
                }
            }
        }
        val measure = model.measure.value
        return LoupeView.Scene(
            lines = lines,
            dots = dots,
            measure = measure.points.map(WebMercator::world),
            measureClosed = measure is MeasureMode.Closed,
            moving = (moving as? MovablePoint.Measure)?.i,
            user = model.recorder.location.value?.let { WebMercator.world(it.point) },
        )
    }

    // Measuring

    private fun measureChanged(m: MeasureMode) {
        shownMeasure = m
        markers.clear()
        markers.addAll(m.points)
        // A measuring point being moved stays under the finger.
        adjusting?.let { (which, p) -> if (which is MovablePoint.Measure && which.i in markers.indices) markers[which.i] = p }
        markerLayer?.update(markers)
        updateMeasureLine()
        updateAim()
    }

    /** From you to the point, or through the points (back to the first when the shape is closed). */
    private fun updateMeasureLine() {
        val me = model.recorder.location.value?.point
        var line: List<GeoPoint> = emptyList()
        var closed = false
        when (shownMeasure) {
            is MeasureMode.Placed -> if (me != null && markers.size == 1) line = listOf(markers[0], me)
            is MeasureMode.Path -> line = markers.toList()
            is MeasureMode.Closed -> {
                line = markers.toList()
                closed = true
            }
            MeasureMode.Off, MeasureMode.Aiming -> {}
        }
        if (dragLineShown) line = emptyList() // drawn over the map meanwhile (`updateDragLine`)
        measureLine?.set(line, closed)
    }

    /** The measuring line over the map while a point is moved: through the markers as they are now (from you, for a
     *  single point; joined back and filled for a closed shape). */
    private fun updateDragLine() {
        val m = map
        if (!dragLineShown || m == null) {
            dragLine.show(null)
            return
        }
        val line = DragLineView.points(markers, model.recorder.location.value?.point)
        val closed = shownMeasure is MeasureMode.Closed && line.size >= 3
        dragLine.show(DragLineView.Line(line.map { m.projection.toScreenLocation(LatLng(it.lat, it.lon)) }, closed))
    }

    // Lasso (choose an offline area)

    private fun lassoFinished(pts: List<PointF>) {
        val m = map
        val simplified = if (m != null && started) lassoRing(pts, density) else null
        if (m == null || simplified == null) {
            // A tap or a straight stroke isn't an area: clear it and let the user draw a loop.
            lasso.reset()
            model.finishDrawing(emptyList())
            return
        }
        model.finishDrawing(simplified.map { coordinate(m, it) })
    }

    private fun draftChanged(ring: List<GeoPoint>?) {
        if (ring == null || ring.size < 3) {
            draft?.update(null)
            return
        }
        lasso.reset()
        draft?.update(ring)
    }

    // The names on the map, for accessibility

    /** A name MapLibre has placed: what it says, its box and the spot a tap on it selects (the map view's pixels). */
    private class Placed(val name: String, val box: RectF, val spot: PointF)

    /** Every name MapLibre has placed in the symbol layers `layerIds` (iOS: the annotations it shows, each an
     *  accessibility element), with its box in the map view's pixels. Also the screenshot walk's way to find a name on
     *  the map. */
    fun renderedLabels(layerIds: List<String>): List<Pair<String, RectF>> =
        placed(layerIds.toTypedArray()).map { it.name to it.box }

    private fun placed(layerIds: Array<String>): List<Placed> {
        val m = map ?: return emptyList()
        if (!started || layerIds.isEmpty() || mapView.width <= 0) return emptyList()
        val screen = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
        val d = density
        return m.queryRenderedFeatures(screen, *layerIds).mapNotNull { f ->
            val name = f.getStringProperty("name")
            val p = f.geometry() as? Point
            if (name.isNullOrEmpty() || p == null) return@mapNotNull null
            val s = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
            val w = (f.getNumberProperty("w")?.toFloat() ?: 0f) * d
            val h = (f.getNumberProperty("h")?.toFloat() ?: 0f) * d
            Placed(name, labelBox(f.getStringProperty("kind"), s, w, h, d), s)
        }.distinctBy { Triple(it.name, it.spot.x, it.spot.y) } // (a symbol across tile edges is found twice)
    }

    /** The names on the map that accessibility services read: the KML points shown and, in drone mode, the cards
     *  shown ("Airstrip FASH Stellenbosch", iOS's accessibility label), top to bottom. */
    private fun mapLabels(): List<MapContainerView.Label> {
        val aviationLayers = if (aviation?.isOn == true) listOf(MapIds.AVIATION) else emptyList()
        val layers = blocks?.pointLayerIds.orEmpty().toList() + aviationLayers
        return placed(layers.toTypedArray())
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .map { MapContainerView.Label(it.name, it.box, it.spot) }
    }

    /** A spot in the map view as the content root has it (the loupe goes there), in pixels. */
    private fun rootPoint(x: Float, y: Float): PointF {
        val root = activity.findViewById<View>(android.R.id.content) ?: return PointF(x, y)
        val a = IntArray(2).also { container.getLocationOnScreen(it) }
        val b = IntArray(2).also { root.getLocationOnScreen(it) }
        return PointF(x + a[0] - b[0], y + a[1] - b[1])
    }

    private fun coordinate(m: MapLibreMap, p: PointF): GeoPoint {
        val c = m.projection.fromScreenLocation(p).wrap()
        return GeoPoint(c.latitude, c.longitude)
    }

    /** How far apart two spots of the map view are, in dp. */
    private fun apart(a: PointF, b: PointF): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) / density

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
        blocks?.stop()
        blocks = null
        aviation?.stop()
        aviation = null
        markerLayer = null
        measureLine = null
        selectedLayer = null
        draft = null
        zoomScript?.stop()
        zoomScript = null
        lasso.onFinish = null
        adjuster?.stop()
        adjuster = null
        imagery?.destroy()
        imagery = null
        map = null
        started = false
        whenStarted.clear()
        container.onLaidOut = null
        container.touches = null
        container.labels = null
        container.onLabelClicked = null
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

        /** The aim line from you (`me`) to the crosshair (`centre`) in a view of `bounds`, in pixels (`density` per dp):
         *  from 11 dp off your dot's centre, only when that leaves more than 4 dp of line. With you off the screen it
         *  starts where it comes onto the view (enlarged by 20 dp), with the dashes where they'd be. */
        fun aimLine(me: PointF, centre: PointF, bounds: RectF, density: Float): AimLine? {
            val length = hypot(centre.x - me.x, centre.y - me.y)
            val dot = 11 * density // from the edge of your dot
            if (length <= dot + 4 * density) return null
            val edge = PointF(me.x + (centre.x - me.x) / length * dot, me.y + (centre.y - me.y) / length * dot)
            val margin = 20 * density
            val start = entry(edge, centre, RectF(bounds.left - margin, bounds.top - margin, bounds.right + margin, bounds.bottom + margin))
            val phase = hypot(start.x - edge.x, start.y - edge.y) % (17 * density)
            return AimLine(start, centre, phase)
        }

        /** The drawn loop (pixels, `density` per dp) simplified as iOS does it, Douglas–Peucker with a tolerance of
         *  4 dp; null when it isn't an area (fewer than 3 points, before or after). */
        fun lassoRing(pts: List<PointF>, density: Float): List<PointF>? {
            val d = density.toDouble()
            val simplified = Simplify.douglasPeucker(pts.map { XY(it.x / d, it.y / d) }, tolerance = 4.0)
            if (pts.size < 3 || simplified.size < 3) return null
            return simplified.map { PointF((it.x * d).toFloat(), (it.y * d).toFloat()) }
        }

        /** A placed label's box (pixels, `d` per dp) from its point `s` on the screen and its image's size in pixels:
         *  a KML point's image has its dot on top, 5 dp above the point; an aviation card has its symbol's middle on
         *  the place, 11 dp below its top; a name is centred on it (half a dp lower, for the shadow). */
        fun labelBox(kind: String?, s: PointF, w: Float, h: Float, d: Float): RectF {
            val top = when (kind) {
                "point" -> s.y - 5 * d
                AviationLayer.KIND -> s.y - AviationCard.symbolSize / 2 * d
                else -> s.y + d / 2 - h / 2
            }
            return RectF(s.x - w / 2, top, s.x + w / 2, top + h)
        }

        /** Where the line from `a` to `b` (inside `r`) comes into `r`; `a` itself when it is inside. */
        fun entry(a: PointF, b: PointF, r: RectF): PointF {
            var t = 0f
            if (a.x < r.left) t = max(t, (r.left - a.x) / (b.x - a.x))
            if (a.x > r.right) t = max(t, (a.x - r.right) / (a.x - b.x))
            if (a.y < r.top) t = max(t, (r.top - a.y) / (b.y - a.y))
            if (a.y > r.bottom) t = max(t, (a.y - r.bottom) / (a.y - b.y))
            return PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }

    /** The aim line: from `start` to `end` (the crosshair), its dashes `phase` pixels into their pattern. */
    data class AimLine(val start: PointF, val end: PointF, val phase: Float)
}

/** The region's edges as MapLibre bounds. */
private fun MapController.Region.latLngBounds(): LatLngBounds {
    val b = bounds
    return LatLngBounds.from(b.maxLat, b.maxLon, b.minLat, b.minLon)
}

/** The map and the views over it, which says when it is laid out (as when the screen turns). It sees every touch before
 *  the `MapView`: it records where a tap's finger was (iOS's touch recorder), and runs the press and hold that moves a
 *  point (iOS's long press recognizer: 0.35 s, failing when the finger moves more than 10 dp or a second finger lands,
 *  and never the press of a tap then press, a double-tap or one-finger zoom); while a point is moved it keeps every
 *  touch from the map. It also gives accessibility services the names the map shows (images to MapLibre), as MapKit
 *  makes its annotations accessibility elements. */
class MapContainerView(context: Context) : FrameLayout(context) {
    /** After each layout, with the views already laid out: `changed` when the container's frame changed (its size, as
     *  when the screen turns). */
    var onLaidOut: ((changed: Boolean) -> Unit)? = null

    /** What the map does with touches (`MapController`); container pixels. */
    interface Touches {
        /** Whether a press here would move a point. */
        fun pressable(x: Float, y: Float): Boolean

        /** The press has been held at (x, y): true when a point is now being moved. */
        fun pressBegan(x: Float, y: Float): Boolean
        fun pressMoved(x: Float, y: Float)

        /** The finger was lifted, or the press cancelled. */
        fun pressEnded(cancelled: Boolean)

        /** One finger went down and came up without moving: where. */
        fun touchEnded(down: PointF, up: PointF)
    }

    var touches: Touches? = null

    private val density = resources.displayMetrics.density
    private val configuration = ViewConfiguration.get(context)
    private var down = PointF()
    private var downTime = 0L
    private var last = PointF()
    private var pointerId = -1

    /** How far the finger has gone from where it went down (pixels), and whether a second finger came. */
    private var farthest = 0f
    private var multi = false

    /** The last tap (where its finger went down, when it came up), for telling a tap then press. */
    private var lastTap: PointF? = null
    private var lastTapUp = 0L
    private var armed = false
    private var adjusting = false

    /** A point was moved and let go with another finger still down: the rest of that gesture isn't the map's. */
    private var swallowing = false
    private val press = Runnable { pressHeld() }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLaidOut?.invoke(changed)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val t = touches ?: return super.dispatchTouchEvent(ev)
        // A point being moved owns the gesture: the map sees nothing of it.
        if (adjusting || swallowing) {
            adjust(ev, t)
            return true
        }
        observe(ev, t)
        return super.dispatchTouchEvent(ev)
    }

    private fun observe(ev: MotionEvent, t: Touches) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disarm()
                down = PointF(ev.x, ev.y)
                last = PointF(ev.x, ev.y)
                downTime = ev.downTime
                pointerId = ev.getPointerId(0)
                farthest = 0f
                multi = false
                // Not the press of a one-finger zoom (tap, then press) that happens to pause on a point.
                val tap = lastTap
                val tapThenPress = tap != null && ev.eventTime - lastTapUp <= ViewConfiguration.getDoubleTapTimeout() &&
                    hypot(ev.x - tap.x, ev.y - tap.y) < configuration.scaledDoubleTapSlop
                if (!tapThenPress && t.pressable(ev.x, ev.y)) {
                    armed = true
                    postDelayed(press, PRESS_MILLIS)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                disarm()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(pointerId)
                if (i < 0) return
                moved(ev.getX(i), ev.getY(i))
                if (hypot(last.x - down.x, last.y - down.y) > 10 * density) disarm() // UILongPress allowableMovement
            }
            MotionEvent.ACTION_UP -> {
                disarm()
                moved(ev.x, ev.y)
                if (!multi && farthest < configuration.scaledTouchSlop) {
                    t.touchEnded(PointF(down.x, down.y), PointF(ev.x, ev.y))
                    lastTap = PointF(down.x, down.y)
                    lastTapUp = ev.eventTime
                } else {
                    lastTap = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                disarm()
                lastTap = null
            }
        }
    }

    private fun moved(x: Float, y: Float) {
        last = PointF(x, y)
        farthest = max(farthest, hypot(x - down.x, y - down.y))
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        removeCallbacks(press)
    }

    /** Held long enough: the point is moved from here on, and the map's own gestures stop now (its scroll, its click
     *  and its long press). */
    private fun pressHeld() {
        armed = false
        val t = touches ?: return
        if (!t.pressBegan(last.x, last.y)) return
        adjusting = true
        lastTap = null
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, last.x, last.y, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun adjust(ev: MotionEvent, t: Touches) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (adjusting) {
                val i = ev.findPointerIndex(pointerId)
                if (i >= 0) t.pressMoved(ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (adjusting && ev.getPointerId(ev.actionIndex) == pointerId) {
                finish(t, cancelled = false)
                swallowing = true
            }
            MotionEvent.ACTION_UP -> {
                if (adjusting) finish(t, cancelled = false)
                swallowing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (adjusting) finish(t, cancelled = true)
                swallowing = false
            }
        }
    }

    private fun finish(t: Touches, cancelled: Boolean) {
        adjusting = false
        t.pressEnded(cancelled)
        if (labelsUntold) {
            labelsUntold = false
            labelsChanged()
        }
    }

    override fun onDetachedFromWindow() {
        disarm()
        if (adjusting) touches?.let { finish(it, cancelled = true) }
        swallowing = false
        removeCallbacks(tellLabels)
        super.onDetachedFromWindow()
    }

    // The names on the map, for accessibility services (each a virtual view of this one, as with ExploreByTouchHelper)

    /** A name the map shows: what it says, its box (container pixels), and the spot where a tap selects it. */
    class Label(val description: String, val bounds: RectF, val spot: PointF)

    /** The names the map shows now. */
    var labels: (() -> List<Label>)? = null

    /** A name activated (double-tapped with TalkBack): as a tap on it. */
    var onLabelClicked: ((Label) -> Unit)? = null

    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val names = Names()

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = names

    /** Changed names not yet told, held back while a point is moved. */
    private var labelsUntold = false
    private val tellLabels = Runnable { sendLabelsChanged() }

    /** The names may have changed (the map came to rest, files were shown): accessibility services are told so, and
     *  read them again when they ask (the names are found then, as `ExploreByTouchHelper.invalidateRoot` does; finding
     *  them asks the map what it shows, which waits for its renderer). Told once things settle, 100 ms after the last
     *  change (Android's own interval for content changes; the map comes to rest every frame while the loupe scrolls
     *  it), and only after a point being moved is let go. */
    fun labelsChanged() {
        names.stale = true
        removeCallbacks(tellLabels)
        if (accessibility?.isEnabled != true) return
        if (adjusting) {
            labelsUntold = true
        } else {
            postDelayed(tellLabels, LABELS_SETTLE_MILLIS)
        }
    }

    private fun sendLabelsChanged() {
        if (accessibility?.isEnabled != true) return
        if (adjusting) {
            labelsUntold = true
            return
        }
        val parent = parent ?: return
        @Suppress("DEPRECATION")
        val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        e.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        onInitializeAccessibilityEvent(e)
        parent.requestSendAccessibilityEvent(this, e)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibility?.isEnabled == true && accessibility.isTouchExplorationEnabled && names.hover(event)) return true
        return super.dispatchHoverEvent(event)
    }

    /** The virtual views: one per name, numbered in reading order (top to bottom), under the container's own node,
     *  which keeps its real children (the map). */
    private inner class Names : AccessibilityNodeProvider() {
        private var shown: List<Label> = emptyList()
        private var focused = INVALID
        private var hovered = INVALID

        /** The names may have changed since they were last found. */
        var stale = true

        fun refresh() {
            val wasHovered = shown.getOrNull(hovered)
            val wasFocused = shown.getOrNull(focused)
            shown = labels?.invoke().orEmpty()
            stale = false
            // Ids are places in the list: the name under the finger and the one in focus keep theirs at their new
            // places, if they still show.
            hovered = place(wasHovered)
            focused = place(wasFocused)
        }

        private fun place(label: Label?): Int {
            if (label == null) return INVALID
            val i = shown.indexOfFirst { it.description == label.description }
            return if (i >= 0) i else INVALID
        }

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            val host = this@MapContainerView
            if (virtualViewId == HOST_VIEW_ID) {
                @Suppress("DEPRECATION")
                val info = AccessibilityNodeInfo.obtain(host)
                host.onInitializeAccessibilityNodeInfo(info)
                for (i in 0 until childCount) {
                    if (getChildAt(i).visibility == VISIBLE) info.addChild(getChildAt(i))
                }
                refresh()
                for (i in shown.indices) info.addChild(host, i)
                return info
            }
            val label = shown.getOrNull(virtualViewId) ?: return null
            @Suppress("DEPRECATION")
            val info = AccessibilityNodeInfo.obtain(host, virtualViewId)
            info.setParent(host)
            info.packageName = context.packageName
            info.className = View::class.java.name
            info.contentDescription = label.description
            val r = Rect()
            label.bounds.roundOut(r)
            if (!r.intersect(0, 0, width, height)) r.setEmpty()
            @Suppress("DEPRECATION")
            info.setBoundsInParent(r)
            val at = IntArray(2).also { getLocationOnScreen(it) }
            r.offset(at[0], at[1])
            info.setBoundsInScreen(r)
            info.isVisibleToUser = !r.isEmpty
            info.isEnabled = true
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (focused == virtualViewId) {
                info.isAccessibilityFocused = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == HOST_VIEW_ID) return performAccessibilityAction(action, arguments)
            val label = shown.getOrNull(virtualViewId) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (focused == virtualViewId) return false
                    if (focused != INVALID) send(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    focused = virtualViewId
                    invalidate()
                    send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (focused != virtualViewId) return false
                    focused = INVALID
                    invalidate()
                    send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    onLabelClicked?.invoke(label)
                    send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }
                else -> false
            }
        }

        /** Touch exploration: the name under the finger (the names found again first if they may have changed). */
        fun hover(event: MotionEvent): Boolean {
            if (stale) refresh()
            val id = when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                    shown.indexOfFirst { it.bounds.contains(event.x, event.y) }
                else -> INVALID
            }
            if (id != hovered) {
                val old = hovered
                hovered = id
                if (id != INVALID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                if (old != INVALID) send(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            }
            return id != INVALID
        }

        private fun send(virtualViewId: Int, type: Int) {
            if (accessibility?.isEnabled != true) return
            val label = shown.getOrNull(virtualViewId) ?: return
            val parent = parent ?: return
            @Suppress("DEPRECATION")
            val e = AccessibilityEvent.obtain(type)
            e.packageName = context.packageName
            e.className = View::class.java.name
            e.contentDescription = label.description
            e.isEnabled = true
            e.setSource(this@MapContainerView, virtualViewId)
            parent.requestSendAccessibilityEvent(this@MapContainerView, e)
        }
    }

    companion object {
        /** iOS's minimum press duration for moving a point. */
        const val PRESS_MILLIS = 350L

        /** How long the names stay unchanged before services are told: Android's own interval for content changes
         *  (`ViewConfiguration`'s hidden `SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS`). */
        const val LABELS_SETTLE_MILLIS = 100L
        private const val INVALID = Int.MIN_VALUE
    }
}

/** While aiming the measuring tool: the dashed line from your dot to the crosshair (pink, 3 dp, dashes 10 and 7 dp,
 *  butt caps, as the measuring line). Over the map rather than in it, as one end is on the screen's centre and it
 *  follows a pan frame by frame. Takes no touches. */
class AimLineView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MapColours.measurePink
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.BUTT
    }
    private var line: MapController.AimLine? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows the line (null: none). */
    fun show(line: MapController.AimLine?) {
        if (line == this.line) return
        this.line = line
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = line ?: return
        paint.pathEffect = DashPathEffect(floatArrayOf(10 * density, 7 * density), l.phase)
        canvas.drawLine(l.start.x, l.start.y, l.end.x, l.end.y, paint)
    }
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
