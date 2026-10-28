# SatMap for Android: porting guide

The Android app is the iPhone app (`../SatMap`, Swift) rebuilt for Android, feature for feature: the same screens,
texts, numbers, colours, behaviour and battery policy. Where the two differ it is only because Android needs it
(and the difference is written down in the code where it happens). When in doubt, read the Swift and do what it
does.

## Layout

```
android/
  core/   com.jacobusoettle.satmap.core   ← SatMap/Core: platform-independent logic, plain Kotlin/JVM
          src/test                        ← Tests/SatMapCoreTests/CoreTests.swift, ported test for test
  app/    com.jacobusoettle.satmap        ← SatMap/App, Map, Location, Offline, KML, UI, CarPlay
          …/app        AppModel, Config (TileSources, LocationPolicy), AppPaths/Format, Connectivity, MainActivity
          …/location   LocationRecorder (+ a foreground service for recording in the background)
          …/map        MapController (MapLibre), TileRepository, tile interceptor, contour/colour tiles, overlays,
                       aviation layer, point adjuster (loupe)
          …/offline    OfflineManager, download service, RoadFetcher, PlaceSearch
          …/kml        KMLLibrary
          …/ui         Compose screens (MapScreen, sheets)
          …/car        Android Auto (CarPlay's counterpart)
```

One Kotlin file per Swift file, with the same name (`GeoMath.swift` → `GeoMath.kt`) and the same type, function
and property names (Swift `camelCase` is Kotlin's too). Keep the doc comments, translated where they mention iOS
APIs; match the Swift's comment density and wording style (short, plain sentences).

Shipped data is shared, not copied: the app's assets come from `../SatMap/Resources` (Aviation.tsv,
KrugerRoads.json), and the core tests read `../SatMap/Resources` and `../Tests/SatMapCoreTests/Fixtures` through
the system properties `satmap.resources` and `satmap.fixtures`.

## Kotlin for the Swift

| Swift | Kotlin |
| --- | --- |
| `struct` (value type) | `data class` with `val`s (copy with `.copy(…)`); a mutating struct → `data class` with `var`s, or a class when it is passed around and mutated |
| `enum` without payloads | `enum class` |
| `enum` with associated values | `sealed class`/`sealed interface` with `data class`/`data object` cases |
| caseless `enum` as a namespace (`enum Geo { static func … }`) | `object Geo { fun … }` |
| `final class` | `class` |
| `protocol` | `interface` |
| `extension` | extension functions/properties, or members when in the same file |
| `Double`, `Int`, `Bool`, `String` | the same (Swift `Int` is 64-bit: use `Long` where values can pass 2³¹, e.g. byte counts, tile counts at deep zooms; `Int` for tile x/y/z) |
| `Data` / `[UInt8]` | `ByteArray` |
| `TimeInterval` (seconds) | `Double` seconds, same names |
| `Date` | `Double` seconds since 1970 (or `Long` ms where the Swift stores ms) |
| `UUID` | `java.util.UUID` |
| optionals | nullable types |
| `throws` | exceptions (a Kotlin exception class per Swift `Error` type) |
| `async`/`await`, tasks | coroutines (`suspend`, `Dispatchers.IO/Default`) |
| `DispatchQueue`/locks | coroutines, `Mutex`, or `synchronized` |
| Foundation `XMLParser` | `javax.xml.parsers.SAXParserFactory` (on the JVM and on Android) |
| zlib / own `Inflate` | `java.util.zip.Inflater` |
| own `ZipArchive` | `java.util.zip` (same API as the Swift type exposes) |
| SQLite C API (MBTiles) | a small `SqlDriver` interface in core: sqlite-jdbc in the core tests, `android.database.sqlite` in the app |
| `JSONSerialization`/`Codable` | `org.json` is Android-only, so in core: a small JSON reader/writer of our own (or kotlinx-serialization if it keeps the code simpler) |
| `UserDefaults` | `SharedPreferences` (same keys) |
| `@Published` / `ObservableObject` | `StateFlow` (`MutableStateFlow` private, `asStateFlow()` public) |
| SwiftUI | Jetpack Compose, Material 3 |
| MapKit | MapLibre Native Android (see below) |
| Core Location | `LocationManager` (GPS provider; no Google Play services) |
| Core Motion activity | significant-motion / step sensors |
| URLSession | OkHttp (the same client MapLibre uses) |
| background URLSession / continued processing task | a foreground service with a progress notification |
| share sheet / Files | `Intent.ACTION_SEND_MULTIPLE` / Storage Access Framework |
| CarPlay | Android Auto (Car App Library) |

