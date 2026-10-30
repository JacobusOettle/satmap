# Offline areas, KML files and Android Auto: Android spec

Scope: `SatMap/Offline/*` (OfflineManager, BackgroundDownload, RoadFetcher, PlaceSearch), `SatMap/KML/KMLLibrary.swift`, `SatMap/Core/OfflineBackup.swift` and `DownloadEngine.swift` as the app drives them, `SatMap/CarPlay/*`, and the screens that call them: `OfflineSheet`, `NewAreaView`, `AreaDetailView`, `BlocksSheet`, `ShareSheet`, the download banner, the Blocks long-press menu and the drawing bar in `MapScreen`, plus the `AppModel` offline functions and `SatMapApp.onOpenURL`.

## File map

| Swift | Kotlin (`com.jacobusoettle.satmap…`) |
| --- | --- |
| `Offline/OfflineManager.swift` | `offline/OfflineManager.kt` (+ `OfflineArea.directory`, `roadLines`) |
| `Offline/BackgroundDownload.swift` | `offline/BackgroundDownload.kt` + `offline/OfflineDownloadService.kt` (the foreground service) |
| `Offline/RoadFetcher.swift` | `offline/RoadFetcher.kt` (`RoadPresets`, `RoadFetcher`) |
| `Offline/PlaceSearch.swift` | `offline/PlaceSearch.kt` |
| `UI/ShareSheet.swift` (`SharePresenter`) | `offline/BackupFileProvider.kt` + `offline/BackupSharing.kt` (share intent, "Save to a folder") |
| `KML/KMLLibrary.swift` | `kml/KMLLibrary.kt` |
| `UI/OfflineSheet.swift` | `ui/OfflineSheet.kt` (`AreaRow`, `PlaceSearchView`, `PlaceAreaView`, `PresetAreaView`, `TrackAreaView`, `KMLAreaPicker`) |
| `UI/NewAreaView.swift` | `ui/NewAreaView.kt` (`TrackDownload`) |
| `UI/AreaDetailView.swift` | `ui/AreaDetailView.kt` |
| `UI/BlocksSheet.swift` | `ui/BlocksSheet.kt` |
| `MapScreen.swift`: `DownloadBanner`, `DrawingBar`, `blocksMenu` | `ui/MapScreen.kt` (same composable names) |
| `CarPlay/CarPlaySceneDelegate.swift` | `car/CarSceneDelegate.kt` (`SatMapCarAppService`, `CarSession`, `CarMapScreen`) |
| `CarPlay/CarPlayMapController.swift` | `car/CarMapController.kt` |
| `Info.plist` document types, `onOpenURL` | `AndroidManifest.xml` intent filters, `MainActivity.handleIncoming` |

The core (`android/core`) supplies these, under the same names: `OfflineArea`, `OfflineBackup`, `TileDownloadJob`, `DownloadLayer`, `DownloadProgress`, `DownloadOutcome`, `TileFetching`, `TileFetchResult`, `MBTiles`, `TileCoverage`, `CoverageShape`, `Roads`, `RoadPreset`, `Polyline`, `Places`, `FoundPlace`, `KMLParser`, `KMLDocument`, `GPXParser`, `FileShapes`, `Terrarium`, `TerrainMode` and `stringFormat`.

The spec needs one addition in core, described in §2.8: `OfflineBackup.prepareExport(...)`.

---

# 1. Behaviour spec

## 1.1 The area record and its persistence

`OfflineArea` has these fields: `id`, `name`, `created`, `shape: CoverageShape`, `roads: [[GeoPoint]]?`, `preset: String?`, `isTrack: Bool?`, `maxZoom`, `allowCellular`, `status`, `failedTiles`, `bytesOnDisk: Int64` and `problem: String?`. `alongRoads = roads != nil || preset != nil`.

The same JSON is used for the backup format; PORTING.md gives its encoding.

- `roadLines` is `roads ?? RoadPresets.load(preset)?.roads`, or nil when the whole area is wanted.
- `directory` is `AppPaths.offlineArea(id)`. The folder is created when first asked for.
- The index is `Offline/areas.json`, a JSON array of areas in list order. It is written whole and atomically after every change, except the size refresh, which saves once at the end.
- At load:
  - Any area left as `downloading` becomes `queued`.
  - UUID-named folders that belong to no area are deleted.
  - Leftover `Restoring` folders are deleted, and on iOS the `Export` folder too.
- An index that can't be read is never overwritten. It is renamed to `areas.json.unreadable-<unix seconds>`, and the app starts with no areas.

## 1.2 Status lifecycle

| Event | Transition |
| --- | --- |
| `add(name, shape, roads, preset, maxZoom, allowCellular)` | New area, appended to the list as `queued`. It gets `id = UUID()` and `created = now`, with `failedTiles 0`, `bytesOnDisk 0` and `problem nil`. Then save → `publishToMap` → `startNextIfIdle`. |
| `startNextIfIdle`: the current network isn't allowed for the area | `queued → waitingForNetwork` |
| `startNextIfIdle`: less than 500 MB free | every `queued → incomplete`, `problem = "Less than 500 MB free on the phone. Free up space, then tap ↻."` |
| `run` picks the first `queued` area in list order | `queued → downloading` |
| run ends `completed(failed)` | `complete` when failed == 0, else `incomplete` with `failedTiles = failed`. The network retry delay is reset to 15 s. |
| run ends `offline` | `waitingForNetwork`, and a network retry is scheduled |
| run ends `cancelled` | `downloading → paused`. Any other status is kept. |
| run ends `storageError(msg)` | `incomplete`, `problem = msg` |
| run stopped for low space | `incomplete`, with the low-space problem. `bytesOnDisk` is updated. |
| `pause(id)` | `paused` straight away. If the area is downloading, its run is cancelled. |
| `resume(id)` | `queued`, `failedTiles 0`, `problem nil`, then `startNextIfIdle` |
| `changeMaxZoom(id, z)`, when z differs | `maxZoom = z`, `queued`, failed 0, problem nil. If the area is active its run is cancelled and it runs again; otherwise `startNextIfIdle`. |
| `setAllowCellular(id, b)`, when b differs | `waitingForNetwork → queued`. If the area is active: `downloading → queued` and the run is cancelled, because its connection was chosen for the old setting. Otherwise `startNextIfIdle`. |
| network change, or the app comes to the foreground | each `waitingForNetwork` area that `canDownloadNow` becomes `queued`, then `startNextIfIdle` |
| background time ends, or Stop in the notification while SatMap is in the background (§1.6) | `downloading → queued` and the run is cancelled. It continues when SatMap is opened. |
| export (§1.13) | an exported area that is downloading is paused, then resumed when the export ends |

Rules in `finished(id, outcome, size)`, applied in this order:

1. If the run was stopped for low space, the area becomes `incomplete` with the problem, `bytesOnDisk = size` and `lastRunComplete = false`.
2. Otherwise `bytesOnDisk = size` and `lastRunComplete = (outcome == completed(0))`.
3. A **pause by the user wins**: if the area is `paused` and the outcome is `cancelled` or `offline`, nothing else changes.
4. If the area was **queued again during the run** (a new zoom, a new track, a mobile-data change, or the background ending), nothing else changes. It runs again.
5. Otherwise the outcome table above applies.
6. `completedRevision += 1` if the run stored at least one tile (`progress.fetched > 0`). The map reloads tiles on this.
7. Then `startNextIfIdle()`.

`canDownloadNow(a)` is `net.isOnline && (a.allowCellular || !net.isExpensive)`.

**Network retry.** When a run ends `offline`, a retry is scheduled after `networkRetryDelay` seconds. The delay starts at 15 and doubles after each retry, up to 600. It is reset to 15 by a `completed` run.

When the retry fires:
- If no area is waiting, nothing happens.
- If a run is going on, the retry is scheduled again.
- Otherwise `networkChanged()` runs.

The retry is needed because Wi-Fi can be up with the internet behind it down, and no change is announced when it recovers.

**Delete.**
- The area is removed from the list, saved and `publishToMap()` is called.
- `TileRepository.closeArea(id)` closes the map's readers.
- The area's folder is deleted. If the area was downloading, the deletion waits until its run has ended.
- An export of that area is dropped as well (Android: its share registration, §2.8).
- In the UI, an area is deleted by swiping its row.

## 1.3 Queue and run (`startNextIfIdle`, `run`)

```
startNextIfIdle():
  if a run is going on: return
  if !(inForeground || background.allowsBackgroundWork) || holdDownloads:
      background.end(lastRunComplete); return
  if freeSpace < 500 MiB (500 << 20):
      every queued → incomplete + low-space problem; updateIdleTimer(); background.end(false); return
  every queued area that !canDownloadNow → waitingForNetwork
  next = first area in list order with status queued   (Android also skips areas with open export readers, §2.8)
  if next: run(next)
  else:
      updateIdleTimer()
      if !inForeground && allowsBackgroundWork && any waitingForNetwork: waitForSignalInBackground()
      else background.end(lastRunComplete)
```

`waitForSignalInBackground` gives a gap in the signal a chance. It waits **20 s** (once per gap), then, if no run has started, calls `background.end(false)`. The wait is cleared when SatMap comes to the foreground and when a run starts.

`run(area)` does the following.

1. **Guard: a shipped road list that is missing.** If `preset != nil && roadLines == nil`, the area becomes `incomplete` with `"This version of SatMap doesn't have the road list for this area."`, and `startNextIfIdle` is posted. The whole area (terabytes for Kruger) is never downloaded instead.
2. **Open the stores.** Opening, and creating if needed, `imagery.mbtiles` and `terrain.mbtiles` in the area folder. If that fails, the area becomes `incomplete` with the store's error text, or `"Couldn't open the tile database."`, and `startNextIfIdle` is posted.
3. **Metadata.**
   - Imagery: `name`, `format=jpg`, `type=baselayer`, `minzoom=0`, `maxzoom=<maxZoom>` and `attribution=TileSources.attribution`.
   - Terrain: `name="<name> terrain"`, `format=png`, `encoding=terrarium`, `minzoom=0` and `maxzoom=15`.
4. **The job.** `TileDownloadJob(shape, layers, concurrency = 32)`, with the layers in this order:
   - `DownloadLayer("Elevation", terrain, fetcher(TileSources.terrainURL), 0..Terrarium.maxZoom (15), pruneByParent=false)`. Elevation always covers the whole shape.
   - `DownloadLayer("Satellite", imagery, fetcher(TileSources.imageryURL), 0..maxZoom, pruneByParent=true, shape = roadLines?.let(Roads::corridor))`.
   - The job's defaults are kept: `maxAttempts 4`, retry delays `[1, 3, 8, 15]` s and `outageThreshold 16`. The adaptive parallelism starts at 16, with min 4 and max 32.
