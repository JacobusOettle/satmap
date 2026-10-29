# SatMap for Android: map rendering and tiles

This spec covers `SatMap/Map/SatelliteLayer.swift`, `DrawnImagery.swift`, `TileRepository.swift`, `TileOverlays.swift`, `SatMap/Core/TileFallback.swift` and `SatMap/App/Config.swift`. It also covers how they are called from `MapController`, `CarPlayMapController`, `PointAdjuster` (the loupe), `AppModel` and `AppDelegate`.

I checked every MapLibre and OkHttp API named below against `android-sdk-13.7.1.aar` and `okhttp-4.12.0.jar` with `javap`.

## 0. Files and names

| Swift | Kotlin (package `com.jacobusoettle.satmap…`) | Contents |
|---|---|---|
| App/Config.swift | `app/Config.kt` | `TileSources` (constants unchanged), `LocationPolicy` |
| Core/TileFallback.swift | `core/TileFallback.kt` (already ported) | `TileFallback.ancestor(t, maxLevels, has)`, `RecentCache` (`value`, `peek`, `contains`, `touch`, `set(value, key)`, `remove`, `resize`, `trim`) |
| Map/TileRepository.swift | `map/TileRepository.kt` | `TileRepository`, `HTTPTileFetcher`, `ImageTools`, `Work`, `RequestPriority`, `RequestLimiter`, `LRUCache` |
| Map/TileOverlays.swift | `map/TileOverlays.kt` | `ContourRenderer`, `HeightGrids` (drawing with `android.graphics`) |
| Map/SatelliteLayer.swift | `map/SatelliteLayer.kt` | `ImageryMemory`, `DrawnMap`, `SatelliteRenderer` (the per-tile decision of what to show, answered to MapLibre), `ImageryPrefetch` |
| Map/DrawnImagery.swift | `map/DrawnImagery.kt` | `TerrainKey`, `ContourInterval`, `DrawnImagery` (style sources and layers, layer replacement, colour range, legend) |
| (none) | `map/TileInterceptor.kt` (Android only) | `TileUrls`, `TileLevels`, `TileAnswer`, `TileLayers`, `TileInterceptor` |

Core types used as in Swift:
- `TileID(z, x, y)`, with `.ancestor(levels)` and `.worldBounds: WorldRect`.
- `WorldRect(minX, minY, maxX, maxY)` and `XY`. World units run from 0 to 1, where MapKit used map points from 0 to 2²⁸.
- `WebMercator.world(GeoPoint): XY`.
- `ContourPolicy`, `MarchingSquares`, `ContourGeometry`, `ContourLine`, `Terrarium`, `DEMTile`, `DEMSampler`.
- `ElevationColours`, `ElevationGrid`, `ElevationRange`, `TerrainMode`, `MBTiles` (over `SqlDriver`), `TileFetchResult`, `TileFetching`.

Logging: Swift's `tileLog` becomes `Log.d("tiles", …)`, with the same messages.

---

# Part 1: Behaviour (what iOS does, to be reproduced)

## 1.1 Sources (`TileSources`)

- **Imagery:** `https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false`.
  - With `esriAPIKey` set: `https://ibasemaps-api.arcgis.com/…/tile/{z}/{y}/{x}?blankTile=false&token=KEY`.
  - `blankTile=false` makes Esri answer 404 where it has no imagery.
- **`imageryMaxZoom` = 22.** This is the deepest imagery level ever requested or drawn. It also caps the contour and colour levels, because `level()` clamps to it.
- **Elevation:** `https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png`. `Terrarium.maxZoom` = 15.
- **`attribution`:** the same string as iOS. It appears on the Offline maps screen, not on the map, so MapLibre's logo and attribution are off.
- **`imageryURL(t)` / `terrainURL(t)`:** substitute `{z}`, `{x}` and `{y}` into the template.

## 1.2 Zoom levels

Two definitions:
- **Point zoom `Zp`:** the web-map zoom with 256-point tiles at the scale on screen. On iOS, `Zp = 20 + log2(points per map point)`. On MapLibre, 512-dp tiles and dp ≈ pt, so **`Zp = cameraPosition.zoom + 1`**.
- **`s`:** the screen scale (iOS `contentScaleFactor`; Android `MapView.getPixelRatio()`). The Android phone draws its contour tiles at the iPhone's `s = 3` whatever its density (`TileLevels.PHONE_CONTOUR_SCALE`, §2.3); the car at its own screen's.

**Imagery level.** `SatelliteRenderer.level(pixelsPerMapPoint:)` gives tiles about 256 pixels across on screen:
```
level = clamp(round(log2(worldPixels / 256)), 0, 22)   // round = half away from zero
      = clamp(round(Zp + log2 s), 0, 22)
```
Each tile pixel shows as 0.7 to 1.4 screen pixels. In `draw` iOS uses `z = min(painting, max(painting − 1, focus.level))`, which equals the on-screen level above, because MapKit paints at most one level finer than the screen.

| Zp | s = 2 | s = 2.625 | s = 3 |
|---|---|---|---|
| 14.80 | 16 | 16 | 16 |
| 14.95 | 16 | 16 | 17 |
| 15.00 | 16 | 16 | 17 |
| 15.50 | 17 | 17 | 17 |

**Contour and colour level.** The tiles are 256 points across at the zoom MapKit paints at, which is the next whole zoom at or above the screen's:
```
contourLevel = clamp(ceil(Zp − 1e-6), 0, 22)
```
A tile at this level is shown 128 to 256 points across. Both tile kinds are 256-pixel bitmaps, so on a 3× screen they appear enlarged 1.5 to 3 times. This is why contour line widths and fonts are multiplied by `s`, and it is how the iOS screenshots look: labels of about 13 to 25 pt, minor lines of about 1 to 2 pt.

**Contour interval.** It is chosen from `pointZoom(tileZoom: cz, screenScale: s) = cz − log2(max(1, s))`, where `s = min(scale, 3)`:

| pointZoom | step / index (m) | at s = 3, cz = | at s = 2, cz = |
|---|---|---|---|
| < 11 | none | ≤ 12 | ≤ 11 |
| [11, 12) | 100 / 500 | 13 | 12 |
| [12, 13) | 50 / 250 | 14 | 13 |
| [13, 14) | 20 / 100 | 15 | 14 |
| [14, 16.5) | 10 / 50 | 16–18 | 15–17 |
| ≥ 16.5 | 5 / 25 | ≥ 19 | ≥ 18 |

The screenshots match this. `22-contours-and-colours` is at Zp ≈ 14.8 → cz 15 → 20/100 m. `01-map` is at Zp ≈ 15.05 → cz 16 → 10/50 m.

Further limits:
- Contours: `ContourPolicy.minTileZoom` = 11, and an interval must exist.
- Colours: `ElevationColours.minTileZoom` = 8.
- Elevation (DEM) zoom for a tile at level z: `min(z, 15)`.

**Focus.** This is where a map loads:
- the visible map rect grown by a quarter of its width and height on each side (1.5× in each direction),
- at the imagery level, and at the contour level for contour and colour tiles.

`inFocus(t, f) = |t.z − f.level| ≤ 1 && f.rect intersects t`.

## 1.3 What a spot shows (fallbacks)

**Imagery spot at level z:**
1. Its own tile, if it is in memory.
2. Otherwise the sharper tiles under it, if in memory (one level down, else what there is two levels down; after zooming out).
3. Over the nearest coarser tile in memory, enlarged: `TileFallback.ancestor(of: t, maxLevels: 10, has: memory)`.
4. Otherwise nothing.

For a spot in focus that has nothing, iOS first loads the tile three levels up, `t.ancestor(min(3, t.z))`, urgently. One request covers 64 spots.

