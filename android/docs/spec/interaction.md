# Map interaction and overlays: Android spec

**Swift sources:** `SatMap/Map/MapController.swift`, `MapAnnotations.swift`, `DynamicOverlays.swift`, `BlockLayers.swift`, `AviationLayer.swift`, `PointAdjuster.swift`, `ZoomScript.swift`, plus the parts of `AppModel.swift` and `MapScreen.swift` they call.

**Android package:** `com.jacobusoettle.satmap.map`, in `android/app/src/main/kotlin/com/jacobusoettle/satmap/map/`.

**MapLibre version:** checked with `javap` against `org.maplibre.gl:android-sdk:13.7.1`, `android-sdk-geojson:6.0.1` and `maplibre-android-gestures:0.0.4` from the Gradle cache.

## 0. Files and types

| Swift | Kotlin file | Kotlin types |
| --- | --- | --- |
| `MapController`, `MapContainerView`, `MapViewRepresentable` | `MapController.kt` | `MapController`, `MapContainerView` (a FrameLayout), `AimLineView` (a View), `MapViewHost` (a Composable wrapping `AndroidView`), `MapIds` (object holding source and layer ids) |
| `BlockPolygons`/`BlockLines`/`DraftAreaPolygon`, the annotation classes, `UserDotView`, `MeasureMarkerView`, `SelectedPointView`, `BlockLabelView`, `LassoView`, `UIColor(kml:)` | `MapAnnotations.kt` | `MapImages` (an object that draws every style bitmap), `MapColours`, `UserDotLayer`, `MeasureMarkerLayer`, `SelectedPointLayer`, `DraftAreaLayer`, `LassoView`, `KMLColor.argb()` / `KMLColor.rgba()` |
| `TrackOverlay`+`TrackRenderer`, `MeasureLineOverlay`+`MeasureLineRenderer` | `DynamicOverlays.kt` | `TrackLayer`, `MeasureLineLayer` |
| `BlockLayers` | `BlockLayers.kt` | `BlockLayers` |
| `AviationStore`, `AviationAnnotation`, `AviationLayer`, `AviationView` | `AviationLayer.kt` | `AviationStore`, `AviationLayer`, `AviationCard` (paints what `AviationView` drew) |
| `MovablePoint`, `PointAdjuster`, `LoupeFrame`, `LoupeGlass`, `LoupeView` | `PointAdjuster.kt` | `MovablePoint` (a sealed interface with `Selected` and `Measure(i)`), `PointAdjuster`, `LoupeFrame`, `LoupeView` |
| `ZoomScript`, `Watchdog` | `ZoomScript.kt` | `ZoomScript`, `Watchdog` |

`BlockLayers`, `AviationLayer`, `TrackLayer` and `UserDotLayer` take a `(MapLibreMap, Style, Context)`, not the `MapController`. The Android Auto map then reuses them, as CarPlay reuses the Swift classes.

**Units:**
- iOS "points" become **dp** everywhere. Every distance threshold below is in dp; convert from `MotionEvent` and `Projection` pixels with `resources.displayMetrics.density`.
- `MKMapPoint` becomes core `WebMercator.world()` units, where the world is 1×1 and an `MKMapPoint` equals world × 2²⁸.
- `MKMapPointsPerMeterAtLatitude(lat)` becomes `1 / Geo.metersPerWorldUnit(lat)`.

**Colours.** These are exact conversions of the Swift values. The app is always dark (`preferredColorScheme(.dark)`), so the system colours use their dark variants.

| Name | ARGB |
| --- | --- |
| measure pink (1, 0.18, 0.4) | `0xFFFF2E66` |
| measure pink at 25% | `0x40FF2E66` |
| measure pink at 15% | `0x26FF2E66` |
| track cyan (0, 0.9, 1) | `0xFF00E6FF` |
| aviation "you" (0.35, 0.86, 1) | `0xFF59DBFF` |
| aviation "pin" (1, 0.72, 0.2) | `0xFFFFB833` |
| systemBlue (dark) | `0xFF0A84FF` |
| systemGray | `0xFF8E8E93` |
| crosshair red (`Color.red`, dark) | `0xFFFF453A` |
| block stroke default (white 0.95) | `0xF2FFFFFF` |
| block fill default (white 0.07) | `0x12FFFFFF` |
| label shadow (black 0.9) | `0xE6000000` |

---

## 1. Behaviour spec (the same on both platforms; numbers are exact)

### 1.1 Single tap on the map

1. **When a tap counts.** A tap is recognised only once it can't be the first tap of a double-tap zoom. It is never part of:
   - a two-finger tap (zoom out),
   - a tap-then-press (double-tap zoom, or one-finger zoom: tap, then drag),
   - a press-and-hold on a point.

   It is ignored:
   - while drawing an area (`model.isDrawingArea`),
   - while a point is being moved (`adjuster.isActive`),
   - for **0.4 s** after a moved point is let go (`tapsIgnoredUntil`).
2. **Where the finger was.** The map may move during the wait (following you). So the coordinate under the finger is recorded when the finger lifts (`lastTouch = (point, coordinate)`). If the recognised tap is within **1 dp** of `lastTouch.point`, that recorded coordinate `c` is used. `lastTouch` is then cleared. The spot used below is `spot = toScreen(c)`.
3. **Checks, in this order:**
   1. **KML point** (only when `measure == off`). Pick the KML point nearest `spot` with dot distance < **26 dp**. This counts whether or not its label is visible. If the dot is ≥ 26 dp away but its label is visible and `spot` is inside the label's frame enlarged by **6 dp** on every side, its distance counts as 25.

      Result: `model.pointTapped(point, name = feature name)`. An empty name gives `selectedName = null`.
   2. **Aviation place** (only when `measure == off` and drone mode is on). Only places whose symbol is visible (not hidden by collision) count. The distance is from the symbol centre and must be < **24 dp**. A tap inside the place's frame counts as 23.

      Result: `model.pointTapped(place.point, "${place.label} · ${place.kind.title}")`.
   3. **Close the shape.** If `measure` is `path` with ≥ 3 points and the **tap pixel** (not `spot`) is < **30 dp** from A's screen position: `model.closeMeasureShape()`.
   4. Otherwise `model.mapTapped(c)`. With measure off this selects the spot. While measuring it adds a point at the crosshair; `addMeasurePoint` closes the shape if the crosshair is within 20 dp of A, and ignores a point within 6 dp of the last one. That logic is in AppModel.
4. **Two-finger tap** zooms out, **double tap** zooms in, and **tap then drag** is one-finger zoom. These are the map's own gestures. **Pitch is off. Rotation is on.**

### 1.2 Press and hold a point to move it precisely

**Which point.** At the finger:
- the nearest measuring point within **30 dp**, or
- with `measure == off`, the selected pin within **30 dp**.

**Starting the press:**
- Hold **0.35 s**. The press fails if the finger moves more than **10 dp** (UILongPress allowableMovement) or a second finger lands before then.
- It never starts while drawing an area, or when the press is the second press of a tap-then-press.

**When it begins:**
- The map freezes: scroll, zoom and rotate are off until the finger lifts.
- `model.following = false`.
- Haptic: medium impact.
- `PointAdjuster.begin(which, point, touch, scene)`.