5. **Start.** `activeID = id`, `progress = DownloadProgress()`, the status becomes `downloading`, then `updateIdleTimer()` and `background.begin(name, "Starting…")`.
6. **While it runs.**
   - Each progress report goes to the main thread: `progress = p`, and `background.update(id, name, progressText(p), p.overallDone, p.overallTotal)`.
   - At most **every 10 s**, free space is checked. Below 500 MB the run is stopped for low space (`lowSpaceStop = id`, the run is cancelled).
7. **After the run.** The stores are closed and the folder size measured, then `finished(...)` runs on the main thread.

The fetcher maps answers like this:

| HTTP answer | Result |
| --- | --- |
| 200 with an empty body | `missing` |
| 200 with a JPEG/PNG magic number | `data` |
| any other 200 | `failed` |
| 204, 404, 410 | `missing` |
| any other status | `failed` |

Errors with no network give `offline`; any other error gives `failed`. The User-Agent is `SatMap/1.0 (Android)` (iOS: `SatMap/1.0 (iOS)`).

The download session has:
- no cache;
- a **20 s** request (idle) timeout;
- up to 32 connections per host;
- no waiting for connectivity.

A Wi-Fi-only area uses a session that refuses mobile data and hotspots: its requests fail and the area waits.

## 1.4 Progress reporting

- `TileDownloadJob` reports at most every 0.25 s, and always at level boundaries. `overallDone/overallTotal` is an estimate over every layer and level.
- **Map banner** (`DownloadBanner`, shown while `activeID != nil`; screenshot 08):
  - Title: `"Downloading <name>"`.
  - Detail, in monospaced digits: `"<layer> · zoom <z> of up to <max>: <levelDone>/<levelTotal> · <fetched> tiles, <bytes> saved"`, or `"Starting…"` before the first report.
  - A yellow linear bar showing `levelDone/levelTotal` (0 while the level total is 0).
  - A pause button that calls `pause(id)`.
  - Background black at 70 %, corner radius 12. Tapping the banner opens the Offline maps sheet.
  - Numbers use `Format.count` (grouped). Bytes use `Format.bytes`, which matches `ByteCountFormatter` `.file`: decimal units, KB with no decimals, MB with one, GB with two, trailing zeros dropped ("≈ 2 MB", "68.4 MB", "15.26 GB", "42 GB").
- **Offline maps button** on the map: the filled down-arrow icon, highlighted, while a download runs.
- **Area row status line**: see §1.11.
- **System progress** (iOS: the Lock Screen; Android: the notification):
  - Title: the area name.
  - Subtitle `progressText(p)`: `"<layer> · zoom <zoom> of <maxZoom> · <percent>%"`, where `percent = Int(overallDone / overallTotal × 100)`, or 0.
  - The bar adds up every area downloaded in this background session: areas before the current one count as done (`carried = max(done, total)` when the area changes). It never goes back, and the title/subtitle change at most once a second.
- **Screen**: while a download runs and SatMap is on screen, the screen stays on.

## 1.5 Low space

The threshold is `minimumFreeSpace = 500 << 20` bytes. It is checked before starting (`startNextIfIdle`) and every 10 s during a run.

If space runs short, the run stops and the area shows `"Stopped: Less than 500 MB free on the phone. Free up space, then tap ↻. Tap ↻ to continue."`. This is Swift's text with "iPhone" changed to "phone"; the AreaRow format appends the last sentence.

A write failure from SQLite (`SQLITE_FULL`, Android `SQLiteFullException`) ends the run as `storageError`. The core's message is `"The phone is out of storage space."`.

## 1.6 Background continuation (the iOS semantics to keep)

- A download keeps going when SatMap is left or the phone is locked.
- When the system ends it, or the user stops it from the system UI while SatMap is in the background: the area goes back to `queued`, the run is cancelled, and **it continues from where it stopped when SatMap is opened again**. The same happens after the app is killed (at load, `downloading → queued`).
- Without background time, nothing new starts in the background. Waiting areas get one 20 s grace period (§1.3), then wait until SatMap is opened.
- `backgroundStatus` is shown as the footer of the "Offline areas" section. It is nil until the first download, and says what happens to downloads in the background. Android texts are in §2.5.
- Opening the app again (willEnterForeground) clears the signal wait, ends the extra background time and runs `networkChanged()`. didBecomeActive asks for background time for a run already going on.

## 1.7 Deeper levels and per-area settings (`AreaDetailView`, screenshot 18)

The navigation title is the area's name; if the area is gone, the screen shows `"This area has been deleted."`.

**Section 1**
- `"Show on the map"` (map icon): zooms the phone map to the shape's bounds (`MapController.zoom(to:)`) and closes the sheet.
- Rows:
  - `"Saved"`: `Format.bytes(bytesOnDisk)`.
  - `"Downloaded to"` when complete, else `"Downloading to"`: `"zoom <maxZoom>[, along roads]"`.
  - `"Imagery found down to"`: `"zoom <d>"`, where d is the deepest stored imagery zoom (`MBTiles.maxStoredZoom` from a read-only store, opened off the main thread). Hidden while unknown.

**Section 2**
- Switch `"Allow mobile data"`, which calls `setAllowCellular`.
- Footer when complete: `"Used when you add a deeper level."`.
- Footer otherwise: `"Without mobile data the download waits for Wi-Fi. Switch it on to carry on over mobile data; what is saved is kept."`.

**Section "Detail"**: rows for zoom 22 down to 15.
- Each row shows `"Zoom z"` (grey when z ≤ maxZoom) and `Format.groundResolution(z, centre lat)`.
- Trailing text:
  - for z ≤ maxZoom: `"Downloaded"` when complete, else `"In this download"`;
  - deeper: `"+ ≈ <bytes>"` for z ≤ 19, else `"+ up to <bytes>"`. The bytes are (tiles from maxZoom+1 to z) × 17,000. Tile counts come from `TileCoverage.tileCounts(roadLines?.corridor ?: shape, maxZoom+1..22)`, computed in the background; `"…"` until known.
- Checkmark: accent colour when chosen. Grey and shown for levels ≤ maxZoom when complete.
- A row is disabled when `z == maxZoom`, or when `z < maxZoom` and the area is complete.
- Footer:
  - always `"Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded."`;
  - if not complete: `+ " While it downloads, choose a lower level to stop there instead."`;
  - if complete and `savedDepth < maxZoom`: `+ " Esri's imagery here stops at zoom <d>, so deeper levels will add little or nothing."`.

**Button** (arrow-down-circle icon, headline weight)
- Title `"Choose a deeper level"` (disabled) when none is chosen, `"Stop at zoom z"` when `z < maxZoom`, otherwise `"Download zoom <max+1>"` or `"Download zoom <max+1>–<z>"`.
- Tapping it calls `changeMaxZoom` and clears the choice.

The section's data is recomputed when `maxZoom` or `status` changes. A chosen level below maxZoom is cleared once the area is complete.

## 1.8 New area screen (`NewAreaView`; screenshots 07, 12, 17, 27)

The title is `"Download area"`. A `"Cancel"` button is shown only when the screen is presented on its own (after drawing with the lasso); it calls `discardPendingArea()`.

**Name.** Section `"Name"`, a text field with the placeholder `"Area name"`, prefilled.

**Detail.**
- Rows for zoom 22 down to 15: `"Zoom z"`, the resolution, a size text and a checkmark on the chosen level.
- Tapping a level sets `picked = true` and remembers it:
  - for a track download, in `trackZoom`;
  - otherwise in the preference `"offlineMaxZoom"` (default 22).
- Footer: `"Each zoom level has twice the detail of the one before and takes about 4 times the space. Esri's imagery often stops at zoom 18–19 on farms and 20–21 in towns; the download stops where the imagery does, so deeper levels can add little. Sizes include elevation for contours and heights."`
- Size text:
  - `"…"` until known, or `"–"` if along roads and the road lookup failed;
  - `"Nothing new"` when the estimate is 0 tiles;
  - `"≈ X"` for z ≤ 19, otherwise `"up to X"`.

**Size estimate**
- Imagery tiles from zoom 0 to the depth, from the imagery counts. These are the whole area's counts, or the along-roads counts when the box is ticked. A KML or GPX file's area is `FileShapes.downloadShape`: inside its shapes (`polygonBufferMeters` 0) and 15 m around its lines and points, nothing in between.
- Plus terrain tiles from 0 to 15 from the area's counts.
- Bytes = imagery × 17,000 + terrain × 45,000.
- The area's counts are worked out in the background:
  - for zooms 0–15 when the lines are fixed (a preset or a track), otherwise 0–22;
  - for a track area complete to `completeTo`, each zoom counts only tiles not already in the corridor of the previous lines.

**Chosen zoom**
- `remembered = clamp(trackZoom ?: offlineMaxZoom, 15, 22)`.
- If a level was not picked here, this isn't a track area that already has a level, and `deepestFitting` is known: the chosen zoom is `min(remembered, deepestFitting)`. Otherwise it is `remembered`.
- `deepestFitting` needs the counts, the imagery counts and the free space, with free space above 500 MB. With `room = (free − 500 MB) / 2`, it is the first zoom from 22 downwards whose estimate is at most `room`, or 15 if none is.

**Roads section**, in one of four forms:

1. **Track** (`track != nil`)
   - Rows: `"Your track"` with the length in km. When the area was complete before, also `"New since the last download"` with the length of `newLines`.
   - Footer: `"Satellite imagery 15 m to each side of everywhere you've been (your dotted track), with elevation under it. Come back here after a trip to add the new parts: what is already saved is skipped."`
2. **Preset** (Kruger)
   - Row: `"Roads and tracks"` with the length in km.
   - Footer: `"Satellite imagery 15 m to each side of every tar and gravel road in the park, including management tracks: <preset.note>. Elevation for contours and heights is saved for the whole park."`
3. **A KML or GPX file's area** (`NewAreaState.file`: the shape's `polygonBufferMeters` is 0)
   - The switch `"Only along roads"` only when the file has shapes (`FileShapes.hasAreas`), with the roads status under it when on; with only lines and points, no rows, the footer alone.
   - Footer: `"Satellite imagery inside the file's shapes and 15 m around its lines and points, with elevation for contours and heights."`; with the switch on, `"Satellite imagery only 15 m to each side of the roads inside the file's shapes, and 15 m around the file's lines and points. Elevation for contours and heights is still saved for the whole of each shape."`
