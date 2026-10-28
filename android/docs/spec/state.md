# SatMap Android spec: app state and location

Covers `SatMap/App/AppModel.swift`, `SatMapApp.swift`, `AppPaths.swift` (with `Format`), `Connectivity.swift`, `Config.swift` (`LocationPolicy`), `SatMap/Location/LocationRecorder.swift`, and how `MapScreen` / `MapController` observe them.

Kotlin package root: `com.jacobusoettle.satmap`.

| Swift | Kotlin (android/app/src/main/kotlin/com/jacobusoettle/satmap/…) |
| --- | --- |
| `App/SatMapApp.swift` (`SatMapApp`, `AppDelegate`) | `app/SatMapApp.kt`: `SatMapApplication : Application`. `MainActivity` (already in `MainActivity.kt`) hosts the UI. |
| `App/AppModel.swift` | `app/AppModel.kt`: `MeasureMode`, `CrosshairAim`, `MapScale`, `DroneReport`, `MapLegend`, `HeightLookup`, `PendingArea`, `ActiveSheet`, `AppModel` |
| `App/AppPaths.swift` | `app/AppPaths.kt`: `AppPaths`, `Format` |
| `App/Connectivity.swift` | `app/Connectivity.kt` |
| `App/Config.swift` | `app/Config.kt`: `TileSources`, `LocationPolicy` |
| `Location/LocationRecorder.swift` | `location/LocationRecorder.kt` |
| (none: Android only) | `app/AppLifecycle.kt` (UIApplication state), `app/Defaults.kt` (UserDefaults), `location/LocationService.kt` (foreground service), `location/WakeTimer.kt` (timers that fire while the CPU sleeps), `app/PermissionFlow.kt` (runtime permissions, used by `MainActivity`) |

---

## 1. Behaviour (what the Swift does: port it exactly)

### 1.1 Startup and app lifecycle

- **At launch** (`AppDelegate.didFinishLaunching`), `AppModel.shared` is created whether or not there is UI. Its init then does the following, in order:
  1. Sets `scale.unit` from `distanceUnit`.
  2. Loads the track (`TrackLog(AppPaths.track).loadAll()`).
  3. Seeds the recorder with the last track point.
  4. Wires `recorder.onTrackPoint`, `onFix` and `onHeading`.
  5. Registers the lifecycle observers (below).
  6. Subscribes to connectivity and to `offline.completedRevision`.
  7. Starts the 30 s retry timer.
  8. Calls `recorder.start()`.
- **Memory warning:** `TileRepository.shared.trimMemory()` and `ImageryMemory.trimMemory()`.
- **Opening a .kml/.kmz** (open-in / share): `model.kml.importFile(url)`. If the file has bounds, `model.map?.zoom(to: bounds)`. Otherwise `model.sheet = .blocks`, which shows why the import failed.
- **Dark theme:** the UI is always dark (`.preferredColorScheme(.dark)`).
- **AppModel lifecycle observers:**
  - **didBecomeActive:**
    - If `markerElevation == nil || lastElevation == nil`, call `refreshPointElevations()`.
    - If there is a `recorder.location`, call `refreshUserElevation(loc)`.
    - If `selectedElevation == nil`, call `selectedLookup.retry()`.
  - **didEnterBackground:** stop the retry timer, `TileRepository.shared.trimForBackground()`, `ImageryMemory.enteredBackground()`.
  - **willEnterForeground:** `TileRepository.shared.restoreAfterBackground()`, start the retry timer, and call `requestReload()` if `reloadPending`.
- **"Foreground" means `applicationState != .background`.** With CarPlay showing SatMap, the app is foreground (even active) while the phone is locked.
- **The phone's own map** (`MapController.isShown`) follows the phone's window scene only. It is true from the scene's willEnterForeground to its didEnterBackground.

### 1.2 Published state of `AppModel`

All of it is main-thread only.

| Property | Type | Initial | Changed by | Persisted |
| --- | --- | --- | --- | --- |
| `track` (plain, not published) | `[TrackPoint]` | `TrackLog.loadAll()` | `appendTrack`, `clearTrack` | `track.bin` |
| `trackRevision` | Int | 0 | +1 on every append and on clear | no |
| `following` (public set) | Bool | true | Set true by `centerOnUser`. Set false by: the map when the user's finger moves it; `zoom(to:)`; press-to-move a point; `startDrawingArea`; `measure(from:)` | no |
| `terrainMode` (public set) | `TerrainMode` | `TerrainMode.saved(mode: defaults["terrainMode"], showContours: defaults["showContours"] as Bool?)` | Terrain button (`next`), CarPlay button. Every set writes `"terrainMode"` = rawValue, then calls `updateDroneReport()`. | yes |
| `distanceUnit` (public set) | `DistanceUnit` | `DistanceUnit(rawValue: defaults["distanceUnit"]) ?? .metric` | `nextDistanceUnit()`. Every set writes `"distanceUnit"` and sets `scale.unit`. | yes |
| `heightUnit` (public set) | `HeightUnit` | `HeightUnit(rawValue: defaults["heightUnit"]) ?? .metres` | `nextHeightUnit()`. Every set writes `"heightUnit"`. | yes |
| `droneReport` | `DroneReport(you: String?, pin: String?)` | empty | `updateDroneReport()`; published only when it changes | no |
| `measure` | `MeasureMode` | `.off` | `setMeasure`, `movePoint` | no |
| `markerElevation`, `lastElevation` | Double? | nil | `setMeasure`, `movePoint`, `refreshPointElevations` | no |
| `userElevation` | Double? | nil | `userLookup` (published only when the rounded value changes) | no |
| `aim` | `CrosshairAim` (own object) | – | MapController `updateAim()` → `aim.set(centre)`; `setMeasure` → `aim.set(nil)` | no |
| `scale` | `MapScale` (`label`, `width`; `maxWidth` 110) | – | MapController `update(metersPerPoint:)`; `unit` set from `distanceUnit` | no |
| `legend` | `MapLegend` (`key: TerrainKey`) | – | MapController (`imagery.onKey`) | no |
| `selectedPoint` (public set) | GeoPoint? | nil | Map tap; KML or aviation point tap; measuring; `movePoint`; the ✕ on the coordinates | no |
| `selectedElevation` | Double? | nil | `selectedLookup` | no |
| `selectedName` | String? | nil | Cleared when `selectedPoint` changes; set by `pointTapped` | no |
| `isDrawingArea`, `lassoActive`, `draftArea`, `pendingArea`, `sheet` | as in Swift | false, true, nil, nil, nil | Offline-area functions and the UI | no |
| `map` (weak) | `MapController?` | – | Set by the map view on creation | – |
| `carPlayMap` (weak) | `CarPlayMapController?` | – | CarPlay scene | – |

**`selectedPoint` didSet:**
- If the value is unchanged, return.
- Otherwise:
  1. `selectedName = nil`.
  2. If not `pointMoving`, call `lookUpSelectedHeight()`.
  3. Call `updateDroneReport()`.

**`lookUpSelectedHeight()`:**
- If `selectedLookup.point == selectedPoint`, return (the height is already known).
- Otherwise `selectedElevation = nil`, then `selectedLookup.set(selectedPoint)`.

**`pointTapped(p, name)`:** `selectedPoint = p`, then `selectedName = name` (nil when the name is nil or empty).

**`movePoint(which, p, done)`**, called at most 15 times a second while dragging, and once with `done = true`:
- **`.selected`:**
  1. `pointMoving = !done`.
  2. `selectedPoint = p`.
  3. If done, `lookUpSelectedHeight()`.
- **`.measure(i)`** (ignore it if `i >= count`):
  1. `pointMoving = !done`.
  2. `measure = measure.moving(i, p)`. This is set directly, not through `setMeasure`.
  3. `selectedPoint = p`.
  4. If done:
     1. `lookUpSelectedHeight()`.
     2. If `i == 0`, `markerElevation = nil`.
     3. If `count >= 2 && i == count-1`, `lastElevation = nil`.
     4. `refreshPointElevations()`.

### 1.3 Measuring: state machine

`MeasureMode`: `off | aiming | placed(A) | path([A,B,…]) | closed([A,B,C,…])`.

- `points` is empty for off and aiming.
- `moving(i, p)` replaces point i; it does nothing for off and aiming.
- `label(i)` is bijective base-26: A…Z, AA…ZZ, AAA…