**The loupe (`PointAdjuster` and `LoupeView`):**
- **Geometry.** The square is **232 dp** with an **8 dp** glass rim, so the content is **216 dp** with corner radius 24. The rim's corner radius is 32. It shows **15 m × 15 m** (`metresAcross`) of ground around the point, centred on `centre`, turned by the map's heading captured at begin.
- **Where it goes.** It goes **44 dp** above the finger (`gap`). The area is the window inside the safe area, inset by **8 dp**.
  - Above if `touch.y − 44 − 232 ≥ minY`, with x clamped.
  - Otherwise below if `touch.y + 44 + 232 ≤ maxY`.
  - Otherwise beside the finger, on the side with more room, with x and y clamped.
- **Coordinates at begin.** `start = centre = point` = the point's world position, `perMetre` at its latitude, and `touchStart` = the finger. The map's world directions for screen right and down come from projecting the screen centre and points 100 dp to its right and below, normalised. `perLoupePoint = 15 · perMetre / 216`.
- **Finger move.** Let `finger = touch − touchStart` (dp).
  - `dir = unit(finger.x·right + finger.y·down)`
  - `metres = |finger| · perLoupePoint / perMetre`, which is `|finger| · 15/216`
  - `point = centre + dir · min(metres, 5) · perMetre`. This is the **5 m clamp** (`freeRadius`).
  - Then report `onMove(which, point, done = false)`.
- **Every display frame.**
  - `dt = clamp(now − last, 0, 0.1)`.
  - If `metres > 5`: `step = min(20, 1.5·(metres − 5)) · dt · perMetre`. That is 1.5 m/s for every metre beyond 5 m, at most **20 m/s**.
  - `centre += dir·step`, the map's centre moves by the same world offset, then do the finger-move update again.
- **Finger lifted.** The square fades in **0.18 s** to alpha 0 and scale 0.85, then is removed. `onMove(which, final, done = true)`, where `final = point`, or `start` if the press was cancelled (the point goes back).
- **Appearing.** From scale 0.6 and alpha 0 to 1, with a 0.25 s spring, damping 0.75.

**What the map controller does with each move:**
- Updates the moved point on the map immediately: the measuring marker and the measuring line (or the pin).
- Updates `model.movePoint(which, p, done)` at most **15 times a second**, and always when `done`.
- On `done`: haptic light impact. Re-enable the gestures. `tapsIgnoredUntil = now + 0.4 s`.

**Loupe content, in drawing order.** Everything except the dashed ring and the captions is drawn in a rotated frame (`rotate(−heading)` about the middle) at scale `k = side / (15 · perMetre)`.

1. **Imagery.** The ground is a square of half-side `side·0.75/k`, so it fills the corners when turned.
   - Level: `round(log2(pixelsPerWorldUnit/256))` clamped to [0, `TileSources.imageryMaxZoom` = 22].
   - Each tile: its own decoded tile if available, else request it urgently and draw the nearest coarser tile in memory (`TileFallback.ancestor(maxLevels = 12)`) clipped to the tile's rect.
   - Redraw when an imagery tile arrives.
2. **Blocks.** These are the scene's KML rings and lines whose bounding box overlaps the ground (with a 4 dp margin). Each is stroked twice: black 40% at width 4, then its colour at width 2. Joins and caps are round.
3. **KML points.** Dots of radius 5, filled with the icon colour (or white), with a black 1 dp stroke.
4. **Measuring line.** The points with the moving one replaced by `point`. With a single point and a known location, it runs from you. Stroke pink, width 3, dashes [8, 6], phase 0, round caps (the caps stay round from step 2). Closed if the shape is closed.
5. **Other measuring points.** Pink rings, radius 9, width 3. The one being moved is left out.
6. **You.** Fill systemBlue (always blue here), white stroke 2.5, radius 8.
7. **The moving point.**
   - Ring of radius 13: black 55% at width 5, then white (pin) or pink (measuring point) at width 3.
   - Four white 1 dp ticks from ±4 to ±20 on both axes.
   - White centre dot, radius 1.5.
8. **Dashed ring** (not rotated): radius `5/15 · side` (72 dp), white 55%, width 1, dashes [4, 4].
9. **Captions.**
   - `"15 m"` at (8, 6), left aligned.
   - `"moved %.1f m"` right aligned at (w − 8, h − 24), formatted with `Locale.ROOT`. The value is `Geo.distance(start, point)`.
   - Font: 12 dp semibold with tabular figures, white, on a black 55% capsule padded 6 × 2.

**Scene** (`loupeScene`), built once at begin:
- From every shown KML file, keep features whose box meets a square of ±**2000 m** around the point. Polygons contribute every ring as a closed line. Lines are open. Each gets the file's line colour, or white 95% when file colours are off or the style has none.
- KML points inside that square, with the icon colour or white.
- The measuring points, whether the shape is closed, the index being moved, and your location.

### 1.3 Camera

- **Saved camera.** Key `"camera"` stores (centre lat, centre lon, latitude span, longitude span) of the visible region.
  - Saved when the camera goes idle and when the app goes to the background.
  - Restored without animation: the region is fitted to the view.
  - With nothing saved: centre (−28.5, 24.7), span 14° × 14°.
- **First launch** (nothing saved). The first fix while following sets the region to 1500 m × 1500 m around you, without animation. This happens once.
- **Following.** While `following`, each fix animates the centre onto you, unless `keepCameraUntil` (now + **1 s**, set by "zoom onto") hasn't passed. Any camera move started by the user's gesture sets `following = false`.
- **My location** (`AppModel.centerOnUser`).
  - If already following and the position is known: zoom onto you so the screen is **300 m** across. A quick second tap does this too.
    - Measure ground metres per dp across 100 dp at the screen centre.
    - `across = perDp · widthDp`.
    - Keep the heading, centre on you, scale the camera so `across` becomes 300, animate, and set `keepCameraUntil`.
  - Otherwise: `following = true` and animate the centre onto you.
- **Zoom to bounds** (`zoom(to: GeoBounds)`).
  - Span = max(0.002°, 1.25 × the bounds span) in each axis, centred on `b.center`.
  - `following = false`, then animate.
- **`centerCoordinate`** is the coordinate at the middle of the map view, where the crosshair is.
- **`pointsApart(a, b)`** is the screen distance in dp.
- **Every camera frame:** update the aim, update the scale (`model.scale.update(metersPerPoint)`), and run the imagery hooks.
- **Camera idle:** save the camera, update the aim and scale, then `imagery.regionDidChange()` and `aviation.regionChanged()`.

### 1.4 Your position

- **Dot.** 22 dp diameter. Fill systemBlue, or systemGray while the GPS sleeps (`recorder.power == sleeping`). White 3 dp border inside the 22 dp, so the coloured fill has radius 8. Shadow black 40%, radius 2, no offset. Always shown, drawn on top, and it hides labels under it.
- **Arrow** (the compass).
  - Shape: a triangle just outside the dot. Its base is 16 dp wide, 2.5 dp beyond the dot's edge (13.5 dp from the centre). Its tip is 12 dp further out (25.5 dp from the centre).
  - Paint: fill in the dot's colour, white 2 dp stroke with round join, same shadow. It turns about the dot's centre.
  - On-screen angle: compass heading − map heading.
  - Hidden when the heading is unknown.
  - Turns of ≤ 45° glide over **0.2 s**. Bigger turns, and the arrow appearing, jump.