4. **Otherwise**
   - A switch `"Only along roads"`. When it is on, the roads status shows under it.
   - Footer: `"Saves satellite imagery only 15 m to each side of every road and track in the area, including gravel and farm roads, as mapped on OpenStreetMap. Elevation for contours and heights is still saved for the whole area."`

**Roads status**
- While loading: a spinner and `"Finding roads…"`.
- Loaded: the rows above. With 0 km, an orange `"No roads or tracks are mapped in this area."`.
- Failed: an orange message and a `"Try again"` button.
- km is formatted as `"%.1f km"` below 10, otherwise `"<count> km"`.

**Loading the roads**, which starts when the switch is on (immediately for preset and track):
- Track: `track.lines`.
- Preset: `preset.roads`.
- Otherwise, if `RoadPresets.covering(shape)` finds a shipped set (Kruger holding the area), its roads clipped to the shape (`Roads.clip`); else `RoadFetcher.roads(in: shape)`.
- For a file's area, the shape looked in is `FileShapes.roadSearchShape` (its shapes only, nothing around them), and the lines are `FileShapes.roadsToSave(found)`: the roads found, then the file's lines, then each point as a one-point line, so they keep their 15 m. The km shown are the roads found only.
- Then the imagery counts per zoom from 0 to 22 along `Roads.corridor(lines)`. For a track, levels up to `completeTo` count only tiles not in the corridor of the previous lines.

**Mobile data.** A switch `"Allow mobile data"`, default off; for a track it starts from the track area's own setting. The Android footer is in §2.10.

**Download size section**, titled `"Download size"`:
- Rows: `"Area"` (`Format.hectares`, when the polygon area is above 0), `"Up to zoom z"` with the size text, `"Tiles (most)"` with the count, and `"Free space"`.
- If the estimate is larger than free space minus 500 MB, an orange `"This may not fit. The download stops when less than 500 MB is free; choose a lower zoom or a smaller area."`.
- Otherwise, if the chosen zoom is below the remembered one: `"Zoom <r> needs <size(r)>, more than half the free space, so less detail is chosen. Tap a level above to choose another."`.
- While the roads failed: `"Needs the roads first."`. Otherwise, until the counts are known: a spinner and `"Calculating…"`.

**Download button** (arrow-down-circle icon)
- For a track: `startTrackDownload(trimmedName, track.lines, chosenZoom, allowCellular)`.
- Otherwise: `startDownload(PendingArea(trimmedName, shape), roads = if (presetID == null && alongRoads) roadLines else null, preset = presetID, …)`.
- Then `sheet = null` and the screen closes. Back on the map, the banner shows the progress.
- The button is disabled when the name is blank, when along roads with no road found (a file's own lines and points don't count), or while the estimate is unknown.

## 1.9 Where areas come from (`OfflineSheet` first section; screenshot 06)

1. **"Find a park, reserve or town"** (magnifier) opens `PlaceSearchView` (§1.12).
2. **"Draw an area on the map"** (lasso; an accent-coloured button, not a navigation row).
   - Calls `startDrawingArea()`: `sheet = null`, draft cleared, `following = false`, `lassoActive = true`, `isDrawingArea = true`.
   - `DrawingBar` (screenshot 10), with no draft yet:
     - a segmented control `"Move map"` / `"Draw"`;
     - the hint `"Draw a loop around the area with one finger."` or `"Pan and zoom to the area, then switch to Draw."`;
     - `"Cancel"`.
   - With a draft:
     - the heading `"Download this area?"`;
     - `"Cancel"`, `"Redraw"` (clears the draft), and a semibold `"Continue"` (`confirmDrawnArea` → `pendingArea = PendingArea("Area <count+1>", CoverageShape(polygons=[ring]))`). This presents `NewAreaView` with Cancel; closing it clears the draft.
   - The map side belongs to the map subsystem:
     - the lasso takes one finger and draws a white 3 px dashed (8,6) stroke with a white 15 % fill over a 8 % black veil;
     - the stroke is simplified with Douglas–Peucker at 4 px;
     - fewer than 3 points means no draft;
     - the draft is drawn as a dashed white 2.5 px polygon with a 15 % fill.
   - Tile taps are ignored while drawing.