| Action | From | Result |
| --- | --- | --- |
| `measureButtonTapped()` | off | `setMeasure(.aiming)` |
| | aiming, placed, path | `addPointAtCrosshair()` |
| | closed | `clearMeasure()` |
| `mapTapped(p)` (after MapController's own handling, below) | off | `selectedPoint = p` |
| | otherwise | `addPointAtCrosshair()`; the tapped spot itself is ignored |
| `addPointAtCrosshair()` | – | `c = map.centerCoordinate` (screen centre); `addMeasurePoint(c)` |
| `addMeasurePoint(p)` | path with 3 or more points, and `map.pointsApart(p, A) < 20` pt | `closeMeasureShape()` |
| | placed or path, and `pointsApart(p, last) < 6` pt | nothing (no 0 m leg) |
| | otherwise | `selectedPoint = p`, then: placed(a) → `path([a,p])`; path(pts) → `path(pts+p)`; else → `placed(p)` |
| `closeMeasureShape()` | path with 3 or more points | `closed(pts)` |
| `undoMeasurePoint()` | closed(pts) | `path(pts)` |
| | path with more than 2 points | `path(dropLast)` |
| | path with 2 points | `placed(pts[0])` |
| | placed | `aiming` |
| | off, aiming | return without changing anything |
| | after any change | `selectedPoint = measure.points.last` |
| `measure(from: p)` (Go to coordinates) | any | `sheet = nil`; `following = false`; `setMeasure(.placed(p))`; `selectedPoint = p`. If you are within 50 km, `map.zoom(to: GeoBounds([me, p]))`; otherwise `map.center(on: p, animated)`. |
| `clearMeasure()` (✕) | any | `setMeasure(.off)`; `selectedPoint = nil` |

**MapController, before it calls `mapTapped`:**
- While measure is off, a tap within 26 pt of a KML point's dot (or on its label) calls `pointTapped(point, name)`.
- In drone mode, while measure is off, a tap on an aviation place calls `pointTapped(place.point, "<label> · <kind title>")`.
- On a path with 3 or more points, a tap within 30 pt of A calls `closeMeasureShape()`.

**`setMeasure(m)`:**
1. `old = measure.points`.
2. `measure = m`.
3. `recorder.measuring = measureIsFromYou`, where measureIsFromYou is aiming or placed. The recorder's setter runs on every assignment (see 1.10).
4. If `m != .aiming`, `aim.set(nil)`.
5. If `new.first != old.first`, `markerElevation = nil`.
6. If `new.count < 2 || new.last != old.last || old.count < 2`, `lastElevation = nil`.
7. `refreshPointElevations()`.
8. If measureIsFromYou and there is a location, `refreshUserElevation(loc)`.

**Derived values:**
- `markerDistance`:
  - placed: distance from the current fix to A, or nil without a fix.
  - path: `Geo.pathLength(pts)`.
  - closed: `Geo.pathLength(pts + [pts[0]])`.
- `measuredArea`: `Geo.ringArea(pts)`, for closed only.

**`centerOnUser()`:**
- If `following` and there is a location: `map.zoom(onto: loc, metersAcross: 300)`.
- Otherwise: `following = true`, and if there is a location, `map.center(on: loc, animated: true)`.

### 1.4 Height lookups and retry timing

All lookups call `TileRepository.shared.elevation(at:)` (async), off the main thread, and deliver the result on the main thread.

**`CrosshairAim`** (published: `point`, `elevation`, `elevationPoint`):
- **`set(p)`:**
  1. If unchanged, return.
  2. `point = p`; `retries = 0`.
  3. If p is nil: `session += 1`; `elevation = nil`; `elevationPoint = nil`; `lookedUp = nil`.
  4. `lookUp()`.
- **`lookUp()`:**
  - Runs one lookup at a time, always for the latest spot. Guard: `!lookingUp`, `point != nil`, and `point != lookedUp`.
  - When it finishes:
    1. `lookingUp = false`.
    2. If the session is the same: `elevation = e`; `elevationPoint = p`.
    3. If `e == nil && point == p && retries < 4`: `retries += 1`, and **after 31 s**, if the session is the same, `point == p`, and the app is **active**, call `retry()`.
    4. Call `lookUp()` again to catch a newer point.
- **`retry()`:** guard `point != nil && elevation == nil`; then `lookedUp = nil`; `lookUp()`.
- **UI:** while `elevationPoint != point`, the aim panel's height is dimmed (opacity 0.6; the caption 0.5).

**`HeightLookup(found:)`** (`selectedLookup`, `userLookup`):
- Same as CrosshairAim, but nothing is published; it calls `found(p, e)` instead.
- `retry()` only needs `point != nil`.
- The retry is again 31 s, at most 4 times, only while active.

**`userLookup.found`:**
- `first = userElevation == nil && e != nil`.
- Set `userElevation = e` only if `e?.rounded() != userElevation?.rounded()`. Use Swift's schoolbook `rounded()` (core `Double.rounded()`).
- If first and `markerElevation == nil`, call `refreshPointElevations()`.

**`refreshUserElevation(loc)`** sets `userLookup` to the fix's point. It is called:
- from `locationUpdated`, only if the app is **active** and `map?.isOnScreen == true`;
- from `setMeasure` (when measuring from you);
- from didBecomeActive.

**`refreshPointElevations(attempt = 0)`:**
- Does nothing for closed.
- Wanted points: A when `markerElevation == nil`; the last point when there are 2 or more points and `lastElevation == nil`.
- Each wanted point gets its own lookup. When it finishes:
  - Store the result only if the point is still A, or still the last point (with 2 or more points).
  - If `e == nil && attempt < 4`, then **after 15 s**, if the app is active, call `refreshPointElevations(attempt + 1)`.

### 1.5 Drone report

**`updateDroneReport()`** is called when `terrainMode` is set, when `selectedPoint` changes, and on every used fix:
- If `!terrainMode.showsAviation`: reset to an empty `DroneReport()` (published only if different).
- If `AviationStore.shared.index == nil`: call `AviationStore.shared.load { updateDroneReport() }` and return.
- Otherwise, for you (the current fix) and for the pin (`selectedPoint`):
  - `limit = 150 * Aviation.metresPerNauticalMile`.
  - `found = index.nearest(p, within: limit) { kind.isAerodrome && kind != .heliport && !code.isEmpty } ?? index.nearest(p, within: limit)`.
  - `report = Aviation.report(p, from: index.points[found.index])`.
- Published only when the result changes.

### 1.6 Units and terrain mode persistence

- **Distance unit:** cycles metric → imperial → nautical. Changed by a tap on any distance (aim panel, measure panel, perimeter) or on the scale bar label.
- **Height unit:** cycles metres ↔ feet. Changed by a tap on any height bar.
- **Terrain mode:** contours → contoursAndColours → colours → drone → off. A toast shows `TerrainMode.title` for 1.8 s beside the button.
- **`TerrainMode.saved`** (core) also reads the legacy `"showContours"` boolean.
- **UserDefaults keys in the whole app:**

| Key | Values |
| --- | --- |
| `terrainMode` | contours, contoursAndColours, colours, drone, off |
| `showContours` | legacy, read only |
| `distanceUnit` | metric, imperial, nautical |
| `heightUnit` | metres, feet |
| `kmlFileColours` | Bool; missing means true |
| `offlineMaxZoom` | Int; default 22 |
| `camera` | `[centerLat, centerLon, latDelta, lonDelta]`; missing means first launch |
| `zoomScript` | launch argument only |
| `backgroundTaskFailedStyles` | iOS background-task bookkeeping |

### 1.7 Track

- **`appendTrack(p)`:** `track.append(p)`; `trackLog.append(p)` (asynchronous, 32-byte records); `trackRevision += 1`.
- **`clearTrack()`:** `track = []`; `trackLog.clear()` (synchronous); `recorder.seed(lastRecorded: nil)`; `trackRevision += 1`.
- **UI:** long-press My location → menu item "Clear recorded track" (trash icon, destructive) → confirmation "Clear the recorded track?" → "Clear track" (destructive).

### 1.8 Tile reload orchestration

- **Retry timer:** every 30 s (tolerance 10 s) in the foreground. If `await TileRepository.shared.probeRecovery()` is true, call `requestReload()`.
- **`requestReload()`:**
  1. `reloadPending = true`.
  2. If in the background, stop here; the reload happens on willEnterForeground.
  3. Wait `max(0, 20 − (now − lastReload))` s.
  4. Then, if still pending and in the foreground:
     1. `reloadPending = false`; `lastReload = now`.
     2. `SatelliteRenderer.reload()` (forget the retry-after of failed tiles); `map?.reloadImagery()`; `carPlayMap?.reload()`.
     3. `aim.retry()`.
     4. If `userElevation == nil`, `userLookup.retry()`. If `selectedElevation == nil`, `selectedLookup.retry()`.
- **Triggers:**
  - Connectivity `isOnline` goes false → true (deduplicated, first value dropped). First `TileRepository.shared.resetFailureState()`, then `requestReload()`.
  - `offline.completedRevision` changes (first value dropped).
  - The retry timer.

### 1.9 `LocationPolicy` constants (copy exactly)

| Constant | Value |
| --- | --- |
| `foregroundInterval` | 2 s |
| `backgroundInterval` | 10 s |
| `backgroundWakeLead` | 3 s |
| `stationaryTimeout` | 120 s |
| `movementThreshold` | 15 m |
| `sleepPeekIntervalForeground` | 60 s |
| `sleepPeekIntervalBackground` | 120 s |
| `sleepPeekIntervalMeasuring` | 15 s |
| `peekTimeout` | 15 s |
| `maxTrackAccuracy` | 20 m |
| `minTrackSpacing` | 3 m |
| `segmentBreakSeconds` | 300 s |
| `segmentBreakMeters` | 200 m |

Numbers inside `LocationRecorder` (also exact):

| Rule | Value |
| --- | --- |
| Foreground gate slack | 0.15 s (use a fix if ≥ 1.85 s since the last used) |
| Maximum fix age | < 20 s |
| Background: fix must be taken after the GPS was woken | `timestamp ≥ acquiringSince − 0.5 s` |
| Background: wait for the 10 s mark | `now − lastUsedAt ≥ 9.5 s`, or waited ≥ 20 s |
| Background: accuracy | ≤ 20 m; or waited ≥ 6 s and ≤ 20 m; or waited ≥ 20 s (any) |
| Coarse fix must not replace a good one | new > 100 m, current ≤ 100 m, current younger than 60 s → not published (but `lastUsedAt` and `noteMovement` already ran) |
| After wake: first track point | needs ≤ 10 m for 30 s |
| Track spacing | `d ≥ max(3, accuracy)` |
| Segment break | gap > 300 s **and** d > 200 m (the first point ever starts a segment) |
| Movement anchor | only fixes ≤ 50 m; new anchor when `d > max(15, accuracy)` |
| Stationary check | every 15 s (tolerance 5) while active |
| Motion veto | motion says moving and `now − motionMovingSince < 600 s` → don't sleep |
| Cold start | no fix ≤ 50 m yet and `now − searchingSince < 600 s` → don't sleep |
| Peek length | 15 s if a ≤ 50 m fix in the last 600 s, else 45 s |
| Peek fix | ≤ 25 m |
| Asleep without a good anchor | no anchor, or anchor accuracy > 25 m → wake and use the fix |
| Peek "moved" threshold | measuring and foreground: `max(8, 1.5×acc)`; otherwise `max(20, 2×acc)` |
| Not moved on a peek | `location = fix`, `onFix(fix)` (no track, `lastUsedAt` unchanged), `endPeek()` |
| Coarse jump wake (asleep, not peeking) | acc < 1500 and `d(anchor) > max(300, 2×acc + anchor.acc)` |
| Motion, medium confidence | wake after 6 s unless contradicted |
| Compass filter | 2° |

### 1.10 Location power policy: the state machine

**State:**
- `power`: active | sleeping (published).
- `location` (published): the last fix used.
- `authorization` and `reducedAccuracy` (published).
- Private: `isForeground`, `started`, `gpsResting`, `peeking`, `acquiringSince`, `lastUsedAt`, `lastRecorded`, `preciseFixNeededUntil`, `movementAnchor`, `lastMovementAt`, `lastGoodFixAt`, `searchingSince`, `sleepAnchor`, the motion flags, and timers: `stationaryTimer`, `dutyTimer`, `peekTimer`, `peekEndTimer`, `pendingMotionWake`.

**Desired accuracy (`applyAccuracy`), mapped to Android in 2.5:**

| power | peeking | foreground | iOS accuracy | Meaning |
| --- | --- | --- | --- | --- |
| sleeping | no | any | 3 km | GPS off, coarse fixes only |
| sleeping | yes | yes / no | best / 10 m | GPS on |
| active | – | yes | best | GPS on, one fix used per 2 s |
| active | – | no, resting | 3 km | GPS off between background fixes |
| active | – | no, acquiring | 10 m | GPS on until a fix is used |

**`start()`:**
- Read the authorization.
- notDetermined: request When-In-Use.
- authorized: `beginUpdates()`.
- Otherwise: nothing.

**`beginUpdates()`:**
- Only once, and only when not in the background.
- `started = true`; `lastMovementAt = searchingSince = now`.
- Apply accuracy; start updates (never stopped afterwards); `startMotion()`; start the stationary timer.

**Fix handling:**
- Drop the fix if it has no accuracy or is ≥ 20 s old.
- Sleeping → `handleWhileSleeping`.
- Active and foreground → 2 s gate → `use`.
- Active and background:
  - ignore while resting;
  - otherwise apply the background rules in 1.9;
  - then `use(loc)` and `restUntilNextBackgroundFix()`.
- **`use`:**
  1. `lastUsedAt = now`.
  2. `noteMovement`.
  3. Coarse-replacement check (1.9).
  4. `location = loc`.
  5. `record(loc)`.
  6. `onFix(loc)`.

**`restUntilNextBackgroundFix()`** (only in the background and active):
- `gpsResting = true`; apply.
- `dutyTimer` at `lastUsedAt + 10 − 3` (tolerance 0.5) → `startAcquiring()`, which sets `gpsResting = false`, `acquiringSince = now`, and applies.

**`evaluateStationary()`** runs every 15 s while started and active. It applies the vetoes in 1.9, then calls `enterSleep()` when `now − lastMovementAt ≥ 120`.

**`enterSleep()`:**
- `power = sleeping`; `sleepAnchor = location`.
- Stop the stationary and duty timers; `gpsResting = false`; `peeking = false`.
- Apply; `schedulePeek()`.

**`schedulePeek()`:** the interval is:
- background: 120 s;
- foreground and measuring: 15 s;
- otherwise: 60 s.

The timer's tolerance is 10% of the interval; it calls `startPeek()`.

**`startPeek()`:** `peeking = true`; apply; `peekEndTimer` (15 or 45 s) → `endPeek()`.

**`endPeek()`:** `peeking = false`; apply; `schedulePeek()`.

**`wake()`** (only from sleeping):
- `power = active`; `peeking = false`; cancel the peek, peek-end and pending motion wake.
- `lastMovementAt = searchingSince = now`; `movementAnchor = nil`; `lastUsedAt = distant past`.
- `preciseFixNeededUntil = now + 30`.
- `gpsResting = false`; `acquiringSince = now`.
- Apply; start the stationary timer.

**Motion (CMMotionActivity):**
- Translating means walking, running, cycling, or automotive and not stationary.
- **Translating with confidence above low:**
  - If motion didn't already say moving, `motionMovingSince = now`. Then `motionSaysMoving = true`.
  - If sleeping:
    - high confidence → `wake()`;
    - medium confidence → a pending wake after 6 s (only one at a time).
- **Stationary and not automotive:** `motionSaysMoving = false`; `motionMovingSince = nil`; cancel the pending wake.
- **Unknown:** ignored.
- **Permission:** asked the first time through a history query. Without it, the GPS peeks still detect movement.

**`measuring` setter** (on every assignment): if `measuring && isForeground && power == sleeping`, call `startPeek()` right away.

**App lifecycle in the recorder:**
- **didEnterBackground:** `isForeground = false`.
  - If active: `startAcquiring()` (take the next fix now, then rest).
  - Otherwise: `peeking = false`; cancel the peek end; apply; `schedulePeek()` (now 120 s).
- **willEnterForeground:** `isForeground = true`; cancel the duty timer; `gpsResting = false`; apply. If sleeping, `startPeek()`.
- **didBecomeActive:** `isForeground = true`. If not started, `start()`. If started, `startMotion()` (the permission may have been answered).

**Authorization callback:**
- Update `authorization` and `reducedAccuracy` (`accuracyAuthorization == .reducedAccuracy`).
- If authorized, `beginUpdates()`.

**Hints** (MapScreen, under the coordinate and drone rows):
- Denied or restricted: "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services."
- Otherwise, with reduced accuracy: "Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location."

**UI tied to the recorder:**
- Your coordinate bar shows only with a fix and not while drawing an area. Its icon is "location" (outline) while sleeping and "location.fill" otherwise.
- `HeightBar(userElevation, gpsAltitude)`. The GPS altitude is shown (marked "GPS") only when there is no elevation and the vertical accuracy is in (0, 25] m.
- Your dot is grey while sleeping (MapController `powerChanged`).

### 1.11 Compass

- Runs only while `headingWanted`. MapController sets it to `isShown`.
- `headingFilter` is 2°.
- The heading is for the top of the screen (`headingOrientation` follows the screen orientation).
- Value: `trueHeading` if ≥ 0, else `magneticHeading`. When `headingAccuracy < 0`, the value is nil (no arrow).
- When `headingWanted` turns false: stop updates and send `onHeading(nil)`.
- The arrow angle on screen is `(heading − mapBearing + 360) % 360`, recomputed when the map turns.

### 1.12 Connectivity

- `State(isOnline, isExpensive)`. `isExpensive` means cellular or a personal hotspot.
- `state` is published on the main thread when it changes.
- `isOnlineNow` is a thread-safe snapshot, read by `TileRepository` on worker threads.
- **Consumers:**
  - AppModel reload (1.8).
  - `OfflineManager.networkChanged()` re-queues `waitingForNetwork` areas when `canDownloadNow`, which is `isOnline && (allowCellular || !isExpensive)`.
  - `TileRepository.networkUsable` / `noteRetryNeeded`.
  - UI texts:
    - "Height: loading elevation…" vs "Height: no elevation data here offline (download this area)".
    - HeightBar "… m" vs "– m", and the spoken "Loading" vs "No elevation data here offline".
    - Offline sheet "Waiting for internet" vs "Waiting for Wi-Fi".

### 1.13 AppPaths and Format

- **Folders:**
  - `root = Application Support/SatMap`. Writable while locked after the first unlock.
  - `offline = root/Offline`, excluded from backup. It holds `areas.json`, `Restoring/`, and one `<UUID>` folder per area.
  - `kml = root/KML`.
  - `track = root/track.bin`.
  - `offlineArea(id) = offline/<id.uuidString>` (upper-case), excluded from backup.
  - Directories are created on first use.
- **Sizes:**
  - `size(of:)` sums the allocated size of the files.
  - `freeDiskSpace()` is the volume's available capacity for important usage, or nil.
- **`Format`:**
  - `distance(m, unit) = unit.format(m)`.
  - `bytes`: ByteCountFormatter `.file`.
  - `hectares`: ≥ 100 → `count(rounded) + " ha"`; < 1 → `"%.2f ha"`; otherwise `"%.1f ha"`.
  - `groundResolution`: `m = 156543.03·cos(lat)/2^z`; < 0.95 → `"N cm per pixel"`; < 10 → `"%.1f m per pixel"`; otherwise `"%.0f m per pixel"`.
  - `coordinate`: `"%.6f, %.6f"`.
  - `height`: `count(unit.whole(m)) + " " + symbol`.
  - `heightDifference`: the same with `abs`.
  - `count`: localized decimal grouping.

---

## 2. Android design

### 2.1 Process, lifecycle, entry points

**`SatMapApplication : Application`** (in `SatMapApp.kt`; manifest `android:name=".app.SatMapApplication"`):

```kotlin
override fun onCreate() {
    super.onCreate()
    Defaults.init(this)            // SharedPreferences (UserDefaults)
    AppPaths.init(this)
    Connectivity.init(this)
    AppLifecycle.init()            // ProcessLifecycleOwner (+ car sessions later)
    // MapLibre.getInstance(this) + MapLibre.setConnected(true) + HttpRequestUtil.setOkHttpClient(...)  (map subsystem, see 2.8)
    AppModel.init(this)            // creates the model and starts recording, independent of the UI
}
override fun onLowMemory() { trim() }
override fun onTrimMemory(level: Int) { if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) trim() }
private fun trim() { TileRepository.shared.trimMemory(); ImageryMemory.trimMemory() }
```

**`AppLifecycle`** (Android only) mirrors `UIApplication.State` and its three notifications, for the whole process.

The phone's activities (through `ProcessLifecycleOwner`) and the Android Auto `Session` both count. With Android Auto showing SatMap, the app is foreground even while the phone is locked, as with CarPlay.

```kotlin
object AppLifecycle {
    enum class AppState { BACKGROUND, INACTIVE, ACTIVE }       // UIApplication.State
    interface Observer { fun willEnterForeground() {}; fun didBecomeActive() {}; fun didEnterBackground() {} }
    private val _state = MutableStateFlow(AppState.BACKGROUND)
    val state: StateFlow<AppState> = _state.asStateFlow()
    val isForeground get() = state.value != AppState.BACKGROUND
    val isActive get() = state.value == AppState.ACTIVE
    fun init()                         // ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { … })
    fun observeCar(session: androidx.car.app.Session)   // called by the Android Auto subsystem
    fun addObserver(o: Observer)       // called in registration order, on the main thread
}
```

- The state is the maximum of the phone's and the car's `Lifecycle.State`:
  - `RESUMED` → `ACTIVE`;
  - `STARTED` → `INACTIVE`;
  - lower → `BACKGROUND`.
- Notifications are sent when the state crosses STARTED or RESUMED. When it jumps from BACKGROUND to ACTIVE, send `willEnterForeground` before `didBecomeActive`.
- `ProcessLifecycleOwner` sends ON_STOP 700 ms after the last activity stops; that is fine.

| iOS | Android |
| --- | --- |
| `UIApplication.willEnterForegroundNotification` | `AppLifecycle.Observer.willEnterForeground` (process ON_START, or car session STARTED) |
| `didBecomeActiveNotification` | `didBecomeActive` (ON_RESUME) |
| `didEnterBackgroundNotification` | `didEnterBackground` (ON_STOP of both) |
| `applicationState != .background` | `AppLifecycle.isForeground` |
| `applicationState == .active` | `AppLifecycle.isActive` |
| Window scene foreground/background (`MapController.isShown`) | `MainActivity` `onStart`/`onStop` → `mapController.sceneWillEnterForeground()` / `sceneDidEnterBackground()` |
| `applicationDidReceiveMemoryWarning` | `onLowMemory` / `onTrimMemory` (above); also `mapView.onLowMemory()` (map subsystem) |

**`MainActivity`:**
- Calls `enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))`, so the status bar icons are white over the imagery, as in the screenshots.
- `setContent { SatMapTheme(darkColorScheme(...)) { MapScreen(AppModel.shared) } }`, always dark.
- `android:launchMode="singleTask"`, so a share lands in the open map.
- **KML import:** handled in `onCreate` (when `savedInstanceState == null`) and in `onNewIntent`. The intent is `ACTION_VIEW` or `ACTION_SEND` (`EXTRA_STREAM`) with a .kml/.kmz URI. It calls `model.kml.importFile(uri) { file -> file?.document?.bounds?.let { model.map?.zoom(it) } ?: model.setSheet(ActiveSheet.BLOCKS) }`. The intent filters belong to the KML subsystem.
- **Launch arguments**, the CI counterpart of iOS `-key value`: `adb shell am start -n com.jacobusoettle.satmap/.MainActivity --es terrainMode colours --ez zoomScript true`.
  - In `onCreate`, call `Defaults.setLaunchArguments(intent.extras)`, then `AppModel.shared.applyLaunchArguments()`.
  - `applyLaunchArguments()` re-reads `terrainMode`, `distanceUnit` and `heightUnit` from `Defaults` and sets the flows directly, without writing them back, as iOS reads its argument domain only in init.

### 2.2 `AppModel` (process-wide singleton, not a ViewModel)

The model must outlive activities: recording runs without UI, and Android Auto and the download service use it.

```kotlin
class AppModel private constructor(private val app: Application) : AppLifecycle.Observer {
    companion object { lateinit var shared: AppModel; fun init(app: Application) { shared = AppModel(app) } }
    /** Main thread only; collectors elsewhere use Dispatchers.Main. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val recorder = LocationRecorder(app)
    val kml = KMLLibrary(app)
    val offline = OfflineManager(app)
    private val trackLog = TrackLog(AppPaths.track)
    private val _track = ArrayList<TrackPoint>()
    val track: List<TrackPoint> get() = _track          // read on the main thread only
    private val _trackRevision = MutableStateFlow(0); val trackRevision = _trackRevision.asStateFlow()
    …
}
```

**Pattern for each Swift property:**
- `@Published private(set) var x` → `private val _x = MutableStateFlow(…)` and `val x: StateFlow<T> = _x.asStateFlow()`.
- `@Published var x { didSet {…} }` (publicly settable) → the same, plus `fun setX(v: T)`, which assigns and then runs the didSet body.
  - The Swift didSet runs on every set, even with the same value. So `setTerrainMode` always writes the preference and calls `updateDroneReport()`.
  - `setSelectedPoint` returns early on an equal value, as the Swift guard does.

| Swift | Kotlin |
| --- | --- |
| `following` | `following: StateFlow<Boolean>`, `setFollowing(v)` |
| `terrainMode` | `terrainMode`, `setTerrainMode(m)` (writes `Defaults.putString("terrainMode", m.rawValue)`, then `updateDroneReport()`) |
| `distanceUnit`, `heightUnit` | the same pattern; `setDistanceUnit` also sets `scale.unit` |
| `droneReport`, `measure`, `markerElevation`, `lastElevation`, `userElevation`, `selectedElevation`, `selectedName` | read-only StateFlows |
| `selectedPoint` | `selectedPoint`, `setSelectedPoint(p)` |
| `isDrawingArea`, `lassoActive`, `draftArea`, `pendingArea`, `sheet` | StateFlow + setter |
| `aim`, `scale`, `legend` | `CrosshairAim`, `MapScale`, `MapLegend`: small classes with their own StateFlows, so a pan or zoom recomposes only the panel or bar that collects them |
| `weak var map`, `carPlayMap` | `var map: MapController?` and `var carMap: CarMapController?`, both backed by `WeakReference` |

- **`MeasureMode`** is `sealed interface MeasureMode { data object Off; data object Aiming; data class Placed(val a: GeoPoint); data class Path(val pts: List<GeoPoint>); data class Closed(val pts: List<GeoPoint>) }`. It has `val points`, `fun moving(i, p)` and `companion fun label(i)`.
- **`ActiveSheet`** is `enum class ActiveSheet(val id: String) { BLOCKS("blocks"), OFFLINE("offline"), COORDINATES("coordinates") }`.
- **`PendingArea`** is `data class PendingArea(val id: UUID = UUID.randomUUID(), val name: String, val shape: CoverageShape)`.
- **Async work:**
  - `Task.detached { await TileRepository.shared.elevation(at: p) }` + `DispatchQueue.main.async` → `scope.launch { val e = TileRepository.shared.elevation(p) … }`. `elevation` is a main-safe suspend function; `TileRepository` switches to its own dispatchers.
  - `asyncAfter(31)` / `asyncAfter(15)` → `scope.launch { delay(31_000); if (!AppLifecycle.isActive) return@launch; … }`.
  - The `session`, `retries` and `lookingUp` bookkeeping is ported as is.
- **Retry timer:** `retryJob = scope.launch { while (isActive) { delay(30_000); if (withContext(Dispatchers.IO) { TileRepository.shared.probeRecovery() }) requestReload() } }`.
  - Started in `willEnterForeground`, and in init only if `AppLifecycle.isForeground`.
  - Cancelled in `didEnterBackground`.
  - This differs from iOS on purpose: iOS also starts it in init when launched into the background, where it would run until the next didEnterBackground.
- **`requestReload`:** `scope.launch { delay(waitMs); … }`. Several pending launches are possible, as in Swift; the `reloadPending` flag lets only one reload happen.
- **Init subscriptions:**

```kotlin
Connectivity.shared.state.map { it.isOnline }.distinctUntilChanged().drop(1).filter { it }
    .onEach { TileRepository.shared.resetFailureState(); requestReload() }.launchIn(scope)
offline.completedRevision.drop(1).onEach { requestReload() }.launchIn(scope)
AppLifecycle.addObserver(this)
recorder.start()
```

- The Swift `objectWillChange` re-publishing is not needed: each composable collects the flows it reads.
- **Distances:** use `Geo.distance` (core) where Swift uses `CLLocation.distance(from:)`, for `markerDistance`, the recorder's movement checks, and `measure(from:)`'s 50 km test. Write that down in the code. Apple's algorithm is unspecified, and the difference is under 0.5%.
- **`pointsApart`** returns **dp** (`hypot(px) / resources.displayMetrics.density`), so the 6, 20, 26 and 30 point thresholds keep their size.

### 2.3 `Defaults` (UserDefaults → SharedPreferences, same keys)

```kotlin
object Defaults {
    lateinit var prefs: SharedPreferences       // context.getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
    private var arguments: Bundle? = null        // launch extras: read first, never written (iOS argument domain)
    fun setLaunchArguments(extras: Bundle?)
    fun string(key: String): String?; fun bool(key: String): Boolean?; fun int(key: String): Int?   // null when unset
    fun putString(key: String, v: String); fun putBoolean(...); fun putInt(...)                      // apply()
}
```

| Key | Type | Writer | Notes |
| --- | --- | --- | --- |
| `terrainMode` | String | AppModel | rawValue of `TerrainMode` |
| `showContours` | Boolean | none (read only) | `TerrainMode.saved(mode, showContours = if (contains) getBoolean else null)` |
| `distanceUnit` | String | AppModel | metric, imperial, nautical |
| `heightUnit` | String | AppModel | metres, feet |
| `kmlFileColours` | Boolean | KMLLibrary | missing means true |
| `offlineMaxZoom` | Int | NewAreaView | default `TileSources.imageryMaxZoom` (22) |
| `camera` | String `"lat,lon,latDelta,lonDelta"` (`Locale.ROOT`) | MapController | SharedPreferences has no double arrays |
| `zoomScript` | Boolean (launch extra only) | – | |
| `locationPermissionAsked`, `motionPermissionAsked`, `notificationPermissionAsked` | Boolean | PermissionFlow | Android only (Android can't tell "never asked" from "denied") |

`backgroundTaskFailedStyles` is not used on Android (the iOS `BGContinuedProcessingTask` bookkeeping).

### 2.4 `LocationRecorder` on `LocationManager`

```kotlin
class LocationRecorder(private val context: Context) : AppLifecycle.Observer {
    enum class Power { ACTIVE, SLEEPING }
    enum class Authorization { NOT_DETERMINED, DENIED, SERVICES_OFF, AUTHORIZED }
    val location: StateFlow<GpsFix?>; val power: StateFlow<Power>
    val authorization: StateFlow<Authorization>; val reducedAccuracy: StateFlow<Boolean>
    var onTrackPoint: ((TrackPoint) -> Unit)?; var onFix: ((GpsFix) -> Unit)?; var onHeading: ((Double?) -> Unit)?
    var headingWanted: Boolean      // setter: updateHeading() on change
    var screenRotation: Int         // Surface.ROTATION_*; setter re-applies the remap on change
    var measuring: Boolean          // setter runs on every assignment (as the Swift didSet)
    fun seed(lastRecorded: TrackPoint?)
    fun start()                     // safe to call repeatedly
    fun authorizationChanged()      // after a permission result / ON_START / MODE_CHANGED_ACTION
    fun startMotion()               // idempotent; after the activity-recognition result
    fun stop()                      // the notification's Stop action (= iOS force-quit): everything off, started = false
}

/** A position as the Swift uses CLLocation: immutable (android.location.Location is mutable). */
data class GpsFix(
    val lat: Double, val lon: Double,
    val horizontalAccuracy: Double,     // Location.accuracy
    val altitude: Double?,              // metres above SEA LEVEL (see "Altitude"), null when unknown
    val verticalAccuracy: Double?,      // of that altitude
    val timestamp: Double,              // seconds since 1970 (Location.time / 1000.0): TrackPoint.time
    val elapsed: Double,                // seconds, SystemClock.elapsedRealtime clock (LocationCompat.getElapsedRealtimeMillis)
) { val point get() = GeoPoint(lat, lon) }
```

Everything runs on the main thread. Location listeners use `ContextCompat.getMainExecutor(context)`; sensors and alarms use `Handler(Looper.getMainLooper())`.

#### Time base

All recorder times (`now`, `lastUsedAt`, `acquiringSince`, `lastMovementAt`, …) are **`SystemClock.elapsedRealtime() / 1000.0`**. This clock is monotonic and keeps counting through deep sleep.

- Fix age: `now − fix.elapsed < 20`.
- `loc.timestamp >= acquiringSince − 0.5` → `fix.elapsed >= acquiringSince − 0.5`.

This matters on Android. On re-registration, Android S+ may immediately deliver a cached location up to one interval old. This check rejects it, exactly as the Swift rejects a fix taken before the GPS was woken.

#### Emulating "accuracy 3 km between fixes": what is registered in each state

Android has no accuracy dial on the GPS: a `GPS_PROVIDER` request is either there (GPS on) or not (GPS off). The iOS rule "updates are never stopped" exists only to keep a When-In-Use app alive in the background. On Android, **the foreground service (2.5) keeps the process and its while-in-use location access alive**, so the GPS request can be removed between fixes.

`applyAccuracy()` becomes `applyRequests()`. It computes the wanted set below, compares it with what is registered, and re-registers only what changed. The Swift equivalent is `if manager.desiredAccuracy != desired`.

| power | peeking | foreground | GPS listener (`GPS_PROVIDER`) | Network listener (`NETWORK_PROVIDER`, if `LocationManagerCompat.hasProvider`) | Passive listener (`PASSIVE_PROVIDER`) |
| --- | --- | --- | --- | --- | --- |
| active | – | yes | interval **2000 ms**, `QUALITY_HIGH_ACCURACY` | only while the GPS is searching (no GPS fix in the last 30 s): interval 5 s, `QUALITY_BALANCED_POWER_ACCURACY`. iOS also shows a Wi-Fi fix quickly at a cold start. | – |
| active | – | no, acquiring | interval **1000 ms**, HIGH | the same searching rule | – |
| active | – | no, resting | **none (GPS off)**: the "3 km" | – | – |
| sleeping | no | any | **none** | interval **60 s**, min interval 30 s, `QUALITY_LOW_POWER` | interval 0, min interval 10 s |
| sleeping | yes | any | interval **1000 ms**, HIGH | – | – |

- **API:** `LocationManagerCompat.requestLocationUpdates(lm, provider, LocationRequestCompat.Builder(intervalMs).setQuality(q).setMinUpdateDistanceMeters(0f).build(), mainExecutor, listener)` and `LocationManagerCompat.removeUpdates(lm, listener)`. Use one `LocationListenerCompat` per provider, so each can be removed alone. Never set `setMaxUpdateDelayMillis` (no batching).
- **Routing of fixes:**
  - **GPS listener:** `didUpdateLocations(fix)`, as in Swift. It also sets `lastGpsFixAt`, then calls `applyRequests()` so the searching network request goes.
  - **Network listener:**
    - sleeping → `handleWhileSleeping(fix)` (not peeking: the coarse-jump rule);
    - active → `didUpdateLocations(fix)`. The Swift pipeline then decides: the coarse-replacement rule, `waited ≥ 20`, and so on.
  - **Passive listener:** only while sleeping and not peeking → `handleWhileSleeping(fix)`. It gives free fixes made for other apps. It is never registered next to our own GPS request, which would deliver our GPS fixes twice and use up the 2 s slot.
- **Approximate only** (`reducedAccuracy`, Android 12+): the "GPS listener" uses `LocationManager.FUSED_PROVIDER` with `QUALITY_LOW_POWER` at the same intervals. Its positions are kilometres off anyway, and nothing reaches the track (> 20 m). Without the fused provider, use the network provider. The rest of the policy runs unchanged, as on iOS.
- **Ported unchanged:** `didUpdateLocations`, `use`, `record`, `noteMovement`, `evaluateStationary`, `enterSleep`, `wake`, `schedulePeek`, `startPeek`, `endPeek` and `handleWhileSleeping`, with every number in 1.9. `CLLocation.distance(from:)` becomes `Geo.distance`.

#### Timers that fire while the phone sleeps: `WakeTimer`

iOS `Timer`s keep firing in the background because the location updates keep the app running. On Android, a `Handler` or coroutine `delay` uses `uptimeMillis`, which **stops while the CPU suspends**. With the GPS removed between fixes, the 7 s duty timer could then stretch indefinitely.

So all recorder timers use `AlarmManager` listener alarms on `ELAPSED_REALTIME_WAKEUP`. That covers stationary (15 s), duty (`lastUsedAt + 7 s`), peek (15, 60 or 120 s), peek end (15 or 45 s) and the motion debounce (6 s).

```kotlin
class WakeTimer(context: Context, private val tag: String, private val action: () -> Unit) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AlarmManager.OnAlarmListener { scheduled = false; action() }
    var scheduled = false; private set
    fun schedule(afterSeconds: Double) {
        cancel()
        val at = SystemClock.elapsedRealtime() + (afterSeconds * 1000).toLong()
        try { am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "satmap:$tag", listener, handler) }
        catch (e: SecurityException) { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "satmap:$tag", listener, handler) }
        scheduled = true
    }
    fun cancel() { am.cancel(listener); scheduled = false }
}
```

- The repeating 15 s stationary check reschedules itself in its action.
- Listener alarms are delivered only while the process lives; the foreground service keeps it alive.
- A process with a foreground service is in the *active* standby bucket, so there is no alarm quota.
- Recent Android versions allow exact listener alarms without `SCHEDULE_EXACT_ALARM`. Android 12–13 may refuse them. Then `set()` is used: inexact, about 75% of the delay as a window. Write that in the code. **Do not** declare `SCHEDULE_EXACT_ALARM`.
- **Deep Doze** defers these alarms. Deep Doze only happens while the phone lies still, which is sleep mode. The significant-motion sensor (a wake-up sensor) both ends Doze and wakes the recorder.
- The time an alarm fires is `elapsedRealtime`-based. `dutyTimer` fires at `lastUsedAt + (10 − 3)` s; compute the delay as `max(0, lastUsedAt + 7 − now)`. Android pushes alarms set under 5 s ahead to 5 s. That is fine: the GPS is already searching then, and at worst the fix is a little later.

#### Motion wake (CMMotionActivity → sensors, no Google Play services)

| iOS | Android |
| --- | --- |
| High-confidence walking, running, cycling or driving → `wake()` now | `Sensor.TYPE_SIGNIFICANT_MOTION` (wake-up, one-shot, no permission): `sensorManager.requestTriggerSensor(trigger, sensor)`. `onTrigger` → `noteMoving()`; if sleeping, `wake()`. Re-arm in `onTrigger`, because it cancels itself after firing. Armed from `startMotion()` until `stop()`. |
| Medium confidence → wake after 6 s unless contradicted | `Sensor.TYPE_STEP_DETECTOR` (`getDefaultSensor(TYPE_STEP_DETECTOR, true)` if a wake-up variant exists, else the plain one; needs `ACTIVITY_RECOGNITION` on API 29+). It is registered with `SENSOR_DELAY_NORMAL` and the main handler. Each step → `noteMoving()`; add the step time to `recentSteps` (keep the last 6 s). If sleeping: ≥ 6 steps in the last 6 s → `wake()` (steady walking is the high-confidence case). Otherwise, if no pending wake, schedule `pendingMotionWake` in 6 s; when it fires: ≥ 3 steps in the last 6 s → `wake()`, else nothing (the steps stopped: the contradiction). |
| Stationary → `motionSaysMoving = false`, cancel the pending wake | `motionSaysMoving` is derived: a step in the last 15 s, or significant motion in the last 60 s. When it is found false, clear `motionMovingSince`. The pending wake checks the steps itself when it fires. |
| `motionMovingSince`, 600 s veto in `evaluateStationary` | `noteMoving()` sets `motionMovingSince = now` when `motionSaysMoving` was false. The veto is the same. |
| No motion permission or hardware: GPS peeks only | The same. If the sensor is null (emulators), or the permission is denied, the peeks and coarse jumps remain. |

`startMotion()` is idempotent. It arms significant motion once. It registers the step detector once the permission is granted. It is called from `beginUpdates()` and again after the permission result. This is the iOS `didBecomeActive → startMotion()`.

#### Lifecycle (recorder as `AppLifecycle.Observer`)

The Swift `didEnterBackground`, `willEnterForeground` and `didBecomeActive` are ported verbatim, with `applyAccuracy()` → `applyRequests()` and the timers as `WakeTimer`s.

The initial `isForeground` is `AppLifecycle.isForeground`. In `Application.onCreate` that is false. The first activity start then sends `willEnterForeground` and `didBecomeActive`, and `start()` → `beginUpdates()` runs there.

**`beginUpdates()`:**
- Guard: `!started && AppLifecycle.isForeground && authorization == AUTHORIZED`.
- `started = true`.
- `LocationService.start(context)` (2.5).
- `lastMovementAt = searchingSince = now`.
- `applyRequests()`; `startMotion()`; start the stationary timer.

**`stop()`:**
- Remove all location listeners, cancel the trigger sensor, unregister the step listener, cancel all `WakeTimer`s.
- `started = false`; `power = ACTIVE`.
- Do not clear `location`.

#### Authorization

```
fine   = checkSelfPermission(ACCESS_FINE_LOCATION) == GRANTED
coarse = checkSelfPermission(ACCESS_COARSE_LOCATION) == GRANTED
authorization = when {
    !fine && !coarse -> if (Defaults.bool("locationPermissionAsked") == true) DENIED else NOT_DETERMINED
    !LocationManagerCompat.isLocationEnabled(lm) -> SERVICES_OFF   // iOS folds this into .denied
    else -> AUTHORIZED }
reducedAccuracy = authorization == AUTHORIZED && !fine
```

- **Re-read on:** `start()`, every `willEnterForeground`/`didBecomeActive`, a permission result, `LocationListenerCompat.onProviderEnabled/Disabled`, and a `LocationManager.MODE_CHANGED_ACTION` receiver. Register it with `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)`; system broadcasts are still delivered.
- **If it becomes AUTHORIZED** → `beginUpdates()`.
- **If fine is gained** (the user switched on precise location in Settings) → `applyRequests()`, which switches FUSED → GPS.
- **Revoking** a permission kills the process; nothing more to handle.
- **Hints in MapScreen** (same `Hint` look). Android settings paths differ by maker, so a **tap opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`**, or `ACTION_LOCATION_SOURCE_SETTINGS` for SERVICES_OFF. That tap is an Android addition.
  - DENIED: "Location is off for SatMap. Turn it on in Settings › Apps › SatMap › Permissions › Location."
  - SERVICES_OFF: "Location is off. Turn it on in Settings › Location."
  - Reduced accuracy: "Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › SatMap › Permissions › Location (Use precise location)."

#### Altitude (for the HeightBar's GPS fallback)

`Location.altitude` on Android is height above the WGS84 **ellipsoid**; iOS gives height above **sea level**. The difference is about 30 m in South Africa.

`GpsFix.altitude` / `verticalAccuracy`:
1. If `LocationCompat.hasMslAltitude(loc)`: `getMslAltitudeMeters`, with accuracy from `getMslAltitudeAccuracyMeters` if present, else `getVerticalAccuracyMeters`.
2. Otherwise, on API 35+:
   - Warm up once on `Dispatchers.IO` with `AltitudeConverter().addMslAltitudeToLocation(context, aLocation)` (it loads assets and must stay off the main thread).
   - Then call `AltitudeConverter.tryAddMslAltitudeToLocation(loc)` on the main thread for each fix (it doesn't block) and read the MSL values.
3. Otherwise null: no "GPS" height shown.

MapScreen's `gpsAltitude` = `altitude` if `verticalAccuracy` is in (0, 25].

#### Compass (`TYPE_ROTATION_VECTOR`)

- **`updateHeading()`:**
  - `headingWanted` true: register `TYPE_ROTATION_VECTOR`, else `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, else nothing (iOS `headingAvailable()` false). Use `SENSOR_DELAY_UI` and the main handler.
  - `headingWanted` false: unregister and call `onHeading(null)`.
- **`onSensorChanged`:**

```kotlin
SensorManager.getRotationMatrixFromVector(r, event.values)
val (ax, ay) = when (screenRotation) {
    Surface.ROTATION_90  -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y }
SensorManager.remapCoordinateSystem(r, ax, ay, out); SensorManager.getOrientation(out, o)
var h = (Math.toDegrees(o[0].toDouble()) + 360) % 360                         // magnetic
location.value?.let { h = (h + GeomagneticField(it.lat.toFloat(), it.lon.toFloat(),
    (it.altitude ?: 0.0).toFloat(), System.currentTimeMillis()).declination + 360) % 360 }  // true once there's a position
```

- **Unreliable readings:** while `event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE` or `SENSOR_STATUS_NO_CONTACT` (also through `onAccuracyChanged`), send `onHeading(null)` once. This is iOS `headingAccuracy < 0`.
- **Filter:** send only when the circular difference from the last value sent is ≥ 2°, or when coming from null.
- **No calibration screen:** Android has no system calibration screen. The arrow just stays hidden, as on iOS while the compass can't tell.
- **`screenRotation`:** set by the map side (2.7) from `ContextCompat.getDisplayOrDefault(activity).rotation`.
  - On `onConfigurationChanged`.
  - In a `DisplayManager.DisplayListener.onDisplayChanged`, because a 90°↔270° flip is not a configuration change.

### 2.5 `LocationService`: background recording with "While using" permission

This is the counterpart of iOS `UIBackgroundModes: location` plus `allowsBackgroundLocationUpdates` and `showsBackgroundLocationIndicator`.

- A location request from an app in the background gets nothing without `ACCESS_BACKGROUND_LOCATION`. The exception is **a foreground service of type location that was started while the app was in the foreground**: the app keeps its while-in-use access for as long as the service runs.
- So `ACCESS_BACKGROUND_LOCATION` is neither declared nor requested, as iOS uses When-In-Use only.

```kotlin
class LocationService : Service() {
    companion object {
        const val CHANNEL = "recording"; const val ID = 1; const val ACTION_STOP = "com.jacobusoettle.satmap.STOP_RECORDING"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { AppModel.shared.recorder.stop(); stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
}
```

- **When it is started:** only from `beginUpdates()`, that is, foreground and permission granted. It runs for as long as recording is started, including while sleeping. iOS likewise never stops its updates.
  - `startForegroundService` from the background throws `ForegroundServiceStartNotAllowedException` (API 31+). Catch it, along with `SecurityException`, and carry on without the service; recording continues while the app is visible.
- **`START_NOT_STICKY`:** if the system kills the process, it is not restarted in the background. Android 14+ wouldn't allow location access then anyway. Recording starts again when the app is opened, which is the iOS behaviour.
- **Swiping SatMap away from Recents does not stop recording** (`stopWithTask` stays false). Android doesn't impose the iOS force-quit limit.
  - The notification's **Stop** action is the way to end recording, the equivalent of an iOS force-quit. It stops it until the app is next opened; `didBecomeActive → if (!started) start()` restarts it.
  - Android 13+ also offers Stop in the foreground-services task manager. That kills the process, with the same result.
- **Notification:**
  - Channel `"recording"`, name "Track recording", `IMPORTANCE_LOW`, no badge.
  - Small icon `ic_stat_location`.
  - Title "SatMap", text "Recording your track".
  - `setOngoing(true)`, `setSilent(true)`, `setShowWhen(false)`, `CATEGORY_SERVICE`.
  - Content intent: open `MainActivity` (`FLAG_IMMUTABLE`).
  - Action "Stop": `PendingIntent.getService(…, Intent(ACTION_STOP), FLAG_IMMUTABLE)`.
  - Without `POST_NOTIFICATIONS` the service still runs; the notification is just not shown in the drawer.
- **Android Auto:** call `beginUpdates()` when the car session reaches STARTED, which makes `AppLifecycle` foreground. The Car App host binds the app with its while-in-use capabilities, so locations arrive while the car screen shows SatMap. Starting the service then may be refused (caught above); background recording then begins the next time the phone app is opened.
- **No battery-optimization exemption is requested**, for parity and Play policy.

### 2.6 Runtime permission flow (`PermissionFlow`, used by `MainActivity`)

iOS asks for location at launch, then for motion as soon as updates begin. Android adds notifications. Each permission is asked at most once per install; the system also stops showing a dialog after two denials.

```kotlin
class PermissionFlow(private val activity: ComponentActivity, private val recorder: LocationRecorder) {
    private val location = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asking = false; recorder.authorizationChanged(); next() }
    private val single = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { asking = false; recorder.startMotion(); next() }
    private var asking = false
    /** Called from MainActivity.onResume (iOS didBecomeActive) and after each answer. */
    fun next() {
        if (asking) return
        when {
            recorder.authorization.value == NOT_DETERMINED -> { Defaults.putBoolean("locationPermissionAsked", true); asking = true
                location.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)) }      // Precise/Approximate choice on 12+
            recorder.authorization.value != AUTHORIZED -> return                               // motion only after location, as iOS
            SDK_INT >= 29 && stepDetectorPresent && !granted(ACTIVITY_RECOGNITION) && Defaults.bool("motionPermissionAsked") != true ->
                { Defaults.putBoolean("motionPermissionAsked", true); asking = true; single.launch(ACTIVITY_RECOGNITION) }
            SDK_INT >= 33 && !granted(POST_NOTIFICATIONS) && Defaults.bool("notificationPermissionAsked") != true ->
                { Defaults.putBoolean("notificationPermissionAsked", true); asking = true; single.launch(POST_NOTIFICATIONS) }
        }
    }
}
```

- The `asking` guard is needed because the dialog pauses and resumes the activity, which calls `onResume` again.
- There is no rationale screen; iOS has none either.

### 2.7 Manifest (this subsystem's part)

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-feature android:name="android.hardware.location.gps" android:required="false" />
<uses-feature android:name="android.hardware.sensor.compass" android:required="false" />

<application android:name=".app.SatMapApplication"
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules" …>
    <activity android:name=".MainActivity" android:launchMode="singleTask" android:exported="true"
        android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode" … />
    <service android:name=".location.LocationService" android:exported="false"
        android:foregroundServiceType="location" />
</application>
```

MapLibre's own manifest already merges in `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, which is harmless.

### 2.8 `Connectivity` on `ConnectivityManager`

```kotlin
class Connectivity private constructor(context: Context) {
    data class State(val isOnline: Boolean, val isExpensive: Boolean)   // expensive: cellular / hotspot / metered
    companion object { lateinit var shared: Connectivity; fun init(c: Context) { shared = Connectivity(c.applicationContext) } }
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(read(cm.activeNetwork?.let(cm::getNetworkCapabilities)))
    val state: StateFlow<State> = _state.asStateFlow()
    val isOnline get() = state.value.isOnline
    @Volatile private var current = _state.value
    /** Thread-safe snapshot for use off the main thread. */
    val isOnlineNow get() = current.isOnline
    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = update(read(caps))
            override fun onLost(n: Network) = update(State(false, false))
        }, Handler(Looper.getMainLooper()))
    }
    private fun read(c: NetworkCapabilities?) = State(
        isOnline = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        isExpensive = c != null && !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    private fun update(v: State) { current = v; if (_state.value != v) _state.value = v }
}
```

- **The initial state is read synchronously.** iOS starts at "online" until the first path update. The Android default-network callback never fires when there is no network at all, so starting at "online" would keep the app online forever. Write that in the code.
- **Online means `NET_CAPABILITY_INTERNET` only**, without requiring `VALIDATED`. iOS `.satisfied` doesn't validate either. A drone controller's Wi-Fi without internet is handled by `TileRepository`'s own 45 s network pause, as on iOS.
- **MapLibre must never think it is offline.** Its `ConnectivityReceiver` (`getActiveNetworkInfo`) tells the native file source "offline", which then stops issuing tile requests entirely. The interceptor's offline-area tiles would then never be asked for.
  - Call `MapLibre.setConnected(true)` right after `MapLibre.getInstance(this)`.
  - Checked in 13.7.1: `ConnectivityReceiver.onReceive` returns early once `connected` is set.
  - `setConnected(Boolean)` notifies its listeners (`NativeConnectivityListener.nativeOnConnectivityStateChanged`) on every call. So calling it again from `MapController.reloadImagery()` is a way to make native code retry tiles that failed with connection errors. This only helps if the interceptor fails network-less tiles by throwing `IOException`. That is the map subsystem's decision; verify it.
- **Consumers are as in 1.12.** `OfflineManager` collects `state` (`distinctUntilChanged`) and runs `networkChanged()`. `TileRepository` reads `isOnlineNow`. Composables that show the online/offline texts collect `Connectivity.shared.state`, so they update when the network changes; iOS read it non-reactively.

### 2.9 `AppPaths` and `Format`

```kotlin
object AppPaths {
    fun init(context: Context)
    val root: File by lazy { File(context.filesDir, "SatMap").apply { mkdirs() } }
    val offline: File by lazy { File(root, "Offline").apply { mkdirs() } }
    val kml: File by lazy { File(root, "KML").apply { mkdirs() } }
    val track: File get() = File(root, "track.bin")
    fun offlineArea(id: UUID) = File(offline, id.toString().uppercase()).apply { mkdirs() }  // uuidString is upper-case
    fun size(directory: File): Long        // walk; Os.stat(f.path).st_blocks * 512 (allocated), else f.length()
    fun freeDiskSpace(): Long?             // StorageManager.getAllocatableBytes(sm.getUuidForPath(root)); IOException → null
}
```

- **Locked phone:** `filesDir` is credential-encrypted storage, readable from the first unlock after boot. That is iOS `completeUntilFirstUserAuthentication`, so no extra protection call is needed. Do not use device-protected storage.
- **Backup:** iOS excludes Offline from backup. Android excludes it from cloud backup and device transfer:
  - `res/xml/data_extraction_rules.xml`: `<cloud-backup>` and `<device-transfer>`, each with `<exclude domain="file" path="SatMap/Offline/"/>`.
  - `res/xml/backup_rules.xml` (API ≤ 30): `<full-backup-content><exclude domain="file" path="SatMap/Offline/"/></full-backup-content>`.
  - KML, `track.bin` and the preferences are backed up, as on iOS. Auto Backup has a 25 MB cap, so the exclusion is required.
- **`Format`:** ported operation for operation.
  - Every `String(format:)` goes through core `stringFormat` or `String.format(Locale.ROOT, …)`. Swift always uses "."; Kotlin's default locale may give "," and would break `coordinate` ("%.6f, %.6f"), `hectares` and `groundResolution`.
  - `count` uses `NumberFormat.getIntegerInstance(Locale.getDefault())`: localized grouping, like NumberFormatter `.decimal`.
  - `bytes` reproduces ByteCountFormatter `.file`:
    - base 1000; 0 → "Zero KB"; 1 → "1 byte"; < 1000 → "N bytes";
    - KB without decimals, MB with 1, GB and above with 2, trailing zeros dropped;
    - localized decimal separator.

### 2.10 How the UI and the map observe the model

Swift sinks hop with `DispatchQueue.main.async`. In Kotlin, collect with **`Dispatchers.Main`** (which always dispatches), not `Main.immediate`. With `Main.immediate`, a collector could run in the middle of `setMeasure` / `addMeasurePoint`, before `selectedPoint` and the elevations are set.

`MapController.bind()` runs in a scope cancelled with the `MapView` (`onDestroy`):

| Swift | Kotlin |
| --- | --- |
| `model.$trackRevision.sink { trackChanged() }` | `model.trackRevision.collect { trackChanged() }` |
| `$measure.removeDuplicates()` | `model.measure.collect { measureChanged(it) }` (StateFlow is already distinct) |
| `$selectedPoint.combineLatest($measure.map { $0 == .off })` | `combine(model.selectedPoint, model.measure.map { it == MeasureMode.Off }) { p, off -> if (off) p else null }.collect(::selectedPointChanged)` |
| `$terrainMode.removeDuplicates().dropFirst()` | `model.terrainMode.drop(1).collect { imagery.terrainChanged(it); aviation.isOn = it.showsAviation }` |
| `$selectedPoint.sink(aviationDistances)` | `model.selectedPoint.collect { aviationDistances(it) }` |
| `kml.$files.combineLatest(kml.$useFileColours)` | `combine(model.kml.files, model.kml.useFileColours)` |
| `$isDrawingArea.combineLatest($lassoActive, $draftArea != nil)` | `combine(isDrawingArea, lassoActive, draftArea.map { it != null })` |
| `$isDrawingArea.removeDuplicates → lasso.reset()`; `$draftArea → draftChanged` | the same with `collect` |
| `recorder.$power.removeDuplicates → powerChanged` (grey dot) | `model.recorder.power.collect(::powerChanged)` |
| `model.map = coordinator` | `model.map = mapController` when the `MapView` is created; `null` in `onDestroy` |
| `container.onLayout → recorder.screenOrientation` | `recorder.screenRotation = display.rotation` (`onConfigurationChanged` + `DisplayListener`) |
| Scene background/foreground → `isShown`, `headingWanted`, `saveCamera`, `becameVisible`, `trackChanged`, `locationUpdated` | `MainActivity.onStop` / `onStart` → `mapController.sceneDidEnterBackground()` / `sceneWillEnterForeground()` |
| `regionWillChange` with a finger on the map → `following = false` | `addOnCameraMoveStartedListener { if (it == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) model.setFollowing(false) }` |
| `recorder.onHeading → map?.headingUpdated(h)`; `onFix → AppModel.locationUpdated → map?.locationUpdated / carMap?.locationUpdated` | the same callbacks |

**Compose `MapScreen(model)`:**
- Each composable collects only what it shows, with `collectAsStateWithLifecycle()`:
  - `AimPanel(model.aim, …)`: `aim.point`, `aim.elevation`, `aim.elevationPoint`, `recorder.location`, `model.userElevation`, the units.
  - `ScaleBarView(model.scale)`: `label`, `width`.
  - `ContourKeyView` / `ColourKeyView(model.legend)`: `key`.
  - The coordinate and height rows: `recorder.location`, `recorder.power`, `userElevation`, `selectedPoint`, `selectedName`, `selectedElevation`.
  - The hints: `recorder.authorization`, `recorder.reducedAccuracy`.
  - `DroneCard`: `droneReport`, `terrainMode`.
- This reproduces the Swift split: a pan redraws only the aim panel, a zoom only the bar and legends.
- Sheets: `model.sheet` → `ModalBottomSheet`. `model.pendingArea` → its sheet; on dismiss, `setDraftArea(null)`.
- Clear track: long-press My location (`combinedClickable(onLongClick)`) → `DropdownMenu` item "Clear recorded track" (trash icon, error colour) → `AlertDialog` "Clear the recorded track?" with "Clear track" (error colour) / "Cancel" → `model.clearTrack()`.

### 2.11 Differences from iOS (write each in the code where it happens)

1. The foreground service and its notification (with Stop) replace `allowsBackgroundLocationUpdates`. Swiping from Recents keeps recording. Stop (or a kill by the system) stops it until the app is opened again.
2. The GPS request is removed between background fixes and in sleep mode, instead of lowering the accuracy. The network and passive listeners play the "3 km" role.
3. Timers are `AlarmManager` listener alarms on the `elapsedRealtime` clock, instead of `Date` and run-loop timers.
4. Motion: significant motion plus the step detector replace CMMotionActivity's confidence levels.
5. Location services switched off are a separate state (SERVICES_OFF) with their own hint. The hints open Settings when tapped.
6. The GPS altitude is shown only as sea level from the provider or `AltitudeConverter` (API 35+). Otherwise there is no GPS height.
7. `Geo.distance` is used instead of `CLLocation.distance(from:)`.
8. Connectivity's first state is read at once, not assumed online. MapLibre is pinned online.
9. The retry timer starts only when the app is foreground.
10. There is a notification permission (Android 13+), and an "asked" flag per permission.

### 2.12 Tests (Robolectric, `:app:testDebugUnitTest`)

- **`AppModel`:** the measuring transitions table (1.3), including the 6/20 dp thresholds through a fake `pointsApart`. `undo` back to aiming. `setMeasure` elevation resets. `movePoint` resets. `selectedName` clearing. Units and terrain mode write and read the same keys. `TerrainMode.saved` with the legacy `showContours`.
- **`CrosshairAim` / `HeightLookup`** with a fake elevation source and `runTest` virtual time: one lookup at a time, 31 s retries capped at 4, no retry while inactive, a session bump discards a late result.
- **`LocationRecorder` policy** with `ShadowLocationManager.simulateLocation`, `ShadowAlarmManager` (fire the scheduled listener alarms) and `ShadowSystemClock.advanceBy`:
  - foreground 2 s gate;
  - background: re-register at +7 s, reject a cached fix older than `acquiringSince − 0.5`, accept at ≥ 9.5 s, rest again;
  - sleep after 120 s still;
  - no sleep within 600 s without a good fix;
  - peek intervals 60/120/15 s and lengths 15/45 s;
  - wake thresholds 8 m / 1.5× (measuring) and 20 m / 2×;
  - coarse jump > 300 m;
  - precise-fix rule after a wake;
  - track spacing and segment breaks;
  - `measuring = true` while asleep in the foreground starts a peek immediately.
- **`Connectivity`** with `ShadowConnectivityManager`: initial offline state; `drop(1)` + online edge → `resetFailureState` and reload; `canDownloadNow` with metered networks.

### 2.13 Noted for other subsystems

- `org.maplibre.gl:android-sdk:13.7.1` is the **Vulkan** build. Its merged manifest declares `<uses-feature android:name="android.hardware.vulkan.version" android:required="true" android:version="0x400003"/>`, which limits installs to Vulkan 1.0 devices and needs Vulkan on the CI emulator. The OpenGL build is a separate artifact.
- MapLibre's own `LocationComponent` (`MapLibreMap.getLocationComponent()`, with `activateLocationComponent`, `setLocationEngine` and `forceLocationUpdate` underneath) must **not** be activated with a `LocationEngine`. Its engine would keep the GPS at full power, as MapKit's own tracking would. Your dot, arrow and grey sleep state are drawn by the map subsystem from `recorder.location` and `recorder.power`.