- Fixes are ignored while the phone's screen doesn't show the map.

### 1.5 Dotted track

A cyan (`#00E6FF`) line of dots: 4 dp diameter (width 4, zero-length dashes, round caps), spaced 9 dp apart along the line, with round joins. A point with `startsSegment` starts a new line. When `model.track` gets shorter, the track was cleared and is redrawn from scratch.

### 1.6 Measuring

- **Line.**
  - `placed`: from A to you, only once your location is known.
  - `path`: A → B → C ….
  - `closed`: back to A, filled pink 15%.
  - Paint: pink, width 3, dashes 10 on and 7 off, butt caps, continuous along the whole line.
  - Nothing for `off` or `aiming`.
- **Markers.** 30 × 30 dp: a pink ring (circle of radius 12, stroke 3, fill pink 25%) with a white centre dot of radius 3.
  - Labels ("A", "B", … "Z", "AA" …, from `MeasureMode.label`) only when there are ≥ 2 points.
  - Label box: 15 dp heavy white text, shadow black 90% offset (0, 1). Box at (+3, −29) from the marker centre, 34 × 18, left aligned, vertically centred.
  - Markers always show and hide lower labels. Their letters don't take part in collisions.
- **Aim line** (while `aiming` only).
  - Every camera frame, location update and measure change sets `model.aim.set(centerCoordinate)`.
  - If your location is known, a line goes from 11 dp off your dot's centre (toward the crosshair) to the screen centre. It is drawn only if longer than 15 dp.
  - If you are off screen, it starts where the line enters the view enlarged by 20 dp, with dash phase = (that distance) mod 17, so the dashes stay put.
  - Paint: pink, width 3, dashes [10, 7], butt caps.
- **Crosshair** (`MapScreen`; shown for `aiming`, `placed` and `path`).
  - White circle, stroke 2, 36 dp diameter.
  - White bars of 2 × 56 and 56 × 2.
  - Red 6 dp dot in the centre.
  - Black shadow, radius 1.5.
  - Centred on the full screen, the same point as the map centre. Doesn't take touches.

### 1.7 Selected spot (pin)

Shown only while `measure == off` and `selectedPoint != null`.
- 22 dp: white ring (circle of radius 9, stroke 3, fill black 25%, shadow black 50% radius 2) and a white centre dot of radius 2.
- Drawn above KML points and doesn't collide: a selected KML point keeps its own dot and name under the ring.

### 1.8 KML blocks

**Which files and which colours.** Files with `isShown`. With `useFileColours`, each feature uses its `KMLStyle`; without it, every style is null and the defaults apply. A change of `useFileColours` redraws every file.

**Polygons:**
- Fill: `fillColor`, or white 7%; none if `fill == false`.
- Stroke: `lineColor`, or white 95%. No stroke if `outline == false` or the width is ≤ 0.

**Lines:** the same stroke rules, without the `outline` check.

**Widths and joins:** width = `clamp(lineWidth ?? 2.5, 1.5, 12)`. Round joins and caps (the MapKit path renderer defaults).

**Stacking:** each newly shown file goes above the earlier ones, below the track.

**Labels:**
- A named polygon gets a label at `PolygonLabel.interiorPoint(outer, holes)`. Text: 13 dp semibold white, shadow black 90% offset (0, 1). Frame width = max(text width, 10). Centred on the point.
- A point: a 10 dp dot (icon colour or white, 1 dp black border) on the spot, with its name (15 dp bold, same shadow) under it. The name's top is 12 dp below the dot's top. Frame width = max(name width, 10). An empty name shows just the dot.
- Labels never take touches. A tap on a point is handled by §1.1.

### 1.9 Drawing an area (lasso)

**When the lasso shows:** while `isDrawingArea && lassoActive && draftArea == null`. A full-map overlay with a black 8% wash catches one finger.
- The finger draws a polyline, adding a point when it has moved ≥ **3 dp**.
- Paint: white, width 3, round join and cap, dashes [8, 6], fill white 15%.

**Lifting the finger:**
- With ≥ 3 points, the path is closed on screen.
- Douglas–Peucker simplification in dp, tolerance **4**.
- If fewer than 3 points remain: reset and call `model.finishDrawing([])`.
- Otherwise convert the ring to coordinates and call `model.finishDrawing(ring)`.

**Resets.** A cancelled touch resets the lasso. So does any change of `isDrawingArea`.

**Draft area.** The ring gets a white dashed outline (width 2.5, dashes [8, 6], round caps) with white 15% fill, above the measuring line.

### 1.10 Drone mode (aviation)

- **Loading.** On with `terrainMode.showsAviation`. `AviationStore` reads `Aviation.tsv` once, in the background, the first time it is needed. Every waiting caller is called back on the main thread.
- **Which places** (`regionChanged`, when the camera goes idle; needs a view wider than 100 dp):
  - `km` = ground metres per dp across 100 dp at the centre × view width / 1000.
  - The query box is the region's centre ± span × (0.5 + **0.15**). Latitude is clamped to ±90; longitude is not clamped (it may run past ±180).
  - Keep places with `kind.shownWithinKm ≥ km`:

    | Kind | Shown from (km across) |
    | --- | --- |
    | Large airport | 3000 |
    | Medium airport | 800 |
    | VOR, TACAN | 400 |
    | Small airport | 200 |
    | NDB, DME | 120 |
    | Helipad, seaplane base | 60 |
  - If more than **140**: sort by priority (descending), then by distance to the centre, and keep 140.
  - Places already shown are kept as they are.
- **Distances.** A label is redrawn when you or the pin (tapped spot or selected place) has moved ≥ **20 m**, or one of them appeared or disappeared.
- **Symbol.** 22 × 22 dp, centred on the place. Stroke white 2 with round cap. Underneath it, the same path as a halo: stroke black 65% at width 4.5, closed shapes filled black 35%.

  | Kind | Symbol |
  | --- | --- |
  | Large, medium, seaplane base | ring r = 8 with a runway line through it |
  | Small airport | ring r = 6.5 with a runway line through it |
  | Heliport | ring r = 8 with "H" |
  | VOR, TACAN | hexagon r = 8 (vertices at k·60° from +x) plus a 1.5 dp dot |
  | DME | 13 × 13 square |
  | NDB | ten dots of radius 1.2 on a ring of r = 7.5, plus a centre dot of radius 2 |

  - The runway runs from `c − (sin a, −cos a)·(r + 3)` to `c + (sin a, −cos a)·(r + 3)`, where `a` = (`runwayHeading` ?? 45) − map heading. In other words it lies along the true runway direction, turned with the map.
  - Seaplane bases add "~".
  - Letters: 12 dp heavy white, centred in (0, 4, 22, 18).