3. **"Use a GPX or KML file"** (dashed square) opens `KMLAreaPicker`, titled `"Choose a file"` (the download screen's back label).
   - Each file row shows its name and `"<n> shapes"`, and leads to `NewAreaView(PendingArea(f.name, FileShapes(f.document).downloadShape))`: imagery and elevation inside the file's shapes and 15 m around its lines and points (§1.8). `FileShapes` (core) says which features are shapes, lines and points, from their geometry and the file's own marks.
   - With no files: `"Import a KML or GPX file first (Blocks button on the map)."`.
4. The Kruger roads screen (`OfflineRoute.Preset`, `PresetAreaView`) has no row on the first screen any more, but stays in the code: areas saved with preset `"KrugerRoads"` still load, download, resume, go deeper and back up through `RoadPresets`, and the place search inside Kruger uses its roads (`RoadPresets.covering`). It is `NewAreaView(PendingArea(preset.name, CoverageShape(polygons = preset.boundary)), presetID = "KrugerRoads")`.
   - If the preset is missing: `"The road list is missing from this build of SatMap."`.
   - `RoadPresets.load(id)` reads `<id>.json` once (thread-safe cache). A failure is logged as `road preset <id> missing or unreadable`.
   - `RoadPresets.covering(area)` compares coverage at zoom 14: the preset is used if tiles outside its boundary × 10 ≤ the area's tiles.
5. **"Where I've been"** (walking figure) opens `TrackAreaView`.
   - The download is worked out once, in the background:
     - `latest = Roads.trackLines(track)`;
     - `previous = existing track area's roads ?: []`;
     - `completeTo = existing.maxZoom if existing is complete`;
     - `lines = Roads.mergeTrack(previous, latest)`;
     - `newLines = if (completeTo == null) lines else Roads.newParts(latest, previous)`.
   - Empty lines: `"No track recorded yet. Your dotted track is recorded while SatMap knows where you are."`.
   - Otherwise `NewAreaView` with name = the existing track area's name ?: `"Where I've been"`, `shape = Roads.corridor(lines)` and `track = TrackDownload(...)`.
   - `updateTrackArea(name, lines, maxZoom, allowCellular)`, with `shape = Roads.corridor(lines)`:
     - with no track area: `add(...)` with `roads = lines`, then `isTrack = true`;
     - otherwise the area gets the new name, shape, roads, maxZoom and allowCellular, and becomes `queued` with failed 0 and problem nil; then `publishToMap`. If it is active its run is cancelled (it reruns with the new track); otherwise `startNextIfIdle`.

Footer of the section: `"Saves satellite imagery at the highest detail available plus elevation data for contours and heights, so the map works with no signal."`

## 1.10 Road lookup (`RoadFetcher`)

**Request.**
- The body is `"data=" + percentEncode(Roads.overpassQuery(area))`, where only ASCII alphanumerics and `-._~` are left unencoded and everything else is `%XX` (upper-case hex of the UTF-8 bytes).
- It is POSTed with `Content-Type: application/x-www-form-urlencoded` and a **60 s** timeout.
- A null query (no geometry) gives an empty result.

**Servers**, in order:
1. `https://overpass-api.de/api/interpreter`
2. `https://overpass.private.coffee/api/interpreter`
3. `https://overpass-api.de/api/interpreter` again (its "busy" errors pass quickly)
4. `https://overpass.kumi.systems/api/interpreter`

**Order of attempts.**
- The first server is asked straight away.
- The next one is asked when the previous attempt fails, or **12 s** after the last one started without an answer.
- The first good answer wins and the rest are cancelled.
- An attempt fails on a status other than 200 (logged `"<host>: HTTP <code> after <ms> ms"`), or on an answer `Roads.parse` can't read (`"<host>: unreadable answer (<n> bytes) after <ms> ms"`). It fails as "offline" when there is no network.
- After all have failed, the error is one of:
  - offline (if any attempt was): `"No internet connection. Roads are looked up online; try again when connected."`;
  - otherwise: `"The OpenStreetMap road server is busy or unreachable. Try again in a minute."`.

The result is `Roads.clip(lines, area)`. iOS sends the system User-Agent; Android sends the PlaceSearch User-Agent, because OkHttp's default may be refused.

## 1.11 Offline areas list and backup (`OfflineSheet`; screenshots 09, 13, 14)

The sheet is titled `"Offline maps"`, with `"Done"` on the leading side. Opening it calls `refreshSizes()`, which measures each folder's allocated size off the main thread, then updates and saves once.

**Section "Offline areas"**
- With no areas: `"No offline areas yet."` in grey.
- Each row (`AreaRow`) has three lines:
  1. the name (medium weight);
  2. the status line in caption size;
  3. `"<bytes> · up to zoom <maxZoom>"`, followed by `" · along your track"` if it is the track area, else `" · along roads"` if along roads, then `" · mobile data allowed"`.
- Status texts:
  - queued: `"Waiting to start"`;
  - downloading: `"Downloading <layer lowercased> · zoom <z> of <max> · <levelDone>/<levelTotal> tiles"`, or `"Downloading…"`;
  - paused: `"Paused"`;
  - waiting: `"Waiting for internet"` if mobile data is allowed or the phone is offline, else `"Waiting for Wi-Fi"`;
  - complete: `"Ready offline"`, in green;
  - incomplete: `"Stopped: <problem> Tap ↻ to continue."`, or `"Incomplete: <n> tiles failed. Tap ↻ to retry."`, in orange;
  - other statuses are grey.
- Trailing control:
  - pause-circle (calls `pause`) for downloading, queued or waiting;
  - ↻ circle (calls `resume`) for paused or incomplete;
  - a green filled check for complete.
- Tapping a row opens `AreaDetailView`. Long-pressing it opens a menu with `"Export"` (share icon), disabled while a backup task runs. Swiping it deletes the area.
- Footer: `backgroundStatus`, when set.

**Section "Backup"**
- `"Export all offline maps"`, disabled with no areas or while busy.
- `"Restore from a backup"`, disabled while busy.
- While busy, `backupStatus` is shown with a spinner when the progress is unknown, or a linear bar showing `backupProgress`.
- Footer: Android text in §2.10.
- An alert titled `"Backup"` shows `backupMessage`, with `"OK"`, which calls `clearBackupMessage()`.

**Last section**
- `"Free space on this phone: <bytes>"`, re-read off the main thread when `completedRevision` or the area count changes.
- Footer: `"Map data: <TileSources.attribution> · Roads and places: © OpenStreetMap contributors, found with Photon (komoot) and Nominatim · Airfields and navaids: OurAirports"`.

## 1.12 Place search (`PlaceSearch`; screenshots 26, 27)

**Search screen.** Titled `"Find a place"`.
- The text field has the placeholder `"Kruger, Sabi Sand, Skukuza, Hazyview …"`, no autocorrect, and the search key submits. The field is focused on first appearance.
- Beside it: `"Search"`, disabled for blank text, or a spinner while searching.
- Footer: `"Parks and reserves are saved within their boundary; towns, villages and camps with the area around them. Next you choose the detail, and whether to save the whole area or only along its roads."`
- Results go in a section `"Places"`. Each row shows the name and a detail line made of:
  - the kind and the region (if not empty);
  - with a boundary and a known extent: `"up to <n> km²"` (n rounded and grouped, if km² ≥ 10), else `"up to %.1f km²"`;
  - otherwise `"<r> around it"`: `"<radius/1000 integer> km"` in metric, else `Format.distance(radius, unit)`.
- Joined with `" · "`.
- No results: `"No park, reserve, town or camp called “<q>” was found. Try another spelling."`.
- An error shows its own text, or `"The search didn't work. Try again."`.

**Search requests.**
- `GET https://photon.komoot.io/api/?q=<text>&limit=20[&lat=<%.4f>&lon=<%.4f>]`, near the phone map's centre.
- If `Places.singular(text)` gives a singular form, it is also searched; errors in that second search are ignored.
- The result is `Places.merged([first, second])`, parsed with `Places.photonResults`.

**Boundary.**
- For a place with a boundary (its id doesn't start with `N`): `GET https://nominatim.openstreetmap.org/lookup?osm_ids=<id>&format=geojson&polygon_geojson=1&polygon_threshold=<%.5f of Places.simplification(place)>`.
- Then `Places.area(place, Places.outerRings(geoJSON))`.
- A spot (id `N…`): `Places.area(place, [])`, with no request.
- The place screen shows a spinner with `"Getting the boundary of <name> …"`, then `NewAreaView(PendingArea(place.name, shape))`.
- On error: the message (or `"Couldn't get the boundary. Try again."`) and `"Try again"`. Cancellation is silent.

**Requests in general.**
- User-Agent `SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)`; iOS says "iPhone offline map".
- Timeout 20 s.
- Errors:
  - status other than 200: `unavailable` (logged `"<host>: HTTP <code>"`);
  - no connection or connection lost: `offline`, `"No internet connection. Places are looked up online; try again when connected."`;
  - cancellation propagates;
  - anything else: `unavailable`, `"The place search didn't answer. Try again in a moment."`.

## 1.13 Backup: export and restore

**Export** (`export(ids)`; all areas, or one from the long-press menu). Only one backup task runs at a time.

1. `backupStatus = "Preparing export…"` and `holdDownloads = true`: no download starts until the export ends.
2. If the active area is among those exported, it is paused (`pausedForExport = id`), and the export waits for its run to end.
3. For each area, in list order:
   - the area's JSON is made with `status = complete` if complete, else `paused`;
   - for each layer that exists (`imagery.mbtiles` labelled "satellite" with key `imagery`; `terrain.mbtiles` labelled "elevation" with key `terrain`), `OfflineBackup` writes `satmap_area` and `satmap_layer` into the file's metadata and checkpoints the write-ahead log (`TRUNCATE`);
   - if the checkpoint fails: `"Couldn't prepare <name> for export. Try again in a moment."`;
   - files are named `"<fileSafe(name)> - satellite.mbtiles"` and `"… - elevation.mbtiles"`, with `" (2)"`, `" (3)"`… for repeated names (compared case-insensitively);
   - `fileSafe` replaces `/\:?*"<>|` and control characters with `-`, trims whitespace and dots, cuts to 80 characters, and gives `"Offline area"` when empty.
4. Outcome:
   - files: the share UI is presented, and `finishExport` runs when it closes;
   - none: `"Nothing has been downloaded yet to export."`;
   - error: its text, or `"Couldn't export."`.
5. `finishExport`: `holdDownloads = false`. If the area paused for the export is still paused, `resume(id)`; otherwise `startNextIfIdle`.

**Restore** (`restore(urls)`; the files are picked together, several at once).

1. `backupStatus = "Restoring…"`.
2. A staging folder `Offline/Restoring` is created fresh and always removed at the end.
3. File sizes are read for progress: `"Copying <done> of <total>…"`, with `backupProgress` 0…1, updated at most every 0.1 s and at the end.
4. Each file is copied to `Restoring/<i>.mbtiles` in 4 MiB pieces. If a copy fails, the partial file is removed and `"Couldn't copy <file name> (is there enough free space?)."` is added to the messages.
5. `OfflineBackup.inspect` reads the layer key and area JSON. A file without them adds `"<file name> isn't a SatMap backup."`. The files are grouped by area id.
6. For each area:
   - one already in SatMap adds `"<name> is already in SatMap."`;
   - otherwise each layer file is moved into `Offline/<UUID>/<layer file>`. A missing layer makes it incomplete;
   - `status = complete` only if both layers came and the saved status was complete, else `paused`; `problem = nil`; `bytesOnDisk = folder size`.
7. On the main thread: the new areas are appended, then save, `publishToMap`, and `completedRevision += 1` if any were added.
8. `backupMessage` is these lines joined with newlines, or `"Nothing to restore."` when there are none:
   - `"Restored <names joined with ", ">."`;
   - `"Tap ↻ on an area marked Paused to download what its backup didn't include."` if any restored area is not complete;
   - then the problems.

## 1.14 KML library (`KMLLibrary`, `BlocksSheet`, Blocks long-press; screenshots 04, 05, 24)

The library holds GPX files too, read by `KMLParser.parseKMLOrGPX` (GPX when the file's first element is `<gpx>`) into the same `KMLDocument`: a waypoint is a point, a track (its segments joined) or route a line. GPX has no styles, so its features draw in the app's white, and a tap on a waypoint selects it as a KML point.

**Storage.**
- `KML/index.json` is a JSON array of `Entry { id (UUID), name, fileName, hidden: Bool? }`. `hidden` is `true` when switched off and left out when shown.
- Each file is stored as `KML/<UUID>.kml`, or `.kmz` when its data starts with `PK`, or `.gpx` for a GPX file (`document.format == gpx`).
- The index is read synchronously at start, so an "Open in SatMap" import at launch can't overwrite it. The documents are parsed in the background.
- Merge after parsing: the loaded files (with the current entry, so a change made meanwhile is kept), plus files imported meanwhile. Files deleted meanwhile are skipped.

**Import** (`importFile(uri, completion)`)
- Reading and parsing happen off the main thread.
- The display name is the file name without its extension.
- On success:
  - the bytes are written atomically;
  - the entry and file are appended;
  - the index is saved;
  - `completion(file)`.
- Errors set `importError` and call `completion(null)`:
  - read failure: `"Couldn't read <file name>: <reason>"`;
  - parse failure: `"<file name>: <error description>"`, using `KMLError` or `GPXError` texts.

**Other operations**
- `setShown(id, shown)` updates the entry and its `File`, then saves the index.
- `delete(id)` removes the entry, the `File` and the stored file, then saves the index.
- `useFileColours` is the preference `"kmlFileColours"`, **true when never set**.

**Blocks sheet**, titled `"Blocks"`, with `"Done"` on the leading side and a `+` (`"Import KML or GPX"`) on the trailing side.
- Empty: `"Import a .kml, .kmz or .gpx file to show your blocks on the map."`.
- Each row:
  - left part, a button: the name, and `"<n> shapes · tap to show on map"` as a caption. Tapping it calls `setShown(true)`, zooms the map to the document's bounds and closes the sheet;
  - right: a switch bound to `isShown` (accessibility label `"Show <name> on the map"`);
  - swipe to delete.
- Footer, when there are files: `"Switch a file off to take its blocks off the map."`.
- With files, a checkbox row `"Use the colours in the files"` (filled checked square or empty square) that toggles `useFileColours`. Footer `"Off: every block is drawn in white."`.
- The import picker allows several files: kml, kmz, gpx, xml and zip.
- Errors show an alert titled `"Couldn't import"` with `"OK"`.

**Long-press on the Blocks map button**: a menu with one item per file (name, with a filled check circle or an empty circle; it toggles `setShown`). Then, if there are files, a divider and `"Colours from the files"` (checked or empty square), which toggles `useFileColours`.

**Opened from another app.** The import runs. If the file has bounds, the map zooms to them; otherwise `sheet = blocks`, which shows the error.

## 1.15 CarPlay (what Android Auto must reproduce)

**The map on the car's screen**
- What it shows:
  - satellite imagery with contours and elevation colours as the current `terrainMode`;
  - the KML blocks (with the colours setting);
  - the dotted track;
  - your dot, grey while the GPS sleeps;
  - in drone mode, the aviation places with distances from you and from the phone's tapped spot.
- No POIs, compass, scale, rotation or pitch.
- The map itself takes no taps.

**Start position**
- Your position, or the phone map's centre, or `-24.99, 31.59`.
- About **1.5 km** across (1500 × 1500 m region).
- It follows you, re-centring on each fix while shown, until moved.

**Buttons**
- Map buttons: zoom in (`+`, halves the camera distance), zoom out (`−`, doubles it), follow (location arrow: `following = true` and centre on you). The camera distance is clamped to 150 m – 4,000 km.
- Bar, leading side: `"Move"` shows the panning interface; its title becomes `"Done"`, which hides it.
- Bar, trailing side: a terrain button whose title is the current mode. Tapping it sets `terrainMode = terrainMode.next`. The title follows the mode, also when the mode changes on the phone.

| Mode | Title |
| --- | --- |
| contours | `"Contours"` |
| contoursAndColours | `"Contours + colours"` |
| colours | `"Colours"` |
| drone | `"Drone"` |
| off | `"No contours"` |

**Moving the map**
- Panning arrows move the map a third of the screen in that direction and stop following.
- A drag on a touch screen moves the map with the finger and stops following.

**Lifecycle**
- When the car connects, the model gets `carPlayMap`; it is cleared on disconnect.
- Foreground and background set `isShown`; the map paints only while shown.
- `AppModel.requestReload()` also reloads the car map's tiles.
- Each location fix calls `carPlayMap.locationUpdated`.

---

# 2. Android design

## 2.1 Classes, threading, ownership

- `SatMapApplication.onCreate` creates `AppModel` (a singleton holding the application `Context`). `AppModel` creates `kml = KMLLibrary(ctx)` and `offline = OfflineManager(ctx)`, as `AppModel.shared` does at launch on iOS.
- `OfflineManager` is used on the main thread only (`@MainThread`, like the Swift).
  - State is held in `private val _x = MutableStateFlow(...)` with public `val x: StateFlow<...> = _x.asStateFlow()`: `areas`, `progress`, `activeID`, `completedRevision`, `backgroundStatus`, `backupStatus`, `backupProgress`, `backupMessage`.
  - Its scope is `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
  - Background work uses `withContext(Dispatchers.IO)`.
  - Swift's `DispatchQueue.main.async` becomes `scope.launch`, and `asyncAfter` becomes `scope.launch { delay(ms) … }`.
- The run is `task: Job?`:

```kotlin
task = scope.launch {
    val outcome = withContext(Dispatchers.IO) {
        job.run { p -> mainHandler.post { onProgress(id, name, p) }; checkSpaceEvery10s(id) }
    }
    withContext(NonCancellable + Dispatchers.IO) { imagery.close(); terrain.close() }
    val size = withContext(NonCancellable + Dispatchers.IO) { AppPaths.size(dir) }
    finished(id, outcome, size)        // on Main; must run even after cancel
}
```

  - The core's `TileDownloadJob.run` must catch the `CancellationException` of its own suspend points and **return** `DownloadOutcome.Cancelled`, as Swift returns `.cancelled`. The finishing part runs under `NonCancellable`.
  - `pause` and the other places that cancel call `task?.cancel()`.
  - `delete` and `export` wait with `running.join()`.
- Lifecycle comes from `ProcessLifecycleOwner.get().lifecycle` (`androidx.lifecycle:lifecycle-process` is already a dependency):
  - `ON_START` runs `willEnterForeground()` then `didBecomeActive()`;
  - `ON_STOP` runs `didEnterBackground()`.
- `inForeground = ProcessLifecycleOwner…currentState.isAtLeast(STARTED) || AppModel.carMap?.isShown == true`. With the car screen showing SatMap the app counts as active, as on iOS.
- Connectivity is collected with `Connectivity.state.distinctUntilChanged()`, then `networkChanged()`.
- `publishToMap()` calls `TileRepository.setAreas(...)`, owned by the map subsystem, with `AreaRef(id, directory, minX, minY, maxX, maxY)` from `WebMercator.world` of the shape's bounds (NW and SE corners).
- `AppModel` offline API, the same as Swift: `startDrawingArea`, `finishDrawing`, `cancelDrawing`, `confirmDrawnArea`, `prepareArea(from)`, `startDownload`, `startTrackDownload`, `discardPendingArea`. `pendingArea`, `draftArea`, `isDrawingArea`, `lassoActive` and `sheet` are `MutableStateFlow`s.

## 2.2 HTTP for downloads

The download client is `offline/DownloadHttp.kt`, separate from MapLibre's client (which has the `satmap.local` interceptor):

```kotlin
val anyNetwork = OkHttpClient.Builder()
    .cache(null)
    .connectTimeout(20, SECONDS).readTimeout(20, SECONDS).writeTimeout(20, SECONDS)
    .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
    .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
    .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "SatMap/1.0 (Android)").build()) }
    .build()