**Repository side: `nearestImagery(t, urgent)`.**
1. Offline areas (MBTiles) → `(t, data, beyondDepth = false)`.
2. Check `beyondDepth = offlineKnowsMissing(t)`. For k in 0…min(4, z), take ancestor a: if a store lists a in `missing_tiles`, the answer is true. Stop walking up at the first k > 0 whose tile the store contains.
3. Set `online = networkUsable && !beyondDepth`. If the device is online but not usable (the network is paused) and the tile is not beyond depth, note that a retry is needed.
4. If online, ask the network for t:
   - data → return it as the tile's own;
   - missing → `beyondDepth = true`;
   - failed or offline → `online = false` and note a retry. Rule: no blurry stand-in fetched from the network for a tile that exists but failed. Only offline ancestors are used then.
5. For k = 1…max(1, min(8, z)), take ancestor a: offline tile, else (if still online) the network.
   - missing → continue to the next ancestor;
   - failed → stop using the network.
   - The first ancestor found returns `(a, data, beyondDepth)`.
6. Otherwise nil.

**Loupe (`drawImagery`):** each spot's own tile, else an ancestor in memory up to 12 levels up. What is missing is loaded urgently.

**Contours or colours of a spot:**
- its own tile;
- otherwise, for now, its parent's (one level up only) enlarged, or its four children's;
- otherwise nothing.

A tile with an incomplete result (an elevation tile around it was missing, so its edges are approximated) is drawn, and asked for again in 30 s.

## 1.4 Loading: priorities, limits, sharing

**`SatelliteRenderer.load(t, urgent)`:**
- Runs once per tile at a time (`loading` set).
- Skipped while `retryAfter[t]` holds.
- Calls `nearestImagery`, stores the result in `ImageryMemory.tiles` under the tile it is for, then decodes it.
- If the result is not t's own tile: `retryAfter[t] = now + (beyondDepth ? 600 : 30) s`.

**`onlineImagery(t, priority)`:**
- One network request per tile, shared by all callers (`imageryInFlight`), unless a caller with higher priority arrives while the existing request is still waiting for a connection.
- In that case a new request is made at the new priority. When the old one gets its slot, its `start()` check fails (the id is no longer current). It fetches nothing and returns the newer request's result.
- Tiles the server reported missing go into `imageryMissing` (cleared when it passes 20,000).

**`limitedFetch(url, priority, start)`:**
- The host prefix `elevation…` uses the terrain limiter (4 slots); everything else uses the imagery limiter (10 slots).
- After acquiring a slot: if `start()` says no, return nil; if the network is not usable, return `.offline`; otherwise fetch.

**`RequestLimiter(slots)`:**
- Waiting callers are served urgent first, then normal, then background; newest first within a priority (pop last).
- Background requests hold at most 2 connections, so they never block on-screen requests.
- Priorities:
  - `urgent`: the coarse tile three levels up, and the loupe;
  - `normal`: tiles on screen;
  - `background`: `ImageryPrefetch`.

**HTTP (`HTTPTileFetcher.fetch`):**
- User-Agent `SatMap/1.0 (iOS)`, 15 s timeout, 10 connections per host.
- Cache: `URLCache` with 16 MB memory and 1 GB disk, at `Caches/tiles-http`, using the protocol cache policy.
- Results:
  - 200 with an empty body → `missing`;
  - 200 with a JPEG (FF D8) or PNG (89 50 4E 47) body → `data`;
  - 200 with anything else → `failed`;
  - 204, 404 or 410 → `missing`;
  - any other status → `failed`;
  - no internet, data not allowed, or roaming off → `offline`;
  - any other error → `failed`.

## 1.5 Failures, retries, offline

**Network pause.**
- Data or missing results reset `consecutiveFailures` and set `lastSuccessAt`.
- Each non-background `failed` or `offline` result sets `lastFailedURL` and increments the counter.
- At 8 failures: `networkPausedUntil = now + 45 s` and the counter resets.
- `networkUsable = Connectivity.isOnlineNow && now ≥ networkPausedUntil`.

**Elevation (`demTile`):**
- `demCache` (LRU, 160 tiles) → offline tile decoded on `Work.contours` → network.
- The network request is shared per tile (`demInFlight`).
- `demRetryAfter`:
  - 30 s for failed or offline, plus a retry is noted;
  - 600 s for missing on the server or undecodable.

**`probeRecovery()`:**
- Runs every 30 s (tolerance 10 s) while the app is in the foreground.
- It is true if a retry was needed and either some request has succeeded since then, or `lastFailedURL` now fetches as data or missing. Then the retry state is cleared.

**Triggers for `AppModel.requestReload()`:**
- the network comes back (`Connectivity` online after offline): also calls `resetFailureState()`, which clears `demRetryAfter`, the failure count and the pause;
- an offline area finishes;
- the probe succeeds.

The reload runs at most every 20 s and only in the foreground; while in the background it stays pending. It does three things:
- `SatelliteRenderer.reload()`: forgets the `retryAfter` entries except beyond-depth ones, and all contour and height retry gates.
- Each map's `DrawnImagery.reload()`: a new layer if the shown one has stand-ins in focus.
- Retries the height lookups.

## 1.6 Memory caches

| Cache | Holds | Size |
|---|---|---|
| `ImageryMemory.tiles` | encoded imagery (JPEG, 15–60 KB) | `RecentCache` 900 |
| `SatelliteRenderer.decoded` | decoded imagery (256 KB) | 400, grown to `min(spots, 4000) × 3/2` by `makeRoom` |
| `SatelliteRenderer.contourImages` | decoded contour tiles + `complete` flag | 240 |
| `SatelliteRenderer.noContours` | flat tiles | 4000 |
| `SatelliteRenderer.heightGrids` | `ElevationGrid` 65×65 (17 KB) + `complete` | 400 |
| `colourImages` (per layer) | coloured grid images | 200, then all cleared |
| `ContourRenderer.cache` | contour PNGs (complete only) | 32 MB |
| `ContourRenderer.emptyCache` | blank tiles | unbounded |
| `TileRepository.demCache` | `DEMTile` (256 KB) | LRU 160 |
| URL cache | HTTP responses | 16 MB memory / 1 GB disk |

`RecentCache` drops the least recently used quarter when full (it trims to 3/4).

**Entering the background** (`UIApplication.didEnterBackground`):
- `TileRepository.trimForBackground()`: clear `demCache`, set the URL cache's memory to 0, clear `imageryMissing`, close all MBTiles readers;
- `ImageryMemory.enteredBackground()`: `tiles.trim(150)`, `SatelliteRenderer.trim(0)`;
- `ContourRenderer.clearCaches()`.

**Memory warning:**
- `TileRepository.trimMemory()`: clear `demCache`;
- `ImageryMemory.trimMemory()`: `tiles.trim(100)`, `SatelliteRenderer.trim(32)`;
- `ContourRenderer.clearCaches()`.

## 1.7 Prefetch when the map stops

`DrawnImagery.mapStopped()` runs 0.4 s after the region stops changing. It is debounced by `stopCount` and only runs while the map is visible. It:
1. sets the focus;
2. calls `ImageryPrefetch.mapStopped(visibleMapRect, imageryLevel)`;
3. calls `loadStandIns()` (iOS only);
4. calls `updateColourRange()`.

`ImageryPrefetch.mapStopped`:
- Takes the surround tiles:
  - `z − 3` over the visible rect grown by 0.5× its size on each side (twice the screen);
  - `z − 6` over the rect grown by 3.5× (eight times the screen).
- x wraps around the world. A level is skipped if it needs more than 64 tiles along an axis.
- Tiles already in memory are touched, so the ones around the screen are kept longest.
- The others, unless already loading, are loaded with `imageryInBackground(t)`: offline first, then the network at background priority, but not when the network is unusable or an offline area knows the tile is missing. Results go into `ImageryMemory`.

## 1.8 Contour tiles (`ContourRenderer`)

**Computing the lines.**
- `s = min(contentScale, 3)`. The interval comes from 1.2; if there is none, the tile is blank.
- DEM tiles at `demZoom` covering the tile bounds (plus half a pixel) via `demTiles`.
  - If none are available → `unavailable`.
  - `complete` = all of them loaded.
