# SatMap for Android: the screens (UI)

Sources: `SatMap/UI/*.swift`, the parts of `AppModel`, `OfflineManager`, `KMLLibrary`, `LocationRecorder` and `SatMapApp.swift` that the screens read, `SatMapUITests/ScreenshotTests.swift` (how the screens are driven and what is asserted), and `docs/screenshots/01…27` (600×1304 px captures of a 402×874 pt iPhone, so 1 pt ≈ 1.49 px). **Every pt below is a dp on Android, and every SwiftUI font size is an sp.**

---

## 0. Ground rules

- **Package:** `com.jacobusoettle.satmap.ui`. There is one Kotlin file per Swift file, with the same names:
  - `MapScreen.kt` holds `MapScreen` and every small view in `MapScreen.swift`: `CompassButton`, `MapButton`, `Crosshair`, `Hint`, `MeasurePanel`, `ScaleBarView`, `ElevationKey`, `ContourKeyView`, `ContourSample`, `ColourKeyView`, `ScaleBarShape`, `AimPanel`, `StopMeasuringButton`, `CoordinateRow`, `HeightBar`, `UnitSwitch`, `DroneCard`, `CoordinateBar`, `DownloadBanner`, `DrawingBar`.
  - `OfflineSheet.kt` holds `OfflineSheet`, `AreaRow`, `PlaceSearchView`, `PlaceAreaView`, `PresetAreaView`, `TrackAreaView`, `KMLAreaPicker`.
  - `NewAreaView.kt` holds `NewAreaView` and `TrackDownload`.
  - `AreaDetailView.kt`, `BlocksSheet.kt`, `CoordinateEntryView.kt`.
  - `ShareSheet.kt` holds `SharePresenter` and the MIME type for `.mbtiles`.
- **Android-only support files.** SwiftUI gives iOS these pieces for free, so they have no Swift counterpart; each file's header comment says so.
  - `Theme.kt`: colours, type scale, `SatMapTheme`.
  - `Form.kt`: the inset-grouped list pieces.
  - `SheetHost.kt`: bottom-sheet chrome, the in-sheet back stack and the back button.
  - `Icons.kt`: the custom vectors.
- **No new dependencies.** Everything below uses what `app/build.gradle.kts` already has:
  - Compose BOM 2026.03.01 (resolves material3 **1.4.0**, foundation 1.10.x)
  - `material-icons-extended` **1.7.8**
  - activity-compose 1.11.0
  - lifecycle-runtime-compose
  - androidx.core 1.17
  - MapLibre 13.7.1
