package com.jacobusoettle.satmap.app

import android.app.Application
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.TrackPoint
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.MovablePoint
import com.jacobusoettle.satmap.map.TerrainKey
import com.jacobusoettle.satmap.map.TileRepository
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.UUID

/** Looks up a spot's height (`TileRepository` answers it). Safe to call from the main thread. */
interface ElevationSource {
    suspend fun elevation(p: GeoPoint): Double?
}

/** What the model asks of the phone's map (`MapController`). */
interface MapHost {
    /** The coordinate under the view centre (the crosshair). */
    val centerCoordinate: GeoPoint
    val isOnScreen: Boolean

    /** Their distance apart on screen, in dp (iOS points). */
    fun pointsApart(a: GeoPoint, b: GeoPoint): Double
    fun center(on: GeoPoint, animated: Boolean)
    fun zoom(onto: GeoPoint, metersAcross: Double)
    fun zoom(to: GeoBounds)
    fun locationUpdated(fix: GpsFix)
    fun headingUpdated(heading: Double?)
    fun reloadImagery()
}

/** What the model asks of the car's map (`CarMapController`). */
interface CarMapHost {
    fun locationUpdated(fix: GpsFix)
    fun reload()
}

/** The measuring tool. A point is placed at the crosshair, with the measure button or a tap anywhere on the map.
 *  off → aiming → placed (distance from you to A) → path (A → B → C …, total distance) → closed (back to A: area). */
sealed interface MeasureMode {
    data object Off : MeasureMode

    /** Crosshair shown, waiting for the first point. */
    data object Aiming : MeasureMode

    /** One point: the live distance and height from you to it. */
    data class Placed(val a: GeoPoint) : MeasureMode

    /** Two or more points: the line through them, its total length and the height from first to last. */
    data class Path(val pts: List<GeoPoint>) : MeasureMode

    /** Three or more points joined back to the first: the shape's area and perimeter. */
    data class Closed(val pts: List<GeoPoint>) : MeasureMode

    val points: List<GeoPoint>
        get() = when (this) {
            Off, Aiming -> emptyList()
            is Placed -> listOf(a)
            is Path -> pts
            is Closed -> pts
        }

    /** The same with point `i` moved (`PointAdjuster`). */
    fun moving(i: Int, p: GeoPoint): MeasureMode = when (this) {
        is Placed -> if (i == 0) Placed(p) else this
        is Path -> if (i in pts.indices) Path(pts.toMutableList().also { it[i] = p }) else this
        is Closed -> if (i in pts.indices) Closed(pts.toMutableList().also { it[i] = p }) else this
        Off, Aiming -> this
    }

    companion object {
        /** "A", "B", … "Z", "AA", "AB", … "ZZ", "AAA", … */
        fun label(i: Int): String {
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var n = i + 1
            var s = ""
            while (n > 0) {
                n -= 1
                s = letters[n % 26] + s
                n /= 26
            }
            return s
        }
    }
}

/** While aiming the measuring tool: the spot under the crosshair and its height, which change as the map moves. Kept
 *  out of `AppModel` so a pan recomposes only the measuring panel, not the whole screen. */
class CrosshairAim(private val source: ElevationSource) {
    private val _point = MutableStateFlow<GeoPoint?>(null)
    val point: StateFlow<GeoPoint?> = _point.asStateFlow()

    /** The height last looked up and the spot it is for: while the map moves, an earlier spot's. */
    private val _elevation = MutableStateFlow<Double?>(null)
    val elevation: StateFlow<Double?> = _elevation.asStateFlow()
    private val _elevationPoint = MutableStateFlow<GeoPoint?>(null)
    val elevationPoint: StateFlow<GeoPoint?> = _elevationPoint.asStateFlow()

    /** The spot under the crosshair (nil when not aiming). */
    fun set(p: GeoPoint?) {
        if (p == _point.value) return
        _point.value = p
        // M3: the Swift lookUp (one lookup at a time, 31 s retries capped at 4, session bump on nil).
    }

    /** The network is back: looks up a height that couldn't be found. */
    fun retry() {
        // M3: lookedUp = nil; lookUp().
    }
}