```

**Wi-Fi only.** This replaces iOS's `allowsExpensiveNetworkAccess = false`.
- At run start the default network is unmetered; `startNextIfIdle` has checked that.
- `wifiOnly = anyNetwork.newBuilder().socketFactory(net.socketFactory).dns { net.getAllByName(it).toList() }.build()`, where `net` is `Connectivity.network` (the default `android.net.Network`).
- Bound sockets fail when Wi-Fi goes, instead of moving to mobile data. Different `socketFactory`/`dns` objects make a different OkHttp `Address`, so pooled mobile connections are never reused.

**`HTTPTileFetcher(client, url: (TileID) -> String, wifiOnly: Boolean)`** is shared with `TileRepository`. It implements `TileFetching.suspend fetch`.
- Before each request: if `!Connectivity.isOnlineNow || (wifiOnly && Connectivity.isExpensiveNow)`, it returns `Offline`. This is Swift's `dataNotAllowed`.
- `client.newCall(req)` is wrapped in `suspendCancellableCoroutine` with `invokeOnCancellation { call.cancel() }`.
- The status mapping is §1.3. `ImageTools.looksLikeImage` checks for `FF D8` or `89 50 4E 47`.
- `IOException`: `Offline` if the same check now fails, else `Failed`.

## 2.3 Connectivity (what offline needs from `app/Connectivity.kt`)

- `ConnectivityManager.registerDefaultNetworkCallback(object : NetworkCallback() { onCapabilitiesChanged(network, caps); onLost(network) })`.
- `isOnline = caps.hasCapability(NET_CAPABILITY_INTERNET)`. VALIDATED is not required, as with iOS's `.satisfied`: Wi-Fi without internet is caught by the job's outage detection.
- `isExpensive = !caps.hasCapability(NET_CAPABILITY_NOT_METERED)`. This covers mobile data and hotspots marked metered.
- `onLost` gives offline.
- `state: StateFlow<State(isOnline, isExpensive)>`, plus thread-safe `isOnlineNow`, `isExpensiveNow` and `network: Network?` (`@Volatile`).

## 2.4 Free space, sizes, paths

`AppPaths` (owned by app/) gives offline these:

| Path | Location | Backed up |
| --- | --- | --- |
| `root` | `File(context.filesDir, "SatMap")` | yes |
| `offline` | `File(root, "Offline")` | **excluded** (§2.6) |
| `kml` | `File(root, "KML")` | yes |
| `track` | `File(root, "track.bin")` | yes |
| `offlineArea(id)` | `File(offline, id.toString().uppercase())`, created with `mkdirs()` | excluded |
| `restoreFolder` | `File(offline, "Restoring")` | excluded |

- `freeDiskSpace()` is `StatFs(root.path).availableBytes`.
- `size(dir)` is the sum over files of `Os.lstat(f.path).st_blocks * 512`, the allocated size like iOS `totalFileAllocatedSize`.
- `filesDir` is credential-encrypted storage, usable after the first unlock. That is the same as iOS `completeUntilFirstUserAuthentication`, so no extra setting is needed.
- MBTiles files are opened through the core `SqlDriver` with `SQLiteDatabase.OPEN_READWRITE or CREATE_IF_NECESSARY or NO_LOCALIZED_COLLATORS` (PORTING).

## 2.5 Background: `BackgroundDownload` + `OfflineDownloadService` (foreground service, type `dataSync`)

`BackgroundDownload` keeps the Swift API so `OfflineManager` reads the same: `onExpire`, `status`/`onStatusChange`, `allowsBackgroundWork`, `begin(title, subtitle)`, `update(area, title, subtitle, done, total)`, `enteredBackground()`, `enteredForeground()` and `end(success)`.

**`begin(title, subtitle)`**
- It stores the title and subtitle.
- If the service isn't running and `inForeground`, it calls `ContextCompat.startForegroundService(ctx, Intent(ctx, OfflineDownloadService::class.java).setAction(ACTION_START))`.
- If that throws `ForegroundServiceStartNotAllowedException` (API 31+), the status says Android didn't allow it (text below) and the run goes on while the process lives.
- After a successful start, `status` is:
  - with notifications allowed (`NotificationManagerCompat.from(ctx).areNotificationsEnabled()`): `"Downloads keep going in the background, with their progress in the notification."`;
  - without: `"Downloads keep going in the background. Allow notifications for SatMap to see their progress there."`.
- If the start isn't allowed: `"In the background a download stops, and continues when you open SatMap: Android didn't allow it to keep going in the background."`.

**`allowsBackgroundWork`** is `OfflineDownloadService.running && !stopping`.

**`update(...)`** applies the Swift carry-over (`carried`, monotonic `shownDone`) and posts the notification at most once a second (iOS: title at most 1/s).
- `NotificationManagerCompat.notify(NOTIFICATION_ID, build())` uses the same id as the FGS notification, which updates it in place.
- The progress uses an `Int` scale: divide `done` and `total` by `ceil(total / Int.MAX_VALUE)` when the total is above `Int.MAX_VALUE`.

**`enteredBackground()`**
- With the service running, nothing to do: unlike iOS there is no 30 s grace, because the service keeps the process going.
- Without it (start refused), a timer of **25 s** is armed. It calls `onExpire`, mirroring iOS's `beginBackgroundTask`.
- `enteredForeground()` cancels that timer.

**`end(success)`**
- It resets `carried`, `done` and `total`, then calls `ServiceCompat.stopForeground(service, STOP_FOREGROUND_REMOVE)` and `stopSelf()`.
- On success it posts a non-ongoing, auto-cancel notification (title = last area, text `"Ready offline"`, `setTimeoutAfter(8000)`). This stands for iOS's `setTaskCompleted(success:)` closing state. On failure nothing is posted.

**Service**

```kotlin
class OfflineDownloadService : Service() {
  override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
    when (i?.action) {
      ACTION_START -> {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, AppModel.shared.offline.background.notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)          // within 5 s, first thing
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SatMap:offlineDownload").apply { acquire(6 * 3600_000L) }
        running = true
      }
      ACTION_STOP -> AppModel.shared.offline.stopFromNotification()
    }
    return START_NOT_STICKY            // killed → continues when SatMap is opened (as iOS)
  }
  override fun onTimeout(startId: Int, fgsType: Int) {   // API 35: dataSync's 6 h per 24 h are used up
    AppModel.shared.offline.backgroundTimeEnded(); stopSelf()
  }
  override fun onDestroy() { wakeLock?.release(); running = false }
  override fun onBind(i: Intent?) = null
}
```

`OfflineManager.stopFromNotification()` handles Stop. Unlike iOS, Android shows the notification while SatMap is open too.
- With SatMap in the foreground: `pause(activeID)`. The row shows "Paused" and ↻.
- In the background: `backgroundTimeEnded()`, exactly as iOS's Lock Screen stop. `stopping = true` is set first so `allowsBackgroundWork` is false, the area goes back to `queued`, the run is cancelled, and `end(false)` is called. It continues on the next `ON_START`.

**Notification**
- Channel `"downloads"`, named "Offline downloads", `IMPORTANCE_LOW`.
- `NotificationCompat.Builder(ctx, "downloads")` with:
  - `setSmallIcon(R.drawable.ic_download)`, `setColor(0xFFFFBD29)` (the AccentColor);
  - `setContentTitle(title)`, `setContentText(subtitle)`;
  - `setProgress(max, shown, max == 0)`;
  - `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setSilent(true)`, `setCategory(CATEGORY_PROGRESS)`;
  - `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`;
  - `setContentIntent`: a `PendingIntent.getActivity` to `MainActivity` with `FLAG_IMMUTABLE`;
  - `addAction(R.drawable.ic_stop, "Stop", PendingIntent.getService(ctx, 1, Intent(ctx, OfflineDownloadService::class.java).setAction(ACTION_STOP), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))`.
- `POST_NOTIFICATIONS` (API 33+) is requested the first time **Download** is tapped, with `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`. The download starts whatever the answer.

**Restart on app open.** `init` turns `downloading` into `queued` and posts `startNextIfIdle`. In `Application.onCreate` nothing is in the foreground yet, so that call only ends. Then `MainActivity` `ON_START` runs `willEnterForeground` → `networkChanged` → `startNextIfIdle` → `run` → `begin` → the service starts. Starting it is allowed because an activity is visible.

**The same as iOS**
- Nothing new starts in the background without the service.
- The 20 s `waitForSignalInBackground` applies.
- A network retry in the background can't start the service; it waits for the app to open. (Android 12+ forbids starting a foreground service from the background anyway.)

**Different from iOS** (note these in the code)
- Swiping SatMap away does **not** stop a running download (`stopWithTask` is false by default), and the notification stays.
- No Wi-Fi lock is taken. The adaptive parallelism absorbs Wi-Fi power-save latency.

**Keep screen on.** In `MapScreen`: `val view = LocalView.current; val active by offline.activeID.collectAsState(); DisposableEffect(active) { view.keepScreenOn = active != null; onDispose { view.keepScreenOn = false } }`. This replaces `updateIdleTimer()`; the manager only exposes `activeID`.

## 2.6 Storage layout and backup exclusions

```
filesDir/SatMap/
  Offline/                 excluded from backup and device transfer (GBs; the 25 MB Auto Backup quota)
    areas.json             written with android.util.AtomicFile (startWrite / finishWrite)
    <UUID>/imagery.mbtiles, terrain.mbtiles (+ -wal/-shm while open)
    Restoring/             staging for a restore, removed at start and after each restore
  KML/index.json, KML/<UUID>.kml|.kmz|.gpx backed up
  track.bin                                backed up
