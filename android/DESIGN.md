# SatMap for Android: app design

This is the design of the Android app layer (`android/app`), the part above the ported core (`android/core`). It
tells a team of engineers who build the app in parallel which files exist, who owns them, how they talk to each
other, and how the work is ordered and tested.

**Sources of truth**, in this order:

1. **The Swift code.** It is the truth for behaviour: numbers, strings, timings, colours and the order of
   operations. Port it file for file, as `PORTING.md` says. This document only repeats Swift where Android
   needs a decision.
2. **This document.** It is the truth for structure: classes, threads, the MapLibre style, services, the
   manifest and the tests.
3. **The five subsystem specs** in `docs/spec/` (`rendering.md`, `interaction.md`, `state.md`, `ui.md`,
   `offline.md`; "rendering spec §2.4" means `docs/spec/rendering.md` §2.4). They hold the detailed Android
   mapping. Where they disagree with each other, or with this document, §0 decides.

---

## 0. Decisions that override the subsystem specs

| # | Topic | Decision |
| --- | --- | --- |
| D1 | MapLibre artifact | Use **`org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1`**, the multi-backend build: it draws with Vulkan where the phone has Vulkan 1.0.3 (`RenderingEngine.getDefaultRenderingEngine`) and with OpenGL ES otherwise, and its manifest declares `android.hardware.vulkan.version` with `required="false"`. Not the plain `android-sdk` artifact, the Vulkan-only build, whose manifest has `required="true"`, which shuts out older phones. Not `android-sdk-opengl` either: on the CI emulator (SwiftShader) its OpenGL ES renderer draws no icon symbols and no dashed lines, so your dot, the track, the measuring line and markers, the pin and the KML names and points were missing, while circles, plain lines, fills, patterns and rasters drew (every OpenGL release from 12.3.1 to 13.7.1, also on a bare `MapView` with an empty style; the Vulkan renderer draws them all). The public API is the same (`MapView`, `MapLibreMapOptions`, `RasterSource` …); only the renderer internals differ. The cost: both native renderers are in the APK. |
| D2 | Tile URLs | Use the rendering spec's scheme with a generation and the drawing parameters in the path (§3.2). The car map uses the same scheme through its own `DrawnImagery`. The car section's `satmap.local/<layer>/{z}/{x}/{y}` with tileSize 256 is dropped. |
| D3 | Foreground/background | Everything uses **`AppLifecycle`** (§2.3), which merges the phone's activities with the Android Auto session. Nothing else observes `ProcessLifecycleOwner` directly. |
| D4 | Writing state | No public `MutableStateFlow` anywhere. A Swift `@Published var x` becomes `val x: StateFlow<T>` plus `fun setX(v)`, which runs the Swift `didSet`. `model.sheet.value = …` and `model.selectedPoint = …` are not used. |
| D5 | Enum naming | App enums keep the Swift case names in lower camel case, as core does (`TerrainMode.contours`): `ActiveSheet.blocks`, `LocationRecorder.Power.sleeping`, `SatelliteRenderer.Content.imagery`. Sealed subtypes are capitalised: `MeasureMode.Placed(a)`, `MovablePoint.Measure(i)`. |
| D6 | A location fix | One immutable type, **`GpsFix`** (§5.1), everywhere: recorder, `AppModel`, map, car, UI. Its `altitude` is already height above sea level. `android.location.Location` never leaves `LocationRecorder`. |
| D7 | Loupe imagery | `SatelliteRenderer.drawImagery(...)`, as in Swift. The interaction spec's `LoupeImagery` helper is dropped. `PointAdjuster` keeps its own `DrawnMap` and redraws on `onArrival(_, imagery)`. |
| D8 | Export | Zero-copy sharing through `BackupFileProvider` (offline spec §2.8). The UI spec's copy into `cache/Export` is dropped, because copying 15 GB areas is not acceptable. `ui/ShareSheet.kt` is owned by the offline package (WP-E). |
| D9 | List look and icons | The UI spec's "Form" (§4.3) and its icon table (§4.4) win over the offline spec's §2.10 (`labelMedium` headers, `Gesture`/`SelectAll`/`Replay` icons). |
| D10 | Permissions | At launch: location, then motion (`ACTIVITY_RECOGNITION`), as iOS asks for location and motion. **Notifications** are asked once, on the first tap of a Download button (offline spec). `PermissionFlow` does not ask for them. |
| D11 | Location hints | Three texts (state spec): denied, location services off, approximate only. A tap on the hint opens the right Settings page (an Android addition). See §4.6. |
| D12 | Texts the specs disagree on | `backgroundStatus`: the offline spec's three texts (§5.2). Backup footer: "Saves each area as two MBTiles files, satellite and elevation, to Google Drive, a folder or a USB drive, or send them to a computer. To restore, choose both files of each area. Long-press an area to export only that one." |
| D13 | KML picker types | Use the offline spec's MIME list (kml, kmz, xml, zip, octet-stream). Restore uses `*/*`. |
| D14 | `HTTPTileFetcher` | Lives in `map/TileRepository.kt` (as in Swift) with signature `HTTPTileFetcher(client, url, wifiOnly = false)`. The download clients are in `offline/DownloadHttp.kt`. |
| D15 | Memory trims | `onTrimMemory` trims when `level == RUNNING_LOW`, `level == RUNNING_CRITICAL` or `level >= TRIM_MEMORY_BACKGROUND`, and `onLowMemory` always trims. "Entering the background" is `AppLifecycle.didEnterBackground`. Details in §3.14. |
| D16 | Free space | `StorageManager.getAllocatableBytes(getUuidForPath(root))` (iOS "important usage" counts purgeable space too). Fall back to `StatFs.availableBytes` when it throws. |
| D17 | SQLite on Android | `AndroidSqlDriver` opens writers and map readers with `OPEN_READWRITE or NO_LOCALIZED_COLLATORS or ENABLE_WRITE_AHEAD_LOGGING`, plus `CREATE_IF_NECESSARY` when creating. Without the WAL flag, Android may reset the file's journal mode to its own default on open: always on API 26–27, and on later versions depending on its compatibility-WAL setting. The map could then no longer read while a download writes. One-off readers (`savedImageryDepth`) use `OPEN_READONLY or NO_LOCALIZED_COLLATORS`, through `AndroidSqlDriver(readOnly = true)`: core's `SqlDriver.open` has no read-only option, and `MBTiles.driver` is the read-write one. PRAGMAs that return rows (`wal_checkpoint`, `journal_mode`) go through `rawQuery`. |
| D18 | Map hooks | `MapController` (and `CarMapController`) registers the only camera listeners and calls `DrawnImagery`'s hooks itself. `DrawnImagery` registers no listeners. |
| D19 | Network errors in map tiles | The interceptor never fails a request with an exception, except when MapLibre cancels it. Every outcome is a 200 or a 204 with `Cache-Control`. `MapLibre.setConnected(true)` is set once at startup and never toggled. |
| D20 | Car map surface | A `Presentation` on a `VirtualDisplay` with a `MapView` in `textureMode(true)` (offline spec §2.12). |
| D21 | WorkManager | It is not used: downloads run in a foreground service. Remove the dependency in M0. |

---

## 1. Packages and files

All app code is in `android/app/src/main/kotlin/com/jacobusoettle/satmap/`. The **WP** column says which work
package owns the file (§7). Only the owner edits a file.

### 1.1 Root and `app/`: process, state, configuration

| File | iOS counterpart | Contents and responsibility | WP |
| --- | --- | --- | --- |
| `MainActivity.kt` | `SatMapApp.swift` (`WindowGroup`, `onOpenURL`) | Edge-to-edge dark Compose host. It creates `MapController` and `PermissionFlow`, handles incoming KML intents, `ACTION_SAVE_TO_FOLDER` and launch extras, and forwards `onSaveInstanceState`. | A |
| `app/SatMapApp.kt` | `AppDelegate` | `SatMapApplication`: the init order (§2.2) and memory trims. | A |
| `app/AppModel.kt` | `AppModel.swift` | `MeasureMode`, `CrosshairAim`, `MapScale`, `DroneReport`, `MapLegend`, `HeightLookup`, `PendingArea`, `ActiveSheet`, `AppModel`, plus the seams `MapHost`, `CarMapHost` and `ElevationSource` (§2.5). | A |
| `app/AppPaths.kt` | `AppPaths.swift` | `AppPaths` (folders, `size`, `freeDiskSpace`) and `Format` (all formatting through core `stringFormat`; `bytes` = ByteCountFormatter `.file`). | A |
| `app/Config.kt` | `Config.swift` | `TileSources` and `LocationPolicy`, constants verbatim. | A |
| `app/Connectivity.kt` | `Connectivity.swift` | `Connectivity` (§5.8). | A |
| `app/AppLifecycle.kt` | `UIApplication` state | `AppLifecycle`: process state, phone and car merged (§2.3). | A |
| `app/Defaults.kt` | `UserDefaults` | `Defaults`: SharedPreferences and launch arguments (§5.7). | A |
| `app/PermissionFlow.kt` | system prompts | `PermissionFlow`: location, then motion (§5.3). | A |

### 1.2 `location/`

| File | iOS counterpart | Contents | WP |
| --- | --- | --- | --- |
| `LocationRecorder.kt` | `LocationRecorder.swift` | `LocationRecorder`, `GpsFix`. The power policy is ported line for line, on `LocationManager` (§5.1). | A |
| `LocationService.kt` | `UIBackgroundModes: location` | Foreground service of type `location`, with a "Recording your track" notification and a Stop action. | A |
| `WakeTimer.kt` | `Timer` | `AlarmManager` listener alarms on `ELAPSED_REALTIME_WAKEUP`. | A |

### 1.3 `map/`

| File | iOS counterpart | Contents | WP |
| --- | --- | --- | --- |
| `TileRepository.kt` | `TileRepository.swift` | `TileRepository` (memory → offline MBTiles → network, DEM, `elevation`, pause/probe), `HTTPTileFetcher`, `ImageTools`, `Work`, `RequestPriority`, `RequestLimiter`, `LRUCache`. | B |
| `TileInterceptor.kt` | — (Android only) | `TileUrls`, `TileLevels`, `TileRoute`, `TileAnswer`, `TileLayer`, `TileLayers`, `TileInterceptor`, plus `mapClient` and `browseClient`. | B |
| `SatelliteLayer.kt` | `SatelliteLayer.swift` | `ImageryMemory`, `DrawnMap`, `SatelliteRenderer` (per-tile answers, gates, loupe drawing, `heightExtremes`), `ImageryPrefetch`. | B |
| `DrawnImagery.kt` | `DrawnImagery.swift` | `TerrainKey`, `ContourInterval`, `DrawnImagery`: terrain sources and layers, levels, focus, colour range, layer replacement, legend key. | B |
| `TileOverlays.kt` | `TileOverlays.swift` | `ContourRenderer`, `HeightGrids`, `colourPng`. Drawing uses `android.graphics`. | B |
| `MapStyle.kt` | — (Android only) | `MapIds` (every source and layer id) and `MapStyle`: the base style builder, every fixed overlay source and layer in stack order, and the per-file KML layer definitions (§3.8). | C |
| `MapController.kt` | `MapController.swift` | `MapController` (implements `MapHost`), `MapContainerView` (touch routing, press detector), `AimLineView`, `@Composable MapViewRepresentable`. | C |
| `MapAnnotations.kt` | `MapAnnotations.swift` | `MapImages` (every style bitmap), `MapColours`, `UserDotLayer`, `MeasureMarkerLayer`, `SelectedPointLayer`, `DraftAreaLayer`, `LassoView`, `KMLColor.argb()` / `.rgba()`. | C |
| `DynamicOverlays.kt` | `DynamicOverlays.swift` | `TrackLayer` (finished and live parts), `MeasureLineLayer`. | C |
| `BlockLayers.kt` | `BlockLayers.swift` | `BlockLayers`: per-file sources and layers; `shapes()` and `points` for the tap test. | C |
| `AviationLayer.kt` | `AviationLayer.swift` | `AviationStore` (reads `Aviation.tsv`), `AviationLayer`, `AviationCard` (card bitmaps). | C |
| `PointAdjuster.kt` | `PointAdjuster.swift` | `MovablePoint`, `PointAdjuster`, `LoupeFrame`, `LoupeView`. | C |
| `ZoomScript.kt` | `ZoomScript.swift` | `ZoomScript`, `Watchdog`. | C |

### 1.4 `offline/`, `kml/`

| File | iOS counterpart | Contents | WP |
| --- | --- | --- | --- |
| `offline/OfflineManager.kt` | `OfflineManager.swift` | The area list, the queue, runs, backup export and restore. Main thread only. | E |
| `offline/BackgroundDownload.kt` | `BackgroundDownload.swift` | The same API as Swift, backed by `OfflineDownloadService`. Owns the progress notification and `status`. | E |
| `offline/OfflineDownloadService.kt` | `BGContinuedProcessingTask` | Foreground service of type `dataSync`, with a wake lock, Stop and `onTimeout`. | E |
| `offline/DownloadHttp.kt` | `OfflineManager.makeSession` | The OkHttp clients for downloads (any network or Wi-Fi only) and for roads and places. | E |
| `offline/RoadFetcher.kt` | `RoadFetcher.swift` | `RoadPresets`, `RoadFetcher` (Overpass with staggered servers). | E |
| `offline/PlaceSearch.kt` | `PlaceSearch.swift` | `PlaceSearch` (Photon, Nominatim). | E |
| `offline/BackupFileProvider.kt` | `exportFolder` | `BackupFileProvider` (a `FileProvider` subclass, read-only, display names) and `ExportShares` (open-reader registry). | E |
| `offline/BackupSharing.kt` | share items | The `ACTION_SEND_MULTIPLE` chooser intent, the "Save to a folder" action and its copy into a SAF tree. | E |
| `offline/SaveToFolderActivity.kt` | — | Chooser entry for API 26–33. It forwards to `MainActivity` with `ACTION_SAVE_TO_FOLDER`. | E |
| `offline/AndroidSqlDriver.kt` | SQLite C API | Implements core's `SqlDriver` on `android.database.sqlite`, with the flags in D17. Used by the downloader and by `TileRepository`. | E |
| `kml/KMLLibrary.kt` | `KMLLibrary.swift` | `KMLLibrary` (nested `Entry` and `File`; write `java.io.File` in full in this file). | E |