- **Card** under the symbol: 3 dp gap, background white 5% at 74% alpha, corner 8, white 18% border of 0.5 dp, text padded 6 × 3, maximum text width 190.
  - **Line 1, with a code:** `code` (13 bold white). If not an aerodrome, then `" " + kind.title` (11.5 semibold, white 75%). Then `"  " + name` (11.5 semibold, white 85%).
  - **Line 1, without a code:** the name (11.5 semibold, white).
  - **Name truncation:** `shortName`; longer than 24 characters becomes the first 23 + "…".
  - **Line 2 (you):** "\n", a filled location arrow (10 dp bold, cyan), " ", then `Aviation.nauticalMiles(d)` (13 bold with tabular figures, cyan).
  - **Line 3 (pin):** the same with a pin icon in amber.
  - A distance line is left out if d < 0.05 NM (the place itself).
  - **Frame:** width = max(22, card width), height = 22 + 3 + card height. The symbol is centred at the top, and the symbol's middle sits on the place.
- **Collisions.** The whole frame collides. The priority comes from the kind:

  | Kind | Priority |
  | --- | --- |
  | Large airport | 740 |
  | Medium airport | 720 |
  | VOR, TACAN | 650 |
  | Small airport | 600 |
  | NDB, DME | 500 |
  | Helipad, seaplane base | 450 |

### 1.11 Display priorities and z-order (MapKit values)

| Item | Collision | Priority | z |
| --- | --- | --- | --- |
| User dot | yes (circle) | required | top |
| Measure markers | yes (frame) | required | defaultSelected |
| Selected pin | none | required | defaultSelected |
| KML points | yes (frame) | 750 | default |
| Aviation | yes (frame) | 450–740 | default |
| Block names | yes (frame) | 250 | default |

All of these are above every line and fill. Lines and fills, bottom to top: imagery and contours, then blocks (by file, in the order they were shown), the track, the measuring line, the draft area.

### 1.12 Background and foreground

When the phone's screen stops showing the map (`isShown = false`):
- `recorder.headingWanted = false`
- save the camera
- location fixes and track changes are ignored

When it comes back:
- `headingWanted = true`
- `imagery.becameVisible()`
- catch up on track points
- `locationUpdated(last fix)`

### 1.13 ZoomScript (zoom test)

Only with the `zoomScript` setting.
- Start at (−33.9183, 18.9212).
- Four 10 dp squares at (8 + i·12, 110) show `step + 1` in binary, white for 1, bit 0 on the left.
- A watchdog ticks every 0.1 s and logs `"main thread held up N ms"` when the gap is over 500 ms.
- After **20 s**, run one step every **5 s**: set `following = false`, set the visible width to `world/2^zoom × viewWidth/256`, shift the centre by `dx`/`dy` screens, log `"step i zoom z"`, and animate (except step 0).
- Steps: (14,0,0) (15,0,0) (16,0,0) (17,0,0) (18,0,0) (17,0,0) (16,0,0) (15,0,0) (15,1,0) (17,0,0) (14,0,0) (14,0,−1) (15.15,0,0).

---

## 2. Android design on MapLibre 13.7.1

### 2.1 Views

```
MainActivity content (android.R.id.content, a FrameLayout)
 ├─ ComposeView: MapScreen
 │    ├─ MapViewHost → AndroidView { controller.container }   (edge to edge, full screen)
 │    ├─ Crosshair (Compose Canvas, centred on the full screen, no pointer input)
 │    └─ panels and buttons
 └─ LoupeFrame (added by PointAdjuster while a point is moved; above everything, as iOS adds it to the window)

MapContainerView : FrameLayout      // dispatchTouchEvent watches every touch
 ├─ org.maplibre.android.maps.MapView
 ├─ AimLineView : View              // not touchable
 ├─ LassoView : View                // GONE unless the lasso is active
 └─ (ZoomScript squares)
```

`MapController` creates the `MapView` with `MapLibreMapOptions().textureMode(false)` (the default SurfaceView) and forwards the Activity lifecycle to it (`onCreate/onStart/onResume/onPause/onStop/onDestroy/onLowMemory/onSaveInstanceState`).

In `getMapAsync` it sets these `UiSettings`:
- `isTiltGesturesEnabled = false`
- `isCompassEnabled = false`: the UI's own compass button calls `map.resetNorth()` and shows while `cameraPosition.bearing != 0`.
- `isLogoEnabled = false`
- `isAttributionEnabled = false`: the credits are on the Offline screen, as on iOS.
- rotate, zoom, scroll, double-tap and quick zoom stay on.

It installs its overlays in the `Style.OnStyleLoaded` of the style the imagery subsystem builds. Install is idempotent, so a reloaded style gets them again.

`MapContainerView.onLayout` / `onConfigurationChanged` sets `model.recorder.screenRotation = display.rotation`. This replaces `screenOrientation`.

The following are Android Views because they are fixed to the screen or drawn per frame in screen space:
- the crosshair (on the screen centre),
- the aim line (one end at the screen centre, and it must follow a pan frame by frame, as on iOS),
- the lasso (screen-space drawing),
- the loupe (its own drawing, see §2.8).

Everything fixed to the ground is in the MapLibre style (GeoJSON sources and layers), including the user dot. That keeps it glued to the map with no frame of lag.

### 2.2 Style layer stack (bottom to top)

Sources are `org.maplibre.android.style.sources.GeoJsonSource`. They are created empty with `FeatureCollection.fromFeatures(emptyList())`, and the tiny live ones use `GeoJsonOptions().withSynchronousUpdate(true)` so a moved point shows in the same frame. Geometry comes from `org.maplibre.geojson.{Feature, FeatureCollection, Point, LineString, MultiLineString, Polygon}`. Layers are `LineLayer`, `FillLayer` and `SymbolLayer`, with `PropertyFactory` and `Expression`.