Numbers, thresholds, timings, strings and colours are copied exactly. Floating-point maths is ported operation for
operation so results match the Swift tests to the same tolerances.

## Files shared with the iPhone app

Offline map backups move between the two apps (export on one phone, restore on the other), so the Android app
writes and reads exactly what the iPhone app does:

- An area's folder holds `imagery.mbtiles` and `terrain.mbtiles` (MBTiles: tables `metadata`, `tiles`,
  `missing_tiles`, as `MBTiles.swift` creates them, WAL journaling). On Android, open them with
  `SQLiteDatabase.NO_LOCALIZED_COLLATORS`, so no `android_metadata` table is added.
- Export: the area's settings go into each file's metadata (`satmap_area`: the area JSON, `satmap_layer`:
  `imagery`/`terrain`), the write-ahead log is folded in (`PRAGMA wal_checkpoint(TRUNCATE)`, no `-wal` file left),
  and the files are shared as "<name> - satellite.mbtiles" and "<name> - elevation.mbtiles"
  (`OfflineBackup.fileSafe`, " (2)" for repeated names).
- The area JSON is Swift's `JSONEncoder` output for `OfflineArea` (`SatMap/Core/OfflineBackup.swift`): keys as the
  property names; `id` an upper-case UUID; `created` in seconds since 1 January 2001 (Unix time − 978307200);
  whole-number fields (`maxZoom`, `failedTiles`, `bytesOnDisk`) written without a decimal point (Swift refuses
  `18.0` for an `Int`); `status` one of queued/downloading/paused/waitingForNetwork/complete/incomplete; properties
  that aren't set (`roads`, `preset`, `isTrack`, `problem`) left out; `shape` = {polygons, lines, points,
  bufferMeters} with points as {lat, lon}. Reading accepts any key order, lower-case UUIDs, numbers with or without
  decimals, and `null` for a value not set.
- Restore accepts the files from any app or folder (they may have been through Google Drive, a computer, e-mail …):
  any file name, chosen together in one pick; the files say which area and layer they are.
- Tests on both sides read a backup made by the other app: `Tests/SatMapCoreTests/Fixtures/backup/iphone` (made by
  the iPhone code) and `…/backup/android` (made by the Android code).

## The map on Android

MapKit + the app's own drawn overlays become **MapLibre Native** with a style built in code:

- Every tile the map shows goes through the app: the style's sources use `https://satmap.local/<layer>/{z}/{x}/{y}`
  URLs, and an OkHttp interceptor (set with `HttpRequestUtil.setOkHttpClient`) answers them from `TileRepository`:
  memory → offline areas (MBTiles) → network, and where a place has no imagery that deep, the best coarser tile
  enlarged (the Swift `TileFallback`), so the map never goes blank. Other requests pass through untouched.
- Contour lines and elevation colours are drawn on the phone exactly as on iOS (Terrarium tiles → marching squares /
  colour table) into transparent tile bitmaps served the same way, on raster layers above the imagery.
- Your position, track, KML blocks, measuring lines and points, the tapped pin and the aviation places are
  GeoJSON sources with line/fill/circle/symbol layers (or views over the map where MapLibre can't draw them).
- The Compose UI (panels, buttons, sheets) sits over the `MapView`, as SwiftUI sits over the `MKMapView`.

## Building and testing

```
export ANDROID_HOME=$HOME/android-sdk        # with platforms;android-36 and build-tools;36.1.0
cd android
./gradlew :core:test                         # the ported core tests (JVM, fast)
./gradlew :app:testDebugUnitTest             # app logic (Robolectric)
./gradlew :app:assembleDebug                 # the APK
```

No Android emulator runs on the Linux build machine (no KVM): the app is run and screenshotted on an emulator in
GitHub Actions (`.github/workflows/android*.yml`).