### 1.5 `ui/`

| File | iOS counterpart | Contents | WP |
| --- | --- | --- | --- |
| `Theme.kt` | (SwiftUI defaults) | Colour tokens, `SatTypography`, `SatMapTheme`, `Modifier.blocksMap()`, pill and panel modifiers. | D |
| `Form.kt` | (inset-grouped `List`) | `FormSection`, `FormRow`, link, action, value, toggle, text-field and swipe-to-delete rows. | D |
| `SheetHost.kt` | (`.sheet`, `NavigationStack`) | `SheetHost`, `SheetTopBar`, `ScreenChrome`, the route stack and back handling. | D |
| `Icons.kt` | SF Symbols | The custom `ImageVector`s: `SquareDashed`, `LocationMagnifyingGlass`, `MapPin`, `MapPinCircleFill`, `Lasso`, plus `RetryCircle`. | D |
| `MapScreen.kt` | `MapScreen.swift` | `MapScreen` and every view of `MapScreen.swift` under the same names (UI spec §0). | D |
| `OfflineSheet.kt` | `OfflineSheet.swift` | `OfflineSheet`, `AreaRow`, `PlaceSearchView`, `PlaceAreaView`, `PresetAreaView`, `TrackAreaView`, `KMLAreaPicker`, plus the route state holders. | D |
| `NewAreaView.kt` | `NewAreaView.swift` | `NewAreaView`, `TrackDownload`, `NewAreaState`. | D |
| `AreaDetailView.kt`, `BlocksSheet.kt`, `CoordinateEntryView.kt` | same names | The screens. | D |
| `ShareSheet.kt` | `ShareSheet.swift` | `SharePresenter` (request flow), `ShareRequest`, `@Composable ShareHost()` (the chooser and folder-picker launchers). | E |

### 1.6 `car/`

| File | iOS counterpart | Contents | WP |
| --- | --- | --- | --- |
| `CarSceneDelegate.kt` | `CarPlaySceneDelegate.swift` | `SatMapCarAppService`, `CarSession`, `CarMapScreen` (the `NavigationTemplate`). | B |
| `CarMapController.kt` | `CarPlayMapController.swift` | `CarMapController` (implements `SurfaceCallback` and `CarMapHost`): a `VirtualDisplay` + `Presentation` + `MapView` drawing the same style. | B |

### 1.7 Tests and resources

- `app/src/test/kotlin/…`: Robolectric and JVM tests, mirroring the main packages (§8.1).
  - Plus `TestApplication.kt`: a bare `Application` that initialises nothing (not even `MBTiles.driver`, §2.2).
  - Plus `src/test/resources/robolectric.properties` with `application=com.jacobusoettle.satmap.TestApplication` and `sdk=35`, so MapLibre's native code is never loaded under Robolectric.
- `app/src/androidTest/kotlin/com/jacobusoettle/satmap/`: `ScreenshotTest.kt`, `ZoomTest.kt`, `TestLocations.kt`, `Gestures.kt` (§8.2).
- `app/src/main/res/`: listed in §5.5.

---

## 2. State and threading

### 2.1 Who owns what

| Object | Lifetime | Created by | Thread |
| --- | --- | --- | --- |
| `AppModel.shared`, and in it `recorder`, `kml`, `offline`, `aim`, `scale`, `legend` | the process | `SatMapApplication.onCreate` | main |
| `TileRepository.shared`, `ImageryMemory`, `SatelliteRenderer`, `ContourRenderer.shared`, `HeightGrids.shared`, `TileLayers` | the process | `SatMapApplication.onCreate`, or first use | thread-safe (locks) |
| `Connectivity.shared`, `AppLifecycle`, `Defaults`, `AppPaths` | the process | `SatMapApplication.onCreate` | main (writes); snapshots readable anywhere |
| `AviationStore.shared` | the process | first use | loads on `Dispatchers.IO`, calls back on main |
| `SharePresenter`, `ExportShares` | the process | object | main |
| `MapController` (with its `MapView`, `DrawnImagery`, overlays, `PointAdjuster`) | one `MainActivity` instance | `MainActivity.onCreate` | main |
| `PermissionFlow` | one `MainActivity` | `MainActivity.onCreate` | main |
| `CarMapController` (with its own `DrawnImagery` and overlays) | one car `Session` screen | `CarMapScreen` | main |
| `LocationService`, `OfflineDownloadService` | while recording / downloading | `startForegroundService` | main |

`AppModel` is a plain singleton, not a `ViewModel`. Recording, downloads and Android Auto all run without an
activity.

### 2.2 Startup order (`SatMapApplication.onCreate`)

1. `Defaults.init(this)`, `AppPaths.init(this)`, `Connectivity.init(this)`, `AppLifecycle.init()`,
   `MBTiles.driver = AndroidSqlDriver()`.
   - `ProcessLifecycleOwner` is already initialised by App Startup, so `AppLifecycle.init()` can observe it here.
   - `MBTiles.driver` is the read-write mode of D17. It is set before anything opens a tile store: `TileRepository`'s
     offline readers, `OfflineManager`'s downloads, and core `OfflineBackup`'s export and restore. `TestApplication`
     sets nothing, so a Robolectric test that opens a store sets `MBTiles.driver` itself.
2. `TileRepository.init(this)`. This creates `browseClient` and the limiters.
3. MapLibre:
   - `MapLibre.getInstance(this)`
   - `MapLibre.setConnected(true)`
   - `HttpRequestUtil.setOkHttpClient(TileInterceptor.mapClient)`
   - `HttpRequestUtil.setLogEnabled(false)`
   - `HttpRequestUtil.setPrintRequestUrlOnFailure(false)`
4. `AppModel.init(this)`. In the Swift order, this creates `LocationRecorder`, `KMLLibrary` and `OfflineManager`. `OfflineManager` loads `areas.json` and publishes the areas to `TileRepository`. Then the track is loaded, the recorder seeded, the observers registered and the subscriptions made, and `recorder.start()` is called. That call does nothing until the app is in the foreground.

### 2.3 `AppLifecycle` (the `UIApplication` state)

- The state is the maximum of `ProcessLifecycleOwner` and every car `Session` lifecycle:
  - `RESUMED` → `active`
  - `STARTED` → `inactive`
  - anything lower → `background`
- It sends `willEnterForeground`, `didBecomeActive` and `didEnterBackground` to its observers, in registration order, on the main thread. On a jump from `background` to `active`, `willEnterForeground` comes first.
- `isForeground` (`!= background`) and `isActive` replace `applicationState`.

Observers, and what they do:

| Observer | `willEnterForeground` | `didBecomeActive` | `didEnterBackground` |
| --- | --- | --- | --- |
| `LocationRecorder` | Swift | Swift (`start()` / `startMotion()`) | Swift |
| `AppModel` | `TileRepository.restoreAfterBackground()`, retry timer on, pending reload | Swift (elevation refreshes) | Retry timer off, `TileRepository.trimForBackground()`, `ImageryMemory.enteredBackground()`, `ContourRenderer.shared.clearCaches()` |
| `OfflineManager` | Swift (`networkChanged`, end the signal wait) | Swift | Swift (`background.enteredBackground()`) |

Each registers in its own `init`, as in Swift. `AppModel` builds `LocationRecorder`, then `KMLLibrary` and
`OfflineManager`, before its own `init` block, so they are told in the order recorder, offline manager, model.
Nothing registers them elsewhere. Tests call `AppLifecycle.resetForTests()` in `@After`: Robolectric keeps the object
from one test to the next.

The **phone's map** (`MapController.isShown`) follows `MainActivity` `onStart`/`onStop` only, as iOS's window
scene does. The **car's map** (`CarMapController.isShown`) follows the car screen's lifecycle.

### 2.4 Coroutines and threads

| Scope | Dispatcher | Used for |
| --- | --- | --- |
| `AppModel.scope`, `OfflineManager.scope`, `KMLLibrary.scope` | `SupervisorJob() + Dispatchers.Main.immediate` | Their own work, for the process lifetime. Off-main work is `withContext(IO/Default)`. |
| `MapController.scope`, `CarMapController.scope` | `SupervisorJob() + Dispatchers.Main` | **Collecting `AppModel` flows**, cancelled in `onDestroy` or when the surface is destroyed. |
| `TileRepository.scope` | `SupervisorJob() + Dispatchers.IO` | Tile and DEM loads shared between callers. They outlive a cancelled MapLibre request, as iOS's detached loads do. |
| `Work.contours` | `Dispatchers.Default.limitedParallelism(3, "satmap.contours")` | DEM decode, grids, marching squares, contour and colour bitmaps, PNG encoding. |
| `Work.prepare` | `Dispatchers.Default.limitedParallelism(2, "satmap.prepare")` | Imagery decode and stand-in JPEG encoding. |
| OkHttp dispatcher threads | (OkHttp) | `TileInterceptor.intercept`, which blocks in `runBlocking` until the answer (§3.2). |
| Compose | `collectAsStateWithLifecycle()`, `LaunchedEffect` | UI only. |

Rules:

1. **All `AppModel`, `OfflineManager`, `KMLLibrary`, `LocationRecorder` state is written on the main thread.**
   Readers on other threads use the documented snapshots: `Connectivity.isOnlineNow`/`isExpensiveNow`/`network`,
   `DrawnMap.focuses` (`@Volatile`), and `TileLayers`.
2. **Collectors that act on model changes use `Dispatchers.Main`, never `Main.immediate`.** This includes
   `MapController`, `CarMapController` and `OfflineManager`'s connectivity collector. The reason (state spec
   §2.10): with `Main.immediate`, a collector can run in the middle of `setMeasure`, before `selectedPoint` is
   set. This is the Swift `DispatchQueue.main.async` hop.
3. **MapLibre: main thread only.**
   - Every `MapLibreMap`, `Style`, `Source`, `Layer` and `Projection` call is made on the main thread, after `OnStyleLoaded`, and never after the controller's `onDestroy`. Cancel the scope first.
   - The interceptor and the tile workers never touch MapLibre objects. They talk to the map only through `DrawnMap.announce`, which posts to the main thread.
4. **Clocks:**
   - tile gates and map timers: `SystemClock.uptimeMillis()`;
   - the recorder: `SystemClock.elapsedRealtime()` (§5.1);
   - track points and the area `created` field: wall-clock seconds.
5. **Logging:** `android.util.Log` with the Swift logger categories as tags (`tiles`, `zoomscript`, `roads`, `places`, `location`, `offline`) and the Swift messages.

### 2.5 Contracts between work packages

These signatures are fixed. M0 creates them as compiling stubs, so every package builds against them from the
first day. A change goes through the owner (§7.3).