- **Always dark.** The iOS app sets `.preferredColorScheme(.dark)`, so the system light theme is ignored.
- **Strings** are Kotlin literals at the point of use, as in Swift (the app isn't localised). They are copied exactly, including these Unicode characters: `–` U+2013, `·` U+00B7, `…` U+2026, `▲` U+25B2, `▼` U+25BC, `→` U+2192, `↻` U+21BB, `≈` U+2248, `²` U+00B2, `›` U+203A, `“ ”` U+201C/U+201D, `'` U+0027.
  - A string is reworded only where it names an iOS feature (§5.1).
- **Numbers:**

  | Swift | Kotlin |
  | --- | --- |
  | `Format.count` | grouped in the current locale (`NumberFormat.getIntegerInstance()`) |
  | `String(format:)` | core `stringFormat(...)` (POSIX `.`, printf rounding) |
  | `.rounded()` | core `Double.rounded()` |
  | `"\(int)"` | plain `toString()` |
  | `DistanceUnit.name` / `HeightUnit.name` | `spokenName` (as in core `Units.kt`; enum cases stay lower case: `metric`, `metres` …) |
  | `Format.bytes` | must reproduce `ByteCountFormatter(.file)`: decimal units, KB 0 decimals, MB 1, GB 2, trailing zeros dropped (`"68.4 MB"`, `"2 MB"`, `"42 GB"`, `"15.26 GB"`). This is owned by the `Format` port; the screens only call it. |

---

## 1. Visual language

### 1.1 Colours (`Theme.kt`)

| Token | Value | Used for |
| --- | --- | --- |
| `Accent` | `#FFBD29` (AccentColor asset 1.0/0.74/0.16) | Sheet buttons, toolbar texts, icons in list rows, checkmarks, back button, text cursor |
| `SystemYellow` | `#FFD600` (iOS 26 dark; iOS 18 was `#FFD60A`) | Active map button background, download banner progress bar, DrawingBar tint |
| `SystemGreen` | `#30D158` (iOS 26 dark, as iOS 18) | "Ready offline", complete check, switch track when on |
| `SystemOrange` | `#FF9230` (iOS 26 dark; iOS 18 was `#FF9F0A`; no screenshot shows it) | Warnings ("Not a position yet.", failures, "This may not fit…") |
| `SystemRed` | `#FF4245` (iOS 26 dark; iOS 18 was `#FF453A`) | Crosshair dot, compass north needle, destructive menu item and dialog button, swipe-to-delete background |
| `SheetBackground` | `#1C1C1E` | Sheet container and list background |
| `SheetBarScrolled` | `#2D2D2E` | A sheet's bar once its list has scrolled under it: iOS's bar material, 41–50 in screenshots 12, 17, 18, 20 |
| `CellBackground` | `#2C2C2E` | Form cards, alert and menu containers |
| `CellPressed` | `#3A3A3C` | Ripple / pressed row |
| `Separator` | `#38383A` | 0.5 dp row dividers, the hairline under a scrolled sheet bar |
| `LabelPrimary` | `#FFFFFF` | |
| `LabelSecondary` | `#99EBEBF5` (EBEBF5 @ 60 %) | Secondary texts, footers, headers, row values |
| `LabelTertiary` | `#4DEBEBF5` (@ 30 %) | Chevrons, placeholders, disabled rows |
| `SecondaryFill` | `#52787880` (#787880 @ 32 %, iOS `secondarySystemFill`, dark) | Switch track when off: (68, 67, 72) on a cell in screenshots 12 and 18 |
| `PasteFill` | `#BD8B1A` | Paste button container (screenshot 21) |
| `YouColour` | `#59DBFF` (0.35/0.86/1.0, `AviationView.youColour`) | Drone card "You" line |
| `PinColour` | `#FFB833` (1.0/0.72/0.2, `AviationView.pinColour`) | Drone card "Pin" line |
| `ContourBold` | `rgb(1.0, 0.74, 0.16)` | Legend's bold contour sample |
| `ContourThin` | `rgb(1.0, 0.84, 0.32)` @ 0.9 | Legend's thin contour sample |
| Overlay blacks | `Color.Black.copy(alpha = …)`: 0.4, 0.6, 0.65, 0.7, 0.72, 0.75 | As given per component |

- **Elevation ramp** (`ElevationKey.gradient`): build the colour stops from core `ElevationColours.stops` as `Color(red = r.toFloat(), green = g…, blue = b…)` at position `f`. For reference the stops are `#2952F2`@0, `#1AB8F7`@0.2, `#38CC5C`@0.4, `#F7E633`@0.6, `#FC8C1A`@0.8, `#E61F1F`@1.
- **`SatMapTheme`:**

  ```kotlin
  MaterialTheme(colorScheme = darkColorScheme(
      primary = Accent, onPrimary = Color.Black, secondary = Accent, onSecondary = Color.Black,
      background = Color.Black, onBackground = Color.White,
      surface = SheetBackground, onSurface = Color.White, onSurfaceVariant = LabelSecondary,
      surfaceContainerLowest = SheetBackground, surfaceContainerLow = SheetBackground,
      surfaceContainer = CellBackground, surfaceContainerHigh = CellBackground, surfaceContainerHighest = CellPressed,
      outline = Separator, outlineVariant = Separator, error = SystemRed, scrim = Color.Black),
    typography = SatTypography)
  ```

### 1.2 Type scale (iOS text styles at the default size; Roboto replaces SF Pro)

| SwiftUI | sp / line height | Compose `TextStyle` |
| --- | --- | --- |
| `.title2` | 22 / 28 | used only as an icon size |
| `.title3` | 20 / 25 | used only as an icon size |
| `.headline` | 17 / 22 SemiBold | `Headline` |
| `.body` | 17 / 22 Normal | `Body` (also M3 `bodyLarge`) |
| `.subheadline` | 15 / 20 | `Subheadline` |
| `.footnote` | 13 / 16 | `Footnote` |
| `.caption` | 12 / 16 | `Caption` |
| `.caption2` | 11 / 13 | `Caption2` |
| `.system(size: 30, weight: .bold)` | 30 / 36 Bold | `BigNumber` |

- **`.footnote`'s 16 is measured**, not Apple's 18: the lines of every wrapped footer in the iPhone screenshots (06, 07, 12, 21) are 16 pt apart. The other line heights are Apple's table; measure them the same way before relying on them for wrapped text. Compose trims a text's first and last line to the font (`LineHeightStyle` default), so a line height changes only the distance between lines.
- **Weights:** `.medium` = `FontWeight.Medium`, `.semibold` = `SemiBold`, `.bold` = `Bold`, `.heavy` = `ExtraBold`.
- **`.monospacedDigit()`:** `fontFeatureSettings = "tnum"`.
- **Text shadow** (`.shadow(color: .black, radius: 1.5)`): `TextStyle(shadow = Shadow(Color.Black, Offset.Zero, blurRadius = 3.dp.toPx()))`.
- **Sheet title:** Headline. Toolbar buttons ("Done", "Cancel", back label): Body, Normal weight, `Accent`.
- Map `SatTypography` onto M3 as `bodyLarge = Body`, `bodyMedium = Subheadline`, `bodySmall = Footnote`, `labelSmall = Caption2`, `titleMedium = Headline`, so stock M3 components look right.

### 1.3 Icon sizes

An SF Symbol drawn at text size *X* pt becomes a Material icon of about **1.2·X dp**:

| SF size | Icon |
| --- | --- |
| caption 12 | 14 dp |
| footnote 13 | 16 dp |
| subheadline 15 | 18 dp |
| body 17 | 20 dp |
| title3 20 | 24 dp |
| title2 22 | 26 dp |
| map buttons (20 pt semibold) | 24 dp (Landscape 38, GpsFixed and NearMe 26, Close 27: §2.4.4) |

**Except in a list `Label`:** there the symbols are drawn larger than their text size (21–26 pt in screenshot 06), so the rule underestimates them. A list row's icon is **28 dp** (`ListIconSize`), and `arrow.down.circle.fill` on the Download buttons (custom `ArrowDownCircleFill`, §6.3) 26.5 dp (its disc 22 dp, as on iOS). See §1.5.

**Except where a Material glyph fills less of its box than the SF symbol does of its frame:** the rule then draws it smaller than iOS, so the icon is sized to draw it as big as iOS's measured symbol. Over the map these are the map buttons' Landscape, GpsFixed, Close and NearMe (§2.4.4), the height pill's Landscape (22 dp, §2.3.8), the coordinate pills' `ContentCopy` (16 dp, §2.3.7) and the download banner's Pause (23 dp, §2.3.2).

### 1.4 Shared overlay styles (map screen)

- **Pill:** `background(Color.Black.copy(0.65f), CircleShape)`, padding horizontal 12 / vertical 8, content `White`.
- **Panel:** `background(Color.Black.copy(alpha), RoundedCornerShape(12.dp))`. The alpha and padding are given per panel.
- **Panels and the pill/hint backgrounds block the map.** A tap on them must not reach the map, or it would place a measuring point. Use a helper:

  ```kotlin
  Modifier.blocksMap() = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
  ```

  Apply it on each panel and pill container. Its own clickable children still get their taps, because they handle them first.
- Legends, the scale bar line, the crosshair and the terrain toast **do not** take touches: no pointer modifiers, so the map under them gets them.

### 1.5 The "Form" look (iOS inset-grouped `List`/`Form`), `Form.kt`

| Piece | Spec |
| --- | --- |
| Screen | `LazyColumn` on `SheetBackground`. Content padding: top 16 dp; bottom = navigation-bar inset + 15 dp (with the last section's 5.5 dp, 20.5 dp below the last card, as iOS's list ends 20.6 pt above the home indicator in screenshots 12, 17, 18, 20). |
| `FormSection(header?, footer?)` | Header: Footnote, `LabelSecondary`, **upper-cased** (`"Offline areas"` shows as `OFFLINE AREAS`), padding start/end 32 dp (16 margin + 16), bottom 6 dp. Card: horizontal margin 16 dp, `RoundedCornerShape(10.dp)`, `CellBackground`, clipped (so ripples and swipe stay inside). Footer: Footnote, `LabelSecondary`, padding start/end 32 dp, top 6 dp. A section without a footer ends with a 5.5 dp spacer under its card (iOS's empty footer). |
| Gap between sections | `Arrangement.spacedBy(29.5.dp)`, from the end of one section (its footer's text, or else its 5.5 dp spacer) to the start of the next (its header, or else its card). So a card is followed by 35 dp to the next section, a footer's last baseline by 33 dp, as on iOS (screenshots 06, 07, 21). The first section's header starts 1 dp below the top padding; a first section without a header starts 19 dp lower. |
| `FormRow` | Min height 44 dp; padding start 16, end 16 (14 before a switch), vertical 11. A row of several lines (`FormLinkRow` with content, `FormChoiceRow`, the Blocks file row) uses `MultiLineRowPadding`, 11.33 dp: SF's lines are about 0.3 pt taller than Roboto's trimmed ones, so Body + Caption make 58.3 dp (iOS 58.6) and three lines 75.3 dp (iOS 75.7). Title Body `LabelPrimary`; caption line Caption `LabelSecondary`, spacing 2. |
| Row with icon (`Label`) | `LabelIcon`: a box 28 dp wide and one Body line (22 dp) tall, the icon centred in it at `requiredSize(28.dp)` (`ListIconSize`, `Accent`), overflowing it as an SF Symbol overflows its line, so the row stays 44 dp whatever the icon's size. Then a 16 dp gap and the text, which starts 60 dp from the card's left edge. |
| Divider | 0.5 dp `Separator` between rows, inset to the text start (16 dp, or 60 dp for icon rows), none after the last row. |
| `NavigationLink` row | Clickable; trailing `Icons.AutoMirrored.Filled.KeyboardArrowRight` in `LabelTertiary`, drawn at 24 dp and 3.5 dp to the right over a 20 dp layout box (`offset(x = 3.5.dp).size(20.dp).requiredSize(24.dp)`): a 12 dp tall chevron whose right edge is 19 dp in from the card's, as iOS's. Title text `LabelPrimary`, icon `Accent`. |
| Action row (`Button` in a list) | Clickable; text **and** icon `Accent`. When disabled, text and icon are `LabelTertiary` and the row is not clickable. |
| Value row (`row(title, value)`) | Title Body primary; `Spacer(weight)`; value Body `LabelSecondary` with `tnum`. In the coordinate entry the value wraps and is end-aligned (`weight(1f, fill = false)`, `TextAlign.End`). |
| Toggle row | Whole row `Modifier.toggleable(value, role = Role.Switch)`, end padding 14 dp; title Body; trailing `IosSwitch(checked)`, which only draws (no semantics or clicks of its own): iOS's `UISwitch` (screenshots 04, 12, 18), a 51 × 31 dp capsule, `SystemGreen` when on and `SecondaryFill` when off, with a 27 dp white knob (a 1 dp shadow) 2 dp in from its ends, its offset and the track colour animated. Its right edge is 14 dp in from the card's. M3's `Switch` can't match it: its track and thumb sizes are fixed (52 × 32, a 16 dp thumb when off). |
| Text field row | `BasicTextField`, Body white with `LineHeightStyle(Alignment.Center, Trim.None)`, so the line is 22 dp and the cursor as tall as iOS's; `cursorBrush = SolidColor(Accent)`, placeholder in the same style, `LabelTertiary`, row padding. No underline and no outline. |
| Swipe-to-delete row | M3 `SwipeToDismissBox(state, backgroundContent = { red box, "Delete" Body White SemiBold end-aligned, padding end 20 }, enableDismissFromStartToEnd = false, onDismiss = { delete })`. Also `semantics { customActions = listOf(CustomAccessibilityAction("Delete") { delete(); true }) }`. |
| Pressed state | Default M3 ripple in `CellPressed`. |

---

## 2. The map screen (`MapScreen`)

### 2.1 Layers (bottom to top) and insets

```
Box(fillMaxSize, semantics { testTagsAsResourceId = true })
├─ MapViewRepresentable(model)      map subsystem; fills the whole window, behind the status and navigation bars
├─ Crosshair                        if showsCrosshair; centred on the WHOLE window (= the map's centre), no insets, no touches
├─ Box(fillMaxSize.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)))   ← "safe area" (excludes the IME)
│  ├─ Column(fillMaxSize, padding(start 12, end 12, bottom 8), verticalArrangement = spacedBy(8.dp), horizontalAlignment = CenterHorizontally)
│  │    DrawingBar? · DownloadBanner? · AimPanel? | MeasurePanel? · my CoordinateRow? · DroneCard? · Hint?
│  │    Spacer(Modifier.weight(1f))
│  │    if (!isDrawingArea) Row(fillMaxWidth, verticalAlignment = Bottom, padding(bottom = 124.dp)) {
│  │        Column(spacedBy 8, horizontalAlignment = Start) { ContourKeyView; ScaleBarView; ColourKeyView }
│  │        Spacer(weight 1f)
│  │        controls (side buttons)
│  │    }
│  └─ selected-point overlay (align BottomCenter, padding(start 12, end 12, bottom 8)), if selectedPoint != null && !isDrawingArea
├─ sheet host for model.sheet       (ModalBottomSheet, §3)
├─ sheet host for model.pendingArea (ModalBottomSheet, §3.9)
└─ "Clear the recorded track?" dialog
```

- **Edge-to-edge:** call `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))` in `MainActivity.onCreate`, so status-bar icons are light over the imagery. The manifest gets `android:windowSoftInputMode="adjustResize"`.
- **No extra top padding:** the first panel sits right under the status bar, as on iOS.
- **The controls lift** is `2 × (50 + 12) = 124 dp` (Swift `controlsLift`). It leaves room under the buttons for the tapped spot's coordinates.
- **Landscape:** no special layout, as on iOS (all orientations allowed; the manifest already has `configChanges` for orientation, so nothing is recreated). When the screen turns, the point at the safe area's middle stays at its new middle, as MapKit keeps its camera's centre (interaction spec §1.3).
- **Recomposition scope:** `AimPanel` reads `model.aim`, `ScaleBarView` reads `model.scale`, and the two keys read `model.legend`. Each collects its own `StateFlow`, so a pan recomposes only them (Swift keeps these out of `AppModel` for the same reason).

### 2.2 The top column, in order

| # | Element | Shown when |
| --- | --- | --- |
| 1 | `DrawingBar` | `isDrawingArea` |
| 2 | `DownloadBanner` | `offline.activeID != null` and that area is in `offline.areas`. A tap on the banner (outside the pause button) sets `model.sheet = offline`. |
| 3 | `AimPanel` | `measure is aiming` |
| 3 | `MeasurePanel` | `measure is placed / path / closed` |
| 4 | My `CoordinateRow` (`CoordinateBar` + `HeightBar`) | `recorder.location != null && !isDrawingArea`; centred |
| 5 | `DroneCard` | `terrainMode.showsAviation && !isDrawingArea && (droneReport.you != null \|\| droneReport.pin != null)` |
| 6 | `Hint` (location) | Permission denied, else reduced accuracy (§2.3.9) |

### 2.3 Components

#### 2.3.1 `DrawingBar`

- **Container:** `fillMaxWidth`, padding 12, `Black@0.75`, radius 12, content White, `Column(spacedBy 8, CenterHorizontally)`, `blocksMap()`.
- **While `draftArea == null`:**
  1. Segmented control bound to `lassoActive`, items `Move map` (false) and `Draw` (true), full width:
     - A `SegmentedControl` of our own, drawn as iOS's `UISegmentedControl` (screenshot 10): M3's segmented buttons are outlined capsule halves.
     - The track: 32 dp tall, `Color(0x3D767680)`, radius 9, 2 dp padding; one `selectable` segment per item (`Role.Tab`, in a `selectableGroup`), each `weight(1f)`.
     - The chosen segment: `White@0.27` with radius 7, over the track. It is see-through, as iOS's: the map shows through it as through the track, only lighter (grey 99 over the bar in screenshot 10).
     - The titles: Footnote White, **SemiBold for the chosen one and Regular for the other**, as UISegmentedControl's.
  2. Footnote: `Draw a loop around the area with one finger.` when `lassoActive`, else `Pan and zoom to the area, then switch to Draw.`
  3. `Row { TextButton("Cancel") ; Spacer(weight) }`. The button text is Body White and calls `model.cancelDrawing()`.
- **When a loop is drawn (`draftArea != null`):**
  1. `Download this area?` in Headline.
  2. `Row { "Cancel" → cancelDrawing() ; Spacer(weight) ; "Redraw" → model.draftArea = null ; Spacer(weight) ; "Continue" (Body SemiBold) → model.confirmDrawnArea() }`. All White, as `TextButton`s with `contentColor = White`.

#### 2.3.2 `DownloadBanner(area, progress, onPause)`

- **Container:** `Row(spacedBy 10, verticalAlignment = CenterVertically)`, `fillMaxWidth`, padding h12 v8, `Black@0.7`, radius 12, White, `blocksMap()`. In it: the left column, `Spacer(width 8)` (Swift's `Spacer()`, which stays at its 8 pt least since the column takes the rest of the width), and the pause button. The column and its bar therefore end 10 + 8 + 10 = 28 dp before the pause button, 71 dp from the banner's right edge (iOS 70.8 pt).
- **Left column** (`weight(1f)`, `spacedBy 3`):
  - `Downloading <area.name>` in Subheadline SemiBold, 1 line, ellipsis.
  - The detail line: Caption, `tnum`, alpha 0.85, **1 line, ellipsis** (screenshot 08 truncates it).
    - When `progress == null || progress.layer.isEmpty()`: `Starting…`
    - Otherwise: `"${p.layer} · zoom ${p.zoom} of up to ${p.maxZoom}: ${count(p.levelDone)}/${count(p.levelTotal)} · ${count(p.fetched)} tiles, ${Format.bytes(p.bytes)} saved"`
  - `LinearProgressIndicator(progress = { fraction }, color = SystemYellow, trackColor = White@0.2, height 4 dp, gapSize = 0.dp, drawStopIndicator = {})`.
  - `fraction = if (p != null && p.levelTotal > 0) levelDone / levelTotal else 0`.
- **Right:** a pause button, `Icons.Filled.Pause` **23 dp** with padding h4 v8 (31 × 39 dp), `contentDescription = "Pause"`, calling `onPause` (= `offline.pause(id)`). iOS's `pause.fill.padding(8)` is 31 pt wide with a glyph 13.4 pt tall, its centre 27.7 pt from the banner's edge (screenshot 08): Pause at 23 dp is as tall (an exception to §1.3's body size, 20 dp), and its frame is wider than SF's, so 4 dp at its sides keeps the button 31 dp wide.

#### 2.3.3 `AimPanel` (measure = aiming)

- **Container:** `fillMaxWidth`, `Column(spacedBy 4, Start)`, padding h14 v10, `Black@0.7`, radius 12, White, `blocksMap()`, with `StopMeasuringButton` overlaid at top-end.
- **Lines:**
  1. `You to the crosshair`: Caption SemiBold, alpha 0.85.
  2. Distance in `BigNumber` with `tnum`, plus `UnitSwitch`:
     - `Waiting for GPS…` when there is no `recorder.location`.
     - `–` when `aim.point == null`.
     - Otherwise `Format.distance(distance(location, aim.point), model.distanceUnit)`.
  3. Height line in Headline, alpha `1` when `aim.elevationPoint == aim.point`, else `0.6`:
     - No `aim.elevation` or no `model.userElevation`: `Height: loading elevation…` when online, else `Height: no elevation data here offline (download this area)`.
     - Otherwise, with `diff = c − u`:
       - `unit.whole(|diff|) == 0`: `Crosshair is level with you`
       - `diff > 0`: `▲ Crosshair is <d> higher than you`
       - otherwise: `▼ Crosshair is <d> lower than you`
       - `<d>` = `Format.heightDifference(diff, heightUnit)`.
  4. Only when both heights exist: `Crosshair <Format.height(c, unit)> · You <Format.height(u, unit)> above sea level`. Caption, alpha 0.85, or 0.5 while not current.
  5. `Move the crosshair onto the spot and tap the map (or the measure button) for point A.` Caption2, alpha 0.7.
- "Online" is `Connectivity.isOnline`, collected.

#### 2.3.4 `MeasurePanel` (placed / path / closed)

The container is the same as `AimPanel`, plus `StopMeasuringButton`. `points = model.measure.points`, `L(i) = MeasureMode.label(i)`.

- **closed:**
  1. `Shape ${L(0)}–${L(n-1)} · $n corners`: Caption SemiBold, alpha 0.85. The UI test asserts `Shape A–D · 4 corners`.
  2. `model.measuredArea?.let(Format::hectares) ?: "–"` in BigNumber `tnum`.
  3. `Perimeter ${markerDistance?.let { Format.distance(it, distanceUnit) } ?: "–"}` in Headline, with `UnitSwitch`.
  4. Only if `measuredArea < 1_000_000`: `${Format.count(area.rounded().toInt())} m²` in Caption, alpha 0.85.
  5. Actions: `Undo` only.
- **path:** `last = L(n-1)`.
  1. `From A to B` when n == 2, else `From A to $last · $n points`. Caption SemiBold, alpha 0.85.
  2. Distance (`markerDistance` formatted, or `–`): BigNumber `tnum`, `UnitSwitch`, **`testTag("measuredDistance")`**.
  3. Height text (below) in Headline.
  4. If n ≥ 3: `Last leg ${L(n-2)} → $last: ${Format.distance(Geo.distance(p[n-2], p[n-1]), unit)}`. Caption, alpha 0.85.
  5. Only if `markerElevation` and `lastElevation` are both known: `A ${height(a)} · $last ${height(b)} above sea level`. Caption, alpha 0.85.
  6. Actions: `Undo`, plus `Close shape` when n ≥ 3.
  7. Caption2, alpha 0.7: `Move the crosshair and tap the map for more points, or tap A to close the shape and see its area.` when n ≥ 3, else `Move the crosshair and tap the map for more points.`
- **placed:**
  1. Distance or `Waiting for GPS…`: BigNumber `tnum`, `UnitSwitch` (no test tag).
  2. Height text in Headline.
  3. Only if `markerElevation` and `userElevation` are both known: `Marker ${height(m)} · You ${height(u)} above sea level`. Caption, alpha 0.85.
  4. `Move the crosshair to the next spot and tap the map for point B.` Caption2, alpha 0.7.
- **Height text:**
  - Missing heights: the same two `Height: …` strings as `AimPanel`.
  - path (`diff = last − A`): `$last is level with A` / `▲ $last is <d> higher than A` / `▼ $last is <d> lower than A`.
  - placed (`diff = marker − you`): `Marker is level with you` / `▲ Marker is <d> higher than you` / `▼ Marker is <d> lower than you`.
- **Actions row:** `Row(spacedBy 10)`, padding top 2.
  - Each button is a `FilledTonalButton(colors = containerColor White@0.15, contentColor White)` with `RoundedCornerShape(8.dp)`, `contentPadding = PaddingValues(12.dp, 7.dp)` and min height 32 dp.
  - Content: icon 18 dp, a 6 dp gap, then the text in Subheadline SemiBold.
  - `Undo`: `Icons.AutoMirrored.Filled.Undo`, calls `model.undoMeasurePoint()`.
  - `Close shape`: `Icons.Outlined.Pentagon`, calls `model.closeMeasureShape()`.

#### 2.3.5 `StopMeasuringButton`

`Icons.Filled.Close` 18 dp White in a 42 dp box (12 dp padding), aligned `TopEnd` of the panel. `contentDescription = "Stop measuring"`, `testTag("stopMeasuring")` (Android extra), calls `model.clearMeasure()`.

#### 2.3.6 `CoordinateRow` (Swift `ViewThatFits`)

A custom `Layout`:
- Measure the children at their intrinsic width (`maxIntrinsicWidth`).
- If `sum + 8 dp × (n−1) ≤ maxWidth`, place them in a row with 8 dp gaps, centred horizontally and vertically.
- Otherwise stack them in a column with 6 dp gaps, centred.

#### 2.3.7 `CoordinateBar(icon, text, id, iconSlot, onClose?)`

- **Container:** pill (padding h12 v8, `Black@0.65`, `CircleShape`), `Row(spacedBy 8)`, White, `blocksMap()`.
- **Copy button:** `Row(spacedBy 6, CenterVertically)` holding:
  - The icon 20 dp tall (a narrow one, the pin, keeps its narrow frame), laid out in a slot as tall as SF's frame for that symbol at body size: `Modifier.height(iconSlot).requiredHeight(20.dp)`. `iconSlot` is 18.7 dp for `location(.fill)` (`NearMe`), 19.7 for `mappin` and 19.1 for `mappin.circle.fill`. With the text line (about 17.7 dp) it sets the pill's height, as on iOS: 34.7, 35.7 and 35.1 dp (screenshots 01, 15, 24).
  - Text in Subheadline SemiBold `tnum`, 1 line, `autoSize = TextAutoSize.StepBased(minFontSize = 10.5.sp, maxFontSize = 15.sp, stepSize = 0.25.sp)` (Swift `minimumScaleFactor(0.7)`). It shows `Copied` while `copied`, else `text`.
  - Unless copied: `Icons.Outlined.ContentCopy` at **16 dp**, alpha 0.8: `doc.on.doc` at caption size is 12 × 14.7 pt (screenshot 01), and ContentCopy fills 19 × 22 of its 24.
- **On tap:**
  1. `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Coordinates", text))`.
  2. `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.Confirm)` (Swift success notification).
  3. `copied = true`, then false after **2.5 s** (`LaunchedEffect(copied) { delay(2500) }`).
- **Copy button semantics:** `clearAndSetSemantics { contentDescription = if (copied) "Copied" else "Copy $text"; role = Role.Button; testTag = id; onClick { … } }`. The test asserts the description is `Copied` right after a tap, and that it changes after the pin is dragged.
- **Close button** (if `onClose`): `Icons.Filled.Close` 14 dp in a 24 dp box, `contentDescription = "Hide coordinates"`, `testTag("hideCoordinates")` (Android extra).

#### 2.3.8 `HeightBar(elevation, gpsAltitude = null, unit, id, onTap)`

- **Container:** pill with padding h10 v8, `Row(spacedBy 5, CenterVertically)`, wraps its content (`fixedSize`), White.
- **Content:**
  - `Icons.Filled.Landscape` drawn at **22 dp** and laid out 22 × 12 dp (`Modifier.size(22.dp, 12.dp).requiredSize(22.dp)`): `mountain.2.fill` at caption size is 20 × 10.5 pt (screenshots 01, 15, 24), and Landscape fills 22 × 12 of its 24. Laid out only as tall as its drawing, it leaves the text line to set the pill's height (34 dp, as iOS's).
  - Text in Subheadline SemiBold `tnum`, 1 line:
    - `Format.height(h, unit)` where `h = elevation ?: gpsAltitude`.
    - Otherwise `… ${unit.symbol}` when online, else `– ${unit.symbol}`.
  - Only when `elevation == null && gpsAltitude != null`: `GPS` in Caption2 Bold, alpha 0.75.
- **Tap:** `onTap` (= `model.nextHeightUnit()`).
- **Semantics:** `clearAndSetSemantics { contentDescription = "Height"; stateDescription = spoken; role = Role.Button; onClick(label = "Shows heights in ${unit.next.spokenName}") { onTap(); true }; testTag = id }`.
  - `spoken`: `"${Format.height(h, unit)} above sea level"`, plus `", from GPS"` when `elevation == null`.
  - With no height: `Loading` when online, else `No elevation data here offline`.
  - The test checks that the value contains `above sea level`, then ` ft ` after a tap, then ` m ` after another.
- **`gpsAltitude(loc)`** (Swift: vertical accuracy in (0, 25] → altitude above mean sea level):

  ```kotlin
  if (LocationCompat.hasMslAltitude(loc) && LocationCompat.hasMslAltitudeAccuracy(loc) && LocationCompat.getMslAltitudeAccuracyMeters(loc).let { it > 0 && it <= 25 }) LocationCompat.getMslAltitudeMeters(loc) else null
  ```

  - Android's plain `altitude` is above the WGS84 ellipsoid (about 30 m off in South Africa), so it is not used.
  - The location subsystem must add the MSL altitude to each fix: `AltitudeConverter().addMslAltitudeToLocation(...)` on API 34+, off the main thread. Without it no GPS height is shown.

#### 2.3.9 `Hint(text, onClose? = null)`

- **Container:** `Row(spacedBy 8, Top)`, padding h12 v8, `Black@0.65`, radius 10, White, wraps its content and is centred.
- **Text:** Footnote Medium.
- **Optional close:** `Icons.Filled.Close` 16 dp, labelled `Stop measuring`. It exists in Swift but no caller uses it now; port it anyway.
- **Conditions and texts:**
  - Permission denied, or location off on the phone (iOS reports `.denied` for both), and the request has been answered: `Location is off for SatMap. Turn it on in Settings › Location and in Settings › Apps › SatMap › Permissions.` (Android wording; iOS: `Location is off for SatMap. Turn it on in Settings › Privacy › Location Services.`)
  - Only COARSE location granted (iOS `reducedAccuracy`): `Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › SatMap › Permissions › Location (Use precise location).` (iOS: `Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location.`)
  - The hint is not tappable, as on iOS.

#### 2.3.10 `DroneCard(report)`

- **Container:** wraps its content and is centred. `Column(spacedBy 4, Start)`, padding h12 v8, `Black@0.72`, `RoundedCornerShape(12.dp)`, `border(0.5.dp, White@0.18)`, `blocksMap()`, `semantics(mergeDescendants = true)`, `testTag("droneCard")`.
- **One line per party** (`report.you`, then `report.pin`): `Row(spacedBy 6, CenterVertically)`, all in the line's colour:
  - Icon at 13 dp in a 14 dp-wide box: `Icons.Filled.NearMe` for You, `MapPin` for Pin.
  - Who: `You` / `Pin`, Caption ExtraBold, alpha 0.8.
  - Text: Subheadline Bold `tnum`, 1 line, `autoSize` 11.25–15 sp (`minimumScaleFactor 0.75`).
- **Colours:** `YouColour` for You, `PinColour` for Pin.
- **Example:** `You 6.1 NM NE of FASH Stellenbosch`.

#### 2.3.11 `Crosshair`

A 56 × 56 dp `Canvas` drawn in this order:
1. White circle stroke, 2 dp wide, diameter 36.
2. White vertical bar, 2 × 56.
3. White horizontal bar, 56 × 2.
4. `SystemRed` filled dot, diameter 6.

Every element gets a black shadow with radius 1.5 dp. Draw with `drawIntoCanvas { it.nativeCanvas }` and a `Paint` with `setShadowLayer(1.5.dp.toPx(), 0f, 0f, BLACK)`.

`showsCrosshair` is true for aiming, placed and path; false for off and closed.

### 2.4 Bottom row: legends and side buttons

#### 2.4.1 `ContourKeyView` (shown iff `legend.key.contours != null`)

- **Layout:** `Column(spacedBy 2, Start)`, White.
  - `Contours`: Caption2 SemiBold, alpha 0.9.
  - `Row(spacedBy 5, CenterVertically)`, Caption SemiBold `tnum`, all with text shadow:
    - `ContourSample(bold = false)`
    - `Format.height(c.step)` (metres always: the Swift passes no unit)
    - `ContourSample(bold = true)` with an extra 4 dp start padding
    - `Format.height(c.index)`
- **`ContourSample`:** an outer capsule 20 × (3, or 4.5 when bold), `Black@0.4`, with an inner capsule centred inside, 18 × (1.6, or 3 when bold), `ContourBold`, or `ContourThin` at alpha 0.9.
- **Semantics:** `clearAndSetSemantics { contentDescription = "Contour lines"; stateDescription = "every ${height(step)}, bold every ${height(index)}"; testTag = "contourKey" }`. No touches.

#### 2.4.2 `ScaleBarView(scale, onTap)` (shown iff `scale.width > 0`)

- **Layout:** `Column(spacedBy 2, Start)`, padding bottom 4.
- **Label:** `scale.label` in Caption SemiBold `tnum`, text shadow, padding top/bottom 3 and end 6.
  - It takes a tap through `UnitSwitch` (`onTap = model.nextDistanceUnit`).
  - Semantics: `contentDescription = "Scale"`, `stateDescription = label`.
- **Bar:** `ScaleBarShape` ⊔, a 6 dp-tall `Canvas` of width `scale.width` dp. Path: (0,0) → (0,h) → (w,h) → (w,0). White stroke 2 dp, `StrokeCap.Square`, black shadow 1.5 dp. **No touches.**
- `MapScale.maxWidth = 110.0` dp. The map subsystem feeds `scale.update(metersPerPoint)` with metres per dp, measured across 100 dp around the centre (as Swift does).

#### 2.4.3 `ColourKeyView` (shown iff `legend.key.colours != null`)

- **Layout:** `Column(spacedBy 3)`.
  - Bar 110 × 8 dp, `RoundedCornerShape(2.dp)`, filled with `Brush.horizontalGradient(colorStops)`, with a 1 dp `White@0.85` line **centred on its edge**, as SwiftUI's `stroke` draws it: `drawWithContent { drawContent(); drawRoundRect(…, cornerRadius = 2 dp, style = Stroke(1 dp)) }`. Half of the line is outside the bar, so the key is 111 × 9 dp over all with 7 dp of colours inside the line (screenshot 23). Not `Modifier.border`, which keeps all of it inside.
  - A `Row` of width 110: `Format.height(r.low)` `Spacer(weight, min 6)` `Format.height(r.high)`. Caption SemiBold `tnum`, White, text shadow.
- **Semantics:** `clearAndSetSemantics { contentDescription = "Elevation colours"; stateDescription = "blue ${height(low)} to red ${height(high)}"; testTag = "colourKey" }`. No touches.

#### 2.4.4 `MapButton(icon, label, active = false, dimmed = false, heightColours = false, onClick, onLongClick? = null)`

- **Shape:** 50 dp circle. Background `SystemYellow` when active, else `Black@0.6`. Border `1.dp White@0.3`. `Modifier.shadow(4.dp, CircleShape, ambientColor = Black@0.35, spotColor = Black@0.35)`.
- **Icon:** 24 dp (`mapButtonIconSize(icon)`), except for the glyphs that fill less of their box than the SF symbols do of their frames, so that they draw as big as iOS's (screenshots 01, 02, 19):
  - `Landscape` (filled and outlined) 38 dp: it fills 22 × 12 of its 24 and draws 34 × 18.5 (`mountain.2` 35 × 17.5 pt; Landscape is wider for its height than mountain.2, so no size matches both).
  - `GpsFixed` 26 dp: it fills 22 of its 24 (`scope` 23.5 pt).
  - `Close` 27 dp: it fills 14 of its 24 (`xmark` 15.5 pt).
  - `NearMe` (filled and outlined) 26 dp: it fills 18 of its 24 (`location(.fill)` 18.8 pt).
  - Tint is Black when active, otherwise White with alpha 0.45 when dimmed, else 1.
  - **heightColours** draws the icon in the elevation ramp, blue at the bottom to red at the top: `Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent { drawContent(); drawRect(Brush.verticalGradient(stops mapped to y = 1 − f), blendMode = BlendMode.SrcIn) }`.
- **Interaction:** `combinedClickable(onClick, onLongClick, onLongClickLabel)` with a ripple.
- **Semantics:** `contentDescription = label`, `role = Role.Button`, plus the per-button test tag below.

#### 2.4.5 `controls`: `Column(spacedBy 12.dp, CenterHorizontally)`, 50 dp wide, top to bottom

| # | Button | Icon (state) | `contentDescription` / extra semantics | Tap | Long press | Android test tag |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | **Compass slot** (always 44 × 44 dp, even when the compass is hidden) | `CompassButton` (§2.4.6) | `Compass` | `model.map?.resetNorth()` | none | `compassButton` |
| 1 | Terrain | contours or contoursAndColours: `Icons.Filled.Landscape`; colours: `Icons.Filled.Palette`; drone: `Icons.Filled.Flight` rotated 90° (points right like SF `airplane`); off: `Icons.Outlined.Landscape`. `dimmed = mode == off`; `heightColours = mode.showsColours` | `Contour lines`, **`stateDescription = terrainMode.title`** | `nextTerrainMode()` | none | `terrainButton` |
| 2 | Blocks | `SquareDashed` (custom) | `Blocks` | `model.sheet = blocks` | DropdownMenu (below), only when `kml.files` is not empty; `onLongClickLabel = "Show or hide files"` | `blocksButton` |
| 3 | Offline maps | `Icons.Outlined.ArrowCircleDown`, or custom `ArrowDownCircleFill` while downloading (a black disc 20 dp across with the arrow cut out of it in the button's yellow, as `arrow.down.circle.fill` in screenshot 08; Material's `Filled.ArrowCircleDown` is the ring); `active = offline.activeID != null` | `Offline maps` | `model.sheet = offline` | none | `offlineButton` |
| 4 | Go to coordinates | `LocationMagnifyingGlass` (custom) | `Go to coordinates` | `model.sheet = coordinates` | none | `goToButton` |
| 5 | Measure | off: `Icons.Outlined.Straighten`; aiming/placed/path: `Icons.Filled.GpsFixed`; closed: `Icons.Filled.Close`. `active = measure != off` | `Measure` | `model.measureButtonTapped()` | none | `measureButton` |
| 6 | My location | `Icons.Filled.NearMe` when `following`, else `Icons.Outlined.NearMe` | `My location` | `model.centerOnUser()` | DropdownMenu with one item: `Clear recorded track`, `Icons.Outlined.Delete`, text and icon `SystemRed`. Its click sets `confirmClearTrack = true`. `onLongClickLabel = "Clear recorded track"` | `myLocationButton` |

- **Blocks long-press menu** (`DropdownMenu` anchored to the button, `containerColor = CellBackground`; it closes after any item):
  - For each file: `DropdownMenuItem(text = f.name, leadingIcon = Icons.Filled.CheckCircle if f.isShown else Icons.Outlined.Circle)`, calling `model.kml.setShown(f.id, !f.isShown)`.
  - Then `HorizontalDivider()` and `Colours from the files` with `Icons.Filled.CheckBox` or `Icons.Outlined.CheckBoxOutlineBlank`, calling `model.kml.useFileColours = !useFileColours`.
  - Do a `HapticFeedbackType.LongPress` when a menu opens.
- **`nextTerrainMode()`:**
  1. `model.terrainMode = terrainMode.next`.
  2. `terrainToast = terrainMode.title`.
  3. Restart a 1.8 s timer, held in a `LaunchedEffect(toastKey)` where every tap bumps the key.
  4. When the timer ends, `terrainToast = null` with a fade.
- **Terrain toast:**
  - **Placement:** zero-size, so it never widens the column. `Modifier.layout { m, _ -> val p = m.measure(Constraints(maxWidth = 230.dp.roundToPx())); layout(0, 0) { p.place(-p.width - 10.dp.roundToPx(), -p.height / 2) } }`, attached at the button's left-centre. The toast's right edge sits 10 dp left of the button (Swift: width 230, trailing, offset −60 from a 50 dp button).
  - **Style:** Footnote SemiBold White, `TextAlign.End`, wraps; padding h10 v6, `Black@0.75`, radius 12.
  - **Animation:** appears at once; disappears with `fadeOut(tween(350))` (`AnimatedVisibility`).
  - Hidden from accessibility (`clearAndSetSemantics {}`) and takes no touches.
  - Texts are the `TerrainMode.title`s: `Contour lines`, `Contour lines and elevation colours`, `Elevation colours`, `Drone mode: airfields and navaids`, `Contours and colours off`.

#### 2.4.6 `CompassButton` (MKCompassButton, adaptive)

- **Why custom:** MapLibre's own compass is a view inside `MapView` that can't sit in the Compose column. Turn it off with `UiSettings.setCompassEnabled(false)` and draw this one instead.
- **Data:** `bearing` = `MapController.bearing: StateFlow<Double>`, which the map updates from `MapLibreMap.addOnCameraMoveListener { cameraPosition.bearing }`.
- **Visibility:** visible while the normalised `|bearing| > 0.5°`, with `fadeIn`/`fadeOut(200 ms)`. While hidden the 44 dp slot stays, but it takes no touches and has no semantics.
- **Drawing:** a 40 dp circle centred in the slot, `Black@0.6`, border `1.dp White@0.3`. A `Canvas` rotated by `-bearing` holds:
  - A north needle: `SystemRed` triangle 6 dp wide, 13 dp long from the centre.
  - A south needle: `White@0.85`, the same size.
  - `N` in 10 sp Bold White, 12 dp above the centre in the rotated frame.
- **Tap:** `model.map?.resetNorth()`, `onClickLabel = "Point north up"`. It turns the map north up about the safe area's middle, which stays where it is, as `MKCompassButton` keeps the camera's centre (you stay put while following): `easeCamera(newCameraPosition(…bearing(0.0).target(MapController.northUp(…))), 500)`, as long as MapLibre's own. `MapLibreMap.resetNorth()` turns about the camera's target, the view's middle, so it is only used before the map has started.

### 2.5 The tapped spot (bottom, `overlay(alignment: .bottom)`)

- **Shown when:** `model.selectedPoint != null && !isDrawingArea`.
- **Layout:** `Column(spacedBy 6, CenterHorizontally)`, padding start/end 12 and bottom 8 inside the safe area.
  1. If `selectedName != null`: the name capsule. Subheadline Bold White, 1 line, ellipsis, padding h12 v5, `Black@0.65`, `CircleShape`, `testTag("tappedName")`.
  2. `CoordinateRow`:
     - `CoordinateBar(icon = MapPin if selectedName == null else MapPinCircleFill, text = Format.coordinate(p), id = "tappedCoordinates", iconSlot = 19.7.dp if selectedName == null else 19.1.dp, onClose = { model.selectedPoint = null })`
     - `HeightBar(elevation = model.selectedElevation, unit = heightUnit, id = "tappedHeight", onTap = model::nextHeightUnit)`
- **My row** (top, §2.2) is `CoordinateBar(icon = Icons.Outlined.NearMe if recorder.power == sleeping else Icons.Filled.NearMe, text = Format.coordinate(location), id = "myCoordinates", iconSlot = 18.7.dp)` beside `HeightBar(model.userElevation, gpsAltitude(loc), unit, "myHeight", nextHeightUnit)`.
- `Format.coordinate` is `"%.6f, %.6f"`, through `stringFormat`.

### 2.6 `UnitSwitch` (modifier)

```kotlin
Modifier.clickable(role = Role.Button, onClickLabel = "Tap to show distances in other units", onClick = action)
```

No ripple (`indication = null`), because the iOS version has no highlight. The `name` is always `distances`.

### 2.7 Dialogs on the map screen

**Clear the track** (iOS `confirmationDialog`, title visible):

```kotlin
AlertDialog(containerColor = CellBackground,
  title = { Text("Clear the recorded track?") },
  confirmButton = { TextButton({ model.clearTrack(); dismiss() }) { Text("Clear track", color = SystemRed) } },
  dismissButton = { TextButton(dismiss) { Text("Cancel") } })
```

**Keep the screen on while downloading** (iOS `isIdleTimerDisabled`): `val view = LocalView.current; DisposableEffect(activeID != null) { view.keepScreenOn = activeID != null; onDispose { view.keepScreenOn = false } }`.

### 2.8 Sharing in and out (MainActivity, SatMapApp's `onOpenURL`)

- **KML/KMZ shared into the app** (`ACTION_VIEW` / `ACTION_SEND` with `application/vnd.google-earth.kml+xml`, `application/vnd.google-earth.kmz`, or `*.kml`/`*.kmz` paths): call `model.kml.importFile(uri) { file -> file?.document?.bounds?.let { model.map?.zoom(it) } ?: run { model.sheet = blocks } }`. The Blocks sheet then shows why the import failed. Handle it in `onCreate` and in `onNewIntent`.
- **`SharePresenter.present(items, completion)`** (`ShareSheet.kt`):
  - `OfflineManager` calls it from outside any composable, so it publishes `MutableStateFlow<ShareRequest?>`. `MapScreen` (always composed) launches each request through `rememberLauncherForActivityResult(StartActivityForResult())` with `Intent.createChooser(Intent(ACTION_SEND_MULTIPLE).apply { type = "application/octet-stream"; putParcelableArrayListExtra(EXTRA_STREAM, uris); clipData = …all uris…; addFlags(FLAG_GRANT_READ_URI_PERMISSION) }, null)`.
  - The result callback is the "share sheet closed" moment: call `completion()`.
  - With no started activity (app in the background), call `completion()` at once, as Swift does when there is no window.
  - The URIs come from a `FileProvider` with authority `${applicationId}.files` and `<cache-path name="export" path="Export/"/>` (Swift's `exportFolder`).

---

## 3. Sheets

### 3.0 Hosting, chrome, in-sheet navigation (`SheetHost.kt`)

**Presentation.** Every iOS `.sheet` becomes an M3 `ModalBottomSheet`:

```kotlin
ModalBottomSheet(
  onDismissRequest = onDismiss,
  modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp),   // map shows above, dimmed, as the iOS page sheet
  sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),                // always the large detent
  sheetGesturesEnabled = true,                                                             // drag down to dismiss, as on iOS
  shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
  containerColor = SheetBackground, contentColor = Color.White, tonalElevation = 0.dp,
  scrimColor = Color.Black.copy(alpha = 0.4f),
  dragHandle = null,                                                                       // iOS shows no grabber here
  contentWindowInsets = { WindowInsets(0) },                                               // insets handled inside (§6.6)
) { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { … } }
```

- **Two hosts on `MapScreen`:**
  - `model.sheet` (`blocks` / `offline` / `coordinates`).
  - `model.pendingArea` (a drawn lasso area): `NewAreaView(showsCancel = true)` inside its own sheet scaffold.
- **Dismissing:**
  - A swipe on the `sheet` host sets `model.sheet = null`.
  - A swipe on `pendingArea` sets `model.pendingArea = null; model.draftArea = null` (Swift `onDismiss`).
  - "Done" and "Cancel" animate first (`scope.launch { sheetState.hide() }.invokeOnCompletion { state = null }`).
  - When the model clears the state itself (`startDrawingArea`, Download, `measure(from:)`, Show on the map), the host keeps showing the last content while it calls `sheetState.hide()`, then leaves composition.
- **Top bar** (`SheetTopBar`):
  - `CenterAlignedTopAppBar(expandedHeight = 56.dp, windowInsets = WindowInsets(0), colors = topAppBarColors(containerColor = if (scrolled) SheetBarScrolled else SheetBackground, scrolledContainerColor = SheetBarScrolled))`: without a scroll behaviour the bar only shows `containerColor`, and it fades between the two.
  - Title: Headline White, 1 line, ellipsis, `Modifier.semantics { heading() }.testTag("screenTitle")`.
  - **Leading slot**, one of:
    - `TextButton("Done")` or `TextButton("Cancel")`: Body, `Accent`.
    - The back button: `Row(padding start 5.33, end 8, top 8, bottom 8) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, 23.5 dp, Accent); Text(previousTitle, Body, Accent, 1 line, offset x = -2 dp) }`, with `clearAndSetSemantics { contentDescription = "Back"; role = Button; testTag = "back" }`. With the bar's 4 dp inset the chevron's tip is 9.4 dp from the sheet's edge and 11.4 × 19.4 dp, and the title starts 31.5 dp in, as on iOS (screenshots 07, 18, 26).
  - **Trailing slot:** `IconButton` (Blocks "+").
  - When the list has scrolled (`listState.canScrollBackward`), the bar turns `SheetBarScrolled` and a 0.5 dp `Separator` line shows under it, as iOS's bar turns grey with a hairline (screenshots 12, 17, 18, 20).
- **Back stack.** navigation-compose is not used: it isn't a dependency, and the routes carry non-serialisable objects (`FoundPlace`, `PendingArea`, `CoverageShape`).
  - Each sheet keeps `val stack = remember { mutableStateListOf<Route>(Root) }`. A route is an object that holds its own screen state (§6.5), so returning to it keeps its state, as with a SwiftUI `NavigationStack`.
  - Shown through `AnimatedContent(stack.last())`:
    - push: `slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }`
    - pop: `slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }`
  - `BackHandler(enabled = stack.size > 1) { stack.removeLast() }` goes inside the sheet content. It is registered after the sheet's own back callback, so it wins; at the root, system back closes the sheet. If an instrumentation test shows otherwise, use `ModalBottomSheetProperties(shouldDismissOnBackPress = false)` and do both steps in our handler.
  - The back label is the previous route's title: `Offline maps`, `Find a place` or `Choose KML`.

### 3.1 Blocks (`BlocksSheet`, `model.sheet == blocks`)

- **Top bar:** title `Blocks`; leading `Done` (dismiss); trailing `IconButton(Icons.Filled.Add, tint Accent, contentDescription = "Import KML", testTag "importKml")`.
  - "+" launches `rememberLauncherForActivityResult(OpenMultipleDocuments())` with `arrayOf("*/*")` and calls `library.importFile(uri)` for each result. Android MIME types for KML are unreliable (often `application/octet-stream`), so every file is offered and the import says what is wrong.
- **List:**
  - If `library.files` is empty: a card with one row, `Import a .kml or .kmz file to show your blocks on the map.` in Body `LabelSecondary`.
  - **Files section.** Footer, only if files exist: `Switch a file off to take its blocks off the map.`
    - Each file is a swipe-to-delete row (`library.delete(f.id)`): `Row(spacedBy 12)`, then:
      - **Left** (`weight 1f`, clickable, `Column(spacedBy 2)`): `f.name` in Body primary, then `"${Format.fileContents(FileShapes(f.document))} · tap to show on map"` in Caption secondary (`remember`ed per document), e.g. `7 shapes · 1 point · tap to show on map`. `Format.fileContents` counts the file's shapes (`FileShapes.areas`), lines and points with `Format.count` as `"<n> shape|shapes"`, `"<n> line|lines"`, `"<n> point|points"`, leaves out the kinds it hasn't and joins the rest with `" · "`; each row of Choose a file (§3.4) shows the same text.
      - On click: `library.setShown(f.id, true)`, `f.document.bounds?.let { model.map?.zoom(it) }`, then dismiss the sheet.
      - **Right:** `IosSwitch(f.isShown, Modifier.minimumInteractiveComponentSize().toggleable(f.isShown, role = Role.Switch) { library.setShown(f.id, it) }.semantics { contentDescription = "Show ${f.name} on the map" })`, 14 dp from the card's edge. The texts have `MultiLineRowPadding` above and below (the row is 58 dp, as iOS's).
  - **Colours section** (only if files exist). Footer `Off: every block is drawn in white.`
    - One row, `Modifier.toggleable(library.useFileColours, role = Role.Checkbox) { library.useFileColours = it }` with `semantics { stateDescription = if (on) "On" else "Off" }` and `testTag("useFileColours")`.
    - Icon: `Icons.Filled.CheckBox` or `Icons.Outlined.CheckBoxOutlineBlank`, 30 dp (a 23 dp square, as `checkmark.square.fill`), `Accent`, in a `LabelIcon` box, so the row is 44 dp.
    - 16 dp gap, then `Use the colours in the files` in Body **primary**.
- **Alert:** when `library.importError != null`: `AlertDialog(title "Couldn't import", text = importError, confirm "OK")`. OK or dismiss sets `importError = null`.

### 3.2 Offline maps (`OfflineSheet`, `model.sheet == offline`), route Root

- **Top bar:** title `Offline maps`, leading `Done`.
- **On first show:** `offline.refreshSizes()`.
- **Free space:** `freeSpace` is computed off the main thread (`withContext(Dispatchers.IO) { AppPaths.freeDiskSpace() }`) in `LaunchedEffect("${completedRevision}-${areas.size}")`.

**Section 1** (no header). Footer: `Saves satellite imagery at the highest detail available plus elevation data for contours and heights, so the map works with no signal.`

| Row | Icon (Accent) | Kind | Action |
| --- | --- | --- | --- |
| `Find a park, reserve or town` | `Icons.Filled.Search` | link | push `PlaceSearch` |
| `Draw an area on the map` | `Lasso` (custom) | action (Accent text) | `model.startDrawingArea()` (closes the sheet) |
| `Use the blocks in a KML file` | `SquareDashed` | link | push `KMLPicker` |
| `Kruger National Park roads` | `Icons.Filled.DirectionsCar` | link | push `Preset(RoadPresets.kruger)` |
| `Where I've been` | `Icons.AutoMirrored.Filled.DirectionsWalk` | link | push `Track` |

**Section 2**, header `Offline areas`.
- **Rows:**
  - If no areas: `No offline areas yet.` in Body `LabelSecondary`.
  - Otherwise one `AreaRow` per area, each a link that pushes `AreaDetail(area.id)` and supports swipe-to-delete (`offline.delete(id)`).
- **Long press on a row** opens a `DropdownMenu` with one item, `Export` (`Icons.Outlined.IosShare`). It is enabled only when `offline.backupStatus == null` and calls `offline.export(listOf(area.id))`.
- **Footer:** `offline.backgroundStatus`, when not null (§5.2).

**`AreaRow`:**

- **Layout:** `Row(CenterVertically)` holding `Column(weight 1f, spacedBy 3)`, the status control, then the chevron.
- **Column:**
  1. `area.name`: Body Medium primary.
  2. Status: Caption, coloured `SystemGreen` when complete, `SystemOrange` when incomplete, else `LabelSecondary`:

     | `status` | Text |
     | --- | --- |
     | queued | `Waiting to start` |
     | downloading | `Downloading…` when `progress == null` or layer empty; else `"Downloading ${p.layer.lowercase()} · zoom ${p.zoom} of ${p.maxZoom} · ${count(levelDone)}/${count(levelTotal)} tiles"` |
     | paused | `Paused` |
     | waitingForNetwork | `Waiting for internet` if `area.allowCellular \|\| !online`, else `Waiting for Wi-Fi` |
     | complete | `Ready offline` |
     | incomplete | `"Stopped: ${area.problem} Tap ↻ to continue."` if `problem != null`, else `"Incomplete: ${count(area.failedTiles)} tiles failed. Tap ↻ to retry."` |

     `progress` is passed only for the area with `offline.activeID == area.id`.
  3. Info: Caption2 `LabelSecondary`, built as `"${Format.bytes(bytesOnDisk)} · up to zoom ${maxZoom}"`, then `" · along your track"` if `isTrack == true`, else `" · along roads"` if `alongRoads`; then `" · mobile data allowed"` if `allowCellular`.
- **Status control** (26 dp, a separate touch target inside the row):
  - downloading, queued or waitingForNetwork: `IconButton(Icons.Outlined.PauseCircle, Accent, contentDescription "Pause")`, calling `offline.pause(id)`.
  - paused or incomplete: `RetryCircle` (`Icons.Filled.Refresh` 17 dp inside a 24 dp circle with a 1.75 dp `Accent` border), `contentDescription "Resume"`, calling `offline.resume(id)`.
  - complete: `Icons.Filled.CheckCircle` in `SystemGreen` (decorative).

**Section 3**, header `Backup`.
- `Export all offline maps` (`Icons.Outlined.IosShare`), an action row. Disabled when there are no areas or `backupStatus != null`. Calls `offline.export(areas.map { it.id })`.
- `Restore from a backup` (`Icons.Outlined.SaveAlt`), an action row. Disabled when `backupStatus != null`. Launches `OpenMultipleDocuments` with `arrayOf("*/*")` (any app, any name, as PORTING asks) and calls `offline.restore(uris)`.
- If `backupStatus != null`, one more row, `Column(spacedBy 6)`:
  - `Row(spacedBy 8)`: a `CircularProgressIndicator(20 dp, 2 dp stroke)` only when `backupProgress == null`, then `Text(backupStatus, LabelSecondary)`.
  - If `backupProgress != null`: `LinearProgressIndicator(progress = { backupProgress }, color = Accent)`.
- Footer (Android wording, §5.1): `Saves each area as two MBTiles files, satellite and elevation, to Google Drive, your phone's storage or a USB drive, or send them to a computer. To restore, choose both files of each area. Long-press an area to export only that one.`

**Section 4** (no header).
- When `freeSpace != null`, one row: `Free space on this phone: ${Format.bytes(free)}`, Footnote `LabelSecondary` (iOS: "…this iPhone…").
- Footer: `"Map data: ${TileSources.attribution} · Roads and places: © OpenStreetMap contributors, found with Photon (komoot) and Nominatim · Airfields and navaids: OurAirports"`.
- `TileSources.attribution` is `Powered by Esri · Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community · Elevation: Mapzen Terrain Tiles on AWS (USGS, NOAA, Copernicus and others)`.

**Alert:** when `offline.backupMessage != null`, show `AlertDialog(title "Backup", text = backupMessage, confirm "OK")`. OK or dismiss calls `offline.clearBackupMessage()`. It belongs to this sheet, so a message that arrives while the sheet is closed shows the next time it opens, as on iOS.

### 3.3 Find a place (`PlaceSearchView`) and `PlaceAreaView`

**`PlaceSearchView`**, title `Find a place`, back `Offline maps`.

- **Search field section**, one row `Row(CenterVertically)`:
  - The text field: placeholder `Kruger, Sabi Sand, Skukuza, Hazyview …`, `testTag("placeSearchText")`.
    - `KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search)`.
    - `KeyboardActions(onSearch = { search() })`.
  - Then either a `CircularProgressIndicator(20 dp)` while searching, or `TextButton("Search")` (Body, Accent), disabled when `text.trim().isEmpty()`.
  - Footer: `Parks and reserves are saved within their boundary; towns, villages and camps with the area around them. Next you choose the detail, and whether to save the whole area or only along its roads.`
- If `message != null`: a card with `message` in Body `LabelSecondary`.
- If there are results: section header `Places`. Each place is a link to `PlaceArea(place)`: `Column(spacedBy 2)` with `place.name` in Body primary and `detail(place)` in Caption secondary (wraps).
- **`detail(p)`:** join with `" · "` the parts `p.kind`, `p.region` (if not empty), then:
  - With a boundary (`p.hasBoundary && p.extentKm2 != null`): `"up to ${count(km2.rounded().toInt())} km²"` when km2 ≥ 10, else `stringFormat("up to %.1f km²", km2)`.
  - Otherwise `"$r around it"`, where `r = "${(p.radiusMeters / 1000).toInt()} km"` for metric, else `Format.distance(p.radiusMeters, distanceUnit)`.
- **`search()`:**
  1. `q = text.trim()`. Return if `q` is empty or a search is running.
  2. `searching = true; message = null`.
  3. `near` = the map centre.
  4. `results = PlaceSearch.search(q, near)`.
  5. `message = if (results.isEmpty()) "No park, reserve, town or camp called “$q” was found. Try another spelling." else null`.
  6. On error: `message = error.message ?: "The search didn't work. Try again."`.
  7. `searching = false`.

  It also hides the keyboard and clears focus. Old results stay until new ones arrive.
- **Focus:** on first show, if there are no results, request focus once the push animation ends, so the keyboard opens.

**`PlaceAreaView(place)`:**

- **Loading** (`LaunchedEffect(Unit)`, only if `shape == null`): `shape = PlaceSearch.area(place)`. A `CancellationException` returns quietly; any other error sets `failed = error.message ?: "Couldn't get the boundary. Try again."`.
- **Display:**
  - Loaded: `NewAreaView(area = PendingArea(place.name, shape))`.
  - Failed: centred `Column(spacedBy 12, padding 16)` with `failed` in Body `LabelSecondary`, `TextAlign.Center`, and a `TextButton("Try again")` that loads again.
  - Loading: a centred spinner with `Getting the boundary of ${place.name} …` below it in `LabelSecondary`.
  - The title is empty until loaded (then `Download area`).

### 3.4 Choose KML (`KMLAreaPicker`)

- Title `Choose KML`, back `Offline maps`.
- If there are no files: `Import a KML file first (Blocks button on the map).` in `LabelSecondary`.
- Each file is a link to `NewArea(PendingArea(f.name, f.document.coverageShape(bufferMeters = 150.0)))`: `Column(spacedBy 2)` with `f.name` in Body, then `"${f.document.features.size} shapes · includes 150 m around them"` in Caption secondary.

### 3.5 Kruger National Park roads (`PresetAreaView`)

- If `RoadPresets.load(presetID)` succeeds: `NewAreaView(PendingArea(preset.name, CoverageShape(polygons = preset.boundary)), presetID = presetID)`.
- Otherwise: `The road list is missing from this build of SatMap.` in `LabelSecondary`.

### 3.6 Where I've been (`TrackAreaView`)

- **Worked out once** (`LaunchedEffect(Unit)`, guarded by `worked`), off the main thread, exactly as in Swift:
  - Inputs: `existing` = the area with `isTrack == true`, `previous = existing?.roads ?: []`, `completeTo = existing.maxZoom` if `existing.status == complete`.
  - `latest = Roads.trackLines(track)`, `lines = Roads.mergeTrack(previous, latest)`.
  - Result: `TrackDownload(lines, newLines = if (completeTo == null) lines else Roads.newParts(latest, previous), previousLines = previous, maxZoom = existing?.maxZoom, allowCellular = existing?.allowCellular, completeTo)`, or null when `lines` is empty.
- **Display:**
  - A result: `NewAreaView(PendingArea(existing?.name ?: "Where I've been", Roads.corridor(lines)), track = download)`.
  - Worked out but null: `No track recorded yet. Your dotted track is recorded while SatMap knows where you are.` in `LabelSecondary`, padding 16.
  - Otherwise: a centred spinner.

### 3.7 Download area (`NewAreaView`)

- **Title:** `Download area`.
- **Leading button:** `Cancel` only when `showsCancel` (the lasso sheet). It calls `model.discardPendingArea()` and closes.
- **Pushed** from KML, Kruger, track or place, it gets the back button instead.

**State:**
- `name = area.name`.
- `maxZoom`: SharedPreferences key **`offlineMaxZoom`**, default `TileSources.imageryMaxZoom` (22).
- `zoomChoices = 22 downTo 15`.
- `trackZoom = track?.maxZoom`.
- `picked = false`.
- `allowCellular = track?.allowCellular ?: false`.
- `alongRoads = presetID != null || track != null`.
- `roads = NotLoaded | Loading | Loaded(lines, km, counts) | Failed(msg)`.
- `counts: Map<Int, Int>?`, `freeSpace: Long?`.
- `imageryTileBytes = 17000`, `terrainTileBytes = 45000`, `typicalDepth = 19`.
- `fixedLines = presetID != null || track != null`.

**Sections, in order:**

1. **Header `Name`.** A text field row, placeholder `Area name`, `ImeAction.Done` (clears focus).

2. **Header `Detail`.** One row per z in `zoomChoices`:
   - `Modifier.selectable(selected = z == chosenZoom, onClick = { picked = true; if (track != null) trackZoom = z else maxZoom = z /* saved */ })`.
   - Content: `Column(spacedBy 2) { "Zoom $z" (Body primary); resolution(z) (Caption secondary) }`, `Spacer(weight)`, `sizeText(z)` (Body secondary `tnum`), then `Icons.Filled.Check` at 20 dp in `Accent`, alpha 1 when chosen else 0. The check is hidden from semantics.
   - Footer: `Each zoom level has twice the detail of the one before and takes about 4 times the space. Esri's imagery often stops at zoom 18–19 on farms and 20–21 in towns; the download stops where the imagery does, so deeper levels can add little. Sizes include elevation for contours and heights.`

3. **Roads section**, one of three:
   - **Track:** rows = `roadsStatus`. Footer: `Satellite imagery 15 m to each side of everywhere you've been (your dotted track), with elevation under it. Come back here after a trip to add the new parts: what is already saved is skipped.`
   - **Preset:** rows = `roadsStatus`. Footer: `"Satellite imagery 15 m to each side of every tar and gravel road in the park, including management tracks: ${preset.note}. Elevation for contours and heights is saved for the whole park."`
   - **Otherwise:** a toggle row `Only along roads` (`alongRoads`), followed by `roadsStatus` when on. Footer: `Saves satellite imagery only 15 m to each side of every road and track in the area, including gravel and farm roads, as mapped on OpenStreetMap. Elevation for contours and heights is still saved for the whole area.`
   - "15" is `Roads.corridorMeters.toInt()`.

4. **Mobile data.** A toggle row `Allow mobile data`. Footer (Android wording): `Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, with its progress in a notification. If Android stops it, it continues when you open SatMap again.`

5. **Header `Download size`.**
   - If the area has an area (m² > 0): row `Area` with `Format.hectares(m2)`.
   - If `chosenEstimate != null`:
     - Row `Up to zoom $chosenZoom` with `sizeText(chosenZoom)`.
     - Row `Tiles (most)` with `count(e.tiles)`.
     - If `freeSpace != null`, row `Free space` with `Format.bytes(free)`, followed by:
       - If `e.bytes > free − OfflineManager.minimumFreeSpace`: `This may not fit. The download stops when less than 500 MB is free; choose a lower zoom or a smaller area.` in Body `SystemOrange`.
       - Else if `chosenZoom < rememberedZoom`: `"Zoom $rememberedZoom needs ${sizeText(rememberedZoom)}, more than half the free space, so less detail is chosen. Tap a level above to choose another."` in Body `LabelSecondary`.
   - Else if `alongRoads` and roads failed: `Needs the roads first.` in `LabelSecondary`.
   - Otherwise: `Row(spacedBy 8) { spinner 20 dp; "Calculating…" (secondary) }`.

6. **Download.** One action row: `SatIcons.ArrowDownCircleFill` (26.5 dp, so its disc is 22 dp; Accent, the arrow cut out of it) plus `Download` in Headline Accent.
   - Disabled (tertiary) when `name.trim().isEmpty()`, or `alongRoads && roadLines.isNullOrEmpty()`, or `chosenEstimate == null`.
   - Action:
     - With a track: `model.startTrackDownload(trimmed, track.lines, chosenZoom, allowCellular)`.
     - Otherwise: `model.startDownload(PendingArea(trimmed, area.shape), roads = if (presetID == null && alongRoads) roadLines else null, preset = presetID, maxZoom = chosenZoom, allowCellular)`.
     - Then `model.sheet = null` and close this sheet. Back on the map, the banner shows progress.

**`roadsStatus`:**
- NotLoaded or Loading: `Row { spinner; "Finding roads…" (secondary) }`.
- Loaded:
  - Track: row `Your track` with `km(km)`; plus row `New since the last download` with `km(Roads.length(track.newLines) / 1000)` when `track.completeTo != null`.
  - Otherwise: row `Roads and tracks` with `km(km)` when km > 0, else `No roads or tracks are mapped in this area.` in `SystemOrange`.
- Failed: `msg` in `SystemOrange`, followed by a `TextButton("Try again")` row that calls `loadRoads()`.

**Formatting helpers:**
- `km(x)`: `stringFormat("%.1f km", x)` when x < 10, else `"${count(x.rounded().toInt())} km"`.
- `resolution(z)`: `Format.groundResolution(z, area.shape.bounds?.center?.lat ?: 0.0)`. It gives `"<n> cm per pixel"`, `"%.1f m per pixel"` or `"%.0f m per pixel"`.
- `sizeText(z)`:
  - Counts not known: `–` if `alongRoads` and roads failed, else `…`.
  - `e.tiles == 0`: `Nothing new`.
  - Otherwise `≈ <bytes>` for z ≤ 19, else `up to <bytes>`.

**Logic, ported 1:1:**
- `rememberedZoom = (trackZoom ?: maxZoom).coerceIn(15, 22)`.
- `deepestFitting`, which needs counts, imageryCounts and freeSpace, and `free > minimumFreeSpace`:
  - `room = (free − minimumFreeSpace) / 2`.
  - Result: the first z in 22 down to 15 with `estimate(z).bytes ≤ room`, else 15.
- `chosenZoom = if (!picked && track?.maxZoom == null && deepestFitting != null) min(rememberedZoom, deepestFitting) else rememberedZoom`.
- `imageryCounts` = `counts` when not along roads; otherwise the loaded roads' counts, or null.
- `estimate(z)`:
  - `imagery = Σ_{0..z} imageryCounts`
  - `terrain = Σ_{0..Terrarium.maxZoom} counts`
  - `bytes = imagery × 17000 + terrain × 45000`
  - `tiles = imagery + terrain`
- **Counting** (`LaunchedEffect(Unit)`, `Dispatchers.Default`):
  - Zooms `0..Terrarium.maxZoom` when `fixedLines`, else `0..22`.
  - `counts[z]` = `TileCoverage(shape, z).tileCount`, or `.count(notIn = TileCoverage(Roads.corridor(track.previousLines), z))` when `track?.completeTo != null`.
  - Then `freeSpace = AppPaths.freeDiskSpace()`.
- **`LaunchedEffect(alongRoads)`:** if `alongRoads && roads is NotLoaded`, call `loadRoads()`.
- **`loadRoads()`:**
  - `lines` comes from `track.lines`, or `preset.roads`, or `RoadPresets.covering(shape)?.let { Roads.clip(it.roads, shape) }` (computed off the main thread), or else `RoadFetcher.roads(shape)`.
  - Then, off the main thread: `whole = Roads.corridor(lines)`. For each z in `0..22`: `count(notIn = corridor(previousLines))` if `z ≤ track.completeTo`, else `tileCount`.
  - `km = Roads.length(lines) / 1000`.
  - Errors: cancellation sets `NotLoaded`; anything else `Failed(error.message ?: "Couldn't get the roads. Try again.")`.

### 3.8 Area detail (`AreaDetailView(areaID)`)

- **Title:** `area.name`; back `Offline maps`.
- **Deleted area:** if the area no longer exists, the body is `This area has been deleted.` in `LabelSecondary`.

**Sections:**

1. Rows:
   - Action row `Show on the map` (`Icons.Outlined.Map`): `area.shape.bounds?.let { model.map?.zoom(it) }; model.sheet = null`.
   - Row `Saved` with `Format.bytes(bytesOnDisk)`.
   - Row `Downloaded to` (complete) or `Downloading to`, with `"zoom ${maxZoom}"` plus `", along roads"` if `alongRoads`.
   - Row `Imagery found down to` with `"zoom $d"`, when `savedDepth != null`.
2. Toggle `Allow mobile data`, bound to `area.allowCellular` and calling `offline.setAllowCellular(areaID, it)`. Footer: `Used when you add a deeper level.` when complete, else `Without mobile data the download waits for Wi-Fi. Switch it on to carry on over mobile data; what is saved is kept.`
3. **Header `Detail`.** One row per z in 22 down to 15:
   - `selectable(selected = z == chosenZoom, enabled = !(z == maxZoom || (z < maxZoom && complete)), onClick = { chosenZoom = z })`.
   - `Zoom $z` is `LabelSecondary` when z ≤ maxZoom, else primary. The resolution line is Caption secondary.
   - The value (secondary `tnum`): `Downloaded` (complete) or `In this download` when z ≤ maxZoom, else `extraSize(z, maxZoom)`.
   - `Icons.Filled.Check`: `Accent` when chosen, else `LabelSecondary`. Alpha 1 when chosen, or when `z ≤ maxZoom && complete`, else 0.
   - Footer: `Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded.`, plus ` While it downloads, choose a lower level to stop there instead.` unless complete, plus `" Esri's imagery here stops at zoom $d, so deeper levels will add little or nothing."` when complete and `savedDepth < maxZoom`.
4. Action row: `SatIcons.ArrowDownCircleFill` (26.5 dp, so its disc is 22 dp, as in screenshot 18) plus `buttonTitle` in Headline. Disabled when `chosenZoom == null`. Calls `offline.changeMaxZoom(areaID, z)` then sets `chosenZoom = null`.

**Labels:**
- `buttonTitle`:
  - `Choose a deeper level` when nothing is chosen.
  - `"Stop at zoom $z"` when z < maxZoom.
  - Otherwise `"Download zoom ${maxZoom + 1}"`, plus `"–$z"` when `z > maxZoom + 1`.
- `extraSize(z)`:
  - Counts not known: `…`.
  - Otherwise `tiles = Σ_{maxZoom+1..z} counts`, `bytes = tiles × 17000`, giving `+ ≈ <bytes>` for z ≤ 19, else `+ up to <bytes>`.

**`LaunchedEffect("${maxZoom}-${status}")`:**
1. If complete and `chosenZoom < maxZoom`, set `chosenZoom = null`.
2. `savedDepth = offline.savedImageryDepth(areaID)`.
3. If `maxZoom < 22`: `counts = TileCoverage.tileCounts(area.roadLines?.let(Roads::corridor) ?: area.shape, maxZoom + 1..22)`, on `Dispatchers.Default`.

### 3.9 The lasso area sheet (`model.pendingArea`)

`ModalBottomSheet` → `SheetTopBar(title "Download area", leading "Cancel")` → `NewAreaView(model, pendingArea, showsCancel = true)`. Dismissal is in §3.0. The default name is `"Area ${offline.areas.size + 1}"` (from `confirmDrawnArea`).

### 3.10 Go to coordinates (`CoordinateEntryView`, `model.sheet == coordinates`)

- **Top bar:** title `Go to coordinates`, leading `Cancel` (dismiss).
- **State:** `text`. `me` = `recorder.location` as a `GeoPoint`. `result = remember(text, me) { CoordinateParser.parse(text, near = me) }`.

**Sections:**

1. **The text field and Paste button.**
   - Text field row: placeholder `Paste or type coordinates`, multi-line (`minLines = 1, maxLines = 4`), `KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Text)`, Enter inserts a newline. `testTag("coordinateText")`.
   - Paste row (padding h16 v5, so 44 dp tall): a `Row` clipped to `RoundedCornerShape(8.dp)` on `PasteFill` (alpha 0.4 while disabled), `heightIn(min = 34.dp)`, padding h12 v6, `Arrangement.spacedBy(3.dp)`, with `Icons.Outlined.ContentPaste` (18 dp) and `Paste` (Body), both White (alpha 0.5 while disabled). The 3 dp spacing gives iOS's 7.4 pt ink-to-ink gap, as the glyph sits 2.25 dp inside its 18 dp box.
     - Enabled when `clipboard.hasPrimaryClip() && primaryClipDescription.hasMimeType("text/*")`. Reading the description doesn't trigger Android's paste toast.
     - On tap: `text = primaryClip.getItemAt(0).coerceToText(ctx).toString()`.
   - Footer: `Any format: -25.0969, 31.4371 · 25.0969 S 31.4371 E · 25° 05.814' S 31° 26.227' E · 25°05'48.8"S 31°26'13.6"E · 2505.814,S,03126.227,E (GPS) · map and geo: links · UTM 36J 342401 7223411 · MGRS 36J UT 42401 23411 · plus code 5G6HWC3P+6R`.
2. **The reading.**
   - With a result: rows `Position` (`Format.coordinate(r.point)`), `Read as` (`r.format`), and `From you` (`Format.distance(Geo.distance(me, r.point), distanceUnit)`, only when `me != null`). Values are secondary, end-aligned and wrap.
   - Else if `text.trim()` is empty (whitespace and newlines): `The position shows here as you type or paste.` in `LabelSecondary`.
   - Otherwise: `Not a position yet.` in `SystemOrange`.
3. **Action row:** `Icons.Outlined.Straighten` plus `Show and measure from here` in Headline. Disabled when `result == null`. Calls `model.measure(from = r.point)` (which also sets `sheet = null`), then closes.

- **Focus:** once the sheet reaches `SheetValue.Expanded` (`snapshotFlow { sheetState.currentValue }.first { it == Expanded }`), call `focusRequester.requestFocus()` and `keyboardController?.show()`.

---

## 4. Accessibility and test identifiers

Compose mapping:

| iOS | Compose |
| --- | --- |
| `accessibilityIdentifier` | `Modifier.testTag` (UiAutomator also finds it as a resource id, because every root sets `semantics { testTagsAsResourceId = true }`: the map screen root, each sheet's content root and each dialog's content) |
| `accessibilityLabel` | `contentDescription` |
| `accessibilityValue` | `stateDescription` |
| `accessibilityHint` | `onClickLabel` / `onClick(label = …)` |
| `.isSelected` | `selectable(selected)` |
| `accessibilityHidden` | `clearAndSetSemantics {}` |
| `.accessibilityElement(children: .combine)` | `semantics(mergeDescendants = true)` |
| `.accessibilityElement(children: .ignore)` + label | `clearAndSetSemantics { … }` with the tag set inside it |

**Identifiers from iOS (same strings):**

| Test tag | On | Asserted by the UI test |
| --- | --- | --- |
| `myCoordinates` | Copy button of your coordinates | exists |
| `myHeight` | Your `HeightBar` | value contains `above sea level` |
| `tappedCoordinates` | Copy button of the tapped spot | description becomes `Copied` after a tap; changes after the pin is dragged |
| `tappedHeight` | The tapped spot's `HeightBar` | ` ft ` after one tap, ` m ` after another |
| `tappedName` | KML point's name capsule | appears after tapping "Launch 1" |
| `measuredDistance` | Distance text in **path** mode only | text ends in ` mi`/` ft`, then ` NM`, then ` km`/` m` after taps |
| `contourKey` | Contour legend | exists with contours on |
| `colourKey` | Colour legend | exists with colours on |
| `droneCard` | Drone card | exists in drone mode |
| `useFileColours` | Blocks colours checkbox row | exists |
| `coordinateText` | Go-to text field | typed into |
| `placeSearchText` | Place search field | typed into, plus the search action |

**Labels used to find controls** (`contentDescription` for icon buttons; visible text for text buttons and rows):
- Map buttons: `Contour lines` (value = mode title), `Blocks`, `Offline maps`, `Go to coordinates`, `Measure`, `My location`.
- Measuring: `Stop measuring`, `Undo`, `Close shape`.
- Sheet bars and rows: `Done`, `Cancel`, `Draw an area on the map`, `Use the blocks in a KML file`, `Kruger National Park roads`, `Find a park, reserve or town`, `Where I've been`, `Download`, `Download zoom 21`, `Try again`, `Show and measure from here`, `Export all offline maps`, `Restore from a backup`, `Search`.
- Toggles: `Only along roads`, `Allow mobile data`.
- Texts the test waits for: `Ready offline`, `Roads and tracks`, `Your track`, `Imagery found down to`, `Shape A–D · 4 corners`, and texts starting with `From A to`, `Shape A`, `Move the crosshair onto the spot`, or containing `than you`, `Height:`, `Waiting for GPS`.
- Other icon controls: `Hide coordinates`, `Import KML`, `Show <name> on the map`, `Height`, `Scale`.

**Clashes:** `Contour lines` is both the terrain button and the contour key, and `Blocks` is both a button and a sheet title. Android tests should therefore use the extra tags below, or `hasContentDescription(...) and hasClickAction()`.

**Android-only extra tags** (no iOS equivalent; for the Android tests):
- Map: `compassButton`, `terrainButton`, `blocksButton`, `offlineButton`, `goToButton`, `measureButton`, `myLocationButton`, `stopMeasuring`, `hideCoordinates`.
- Sheet chrome: `screenTitle` (the sheet bar title, which replaces `navigationBars["…"]`), `back` (back button, `contentDescription "Back"`).
- `importKml`.

**Hints and actions:**
- Distances and the scale label: `Tap to show distances in other units`.
- Heights: `Shows heights in feet` / `Shows heights in metres`.
- Long-press labels: `Show or hide files` (Blocks), `Clear recorded track` (My location).
- Swipe rows: custom action `Delete`.

**Decorative icons** have `contentDescription = null`: the checkmarks, chevrons, the complete check and the panel icons.

---

## 5. Strings

Every string is given in place in §2–3. This section lists the reworded ones and the strings other subsystems produce that these screens display.

### 5.1 Reworded for Android (iOS original → Android)

| Where | iOS | Android |
| --- | --- | --- |
| Location hint | `Location is off for SatMap. Turn it on in Settings › Privacy › Location Services.` | `Location is off for SatMap. Turn it on in Settings › Location and in Settings › Apps › SatMap › Permissions.` |
| Precise hint | `Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location.` | `Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › SatMap › Permissions › Location (Use precise location).` |
| Offline: free space | `Free space on this iPhone: %s` | `Free space on this phone: %s` |
| Offline: backup footer | `Saves each area as two MBTiles files, satellite and elevation, to Files, iCloud Drive or a USB drive, or AirDrop them to a computer. To restore, choose both files of each area. Long-press an area to export only that one.` | `Saves each area as two MBTiles files, satellite and elevation, to Google Drive, your phone's storage or a USB drive, or send them to a computer. To restore, choose both files of each area. Long-press an area to export only that one.` |
| Download area: mobile data footer | `Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, with its progress on the Lock Screen (iOS 26 or later; earlier iOS allows about half a minute). If iOS stops it, it continues when you open SatMap again.` | `Without mobile data the download waits for Wi-Fi. It keeps going when you leave SatMap, with its progress in a notification. If Android stops it, it continues when you open SatMap again.` |
| New labels (iOS uses system or automatic ones) | (SF auto labels) | `Pause` (banner and row), `Resume` (row ↻), `Compass`, `Point north up`, `Back`, `Show or hide files`, `Delete` (swipe action) |

### 5.2 Shown here, produced elsewhere (port them with their owners, exactly)

- **`OfflineManager`:**
  - `backupStatus`: `Preparing export…`, `Restoring…`, `Copying %s of %s…`.
  - `backupMessage`: `Nothing has been downloaded yet to export.`, `Couldn't export.`, `Restored <names joined ", ">.`, `Tap ↻ on an area marked Paused to download what its backup didn't include.`, `Couldn't copy <file> (is there enough free space?).`, `<file> isn't a SatMap backup.`, `<name> is already in SatMap.`, `Nothing to restore.`, `Couldn't prepare <name> for export. Try again in a moment.`, all joined with `\n`.
  - `backgroundStatus` (the Android counterpart of `BackgroundDownload.status`), suggested: `Downloads keep going in the background, with their progress in a notification.`; with notifications denied, `Downloads keep going in the background. Allow notifications for SatMap to see their progress.`
- **`PlaceSearch` errors:** `No internet connection. Places are looked up online; try again when connected.`, `The place search didn't answer. Try again in a moment.`
- **`RoadFetcher` errors:** `No internet connection. Roads are looked up online; try again when connected.`, `The OpenStreetMap road server is busy or unreachable. Try again in a minute.`
- **`KMLLibrary.importError`:** `Couldn't read <file>: <reason>`, `<file>: <error>`.
- **`TerrainMode.title`:** the 5 titles in §2.4.5. **`DroneReport`** lines come from `Aviation.report`.
- **`DistanceUnit.format` / `ScaleBar.label`** (core), and **`Format.*`** (`AppPaths.kt`).

---

## 6. Compose design

### 6.1 Composable tree and signatures

```kotlin
// MainActivity.onCreate: enableEdgeToEdge(dark, dark); setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder) } }

@Composable fun MapScreen(model: AppModel, offline: OfflineManager, recorder: LocationRecorder)
  MapViewRepresentable(model)                       // map subsystem (AndroidView of MapView)
  Crosshair()
  DrawingBar(model) · DownloadBanner(area, progress, onPause) · AimPanel(model, aim, recorder) · MeasurePanel(model, recorder)
  CoordinateRow { CoordinateBar(icon, text, id, iconSlot, onClose); HeightBar(elevation, gpsAltitude, unit, id, onTap) }
  DroneCard(report) · Hint(text, onClose)
  ContourKeyView(legend) · ScaleBarView(scale, onTap) · ColourKeyView(legend)
  Controls(model, offline) { CompassButton(map); MapButton(...) ×6 + DropdownMenus + TerrainToast }
  SheetHost(model.sheet) { when (it) { blocks -> BlocksSheet(model, model.kml); offline -> OfflineSheet(model, offline, model.kml); coordinates -> CoordinateEntryView(model) } }
  PendingAreaHost(model.pendingArea) { NewAreaView(model, it, showsCancel = true) }
  ClearTrackDialog

@Composable fun OfflineSheet(model, offline, library, close: () -> Unit)         // owns the back stack
  sealed interface OfflineRoute { Root; PlaceSearch(state); PlaceArea(place, state); KMLPicker; NewArea(area, state); Preset(id, state); Track(state); AreaDetail(id, state) }
@Composable fun PlaceSearchView(model, state, push)        @Composable fun PlaceAreaView(model, place, state)
@Composable fun PresetAreaView(model, presetID, state)     @Composable fun TrackAreaView(model, state)
@Composable fun KMLAreaPicker(model, library, push)        @Composable fun AreaRow(area, progress, canDownloadNow, onPause, onResume)
@Composable fun NewAreaView(model, area, showsCancel = false, presetID: String? = null, track: TrackDownload? = null, state = remember { NewAreaState(...) }, close)
@Composable fun AreaDetailView(model, offline, areaID, close)
@Composable fun BlocksSheet(model, library, close)
@Composable fun CoordinateEntryView(model, sheetState, close)
```

- Each screen inside a sheet supplies its title, leading button and trailing button to `SheetTopBar` through a small `ScreenChrome` data holder, so the bar can show the previous route's title as the back label.
- `NewAreaView`'s body is a `LazyListScope` extension, so `PlaceAreaView`, `PresetAreaView` and `TrackAreaView` can wrap it as SwiftUI does.

### 6.2 SwiftUI → Compose / Material 3

| SwiftUI | Compose |
| --- | --- |
| `ZStack` / `VStack` / `HStack` / `Spacer` | `Box` / `Column(spacedBy)` / `Row(spacedBy)` / `Spacer(Modifier.weight(1f))` |
| `.sheet(item:)` | `ModalBottomSheet` (`skipPartiallyExpanded`, no drag handle), §3.0 |
| `NavigationStack` / `NavigationLink` / back button | In-sheet route stack + `AnimatedContent` + `BackHandler`; link row with chevron; `ArrowBackIos` + previous title |
| `.navigationTitle` (inline) + `.toolbar` | `CenterAlignedTopAppBar(expandedHeight = 56.dp, windowInsets = WindowInsets(0))` |
| `List` / `Form` (inset grouped), `Section` header/footer | `LazyColumn` + `FormSection` / `FormRow` (§1.5) |
| `Toggle` | `IosSwitch` (drawn: 51 × 31 track, 27 dp knob) in a `toggleable` row (§1.5) |
| `Picker(.segmented)` | `SegmentedControl` of our own (§2.3.1) |
| `TextField` (in a form) | `BasicTextField` + placeholder decoration |
| `PasteButton` | a pressable `Row` on PasteFill (§3.10) reading `ClipboardManager` |
| `ProgressView()` / `ProgressView(value:)` / `ProgressView("…")` | `CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)` / `LinearProgressIndicator(progress = {…})` / spinner over a secondary label |
| `Button` `.bordered`, `.tint(.white)` | `FilledTonalButton` (White@0.15, White) |
| `Label(text, systemImage:)` | `Row { Icon; Spacer(…); Text }` |
| `.contextMenu` | `combinedClickable(onLongClick)` + `DropdownMenu` / `DropdownMenuItem` |
| `.confirmationDialog` / `.alert` | `AlertDialog` (`CellBackground`; destructive in `SystemRed`) |
| `.onDelete` | `SwipeToDismissBox(enableDismissFromStartToEnd = false, onDismiss = …)` + `CustomAccessibilityAction("Delete")` |
| `.fileImporter(allowsMultipleSelection:)` | `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())` with `arrayOf("*/*")` |
| `UIActivityViewController` | `Intent.createChooser(ACTION_SEND_MULTIPLE)` via `StartActivityForResult` |
| `UIPasteboard` / `UINotificationFeedbackGenerator(.success)` | `ClipboardManager.setPrimaryClip` / `HapticFeedbackType.Confirm` |
| `MKCompassButton` | Custom `CompassButton` (Canvas) + `MapController.resetNorth()` (north up about the safe area's middle, §2.4.6) |
| `ViewThatFits` | Custom `Layout` (`CoordinateRow`) |
| `.minimumScaleFactor` | `Text(autoSize = TextAutoSize.StepBased(min, max, 0.25.sp))` |
| `.monospacedDigit()` / text `.shadow` | `fontFeatureSettings = "tnum"` / `TextStyle(shadow = Shadow(…))` |
| `@State` / `@FocusState` / `@AppStorage` | `remember { mutableStateOf }` (or route state holders) / `FocusRequester` / `SharedPreferences` (same key) |
| `.task` / `.task(id:)` / `.onAppear` | `LaunchedEffect(Unit)` / `LaunchedEffect(id)` / `LaunchedEffect(Unit)` |
| `DispatchQueue.main.asyncAfter` (toast 1.8 s, copied 2.5 s) | `LaunchedEffect(key) { delay(…) }` |
| `Task.detached { … }.value` | `withContext(Dispatchers.Default)` (`Dispatchers.IO` for disk) |

### 6.3 SF Symbols → icons

All are from `material-icons-extended` 1.7.8; every name below exists in the cached AAR. Where no Material icon matches, `Icons.kt` defines an `ImageVector` (24×24 viewport).

| SF Symbol | Android | Where |
| --- | --- | --- |
| `location.fill` / `location` | `Icons.Filled.NearMe` / `Icons.Outlined.NearMe` | My location button, my coordinates (outlined while GPS sleeps), drone "You" |
| `mountain.2.fill` / `mountain.2` | `Icons.Filled.Landscape` / `Icons.Outlined.Landscape` | Terrain button, height pills |
| `paintpalette.fill` | `Icons.Filled.Palette` | Terrain: colours |
| `airplane` | `Icons.Filled.Flight` + `Modifier.rotate(90f)` | Terrain: drone |
| `square.dashed` | **custom `SquareDashed`**, stroke 2, round caps: `M4,8V6a2,2 0 0 1 2,-2h2 M16,4h2a2,2 0 0 1 2,2v2 M20,16v2a2,2 0 0 1 -2,2h-2 M8,20H6a2,2 0 0 1 -2,-2v-2 M11,4h2 M20,11v2 M11,20h2 M4,11v2` | Blocks button, "Use the blocks in a KML file" |
| `arrow.down.circle` / `.fill` | `Icons.Outlined.ArrowCircleDown` / **custom `ArrowDownCircleFill`** (Material's `Filled.ArrowCircleDown` is the same ring as the outlined one), fill, `PathFillType.EvenOdd`, the disc and the arrow as one outline that doesn't cross itself (SF's stem and chevron, 1.8 wide, round ends): `M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z M11.1,8.15A0.9,0.9 0 0 1 12.9,8.15L12.9,14.57L14.9,12.81A0.9,0.9 0 0 1 16.08,14.17L12.59,17.24A0.9,0.9 0 0 1 11.41,17.24L7.92,14.17A0.9,0.9 0 0 1 9.1,12.81L11.1,14.57Z` | Offline button; Download buttons |
| `location.magnifyingglass` | **custom `LocationMagnifyingGlass`**: lens stroke 2 `M10.5,4a6.5,6.5 0 1 0 0.001,0z`, handle stroke 2.6 round `M15.3,15.3L20.5,20.5`, arrow fill `M13.4,7.6L7.4,10.1L10.2,10.8L10.9,13.6Z` | Go to coordinates |
| `ruler` | `Icons.Outlined.Straighten` | Measure (off), "Show and measure from here" |
| `scope` | `Icons.Filled.GpsFixed` | Measure (aiming / placed / path) |
| `xmark` | `Icons.Filled.Close` | Measure (closed), stop measuring, hide coordinates |
| `trash` | `Icons.Outlined.Delete` | "Clear recorded track" |
| `checkmark.circle.fill` / `circle` | `Icons.Filled.CheckCircle` / `Icons.Outlined.Circle` | Blocks menu; area complete |
| `checkmark.square.fill` / `square` | `Icons.Filled.CheckBox` / `Icons.Outlined.CheckBoxOutlineBlank` | Colours from the files |
| `arrow.uturn.backward` | `Icons.AutoMirrored.Filled.Undo` | Undo |
| `pentagon` | `Icons.Outlined.Pentagon` | Close shape |
| `mappin` | **custom `MapPin`**, fill: `M12,2.5a4.25,4.25 0 1 1 0,8.5a4.25,4.25 0 1 1 0,-8.5z M11.15,10.5h1.7v9.6a0.85,0.85 0 0 1 -1.7,0z` | Tapped coordinates, drone "Pin" |
| `mappin.circle.fill` | **custom `MapPinCircleFill`**, fill, `PathFillType.EvenOdd`: `M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z M12,6a2.9,2.9 0 1 1 0,5.8a2.9,2.9 0 1 1 0,-5.8z M11.35,11.8h1.3v5.7a0.65,0.65 0 0 1 -1.3,0z` | Tapped KML point |
| `doc.on.doc` | `Icons.Outlined.ContentCopy` | Coordinate pills |
| `pause.fill` | `Icons.Filled.Pause` | Download banner |
| `magnifyingglass` | `Icons.Filled.Search` | Find a park… |
| `lasso` | **custom `Lasso`**, stroke 1.8, round: `M12,4.5c4.7,0 8.5,2.4 8.5,5.25S16.7,15 12,15S3.5,12.6 3.5,9.75S7.3,4.5 12,4.5z M7.6,14.1c-1.3,0.9 -1.6,2.4 -0.6,3.3c1.1,1 2.9,0.5 3.1,-0.9c0.2,-1.3 -1.2,-1.9 -2,-1.1 M8.2,17.9c0.3,1.3 -0.2,2.5 -1.2,3.1` | Draw an area |
| `car` | `Icons.Filled.DirectionsCar` | Kruger roads |
| `figure.walk` | `Icons.AutoMirrored.Filled.DirectionsWalk` | Where I've been |
| `square.and.arrow.up` | `Icons.Outlined.IosShare` | Export, Export all |
| `square.and.arrow.down` | `Icons.Outlined.SaveAlt` | Restore |
| `pause.circle` | `Icons.Outlined.PauseCircle` | Area row |
| `arrow.clockwise.circle` | **composite `RetryCircle`**: `Icons.Filled.Refresh` 17 dp in a 24 dp circle with a 1.75 dp border | Area row |
| `map` | `Icons.Outlined.Map` | Show on the map |
| `checkmark` | `Icons.Filled.Check` | Zoom rows |
| `plus` | `Icons.Filled.Add` | Import KML |
| (paste button) | `Icons.Outlined.ContentPaste` | Paste |
| (disclosure chevron) / (back) | `Icons.AutoMirrored.Filled.KeyboardArrowRight` / `Icons.AutoMirrored.Filled.ArrowBackIos` | Link rows / sheet back button |

### 6.4 Why bottom sheets with an in-sheet stack (not a NavHost, not full-screen dialogs)

- The screenshots show iOS page sheets: rounded top corners, the map dimmed above, drag down to close. `ModalBottomSheet` (`skipPartiallyExpanded`) gives the same.
- An inner stack of route objects keeps each pushed screen's state while it is under another, as `NavigationStack` does.
- It also avoids a new dependency and serialising `FoundPlace`/`PendingArea`.

### 6.5 State

- **`AppModel` flows the screens collect** (`collectAsStateWithLifecycle()`):
  - `sheet`, `pendingArea`, `draftArea`, `isDrawingArea`, `lassoActive`, `measure`, `markerElevation`, `lastElevation`, `userElevation`, `selectedPoint`, `selectedElevation`, `selectedName`, `terrainMode`, `distanceUnit`, `heightUnit`, `droneReport`, `following`.
  - `aim.point` / `aim.elevation` / `aim.elevationPoint`, `scale.label` / `scale.width`, `legend.key`.
  - `offline`: `areas`, `progress`, `activeID`, `backupStatus`, `backupProgress`, `backupMessage`, `backgroundStatus`, `completedRevision`.
  - `recorder`: `location`, `power`, the permission state (denied / restricted vs. coarse only), `reducedAccuracy`.
  - `kml`: `files`, `useFileColours`, `importError`.
  - `Connectivity.state`.
- **Writes** go through the Swift-named setters and functions:
  - `model.selectedPoint = null` runs the Swift `didSet` logic.
  - `kml.useFileColours = …`, `model.lassoActive = …`, `model.draftArea = null`.
  - `nextDistanceUnit()`, `nextHeightUnit()`, `measureButtonTapped()`, `clearMeasure()`, `undoMeasurePoint()`, `closeMeasureShape()`, `centerOnUser()`, `clearTrack()`, `startDrawingArea()`, `cancelDrawing()`, `confirmDrawnArea()`, `discardPendingArea()`, `startDownload(...)`, `startTrackDownload(...)`, `measure(from = …)`.
- **Screen-local state lives in route holders** (plain classes with `mutableStateOf` fields) created at push:
  - `PlaceSearchState(text, results, searching, message)`
  - `PlaceAreaState(shape, failed)`
  - `TrackState(download, worked)`
  - `NewAreaState(name, trackZoom, picked, allowCellular, alongRoads, roads, counts, freeSpace)`
  - `AreaDetailState(chosenZoom, counts, savedDepth)`
- Map-screen-local state (`confirmClearTrack`, `terrainToast`, menu-open flags, `copied`) uses `remember`.

### 6.6 Insets and keyboard

- **Map overlay:** `WindowInsets.systemBars.union(WindowInsets.displayCutout)`. Never the IME, so the overlay doesn't jump while a sheet's keyboard is open.
- **Sheets:**
  - The outer top margin comes from `windowInsetsPadding(WindowInsets.statusBars)` + 10 dp on the `ModalBottomSheet` modifier.
  - Inside: `LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(top = 16.dp, bottom = navigationBars + 15.dp))` (§1.5). The list shrinks above the keyboard, and a focused field scrolls into view (`BringIntoView` is built into `BasicTextField` in lazy lists).
- **Focus on open:**
  - Go to coordinates: always, once the sheet is expanded.
  - Find a place: when it has no results, once the push animation ends.
  - Use `FocusRequester` + `LocalSoftwareKeyboardController.show()`.
- **IME actions:** Search runs the search and then hides the keyboard and clears focus; Name uses Done (clears focus); the coordinate field is multi-line (Enter makes a new line).
- **Back:** while the keyboard is open, system back closes it first (default); then it pops the sheet stack; then it closes the sheet.

### 6.7 MapLibre API the screens rely on

Checked with `javap` on `android-sdk-13.7.1.aar`.

- **Map options** (`org.maplibre.android.maps.MapLibreMapOptions`): `createFromAttributes(Context)`, `.compassEnabled(false)`, `.logoEnabled(false)`, `.attributionEnabled(false)` (the credits are in the Offline maps footer, as on iOS), `.tiltGesturesEnabled(false)` (MapKit `isPitchEnabled = false`), `.rotateGesturesEnabled(true)`.
  - The same through `org.maplibre.android.maps.UiSettings`: `setCompassEnabled(false)`, `setLogoEnabled(false)`, `setAttributionEnabled(false)`, `setTiltGesturesEnabled(false)`.
- **Compass** (`MapLibreMap`): `getCameraPosition().bearing` (`org.maplibre.android.camera.CameraPosition.bearing`), `addOnCameraMoveListener(MapLibreMap.OnCameraMoveListener)`, and `easeCamera(CameraUpdate, int)` for the compass tap: bearing 0 with the target moved so the safe area's middle stays put, over 500 ms. `resetNorth()` (`Transform.resetNorth` → the native `easeTo` of the bearing alone, 500 ms) keeps the target, the view's middle, so it would move you while following; it is the fallback before the map has started.
- **Scale bar and crosshair** (`org.maplibre.android.maps.Projection`, via `MapLibreMap.getProjection()`):
  - `fromScreenLocation(PointF)` at the view centre ± 50 dp gives metres per dp, as Swift does. `getMetersPerPixelAtLatitude(double)` returns metres per *logical* pixel (dp at the default `MapView.getPixelRatio()`); it is an alternative.
  - `toScreenLocation(LatLng)` gives `pointsApart`.
  - The crosshair is the view centre, so never call `MapLibreMap.setPadding(...)`: camera padding is camera state (`CameraPosition.padding`, copied by `CameraPosition.Builder(camera)`), and `cameraPosition.target` would become the padded middle.
- **Centring and zooming:** as MapKit, the camera centres on the safe area's middle (14 dp below the view's on the Pixel 7, as 14 pt on the iPhone 16 Pro), so you are below the crosshair while following. The target is moved instead of padding the camera (interaction spec §2.7).
  - `MapController.zoom(to: GeoBounds)` uses `getCameraForLatLngBounds(LatLngBounds, int[] padding)` with the safe area's insets (MapLibre returns that padding in the `CameraPosition`; `MapController.centred` sets it back to 0) and `easeCamera(newCameraPosition)`.
  - `center(on:)` uses `CameraUpdateFactory.newLatLng` with the moved target.
- **Touches:** the Compose overlay only takes touches where it has pointer input. Everything else reaches the `MapView` (`AndroidView`) underneath, and from there `MapLibreMap.addOnMapClickListener` / `addOnMapLongClickListener` (map subsystem).

---

## 7. Numbers and timings recap

| What | Value |
| --- | --- |
| Overlay side margin / bottom margin / column spacing | 12 / 8 / 8 dp |
| Map button / spacing / compass slot | 50 dp circle / 12 dp / 44 dp (always reserved) |
| Controls lift above the bottom | 124 dp |
| Scale bar max width = colour key width | 110 dp |
| Crosshair | ring 36 dp (2 dp stroke), arms 56 × 2 dp, red dot 6 dp |
| Terrain toast | 1.8 s, max width 230 dp, right edge 10 dp left of the button, fades out |
| "Copied" | 2.5 s |
| Panels | aim/measure Black 0.7 r12 pad 14/10; banner 0.7 r12 pad 12/8; drawing bar 0.75 r12 pad 12; drone card 0.72 r12 pad 12/8 + 0.5 dp White@0.18 border; hint 0.65 r10 pad 12/8; pills 0.65 capsule pad 12/8 (height pill 10/8, name 12/5) |
| Map button background | Black@0.6 (`SystemYellow` when active), 1 dp White@0.3 border |
| Zoom levels offered | 22 → 15; remembered under `offlineMaxZoom` (default 22) |
| Size estimates | 17 000 B per imagery tile, 45 000 B per elevation tile; "≈" up to z19, "up to" deeper |
| Free-space limit | `OfflineManager.minimumFreeSpace` = 500 MiB; auto-chosen level ≤ half of what's above it |
| KML area buffer / road corridor | 150 m / `Roads.corridorMeters` (15 m) |
