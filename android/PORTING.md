# Spoor for Android: porting guide

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
| `Date` | `Double` seconds since 1970 (or `Long` ms where the Swift stores ms); a `Date` in the backup JSON (`OfflineArea.created`) keeps Swift's seconds since 1 January 2001, so it is written back unchanged |
| `UUID` | `java.util.UUID` |
| optionals | nullable types |
| `throws` | exceptions (a Kotlin exception class per Swift `Error` type) |
| `async`/`await`, tasks | coroutines (`suspend`, `Dispatchers.IO/Default`) |
| `DispatchQueue`/locks | coroutines, `Mutex`, or `synchronized` |
| Foundation `XMLParser` | `javax.xml.parsers.SAXParserFactory` (on the JVM and on Android) |
| zlib / own `Inflate` | `java.util.zip.Inflater` |
| own `ZipArchive` | `java.util.zip` (same API as the Swift type exposes) |
| SQLite C API (MBTiles) | a small `SqlDriver` interface in core: sqlite-jdbc in the core tests, `android.database.sqlite` in the app |
| CryptoKit `AES.GCM` (`TileCrypto`) | `javax.crypto.Cipher` `"AES/GCM/NoPadding"` with `GCMParameterSpec(128, nonce)` and `updateAAD` |
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
- Tiles are stored encrypted (`TileCrypto`), so files copied off the phone can't be shown in another app.
  A sealed tile (`tile_data`) is the bytes `S` `M` `T` `0x01` (magic and format version 1), a 12-byte random nonce,
  the AES-256-GCM ciphertext and its 16-byte tag. The associated data is the UTF-8 text `satmap/{z}/{x}/{y}` with the
  XYZ tile (`TileID`'s, not the TMS `tile_row`), so a tile can't be moved to another place. The key is the app's one
  256-bit key, the same bytes in both apps (in `TileCrypto.swift` and `TileCrypto.kt`, two 32-byte halves XORed), so
  backups restore on either. Swift uses CryptoKit (OpenSSL's EVP on Linux, for the tests), Kotlin `javax.crypto`;
  both give the same bytes for the same nonce (`TileCryptoTests`, with a fixed nonce through the internal
  `seal(data, tile, nonce)`).
- `MBTiles.put` seals every tile, imagery and elevation, unless it already starts with the magic (a tile server may
  deliver tiles sealed in this format; they are stored as they come). `MBTiles.tile` opens sealed tiles and returns
  plain ones as they are: files from before tiles were encrypted hold plain tiles, and a file may hold both, as no
  image format starts with the magic (JPEG `FF D8`, PNG `89 50`, WebP `RIFF`, GIF `GIF8`). A tile that doesn't open
  is missing (logged once per store).
- Metadata `satmap_encrypted` = `1` marks a file whose tiles are all sealed: set when a file is created (a new file,
  or one without tiles), and by `MBTiles.encryptPlainTiles`, which seals an old file's plain tiles in place, in
  batches in rowid order (each a transaction; the one that finds no rows left sets the mark), overwriting the plain
  tiles' space with zeros (`secure_delete`). It is resumable: each batch also writes `satmap_encrypt_after` (the last
  rowid checked) and `satmap_encrypt_checked` (the rows checked so far), so a run that stopped, also in an earlier
  launch, carries on there without reading the sealed tiles again; the batch that sets the mark removes both. They
  hold in a copy of the file too: nothing renumbers rowids, and tiles added later get higher ones, sealed. It ends
  with a checkpoint (`wal_checkpoint(TRUNCATE)`): only that takes the plain pages out of the database file (until then
  the sealed ones are only in the write-ahead log, which also keeps earlier copies of the pages). If the checkpoint
  can't finish it returns false, to run again later; on a marked file it only checkpoints. A batch's `BEGIN IMMEDIATE`
  and the checkpoint wait for another connection while holding the store's lock, so with a busy timeout of 200 ms
  (then 10 s again): a download writing through another connection holds up the store's other calls (the map's reads,
  if it runs on the map's store) by little more than a batch, and it returns false. It runs best on the area's
  download store while one is open (its writes are committed before each batch).
- Export: the area's settings go into each file's metadata (`satmap_area`: the area JSON, `satmap_layer`:
  `imagery`/`terrain`), the write-ahead log is folded in (`PRAGMA wal_checkpoint(TRUNCATE)`, no `-wal` file left),
  and the files are shared as "<name> - satellite.mbtiles" and "<name> - elevation.mbtiles"
  (`OfflineBackup.fileSafe`, " (2)" for repeated names).
- The area JSON is Swift's `JSONEncoder` output for `OfflineArea` (`SatMap/Core/OfflineBackup.swift`): keys as the
  property names; `id` an upper-case UUID; `created` in seconds since 1 January 2001 (Unix time − 978307200);
  numbers as `JSONEncoder` writes them (`swiftJSONNumber`: "150", "1e-07"), whole-number fields (`maxZoom`,
  `failedTiles`, `bytesOnDisk`) without a decimal point; `status` one of
  queued/downloading/paused/waitingForNetwork/complete/incomplete; properties that aren't set (`roads`, `preset`,
  `isTrack`, `problem`) left out; `shape` = {polygons, lines, points, bufferMeters, polygonBufferMeters} with points
  as {lat, lon}. `polygonBufferMeters` (the buffer around the polygons, when it isn't `bufferMeters`) is written only
  when set, as Swift's synthesized `Codable` leaves out nil: a KML or GPX file's area (`FileShapes.downloadShape`) has
  `"bufferMeters":15,"polygonBufferMeters":0`; every other area (and every area saved before it) has none, and a
  missing or null one means `bufferMeters`. An older version that doesn't know the key takes 15 m around a file's
  areas too. A file's area saved along roads keeps its lines, and each dot as a one-point line, in `roads`.
  Reading accepts any key order, lower-case UUIDs, numbers with or without decimals, and `null` for a value not set,
  and is otherwise as strict as Swift's `JSONDecoder`: restore reads the text with `OfflineArea.decode`.
- Restore accepts the files from any app or folder (they may have been through Google Drive, a computer, e-mail …):
  any file name, chosen together in one pick; the files say which area and layer they are. Backups from before tiles
  were encrypted (plain tiles) restore as they are, and are then sealed in place (`encryptPlainTiles`).
- Tests on both sides read a backup made by the other app: `Tests/SatMapCoreTests/Fixtures/backup/iphone-encrypted`
  (made by the iPhone code) and `…/backup/android-encrypted` (made by the Android code), with sealed tiles; and
  `…/backup/iphone` and `…/backup/android`, made the same way before tiles were encrypted (plain tiles), which show
  that old backups still restore (and are sealed by `encryptPlainTiles`). They also write a file's shape as the same
  text (`…/Fixtures/file-area-shape.json`, keys sorted: Swift's `JSONEncoder` puts them in a different order each run).

## Group formats

Groups (shared positions, trails and pins) are specified in `../docs/groups/SPEC.md`, the single source of truth:
the byte layouts, JSON keys, texts and numbers are there, and both cores follow it exactly. The core files have the
same names on both sides (`GroupWire`, `GroupCrypto`, `GroupRecords`, `Deflate`, `InviteLink`, `WordFilter`,
`GroupAPI`, `GroupStore`, `GroupHub`, `FakeGroupServer`, `GroupDemo`), and both cores' tests read the shared vectors
in `../Tests/SatMapCoreTests/Fixtures/groups/vectors.json` (made by `../docs/groups/vectors.py`; never edited by
hand), so the two apps seal, open, encode and decode the same bytes.

- Crypto: AES-256-GCM envelopes (`0x01`, 12-byte nonce, ciphertext, tag) with `javax.crypto`, as `TileCrypto` does,
  and HKDF-SHA256 (RFC 5869) on `Mac.getInstance("HmacSHA256")`.
- Trail chunks: raw DEFLATE with `Deflater(6, true)`; `Deflate.inflateExact` (`Inflater(true)` into N + 1 bytes)
  accepts a stream only if it ends exactly after N bytes with no input left. Not `Inflate.raw`.
- The sealed JSON records (profiles, pins) are written by hand, keys sorted and padded (SD25), for byte-identical
  output; API bodies are built and read with kotlinx-serialization-json's `JsonObject`, no plugin.
- Text: `GroupText.clean` writes the White_Space set out (not `trim()`); coordinates use the Swift `rounded()` from
  `Foundation.kt`, never `Math.round`. A Kotlin string can hold half of a surrogate pair, which a Swift string can't:
  such a text is invalid.
- The Kotlin API is the Swift's (spec §8.2) with these mappings: `async` → `suspend`; Swift enums with payloads →
  sealed types with PascalCase cases (`InviteLink.Parsed.Invite`, `ChangeItem.Pin`, `GroupActionError.Kind.Offline`),
  plain enums → `enum class` with the Swift case names; failable `init?` → a companion `invoke` returning null
  (`InviteLink(token =, groupKey =)`); tuples → small classes (`GroupResponse(status, body)` for the transport's answer,
  `GroupDemo.Demo(hub, server)`, `GroupStatusDTO.Pause`, `GroupArchive.Record`). Classes holding a `ByteArray` compare
  its contents, as Swift's `Data` does.
- `GroupHub` takes one more argument, `scope`: the work its plain calls start runs there, on the thread every hub call
  is made on (`Dispatchers.Main` by default; the tests pass their own). `GroupStore(path)` runs on `MBTiles.driver`
  unless given a driver; ":memory:" keeps it in memory (demo mode, tests).

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