```kotlin
// app/AppModel.kt
interface ElevationSource { suspend fun elevation(p: GeoPoint): Double? }   // TileRepository implements it; main-safe
interface MapHost {                                    // MapController implements it
    val centerCoordinate: GeoPoint                     // the coordinate under the view centre (the crosshair)
    val isOnScreen: Boolean
    fun pointsApart(a: GeoPoint, b: GeoPoint): Double  // dp
    fun center(on: GeoPoint, animated: Boolean)
    fun zoom(onto: GeoPoint, metersAcross: Double)
    fun zoom(to: GeoBounds)
    fun locationUpdated(fix: GpsFix)
    fun headingUpdated(heading: Double?)
    fun reloadImagery()
}
interface CarMapHost { fun locationUpdated(fix: GpsFix); fun reload() }   // CarMapController implements it

sealed interface MeasureMode { data object Off; data object Aiming; data class Placed(val a: GeoPoint)
    data class Path(val pts: List<GeoPoint>); data class Closed(val pts: List<GeoPoint>)
    val points: List<GeoPoint>; fun moving(i: Int, p: GeoPoint): MeasureMode; companion object { fun label(i: Int): String } }
enum class ActiveSheet { blocks, offline, coordinates }
data class PendingArea(val id: UUID = UUID.randomUUID(), val name: String, val shape: CoverageShape)
data class DroneReport(val you: String? = null, val pin: String? = null)
class CrosshairAim(source: ElevationSource) { val point: StateFlow<GeoPoint?>; val elevation: StateFlow<Double?>; val elevationPoint: StateFlow<GeoPoint?>; fun set(p: GeoPoint?); fun retry() }
class HeightLookup(source: ElevationSource, found: (GeoPoint, Double?) -> Unit) { val point: GeoPoint?; fun set(p: GeoPoint?); fun retry() }   // main thread only
// AppModel passes its own ElevationSource to both, so a test's fake reaches the aim and every height lookup.
class MapScale { val label: StateFlow<String>; val width: StateFlow<Double>; var unit: DistanceUnit; fun update(metersPerPoint: Double); companion object { const val maxWidth = 110.0 } }
class MapLegend { val key: StateFlow<TerrainKey>; fun update(k: TerrainKey) }

class AppModel(app: Application, elevation: ElevationSource = TileRepository.shared) : AppLifecycle.Observer {
    companion object { lateinit var shared: AppModel; fun init(app: Application) }
    val recorder: LocationRecorder; val kml: KMLLibrary; val offline: OfflineManager
    val aim: CrosshairAim; val scale: MapScale; val legend: MapLegend
    val track: List<TrackPoint>; val trackRevision: StateFlow<Int>
    val following: StateFlow<Boolean>;            fun setFollowing(v: Boolean)
    val terrainMode: StateFlow<TerrainMode>;      fun setTerrainMode(m: TerrainMode)
    val distanceUnit: StateFlow<DistanceUnit>;    fun nextDistanceUnit()
    val heightUnit: StateFlow<HeightUnit>;        fun nextHeightUnit()
    val droneReport: StateFlow<DroneReport>
    val measure: StateFlow<MeasureMode>
    val markerElevation: StateFlow<Double?>; val lastElevation: StateFlow<Double?>; val userElevation: StateFlow<Double?>
    val markerDistance: Double?; val measuredArea: Double?          // derived getters, as Swift
    val selectedPoint: StateFlow<GeoPoint?>;      fun setSelectedPoint(p: GeoPoint?)
    val selectedElevation: StateFlow<Double?>; val selectedName: StateFlow<String?>
    val isDrawingArea: StateFlow<Boolean>; val lassoActive: StateFlow<Boolean>; fun setLassoActive(v: Boolean)
    val draftArea: StateFlow<List<GeoPoint>?>;    fun setDraftArea(r: List<GeoPoint>?)
    val pendingArea: StateFlow<PendingArea?>;     fun setPendingArea(a: PendingArea?)
    val sheet: StateFlow<ActiveSheet?>;           fun setSheet(s: ActiveSheet?)
    var map: MapHost?        // WeakReference inside
    var carMap: CarMapHost?  // WeakReference inside
    fun pointTapped(p: GeoPoint, name: String?); fun mapTapped(p: GeoPoint); fun movePoint(which: MovablePoint, p: GeoPoint, done: Boolean)
    fun measureButtonTapped(); fun closeMeasureShape(); fun undoMeasurePoint(); fun measure(from: GeoPoint); fun clearMeasure()
    fun centerOnUser(); fun clearTrack()
    fun startDrawingArea(); fun finishDrawing(ring: List<GeoPoint>); fun cancelDrawing(); fun confirmDrawnArea(); fun discardPendingArea()
    fun startDownload(area: PendingArea, roads: List<List<GeoPoint>>?, preset: String?, maxZoom: Int, allowCellular: Boolean)
    fun startTrackDownload(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean)
    fun applyLaunchArguments()
}

// location/LocationRecorder.kt — see §5.1 for GpsFix
class LocationRecorder(context: Context) : AppLifecycle.Observer {
    enum class Power { active, sleeping }
    enum class Authorization { notDetermined, denied, servicesOff, authorized }
    val location: StateFlow<GpsFix?>; val power: StateFlow<Power>
    val authorization: StateFlow<Authorization>; val reducedAccuracy: StateFlow<Boolean>
    var onTrackPoint: ((TrackPoint) -> Unit)?; var onFix: ((GpsFix) -> Unit)?; var onHeading: ((Double?) -> Unit)?
    var headingWanted: Boolean; var screenRotation: Int; var measuring: Boolean
    fun seed(lastRecorded: TrackPoint?); fun start(); fun authorizationChanged(); fun startMotion(); fun stop()
    @VisibleForTesting fun setForTests(fix: GpsFix?, authorization: Authorization = Authorization.authorized, reducedAccuracy: Boolean = false)
}

// map/MapController.kt (what the UI and MainActivity use)
class MapController(activity: ComponentActivity, model: AppModel, savedState: Bundle?) : MapHost, DefaultLifecycleObserver {
    val container: MapContainerView
    val bearing: StateFlow<Double>                    // degrees, for CompassButton
    fun resetNorth()
    fun onSaveInstanceState(out: Bundle)
    fun onLowMemory()
}
@Composable fun MapViewRepresentable(controller: MapController)

// map/TileRepository.kt (public part)
class TileRepository private constructor(context: Context) : ElevationSource {
    companion object { lateinit var shared: TileRepository; fun init(context: Context) }
    data class AreaRef(val id: UUID, val directory: File, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    fun setAreas(refs: List<AreaRef>); fun closeArea(id: UUID)
    suspend fun probeRecovery(): Boolean; fun resetFailureState()
    fun trimForBackground(); fun restoreAfterBackground(); fun trimMemory()
    override suspend fun elevation(p: GeoPoint): Double?
}
class HTTPTileFetcher(client: OkHttpClient, url: (TileID) -> String, wifiOnly: Boolean = false) : TileFetching
object SatelliteRenderer { fun reload(); fun level(pixelsPerWorld: Double): Int
    fun drawImagery(r: WorldRect, level: Int, canvas: Canvas, target: RectF) }   // the loupe

// offline/OfflineManager.kt (what the UI uses) — names as Swift
class OfflineManager(context: Context) : AppLifecycle.Observer {
    val areas: StateFlow<List<OfflineArea>>; val progress: StateFlow<DownloadProgress?>; val activeID: StateFlow<UUID?>
    val completedRevision: StateFlow<Int>; val backgroundStatus: StateFlow<String?>
    val backupStatus: StateFlow<String?>; val backupProgress: StateFlow<Double?>; val backupMessage: StateFlow<String?>
    fun add(name: String, shape: CoverageShape, roads: List<List<GeoPoint>>?, preset: String?, maxZoom: Int, allowCellular: Boolean): OfflineArea
    fun updateTrackArea(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean)
    fun pause(id: UUID); fun resume(id: UUID); fun delete(id: UUID); fun changeMaxZoom(id: UUID, zoom: Int); fun setAllowCellular(id: UUID, allowed: Boolean)
    fun refreshSizes(); suspend fun savedImageryDepth(id: UUID): Int?; fun canDownloadNow(a: OfflineArea): Boolean
    fun export(ids: List<UUID>); fun restore(uris: List<Uri>); fun clearBackupMessage()
    companion object { const val minimumFreeSpace: Long = 500L shl 20 }
}

// kml/KMLLibrary.kt
class KMLLibrary(context: Context) {
    data class Entry(val id: UUID, val name: String, val fileName: String, val hidden: Boolean? = null)   // an index.json record (offline spec §2.9)
    data class File(val id: UUID, val name: String, val fileName: String, val document: KMLDocument, val isShown: Boolean)
    val files: StateFlow<List<File>>; val useFileColours: StateFlow<Boolean>; fun setUseFileColours(v: Boolean)
    val importError: StateFlow<String?>; fun clearImportError()
    fun importFile(uri: Uri, completion: ((File?) -> Unit)? = null); fun setShown(id: UUID, shown: Boolean); fun delete(id: UUID)
}
```

---

## 3. The map

### 3.1 MapLibre setup

- **Artifact:** `org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1` (D1): Vulkan, or OpenGL ES without it. It brings `android-sdk-geojson:6.0.1` and `maplibre-android-gestures:0.0.4`.
- **Never import `org.maplibre.android.offline.*`.** Our `OfflineManager` has the same name, and MapLibre's offline database is not used.
- **Global setup:** §2.2 step 3.
- **`TileInterceptor.mapClient`:**
  - `OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 }).addInterceptor(TileInterceptor()).build()`
  - Hosts other than `satmap.local` pass through with `chain.proceed`. No style, glyph or sprite URL exists, so in practice nothing passes through.
- **Phone `MapView` options:**
  - `MapLibreMapOptions.createFromAttributes(context)`
  - `.pixelRatio(density)`, `.textureMode(false)`
  - `.setPrefetchesTiles(true)`, `.setPrefetchZoomDelta(3)`
  - `.logoEnabled(false)`, `.attributionEnabled(false)`, `.compassEnabled(false)`
  - `.tiltGesturesEnabled(false)`, `.rotateGesturesEnabled(true)`
  - `.foregroundLoadColor(0xFF202020.toInt())`
  - After `getMapAsync`: `map.setPrefetchZoomDelta(3)`, and the `UiSettings` setters with the same values.
- **The credits:** they are only in the Offline maps footer, as on iOS.

### 3.2 Tile URLs and the interceptor (`map/TileInterceptor.kt`)

```
https://satmap.local/imagery/{gen}/{z}/{x}/{y}
https://satmap.local/contours/{gen}/{scale}/{z}/{x}/{y}    scale = round(s·100): s = 3 on the phone, min(max(1, pixelRatio), 3) in the car
https://satmap.local/colours/{gen}/{range}/{z}/{x}/{y}     range = "none" | "{low}_{high}" (Double.toString)
```