/** The scale bar's round distance and its length on screen, set by the map as it zooms. Kept out of `AppModel` so a
 *  zoom recomposes only the bar. */
class MapScale {
    private val _label = MutableStateFlow("")
    val label: StateFlow<String> = _label.asStateFlow()
    private val _width = MutableStateFlow(0.0)
    val width: StateFlow<Double> = _width.asStateFlow()

    /** Metres or kilometres, feet or miles, or nautical miles (as distances are shown). */
    var unit: DistanceUnit = DistanceUnit.metric
        set(value) {
            if (value != field) {
                field = value
                choose()
            }
        }
    private var metersPerPoint: Double? = null

    fun update(metersPerPoint: Double) {
        this.metersPerPoint = metersPerPoint
        choose()
    }

    private fun choose() {
        // M1: label and width from unit.scale(metersPerPoint, maxWidth), the width rounded to half points.
    }

    companion object {
        /** The bar's longest, in dp. */
        const val maxWidth = 110.0
    }
}

/** Drone mode's reporting lines: where you are, and where the tapped spot is, from the nearest airfield, as a pilot
 *  says it ("3.2 NM NE of FASZ Skukuza"). */
data class DroneReport(val you: String? = null, val pin: String? = null)

/** What the legends beside the scale bar show (the contour interval and the elevation colours' range), set by the map
 *  as it zooms. Kept out of `AppModel` so a zoom recomposes only them. */
class MapLegend {
    private val _key = MutableStateFlow(TerrainKey())
    val key: StateFlow<TerrainKey> = _key.asStateFlow()

    fun update(k: TerrainKey) {
        if (k != _key.value) _key.value = k
    }
}

/** Looks up the height of a spot that may keep changing (your position, a tapped spot): one lookup at a time, always
 *  for the latest spot. Main thread only. */
class HeightLookup(private val source: ElevationSource, private val found: (GeoPoint, Double?) -> Unit) {
    /** The latest spot asked about. */
    var point: GeoPoint? = null
        private set

    fun set(p: GeoPoint?) {
        if (p == point) return
        point = p
        // M1: the Swift lookUp (one lookup at a time, 31 s retries capped at 4 while active, session bump on nil).
    }

    /** Looks up the latest spot again (the network is back, or the app was opened). */
    fun retry() {
        // M1: lookedUp = nil; lookUp().
    }
}

/** A shape waiting for the user to confirm an offline download. */
data class PendingArea(val id: UUID = UUID.randomUUID(), val name: String, val shape: CoverageShape)

enum class ActiveSheet { blocks, offline, coordinates }

/** The app's state, for the whole process (not a ViewModel: recording, downloads and Android Auto run without an
 *  activity). All of it is written on the main thread; a Swift `@Published var x` is `val x: StateFlow` plus
 *  `fun setX(v)`, which runs the Swift `didSet`. */
