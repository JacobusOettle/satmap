package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.jacobusoettle.satmap.core.Aviation
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.AviationPoint
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.TrackLog
import com.jacobusoettle.satmap.core.TrackPoint
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.kml.KMLLibrary
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.map.AviationStore
import com.jacobusoettle.satmap.map.ContourRenderer
import com.jacobusoettle.satmap.map.ImageryMemory
import com.jacobusoettle.satmap.map.MovablePoint
import com.jacobusoettle.satmap.map.SatelliteRenderer
import com.jacobusoettle.satmap.map.TerrainKey
import com.jacobusoettle.satmap.map.TileRepository
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.max

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

/** A height asked of the source; a failure counts as none (the Swift lookup can't fail). */
private suspend fun ElevationSource.heightOrNull(p: GeoPoint): Double? = try {
    elevation(p)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** While aiming the measuring tool: the spot under the crosshair and its height, which change as the map moves. Kept
 *  out of `AppModel` so a pan recomposes only the measuring panel, not the whole screen. Main thread only. */
class CrosshairAim(private val source: ElevationSource) {
    private val _point = MutableStateFlow<GeoPoint?>(null)
    val point: StateFlow<GeoPoint?> = _point.asStateFlow()

    /** The height last looked up and the spot it is for: while the map moves, an earlier spot's. */
    private val _elevation = MutableStateFlow<Double?>(null)
    val elevation: StateFlow<Double?> = _elevation.asStateFlow()
    private val _elevationPoint = MutableStateFlow<GeoPoint?>(null)
    val elevationPoint: StateFlow<GeoPoint?> = _elevationPoint.asStateFlow()
    private var lookingUp = false
    private var lookedUp: GeoPoint? = null

    /** Bumped when aiming ends, so a lookup still under way then isn't shown when aiming again. */
    private var session = 0
    private var retries = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The spot under the crosshair (null when not aiming). */
    fun set(p: GeoPoint?) {
        if (p == _point.value) return
        _point.value = p
        retries = 0
        if (p == null) {
            session += 1
            _elevation.value = null
            _elevationPoint.value = null
            lookedUp = null
        }
        lookUp()
    }

    /** The network is back: looks up a height that couldn't be found. */
    fun retry() {
        if (_point.value == null || _elevation.value != null) return
        lookedUp = null
        lookUp()
    }

    /** One lookup at a time, always for the latest spot, so a pan doesn't queue one for every frame. A failed lookup
     *  (no signal) is tried again a few times while the crosshair stays on the spot, 31 s apart: after the elevation
     *  tile's own 30 s wait, which would otherwise answer the same. */
    private fun lookUp() {
        val p = _point.value
        if (lookingUp || p == null || p == lookedUp) return
        lookingUp = true
        lookedUp = p
        val s = session
        scope.launch {
            val e = source.heightOrNull(p)
            lookingUp = false
            if (s == session) {
                _elevation.value = e
                _elevationPoint.value = p
                if (e == null && _point.value == p && retries < 4) {
                    retries += 1
                    scope.launch {
                        delay(31_000)
                        if (s != session || _point.value != p || !AppLifecycle.isActive) return@launch
                        retry()
                    }
                }
            }
            lookUp()
        }
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
            val old = field
            field = value
            if (value != old) choose()
        }
    private var metersPerPoint: Double? = null

    fun update(metersPerPoint: Double) {
        this.metersPerPoint = metersPerPoint
        choose()
    }

    private fun choose() {
        val m = metersPerPoint ?: return
        val s = unit.scale(m, maxWidth) ?: return
        val w = (s.width * 2).rounded() / 2
        if (s.label != _label.value) _label.value = s.label
        if (w != _width.value) _width.value = w
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
 *  for the latest spot, so moving doesn't queue one for every GPS fix. A failed lookup (no signal) is tried again a few
 *  times, 31 s apart (after the elevation tile's own 30 s wait, which would otherwise answer the same), while the spot
 *  stays and the app is on screen. Main thread only. */
class HeightLookup(private val source: ElevationSource, private val found: (GeoPoint, Double?) -> Unit) {
    /** The latest spot asked about. */
    var point: GeoPoint? = null
        private set
    private var lookingUp = false
    private var lookedUp: GeoPoint? = null
    private var retries = 0

    /** Bumped when the spot goes, so a lookup still under way then isn't reported. */
    private var session = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun set(p: GeoPoint?) {
        if (p == point) return
        point = p
        retries = 0
        if (p == null) {
            session += 1
            lookedUp = null
        }
        lookUp()
    }

    /** Looks up the latest spot again (the network is back, or the app was opened). */
    fun retry() {
        if (point == null) return
        lookedUp = null
        lookUp()
    }

    private fun lookUp() {
        val p = point
        if (lookingUp || p == null || p == lookedUp) return
        lookingUp = true
        lookedUp = p
        val s = session
        scope.launch {
            val e = source.heightOrNull(p)
            lookingUp = false
            if (s == session) {
                found(p, e)
                if (e == null && point == p && retries < 4) {
                    retries += 1
                    scope.launch {
                        delay(31_000)
                        if (s != session || point != p || !AppLifecycle.isActive) return@launch
                        retry()
                    }
                }
            }
            lookUp()
        }
    }
}

/** A shape waiting for the user to confirm an offline download. */
data class PendingArea(val id: UUID = UUID.randomUUID(), val name: String, val shape: CoverageShape)

enum class ActiveSheet { blocks, offline, coordinates }

/** App state shared by the map and the controls, for the whole process (not a ViewModel: recording, downloads and
 *  Android Auto run without an activity). Used on the main thread only (background work comes back on the main
 *  thread). A Swift `@Published var x` is `val x: StateFlow` plus `fun setX(v)`, which runs the Swift `didSet`. */
class AppModel(private val app: Application, private val elevation: ElevationSource = TileRepository.shared) :
    AppLifecycle.Observer {

    companion object {
        /** Set by `init` at startup. Tests may install their own (one with a fake `ElevationSource`, say). */
        lateinit var shared: AppModel

        fun init(app: Application) {
            shared = AppModel(app)
        }

        /** Seconds on a monotonic clock, for the reload's 20 s spacing (Swift: `Date`). */
        private fun now(): Double = SystemClock.elapsedRealtime() / 1000.0
    }

    /** The model's own work, for the process lifetime; off-main work uses withContext(IO/Default). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The same, for what the Swift does with `DispatchQueue.main.async(After)`: always a later turn of the main
     *  thread, never in the middle of the caller. */
    private val mainScope = CoroutineScope(scope.coroutineContext + Dispatchers.Main)

    val recorder = LocationRecorder(app)
    val kml = KMLLibrary(app)
    val offline = OfflineManager(app)
    private val trackLog = TrackLog(AppPaths.track)

    /** The recorded track. Changes are announced through `trackRevision` (cheaper than publishing the list). Read on
     *  the main thread only; a reader that hands it to another thread copies it first. */
    val track: List<TrackPoint> get() = _track
    private val _track = ArrayList<TrackPoint>()
    private val _trackRevision = MutableStateFlow(0)
    val trackRevision: StateFlow<Int> = _trackRevision.asStateFlow()

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    fun setFollowing(v: Boolean) {
        _following.value = v
    }

    /** What the terrain button shows over the imagery: contour lines, elevation colours, both or neither (each tap the
     *  next). Remembered between launches. With neither, no elevation tiles are fetched for the map; the measuring
     *  tool and the heights beside the coordinates still look them up. Also reads the legacy "showContours" (a launch
     *  extra "NO" too, as iOS). */
    private val _terrainMode = MutableStateFlow(TerrainMode.saved(Defaults.string("terrainMode"), Defaults.bool("showContours")))
    val terrainMode: StateFlow<TerrainMode> = _terrainMode.asStateFlow()

    fun setTerrainMode(m: TerrainMode) {
        _terrainMode.value = m
        Defaults.putString("terrainMode", m.rawValue)
        updateDroneReport()
    }

    /** Distances in kilometres, miles or nautical miles, heights in metres or feet (a tap on one changes them). */
    private val _distanceUnit = MutableStateFlow(Defaults.string("distanceUnit")?.let { DistanceUnit(it) } ?: DistanceUnit.metric)
    val distanceUnit: StateFlow<DistanceUnit> = _distanceUnit.asStateFlow()

    private fun setDistanceUnit(u: DistanceUnit) {
        _distanceUnit.value = u
        Defaults.putString("distanceUnit", u.rawValue)
        scale.unit = u
    }

    private val _heightUnit = MutableStateFlow(Defaults.string("heightUnit")?.let { HeightUnit(it) } ?: HeightUnit.metres)
    val heightUnit: StateFlow<HeightUnit> = _heightUnit.asStateFlow()

    private fun setHeightUnit(u: HeightUnit) {
        _heightUnit.value = u
        Defaults.putString("heightUnit", u.rawValue)
    }

    /** In drone mode: you, and the tapped spot, from the nearest airfield. */
    private val _droneReport = MutableStateFlow(DroneReport())
    val droneReport: StateFlow<DroneReport> = _droneReport.asStateFlow()

    /** Where drone mode's places come from: `AviationStore.shared`, read when first needed. Tests give their own. */
    @VisibleForTesting
    internal var aviationIndex: () -> AviationIndex? = { AviationStore.shared.index }

    private val _measure = MutableStateFlow<MeasureMode>(MeasureMode.Off)
    val measure: StateFlow<MeasureMode> = _measure.asStateFlow()

    /** Elevation of the first measuring point, of the last one (with two or more), and of where you are (beside your
     *  coordinates, and for measuring from you). */
    private val _markerElevation = MutableStateFlow<Double?>(null)
    val markerElevation: StateFlow<Double?> = _markerElevation.asStateFlow()
    private val _lastElevation = MutableStateFlow<Double?>(null)
    val lastElevation: StateFlow<Double?> = _lastElevation.asStateFlow()
    private val _userElevation = MutableStateFlow<Double?>(null)
    val userElevation: StateFlow<Double?> = _userElevation.asStateFlow()

    /** The spot under the crosshair while aiming. */
    val aim = CrosshairAim(elevation)

    /** The scale bar, and the legends beside it. */
    val scale = MapScale()
    val legend = MapLegend()

    /** The last point tapped on the map (or placed for measuring); its coordinates are shown at the bottom, with its
     *  height (`selectedElevation`). */
    private val _selectedPoint = MutableStateFlow<GeoPoint?>(null)
    val selectedPoint: StateFlow<GeoPoint?> = _selectedPoint.asStateFlow()

    fun setSelectedPoint(p: GeoPoint?) {
        if (p == _selectedPoint.value) return
        _selectedPoint.value = p
        _selectedName.value = null
        // While a point is being moved, its height is looked up once it is let go (`movePoint`).
        if (!pointMoving) lookUpSelectedHeight()
        updateDroneReport()
    }

    private var pointMoving = false
    private val _selectedElevation = MutableStateFlow<Double?>(null)
    val selectedElevation: StateFlow<Double?> = _selectedElevation.asStateFlow()

    /** The name of the KML point selected (a tap on it), shown with its coordinates. */
    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName.asStateFlow()

    /** The selected spot's height, unless it is the one already looked up. */
    private fun lookUpSelectedHeight() {
        if (selectedLookup.point == _selectedPoint.value) return
        _selectedElevation.value = null
        selectedLookup.set(_selectedPoint.value)
    }

    private val selectedLookup = HeightLookup(elevation) { p, e ->
        if (p == _selectedPoint.value) _selectedElevation.value = e
    }

    /** Your height, looked up as you move (while the app is on screen). */
    private val userLookup = HeightLookup(elevation) { _, e ->
        val first = _userElevation.value == null && e != null
        if (e?.rounded() != _userElevation.value?.rounded()) _userElevation.value = e
        // Heights can be had again: those of the measuring points too.
        if (first && _markerElevation.value == null) refreshPointElevations()
    }

    /** Lasso drawing mode for choosing an offline area. */
    private val _isDrawingArea = MutableStateFlow(false)
    val isDrawingArea: StateFlow<Boolean> = _isDrawingArea.asStateFlow()

    /** While drawing: true = finger draws the lasso, false = finger moves the map. */
    private val _lassoActive = MutableStateFlow(true)
    val lassoActive: StateFlow<Boolean> = _lassoActive.asStateFlow()

    fun setLassoActive(v: Boolean) {
        _lassoActive.value = v
    }

    /** The lasso polygon, shown on the map until confirmed or discarded. */
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

    /** Set by the map so the model can read the centre of the screen and move the camera (held weakly). */
    var map: MapHost?
        get() = mapRef?.get()
        set(value) {
            mapRef = value?.let { WeakReference(it) }
        }

    private var carMapRef: WeakReference<CarMapHost>? = null

    /** The map on the car's screen while Android Auto shows SatMap (held weakly). */
    var carMap: CarMapHost?
        get() = carMapRef?.get()
        set(value) {
            carMapRef = value?.let { WeakReference(it) }
        }

    private var retryJob: Job? = null
    private var reloadPending = false
    private var lastReload = Double.NEGATIVE_INFINITY

    init {
        scale.unit = _distanceUnit.value
        _track.addAll(trackLog.loadAll())
        recorder.seed(_track.lastOrNull())
        recorder.onTrackPoint = { p -> appendTrack(p) }
        recorder.onFix = { loc -> locationUpdated(loc) }
        recorder.onHeading = { h -> map?.headingUpdated(h) }

        // For the whole app, whichever maps there are (the phone's, the car's, or both): memory trimmed in the
        // background, and tiles that failed asked for again. After the recorder and the offline manager, which
        // registered in their own inits: the Swift order.
        AppLifecycle.addObserver(this)

        // MapLibre isn't told of a network that came back. When it does, after a temporary failure, or when an
        // offline area finishes, reload the tiles (debounced, in the foreground only). As the Swift sinks: every
        // value is seen as it is set (collected at once from here, so a quick offline → online isn't merged into
        // one value), and what is done hops to a later turn of the main thread (DispatchQueue.main.async).
        Connectivity.shared.state
            .map { it.isOnline }
            .distinctUntilChanged()
            .drop(1)
            .filter { it }
            .onEach {
                mainScope.launch {
                    TileRepository.shared.resetFailureState()
                    requestReload()
                }
            }
            .launchIn(scope)
        offline.completedRevision
            .drop(1)
            .onEach { mainScope.launch { requestReload() } }
            .launchIn(scope)
        // Only in the foreground (iOS also starts it when launched into the background, where it runs until the
        // next didEnterBackground): it starts with each willEnterForeground.
        if (AppLifecycle.isForeground) startRetryTimer()
        recorder.start()
    }

    // Reloading tiles

    /** Every 30 s in the foreground: reload tiles if some failed for temporary reasons. */
    private fun startRetryTimer() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (true) {
                delay(30_000)
                val recovered = withContext(Dispatchers.IO) { TileRepository.shared.probeRecovery() }
                if (recovered) requestReload()
            }
        }
    }

    /** Tiles may be had again: the maps ask again for those that failed (at most every 20 s). */
    @VisibleForTesting
    internal fun requestReload() {
        reloadPending = true
        if (!AppLifecycle.isForeground) return // done on return to the foreground
        val wait = max(0.0, 20 - (now() - lastReload))
        mainScope.launch {
            delay((wait * 1000).toLong())
            if (!reloadPending || !AppLifecycle.isForeground) return@launch
            reloadPending = false
            lastReload = now()
            SatelliteRenderer.reload()
            map?.reloadImagery()
            carMap?.reload()
            aim.retry()
            if (_userElevation.value == null) userLookup.retry()
            if (_selectedElevation.value == null) selectedLookup.retry()
        }
    }

    // Units

    fun nextDistanceUnit() {
        setDistanceUnit(_distanceUnit.value.next)
    }

    fun nextHeightUnit() {
        setHeightUnit(_heightUnit.value.next)
    }

    // Drone mode

    /** The reporting lines for you and the tapped spot, from the nearest airfield with a code (not a helipad) within
     *  150 NM, else the nearest airfield at all. */
    private fun updateDroneReport() {
        if (!_terrainMode.value.showsAviation) {
            if (_droneReport.value != DroneReport()) _droneReport.value = DroneReport()
            return
        }
        val index = aviationIndex()
        if (index == null) {
            AviationStore.shared.load(app) { updateDroneReport() }
            return
        }
        fun report(p: GeoPoint?): String? {
            p ?: return null
            val limit = 150 * Aviation.metresPerNauticalMile
            val found = index.nearest(p, limit) {
                it.kind.isAerodrome && it.kind != AviationPoint.Kind.heliport && it.code.isNotEmpty()
            } ?: index.nearest(p, limit)
            return found?.let { Aviation.report(p, index.points[it.index]) }
        }
        val new = DroneReport(you = report(recorder.location.value?.point), pin = report(_selectedPoint.value))
        if (new != _droneReport.value) _droneReport.value = new
    }

    // Track

    private fun appendTrack(p: TrackPoint) {
        _track.add(p)
        trackLog.append(p)
        _trackRevision.value += 1
    }

    fun clearTrack() {
        _track.clear()
        trackLog.clear()
        recorder.seed(null)
        _trackRevision.value += 1
    }

    // Location

    private fun locationUpdated(loc: GpsFix) {
        map?.locationUpdated(loc)
        carMap?.locationUpdated(loc)
        updateDroneReport()
        // Only for the phone's screen (with Android Auto the app stays foreground while the phone is locked).
        if (AppLifecycle.isActive && map?.isOnScreen == true) refreshUserElevation(loc)
    }

    /** My location: centres the map on you and follows you. Tapped again while it does (also a quick second tap), it
     *  zooms in until the screen is 300 m across. */
    fun centerOnUser() {
        val loc = recorder.location.value
        if (_following.value && loc != null) {
            map?.zoom(onto = loc.point, metersAcross = 300.0)
            return
        }
        _following.value = true
        if (loc != null) map?.center(on = loc.point, animated = true)
    }

    // Measuring

    /** The measure button: shows the crosshair, then places a point at it each time (A, B, C …); after a shape is
     *  closed it clears. */
    fun measureButtonTapped() {
        when (_measure.value) {
            MeasureMode.Off -> setMeasure(MeasureMode.Aiming)
            MeasureMode.Aiming, is MeasureMode.Placed, is MeasureMode.Path -> addPointAtCrosshair()
            is MeasureMode.Closed -> clearMeasure()
        }
    }

    /** A tap on the map: shows the spot's coordinates; while measuring, places a point at the crosshair wherever the
     *  tap was (a finger covers the spot it taps). */
    fun mapTapped(p: GeoPoint) {
        if (_measure.value == MeasureMode.Off) {
            setSelectedPoint(p)
        } else {
            addPointAtCrosshair()
        }
    }

    /** A tap on a point in a KML file (a launch point …): selected at its own position, with its name. */
    fun pointTapped(p: GeoPoint, name: String?) {
        setSelectedPoint(p)
        _selectedName.value = if (name.isNullOrEmpty()) null else name
    }

    /** A point being moved precisely (pressed and held): the tapped spot, or a measuring point (its distances and area
     *  follow; its coordinates and height show at the bottom). Heights are looked up again once it is let go
     *  (`done`). */
    fun movePoint(which: MovablePoint, p: GeoPoint, done: Boolean) {
        when (which) {
            MovablePoint.Selected -> {
                pointMoving = !done
                setSelectedPoint(p)
                if (done) lookUpSelectedHeight()
            }
            is MovablePoint.Measure -> {
                val i = which.i
                val count = _measure.value.points.size
                if (i >= count) return
                pointMoving = !done
                _measure.value = _measure.value.moving(i, p)
                setSelectedPoint(p)
                if (!done) return
                lookUpSelectedHeight()
                if (i == 0) _markerElevation.value = null
                if (count >= 2 && i == count - 1) _lastElevation.value = null
                refreshPointElevations()
            }
        }
    }

    private fun addPointAtCrosshair() {
        val c = map?.centerCoordinate ?: return
        addMeasurePoint(c)
    }

    private fun addMeasurePoint(p: GeoPoint) {
        val m = _measure.value
        // With the crosshair on A (three or more points): the shape closes.
        if (m is MeasureMode.Path && m.pts.size >= 3) {
            val apart = map?.pointsApart(p, m.pts[0])
            if (apart != null && apart < 20) return closeMeasureShape()
        }
        // The crosshair still on the last point (another tap without moving the map): no leg of 0 m.
        if (m is MeasureMode.Placed || m is MeasureMode.Path) {
            val last = m.points.lastOrNull()
            val apart = last?.let { map?.pointsApart(p, it) }
            if (apart != null && apart < 6) return
        }
        setSelectedPoint(p)
        when (m) {
            is MeasureMode.Placed -> setMeasure(MeasureMode.Path(listOf(m.a, p)))
            is MeasureMode.Path -> setMeasure(MeasureMode.Path(m.pts + p))
            // The first point, or a new start after a closed shape.
            else -> setMeasure(MeasureMode.Placed(p))
        }
    }

    /** Joins the last point back to the first (tap on A, or "Close shape"): the area. */
    fun closeMeasureShape() {
        val m = _measure.value
        if (m !is MeasureMode.Path || m.pts.size < 3) return
        setMeasure(MeasureMode.Closed(m.pts))
    }

    /** Removes the last point (or reopens a closed shape). */
    fun undoMeasurePoint() {
        when (val m = _measure.value) {
            is MeasureMode.Closed -> setMeasure(MeasureMode.Path(m.pts))
            is MeasureMode.Path -> if (m.pts.size > 2) {
                setMeasure(MeasureMode.Path(m.pts.dropLast(1)))
            } else {
                setMeasure(MeasureMode.Placed(m.pts[0]))
            }
            is MeasureMode.Placed -> setMeasure(MeasureMode.Aiming)
            MeasureMode.Off, MeasureMode.Aiming -> return
        }
        setSelectedPoint(_measure.value.points.lastOrNull())
    }

    private fun setMeasure(m: MeasureMode) {
        val old = _measure.value.points
        _measure.value = m
        // Faster GPS checks while asleep only matter when the distance is from you.
        recorder.measuring = measureIsFromYou
        if (m != MeasureMode.Aiming) aim.set(null)
        val new = m.points
        if (new.firstOrNull() != old.firstOrNull()) _markerElevation.value = null
        if (new.size < 2 || new.lastOrNull() != old.lastOrNull() || old.size < 2) _lastElevation.value = null
        refreshPointElevations()
        val loc = recorder.location.value
        if (measureIsFromYou && loc != null) refreshUserElevation(loc)
    }

    /** Aiming (to the crosshair) or one point: the distance and height are from you. */
    private val measureIsFromYou: Boolean
        get() = when (_measure.value) {
            MeasureMode.Aiming, is MeasureMode.Placed -> true
            MeasureMode.Off, is MeasureMode.Path, is MeasureMode.Closed -> false
        }

    /** Distance from you to the point, or the total length of the line through the points (around the shape when
     *  it is closed). `Geo.distance` where the Swift uses `CLLocation`'s distance (Apple's algorithm is unspecified;
     *  they differ by under 0.5 %). */
    val markerDistance: Double?
        get() = when (val m = _measure.value) {
            is MeasureMode.Placed -> recorder.location.value?.let { Geo.distance(it.point, m.a) }
            is MeasureMode.Path -> Geo.pathLength(m.pts)
            is MeasureMode.Closed -> Geo.pathLength(m.pts + m.pts[0])
            MeasureMode.Off, MeasureMode.Aiming -> null
        }

    /** The closed shape's area in square metres. */
    val measuredArea: Double?
        get() = (_measure.value as? MeasureMode.Closed)?.let { Geo.ringArea(it.pts) }

    /** Looks up the elevations of the first and last measuring points that aren't known yet (not for a closed
     *  shape). A failed lookup (no signal) is tried again a few times, 15 s apart, while the point stays and the app
     *  is on screen. */
    private fun refreshPointElevations(attempt: Int = 0) {
        val m = _measure.value
        if (m is MeasureMode.Closed) return
        val points = m.points
        val first = points.firstOrNull() ?: return
        val wanted = ArrayList<Pair<Boolean, GeoPoint>>()
        if (_markerElevation.value == null) wanted.add(true to first)
        if (points.size >= 2 && _lastElevation.value == null) wanted.add(false to points.last())
        for ((isFirst, p) in wanted) {
            scope.launch {
                val e = elevation.heightOrNull(p)
                val now = _measure.value.points
                val still = if (isFirst) now.firstOrNull() else if (now.size >= 2) now.last() else null
                if (still != p) return@launch
                if (isFirst) _markerElevation.value = e else _lastElevation.value = e
                if (e == null && attempt < 4) {
                    scope.launch {
                        delay(15_000)
                        if (!AppLifecycle.isActive) return@launch
                        refreshPointElevations(attempt + 1)
                    }
                }
            }
        }
    }

    /** A position typed or pasted in: measuring starts from it (point A, with the live distance from you), and the map
     *  shows it, with you too if you are within 50 km. */
    fun measure(from: GeoPoint) {
        val p = from
        setSheet(null)
        setFollowing(false)
        setMeasure(MeasureMode.Placed(p))
        setSelectedPoint(p)
        recorder.location.value?.let { loc ->
            val me = loc.point
            val b = GeoBounds(points = listOf(me, p))
            if (Geo.distance(me, p) < 50_000 && b != null) {
                map?.zoom(to = b)
                return
            }
        }
        map?.center(on = p, animated = true)
    }

    /** The ✕ on the measuring panel. */
    fun clearMeasure() {
        setMeasure(MeasureMode.Off)
        setSelectedPoint(null)
    }

    private fun refreshUserElevation(loc: GpsFix) {
        userLookup.set(loc.point)
    }

    // Offline areas

    fun startDrawingArea() {
        setSheet(null)
        setDraftArea(null)
        setFollowing(false)
        setLassoActive(true)
        _isDrawingArea.value = true
    }

    fun finishDrawing(ring: List<GeoPoint>) {
        setDraftArea(if (ring.size >= 3) ring else null)
    }

    fun cancelDrawing() {
        _isDrawingArea.value = false
        setDraftArea(null)
    }

    fun confirmDrawnArea() {
        val ring = _draftArea.value ?: return
        _isDrawingArea.value = false
        setPendingArea(PendingArea(name = "Area ${offline.areas.value.size + 1}", shape = CoverageShape(polygons = listOf(ring))))
    }

    /** Offline area around every block, line and point in a KML file (with a 150 m margin). */
    fun prepareArea(from: KMLLibrary.File) {
        setSheet(null)
        setPendingArea(PendingArea(name = from.name, shape = from.document.coverageShape(bufferMeters = 150.0)))
    }

    /** `roads` or `preset`: download satellite imagery only along these roads (or a road set shipped with the app). */
    fun startDownload(area: PendingArea, roads: List<List<GeoPoint>>?, preset: String? = null, maxZoom: Int, allowCellular: Boolean) {
        offline.add(name = area.name, shape = area.shape, roads = roads, preset = preset, maxZoom = maxZoom, allowCellular = allowCellular)
        setPendingArea(null)
        setDraftArea(null)
    }

    /** "Where I've been": imagery along the recorded track, added to (or creating) the track area. */
    fun startTrackDownload(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean) {
        offline.updateTrackArea(name = name, lines = lines, maxZoom = maxZoom, allowCellular = allowCellular)
    }

    fun discardPendingArea() {
        setPendingArea(null)
        setDraftArea(null)
    }

    /** Re-reads terrainMode, distanceUnit and heightUnit after `Defaults.setLaunchArguments`, without writing them
     *  back, as iOS reads its argument domain only in init. */
    fun applyLaunchArguments() {
        _terrainMode.value = TerrainMode.saved(Defaults.string("terrainMode"), Defaults.bool("showContours"))
        _distanceUnit.value = Defaults.string("distanceUnit")?.let { DistanceUnit(it) } ?: DistanceUnit.metric
        _heightUnit.value = Defaults.string("heightUnit")?.let { HeightUnit(it) } ?: HeightUnit.metres
        scale.unit = _distanceUnit.value
        updateDroneReport()
    }

    // App state

    override fun willEnterForeground() {
        TileRepository.shared.restoreAfterBackground()
        startRetryTimer()
        if (reloadPending) requestReload()
    }

    /** Elevations aren't looked up in the background; catch up when the app is opened. */
    override fun didBecomeActive() {
        if (_markerElevation.value == null || _lastElevation.value == null) refreshPointElevations()
        recorder.location.value?.let { refreshUserElevation(it) }
        if (_selectedElevation.value == null) selectedLookup.retry()
    }

    override fun didEnterBackground() {
        retryJob?.cancel()
        retryJob = null
        TileRepository.shared.trimForBackground()
        ImageryMemory.enteredBackground()
        ContourRenderer.shared.clearCaches()
    }
}