```

Manifest `<application>`: `android:allowBackup="true"`, `android:fullBackupContent="@xml/backup_rules"` (API ≤ 30) and `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+).

```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="file" path="SatMap/Offline/" />
</full-backup-content>
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup><exclude domain="file" path="SatMap/Offline/" /></cloud-backup>
    <device-transfer><exclude domain="file" path="SatMap/Offline/" /></device-transfer>
</data-extraction-rules>
```

`areas.json` sits inside `Offline/`, so after a reinstall-and-restore the restored index can never name areas whose files weren't restored, as on iOS. Preferences (`kmlFileColours`, `offlineMaxZoom`) are backed up by default.

## 2.7 Free-space and size checks in UI

- `OfflineSheet`: `LaunchedEffect(completedRevision, areas.size) { freeSpace = withContext(IO) { AppPaths.freeDiskSpace() } }`.
- `NewAreaView` reads the free space with the counts, in `withContext(Dispatchers.Default)`.

## 2.8 Export (share intent through a FileProvider) and restore

**Core addition.**
- `OfflineBackup.prepareExport(areaFolder, layers, name, areaJSON, usedNames): List<Pair<File, String>>` does the metadata, checkpoint and name work and returns (source file, display name) pairs.
- The existing `export(..., into)` becomes `prepareExport` plus a copy, so the cross-platform fixture tests stay as they are.

**Zero-copy share.** Android has no file clones, so copying an area would double its size: 15 GB for Kruger. Instead the area files themselves are shared read-only, and the guarantee iOS gets from a clone comes from not writing while they are read.

- Provider in the manifest:

```xml
<provider android:name=".offline.BackupFileProvider" android:authorities="${applicationId}.backup"
          android:exported="false" android:grantUriPermissions="true">
  <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/backup_paths" />
</provider>
<!-- res/xml/backup_paths.xml --> <paths><files-path name="offline" path="SatMap/Offline/" /></paths>
```

- `class BackupFileProvider : androidx.core.content.FileProvider()`:
  - `query()` returns `OpenableColumns.DISPLAY_NAME` set to the registered display name (`"<name> - satellite.mbtiles"`) and `SIZE = file.length()`;
  - `getType()` returns `"application/octet-stream"`;
  - `openFile(uri, mode)` allows only `"r"` and URIs registered in `ExportShares`. It returns `ParcelFileDescriptor.open(file, MODE_READ_ONLY, Handler(Looper.getMainLooper())) { ExportShares.closed(areaId) }` after `ExportShares.opened(areaId)`. The close listener also fires when the receiving process dies.
- `ExportShares` is a main-thread registry: URI → (areaId, file, displayName), plus open-reader counts per area. It is cleared on the next export and on deleting that area. On process death it is empty, which matches iOS deleting `Export` at the next launch.
- `OfflineManager` rules:
  - `holdDownloads` (global) lasts from the export start until the share flow returns;
  - an area with `ExportShares.readers(id) > 0` is not started: `startNextIfIdle` skips it;
  - if a reader opens an area that is downloading, the area is paused like `pausedForExport` and resumed after the last close;
  - the last close calls `startNextIfIdle()`.
- Safety: after `checkpoint(TRUNCATE)` the main database file holds everything. The map's read-only connections never write, and no writer runs while a reader is open, so a raw copy taken by the receiving app is consistent.

**Share intent.** `BackupSharing.share(activity, uris)` is launched by `MainActivity` through `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`. The callback is iOS's `completionWithItemsHandler` and calls `offline.finishExport()`.

```kotlin
val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
    type = "application/octet-stream"
    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    clipData = ClipData.newUri(cr, "SatMap", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
val chooser = Intent.createChooser(send, null)
```

- The share sheet's Drive, Gmail, Quick Share (the AirDrop equivalent) and Files targets work.
- Stock Android has no "Save to Files" target for USB drives or local folders, so the chooser gets one SatMap entry, **"Save to a folder"**:
  - API 34+: `Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS` = `arrayOf(ChooserAction.Builder(Icon.createWithResource(ctx, R.drawable.ic_folder), "Save to a folder", PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).setAction(ACTION_SAVE_TO_FOLDER), FLAG_IMMUTABLE)).build())`;
  - API 26–33: `Intent.EXTRA_INITIAL_INTENTS` = `arrayOf(LabeledIntent(Intent(ctx, SaveToFolderActivity::class.java), packageName, "Save to a folder", R.drawable.ic_folder))`. `SaveToFolderActivity` is exported with no intent filter, and forwards `ACTION_SAVE_TO_FOLDER` to `MainActivity`.
- `MainActivity` handles `ACTION_SAVE_TO_FOLDER`:
  1. It launches `ActivityResultContracts.OpenDocumentTree()`.
  2. For each prepared file, it calls `DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", displayName)` and streams the area file into it in 4 MiB pieces.
  3. Downloads are held during the copy, with `backupStatus = "Copying <x> of <y>…"` and `backupProgress`.
  4. Then `finishExport()`. On error, `backupMessage = "Couldn't save <display name>."`.

**Restore**
- `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())` is launched with `arrayOf("*/*")`. MBTiles has no registered MIME type: providers report `application/octet-stream`, `application/vnd.sqlite3` and others. The files say themselves what they are, so any file is accepted.
- `OfflineManager.restore(uris: List<Uri>)` follows §1.13 with these replacements:
  - the size comes from `cr.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME))`, with 0 meaning unknown;
  - the name comes from `DISPLAY_NAME`, or `uri.lastPathSegment`;
  - the copy is `cr.openInputStream(uri)` into `FileOutputStream(Restoring/<i>.mbtiles)` with a 4 MiB buffer and progress;
  - the move is `File.renameTo` (same file system), with copy and delete as a fallback;
  - work runs on `Dispatchers.IO`, and the result is applied on Main.
- Android OfflineSheet backup footer: `"Saves each area as two MBTiles files, satellite and elevation, to Google Drive, a folder or a USB drive, or sends them to a computer. To restore, choose both files of each area. Long-press an area to export only that one."`

## 2.9 KML and GPX import and "share to SatMap"

**Picker** (Blocks `+`): `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())` with
`KMLLibrary.pickerTypes`: `arrayOf("application/vnd.google-earth.kml+xml", "application/vnd.google-earth.kmz", "application/gpx+xml", "application/xml", "text/xml", "application/zip", "application/octet-stream")`.
These are iOS's kml/kmz/gpx/xml/zip types, plus octet-stream, which many providers report for `.kml`, `.kmz` and `.gpx`. Each URI goes to `kml.importFile(uri)`.

**`KMLLibrary.importFile(uri: Uri, completion: ((File?) -> Unit)? = null)`**
- The display name comes from `OpenableColumns.DISPLAY_NAME`, or `uri.lastPathSegment`, with `substringBeforeLast('.')`. The `"<file name>"` used in error texts is the full `DISPLAY_NAME`.
- It reads with `cr.openInputStream(uri)!!.use { it.readBytes() }` on `Dispatchers.IO`, straight away, because the URI grant lives with the receiving activity.
- Storage: `KML/<UUID upper-case>.kml|.kmz|.gpx`, written with `AtomicFile`, and an atomic `index.json`. Entries are encoded as Swift encodes them: `{"id":"<UPPER-UUID>","name":…,"fileName":…}`, plus `"hidden":true` when switched off.
- No inbox clean-up is needed on Android (content URIs).

**Intent filters** on `MainActivity`, with `android:launchMode="singleTask"`. SatMap then opens in its own task with the map, as iOS switches to SatMap.