- Grid: `DEMSampler.grid` with 129×129 nodes (`gridCells` = 128) spanning the tile edge to edge, on `Work.contours`.
- `MarchingSquares.contours(step)`. No lines → blank.

**Drawing:** a 256×256 transparent bitmap at scale 1.

| Item | Value |
|---|---|
| Point position | node × 2 px (256 / 128) |
| Smoothing | one Chaikin pass when `t.z > 15` |
| Index line | `abs(IEEEremainder(level, index)) < 0.01` |
| Joins / caps | round / round |
| Minor width | `0.7·s` |
| Index width | `1.3·s` |
| Halo width | line width + `0.9·s` |
| Minor colour | RGBA (1.0, 0.84, 0.32, 0.85) |
| Index colour | (1.0, 0.74, 0.16, 1) |
| Halo colour | black at 0.38 |

Order: every halo first, then every line, one stroke per line.

**Labels:**
- Only on tiles with `(x + y) % 2 == 0` (checkerboard), and only on index lines.
- Text: `Int(level.rounded())`, metres always.
- Font: system 8.5·s, semibold.
- Fill (1.0, 0.93, 0.70); stroke black at 0.75, stroke width −3 (3 % of the font size, stroke and fill).
- `margin = textWidth/2 + 4·s`; the label is skipped if `2·margin ≥ 256`.
- `ContourGeometry.labelAnchor(points, minLength: textWidth + 16·s, bounds 256×256, margin)`.
- Translate to the anchor and rotate by its angle. Clear the box `(−w/2 − 2s, −h/2, w + 4s, h)` (blend mode clear), then draw the text with its top-left corner at `(−w/2, −h/2)`.

**Results and caching:**
- `Tile.lines(png, complete)`, `.blank` or `.unavailable`.
- Only complete results are cached (`cache` by `"z/x/y"`, `emptyCache`).
- Requests in flight are shared per key.

**Renderer side:**
- `loadContours` runs once per tile at a time, only for tiles in focus.
- `contoursRetryAfter = 30 s` when the result is unavailable or incomplete.
- A newly arrived result is announced. A blank result goes into `noContours`.

## 1.9 Elevation colours

**Heights (`HeightGrids.tile`):**
- `demTiles` at `min(z, 15)` → `DEMSampler.grid` with 65×65 nodes (`cells` = 64) on `Work.contours`.
- Returns `.grid(ElevationGrid, complete)` or `.unavailable`. Requests are shared per tile.

**Renderer side (`loadGrid`):**
- Stores the grid only if it is complete or none is held.
- `gridsRetryAfter = 30 s` unless the grid is complete.
- Announces the grid if it is complete or new. Heights are loaded even while there is no range yet.

**Pixels (`ElevationColours.pixels(grid, range)`):**
- One premultiplied RGBA pixel per node, alpha 0.5 baked in.
- Ramp stops: blue (0.16, 0.32, 0.95) at 0, (0.10, 0.72, 0.97) at 0.2, (0.22, 0.80, 0.36) at 0.4, (0.97, 0.90, 0.20) at 0.6, (0.99, 0.55, 0.10) at 0.8, red (0.90, 0.12, 0.12) at 1.
- 256-entry lookup table; the sea (below 0 m) is clear.
- The 65×65 image is drawn with high-quality interpolation into the tile rect, grown by half a cell (256/64/2 = 2 px) on every side and clipped to the tile. Each node's pixel is centred on it, so tiles meet without seams.

**Choosing the range (`DrawnImagery.updateColourRange`).** It requires: colours shown, map visible, levels known, and contour level ≥ 8. Steps:
1. `current = rangeArea ∩ visible ≠ ∅ ? colourRange : nil`. If `current` is nil but a range is held, the range is dropped, `rangeArea` is cleared and the key is reported. A range chosen elsewhere is not kept.
2. Compute `heightExtremes(visibleRect, level: contourLevel, onScreen: screenPoints)`:
   - `screenPoints`: 81 points on a 9×9 grid over the map bounds inset by 1 point.
   - Take the tiles at the contour level under those points (x and y clamped to 0…n−1).
   - For each tile with a grid, take `grid.extremes(tile: bounds, within: visibleRect)`. Land nodes only (≥ 0 m).
   - `complete`: every one of those tiles has a grid, or has given up for now (`gridsRetryAfter` set and not loading).
   - Nil when there are no land heights.
3. If whether any heights are on screen has changed → report the key. If there are no heights, stop.
4. Wait for the first range of a place:
   - a wait older than 5 s is forgotten;
   - if `current` is nil and the heights are incomplete: start the wait (and re-check after 1.6 s); return while less than 1.5 s has passed.
5. `rangeWaitSince = nil`, `rangeArea = visible`.
6. `range = ElevationColours.range(low, high, current, complete)`:
   - **Keep** `current` if the heights fit inside it and either the heights are incomplete or `max(high − low, 10) ≥ 0.6 × (current span)` (hysteresis).
   - Otherwise start from `low…high`. If incomplete, take the union with `current` (the range only grows while heights are still loading).
   - If the span is < 10 m: `lo = mid − 5`, `max(0, lo)` when all heights are ≥ 0, `hi = lo + 10`.
   - Round outward to `niceStep(span / 10)` (1, 2 or 5 × 10ᵏ): `floor(lo/step)·step`, `ceil(hi/step)·step`.
7. If the range has changed → `colourRange = range`, report the key. If the shown layer's range differs → schedule a new layer.

**Recomputed on:**
- `mapStopped`;
- `terrainChanged` (when colours turn on);
- `becameVisible`;
- every arrival of heights.

**Replacing layers without flashing (iOS):**
- `scheduleSwap`:
  - Waits `max(0.25, 1 − (now − lastSwap))` s, so it runs at most about once a second.
  - Never runs while the map is moving, unless it has been moving for more than 3 s; then it is owed until `regionDidChange`.
  - Never runs while the map is invisible; then it is owed until `becameVisible`.
- One replacement at a time: the new layer goes under the shown one.
- `retire` checks every 0.1 s:
  - When the new layer has painted the 81 screen points, the old one is removed after 0.4 s more (0.1 s if the old one had painted less than 95 % of them).
  - If it has not painted after 20 ticks while visible, the new one is removed, the old one stays, and another replacement is scheduled.

## 1.10 Legend key (`TerrainKey`)

Reported through `DrawnImagery.onKey` → `MapLegend.update`, which ignores duplicates. It is reported:
- on every visible-region change;
- on terrain change;
- on range change;
- when whether heights are on screen changes.

Contents:
- `levels == nil` (map narrower than 100 pt) → empty key.
- `contours = ContourInterval(step, index)` when contours are shown, cz ≥ 11, and `intervals(pointZoom(cz, s))` is not nil.
- `colours = colourRange` (which may be nil) when colours are shown, heights are on screen, and cz ≥ 8.

`ContourKeyView` shows "Contours", then a minor sample and `step`, then an index sample and `index`. `ColourKeyView` shows the gradient with `low` and `high` beneath.

## 1.11 Callers (how MapController and the others use this)

- **`MapController`:**
  - Creates `DrawnImagery(mapView, isVisible: isShown, terrain: model.terrainMode)` and sets `onKey → model.legend.update`.
  - Calls `addToMap()` first, so the imagery sits under everything else.
  - Delegate calls:
    - `regionWillChange` → `imagery.regionWillChange()`;
    - `regionDidChange` → `imagery.regionDidChange()`;
    - `mapViewDidChangeVisibleRegion` → `imagery.visibleRegionChanged()`.
  - A terrain mode change → `imagery.terrainChanged(mode)`.
  - Coming back to the foreground → `imagery.becameVisible()`.
  - `reloadImagery()` → `imagery.reload()`.
  - `ContourRenderer.shared.screenScale = displayScale`.
- **`CarPlayMapController`:** a second `DrawnImagery` on the car's map. The tiles in memory are shared.
- **`PointAdjuster` (loupe):**
  - `level = SatelliteRenderer.level(pixelsPerMapPoint: k·scale)`;
  - its own `DrawnMap.setFocus(ground, level, contourLevel: −1)`;
  - `SatelliteRenderer.drawImagery(...)`;
  - redraws on `onArrival(.imagery)`.