**Generations.**
- `gen` comes from `TileUrls.nextGeneration()`, a global `AtomicInteger` starting at 1, shared by the phone and the car.
- The source id and the layer id are both `"${kind}-${gen}"`.
- A source is registered in `TileLayers` (`gen → TileLayer(gen, kind, map: DrawnMap)`) when it is added, and unregistered when it is removed.
- A source's URL can't change in 13.7.1. A new colour range, or a reload, is therefore a new generation, so a new source.
- The phone's contours are drawn at the iPhone's scale, `TileLevels.PHONE_CONTOUR_SCALE` = 3, whatever the phone's
  density (an Android difference: iOS uses its own screen's). The tiles are 128 to 256 dp across on both, so the line
  widths, labels and intervals are the iPhone's in dp. The legend and the source's minzoom use the same scale. The car
  keeps its own screen's, `TileLevels.contourScale(pixelRatio)`.

**`TileInterceptor.intercept`.**
1. Requests to other hosts go on with `chain.proceed`.
2. Parse the URL with `TileUrls.parse`. A malformed URL, or a generation not in `TileLayers`, gets a 204 with no `Cache-Control`.
3. Otherwise run `runBlocking { awaitUnlessCanceled(chain.call()) { SatelliteRenderer.answer(route, layer) } }`.
   - It checks `call.isCanceled()` every 50 ms.
   - If MapLibre cancelled the call, it throws `IOException("Canceled")`. The shared load goes on in `TileRepository.scope`.
4. Then `layer.noteAnswered(tile, answer.lacking)`, and build the `okhttp3.Response` from the answer:
   - `Protocol.HTTP_1_1`;
   - `Content-Type`;
   - `Cache-Control: max-age=N` when the answer has one.

Never return an empty 200: MapLibre would count it as a decode error.

**The answer table** (rendering spec §2.5; `max-age` is what is left of the retry gate, at least 1 s):

| Route | Outcome | Answer | `Cache-Control` |
| --- | --- | --- | --- |
| imagery | the tile's own data | 200, the stored JPEG/PNG bytes | none |
| imagery | stand-in, beyond depth | 200, crop of the nearest ancestor in memory (JPEG q90) | `max-age=600` (what is left) |
| imagery | stand-in after a failure or offline | 200, crop | `max-age=30` (what is left) |
| imagery | nothing anywhere | 204 | `max-age=30` (what is left) |
| contours | lines, complete / incomplete | 200, PNG | none / `max-age=30` |
| contours | blank (flat ground) | 200, `TileAnswer.BLANK_PNG` (1×1 transparent). **Never** 204, or MapLibre would show the parent's lines. | none |
| contours | unavailable | 204 | `max-age=30` |
| colours | range `none` | 200, 1×1 transparent. The heights are loaded so that a range can be chosen. | none |
| colours | grid, complete / incomplete | 200, PNG | none / `max-age=30` |
| colours | unavailable | 204 | `max-age=30` |

What MapLibre does with these:
- A 204 leaves the tile without content, so MapLibre draws a loaded parent (within `maxOverscaleFactorForParentTiles`) or the children.
- An expired tile is asked for again while it is in use. The old image stays until the new one is decoded.

These three replace iOS's stand-in and repaint machinery (rendering spec §2.12 lists what is *not* ported).

### 3.3 Levels (`TileLevels`)

MapLibre picks a raster level with `round(zoom + log2(512 / tileSize))`, clamped to the source's minzoom and
maxzoom. The tile sizes below make that equal to iOS's levels (proof in rendering spec §2.4):

| Source | `tileSize` (dp) | minzoom | maxzoom | Level = iOS |
| --- | --- | --- | --- | --- |
| imagery | `round(256 / max(1, pixelRatio))` (98 at 2.625, 85 at 3) | 0 | 22 | `round(Zp + log2 s)`: tiles 256 *pixels* across |
| colours | 182 | 8 | 22 | `ceil(Zp − 1e-6)` |
| contours | 182 | `contourMinZoom(s)`: 13 on the phone (s = 3); in the car 12 at s = 2, 13 at 2.63 | 22 | `ceil(Zp − 1e-6)` |

`Zp = cameraPosition.zoom + 1` (the 256-point zoom). `DrawnImagery` computes its levels with the same
`TileLevels.level(zoom, tileSize)`, so the focus, prefetch, readiness and legend all use the levels MapLibre
asks for.

**Source settings:**
- every source: `setVolatile(true)` (TileRepository caches; MapLibre's SQLite cache stays empty);
- imagery: `setPrefetchZoomDelta(3)` and `setMaxOverscaleFactorForParentTiles(10)`;
- contours and colours: `setPrefetchZoomDelta(0)` and `setMaxOverscaleFactorForParentTiles(1)`.

**Layer settings:**
- `rasterFadeDuration(0f)`, `rasterResampling(LINEAR)`;
- `setRasterOpacityTransition(TransitionOptions(0, 0, false))`;
- the style itself: `withTransition(TransitionOptions(0, 0, false))`.

### 3.4 `TileRepository`

The logic is Swift's, line for line (rendering spec §1.3–1.6 and §2.7):

- `nearestImagery`: offline areas first, then `offlineKnowsMissing` up to 4 levels, then the network, then ancestors up to 8 levels.
- `onlineImagery`: one shared request per tile, re-requested when a higher priority arrives.
- `limitedFetch`: the terrain limiter (4 slots) for hosts starting with `elevation`, the imagery limiter (10 slots) for the rest.
- The network pause: 8 failures → 45 s.
- `probeRecovery`.
- `demTile`: an LRU of 160, with 30 s and 600 s retry gates.
- `elevation(p)`.

Android specifics:

- **`browseClient`:**
  - `Cache(File(cacheDir, "tiles-http"), 1L shl 30)`;
  - 15 s connect, read and write timeouts;
  - `ConnectionPool(10, 5, MINUTES)`;
  - a User-Agent interceptor sending `SatMap/1.0 (Android)`.

  OkHttp has no memory cache, so `restoreAfterBackground()` is empty, with a comment saying why.
- **Calls:** `RequestLimiter.acquire(priority)`, then `withContext(IO) { call.execute() }`. `invokeOnCancellation` cancels the `Call`.
- **`RequestLimiter`** is a coroutine port of the Swift class:
  - urgent, then normal, then background waiters, newest first within each;
  - background holds at most 2 slots;
  - a waiter cancelled while queued is removed.
- **`HTTPTileFetcher`** keeps the Swift result classification.
  - An `IOException` gives `offline` if `!Connectivity.isOnlineNow` (or, when `wifiOnly`, if the network is now expensive), otherwise `failed`.
  - `wifiOnly` also returns `offline` before the request when the network is expensive (D14).
- **Offline areas:**
  - `setAreas(refs)` keeps one `OpenArea` per area. It opens `imagery.mbtiles` and `terrain.mbtiles` lazily, through `AndroidSqlDriver` (D17), retrying the open at most every 10 s.
  - A tile is looked up only in areas whose world bounds intersect it; the first hit wins.
  - `closeArea(id)` and `trimForBackground()` close the readers.
- **`Work`** and **`LRUCache`:** §2.4, and a synchronised access-order `LinkedHashMap`.

### 3.5 `SatelliteRenderer`, `ImageryMemory`, `ImageryPrefetch`, `DrawnMap`

- **`SatelliteRenderer.answer(route, layer)`** works as rendering spec §2.6 describes:
  - imagery: memory, then the shared `load(t, urgent)` behind the gate, then a stand-in crop or a 204;
  - contours: `noContours`, then the complete cache, then the gate, then the shared `ContourRenderer.shared.tile(t, scale)`;
  - colours: `heightGrids`, then the shared `loadGrid`, then `colourPng`.

  `urgent` means the tile is at least 3 levels above the map's imagery level: MapLibre's prefetch tiles.
- **Caches:**
  - `ImageryMemory.tiles = RecentCache<TileID, ByteArray>(900)`;
  - `decoded` 64 bitmaps (stand-in crops and the loupe);
  - `contourImages` 240, keyed `"scale/z/x/y"` (the phone and the car have different scales);
  - `noContours` 4000;
  - `heightGrids` 400;
  - `colourImages` 200, keyed `"low_high/z/x/y"`;
  - `standInJpegs` 256.
- **The stand-in crop:**
  - `Matrix.setRectToRect(src, RectF(0,0,256,256), FILL)` with `Paint(FILTER_BITMAP_FLAG)`, drawn from the whole decoded ancestor (no seams);
  - `compress(JPEG, 90)`, on `Work.prepare`.
- **`ImageryPrefetch.mapStopped(visible, level)`:**
  - the tiles 3 levels up over twice the screen, and 6 levels up over eight times the screen;
  - x wraps, and a level is skipped above 64 tiles per axis;
  - tiles in memory are touched; the others are loaded with `imageryInBackground` at background priority.

  The Swift on-screen "prepare" (decode) step is not ported.
- **`DrawnMap`:**
  - holds the immutable `@Volatile` `Focuses` (imagery rect and level, contour level);
  - `onArrival: ((TileID, Content) -> Unit)?`, called on the main thread;
  - `companion fun announce(t, content)` posts to every live map (`WeakReference` list).

### 3.6 Contour and colour bitmaps (`TileOverlays.kt`)

Both are 256×256 `ARGB_8888` bitmaps, encoded with `compress(PNG, 100)`, on `Work.contours`.

**Contours** (Swift `ContourRenderer`, constants in rendering spec §2.8):
- One halo pass, then one line pass.
- Widths:
  - minor `0.7·s`;
  - index `1.3·s`;
  - halo = line width + `0.9·s`.
- Round joins and caps.
- Chaikin smoothing when `z > 15`.
- Labels on checkerboard tiles only, `8.5·s` semibold (`Typeface.create(DEFAULT, 600, false)` on API 28+, else `sans-serif-medium`).
  - Under each label, a cleared box (`PorterDuff.Mode.CLEAR`).
  - The text: fill first, then a stroke of `0.03 × textSize` on top.
- The PNG cache is `LruCache<String, ByteArray>(32 MiB)`, keyed `"scale/z/x/y"`.

**Colours:**
- `ElevationColours.pixels(grid, range)` gives premultiplied RGBA, the same byte layout as `ARGB_8888`. It goes in with `copyPixelsFromBuffer` into a 65×65 bitmap.
- That bitmap is drawn with `FILTER_BITMAP_FLAG` into `RectF(-2, -2, 258, 258)`, so each node's pixel is centred on the node.
- The 50 % alpha is in the pixels, so the layer's opacity is 1.

### 3.7 `DrawnImagery`

```kotlin
class DrawnImagery(mapView: MapView, map: MapLibreMap, isVisible: () -> Boolean, terrain: () -> TerrainMode,
                   contourScale: Double) {   // PHONE_CONTOUR_SCALE on the phone, contourScale(pixelRatio) in the car
    val drawnMap: DrawnMap; var colourRange: ElevationRange?; var onKey: ((TerrainKey) -> Unit)?
    fun addTo(builder: Style.Builder)       // imagery, colours (visibility from mode), contours: each a fresh generation, in that order
    fun regionWillChange(); fun regionDidChange(); fun visibleRegionChanged()
    fun reload(); fun terrainChanged(mode: TerrainMode); fun becameVisible()
}
```

**Geometry:**
- `levels()` is null when the view is narrower than 100 dp.
- `visibleWorldRect()` comes from `projection.visibleRegion`, with x unwrapped around the centre.
- `screenPoints()` is 81 points (9×9) through `fromScreenLocation`, inset 1 px.

**Focus:** `visibleRegionChanged` sets the focus to the visible rect grown by ¼ on each side (both levels), then
calls `reportKey()`.

**Map stopped:** 400 ms after the camera goes idle, debounced, and only while the map is visible:
1. set the focus;
2. `ImageryPrefetch.mapStopped`;
3. `updateColourRange()`.

**Colour range and legend key:** Swift's `updateColourRange` and `reportKey`, exactly. The 1.6 s re-check is a
`Handler.postDelayed`.

**Replacing a layer** (only for a new colour range, or `reload()` with stand-ins in focus):
- The timing is iOS's `scheduleSwap`: at least 0.25 s, about once a second, not while the map is moving (unless it has moved for more than 3 s), and owed while the map is invisible.
- **Imagery:** the new layer goes *below* the shown one, at full opacity.
- **Colours and contours:** the new layer goes *above* the shown one, at `rasterOpacity(0.01f)`. MapLibre still loads its tiles; at opacity 0 it would not.
- **Retiring**, every 100 ms:
  - the new layer is ready when every tile under the 81 screen points at its level has been answered (`TileLayer.answered`);
  - 400 ms after that, in one main-thread block: the new layer goes to opacity 1, and the old layer and source are removed;
  - if it is not ready after 20 visible ticks, the new layer is dropped and the replacement rescheduled.
- **A hidden colours layer** (a mode without colours) is replaced at once.

**Terrain mode change:** `visibility(VISIBLE/NONE)` on the shown (and incoming) colours and contours layers.
Hidden layers request nothing, so no elevation is downloaded with terrain off.

**Hooks**, all called by the map controller (D18):

| MapLibre event | Call |
| --- | --- |
| `addOnCameraMoveStartedListener` | `regionWillChange()` (plus `following = false` when `reason == REASON_API_GESTURE`) |
| `addOnCameraMoveListener` | `visibleRegionChanged()` (also once after the style loads) |
| `addOnCameraIdleListener` | `regionDidChange()` |

### 3.8 The style: every source and layer, bottom to top

`MapStyle.builder(imagery)` does this:
1. `Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[]}""")`;
2. the `background` layer (`#202020`);
3. `imagery.addTo(builder)`;
4. `terrain-top`, a hidden background layer that anchors the overlays;
5. `.withTransition(TransitionOptions(0, 0, false))`.

`MapStyle.installOverlays(style, images)` runs in `OnStyleLoaded`. It adds every fixed overlay source (empty
GeoJSON; `withSynchronousUpdate(true)` for `user`, `selected`, `measure`, `measure-points`) and every fixed layer
with `style.addLayer` in the order below, plus the base images.

The phone and the car install the same stack, and unused sources stay empty. Per-file KML layers are inserted
against the anchors `track`, `aviation-rings` and `selected`.

| # | Layer id | Type | Source | Defined by | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | `background` | background | — | MapStyle | `#202020`, shows only where there is no imagery at all |
| 2 | `imagery-<gen>` | raster | `imagery-<gen>` | DrawnImagery | during a replacement, two can exist |
| 3 | `colours-<gen>` | raster | `colours-<gen>` | DrawnImagery | `visibility` from the mode |
| 4 | `contours-<gen>` | raster | `contours-<gen>` | DrawnImagery | |
| 5 | `terrain-top` | background | — | MapStyle | always hidden |
| 6 | `kml/<uuid>/fill`, `kml/<uuid>/line` | fill, line | `kml/<uuid>` | MapStyle.kmlLayers, inserted by BlockLayers | `addLayerBelow(…, "track")`; each newly shown file goes above the earlier ones |
| 7 | `track`, `track-live` | line | `track`, `track-live` | MapStyle | `#00E6FF`, width 4, `lineDasharray(0, 2.25)`, round cap and join |
| 8 | `measure-fill`, `measure-line` | fill, line | `measure` | MapStyle | pink 15 % fill; pink width 3, dashes `(10/3, 7/3)`, butt cap |
| 9 | `draft-fill`, `draft-line` | fill, line | `draft` | MapStyle | white 15 %; white 2.5, dashes `(3.2, 2.4)`, round |
| 10 | `kml/<uuid>/names` | symbol | `kml/<uuid>/labels` | MapStyle.kmlLayers | filter `kind == "name"`; `addLayerBelow(…, "aviation-rings")` |
| 11 | `aviation-rings` | symbol | `aviation` | MapStyle | map-aligned runway rings, filtered to visible cards (§3.13) |
| 12 | `aviation` | symbol | `aviation` | MapStyle | the cards, `symbolSortKey(get("key"))`, `SYMBOL_Z_ORDER_SOURCE` |
| 13 | `kml/<uuid>/points` | symbol | `kml/<uuid>/labels` | MapStyle.kmlLayers | filter `kind == "point"`; `addLayerBelow(…, "selected")` |
| 14 | `selected` | symbol | `selected` | MapStyle | allow-overlap and ignore-placement |
| 15 | `measure-markers`, `measure-letters` | symbol | `measure-points` | MapStyle | markers: allow-overlap, not ignore-placement; letters: both |
| 16 | `user-arrow`, `user-dot` | symbol | `user` | MapStyle | the arrow is `iconRotationAlignment(MAP)`; the dot allows overlap and still takes placement |

**Collisions.** MapLibre places symbols from the top layer down, so this order gives MapKit's priorities:
- required items: you, then the markers;
- KML points (750), then aviation (450–740, interleaved by sort key), then block names (250).

Every collidable layer has `iconAllowOverlap(false)`, `iconIgnorePlacement(false)` and `iconPadding(0f)`.

### 3.9 What is a style layer and what is a View

| Thing | How | Why |
| --- | --- | --- |
| Your dot and compass arrow, the track, KML fills/lines/names/points, the measuring line/fill/markers/letters, the selected pin, the draft area, aviation cards and runways | GeoJSON sources plus style layers and **app-drawn images** | Fixed to the ground with no frame of lag. MapLibre's `LocationComponent` is **never** activated: its location engine would keep the GPS on. |
| All map text | Bitmaps drawn by `MapImages`/`AviationCard` with `Canvas` and added with `Style.addImages` | The style has no `glyphs` URL. App-drawn text gives the iOS fonts, shadows, tabular digits and the card background (interaction spec §2.3). The bitmap density is the map's density. A changed image gets a new id; the generation before last is removed. |
| Aim line | `AimLineView` in `MapContainerView` | One end is on the screen centre, and it follows a pan frame by frame. |
| Lasso | `LassoView` in `MapContainerView` (`GONE` unless drawing) | Drawn in screen space, and it takes the finger. |
| ZoomScript squares | small Views in `MapContainerView` | Debug only. |
| Crosshair, panels, buttons, legends, scale bar, compass | Compose over the map (§4) | As SwiftUI over MapKit. |
| Loupe | `LoupeFrame` added to `android.R.id.content` | Above everything, as iOS adds it to the window. |

View tree:
```
android.R.id.content
 ├─ ComposeView ─ MapScreen ─ MapViewRepresentable ─ AndroidView(controller.container)
 │                                   MapContainerView : FrameLayout { MapView, AimLineView, LassoView, ZoomScript squares }
 │               ─ Crosshair, panels, controls, sheets (ModalBottomSheet = own window)
 └─ LoupeFrame (only while a point is moved)
```

`MapViewRepresentable` detaches `container` from an old parent before returning it from the `AndroidView`
factory.

### 3.10 Gestures and taps

These are MapLibre's own gestures:
- pan, pinch, rotate and fling;
- double tap to zoom in, two-finger tap to zoom out;
- quick zoom (tap, then drag).

Tilt is off.

**Single tap:** `map.addOnMapClickListener`. MapLibre calls it from `onSingleTapConfirmed`, so it never fires for
a double tap, a quick zoom, a two-finger tap or a long press: the same as iOS's `require(toFail:)`. The handler
then follows interaction spec §1.1:
- It is ignored while drawing, while a point is moving, and for 0.4 s after one is let go.
- It uses the coordinate recorded at finger-up, when the tap is within 1 dp of it.
- Then, in order:
  1. a KML point (26 dp, or on its name via `queryRenderedFeatures(RectF(spot ± 6dp), pointLayerIds)`);
  2. an aviation place (24 dp, visible cards only);
  3. close the shape (30 dp from A);
  4. otherwise `model.mapTapped(c)`.

**Press and hold to move a point:** `MapContainerView.dispatchTouchEvent` runs its own detector:
- it fires after 0.35 s;
- it is cancelled by a move beyond 10 dp, a second finger, or a tap-then-press;
- it starts only within 30 dp of a measuring point or the pin.

When it fires:
1. send the `MapView` an `ACTION_CANCEL`;
2. switch off scroll, zoom, rotate, double-tap and quick-zoom gestures;
3. `following = false`;
4. `performHapticFeedback(LONG_PRESS)`;
5. `adjuster.begin(...)`.

While a point is moving, the container consumes every event. When it ends:
- the gestures come back on;
- `CLOCK_TICK` haptic;
- taps are ignored for 400 ms.

The model is updated at most 15 times a second, and always on `done`.

**Lasso:** `LassoView` takes one finger and adds a point every 3 dp. On lift: Douglas–Peucker at 4 dp, then
`model.finishDrawing(ring)`. With fewer than 3 points, `finishDrawing(emptyList())`.

**Compose overlay:** every panel and pill consumes its touches (`Modifier.blocksMap()`), so a tap on a panel
never places a measuring point. Legends, the scale bar line, the crosshair and the toast have no pointer input,
so the map underneath gets those touches.

### 3.11 The loupe (`PointAdjuster.kt`)

Geometry, motion, content and drawing order are Swift's (interaction spec §1.2):
- a 232 dp square, with an 8 dp rim and a 216 dp content;
- 15 m across;
- a 5 m free radius, then the map scrolls at up to 20 m/s;
- 44 dp above the finger.

Android specifics:
- **`LoupeView.onDraw`:**
  - `canvas.rotate(-bearing)`;
  - the imagery from `SatelliteRenderer.drawImagery(groundRect, level, canvas, target)` (D7), at level `round(log2(k·density/256))` clamped to [0, 22];
  - blocks, points, the measuring line and the moving point drawn with `Path`, `Paint` and `DashPathEffect`.
- **Each frame:** a `Choreographer` frame callback.
- **Scrolling the map:** `moveCamera(newLatLng)`.
- **`LoupeFrame`:**
  - `glass` is an `ImageView` with a `map.snapshot` crop. On API 31+ it gets `RenderEffect` blur + tint; on 26–30, a 1/8 downscale + tint. A new snapshot every 200 ms while scrolling.
  - `LoupeView` sits at (8, 8), clipped to 24 dp corners.
  - A 1 dp white 45 % border.
  - `elevation` 14 dp.
- **Animations:** in from scale 0.6 / alpha 0 over 250 ms (`OvershootInterpolator(0.6f)`); out to 0.85 / 0 over 180 ms.
- **`PointAdjuster.place(...)`** is a pure function in its companion, for the tests.

### 3.12 Camera

| iOS | Android |
| --- | --- |
| Saved camera `"camera"` | SharedPreferences string `"lat,lon,dLat,dLon"` (`Locale.ROOT`). Saved on camera idle and in `onStop`. Restored once the container has a size (`doOnLayout`), without animation, with `newLatLngBounds(bounds, 0)`. With nothing saved: (−28.5, 24.7), 14° × 14°. |
| First fix (nothing saved) | 1500 m × 1500 m around you, once: `moveCamera(newLatLngBounds)`. |
| Following | Each used fix eases the centre onto you, unless `keepCameraUntil` (now + 1 s) hasn't passed. A gesture (`REASON_API_GESTURE`) sets `following = false`. |
| `zoom(onto:metersAcross: 300)` | Ground metres per dp across 100 dp at the centre → `newCameraPosition(…target(c).zoom(zoom + log2(across/300)))`, keeping the bearing. |
| `zoom(to: GeoBounds)` | span = max(0.002°, 1.25 × the bounds' span), then `easeCamera(newLatLngBounds(b, 0))`, `following = false`. |
| `centerCoordinate` | `fromScreenLocation(view centre)`. Never call `setPadding` on the phone map. |
| metres per point | `fromScreenLocation(centre ± 50 dp)`, `Geo.distance / 100` → `model.scale.update`. |
| Every camera frame | aim (`model.aim.set(centre)` while aiming), scale, the `DrawnImagery` hooks, `bearing.value = cameraPosition.bearing`. |
| Camera idle | save, aim, scale, `imagery.regionDidChange()`, `aviation.regionChanged()`. |
| ZoomScript zoom `z` | MapLibre zoom `z − 1`. |

### 3.13 Drone mode

- **`AviationStore`** reads `assets.open("Aviation.tsv")` once on `Dispatchers.IO` and calls back on the main thread. `AppModel.updateDroneReport` uses it as in Swift.
- **`AviationLayer.regionChanged()`:**
  - needs a view wider than 100 dp;
  - keeps the places whose `kind.shownWithinKm ≥` the km across the screen, within the region ± 65 %, at most 140 (by priority, then distance);
  - draws the card bitmaps on `Dispatchers.Default`, then on the main thread runs `addImages` → `setGeoJson` → removes the generation before last.
- **Cards:** the 22×22 symbol slot is at the top of the card image, so the whole frame collides, as on iOS.
- **Runway kinds** (large, medium and small airports, seaplane bases): their ring and runway are a separate map-aligned symbol (`aviation-rings`, `iconRotate(get("rwy"))`), so MapLibre turns it with the map.
  - Its filter is `in(get("id"), literal(visibleIds))`.
  - `visibleIds` come from `queryRenderedFeatures(viewRect, "aviation")`. They are refreshed on camera idle, every 100 ms while the camera moves, and 350 ms after each source update.
- **Distances:** cards are redrawn when you or the pin move by ≥ 20 m, or when either appears or disappears.

### 3.14 Memory and lifecycle

| Event | Calls |
| --- | --- |
| `AppLifecycle.didEnterBackground` | `TileRepository.trimForBackground()`: clear the DEM cache, close the MBTiles readers, clear `imageryMissing`. Then `ImageryMemory.enteredBackground()` (`tiles.trim(150)`, `SatelliteRenderer.trim(0)`) and `ContourRenderer.shared.clearCaches()`. |
| `onTrimMemory` (D15) / `onLowMemory` | `TileRepository.trimMemory()`, `ImageryMemory.trimMemory()` (`trim(100)`, `trim(32)`), `ContourRenderer.shared.clearCaches()`, then `onLowMemory()` on each live map controller. |
| `MainActivity` `onStart` / `onStop` | `MapController.sceneWillEnterForeground()` / `sceneDidEnterBackground()`. These handle `isShown`, `headingWanted`, saving the camera, `imagery.becameVisible()`, catching up on the track, and `locationUpdated(last fix)`. |
| Activity lifecycle → `MapView` | `MapController` is a `DefaultLifecycleObserver` on `MainActivity` and forwards `onStart/onResume/onPause/onStop/onDestroy`. `onCreate(savedState)` is called in its constructor. `onSaveInstanceState` comes from `MainActivity`. |

`largeHeap` is not needed. The budget is in rendering spec §2.11.

---

## 4. Screens

### 4.1 Compose tree

```kotlin
// MainActivity.onCreate
enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder, mapController) } }

@Composable fun MapScreen(model: AppModel, offline: OfflineManager, recorder: LocationRecorder, map: MapController?)
Box(fillMaxSize, semantics { testTagsAsResourceId = true })
├─ if (map != null) MapViewRepresentable(map)                       // whole window, behind the system bars
├─ Crosshair()                                                       // aiming/placed/path; centred on the window; no input
├─ Box(windowInsetsPadding(systemBars ∪ displayCutout))              // the "safe area"; never the IME
│  ├─ Column(padding(start 12, end 12, bottom 8), spacedBy 8, CenterHorizontally)
│  │    DrawingBar? · DownloadBanner? · AimPanel? | MeasurePanel? · CoordinateRow(me)? · DroneCard? · Hint?
│  │    Spacer(weight 1f)
│  │    if (!isDrawingArea) Row(Bottom, padding(bottom = 124.dp)) {
│  │        Column(spacedBy 8) { ContourKeyView; ScaleBarView; ColourKeyView }; Spacer(weight 1f); Controls }
│  └─ tapped spot (BottomCenter): name capsule? + CoordinateRow(tapped)
├─ SheetHost(model.sheet)            { blocks → BlocksSheet · offline → OfflineSheet · coordinates → CoordinateEntryView }
├─ PendingAreaHost(model.pendingArea) { NewAreaView(showsCancel = true) }
├─ ClearTrackDialog
└─ ShareHost()                                                       // WP-E: chooser + folder picker launchers
```

- `Controls` (top to bottom): a 44 dp compass slot, then Terrain, Blocks, Offline maps, Go to coordinates, Measure and My location.
  - The buttons are 50 dp circles with 12 dp between them.
  - The terrain toast is laid out at zero size beside the Terrain button.
- Each composable collects only what it shows (UI spec §6.5). A pan therefore recomposes only `AimPanel`; a zoom only the scale bar and the keys.
- The screen stays on while `offline.activeID != null` (`view.keepScreenOn` in a `DisposableEffect`).
- `map == null` in Robolectric Compose tests. The compass then hides.

### 4.2 Sheets and navigation

- **Every iOS sheet is a `ModalBottomSheet`:**
  - `skipPartiallyExpanded = true`;
  - `dragHandle = null`;
  - container `#1C1C1E`, scrim black 40 %, top corners 10 dp;
  - status-bar inset + 10 dp above it, so the dimmed map shows, as with an iOS page sheet;
  - `contentWindowInsets = { WindowInsets(0) }`.
- **Two hosts:**
  - `model.sheet`: dismissing it calls `setSheet(null)`.
  - `model.pendingArea`: dismissing it calls `setPendingArea(null)` and `setDraftArea(null)`.

  "Done" and "Cancel" hide with an animation, then clear the state. When the model clears the state itself, the host keeps the last content while it hides.
- **No navigation-compose.** Each sheet keeps `mutableStateListOf<Route>(Root)`. A route object holds its screen's state (`PlaceSearchState`, `NewAreaState`, …), so a screen under another one keeps its state.
  - Routes change through `AnimatedContent`: push slides in from the right; pop slides back.
  - `BackHandler(stack.size > 1)` pops the stack. At the root, system back closes the sheet.
  - Offline routes: `Root`, `PlaceSearch`, `PlaceArea`, `KMLPicker`, `NewArea`, `Preset`, `Track`, `AreaDetail`.
- **`SheetTopBar`:** `CenterAlignedTopAppBar(expandedHeight = 56.dp, windowInsets = WindowInsets(0))`.
  - The title is Headline, tagged `screenTitle`, marked as a heading.
  - The leading slot is "Done", "Cancel", or a back button (`ArrowBackIos` with the previous title, tagged `back`).
  - Once the list has scrolled under it, the bar turns `SheetBarScrolled` (`#2D2D2E`, iOS's grey bar material) with a 0.5 dp separator under it.
- **Insets inside a sheet:** `LazyColumn(Modifier.imePadding(), contentPadding = top 16, bottom navigationBars + 15)`, sections `spacedBy(29.5.dp)` (UI spec §1.5).
- **Focus on open:**
  - Go to coordinates: once the sheet is expanded.
  - Find a place: when it has no results yet.

### 4.3 Shared styling (`Theme.kt`, `Form.kt`)

- **Always dark:** `darkColorScheme` as in UI spec §1.1. The accent is `#FFBD29` (the iOS AccentColor).
- **pt → dp, SwiftUI font sizes → sp.** The type scale:

| SwiftUI | sp / line height | Compose style |
| --- | --- | --- |
| `.headline` | 17 / 22, SemiBold | `Headline` (= `titleMedium`) |
| `.body` | 17 / 22 | `Body` (= `bodyLarge`) |
| `.subheadline` | 15 / 20 | `Subheadline` (= `bodyMedium`) |
| `.footnote` | 13 / 16 | `Footnote` (= `bodySmall`) |
| `.caption` | 12 / 16 | `Caption` |
| `.caption2` | 11 / 13 | `Caption2` (= `labelSmall`) |
| `.system(30, .bold)` | 30 / 36, Bold | `BigNumber` |

  `.footnote`'s 16 is measured from the iPhone screenshots (wrapped footers are 16 pt a line), not Apple's 18.

- **Text effects:**
  - `.monospacedDigit()` → `fontFeatureSettings = "tnum"`;
  - text shadow → `Shadow(Black, Offset.Zero, blurRadius = 3.dp)`;
  - heavy → `ExtraBold`.
- **Colour tokens:**

| Token | Value |
| --- | --- |
| Accent | `#FFBD29` |
| SystemYellow | `#FFD60A` |
| SystemGreen | `#30D158` |
| SystemOrange | `#FF9F0A` |
| SystemRed | `#FF453A` |
| SheetBackground | `#1C1C1E` |
| SheetBarScrolled | `#2D2D2E` |
| CellBackground | `#2C2C2E` |
| CellPressed | `#3A3A3C` |
| Separator | `#38383A` |
| LabelSecondary | `#99EBEBF5` |
| LabelTertiary | `#4DEBEBF5` |
| SecondaryFill | `#52787880` |
| PasteFill | `#BD8B1A` |
| YouColour | `#59DBFF` |
| PinColour | `#FFB833` |

  The elevation ramp comes from core `ElevationColours.stops`.
- **Over the map:**
  - **Pill:** black 65 %, `CircleShape`, padding 12 / 8.
  - **Panel:** black at the panel's alpha, 12 dp corners.
  - Panels and pills use `Modifier.blocksMap()`, a `pointerInput` that consumes everything; their children handle their own taps first.
- **Form** (UI spec §1.5, D9):
  - Sections: headers upper-case Footnote, secondary colour, 32 dp from the edge.
  - Cards: 16 dp margin, 10 dp corners, `#2C2C2E`.
  - Sections 29.5 dp apart, and 5.5 dp more under a section without a footer: 35 dp after a card, 33 dp after a footer's last baseline, as iOS's.
  - Rows: at least 44 dp, inset 11 dp (11.33 for rows of several lines); icon rows have 28 dp icons in a box 28 dp wide and a line (22 dp) tall, which they overflow, and text starting 60 dp from the card edge.
  - Dividers: 0.5 dp, inset to the text.
  - Row kinds: link rows (chevron), action rows (accent), iOS switches (`IosSwitch`, drawn: M3's thumb sizes are fixed), and `SwipeToDismissBox` delete rows (with a "Delete" custom accessibility action).

### 4.4 Icons (SF Symbol → Material)

From `material-icons-extended` 1.7.8; the custom vectors are in `Icons.kt`, with the paths in UI spec §6.3. Size
rule: an SF Symbol at text size X becomes an icon of about 1.2·X dp. Map buttons use 24 dp icons. The exception is a list
`Label`, whose symbols are drawn larger than their text: its icons are 28 dp (`ListIconSize`; UI spec §1.3).

| SF Symbol | Android |
| --- | --- |
| `location.fill` / `location` | `Icons.Filled.NearMe` / `Icons.Outlined.NearMe` |
| `mountain.2.fill` / `mountain.2` | `Icons.Filled.Landscape` / `Icons.Outlined.Landscape` |
| `paintpalette.fill` | `Icons.Filled.Palette` |
| `airplane` | `Icons.Filled.Flight`, rotated 90° |
| `square.dashed` | custom `SquareDashed` |
| `arrow.down.circle` / `.fill` | `Icons.Outlined.ArrowCircleDown` / custom `ArrowDownCircleFill` (the arrow cut out of a disc) |
| `location.magnifyingglass` | custom `LocationMagnifyingGlass` |
| `ruler` | `Icons.Outlined.Straighten` |
| `scope` | `Icons.Filled.GpsFixed` |
| `xmark` | `Icons.Filled.Close` |
| `trash` | `Icons.Outlined.Delete` |
| `checkmark.circle.fill` / `circle` | `Icons.Filled.CheckCircle` / `Icons.Outlined.Circle` |
| `checkmark.square.fill` / `square` | `Icons.Filled.CheckBox` / `Icons.Outlined.CheckBoxOutlineBlank` |
| `arrow.uturn.backward` | `Icons.AutoMirrored.Filled.Undo` |
| `pentagon` | `Icons.Outlined.Pentagon` |
| `mappin` / `mappin.circle.fill` | custom `MapPin` / `MapPinCircleFill` |
| `doc.on.doc` | `Icons.Outlined.ContentCopy` |
| `pause.fill` / `pause.circle` | `Icons.Filled.Pause` / `Icons.Outlined.PauseCircle` |
| `magnifyingglass` | `Icons.Filled.Search` |
| `lasso` | custom `Lasso` |
| `car` | `Icons.Filled.DirectionsCar` |
| `figure.walk` | `Icons.AutoMirrored.Filled.DirectionsWalk` |
| `square.and.arrow.up` / `.down` | `Icons.Outlined.IosShare` / `Icons.Outlined.SaveAlt` |
| `arrow.clockwise.circle` | `RetryCircle` (`Icons.Filled.Refresh` 17 dp in a 24 dp ring) |
| `map` | `Icons.Outlined.Map` |
| `checkmark` | `Icons.Filled.Check` |
| `plus` | `Icons.Filled.Add` |
| (paste) | `Icons.Outlined.ContentPaste` |
| (chevron) / (back) | `Icons.AutoMirrored.Filled.KeyboardArrowRight` / `Icons.AutoMirrored.Filled.ArrowBackIos` |

### 4.5 Test tags and accessibility

How the iOS accessibility properties map to Compose:

| iOS | Compose |
| --- | --- |
| `accessibilityIdentifier` | `Modifier.testTag`. Every root sets `testTagsAsResourceId = true`: the map screen, each sheet's content and each dialog. |
| label | `contentDescription` |
| value | `stateDescription` |
| hint | `onClickLabel` |
| hidden | `clearAndSetSemantics {}` |
| `.combine` | `mergeDescendants = true` |

**Tags with the same strings as iOS:**

| Tag | On |
| --- | --- |
| `myCoordinates` | the copy button of your coordinates |
| `myHeight` | your height pill |
| `tappedCoordinates` | the copy button of the tapped spot |
| `tappedHeight` | the tapped spot's height pill |
| `tappedName` | the name capsule |
| `measuredDistance` | the distance in path mode only |
| `contourKey` | the contour legend |
| `colourKey` | the colour legend |
| `droneCard` | the drone card |
| `useFileColours` | the colours row in Blocks |
| `coordinateText` | the Go to coordinates field |
| `placeSearchText` | the place search field |

**Android-only tags**, used by the Android tests:
- the map buttons: `compassButton`, `terrainButton`, `blocksButton`, `offlineButton`, `goToButton`, `measureButton`, `myLocationButton`;
- `stopMeasuring`, `hideCoordinates`, `importKml`;
- the sheet chrome: `screenTitle`, `back`.

**Labels** (`contentDescription` of icon buttons, or the visible text) are the iOS ones:
- `Contour lines`, with `stateDescription` = the mode title;
- `Blocks`, `Offline maps`, `Go to coordinates`, `Measure`, `My location`;
- `Stop measuring`, `Undo`, `Close shape`;
- `Height`, `Scale`, `Hide coordinates`, `Import KML`, `Show <name> on the map`.

**State and hints:**
- The copy buttons' description is `Copy <text>`, then `Copied` for 2.5 s.
- Height pills have `stateDescription` "<height> above sea level" (plus ", from GPS"), `Loading`, or `No elevation data here offline`.

### 4.6 Android texts that differ from iOS

Every other string is copied from Swift exactly.

| Where | Android text |
| --- | --- |
| Location hint, denied | `Location is off for SatMap. Turn it on in Settings › Apps › SatMap › Permissions › Location.` A tap opens `ACTION_APPLICATION_DETAILS_SETTINGS`. |
| Location hint, services off | `Location is off. Turn it on in Settings › Location.` A tap opens `ACTION_LOCATION_SOURCE_SETTINGS`. |
| Approximate only | `Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › SatMap › Permissions › Location (Use precise location).` A tap opens the app details. |
| Free space | `Free space on this phone: <bytes>` |
| Low-space problem | `Less than 500 MB free on the phone. Free up space, then tap ↻.` |
| Backup footer | D12 |
| New area, mobile data footer | `Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, with its progress in a notification. If Android stops it, it continues when you open SatMap again.` |
| `backgroundStatus` | §5.2 |
| New labels | `Pause`, `Resume`, `Compass`, `Point north up`, `Back`, `Show or hide files`, `Delete`, `Save to a folder` |
| User-Agents | `SatMap/1.0 (Android)` for tiles; `SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)` for roads and places |

---

## 5. Services, permissions, manifest, files, preferences

### 5.1 Location: `LocationRecorder`, `GpsFix`, `LocationService`

```kotlin
data class GpsFix(val lat: Double, val lon: Double, val horizontalAccuracy: Double,
                  val altitude: Double?, val verticalAccuracy: Double?,   // above SEA LEVEL, null when unknown
                  val timestamp: Double,                                   // s since 1970 (TrackPoint.time)
                  val elapsed: Double) { val point get() = GeoPoint(lat, lon) }  // s, elapsedRealtime clock
```

- **Policy:** the Swift state machine with every number in `LocationPolicy` and state spec §1.9. All recorder times use `SystemClock.elapsedRealtime() / 1000.0`.
- **Altitude:** use `LocationCompat.hasMslAltitude`. If it is missing, on API 35+, warm up `AltitudeConverter` once on `Dispatchers.IO`, then call `tryAddMslAltitudeToLocation(loc)` per fix. Otherwise the altitude is null. The UI shows the GPS height only when `verticalAccuracy` is in (0, 25].
- **`applyRequests()`** replaces `applyAccuracy()`. It (re)registers only what changed:

| power | peeking | foreground | GPS (`GPS_PROVIDER`) | Network | Passive |
| --- | --- | --- | --- | --- | --- |
| active | – | yes | 2000 ms, HIGH_ACCURACY | 5 s, BALANCED, only while no GPS fix in the last 30 s | – |
| active | – | no, acquiring | 1000 ms, HIGH | the same rule | – |
| active | – | no, resting | none | – | – |
| sleeping | no | any | none | 60 s (min 30 s), LOW_POWER | interval 10 s |
| sleeping | yes | any | 1000 ms, HIGH | – | – |

  - API: `LocationManagerCompat.requestLocationUpdates` with `LocationRequestCompat`, one listener per provider, the main executor, and no batching.
  - **Passive:** a passive request's interval asks for nothing, and `LocationRequest` folds a smaller minimum into the interval, so the interval itself is the 10 s minimum.
  - **Network while searching:** the network row is registered while the GPS has had no fix for 30 s. A `WakeTimer` (`"searching"`), set for 30 s after the GPS's last fix, registers it again.
  - **Approximate only:** the GPS row uses `FUSED_PROVIDER` (or network) at LOW_POWER.
  - **Routing:**
    - GPS fixes go to `didUpdateLocations`;
    - network fixes go to `handleWhileSleeping` when sleeping, else to `didUpdateLocations`;
    - passive fixes are used only while sleeping and not peeking.
- **Timers:** every recorder timer (stationary 15 s, duty `lastUsedAt + 7 s`, peek, peek end, the 6 s motion debounce, searching 30 s) is a `WakeTimer`: `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, …, OnAlarmListener, mainHandler)`. On `SecurityException` it falls back to `set`. Do not declare `SCHEDULE_EXACT_ALARM`.
- **Motion:**
  - `TYPE_SIGNIFICANT_MOTION` (a trigger sensor, re-armed in `onTrigger`) means high confidence: wake now.
  - `TYPE_STEP_DETECTOR` (with `ACTIVITY_RECOGNITION`):
    - ≥ 6 steps in 6 s → wake now;
    - otherwise a 6 s pending wake that needs ≥ 3 steps when it fires.
  - `motionSaysMoving` = a step in the last 15 s, or significant motion in the last 60 s.
  - Without the sensors, or without the permission, the GPS peeks remain, as on iOS.
- **Compass:** `TYPE_ROTATION_VECTOR`, else `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, at `SENSOR_DELAY_UI`.
  - `remapCoordinateSystem` for `screenRotation`.
  - `GeomagneticField` declination once a position is known.
  - The 2° filter.
  - `onHeading(null)` while the reading is UNRELIABLE or NO_CONTACT.
  - `screenRotation` is set by `MapController` on configuration change and from a `DisplayManager.DisplayListener` (a 90°↔270° flip is not a configuration change).
- **`authorization`:**
  - no permission: `notDetermined` until asked (`locationPermissionAsked`), then `denied`;
  - `servicesOff` when `!LocationManagerCompat.isLocationEnabled`;
  - else `authorized`;
  - `reducedAccuracy` = coarse only.

  It is re-read on `start()`, on foreground and active, on a permission result, on provider enable/disable, and on a `MODE_CHANGED_ACTION` receiver.
- **`beginUpdates()`:** guard `!started && AppLifecycle.isForeground && authorized`. Then `LocationService.start(context)` (catch `ForegroundServiceStartNotAllowedException` and `SecurityException`, and carry on), `applyRequests()`, `startMotion()`, and the stationary timer.
- **What came when:** sleep, the peeks, the coarse-jump wake, significant motion and the compass landed in M1, with the request table above. Only the step detector is left for M5 (§7.2 lists the rest there too).
- **`setForTests(fix, authorization, reducedAccuracy)`** (`@VisibleForTesting`): for screen tests, where no fix arrives. It sets the three flows, and passes a fix on through `onFix` without recording it.

**`LocationService`** is a foreground service of type `location`, started only from the foreground (it keeps
while-in-use access in the background, so `ACCESS_BACKGROUND_LOCATION` is never requested).
- `START_NOT_STICKY`; `stopWithTask` stays false.
- The notification:
  - channel `recording` ("Track recording", `IMPORTANCE_LOW`), id 1;
  - small icon `ic_stat_location`;
  - title "SatMap", text "Recording your track";
  - ongoing, silent, no time shown;
  - the content intent opens `MainActivity`.
- Its "Stop" action (`ACTION_STOP`) calls `recorder.stop()` and `stopSelf()`. Recording starts again on the next `didBecomeActive`.

### 5.2 Downloads: `BackgroundDownload` and `OfflineDownloadService`

`OfflineManager` keeps the Swift queue, run and state machine (offline spec §1). A run is
`task = scope.launch { withContext(IO) { job.run(...) }; NonCancellable { close stores, measure size }; finished(...) }`.
Core's `TileDownloadJob.run` returns `DownloadOutcome.Cancelled` on cancellation.

**`BackgroundDownload.begin`:**
- Starts `OfflineDownloadService` (type `dataSync`) only when the app is in the foreground.
- The service calls `startForeground` first, then takes a partial wake lock for at most 6 h.
- `allowsBackgroundWork = service running && !stopping`.
- If the start is refused, a 25 s timer armed in `enteredBackground()` calls `onExpire`, as iOS's background task does.

**`status` texts:**

| When | Text |
| --- | --- |
| Started, notifications on | `Downloads keep going in the background, with their progress in the notification.` |
| Started, notifications off | `Downloads keep going in the background. Allow notifications for SatMap to see their progress there.` |
| Start refused | `In the background a download stops, and continues when you open SatMap: Android didn't allow it to keep going in the background.` |

**Notification:**
- Channel `downloads` ("Offline downloads", `IMPORTANCE_LOW`), id 2.
- Small icon `ic_download`, colour `#FFBD29`.
- Title = the area name; text = `progressText(p)`; a determinate bar carried across areas.
- Ongoing, silent, alerts once, at most once a second, `FOREGROUND_SERVICE_IMMEDIATE`.
- The content intent opens `MainActivity`; a "Stop" action goes to `ACTION_STOP`.
- On success: a non-ongoing "Ready offline" notification (id 3, timeout 8 s).

**Stop:**
- With SatMap in the foreground, Stop pauses the active area.
- In the background, it calls `backgroundTimeEnded()`: the area goes back to `queued` and continues when SatMap is opened.
- `onTimeout` (API 35, when dataSync's 6 h are used up) does the same.

**Download HTTP** (`DownloadHttp.kt`):
- **Any network:** no cache, 20 s timeouts, `maxRequestsPerHost = 32`, `ConnectionPool(32, 5, MINUTES)`, User-Agent `SatMap/1.0 (Android)`.
- **Wi-Fi only:** the same client bound to the default network as it was at run start (`socketFactory` and `dns` of `Connectivity.network`), with `HTTPTileFetcher(wifiOnly = true)`.

### 5.3 Runtime permissions (`PermissionFlow`)

- **`MainActivity.onResume` calls `next()`:**
  1. Location (`FINE` + `COARSE` together, so Android 12+ offers precise or approximate) when `notDetermined`.
  2. Then, once location is authorised: `ACTIVITY_RECOGNITION` on API 29+, if a step detector exists and the permission was never asked.
- **Each permission is asked once per install** (`*PermissionAsked` flags). An `asking` guard covers the activity resuming under the dialog.
- **`POST_NOTIFICATIONS`** (API 33+) is asked on the first Download tap, in `NewAreaView` and `AreaDetailView`, with `rememberLauncherForActivityResult(RequestPermission())`. The flag is `notificationPermissionAsked`. The download starts whatever the answer.
- **No rationale screens**, as iOS has none.

### 5.4 Manifest (`app/src/main/AndroidManifest.xml`, complete after M0)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
  <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <uses-permission android:name="android.permission.WAKE_LOCK" />
  <uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
  <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />
  <uses-feature android:name="android.hardware.location.gps" android:required="false" />
  <uses-feature android:name="android.hardware.sensor.compass" android:required="false" />

  <application android:name=".app.SatMapApplication" android:label="SatMap" android:icon="@mipmap/ic_launcher"
      android:roundIcon="@mipmap/ic_launcher" android:theme="@style/Theme.SatMap" android:supportsRtl="true"
      android:allowBackup="true" android:fullBackupContent="@xml/backup_rules"
      android:dataExtractionRules="@xml/data_extraction_rules">

    <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask"
        android:windowSoftInputMode="adjustResize"
        android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode">
      <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
      <!-- KML/KMZ: VIEW by type, VIEW by name (octet-stream + pathPattern), SEND/SEND_MULTIPLE: offline spec §2.9 verbatim -->
    </activity>
    <activity android:name=".offline.SaveToFolderActivity" android:exported="true"
        android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true" />

    <service android:name=".location.LocationService" android:exported="false" android:foregroundServiceType="location" />
    <service android:name=".offline.OfflineDownloadService" android:exported="false" android:foregroundServiceType="dataSync" />
    <service android:name=".car.SatMapCarAppService" android:exported="true">
      <intent-filter><action android:name="androidx.car.app.CarAppService" /><category android:name="androidx.car.app.category.NAVIGATION" /></intent-filter>
    </service>

    <provider android:name=".offline.BackupFileProvider" android:authorities="${applicationId}.backup"
        android:exported="false" android:grantUriPermissions="true">
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/backup_paths" />
    </provider>

    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />
    <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="2" />
  </application>
</manifest>
```

- `ACCESS_BACKGROUND_LOCATION` and `SCHEDULE_EXACT_ALARM` are deliberately absent.
- MapLibre's merged manifest adds `ACCESS_WIFI_STATE` and the location permissions, which is harmless.

### 5.5 Build and resources (done in M0)

**Gradle:**
- `libs.maplibre` → `org.maplibre.gl:android-sdk-vulkan-opengl`.
- Add `implementation(libs.car.app)`, `implementation(libs.car.app.projected)`.
- Add `testImplementation("androidx.car.app:app-testing:1.7.0")` and `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`.
- Remove `workmanager` (D21).
- `androidTest`: add `androidx.test:rules` (for `GrantPermissionRule`).
- A test task that reads files outside `android/` declares their folders as inputs, or Gradle reuses an up-to-date or
  cached result when only those files change: `inputs.dir(rootProject.file("../Tests/SatMapCoreTests/Fixtures"))
  .withPropertyName("satmapFixtures").withPathSensitivity(PathSensitivity.RELATIVE)`, and the same for
  `../SatMap/Resources` (`satmapResources`). Core's `test` task needs both (the core stream's file). The app's
  `testDebugUnitTest` needs the fixtures from M4, when its restore tests read the backup fixtures.

**`res/`:**

| Folder | Contents |
| --- | --- |
| `mipmap-anydpi-v26/` | an adaptive `ic_launcher`, drawn from `SatMap/Assets.xcassets/AppIcon` |
| `values/` | `themes.xml`: `Theme.SatMap`, a parent `android:Theme.Material.NoActionBar` with a black `windowBackground` |
| `xml/` | `backup_rules.xml`, `data_extraction_rules.xml` (exclude `SatMap/Offline/`), `backup_paths.xml` (`<files-path name="offline" path="SatMap/Offline/"/>`), `automotive_app_desc.xml` (`<uses name="template"/>`) |
| `drawable/` (vectors) | `ic_stat_location`, `ic_download`, `ic_stop`, `ic_folder`, `ic_car_add`, `ic_car_remove`, `ic_car_near_me` |

### 5.6 Files on the phone

```
filesDir/SatMap/                          (credential-encrypted: readable after the first unlock, as iOS's protection class)
  track.bin                               TrackLog, 32-byte records                     backed up
  KML/index.json, KML/<UUID>.kml|.kmz     KMLLibrary (AtomicFile)                       backed up
  Offline/                                excluded from backup and device transfer
    areas.json                            AtomicFile; an unreadable file is renamed areas.json.unreadable-<unix s>
    <UUID upper-case>/imagery.mbtiles, terrain.mbtiles (+ -wal, -shm while open)
    Restoring/                            restore staging, removed at start and after each restore
cacheDir/tiles-http/                      OkHttp disk cache, 1 GiB
getExternalFilesDir("screenshots")        androidTest only
```

There is no `Export/` folder (D8).

### 5.7 Preferences (`Defaults`)

- The file is `"${packageName}_preferences"`, private.
- Launch extras are read before the stored values and never written back: the iOS argument domain.
  - Example: `adb shell am start -n com.jacobusoettle.satmap/.MainActivity --es terrainMode colours --ez zoomScript true`.

| Key | Type | Written by | Notes |
| --- | --- | --- | --- |
| `terrainMode` | String | AppModel | `contours`, `contoursAndColours`, `colours`, `drone`, `off` |
| `showContours` | Boolean | — | legacy, read only (`TerrainMode.saved`) |
| `distanceUnit` | String | AppModel | `metric`, `imperial`, `nautical` |
| `heightUnit` | String | AppModel | `metres`, `feet` |
| `kmlFileColours` | Boolean | KMLLibrary | missing means true |
| `offlineMaxZoom` | Int | NewAreaView | default 22 |
| `camera` | String | MapController | `"lat,lon,latDelta,lonDelta"`, `Locale.ROOT`; missing means first launch |
| `zoomScript` | Boolean | — | launch extra only |
| `locationPermissionAsked`, `motionPermissionAsked`, `notificationPermissionAsked` | Boolean | PermissionFlow / UI | Android only |

### 5.8 Connectivity

```kotlin
class Connectivity private constructor(context: Context) {
    data class State(val isOnline: Boolean, val isExpensive: Boolean)
    val state: StateFlow<State>; val isOnline: Boolean
    val isOnlineNow: Boolean; val isExpensiveNow: Boolean; val network: Network?   // @Volatile snapshots, any thread
}
```

- It uses `registerDefaultNetworkCallback` on the main handler.
  - `isOnline` = `NET_CAPABILITY_INTERNET` (no VALIDATED, as iOS `.satisfied`).
  - `isExpensive` = `!NET_CAPABILITY_NOT_METERED`.
  - `onLost` → offline.
- **The first state is read synchronously.** The callback never fires when there is no network at all.
- **Consumers:**
  - `AppModel`: the online edge → `resetFailureState()` + `requestReload()`;
  - `OfflineManager`: `networkChanged()`;
  - `TileRepository` and `HTTPTileFetcher`: the snapshots;
  - the UI texts (collected).

### 5.9 Files in and out

- **KML in:** the three `MainActivity` intent filters (offline spec §2.9). `MainActivity.handleIncoming(intent)` runs from `onCreate` (when `savedInstanceState == null`) and `onNewIntent`:
  - it takes the URIs from `data` or `EXTRA_STREAM`;
  - for each: `model.kml.importFile(uri) { f -> f?.document?.bounds?.let { model.map?.zoom(it) } ?: model.setSheet(ActiveSheet.blocks) }`;
  - then it clears the intent action;
  - it does nothing for an intent replayed from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, `MainActivity.isReplay`): clearing the action doesn't change the task's base intent, which Recents relaunches with on API 26–30 after Back finished the activity.
- **Export:**
  1. `OfflineManager.export(ids)` holds downloads and pauses an exported area that is downloading.
  2. `OfflineBackup.prepareExport` (core) writes the metadata and checkpoints.
  3. The files are registered in `ExportShares` (URI → area, file, display name).
  4. `SharePresenter.present(ShareRequest(uris) { finishExport() })`.
  5. `ShareHost` launches `Intent.createChooser(ACTION_SEND_MULTIPLE, …)` with `FLAG_GRANT_READ_URI_PERMISSION`, plus "Save to a folder" (`EXTRA_CHOOSER_CUSTOM_ACTIONS` on API 34+, `EXTRA_INITIAL_INTENTS` with `SaveToFolderActivity` below).
  6. The chooser's activity result calls the completion.
  7. "Save to a folder" arrives at `MainActivity` as `ACTION_SAVE_TO_FOLDER`, which calls `SharePresenter.requestFolder()`. `ShareHost` then launches `OpenDocumentTree`, and `BackupSharing.saveToFolder(tree)` copies each registered file in 4 MiB pieces, with `backupStatus`/`backupProgress`.
  - **Readers hold their area:**
    - while a provider reader or the "Save to a folder" copy has an area's files open, that area is not started, and it is paused if it is downloading;
    - the last close resumes it and calls `startNextIfIdle()`;
    - the registry is cleared on the next export and when the area is deleted.
- **Restore:** `OpenMultipleDocuments(arrayOf("*/*"))` → `offline.restore(uris)`. Each file is copied through `ContentResolver` in 4 MiB pieces into `Restoring/`, with progress.

---

## 6. Android Auto

- **Library:** `androidx.car.app:app:1.7.0`, NAVIGATION category (the only category whose app draws its own map on the car's `Surface`), `minCarApiLevel` 2.
- **`SatMapCarAppService`:**
  - `createHostValidator()` = `ALLOW_ALL_HOSTS_VALIDATOR` in debuggable builds, otherwise the library's `hosts_allowlist_sample`;
  - `onCreateSession()` returns `CarSession`.
- **`CarSession`:**
  - `init` calls `AppLifecycle.observeCar(this)`, so the car screen counts as foreground: recording starts and nothing is trimmed;
  - `onCreateScreen` returns `CarMapScreen`.
- **`CarMapScreen`** returns a `NavigationTemplate`:
  - **Action strip:** one titled action with the short terrain title (`Contours`, `Contours + colours`, `Colours`, `Drone`, `No contours`). It calls `model.setTerrainMode(next)`.
  - **Map action strip:** zoom in, zoom out, follow, and `Action.PAN`.
  - **Pan mode:** `setPanModeListener { if (it) map.dragBegan() }`.
  - **Refresh:** `invalidate()` on each terrain mode change, collected on `Dispatchers.Main`.
  - **Lifecycle:** `onStart`/`onStop` set `map.isShown` (`MapView.onResume`/`onPause`). `onDestroy` clears `model.carMap` if it is this map.
- **`CarMapController : SurfaceCallback, CarMapHost`:**
  - **`onSurfaceAvailable`:**
    - `createVirtualDisplay(…, c.width, c.height, c.dpi, c.surface, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)`;
    - a `Presentation` with a `MapView(options.textureMode(true), rotate and tilt off)`, `pixelRatio = c.dpi / 160f`;
    - `setStyle(MapStyle.builder(DrawnImagery(...)))` with the car screen's contour scale
      (`TileLevels.contourScale(pixelRatio)`), then `installOverlays`;
    - `BlockLayers`, `TrackLayer`, `UserDotLayer` and `AviationLayer`, bound to the same model flows as the phone (on `Dispatchers.Main`);
    - zoom limits 3–20, which stand in for MapKit's 150 m – 4,000 km camera clamp.
  - **`onSurfaceDestroyed`:** `MapView` pause/stop/destroy, dismiss the presentation, release the display.
  - **Visible area:** `onVisibleAreaChanged` → `setPadding` (the car map only).
  - **Pan:** `onScroll` → `following = false`, `scrollBy(-dx, -dy)` (check the sign on the DHU).
  - **Zoom:** `onScale` → `zoomBy(log2 f, focus)`; a negative focus means the host's own zoom, around the centre.
  - **Ignored:** `onFling`, `onClick`.
  - **Start:** your position, else the phone map's centre, else (−24.99, 31.59), ±750 m. It follows until moved.
  - **Model calls:** `locationUpdated(fix)` moves the dot and eases onto you when following and shown; `reload()` → `imagery.reload()`.

---

## 7. Build order and work split

### 7.1 Work packages and the files they own

| WP | Area | Owns |
| --- | --- | --- |
| **A** | State, location, shell | `MainActivity.kt`, `app/*`, `location/*`, `AndroidManifest.xml`, `res/**`, `build.gradle.kts`, `libs.versions.toml`, `src/test/.../app/*`, `src/test/.../location/*`, `TestApplication.kt` |
| **B** | Tiles and car | `map/TileRepository.kt`, `map/TileInterceptor.kt`, `map/SatelliteLayer.kt`, `map/DrawnImagery.kt`, `map/TileOverlays.kt`, `car/*`, their tests |
| **C** | Map interaction and overlays | `map/MapStyle.kt`, `map/MapController.kt`, `map/MapAnnotations.kt`, `map/DynamicOverlays.kt`, `map/BlockLayers.kt`, `map/AviationLayer.kt`, `map/PointAdjuster.kt`, `map/ZoomScript.kt`, their tests |
| **D** | Screens | `ui/*` except `ShareSheet.kt`, their tests |
| **E** | Offline, KML, sharing | `offline/*`, `kml/*`, `ui/ShareSheet.kt`, their tests |
| **F** | Verification | `src/androidTest/**`, `.github/workflows/android*.yml`, `docs/screenshots/android/` |

The core port (`android/core`) is a separate stream. It must deliver, in this order:

| Core piece | Needed by |
| --- | --- |
| `SqlDriver` + `MBTiles` | M1, for offline reading (can slip to M4) |
| `CoordinateParser` | M3 |
| `DownloadEngine`, `OfflineBackup` (with `prepareExport`), `Roads`, `Places` | M4 |
| `Aviation` | M5 |

### 7.2 Milestones

Every milestone ends with a green CI (core tests, `:app:testDebugUnitTest`, `assembleDebug`) and an installable
APK that does what its row says.

| M | Goal | A | B | C | D | E | F | Done when |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **M0** Skeleton (one engineer, before the others start) | Every file of §1 exists with the §2.5 signatures as stubs; the complete manifest, resources and Gradle changes; `SatMapApplication` init order; `MainActivity` → `MapScreen` with a `MapView` showing the background colour; `.github/workflows/android.yml` | ✔ | | | | | ✔ | The app launches; all tests pass. |
| **M1** Satellite map | You see where you are on imagery | `AppLifecycle`, `Defaults`, `AppPaths`/`Format`, `Connectivity`, `AppModel` (following, units, track, height lookups, `centerOnUser`), recorder foreground + background duty cycle, `LocationService`, `PermissionFlow` | `TileRepository` (network, memory, limiter, pause, DEM, `elevation`), interceptor imagery route, `SatelliteRenderer` imagery, `ImageryPrefetch`, `DrawnImagery` (imagery only) | `MapStyle` base + user + track, `MapController` (camera save/restore, following, listeners, scale, bearing, `isShown`), `UserDotLayer`, `TrackLayer` | `Theme`, `Form`, `MapScreen` shell, controls, `CompassButton`, `CoordinateBar`, `HeightBar`, `ScaleBarView`, `Hint`, clear-track dialog | `KMLLibrary` and `OfflineManager` logic with unit tests (no UI) | Emulator workflow with mock locations; walkthrough step `01-map` | Imagery loads and zooms without blanks; your dot moves; the track draws; coordinates and height show; the camera is restored. |
| **M2** Terrain | Contours and colours | `requestReload`, retry timer, `probeRecovery` wiring | contour and colour routes, `ContourRenderer`, `HeightGrids`, colour range, layer replacement, legend key, `reload()` | terrain hooks | `ContourKeyView`, `ColourKeyView`, terrain button and toast | | steps `22`, `23`, `11` | The terrain button cycles; legends match `01-map`/`22`/`23`. |
| **M3** Measure, select, blocks | iOS screenshots 02–05, 15, 16, 19, 21, 24 | measuring state machine, `CrosshairAim`, `movePoint`, `measure(from)`, incoming KML intents | `drawImagery` for the loupe | taps, measure layers, `AimLineView`, selected pin, `BlockLayers`, `MapImages`, `PointAdjuster` + loupe | `AimPanel`, `MeasurePanel`, `Crosshair`, tapped spot, `BlocksSheet`, `SheetHost`, `CoordinateEntryView`, Blocks menu | KML import from picker and intents | steps 02–05, 15, 16, 19, 21, 24, and the asserts | The walkthrough passes up to step 21. |
| **M4** Offline | iOS screenshots 06–10, 12–14, 17, 18, 20, 26, 27 | | offline-area reading, `setAreas`, `closeArea` | `LassoView`, `DraftAreaLayer` | `OfflineSheet` and all routes, `NewAreaView`, `AreaDetailView`, `DownloadBanner`, `DrawingBar` | `OfflineManager` runs, service + notification, `DownloadHttp`, `RoadFetcher`, `PlaceSearch`, `AndroidSqlDriver`, export/restore, `ShareSheet` | the offline steps | A KML area downloads to "Ready offline" and shows in airplane mode; export opens the chooser; a restore of the iPhone fixture works. |
| **M5** Drone, car, power | iOS screenshot 25; Android Auto; the full battery policy | sleep / peek / motion / compass, `updateDroneReport` | `CarSceneDelegate`, `CarMapController` | `AviationStore`, `AviationLayer`, `AviationCard`, `ZoomScript` | `DroneCard` | | step 25; `ZoomTest` | Drone mode matches 25; the car map works on the DHU (manual); the recorder sleeps after 120 s still (Robolectric). |
| **M6** Parity pass | Side-by-side review of every Android screenshot with `docs/screenshots/*.jpg`; fix the differences | all | all | all | all | all | screenshots committed | No unexplained visual or text difference remains. |

### 7.3 Working in parallel

1. **One owner per file** (§7.1). Nobody edits another package's file. A needed change (a new parameter, a new flow) goes to the owner. The requester codes against the agreed signature meanwhile; the owner adds it as a stub within a day.
2. **§2.5 is the contract.** Changing it means changing this document in the same pull request, reviewed by the owners on both sides.
3. **Integration files** (manifest, Gradle, `res/`, `MainActivity`) belong to WP-A and are complete after M0. Late additions are requested from WP-A.
4. **`MapStyle.kt`** (WP-C) holds every layer definition, including the terrain anchors. WP-B's only entry point into the style is `DrawnImagery.addTo(builder)` and its own replacement of `imagery-*`/`colours-*`/`contours-*` layers.
5. **`MapScreen.kt`** (WP-D) calls `MapViewRepresentable` (WP-C) and `ShareHost` (WP-E). Both exist as stubs from M0.
6. **Branches:** one branch per WP per milestone, merged into the integration branch when CI is green. WP-F runs the emulator walkthrough on the integration branch after each merge.
7. **Fakes for testing without the others:**
   - `ElevationSource`, `MapHost` and `CarMapHost` (AppModel tests);
   - a fake `TileFetching` and an injectable clock (TileRepository and OfflineManager tests);
   - a fake `Connectivity.State` source (constructor parameter);
   - `SatelliteRenderer.answer` passed into `TileInterceptor` as a function parameter (interceptor tests).

---

## 8. Test plan

### 8.1 Unit tests (`./gradlew :app:testDebugUnitTest`, Robolectric `sdk=35`, `TestApplication`)

MapLibre's native code can't run here, so testable logic lives in pure functions (companion objects) or in
classes that don't touch `MapLibreMap`. `org.maplibre.geojson` types are plain Java and can be used.

| Package (WP) | Tests |
| --- | --- |
| app (A) | `AppModel`: the measuring transitions table (state spec §1.3) with a fake `MapHost` for the 6/20 dp thresholds; undo back to aiming; the elevation resets in `setMeasure` and `movePoint`; `selectedName` clearing; units and terrain mode written to and read from the same keys; `TerrainMode.saved` with legacy `showContours`; `requestReload` at most every 20 s and pending while in the background. `CrosshairAim`/`HeightLookup` with `runTest` virtual time: one lookup at a time, 31 s retries capped at 4, none while inactive, a session bump drops a late result. `Format` (`bytes`, `hectares`, `groundResolution`, `coordinate` under a German default locale). `AppLifecycle` merging phone and car states. `Connectivity` with `ShadowConnectivityManager`: the initial offline state, the online edge. |
| location (A) | `LocationRecorder` with `ShadowLocationManager`, `ShadowAlarmManager` (fire the listener alarms) and `ShadowSystemClock`: the 2 s foreground gate; the background +7 s re-register, a stale cached fix rejected, acceptance at ≥ 9.5 s; sleep after 120 s; no sleep within 600 s without a good fix; peek intervals 60/120/15 s and lengths 15/45 s; wake thresholds 8 m/1.5× and 20 m/2×; the coarse jump; the precise-fix rule after a wake; track spacing and segment breaks; `measuring = true` starting a peek; the request table (§5.1) per state. |
| map tiles (B) | `TileLevels` against iOS's formulas (zoom 0–21; pixel ratios 1, 2, 2.625, 3, 3.5; tolerances in rendering spec §2.13); `contourMinZoom`; `TileUrls` round trip; `RequestLimiter` order and cancellation; `TileRepository.nearestImagery` with a fake fetcher and fake offline stores (beyond depth, failure → offline ancestors only, the network pause after 8 failures); the interceptor answer table through a real `OkHttpClient` (codes, `Cache-Control`, the 1×1 blank, cancellation); contour colour ints and widths; `colourPng` edge pixels. |
| map interaction (C) | `PointAdjuster.place`, `fingerGround`, the 5 m clamp and scroll step; `MapController.entry` (the aim line's entry and dash phase); camera string round trip; `BlockLayers.shapes` (every style case, width clamp, white when colours are off); `AviationLayer` selection (km filter, 15 % padding, cap of 140); `AviationCard.text`; the lasso's 3 dp step + Douglas–Peucker; `MeasureMode.label`. |
| ui (D) | Compose tests under Robolectric with `MapScreen(map = null)`: panel texts for each measure state; `HeightBar` semantics (`above sea level`, ` ft `, ` m `); `CoordinateBar` "Copied" for 2.5 s; terrain toast 1.8 s; `NewAreaView` chosen zoom (`deepestFitting`, `rememberedZoom`) and size texts; `AreaDetailView` button titles; the back stack keeping route state. |
| offline, kml (E) | The `OfflineManager` state machine (offline spec §2.14): queue order, pause winning, re-queue during a run, Wi-Fi-only waiting, retry delay 15→600, low-space stop, the preset guard, unreadable `areas.json`, orphan folders, `downloading` → `queued` at load. Export display names and the reader hold; restore of `Tests/SatMapCoreTests/Fixtures/backup/iphone`, a satellite-only file, a duplicate, a non-backup. `KMLLibrary`: `.kmz` when the data starts with `PK`, `hidden` round trip, the `kmlFileColours` default, delete, an import during the initial load. `RoadFetcher` and `PlaceSearch` with `MockWebServer`: the 12 s stagger, the first answer winning, the error texts, percent-encoding, `lat`/`lon` with a point under a German locale. |
| car (B) | `app-testing`: `ScreenController(CarMapScreen)` → one `NavigationTemplate` titled `Contours`; after a click, `Contours + colours`; the map strip has 4 actions, the last `TYPE_PAN`. `CarMapController` camera logic against a fake map. |

### 8.2 Instrumented screenshot walkthrough (emulator in GitHub Actions)

**Workflow** `.github/workflows/android-screenshots.yml`:
- Triggered by `workflow_dispatch` and pushes to `android/**`, with concurrency per ref.
- Runs on `ubuntu-latest`, with KVM enabled through the udev rule.
- **Emulator:** `reactivecircus/android-emulator-runner@v2`, API 35 `google_apis` x86_64, profile `pixel_7` (1080×2400 at 2.625), `-gpu swiftshader_indirect -no-snapshot`, 4 GB RAM.
- **Before the test:**
  1. `./gradlew :app:installDebug :app:installDebugAndroidTest`.
  2. `adb shell appops set com.jacobusoettle.satmap android:mock_location allow`.
  3. Seed the demo farm blocks with `run-as`:
     - copy `SatMapUITests/DemoFarm.kml` to `files/SatMap/KML/6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F.kml`;
     - write `index.json` the same way the iOS workflow does.
  4. Put the status bar in demo mode: `sysui_demo_allowed`, clock 09:41, full battery and Wi-Fi, no notifications.
- **Run:** `am instrument -w -e class com.jacobusoettle.satmap.ScreenshotTest com.jacobusoettle.satmap.test/androidx.test.runner.AndroidJUnitRunner`, plus `adb logcat -s tiles location offline` to a file.
- **After the run:**
  1. `adb pull /sdcard/Android/data/com.jacobusoettle.satmap/files/screenshots`.
  2. Resize to 600 px wide (Pillow).
  3. Upload the artifact.
  4. Commit to `docs/screenshots/android/NN-name.jpg` on the branch, with the same retry loop as the iOS workflow.

**`ScreenshotTest.kt`** mirrors `SatMapUITests/ScreenshotTests.swift` step for step, with the same snapshot names
(01-map … 27-place-area), the same waits, the same asserts and continue-after-failure (soft asserts collected and
reported at the end).

| iOS | Android |
| --- | --- |
| `XCUIDevice.location` | `TestLocations`: `LocationManager.addTestProvider` for `gps` and `network`, `setTestProviderEnabled`, and `setTestProviderLocation` with accuracy 5, `time = now`, `elapsedRealtimeNanos = now`. `walk()` sets one point every 2.4 s. |
| permission prompts | `GrantPermissionRule.grant(FINE, COARSE, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS)`. The flow then has nothing to ask; a UiAutomator fallback taps "While using the app". |
| `app.launch()` | `createEmptyComposeRule()`, then `ActivityScenario.launch(MainActivity::class.java)` in the test body, after the first mock location is set (as iOS sets the location before launching). |
| `app.buttons["Measure"]`, `["useFileColours"]` | Compose test APIs: `onNodeWithTag(…)`, `onNode(hasContentDescription(…) and hasClickAction())`, `onNodeWithText(…)`, polled with `waitUntil(timeoutMillis)` (the `waitForExistence` equivalent). |
| `navigationBars["Blocks"]` | `onNodeWithTag("screenTitle")` with text `Blocks` |
| `.value` (`above sea level`, `ft`, the terrain button) | `SemanticsProperties.StateDescription` |
| `.label` (`Copied`, `measuredDistance` units) | `ContentDescription` / `Text` |
| map taps at normalised offsets | `UiDevice.click(x·W, y·H)` |
| `press(forDuration:thenDragTo:)`, `dragMap` | `Gestures.pressAndDrag(from, to, holdMs)`, built from `MotionEvent`s through `UiAutomation.injectInputEvent` (down, hold, 20 moves, up) |
| "Save to Files" in the share sheet | `UiDevice.wait(Until.hasObject(By.text("Save to a folder")), 30 s)` |
| `XCUIScreen.screenshot()` | `UiDevice.takeScreenshot(File(targetContext.getExternalFilesDir("screenshots"), "$name.png"))` |

`ZoomTest.kt` launches with `--ez zoomScript true` and each terrain mode. It runs in
`.github/workflows/android-zoomtest.yml`, which records the screen with `adb shell screenrecord` and uploads the
videos.

### 8.3 What CI can't test (checked by hand, and noted in the pull request)

- **Android Auto rendering and input.** The DHU needs Android Auto on a Play phone. Check by hand:
  - that `Presentation` + `textureMode` renders;
  - the sign of `onScroll`;
  - the negative focus in `onScale`;
  - the visible-area padding;
  - that the car session counts as foreground for location.
- **Real power behaviour:**
  - alarms while the CPU sleeps, and Doze deferral;
  - significant-motion and step wakes;
  - GPS duty cycling and battery use over hours;
  - the compass and its declination.
- **Long background downloads:**
  - dataSync's 6 h `onTimeout`;
  - the system killing the process and the restart on open;
  - the notification Stop in the background;
  - Wi-Fi-only binding when Wi-Fi drops.
- **Real share targets:** Drive, Quick Share, Gmail, USB drives through "Save to a folder", and KML arriving from WhatsApp or Gmail with odd MIME types.
- **Device and OEM differences:** Settings paths in the hints, battery killers, GL drivers, `onTrimMemory` under real pressure, Android 12–13 refusing exact alarms.
- **Visual parity.** It is judged by eye against `docs/screenshots/*.jpg` (M6), not by pixel diff: fonts (Roboto against SF), materials (the loupe rim) and the status bar differ.
- **A backup made on a real iPhone, restored on a real phone, and the reverse.** The fixtures cover the format only.