```xml
<intent-filter>                                   <!-- "Open with" by type -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="content" />
  <data android:mimeType="application/vnd.google-earth.kml+xml" />
  <data android:mimeType="application/vnd.google-earth.kmz" />
  <data android:mimeType="application/gpx+xml" />
</intent-filter>
<intent-filter>                                   <!-- "Open with" where the provider doesn't know the type: by name -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="content" android:host="*" android:mimeType="application/octet-stream" />
  <data android:pathPattern=".*\\.kml" /><data android:pathPattern=".*\\..*\\.kml" />
  <data android:pathPattern=".*\\.kmz" /><data android:pathPattern=".*\\..*\\.kmz" />
</intent-filter>
<intent-filter>                                   <!-- A .gpx by name, whatever type it is given (octet-stream, text/xml, text/plain, */*) -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="content" android:host="*" android:mimeType="*/*" />
  <data android:pathPattern=".*\\.gpx" /><data android:pathPattern=".*\\..*\\.gpx" />
  <data android:pathPattern=".*\\.GPX" /><data android:pathPattern=".*\\..*\\.GPX" />
</intent-filter>
<intent-filter>                                   <!-- Share to SatMap: WhatsApp, Gmail, Files, Drive -->
  <action android:name="android.intent.action.SEND" />
  <action android:name="android.intent.action.SEND_MULTIPLE" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:mimeType="application/vnd.google-earth.kml+xml" />
  <data android:mimeType="application/vnd.google-earth.kmz" />
  <data android:mimeType="application/gpx+xml" />
  <data android:mimeType="application/xml" />
  <data android:mimeType="text/xml" />
  <data android:mimeType="application/zip" />
  <data android:mimeType="application/octet-stream" />
</intent-filter>
```

WhatsApp and Gmail pass on the sender's type, which for KML/KMZ/GPX is often `application/octet-stream`, `text/xml` or `application/zip`, and their URIs carry no file name. Without those types SatMap couldn't receive their files. As a result SatMap also appears for other files of those types, and parsing rejects them with the usual "Couldn't import" alert. This is a deliberate difference from iOS's kml/kmz/gpx-only document types. Few phones know `application/gpx+xml` (file managers call a `.gpx` octet-stream, `text/xml`, `text/plain` or `*/*`), so "Open with" also takes a `.gpx` by name whatever its type.

**`MainActivity.handleIncoming(intent)`**
- It is called from `onCreate` (when `savedInstanceState == null`) and from `onNewIntent`.
- URIs:
  - `ACTION_VIEW`: `intent.data`;
  - `ACTION_SEND`: `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`;
  - `ACTION_SEND_MULTIPLE`: `IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`.
- For each URI: `model.kml.importFile(uri) { f -> f?.document?.bounds?.let { model.map?.zoom(it) } ?: run { model.sheet.value = ActiveSheet.Blocks } }`.
- Afterwards `setIntent(Intent(intent).setAction(null))`, so a recreation doesn't import again.
- An intent carrying `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (`MainActivity.isReplay`) is ignored. `setIntent` only changes the activity object, not the task's base intent: on API 26–30 Back finishes the root activity but leaves the task in Recents, and reopening it from there starts `MainActivity` again with the original `ACTION_VIEW`/`ACTION_SEND` intent (and `savedInstanceState == null`). Without the check the file would be imported again (a duplicate in Blocks), or fail with "Couldn't read …" once the URI grant is gone. iOS hands each opened file over once. The same holds for `ACTION_SAVE_TO_FOLDER`.

**Persistence.**
- `useFileColours` is `MutableStateFlow(prefs.getBoolean("kmlFileColours", true))` and is written on change.
- Switches go to `hidden` in `index.json`.
- `files: StateFlow<List<File>>` and `importError: MutableStateFlow<String?>`.

## 2.10 Compose screens (Material 3, made to look like the screenshots)

- **Sheets**: `ModalBottomSheet(skipPartiallyExpanded = true)`, with the map visible above it.
  - Inside, a small back stack (`sealed interface OfflineRoute`, with `BackHandler`). The Compose navigation library is not a dependency.
  - Top bar: `CenterAlignedTopAppBar`, with `"Done"` as a `TextButton` in `navigationIcon` on root screens, and a back arrow with the previous title on pushed screens.
- **Grouped lists**, like iOS inset-grouped:
  - section headers in upper-case grey `labelMedium`;
  - rounded (12 dp) `Surface(color = surfaceContainerHigh)` groups with inset `HorizontalDivider`;
  - footers in grey `bodySmall`.
- **Colours**: accent/primary `#FFBD29`, in dark theme. Action rows use the accent ("Draw an area…", "Export…", "Restore…", "Download"). Navigation rows are white with a chevron.
- **Icons** (`material-icons-extended`):