- **`AppModel` and `AppDelegate`:** the background, memory-warning and reload hooks in 1.5 and 1.6.

---

# Part 2: Android design on MapLibre Native 13.7.1

## 2.1 Overview

```
MapLibre (native) ── tile URL https://satmap.local/<kind>/<gen>/… ──► OkHttp (mapClient)
                                                                       └─ TileInterceptor (OkHttp thread)
                                                                            └─ SatelliteRenderer.answer(route)
                                                                                 ├─ ImageryMemory / stand-in crops
                                                                                 ├─ TileRepository (offline MBTiles → network via limiters)
                                                                                 ├─ ContourRenderer / HeightGrids (Work.contours)
                                                                                 └─ TileAnswer: 200 / 204 + Cache-Control
DrawnImagery (main thread): style sources and layers, levels and focus, prefetch, colour range, replacement of a layer, legend
```

Why an HTTP interceptor: MapLibre Android 13.7.1 has no custom raster source. `org.maplibre.android.style.sources` has only `CustomGeometrySource` and `CustomVectorSource` (vector data), plus `ImageSource` (one image). `RasterSource` has no `setTiles`/`setUrl`, only `getUrl()` and `getUri()`.

## 2.2 Global MapLibre setup

Done once, before any `MapView` exists (in `SatMapApplication.onCreate` or before `MainActivity`'s map):

```kotlin
MapLibre.getInstance(appContext)
// MapLibre's own "offline" stops ALL requests, ours to satmap.local included (offline maps would not show).
// Whether to use the network is TileRepository's decision (Connectivity), so MapLibre always thinks it is online.
MapLibre.setConnected(true)
HttpRequestUtil.setOkHttpClient(TileInterceptor.mapClient)
HttpRequestUtil.setLogEnabled(false)
HttpRequestUtil.setPrintRequestUrlOnFailure(false)
```

`TileInterceptor.mapClient`:
```kotlin
val mapClient: OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 }) // MapLibre's own default is 20 per host
    .addInterceptor(TileInterceptor())
    .build()
```
- Requests to other hosts call `chain.proceed`.
- MapLibre calls `Call.enqueue`, so `intercept` runs on an OkHttp dispatcher thread.
- MapLibre's native file source keeps at most about 20 requests open and queues the rest first-in, first-out. This limit is not settable from Java in 13.7.1; the native lib has a `max-concurrent-requests` property, not exposed. It cancels requests for tiles that leave the screen (`HttpRequestImpl.cancelRequest` → `Call.cancel()`).

`MapLibreMapOptions` (MapController builds the `MapView` with these):
- `.pixelRatio(resources.displayMetrics.density)`
- `.setPrefetchesTiles(true)`
- `.setPrefetchZoomDelta(3)` (iOS's tile three levels up)
- `.logoEnabled(false)`, `.attributionEnabled(false)`
- `.tiltGesturesEnabled(false)`
- `.foregroundLoadColor(BACKGROUND_COLOUR)`

Also, after the map is ready: `map.setPrefetchZoomDelta(3)`.

## 2.3 Style: sources, layers, URL scheme

`DrawnImagery.addTo(builder: Style.Builder)` adds the terrain part of the style. `MapController` calls it first, then adds its own overlay layers above `TERRAIN_TOP`.

**URL scheme** (`TileUrls`, host `satmap.local`):
```
https://satmap.local/imagery/{gen}/{z}/{x}/{y}
https://satmap.local/contours/{gen}/{scale}/{z}/{x}/{y}     scale = round(s·100): s = 3 on the phone, min(max(1, pixelRatio), 3) in the car
https://satmap.local/colours/{gen}/{range}/{z}/{x}/{y}      range = "none" | "{low}_{high}" (Double.toString, e.g. 300.0_900.0)
```
- `gen` comes from `TileUrls.nextGeneration()`, a global `AtomicInteger` starting at 1, so the phone and car maps never clash.
- Source id = layer id = `"$kind-$gen"`.
- Every source is registered in `TileLayers` (gen → `TileLayer`, see 2.5) and unregistered when removed.
- The range and scale are in the URL so a tile URL says exactly what it draws. The generation lets a new range (or a reload) be a new source; a source's URL cannot change in 13.7.1.
- The phone draws its contours at the iPhone's scale, `TileLevels.PHONE_CONTOUR_SCALE` = 3, whatever its density (an Android difference: iOS uses its own screen's `contentScaleFactor`, which is 3 on the iPhones the screenshots come from). Contour tiles are 128 to 256 dp across on both (§2.4), so the line widths, labels and the interval at each level are the iPhone's in dp; at a density of 2.625 the phone's own scale would give z18 5 m / 25 m instead of the iPhone's 10 m / 50 m, and lines and labels 12 % smaller. The URL, the legend (`reportKey`) and the source's minzoom all take this one scale. The car keeps its own screen's, `TileLevels.contourScale(pixelRatio)`.
- Contour PNGs are cached by `"scale/z/x/y"`, so the phone and the car (different scales) don't share wrong lines. iOS keyed them by `z/x/y` only.

**Sources** (built in code):
```kotlin
fun source(kind: TileKind, gen: Int, url: String, tileSize: Int, minZoom: Int) =
    RasterSource("${kind.id}-$gen", TileSet("2.1.0", url).apply { setMinZoom(minZoom.toFloat()); setMaxZoom(22f) }, tileSize).apply {
        setVolatile(true)                                  // never in MapLibre's ambient SQLite cache: TileRepository caches
        setPrefetchZoomDelta(if (kind == IMAGERY) 3 else 0)
        setMaxOverscaleFactorForParentTiles(if (kind == IMAGERY) 10 else 1) // iOS: 10 levels up for imagery; contours/colours: parent only
    }
fun layer(kind: TileKind, gen: Int, visible: Boolean, opacity: Float = 1f) =
    RasterLayer("${kind.id}-$gen", "${kind.id}-$gen").withProperties(
        rasterOpacity(opacity), rasterFadeDuration(0f), rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
        visibility(if (visible) Property.VISIBLE else Property.NONE),
    ).apply { setRasterOpacityTransition(TransitionOptions(0, 0, false)) }
```

| Kind | tileSize (dp) | minzoom | maxzoom |
|---|---|---|---|
| imagery | `TileLevels.imageryTileSize(pixelRatio)` = `round(256 / max(1, pixelRatio))` (98 at 2.625, 85 at 3) | 0 | 22 |
| colours | 182 | 8 | 22 |
| contours | 182 | `TileLevels.contourMinZoom(s)` = first L in 11…22 with `intervals(pointZoom(L, s)) != null` (13 on the phone, s = 3; in the car 12 at s = 2, 13 at 2.63) | 22 |

Above maxzoom 22 MapLibre enlarges the z22 tile, as iOS's clamp does.

**Equivalent style JSON** (example for pixelRatio 2.625, with contours shown and colours off):
```json
{ "version": 8,
  "sources": {
    "imagery-1":  {"type":"raster","tiles":["https://satmap.local/imagery/1/{z}/{x}/{y}"],"tileSize":98,"minzoom":0,"maxzoom":22,"volatile":true},
    "colours-2":  {"type":"raster","tiles":["https://satmap.local/colours/2/none/{z}/{x}/{y}"],"tileSize":182,"minzoom":8,"maxzoom":22,"volatile":true},
    "contours-3": {"type":"raster","tiles":["https://satmap.local/contours/3/300/{z}/{x}/{y}"],"tileSize":182,"minzoom":13,"maxzoom":22,"volatile":true} },
  "layers": [
    {"id":"background","type":"background","paint":{"background-color":"#202020"}},
    {"id":"imagery-1","type":"raster","source":"imagery-1","paint":{"raster-fade-duration":0}},
    {"id":"colours-2","type":"raster","source":"colours-2","layout":{"visibility":"none"},"paint":{"raster-fade-duration":0}},
    {"id":"contours-3","type":"raster","source":"contours-3","paint":{"raster-fade-duration":0}},
    {"id":"terrain-top","type":"background","layout":{"visibility":"none"}} ] }
```
- Build it as `Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[]}""")`, then `.withLayer(...)`, `.withSource(...)` in this order, and `.withTransition(TransitionOptions(0, 0, false))`.
- `terrain-top` is a hidden, stable anchor. New terrain layers are inserted relative to the shown ones; overlays (blocks, track, measuring, aviation) go above `terrain-top`.
- The background colour (#202020) shows only where no imagery exists at all.

## 2.4 Level maths on MapLibre (reproducing iOS's choice)

For a raster source, MapLibre's tile level is `coveringZoomLevel = std::round(zoom + log2(512 / tileSize))`, clamped to the source's minzoom and maxzoom.

**Imagery** (`T = 256/s` dp):
```
round(zoom + log2(512·s/256)) = round(Zp + log2 s)
```
This equals iOS's imagery level: tiles 256 *pixels* across, 0.7 to 1.4 px per tile pixel. Rounding T to an integer moves the 0.5 boundary by less than 0.01 zoom.

**Contours and colours** (`T = 182` → `log2(512/182) = 1.4922`):
```
round(zoom + 1.4922) = floor(Zp + 0.9922) = ceil(Zp − 0.0078)
```
This equals iOS's `ceil(Zp − 1e-6)`. Tiles are 128 to 256 dp on screen, with 256-px bitmaps drawn at the iPhone's `s = 3` on the phone, so line widths in dp and label sizes match iOS's points exactly.

```kotlin
object TileLevels {
    const val TERRAIN_TILE_SIZE = 182
    fun imageryTileSize(pixelRatio: Float): Int = (256.0 / max(1.0, pixelRatio.toDouble())).roundToInt()
    /** MapLibre's raster level (std::round, half away from zero), clamped to 0…imageryMaxZoom. */
    fun level(zoom: Double, tileSize: Int): Int {
        val v = zoom + log2(512.0 / tileSize)
        return (if (v >= 0) floor(v + 0.5) else ceil(v - 0.5)).toInt().coerceIn(0, TileSources.imageryMaxZoom)
    }
    /** The phone map's contour drawing scale: the iPhone's, whatever the phone's density. */
    const val PHONE_CONTOUR_SCALE = 3.0
    /** A screen's contour drawing scale (the car's), quantised as in the URL (2.625 → 2.63). */
    fun contourScale(pixelRatio: Float): Double = (min(max(1.0, pixelRatio.toDouble()), 3.0) * 100).roundToInt() / 100.0
    fun contourMinZoom(s: Double): Int =
        (ContourPolicy.minTileZoom..TileSources.imageryMaxZoom).first { ContourPolicy.intervals(ContourPolicy.pointZoom(it, s)) != null }
}
```
- `DrawnImagery` computes its levels with exactly these functions from `map.cameraPosition.zoom`, so the focus, prefetch, swap readiness and legend use the levels MapLibre requests.
- `SatelliteRenderer.level(pixelsPerWorld: Double) = clamp(round(log2(pixelsPerWorld / 256)), 0, 22)` is the Swift `level(pixelsPerMapPoint:)` with the world (0…1) as the unit. The loupe uses it.

## 2.5 The interceptor (`TileInterceptor.kt`)

```kotlin
class TileInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TileUrls.HOST) return chain.proceed(request)
        val route = TileUrls.parse(request.url) ?: return TileAnswer.empty(maxAge = null).response(request)
        val layer = TileLayers[route.gen] ?: return TileAnswer.empty(maxAge = null).response(request) // source already removed
        val answer = runBlocking { awaitUnlessCanceled(chain.call()) { SatelliteRenderer.answer(route, layer) } }
            ?: throw IOException("Canceled")
        layer.noteAnswered(route.tile, answer.lacking)
        return answer.response(request)
    }
}
/** Waits for the answer, checking every 50 ms whether MapLibre cancelled the call (then null). Loads into memory go on
 *  in TileRepository.scope regardless, as iOS's detached loads do. */
suspend fun <T> awaitUnlessCanceled(call: Call, block: suspend () -> T): T?
```
- `sealed class TileRoute(val gen: Int, val tile: TileID)` with:
  - `Imagery`;
  - `Contours(scale: Double)`;
  - `Colours(range: ElevationRange?)`.
- `TileLayer(gen, kind, map: DrawnMap)` holds the bookkeeping:
  - `answered: MutableSet<TileID>` (`ConcurrentHashMap.newKeySet()`);
  - `lacking: ConcurrentHashMap<TileID, Boolean>`. Imagery: answered with a stand-in (true) or nothing (false). Contours and colours: incomplete (true) or unavailable (false). Removed when the tile is later answered with its own complete tile.
- `TileAnswer(code, body: ByteArray, type: String?, maxAge: Int?, lacking: Boolean?)` builds:
  ```kotlin
  Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(...)
      .header("Content-Type", type).apply { maxAge?.let { header("Cache-Control", "max-age=$it") } }
      .body(body.toResponseBody(type?.toMediaType()))
  ```
  Never return an empty 200: MapLibre would fail to decode it and count the tile as an error.

**Responses and what MapLibre does with them.** I read the MapLibre behaviour from `HttpRequestImpl` (it passes `Cache-Control`, `Expires` and the status to native) and from native `OnlineFileRequest`. Expiry schedules a fresh request while the tile is still in use. 204 means no content: the tile is loaded but not renderable, so MapLibre draws its loaded parent (within `maxOverscaleFactorForParentTiles`) or its children.

| Route | Outcome | Code, body | Cache-Control | Effect (= iOS) |
|---|---|---|---|---|
| imagery | own tile | 200, JPEG/PNG bytes as stored | none | shown; never asked again |
| imagery | stand-in, beyond depth | 200, enlarged crop (JPEG q90) | `max-age=600` (or what is left of the gate) | iOS 600 s retry |
| imagery | stand-in, failed or offline | 200, enlarged crop | `max-age=30` (or what is left) | iOS 30 s retry |
| imagery | nothing anywhere | 204, empty | `max-age=30` (or what is left) | MapLibre shows its own coarser tile if loaded, else the background |
| contours | lines, complete | 200, PNG | none | |
| contours | lines, incomplete | 200, PNG | `max-age=30` | edges approximated, re-asked |
| contours | blank (flat) | 200, 1×1 transparent PNG (`TileAnswer.BLANK_PNG`) | none | must **not** be 204, or MapLibre would show the parent's lines |
| contours | unavailable | 204 | `max-age=30` | parent (one level) enlarged or children: iOS's stand-in |
| colours | range `none` | 200, 1×1 transparent | none | heights loaded only (to choose a range) |
| colours | grid, complete | 200, PNG | none | |
| colours | grid, incomplete | 200, PNG | `max-age=30` | |
| colours | unavailable | 204 | `max-age=30` | |
| any | malformed URL or unknown gen | 204 | none | never asked again |
| any | call cancelled | throws `IOException("Canceled")` | | MapLibre has already dropped it |

When a refreshed answer replaces a tile, MapLibre keeps showing the old image until the new one is decoded. Tiles therefore improve in place with no blank: this replaces iOS's new-layer swap for arrivals.

## 2.6 `SatelliteRenderer` on Android: per-tile answers

```kotlin
object SatelliteRenderer {
    enum class Content { IMAGERY, CONTOURS, COLOURS }
    val decoded = RecentCache<TileID, Bitmap>(64)               // stand-in crops and the loupe only
    private val contourImages = RecentCache<String, Pair<ByteArray, Boolean>>(240) // "scale/z/x/y" → (png, complete)
    private val noContours = RecentCache<String, Boolean>(4000)
    val heightGrids = RecentCache<TileID, Pair<ElevationGrid, Boolean>>(400)
    private val colourImages = RecentCache<String, ByteArray>(200)  // "low_high/z/x/y" → png
    private val standInJpegs = RecentCache<Pair<TileID, TileID>, ByteArray>(256)
    private val loads = Any()   // lock for the gates and in-flight maps below
    private val loading = HashMap<TileID, Deferred<NearestImagery?>>()
    private val retryAfter = HashMap<TileID, Pair<Double, Boolean>>()       // until (s), beyondDepth
    private val contoursLoading = HashMap<String, Deferred<ContourRenderer.Tile>>(); private val contoursRetryAfter = HashMap<String, Double>()
    private val gridsLoading = HashMap<TileID, Deferred<HeightGrids.Tile>>(); private val gridsRetryAfter = HashMap<TileID, Double>()

    suspend fun answer(route: TileRoute, layer: TileLayer): TileAnswer
    fun level(pixelsPerWorld: Double): Int
    fun heightExtremes(r: WorldRect, level: Int, onScreen: List<XY>): Extremes?   // as Swift, data class Extremes(low, high, complete)
    fun drawImagery(r: WorldRect, level: Int, canvas: Canvas, target: RectF)     // the loupe
    fun reload()        // forget retryAfter except beyondDepth; clear contoursRetryAfter and gridsRetryAfter
    fun trim(count: Int) { decoded.trim(count); contourImages.trim(count); heightGrids.trim(count) }
}
```
Time is `SystemClock.uptimeMillis() / 1000.0`. Loads run in `TileRepository.scope` (`SupervisorJob() + Dispatchers.IO`) and are shared per tile through the `loading` maps.

**Imagery:**
1. `ImageryMemory.tiles.value(t)`, which marks the tile as used → own.
2. If `retryAfter[t]` is not active:
   - `urgent = layer.map.focuses.imagery.level.let { it >= 0 && t.z <= it - 3 }`. These are MapLibre's prefetch tiles three levels up.
   - `r = load(t, urgent).await()`. `load` does exactly what Swift's `load` does: `nearestImagery`, then `ImageryMemory.tiles.set(r.data, r.tile)`, then the gate `30/600 s` if `r?.tile != t`, then `DrawnMap.announce(r.tile, IMAGERY)`.
   - If `r?.tile == t` → own.
3. Otherwise `a = TileFallback.ancestor(t, maxLevels = 10) { ImageryMemory.tiles.contains(it) || decoded.contains(it) }`:
   - found → stand-in crop with `max-age` = what is left of the gate, at least 1 s, `lacking = true`;
   - not found → 204 with the same max-age, `lacking = false`.

**Stand-in crop** (on `Work.prepare`):
- Decode a: `decoded`, else `BitmapFactory.decodeByteArray` from memory, then `decoded.set`.
- `k = t.z − a.z`, `side = a.width / 2^k`, `src = RectF((t.x − (a.x shl k))·side, (t.y − (a.y shl k))·side, …+side, …+side)`.
- Draw onto a 256×256 `ARGB_8888` bitmap with `Matrix.setRectToRect(src, RectF(0,0,256,256), FILL)` and `Paint(FILTER_BITMAP_FLAG)`.
  - This samples the whole ancestor, so filtering crosses the crop edge and neighbouring crops meet without seams, as iOS's single enlarged image does.
  - It also handles sub-pixel crops down to 10 levels.
- `compress(JPEG, 90)`; cache in `standInJpegs`.

**Contours** (key `"scale/z/x/y"`):
- `noContours` → blank.
- `contourImages` has the tile complete → PNG.
- The gate (`contoursRetryAfter`) is active → the incomplete PNG with `max-age` = what is left, else 204 with `max-age` = what is left.
- Otherwise await the shared load: `ContourRenderer.shared.tile(t, scale)`:
  - `Lines(png, complete)` → `contourImages.set`; announce if complete or new; gate 30 s if incomplete.
  - `Blank` → `noContours.set`, `contourImages.remove`.
  - `Unavailable` → gate 30 s.
- Answer according to the table in 2.5.

**Colours:**
- Take the grid from `heightGrids`; if it is incomplete or missing and the gate is not active, await the shared `loadGrid(t)`:
  - `HeightGrids.shared.tile(t)`;
  - store if complete or none held;
  - `gridsRetryAfter` 30 s unless complete;
  - announce `COLOURS` if complete or new. The announcement posts to the main thread → `DrawnImagery.updateColourRange()`.
- Range `none` → blank (the heights are loaded).
- No grid → 204 with `max-age=30`.
- Otherwise `colourImages["$low_$high/$t"] ?: colourPng(grid, range)` (2.8), with `max-age=30` if the grid is incomplete.

**Loupe (`drawImagery`):**
- For each spot at `level`: its decoded tile, else decode it (`Work.prepare`, announce) or `load(t, urgent = true)`.
- Draw `TileFallback.ancestor(t, 12, has)` clipped to the spot.
- `PointAdjuster` invalidates on `DrawnMap.onArrival(_, IMAGERY)`.

## 2.7 `TileRepository` on Android

```kotlin
class TileRepository private constructor(context: Context) {
    companion object { lateinit var shared: TileRepository; fun init(context: Context) }
    enum class Layer(val fileName: String) { IMAGERY("imagery.mbtiles"), TERRAIN("terrain.mbtiles") }
    data class AreaRef(val id: UUID, val directory: File, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    data class NearestImagery(val tile: TileID, val data: ByteArray, val beyondDepth: Boolean)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val browseClient: OkHttpClient
    fun setAreas(refs: List<AreaRef>); fun closeArea(id: UUID)
    fun offlineTile(t: TileID, layer: Layer): ByteArray?
    suspend fun nearestImagery(t: TileID, urgent: Boolean = false): NearestImagery?
    suspend fun imageryInBackground(t: TileID): ByteArray?
    suspend fun probeRecovery(): Boolean
    fun resetFailureState(); fun trimForBackground(); fun restoreAfterBackground(); fun trimMemory()
    suspend fun demTile(t: TileID): DEMTile?
    suspend fun demTiles(zoom: Int, minX: Double, minY: Double, maxX: Double, maxY: Double): Pair<Map<TileID, DEMTile>, Boolean>
    suspend fun elevation(p: GeoPoint): Double?
}
```
All logic, constants and locking follow 1.3 to 1.5 line for line. Use `synchronized(lock)` for the state; requests in flight are `Deferred`s in maps (`imageryInFlight` with `priority`, `id`, `started`; `demInFlight`).

**Browse client (OkHttp):**
```kotlin
OkHttpClient.Builder()
    .cache(Cache(File(context.cacheDir, "tiles-http"), 1L shl 30))
    .connectTimeout(15, SECONDS).readTimeout(15, SECONDS).writeTimeout(15, SECONDS)
    .connectionPool(ConnectionPool(10, 5, MINUTES))
    .build()
```
- The disk cache replaces `URLCache` and honours the servers' caching headers, as the protocol cache policy did.
- OkHttp has no memory cache: `ImageryMemory` plays that part. So `restoreAfterBackground()` has nothing to do; keep it, with a comment.
- Calls run as `withContext(Dispatchers.IO) { client.newCall(req).execute() }` after `RequestLimiter.acquire`. Cancelling the coroutine cancels the `Call` (`invokeOnCancellation`).

**`HTTPTileFetcher(client, url: (TileID) -> String) : TileFetching`:**
- `companion suspend fun fetch(url, client): TileFetchResult`, with the iOS classification and `User-Agent: SatMap/1.0 (Android)`.
- An `IOException` gives `offline` if `!Connectivity.shared.isOnlineNow`, otherwise `failed`. This is iOS's "not connected" versus everything else.
- `ImageTools.looksLikeImage`: the same magic bytes. The offline downloader uses this fetcher too.

**`RequestLimiter(slots)`:**
- Coroutine version of the Swift class: `free`, `waiting` as three `ArrayDeque<CancellableContinuation<Unit>>`, `background ≤ 2`.
- `acquire` pops newest first per priority.
- A waiter cancelled while queued is removed. If it was resumed after cancellation, it releases its slot.
- Limiters: `imageryLimiter = RequestLimiter(10)`, `terrainLimiter = RequestLimiter(4)`, chosen by `url.host.startsWith("elevation")`.

**Offline areas:**
- Each `OpenArea` opens its stores lazily, retrying the open at most every 10 s (the file appears when its download starts).
- Readers are `MBTiles(driver, path, create = false)` through the app's `AndroidSqlDriver` (`SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)`; WAL, so the map reads while a download writes).
- Lookups only in areas whose world bounds intersect the tile; the first hit wins.
- `MBTiles` serialises its own operations, as in Swift.

**Executors (`Work`):**
- `Work.contours = Dispatchers.Default.limitedParallelism(3, "satmap.contours")`: DEM decode, grids, marching squares, contour and colour bitmaps and PNG encoding.
- `Work.prepare = Dispatchers.Default.limitedParallelism(2, "satmap.prepare")`: imagery decode and JPEG encode.

**`LRUCache`:** synchronised `LinkedHashMap(capacity, 0.75f, accessOrder = true)` with `removeEldestEntry` above capacity.

## 2.8 Drawing with `android.graphics` (`TileOverlays.kt`)

**`ContourRenderer.draw(lines, cells, interval, s, smooth, labels): ByteArray?`**
```kotlin
val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
// paths: Path().moveTo/lineTo of (x·k, y·k), k = 256/cells; ContourGeometry.chaikin(...) first when smooth (t.z > 15)
val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
// pass 1: HALO, width (isIndex ? 1.3s : 0.7s) + 0.9s, per path; pass 2: INDEX/MINOR at 1.3s/0.7s
```

| Constant | Value |
|---|---|
| `MINOR` | `Color.argb(0.85f, 1f, 0.84f, 0.32f)` |
| `INDEX` | `Color.argb(1f, 1f, 0.74f, 0.16f)` |
| `HALO` | `Color.argb(0.38f, 0f, 0f, 0f)` |
| `LABEL_FILL` | `Color.argb(1f, 1f, 0.93f, 0.70f)` |
| `LABEL_STROKE` | `Color.argb(0.75f, 0f, 0f, 0f)` |

`isIndex = abs(Math.IEEEremainder(level, index)) < 0.01`.

**Labels** (only when `(t.x + t.y) % 2 == 0`, index lines only):
- `Paint(ANTI_ALIAS_FLAG)`, `textSize = 8.5·s`.
- Typeface: `Typeface.create(Typeface.DEFAULT, 600, false)` on API 28+, else `Typeface.create("sans-serif-medium", NORMAL)`.
- `w = measureText(text)`, `h = fontMetrics.descent − fontMetrics.ascent`.
- Margin, `labelAnchor` and skip rules as in 1.8.
- Draw:
  ```
  canvas.save()
  translate(ax, ay)
  rotate(toDegrees(angle))
  drawRect(RectF(−w/2 − 2s, −h/2, w/2 + 2s, h/2), clearPaint)   // clearPaint: xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
  drawText(text, −w/2, −h/2 − fm.ascent, fill)                    // fill: LABEL_FILL
  drawText(text, −w/2, −h/2 − fm.ascent, outline)                 // style STROKE, strokeWidth = 0.03 × textSize, LABEL_STROKE; stroke over fill, as Core Text's negative stroke width
  canvas.restore()
  ```
- Encode with `bitmap.compress(PNG, 100, out)`.

**Caches:**
- `cache = LruCache<String, ByteArray>(32 shl 20) { sizeOf = value.size }`;
- `emptyCache = RecentCache<String, Boolean>(4000)`;
- in-flight `Deferred`s per key.
- `screenScale` is set from the pixel ratio; it is only used if no scale is given.

**`colourPng(grid, range)`** (on `Work.contours`):
```kotlin
val n = grid.nodes                                             // 65
val small = Bitmap.createBitmap(n, n, ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(ElevationColours.pixels(grid, range))) }
// ARGB_8888 is premultiplied RGBA bytes in memory: the same layout as ElevationColours.pixels
val out = Bitmap.createBitmap(256, 256, ARGB_8888); val half = 256f / (n - 1) / 2f   // 2 px
Canvas(out).drawBitmap(small, null, RectF(-half, -half, 256 + half, 256 + half), Paint(Paint.FILTER_BITMAP_FLAG))
return out.png()
```
The bilinear filter gives iOS's smooth interpolation between nodes, with node pixels centred on the nodes. Alpha 0.5 is in the pixels, so the layer's opacity is 1.

## 2.9 `DrawnImagery` on Android

```kotlin
class DrawnImagery(private val mapView: MapView, private val map: MapLibreMap,
                   private val isVisible: () -> Boolean, private val terrain: () -> TerrainMode,
                   private val contourScale: Double) {   // the contour tiles' s: URL, legend and minzoom
    val drawnMap = DrawnMap()
    var colourRange: ElevationRange? = null; private set
    var onKey: ((TerrainKey) -> Unit)? = null
    fun addTo(builder: Style.Builder)
    fun regionWillChange(); fun regionDidChange(); fun visibleRegionChanged()
    fun reload(); fun terrainChanged(mode: TerrainMode); fun becameVisible()
}
```

**Hooks (MapController):**
- `map.addOnCameraMoveStartedListener { imagery.regionWillChange() }`
- `map.addOnCameraIdleListener { imagery.regionDidChange() }`
- `map.addOnCameraMoveListener { imagery.visibleRegionChanged() }` (also once after the style loads)
- `isVisible` = the activity is started and the map is shown
- `becameVisible()` on `onStart`
- the terrain mode `StateFlow` → `terrainChanged`
- `reloadImagery()` → `reload()`

The phone's `MapController` passes `TileLevels.PHONE_CONTOUR_SCALE`. The car map (Android Auto) creates its own `DrawnImagery` with its own pixel ratio, and its contour scale from it (`TileLevels.contourScale(pixelRatio)`).

**Geometry:**
- `levels()`: null if `mapView.width < 100·pixelRatio`; otherwise `(TileLevels.level(zoom, imageryTileSize), TileLevels.level(zoom, 182))`.
- `visibleWorldRect()`: world points of `projection.visibleRegion` farLeft, farRight, nearLeft and nearRight. x is unwrapped relative to the centre (`x + round(cx − x)`) so the rect is continuous across the date line; take min and max.
- `screenPoints()`: 81 points, `projection.fromScreenLocation(PointF(...)).wrap()` → `WebMercator.world`, over the view inset by 1 px.

**Focus:**
- `visibleRegionChanged` → `setFocus` (rect grown by 1/4 on each side, both levels) and `reportKey()`.
- `DrawnMap.setFocus(rect, level, contourLevel)` stores an immutable `@Volatile` `Focuses`, read by the interceptor.

**Map stopped:**
- `regionDidChange`: `moving = false`; if a replacement is owed, schedule it; then `mapStopped()`.
- `mapStopped()`: after 400 ms, debounced by `stopCount`, if the map is visible and has levels: `setFocus`, `ImageryPrefetch.mapStopped(visibleWorldRect(), levels.imagery)`, `updateColourRange()`.

**Colour range:** `updateColourRange()` and `reportKey()` exactly as in 1.9 and 1.10. Use `Handler(Looper.getMainLooper()).postDelayed(…, 1600)` for the re-check, `s = contourScale` (the one the tiles' URL has) for intervals, and "the shown colours layer's range" in place of `renderer.colourRange`.

**Replacing a layer** (range change, reload). This is the Android counterpart of iOS's swap without flashing.
- State:
  - `shown[kind] = (gen, range)`;
  - `incoming: Map<TileKind, Gen>`;
  - `wanted: MutableSet<TileKind>`;
  - `moving`, `movingSince`, `swapScheduled`, `lastSwap`, `swapWaiting`.
- `scheduleSwap()`: the same timing as iOS (≥ 0.25 s, about once a second, not while moving < 3 s, owed while invisible).
- `swap()`:
  - If not visible → owed.
  - If a replacement is under way → `scheduleSwap()`.
  - Otherwise, for every wanted kind:
    - **IMAGERY:** new source and layer at full opacity, `style.addLayerBelow(new, shownImageryId)`. The opaque old layer covers it while it loads, exactly as iOS put the new layer under the old one.
    - **COLOURS / CONTOURS** (translucent, so the two must not stack): new layer with `rasterOpacity(0.01f)`, `style.addLayerAbove(new, shownId)`.
      - At opacity 0.01 MapLibre still loads its tiles; at 0 it would not, because a layer with no render pass is not needed.
      - The old layer stays at full opacity and the 1 % tint does not show.
      - If the colours layer is **hidden** (mode without colours), replace it at once: remove the old one, add the new one, nothing visible.
  - Each new source is registered in `TileLayers`.
- `retire(ticks)` every 100 ms:
  - Ready when every incoming kind has had all tiles at its level under `screenPoints()` answered (`TileLayer.answered`), or the level is below the source's minzoom.
  - When ready, after 400 ms more: imagery → remove the old layer and source; colours or contours → `new.setProperties(rasterOpacity(1f))` and remove the old layer and source. All in one main-thread block, so it is drawn in the same frame. Then `TileLayers.remove(oldGen)`.
  - Not ready after 20 visible ticks (invisible ticks don't count) → remove the incoming layers and sources, keep the old ones, put the kinds back in `wanted`, and `scheduleSwap()`.

**What asks for a replacement:**
- `updateColourRange` → COLOURS, when `shown[COLOURS].range != colourRange`.
- `reload()` → every kind whose `TileLayer.lacking` has a tile in focus: imagery focus for IMAGERY; contour focus for CONTOURS (if shown) and COLOURS (if shown). Then `swap()` at once, as iOS does.
- `terrainChanged(mode)`:
  1. If colours are on → `updateColourRange()`.
  2. Set `visibility(VISIBLE/NONE)` on the shown colours and contours layers (and on incoming ones).
  3. If colours are on and the hidden layer's range is stale → replace it before showing it.
  4. `reportKey()`.

  Turning terrain off means MapLibre no longer needs those sources, so it asks for no elevation: "with neither shown, no elevation is downloaded".
- `becameVisible()` → run the owed replacement and `updateColourRange()`.

## 2.10 `ImageryPrefetch`, `ImageryMemory`, `DrawnMap`

- **`ImageryMemory`:** `tiles = RecentCache<TileID, ByteArray>(900)`.
  - `enteredBackground()`: `tiles.trim(150)`; `SatelliteRenderer.trim(0)`.
  - `trimMemory()`: `tiles.trim(100)`; `SatelliteRenderer.trim(32)`.
- **`ImageryPrefetch.mapStopped(visible: WorldRect, level: Int)`:** as 1.7, with tiles wrapping in x and a 64-tile limit per axis.
  - Loads run in `TileRepository.scope` with `imageryInBackground` at background priority.
  - The iOS on-screen "prepare" (decode) step is dropped: MapLibre already holds what is on screen.
  - Because MapLibre's prefetch asks for the tiles three levels up, these surrounding tiles are answered from memory at once after a pan or zoom.
- **`DrawnMap`:** holds the focus, and `var onArrival: ((TileID, Content) -> Unit)?`, called on the main thread.
  - `companion fun announce(t, content)` posts to every live map (a list of `WeakReference`s).

## 2.11 Memory and lifecycle

- **`ProcessLifecycleOwner` ON_STOP** (the iOS app entering the background): `TileRepository.shared.trimForBackground()`, `ImageryMemory.enteredBackground()`, `ContourRenderer.shared.clearCaches()`.
- **ON_START:** `restoreAfterBackground()`.
- **`Application.onTrimMemory(level)`:** when `level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND` (from API 34 only `UI_HIDDEN` and `BACKGROUND` are delivered), and in **`onLowMemory()`**:
  - `TileRepository.shared.trimMemory()`;
  - `ImageryMemory.trimMemory()`;
  - `ContourRenderer.shared.clearCaches()`;
  - `mapView.onLowMemory()` for each live map. MapLibre then drops its own tile cache.

**Android sizes:**

| Cache | Size |
|---|---|
| `ImageryMemory.tiles` | 900 (~36 MB) |
| `decoded` | 64 bitmaps (~16 MB native) |
| contour PNGs | 32 MiB |
| `contourImages` | 240 |
| `noContours` | 4000 |
| `heightGrids` | 400 (~7 MB) |
| `colourImages` | 200 |
| `standInJpegs` | 256 |
| `demCache` | 160 (~40 MB of `FloatArray`) |
| OkHttp disk cache | 1 GiB |

Everything fits in the normal heap; `largeHeap` is not needed.

## 2.12 What MapLibre does for free (Swift not ported)

- **Stand-ins while loading:** the parent enlarged or the children shrunk (`updateRenderables`) from its tile pyramid and tile cache. This replaces `standIns`, `sharperTiles`, `quarters`, the coarse clipping in `draw`, and the "late" re-announcements.
- **No grey, no flicker:** a tile is replaced in place when a fresher answer arrives. All of iOS's machinery for MapKit's repaints goes: `SatelliteDrawnOverlay`, `retire`'s painted checks, `hasPainted`, `paintedShare`, `ownSpotsReady`, `swapDeferrals`, the "drawn layer hasn't painted" swap in `regionDidChange`, `wouldImprove`/`tileArrived`, and `loadStandIns`. Swaps remain only for a colour-range change and for `reload()`.
- **Decoding and GPU textures:** MapLibre's worker threads replace `prepare`, `Work.prepare` for display, `decoded`'s growth (`makeRoom`, `DrawnMap.spots`) and `lastPaintScale`.
- **The tile three levels up first:** `setPrefetchZoomDelta(3)`.
- **Retries of stand-ins after 30 s or 600 s:** `Cache-Control: max-age`, re-asked while visible.
- **Cancelling requests** for tiles that left the screen.
- **Rotation-correct cover, date-line wrap, and enlarging beyond maxzoom.**
- **Toggling contours or colours:** layer `visibility`, not a new layer.

## 2.13 Tests and checks on a device

**JVM / Robolectric** (`:app:testDebugUnitTest`):
- `TileLevels.level` against iOS's formulas across zoom 0–21 for pixel ratios 1, 2, 2.625, 3, 3.5. Imagery may differ from `round(Zp + log2 s)` only within ±0.01 of a .5 boundary. Contours may differ from `ceil(Zp − 1e-6)` only within 0.008 above an integer.
- `contourMinZoom`: 12 at s = 2, 13 at 2.63 and 3.
- The phone's contour scale is 3 whatever its density: the legend at z18 shows the iPhone's 10 m / 50 m, the URL says `300`, and the source starts at z13.
- `TileUrls.format`/`parse` round trip, including `none` and negative or fractional ranges.
- `RequestLimiter` ordering (urgent > normal > background, newest first, background ≤ 2) and cancellation.
- The `TileInterceptor` answer table, through an `OkHttpClient` with the interceptor and a fake repository: codes, `Cache-Control`, the 1×1 blank, cancellation.
- `ContourRenderer` colour ints and widths; `colourPng` puts edge pixels at the node values.
- `ElevationColours.range` hysteresis cases (core tests).

**On the emulator in CI**, verify that:
1. Non-power-of-two `tileSize` values (85, 98, 182) work and give the levels above (log `TileOperation` via `MapView.addOnTileActionListener`).
2. 204 + `max-age=30` shows the parent and re-asks after about 30 s.
3. A refreshed 200 replaces a tile without blanking.
4. A layer at `rasterOpacity(0.01f)` loads its tiles, and switching it to 1 shows no flash.
5. Offline (airplane mode) with `MapLibre.setConnected(true)` still shows downloaded areas.
6. Screenshots match `docs/screenshots/01-map.jpg`, `22-contours-and-colours.jpg`, `23-elevation-colours.jpg` and `11-contours-off.jpg` for line colours, widths, label size and the 50 % tint.