| Layer id | Type | Source | Key properties |
| --- | --- | --- | --- |
| imagery, colours, contours | Raster | (imagery subsystem) | — |
| `kml/<uuid>/fill` | Fill | `kml/<uuid>` | filter `eq(geometryType(), "Polygon")`; `fillColor(toColor(get("fill")))` |
| `kml/<uuid>/line` | Line | `kml/<uuid>` | `lineColor(toColor(get("stroke")))`, `lineWidth(get("width"))`, `lineCap(LINE_CAP_ROUND)`, `lineJoin(LINE_JOIN_ROUND)`; inserted with `style.addLayerBelow(fill, MapIds.TRACK)` then `addLayerBelow(line, MapIds.TRACK)` |
| `track` | Line | `track` (finished part) | `lineColor(0xFF00E6FF)`, `lineWidth(4f)`, `lineDasharray(arrayOf(0f, 2.25f))` (units of line width: 9 dp / 4), `lineCap(ROUND)`, `lineJoin(ROUND)` |
| `track-live` | Line | `track-live` | same paint |
| `measure-fill` | Fill | `measure` | filter Polygon; `fillColor(0x26FF2E66)` |
| `measure-line` | Line | `measure` | filter LineString; `lineColor(0xFFFF2E66)`, `lineWidth(3f)`, `lineDasharray(arrayOf(10f/3, 7f/3))`, `lineCap(LINE_CAP_BUTT)` |
| `draft-fill` | Fill | `draft` | `fillColor(0x26FFFFFF)` |
| `draft-line` | Line | `draft` | white, `lineWidth(2.5f)`, `lineDasharray(arrayOf(3.2f, 2.4f))`, round cap and join |
| `kml/<uuid>/names` | Symbol | `kml/<uuid>/labels` | filter `eq(get("kind"), "name")`; see §2.4; inserted `addLayerBelow(…, "aviation-rings")` |
| `aviation-rings` | Symbol | `aviation` | §2.9 |
| `aviation` | Symbol | `aviation` | §2.9 |
| `kml/<uuid>/points` | Symbol | `kml/<uuid>/labels` | filter `eq(get("kind"), "point")`; inserted `addLayerBelow(…, "selected")` |
| `selected` | Symbol | `selected` | `iconImage("selected")`, allow-overlap and ignore-placement true |
| `measure-markers` | Symbol | `measure-points` | `iconImage("measure-marker")`, `iconAllowOverlap(true)`, `iconIgnorePlacement(false)` |
| `measure-letters` | Symbol | `measure-points` | filter `has("letter")`; `iconImage(get("letter"))`, `iconAnchor(ICON_ANCHOR_TOP_LEFT)`, `iconOffset(arrayOf(3f, -29f))`, allow-overlap and ignore-placement true |
| `user-arrow` | Symbol | `user` | filter `has("heading")`; `iconImage(get("arrow"))`, `iconRotate(get("heading"))`, `iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP)`, allow-overlap and ignore-placement true |
| `user-dot` | Symbol | `user` | `iconImage(get("dot"))`, `iconAllowOverlap(true)`, `iconIgnorePlacement(false)` |

The layers `track`, `aviation-rings` and `selected` always exist, even when empty. They are the anchors the per-file layers are inserted against. `MapIds` holds every id.

### 2.3 Map text is drawn by the app into style images, not MapLibre glyphs

The style built in code has no `glyphs` URL: every tile is raster, and the contour labels are inside the contour tiles. So MapLibre `text-field` and `format` expressions can't draw anything without shipping SDF glyph PBFs.

The design does **not** use them. Every label and marker is a `Bitmap` drawn with `android.graphics.Canvas` by `MapImages` / `AviationCard` and added with `Style.addImages(HashMap<String, Bitmap>)`. Each `SymbolLayer` then uses `iconImage(get("img"))`.

Why:
- **Fidelity.** The same fonts and sizes as iOS (Roboto at 13/15/11.5/12 dp with the iOS weights), the iOS shadows (text drawn twice: black 90% at +1 dp, then white), the aviation card's rounded background and border, the inline cyan and amber icons, and tabular digits (`Paint.fontFeatureSettings = "tnum"`). None of that is possible with `format` sections plus halos, and the card can't be an `icon-text-fit` background, because the icon slot is needed elsewhere (§2.9).
- **No glyph pipeline** to build, ship and serve, and no glyph gaps for non-Latin KML names.
- **MapKit-like collisions.** Collisions are frame rectangles, as in MapKit.

Rules:
- **Density.** Every bitmap is created at device density with `bitmap.density = resources.displayMetrics.densityDpi`. `Style.toImage` takes `pixelRatio = density/160`, so images display at their dp size; this was confirmed in the bytecode of `Style.toImage`. All sizes, anchors and offsets below are in dp.
- **Fonts.** Text sizes are in dp, not sp: iOS uses fixed point sizes, not Dynamic Type.
  - API 28+: `Typeface.create(Typeface.DEFAULT, weight, false)` with semibold = 600, bold = 700, heavy = 900.
  - API 26–27: `"sans-serif-medium"` for semibold, `Typeface.DEFAULT_BOLD` for bold, `"sans-serif-black"` for heavy.
- **Text height.** Use `ceil(fontMetrics.descent − fontMetrics.ascent)`. The bitmap adds 1 dp at the bottom for the shadow; the matching `iconOffset` shifts by +0.5 dp so the text centre stays on the point.
- **Rendering and adding.** Draw the bitmaps on `Dispatchers.Default`, then call `addImages` once on the main thread. `Style.toImage` copies the pixels, so the Java bitmaps can be dropped afterwards.
- **Changing an image.** An image whose content changes gets a **new id**, the GeoJSON feature points at it, and the previous generation of ids is removed one update later. Icon-only symbols match across layouts by position (their key is empty), so a renamed image swaps without fading.

| Image id | Size (dp) | Drawn as | Anchor / offset |
| --- | --- | --- | --- |
| `user-dot`, `user-dot-grey` | 28 × 28 (22 plus a 3 dp shadow margin) | §1.4 dot, `Paint.setShadowLayer(2dp, 0, 0, 0x66000000)` | centre |
| `user-arrow`, `user-arrow-grey` | 64 × 64, centred on the dot centre | §1.4 triangle pointing up (north), stroke white 2, round join, shadow | centre, map-aligned rotation |
| `measure-marker` | 30 × 30 | ring and centre dot (§1.6) | centre |
| `measure-letter/<L>` | 34 × 18 | heavy 15, white, shadow; left aligned, vertically centred | top-left, (3, −29) |
| `selected` | 28 × 28 | §1.7 with shadow | centre |
| `kml/<uuid>/<n>` (name) | max(w, 10) × h | semibold 13, shadow | centre |
| `kml/<uuid>/<n>` (point) | max(w, 10) × (h + 12) | dot at (w/2 − 5, 0, 10, 10), name at y = 12 | `ICON_ANCHOR_TOP`, offset (0, −5) |
| `av/<id>/<gen>` | max(22, cardW) × (25 + cardH) | card, plus the static symbol of non-runway kinds and the "~" of seaplane bases in the top 22 × 22 slot | `ICON_ANCHOR_TOP`, offset (0, −11) |
| `av-ring-8`, `av-ring-6.5` | 22 × 22 | halo + ring + runway drawn north-up (vertical) | centre |

`AviationCard` draws the text with a `StaticLayout` (width ≤ 190) built from a `SpannableStringBuilder`:
- `ForegroundColorSpan` for the colours.
- `AbsoluteSizeSpan(dp, true)` for the sizes.
- a `MetricAffectingSpan` that sets the typeface, plus `"tnum"` on the distances.
- an `AviationCard.Icon` (`ReplacementSpan`) for the two icons. iOS draws the SF Symbols at the label's 17 point font, so each is filled at the size the iPhone's screenshot shows (25-drone-mode), in dp from the icon's start and the baseline; the line keeps the 17 point font's height.
  - location arrow (advance 20.2): the polygon tip (17.8, −14.7) → wing (−0.6, −6.4) → notch (8.4, −5.3) → wing (9.5, 3.7), its tip rounded to an arc of radius 0.8 and its wings to 0.7, the notch sharp: 15.8 dp across and tall, from 13.9 above the baseline to 1.9 below.
  - pin (advance 17.0): a ball centred (8.1, −11.75), r 3.1, with a hole of r 1 at (7.2, −12.65), on a needle narrowing from 1.7 dp wide (inside the ball) to 1.1 at 2.5 below the baseline.
  - The advances are rounded up to whole pixels; with them the distances start where the iPhone's do.

### 2.4 Collisions instead of MapKit display priority