| iOS symbol | Material icon |
| --- | --- |
| magnifyingglass | `Icons.Outlined.Search` |
| lasso | `Icons.Outlined.Gesture` |
| square.dashed | `Icons.Outlined.SelectAll` (also on the Blocks button) |
| car | `Icons.Outlined.DirectionsCar` |
| figure.walk | `Icons.Outlined.DirectionsWalk` |
| square.and.arrow.up | `Icons.Outlined.IosShare` |
| square.and.arrow.down | `Icons.Outlined.Download` |
| pause.circle | `Icons.Outlined.PauseCircle` |
| arrow.clockwise.circle | `Icons.Outlined.Replay` |
| checkmark.circle.fill | `Icons.Filled.CheckCircle` in green |
| arrow.down.circle / .fill | `Icons.Outlined.ArrowCircleDown` / custom `ArrowDownCircleFill` (the arrow cut out of a disc; Material's `Filled.ArrowCircleDown` is a ring) |
| map | `Icons.Outlined.Map` |

- **Row actions**:
  - long-press: `Modifier.combinedClickable(onClick, onLongClick)` with a `DropdownMenu`;
  - swipe to delete: `SwipeToDismissBox` (end to start, red background with a delete icon). The area or file is deleted in `confirmValueChange`.
- **Controls**: switches are `Switch`; the colours checkbox is `Checkbox`; zoom rows are `ListItem`s with a trailing check `Icon` (alpha 0 when not chosen); `DrawingBar` uses a `SegmentedControl` of our own, drawn as iOS's (UI spec §2.3.1).
- **Android text changes** (everything else is word for word):
  - "iPhone" becomes "phone" in the low-space problem and the free-space line;
  - `NewAreaView` mobile data footer: `"Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, with its progress in a notification. If Android stops it, it continues when you open SatMap again."`;
  - background status: §2.5;
  - backup footer: §2.8.

## 2.11 Road and place requests on Android

- Client: `OkHttpClient.Builder().connectTimeout(20, SECONDS).readTimeout(20, SECONDS)` with a User-Agent interceptor (`SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)`).
- `RoadFetcher` uses `client.newBuilder().connectTimeout(60, SECONDS).readTimeout(60, SECONDS)` and the same User-Agent.
- URLs are built with `HttpUrl.Builder().addQueryParameter(...)`. Numbers use the core's `stringFormat("%.4f", lat)` and `stringFormat("%.5f", t)`: POSIX formatting, **never** `String.format` with the device locale, which would write `24,9876`.
- `RoadFetcher.roads(area)`:

```kotlin
suspend fun roads(area: CoverageShape): List<List<GeoPoint>> = coroutineScope {
    val query = Roads.overpassQuery(area) ?: return@coroutineScope emptyList()
    val body = ("data=" + percentEncode(query)).toRequestBody("application/x-www-form-urlencoded".toMediaType())
    val results = Channel<Attempt>(Channel.UNLIMITED); val jobs = mutableListOf<Job>()
    var started = 0; var running = 0; var offline = false; var startNext = true
    try {
        while (true) {
            if (startNext && started < servers.size) {
                val s = servers[started++]; running++
                jobs += launch { results.send(attempt(s, body)) }
                if (started < servers.size) jobs += launch { delay(12_000); results.send(Attempt.NextServerDue) }
            }
            startNext = false
            if (running == 0) break
            when (val r = results.receive()) {
                is Attempt.Roads -> return@coroutineScope Roads.clip(r.lines, area)
                is Attempt.Failed -> { running--; offline = offline || r.offline; startNext = true }
                Attempt.NextServerDue -> startNext = true
            }
        }
        throw if (offline) RoadFetcher.Failure.Offline else RoadFetcher.Failure.Unavailable
    } finally { jobs.forEach { it.cancel() } }
}
```

- Failures are Kotlin exception classes with Swift's message texts.
- In `attempt`, an `IOException` is offline when `!Connectivity.isOnlineNow || e is UnknownHostException`.
- `PlaceSearch.get`:
  - non-200: `Unavailable`;
  - an `IOException` with `!isOnlineNow` or an `UnknownHostException`: `Offline`;
  - `CancellationException` is rethrown;
  - anything else: `Unavailable`.
- Logs: `android.util.Log` with tags `"roads"` and `"places"`, using the Swift messages.

## 2.12 Android Auto (CarPlay's counterpart)

**Dependencies.**
- `implementation(libs.car.app)` (`androidx.car.app:app:1.7.0`) and `implementation(libs.car.app.projected)`.
- `testImplementation("androidx.car.app:app-testing:1.7.0")`, which is published on Google Maven.

**Manifest**

```xml
<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />
<application …>
  <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />
  <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="2" />
  <service android:name=".car.SatMapCarAppService" android:exported="true">
    <intent-filter>
      <action android:name="androidx.car.app.CarAppService" />
      <category android:name="androidx.car.app.category.NAVIGATION" />
    </intent-filter>
  </service>
</application>
<!-- res/xml/automotive_app_desc.xml --> <automotiveApp><uses name="template" /></automotiveApp>
```

- The minimum car API level is 2 because `NavigationTemplate.Builder.setMapActionStrip` and `setPanModeListener` are `@RequiresCarApi(2)`, as are `SurfaceCallback.onScroll`, `onFling` and `onScale`. `onClick` is level 5.
- The navigation category is required. It is the only category whose app draws its own map on the car's `Surface`; POI apps get a map the host draws.

**Classes** (`car/CarSceneDelegate.kt`)
- `class SatMapCarAppService : CarAppService()`:
  - `createHostValidator()` returns `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` in debuggable builds, else `HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()`. That list includes Android Auto's signatures, so the sideloaded release APK works in real cars;
  - `onCreateSession()` returns `CarSession()`.
- `class CarSession : Session()`: `onCreateScreen(intent)` returns `CarMapScreen(carContext)`.
- `class CarMapScreen(carContext) : Screen(carContext)`. This is the template part of `CarPlaySceneDelegate`.
  - `init`:
    - `val map = CarMapController(carContext, AppModel.shared)`;
    - `carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)`;
    - `AppModel.shared.carMap = map`;
    - a lifecycle observer: `onStart` sets `map.isShown = true`, `onStop` sets it to false, and `onDestroy` clears `carMap` if it is this map and releases the map;
    - `lifecycleScope.launch { model.terrainMode.drop(1).distinctUntilChanged().collect { invalidate() } }`, so the terrain title follows the phone.
  - `onGetTemplate()`:

```kotlin
NavigationTemplate.Builder()
  .setActionStrip(ActionStrip.Builder()                               // required by NavigationTemplate
      .addAction(Action.Builder().setTitle(shortTitle(model.terrainMode.value))
          .setOnClickListener { model.terrainMode.value = model.terrainMode.value.next }.build())
      .build())
  .setMapActionStrip(ActionStrip.Builder()                            // ≤ 4 icon actions
      .addAction(icon(R.drawable.ic_car_add)) { map.zoom(1.0) }        // CarPlay zoom(by: 0.5)
      .addAction(icon(R.drawable.ic_car_remove)) { map.zoom(-1.0) }    // CarPlay zoom(by: 2)
      .addAction(icon(R.drawable.ic_car_near_me)) { map.recentre() }
      .addAction(Action.PAN)                                          // CarPlay "Move"; the host shows its own exit
      .build())
  .setPanModeListener { panning -> if (panning) map.dragBegan() }
  .build()
```

  - Icons are `CarIcon.Builder(IconCompat.createWithResource(carContext, id)).build()`.
  - The library's limits fit: the navigation action strip allows 4 actions with custom titles, and the map strip 4 actions.
  - Leaving pan mode is shown by the host, so there is no `"Done"` title. Following is only restored by the follow button, as on CarPlay.

**Map rendering on the car's `Surface`** (`car/CarMapController.kt`, implements `androidx.car.app.SurfaceCallback`): a `VirtualDisplay` plus a `Presentation`, the pattern Google's Navigation SDK uses for Android Auto.

```kotlin
override fun onSurfaceAvailable(c: SurfaceContainer) {
    val dm = carContext.getSystemService(DisplayManager::class.java)
    display = dm.createVirtualDisplay("SatMap car", c.width, c.height, c.dpi, c.surface,
                                      DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
    presentation = Presentation(carContext, display!!.display).apply {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true).attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        mapView = MapView(context, options)
        setContentView(FrameLayout(context).apply { addView(mapView) /* + view overlays if the phone map uses any */ })
        show()
    }
    mapView.onCreate(null); mapView.onStart(); if (isShown) mapView.onResume()
    mapView.getMapAsync { m -> map = m; m.setMinZoomPreference(3.0); m.setMaxZoomPreference(20.0)
        m.setStyle(MapStyle.builder(model)) { style -> attachLayers(m, style); moveToStart() } }
}
override fun onSurfaceDestroyed(c: SurfaceContainer) {
    mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy(); presentation?.dismiss(); display?.release()
}
override fun onVisibleAreaChanged(r: Rect) = map?.setPadding(r.left, r.top, width - r.right, height - r.bottom) ?: Unit
override fun onScroll(dx: Float, dy: Float) { following = false; map?.scrollBy(-dx, -dy) }   // check the sign on the DHU
override fun onScale(fx: Float, fy: Float, f: Float) {
    val focus = if (fx < 0 || fy < 0) null else Point(fx.toInt(), fy.toInt())          // negative: the host's own zoom
    map?.moveCamera(if (focus == null) CameraUpdateFactory.zoomBy(log2(f.toDouble()))
                    else CameraUpdateFactory.zoomBy(log2(f.toDouble()), focus))
}
// onFling, onClick: ignored (CarPlay ignores velocity; the car map takes no taps)
```

- The MapLibre APIs used here were checked with `javap` against 13.7.1:
  - `MapView(Context, MapLibreMapOptions)`;
  - `MapLibreMapOptions.textureMode`, `attributionEnabled`, `logoEnabled`, `compassEnabled`, `rotateGesturesEnabled`, `tiltGesturesEnabled`;
  - `MapLibreMap.scrollBy(float, float)`, `moveCamera`, `animateCamera`, `easeCamera`, `setPadding`, `setMin/MaxZoomPreference`, `setStyle(Style.Builder, OnStyleLoaded)`, `getCameraPosition`;
  - `CameraUpdateFactory.zoomBy(double[, Point])`, `newLatLng`, `newLatLngBounds(LatLngBounds, int)`;
  - `Projection.getMetersPerPixelAtLatitude`.
- `MapLibre.getInstance(appContext)` and `HttpRequestUtil.setOkHttpClient(...)` are called once in `Application`, so the car's tiles go through the same `satmap.local` interceptor and `TileRepository`. Offline areas work in the car too.
- **Contract for the map subsystem**:
  - the style (`MapStyle.builder(model)`: raster sources `RasterSource(id, TileSet("tileset", "https://satmap.local/<layer>/{z}/{x}/{y}"), 256)` for imagery, contours and colours) and the overlay classes (blocks, track, user dot, aviation layer) must be buildable against any `MapLibreMap` and `Context`, not only `MainActivity`'s;
  - the car map binds to the same state: `trackRevision` updates the track source, `terrainMode` sets layer visibility and aviation on/off, `selectedPoint` and your location set the aviation distances, and `kml.files` with `useFileColours` update the blocks.
- **Behaviour**:
  - `moveToStart()` uses `CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(lat + d, lon + e, lat − d, lon − e), 0)` with `d = 750 / 111_320` and `e = d / cos(lat)`. The centre is `recorder.location`, else the phone map's centre, else `(-24.99, 31.59)`.
  - `locationUpdated(loc)` moves the dot. If `following && isShown`, it calls `easeCamera(newLatLng)`. Then it updates the aviation distances. `AppModel.locationUpdated` calls `carMap?.locationUpdated(loc)`.
  - `zoom(levels)` calls `animateCamera(CameraUpdateFactory.zoomBy(levels))`. MapKit's 150 m – 4,000 km camera-distance clamp is approximated by the zoom limits 3.001–19.999: just inside whole zooms, where the camera rests zoomed all the way in or out, as at a whole zoom the track's dots are half the iPhone's size (interaction spec §2.2).
  - `recentre()` sets `following = true` and eases to your position.
  - `dragBegan()` sets `following = false`.
  - `reload()` is called from `AppModel.requestReload()`, as `carPlayMap?.reload()`, and does the same tile reload as the phone map.
  - Setting `isShown` to true calls `mapView.onResume()`; setting it to false calls `onPause()`. The map paints only while shown.
- `CarMapController` reads `AppModel` state on the main thread, as Swift does.

**Testing on a real car or the DHU**
- On the phone:
  1. Open Android Auto settings and tap "Version" ten times.
  2. In the ⋮ menu, open **Developer settings** and switch on **Unknown sources**. Without it, Android Auto hides apps that weren't installed from Play.
  3. Choose **Start head unit server**.
- On the computer:
  1. `sdkmanager "extras;google;auto"`.
  2. `adb forward tcp:5277 tcp:5277`.
  3. `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.
- The same APK then works in a real car.
- What to check by hand on the DHU:
  - the sign of `onScroll`;
  - whether `onScale` gives a negative focus for the host's zoom;
  - the visible-area padding;
  - that `Presentation` with a `CarContext` and `textureMode(true)` renders.

**In CI**
- No emulator plus head unit is practical: the DHU needs Android Auto on a Google Play phone.
- Robolectric unit tests with `app-testing` are possible:
  - `TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())`;
  - `ScreenController(CarMapScreen(ctx)).moveToState(Lifecycle.State.RESUMED)`;
  - `getTemplatesReturned()` should hold one `NavigationTemplate` whose action strip title is `"Contours"`; after the action's `onClickDelegate`, the next template is `"Contours + colours"`; the map strip holds 4 actions, the last of type `Action.TYPE_PAN`.
- MapLibre's native renderer doesn't run under Robolectric, so `CarMapController` is tested only for its camera logic, with a fake map interface.

## 2.13 Manifest additions for this subsystem (summary)

- Permissions:
  - `INTERNET` and `ACCESS_NETWORK_STATE` (already present);
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`;
  - the two `androidx.car.app.*` permissions.
- Components:
  - `<service android:name=".offline.OfflineDownloadService" android:foregroundServiceType="dataSync" android:exported="false" />`;
  - the `BackupFileProvider`;
  - `SaveToFolderActivity` (exported, no filter, translucent theme);
  - the car service and its meta-data.
- `MainActivity` gets `launchMode="singleTask"` and the four KML and GPX intent filters.
- `<application>` gets the backup attributes from §2.6.

## 2.14 Unit tests (`app/src/test`, Robolectric)

**OfflineManager state machine**, with a fake `TileFetching`, a temp `filesDir` and a fake `Connectivity`:
- the queue runs in list order;
- a pause wins over cancelled or offline outcomes;
- an area queued again during its run reruns;
- the Wi-Fi-only area moves through waiting, queued and running;
- the retry delay goes 15, 30, … up to 600 and is reset by a completed run;
- the low-space stop (with `freeDiskSpace` injected) gives the right `problem` text;
- `changeMaxZoom`, `setAllowCellular` and `updateTrackArea` while active;
- the preset guard;
- an unreadable `areas.json` is renamed;
- orphaned folders are removed;
- `downloading` becomes `queued` at load.

**Backup**
- `export` with the zero-copy share registers display names `"Farm - satellite.mbtiles"` and `"Farm (2) - …"`.
- An open reader holds the area's download; `finishExport` resumes the paused area.
- `restore`:
  - with both files, the area is complete;
  - with the satellite file only, it is paused and the "Tap ↻" line appears;
  - an area that already exists is reported;
  - a non-backup file gives `"<name> isn't a SatMap backup."`;
  - the fixture `Tests/SatMapCoreTests/Fixtures/backup/iphone` restores.

**KMLLibrary**
- An import writes `.kmz` when the data starts with `PK`, and `.gpx` for a GPX file.
- `hidden` is round-tripped and left out when shown.
- `kmlFileColours` defaults to true.
- A delete removes the stored file.
- An import during the initial load is kept.

**RoadFetcher and PlaceSearch** with OkHttp `MockWebServer`, which needs `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`, a new test dependency:
- the next server is asked after 12 s, using virtual time and an injectable delay;
- the first answer wins;
- offline and busy messages;
- the percent-encoding of the query;
- the Photon URL has `lat`/`lon` with a decimal point in a German locale.

**Car**: the `app-testing` template assertions from §2.12.