class AppModel(private val app: Application, private val elevation: ElevationSource = TileRepository.shared) :
    AppLifecycle.Observer {

    companion object {
        /** Set by `init` at startup. Tests may install their own (one with a fake `ElevationSource`, say). */
        lateinit var shared: AppModel

        fun init(app: Application) {
            shared = AppModel(app)
        }
    }

    /** The model's own work, for the process lifetime; off-main work uses withContext(IO/Default). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val recorder = LocationRecorder(app)
    val kml = KMLLibrary(app)
    val offline = OfflineManager(app)

    val aim = CrosshairAim(elevation)
    val scale = MapScale()
    val legend = MapLegend()

    private val _track = ArrayList<TrackPoint>()

    /** Read on the main thread only. */
    val track: List<TrackPoint> get() = _track
    private val _trackRevision = MutableStateFlow(0)
    val trackRevision: StateFlow<Int> = _trackRevision.asStateFlow()

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    fun setFollowing(v: Boolean) {
        _following.value = v
    }

    private val _terrainMode = MutableStateFlow(
        TerrainMode.saved(Defaults.string("terrainMode"), Defaults.bool("showContours")),
    )
    val terrainMode: StateFlow<TerrainMode> = _terrainMode.asStateFlow()

    fun setTerrainMode(m: TerrainMode) {
        _terrainMode.value = m
        // M1: write "terrainMode" = m.rawValue; M5: updateDroneReport().
    }

    private val _distanceUnit = MutableStateFlow(
        Defaults.string("distanceUnit")?.let { DistanceUnit(it) } ?: DistanceUnit.metric,
    )
    val distanceUnit: StateFlow<DistanceUnit> = _distanceUnit.asStateFlow()

    fun nextDistanceUnit() {
        // M1: distanceUnit = distanceUnit.next, written to "distanceUnit", and scale.unit.
    }

    private val _heightUnit = MutableStateFlow(
        Defaults.string("heightUnit")?.let { HeightUnit(it) } ?: HeightUnit.metres,
    )
    val heightUnit: StateFlow<HeightUnit> = _heightUnit.asStateFlow()

    fun nextHeightUnit() {
        // M1: heightUnit = heightUnit.next, written to "heightUnit".
    }

    private val _droneReport = MutableStateFlow(DroneReport())
    val droneReport: StateFlow<DroneReport> = _droneReport.asStateFlow()

    private val _measure = MutableStateFlow<MeasureMode>(MeasureMode.Off)
    val measure: StateFlow<MeasureMode> = _measure.asStateFlow()

    private val _markerElevation = MutableStateFlow<Double?>(null)
    val markerElevation: StateFlow<Double?> = _markerElevation.asStateFlow()
    private val _lastElevation = MutableStateFlow<Double?>(null)
    val lastElevation: StateFlow<Double?> = _lastElevation.asStateFlow()
    private val _userElevation = MutableStateFlow<Double?>(null)
    val userElevation: StateFlow<Double?> = _userElevation.asStateFlow()

    /** Placed: from your fix to A; path: along the points; closed: around the shape. */
    val markerDistance: Double?
        get() = null // M3: as Swift (Geo.distance / Geo.pathLength).

    /** The closed shape's area, m². */
    val measuredArea: Double?
        get() = null // M3: Geo.ringArea for closed.

    private val _selectedPoint = MutableStateFlow<GeoPoint?>(null)
    val selectedPoint: StateFlow<GeoPoint?> = _selectedPoint.asStateFlow()

    fun setSelectedPoint(p: GeoPoint?) {
        if (p == _selectedPoint.value) return
        _selectedPoint.value = p
        // M1: selectedName = null, the height lookup (unless a point is moving), updateDroneReport().
    }

    private val _selectedElevation = MutableStateFlow<Double?>(null)
    val selectedElevation: StateFlow<Double?> = _selectedElevation.asStateFlow()
    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName.asStateFlow()

    private val _isDrawingArea = MutableStateFlow(false)
    val isDrawingArea: StateFlow<Boolean> = _isDrawingArea.asStateFlow()
    private val _lassoActive = MutableStateFlow(true)
    val lassoActive: StateFlow<Boolean> = _lassoActive.asStateFlow()

    fun setLassoActive(v: Boolean) {
        _lassoActive.value = v
    }

    private val _draftArea = MutableStateFlow<List<GeoPoint>?>(null)
    val draftArea: StateFlow<List<GeoPoint>?> = _draftArea.asStateFlow()

    fun setDraftArea(r: List<GeoPoint>?) {
        _draftArea.value = r
    }

    private val _pendingArea = MutableStateFlow<PendingArea?>(null)
    val pendingArea: StateFlow<PendingArea?> = _pendingArea.asStateFlow()

    fun setPendingArea(a: PendingArea?) {
        _pendingArea.value = a
    }

    private val _sheet = MutableStateFlow<ActiveSheet?>(null)
    val sheet: StateFlow<ActiveSheet?> = _sheet.asStateFlow()

    fun setSheet(s: ActiveSheet?) {
        _sheet.value = s
    }

    private var mapRef: WeakReference<MapHost>? = null

    /** The phone's map, while it exists (held weakly). */
    var map: MapHost?
        get() = mapRef?.get()
        set(value) {
            mapRef = value?.let { WeakReference(it) }
        }

    private var carMapRef: WeakReference<CarMapHost>? = null

    /** The car's map, while Android Auto shows SatMap (held weakly). */
    var carMap: CarMapHost?
        get() = carMapRef?.get()
        set(value) {
            carMapRef = value?.let { WeakReference(it) }
        }

    init {
        scale.unit = _distanceUnit.value
        // M1: load the track, seed the recorder, wire onTrackPoint/onFix/onHeading, subscribe to Connectivity and
        // offline.completedRevision, start the retry timer when in the foreground.
        // After the recorder and the offline manager, which registered in their own inits: the Swift order.
        AppLifecycle.addObserver(this)
        recorder.start()
    }

    fun pointTapped(p: GeoPoint, name: String?) {
        // M3: selectedPoint = p, then selectedName = name (null when empty).
    }

    fun mapTapped(p: GeoPoint) {
        // M3: off → selectedPoint = p; otherwise addPointAtCrosshair().
    }

    fun movePoint(which: MovablePoint, p: GeoPoint, done: Boolean) {
        // M3: the Swift movePoint (selected or measure(i)), elevation resets when done.
    }

    fun measureButtonTapped() {
        // M3: off → aiming; aiming, placed, path → addPointAtCrosshair(); closed → clearMeasure().
    }

    fun closeMeasureShape() {
        // M3: path with 3 or more points → closed.
    }

    fun undoMeasurePoint() {
        // M3: the Swift undo table, then selectedPoint = measure.points.last.
    }

    /** Go to coordinates: show the spot and measure from it. */
    fun measure(from: GeoPoint) {
        // M3: sheet = null; following = false; setMeasure(placed(p)); selectedPoint = p; zoom or centre.
    }

    fun clearMeasure() {
        // M3: setMeasure(off); selectedPoint = null.
    }

    fun centerOnUser() {
        // M1: zoom onto you at 300 m when following, else follow and centre on you.
    }

    fun clearTrack() {
        // M1: track = []; trackLog.clear(); recorder.seed(null); trackRevision += 1.
    }

    fun startDrawingArea() {
        // M4: sheet = null, draft cleared, following = false, lassoActive = true, isDrawingArea = true.
    }

    fun finishDrawing(ring: List<GeoPoint>) {
        // M4: the drawn loop becomes the draft (fewer than 3 points: none).
    }

    fun cancelDrawing() {
        // M4: isDrawingArea = false, draft cleared.
    }

    fun confirmDrawnArea() {
        // M4: pendingArea = PendingArea("Area <count + 1>", CoverageShape(polygons = [draft])).
    }

    fun discardPendingArea() {
        // M4: pendingArea = null, draft cleared.
    }

    fun startDownload(area: PendingArea, roads: List<List<GeoPoint>>?, preset: String?, maxZoom: Int, allowCellular: Boolean) {
        // M4: offline.add(...), end drawing.
    }

    fun startTrackDownload(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean) {
        // M4: offline.updateTrackArea(...).
    }

    /** Re-reads terrainMode, distanceUnit and heightUnit after `Defaults.setLaunchArguments`, without writing them
     *  back, as iOS reads its argument domain only in init. */
    fun applyLaunchArguments() {
        _terrainMode.value = TerrainMode.saved(Defaults.string("terrainMode"), Defaults.bool("showContours"))
        _distanceUnit.value = Defaults.string("distanceUnit")?.let { DistanceUnit(it) } ?: DistanceUnit.metric
        _heightUnit.value = Defaults.string("heightUnit")?.let { HeightUnit(it) } ?: HeightUnit.metres
        scale.unit = _distanceUnit.value
        // M5: updateDroneReport().
    }

    override fun willEnterForeground() {
        // M1: TileRepository.restoreAfterBackground(), retry timer on, the pending reload.
    }

    override fun didBecomeActive() {
        // M1: the elevation refreshes.
    }

    override fun didEnterBackground() {
        // M1: retry timer off, TileRepository.trimForBackground(), ImageryMemory.enteredBackground(),
        // ContourRenderer.shared.clearCaches().
    }
}