MapLibre places symbols layer by layer from the **top** layer down. Within a layer it follows `symbol-sort-key`, ascending. `crossSourceCollisions` stays at its default `true`. The stack in §2.2 therefore reproduces MapKit's priorities:

- **Required items** (user dot, markers): `iconAllowOverlap(true)` + `iconIgnorePlacement(false)`. Always drawn, and they block lower labels.
- **Collision mode none** (selected pin, marker letters, arrow, runway rings): `iconAllowOverlap(true)` + `iconIgnorePlacement(true)`.
- **KML points (750) above aviation (450–740) above block names (250).** Layer order handles this. Aviation priorities interleave only with each other: `symbolSortKey(get("key"))` with `key = 1000 − kind.priority` and `symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE)`, so the sort key also orders the drawing. Features are written in the order `ids` comes out of `regionChanged`, which is the tie-break.
- **Every collidable layer:** `iconAllowOverlap(false)`, `iconIgnorePlacement(false)`, `iconPadding(0f)` (MapKit adds no padding).
- **Unchanged defaults:** `symbolPlacement` point and `symbol-avoid-edges` false, so labels at the screen edge are cut off, not hidden, as in the screenshots.

### 2.5 Sources and how they are updated

**`user`.** One `Point` with properties `dot` (`"user-dot"`/`"user-dot-grey"`), `arrow` (likewise) and optional `heading`, the compass in degrees from true north.
- `UserDotLayer.update(location, sleeping, heading)` calls `setGeoJson(Feature)` directly.
- The arrow is map-aligned, so MapLibre subtracts the map's bearing by itself: no update on map rotation (unlike `updateArrow` on iOS).
- Glide: when the heading changes by ≤ 45° and the arrow is already showing, a `ValueAnimator` runs 200 ms (`AccelerateDecelerateInterpolator`, the Core Animation default curve) along the shortest way round, updating `heading` every frame.
- A change > 45°, or `heading` appearing, sets it at once. A null heading removes the property, which hides the arrow through the filter.

**`track` + `track-live`** (`TrackLayer`).
- `track-live` holds the last ≤ 512 points of the current segment as a `MultiLineString` and is rebuilt on each fix.
- When it reaches 512 points it is moved into `track`, which is rebuilt only then, and the new live part starts at the previous point. This is the Swift chunk rule, so the whole track isn't re-sent every 2 s.
- `reset(track)` is used when cleared.
- The finished and live parts each start their dot pattern afresh, so the dot spacing may jump once at each join (every 512 fixes). This is noted in the code.
- Geometry simplification is MapLibre's GeoJSON tolerance; there is no 1.5 pt skip.

**`measure`.**
- `placed`: `LineString [A, me]`.
- `path`: `LineString [pts]`.
- `closed`: `LineString [pts + A]` plus `Polygon [pts + A]`.
- MapLibre lays out the dashes itself; nothing like `MeasureLineRenderer`'s Liang–Barsky and phase code is needed.

**`measure-points`.** One `Point` per measuring point, plus `letter = "measure-letter/<L>"` when there are ≥ 2. Letter images are made on demand and cached by letter.
- `MapController` keeps `markers: MutableList<GeoPoint>`, a copy of `measure.points`.
- `updateMeasureLine()` builds `measure` from `markers` and the measure kind.
- While a point moves (`adjusted`), `markers[i]` changes and both sources are re-sent on every move. This replaces the iOS `dragLine` layer: MapLibre swaps the tiles of a re-sent GeoJSON source without emptying them first, so there is no blink to work around. The difference is noted in the code.

**`selected`.** One `Point` or empty. It is set when `selectedPoint` changes and `measure == off`, and on every move.

**`draft`.** The draft ring as `Polygon`, or empty.

**`kml/<uuid>`** (built by `BlockLayers.shapes`, which keeps the same name and returns `(features, labels)`):
- Polygon features (outer ring plus holes) with `fill`, `stroke` and `width`.
- LineString features with `stroke` and `width`.
- Colours are `"rgba(r,g,b,a)"` strings, with r, g, b in 0–255 and a in 0–1, from `KMLColor.rgba()`. A stroke that is off becomes `"rgba(0,0,0,0)"`, as does a fill with `fill == false`.
- Grouping by style is not needed.
- With file colours on, polygons of one file all fill first and stroke afterwards. iOS draws fill and stroke per style group; this is noted.

**`kml/<uuid>/labels`.**
- Name points: `kind = "name"`, `img`.
- KML points: `kind = "point"`, `img`, `i` (index into the file's point list).
- `BlockLayers.points` (a list of file id, index, coordinate and name for each shown file) serves the tap check.

**`aviation`.** See §2.9.

### 2.6 Gestures

**MapLibre's own gestures** cover double-tap zoom, two-finger-tap zoom out, quick zoom (tap then drag), pinch, rotate and fling. Its `OnMapClickListener` comes from `MapGestureDetector.StandardGestureListener.onSingleTapConfirmed` (checked in the bytecode). That fires only after the double-tap timeout and never for:
- a double tap or quick zoom,
- a two-finger tap (`GestureDetector` cancels taps on `ACTION_POINTER_DOWN`),
- a long press.

That is the counterpart of `tapRecognizer.require(toFail:)` and `shouldRequireFailureOf`.

```kotlin
map.addOnMapClickListener { latLng -> mapTapped(latLng); true }
map.addOnCameraMoveStartedListener { reason ->
    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) model.following = false
}
map.addOnCameraMoveListener { cameraMoved() }   // mapViewDidChangeVisibleRegion
map.addOnCameraIdleListener { cameraIdle() }     // regionDidChange
```

**`touchRecorder`.** `MapContainerView.dispatchTouchEvent` sees every event before the MapView. On `ACTION_UP` of a one-pointer gesture that moved less than `ViewConfiguration.scaledTouchSlop`, it stores `lastTouch = (PointF(x, y), projection.fromScreenLocation(PointF(x, y)))`.

`mapTapped(latLng)` recovers the tap pixel as `projection.toScreenLocation(latLng)`; MapLibre converted that pixel with the current projection, so the round trip gives it back. It compares that pixel with `lastTouch` (< 1 dp), then runs §1.1 step 3.

**Hit tests:**
- KML point: distances to every entry of `blocks.points` through `toScreenLocation`. For "on its name", use `map.queryRenderedFeatures(RectF(spot ± 6dp), *blocks.pointLayerIds)`. It returns only symbols actually placed (visible), and a box intersecting that rect is the same as the box enlarged by 6 dp containing the spot. Read `i` from `feature.getNumberProperty("i")`.
- Aviation: `queryRenderedFeatures(RectF(spot ± 24dp), "aviation")` gives the visible candidates. Distance from `toScreenLocation(place)`. A candidate ≥ 24 dp away whose box contains the spot (`queryRenderedFeatures(PointF(spot), "aviation")`) counts as 23.

**Press and hold on a point.** MapLibre's long-click listener can't do this: it fires at the system long-press timeout (400–500 ms), not 0.35 s, and it can't stop the map's own move detector (4 dp threshold). Instead `MapContainerView` runs its own detector:

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    val c = controller ?: return super.dispatchTouchEvent(ev)
    if (c.adjustTouch(ev)) return true   // a point being moved owns the gesture; MapView sees nothing
    c.observeTouch(ev)                    // records taps (lastTouch), arms/disarms the press
    return super.dispatchTouchEvent(ev)
}
```

`MapController.AdjustPress` state machine:
- **`ACTION_DOWN`.**
  - `tapThenPress` = this down is ≤ `ViewConfiguration.getDoubleTapTimeout()` after the previous tap's up and within `scaledDoubleTapSlop` of it.
  - If `!tapThenPress && !model.isDrawingArea && movablePoint(near: down) != null`, post `begin` with `handler.postDelayed(…, 350)`.
- **`ACTION_MOVE`** more than 10 dp from the down, any `ACTION_POINTER_DOWN`, or `UP`/`CANCEL` disarms the press.
- **`begin`** (the delay ran out):
  1. Look again at `movablePoint(near: current finger)` and stop if there's none.
  2. `adjusting = true`.
  3. Send the MapView a synthetic `MotionEvent.obtain(downTime, now, ACTION_CANCEL, x, y, 0)` so its detectors stop now; this also stops MapLibre's own click and long-press.
  4. Turn off `uiSettings.isScrollGesturesEnabled`, `isZoomGesturesEnabled`, `isRotateGesturesEnabled`, `isDoubleTapGesturesEnabled` and `isQuickZoomGesturesEnabled`.
  5. `following = false`.
  6. `container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)` (the medium impact).
  7. `adjuster.begin(which, point, touch = finger in content-root coordinates (rawX/rawY − content root location on screen), scene = loupeScene(…))`.
- **While adjusting.** `MOVE` of the tracked pointer calls `adjuster.moved(touch)`. `UP` (or `POINTER_UP` of the tracked pointer) calls `adjuster.end(cancelled = false)`. `CANCEL` calls `end(cancelled = true)`. After either, the gestures go back on (tilt stays off) and `tapsIgnoredUntil = uptime + 400 ms`.
- **`adjusted(which, p, done)`.** Update `markers`/`selected` and their sources at once, and the model at most every 1/15 s or on `done`. On `done`: `updateMeasureLine()` and `performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)` (the light impact).

**`LassoView`.** Above the MapView and `GONE` unless it should show, so when visible it receives every touch and the map underneath none. It follows only the first pointer (`isMultipleTouchEnabled = false`). On `ACTION_UP` it calls `onFinish(points)`; on `ACTION_CANCEL`, `reset()`. `MapController.lassoFinished` runs `Simplify.douglasPeucker` on dp coordinates with tolerance 4, then converts the ring to coordinates with `projection.fromScreenLocation` in pixels.

**`AimLineView`.** `onDraw` strokes `Path(start → centre)` with `DashPathEffect(floatArrayOf(10dp, 7dp), phase)`, width 3 dp, pink, butt cap. Its entry and phase maths is `MapController.entry(from:to:into:)`, a pure function in `companion object`.

### 2.7 Camera API mapping

| iOS | MapLibre (`org.maplibre.android`) |
| --- | --- |
| `setRegion(center, span)` | `LatLngBounds.from(north, east, south, west)` → `moveCamera`/`easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))`. On launch, wait until the view has a size (`container.doOnLayout`). |
| `mapView.region` (for saving) | `projection.visibleRegion`: the four corners `farLeft`/`farRight`/`nearLeft`/`nearRight`, longitudes unwrapped around the centre, then min and max. The same helper serves `AviationLayer`. Stored as the string `"lat,lon,dLat,dLon"` under SharedPreferences `"camera"`. |
| 1500 m region on the first fix | bounds of ± 750 m (`dLat = 750/111_320`, `dLon = dLat/cos lat`) → `moveCamera(newLatLngBounds)` |
| `setCenter(c, animated)` | `easeCamera(CameraUpdateFactory.newLatLng(c))` / `moveCamera` |
| `zoom(onto:metersAcross:)` | `easeCamera(newCameraPosition(CameraPosition.Builder(map.cameraPosition).target(c).zoom(zoom + log2(across/300)).build()))` |
| `zoom(to: GeoBounds)` | expanded bounds → `easeCamera(newLatLngBounds(b, 0))` |
| `centerCoordinate` | `projection.fromScreenLocation(PointF(mapView.width/2f, mapView.height/2f))` |
| `camera.heading` | `map.cameraPosition.bearing` |
| map points per point | `metersPerPoint`: `fromScreenLocation(centre ± 50 dp)`, `Geo.distance/100` |
| `PointAdjuster` scrolling the map | target → `WebMercator.world` + (dx, dy) → `geo` → `moveCamera(newLatLng)` each frame |
| `CADisplayLink` | `Choreographer.getInstance().postFrameCallback`, `dt` from `frameTimeNanos` |
| ZoomScript zoom `z` | MapLibre zoom **z − 1**: MapLibre's world is 512·2^z dp wide, the script's 256·2^z |

`keepCameraUntil` and `tapsIgnoredUntil` use `SystemClock.uptimeMillis()`.

### 2.8 The loupe

**Options rejected:**
- `MapLibreMap.snapshot` or `MapSnapshotter`: asynchronous and slow, so not usable at 60 fps.
- A second `MapView`: another GL context, a style to load, and it needs `textureMode` to be clipped, scaled and faded.

**Chosen: draw the tiles ourselves, as iOS does with `drawImagery`.** `LoupeView.onDraw(canvas)` scales by density and draws §1.2's list in dp on a hardware canvas:
- Imagery tiles come from `LoupeImagery`, a small helper in `PointAdjuster.kt`:
  - a decoded `LruCache<TileID, Bitmap>` of about 24 entries, decoded with `BitmapFactory` from the imagery subsystem's in-memory encoded tiles (`ImageryMemory`), or else asked for with `TileRepository.shared.nearestImagery(t, urgent = true)` on `Dispatchers.IO`;
  - `has(t)` for `TileFallback.ancestor(t, 12, has)`;
  - `invalidate()` when a tile arrives (the counterpart of `DrawnMap.onArrival` / `announce`).
- Tiles are drawn with `Paint(Paint.FILTER_BITMAP_FLAG)`, and coarser ones with `canvas.clipRect(tileRect)`.
- Rotation: `canvas.rotate(-bearing)`, which has the same sense as `ctx.rotate(-heading)`.
- Lines: `Path` + `Paint` (`Cap.ROUND`, `Join.ROUND`). The measuring dashes use `DashPathEffect(floatArrayOf(8f, 6f), 0f)` and the 5 m ring `DashPathEffect(floatArrayOf(4f, 4f), 0f)`.
- The level: `round(log2(k·density/256))` clamped to [0, 22], where `k` is dp per world unit.

**`LoupeFrame`** (FrameLayout, 232 dp, non-clickable). It is added to `android.R.id.content` at the place given by `PointAdjuster.place(side = 232, gap = 44, touch, area)`. That is a pure `companion` function, and `area` is the content bounds less `WindowInsetsCompat` `systemBars() or displayCutout()`. Its parts, bottom to top:

1. **`glass: ImageView`**, rounded 32 dp (`clipToOutline`).
   - Content: `map.snapshot { bmp -> }` cropped to the frame's rect in map coordinates.
   - API 31+: `glass.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createColorFilterEffect(PorterDuffColorFilter(0x59000000, SRC_OVER)), RenderEffect.createBlurEffect(20dp, 20dp, Shader.TileMode.CLAMP)))`. This approximates `systemUltraThinMaterialDark`.
   - API 26–30: the crop scaled down to 1/8 and shown stretched with filtering, which gives a cheap blur, plus a `0x59000000` foreground tint.
   - Until the snapshot arrives the background is `0xB31C1C1E`.
   - A new snapshot is taken every 200 ms while the square is scrolling the map.
   - Liquid Glass has no Android equivalent; the rim is only 8 dp wide.
2. **`LoupeView`** at (8, 8), 216 dp, rounded 24 dp (`clipToOutline`), black background.
3. **Border**: 1 dp white 45% on the 32 dp rounded rect, drawn in `dispatchDraw`.

**Shadow:** `elevation = 14 dp`, a rounded `ViewOutlineProvider` (32 dp), and on API 28+ `outlineSpotShadowColor`/`outlineAmbientShadowColor` black. This approximates opacity 0.35, radius 14, offset y 6.

**Animations:** `animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).setInterpolator(OvershootInterpolator(0.6f))` from 0.6 / 0. Out: 180 ms to 0.85 / 0, then `removeView`.

### 2.9 Drone mode on MapLibre

The source `aviation` holds one `Point` per shown place, with:
- `id` (index into `AviationIndex.points`),
- `img` (the card image),
- `key` (1000 − priority),
- for kinds L/M/S/W only: `ring` (`"av-ring-8"` or `"av-ring-6.5"`) and `rwy` (`runwayHeading ?: 45`).

**Layer `aviation`** (the cards): `iconImage(get("img"))`, `iconAnchor(ICON_ANCHOR_TOP)`, `iconOffset(arrayOf(0f, -11f))`, `iconPadding(0f)`, `symbolSortKey(get("key"))`, `symbolZOrder(SYMBOL_Z_ORDER_SOURCE)`, and allow-overlap / ignore-placement false. The card image includes the 22 × 22 symbol slot at its top, so the collision box is the whole frame, as on iOS. Kinds without a runway have their whole chart symbol (halo, stroke, letters) drawn in that slot; seaplane bases have their "~".

**Layer `aviation-rings`** (directly below `aviation`):
- `iconImage(get("ring"))`, `iconRotate(get("rwy"))`, `iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP)`, allow-overlap and ignore-placement true.
- Filter `all(has("ring"), in(get("id"), literal(visibleIds)))`.
- MapLibre turns the runway with the map every frame. That replaces `headingChanged()` and redrawing every card per degree of rotation; on Android that would mean ~140 bitmap uploads per degree.
- `visibleIds` comes from `map.queryRenderedFeatures(RectF(0f, 0f, w, h), "aviation")`, which returns only placed cards. It is refreshed when the camera goes idle, every 100 ms during camera moves, and 350 ms (one symbol fade) after each source update. A ring therefore never shows without its card. At most for one refresh, a ring may lag its card fading in or out.

**`AviationLayer`:**
- `regionChanged()` works as §1.10, on `Dispatchers.Default` for the query and the bitmaps.
- `distancesChanged(me, pin)` uses the 20 m rule. It redraws all cards into new image ids, then calls `addImages`, `setGeoJson` and removes the generation before last.
- A place whose text is unchanged keeps its image.
- `isOn = false` empties the source, removes the `av/…` images and resets the filter.
- `AviationStore.load` reads `assets.open("Aviation.tsv")`; the assets come from `../SatMap/Resources`.

### 2.10 Who calls what

`MapController` collects the `AppModel` StateFlows, as the Combine bindings do:

| Change | Handler |
| --- | --- |
| `trackRevision` | `trackChanged` |
| `measure`, distinct | `measureChanged` → markers, line, aim |
| `selectedPoint` combined with `measure == off` | `selectedPointChanged` |
| `terrainMode`, distinct, after the first | `imagery.terrainChanged`, `aviation.isOn` |
| `selectedPoint` | `aviationDistances(pin)` |
| `kml.files` + `kml.useFileColours` | `blocks.update` |
| `isDrawingArea`, `lassoActive`, `draftArea != null` | lasso visibility |
| `isDrawingArea`, distinct | `lasso.reset()` |
| `draftArea` | `draftChanged` |
| `recorder.power`, distinct | `UserDotLayer` sleeping |

It exposes to AppModel `centerCoordinate: GeoPoint`, `pointsApart(a, b): Double` (dp), `center(on, animated)`, `zoom(onto, metersAcross)`, `zoom(to: GeoBounds)`, `locationUpdated(Location)`, `headingUpdated(Double?)`, `reloadImagery()` and `isOnScreen`.

`isShown` follows the Activity's `onStart`/`onStop` (the phone's screen; Android Auto keeps the process in the foreground).

`ZoomScript.runIfAsked(mapView, map, model)` triggers on `intent.getBooleanExtra("zoomScript", false)` or the SharedPreferences `"zoomScript"`. It logs with `Log.i("zoomscript", …)`. The watchdog is a `Handler` ticking every 100 ms.

### 2.11 Differences from iOS, each noted in the code where it happens

1. Map labels are app-drawn style images, because there are no glyphs (§2.3).
2. Runway lines are a separate map-aligned symbol layer, filtered to visible cards (§2.9).
3. There is no drag-line overlay: the GL measuring line is re-sent on every move (§2.5).
4. The compass arrow is map-aligned, so map rotation needs no updates, and only compass changes glide.
5. The track is split into finished and live parts, so the dot pattern restarts at each join.
6. Within a file, all polygon fills are drawn below all block strokes.
7. The user dot's collision shape is a box, not a circle.
8. The loupe rim is a blurred snapshot (RenderEffect on API 31+), not Liquid Glass or a live material.
9. Haptics are `LONG_PRESS` and `CLOCK_TICK`.
10. The loupe is a child of the content root, not the window.
11. Map symbols aren't exposed to TalkBack. iOS sets accessibility labels on aviation views; adding them would need an `ExploreByTouchHelper` on `MapContainerView`, which is not part of this port.

### 2.12 Unit tests (Robolectric / JVM, `app/src/test`)

These pure functions are kept in `companion object`s so they can be tested:
- `PointAdjuster.place` (above, below, beside, clamped)
- `PointAdjuster.fingerGround`
- the 5 m clamp and the scroll step `min(20, 1.5·(m − 5))·dt`
- `MapController.entry`
- `AviationCard.text` (strings and colours of each run, 24-character truncation, the 0.05 NM rule)
- `AviationCard.Icon` (drawn with native graphics: the arrow's and the pin's extents, rounded tips, the pin's hole, the advances)
- `AviationLayer` selection (km filter, 15% padding, the cap of 140 with priority then distance)
- `BlockLayers.shapes` (feature properties for every style case: defaults, `fill = false`, `outline = false`, width clamped to [1.5, 12], white when file colours are off)
- the lasso's 3 dp step and Douglas–Peucker with tolerance 4
- `MeasureMode.label`
- camera save and restore round trip
