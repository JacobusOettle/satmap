import MapKit
import SwiftUI
import UIKit

struct MapScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var offline: OfflineManager
    @ObservedObject var recorder: LocationRecorder
    @State private var confirmClearTrack = false
    /// What the terrain button now shows, said for a moment beside it after a tap.
    @State private var terrainToast: String?
    @State private var toastEnd: DispatchWorkItem?

    var body: some View {
        ZStack {
            MapViewRepresentable(model: model)
                .ignoresSafeArea()

            if showsCrosshair {
                Crosshair()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }

            VStack(spacing: 8) {
                if model.isDrawingArea {
                    DrawingBar(model: model)
                }
                if let id = offline.activeID, let area = offline.areas.first(where: { $0.id == id }) {
                    DownloadBanner(area: area, progress: offline.progress) {
                        offline.pause(id)
                    }
                    .onTapGesture { model.sheet = .offline }
                }
                switch model.measure {
                case .aiming:
                    AimPanel(model: model, aim: model.aim, recorder: recorder)
                case .placed, .path, .closed:
                    MeasurePanel(model: model, recorder: recorder)
                case .off:
                    EmptyView()
                }
                // Your own coordinates and height at the top; a tapped spot's at the bottom, within reach to copy.
                if let loc = recorder.location, !model.isDrawingArea {
                    CoordinateRow {
                        CoordinateBar(icon: recorder.power == .sleeping ? "location" : "location.fill",
                                      text: Format.coordinate(GeoPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)),
                                      id: "myCoordinates")
                        HeightBar(elevation: model.userElevation, gpsAltitude: Self.gpsAltitude(loc), unit: model.heightUnit,
                                  id: "myHeight", onTap: model.nextHeightUnit)
                    }
                    .frame(maxWidth: .infinity)
                }
                // Drone mode: where you (and the tapped spot) are from the nearest airfield, as a pilot says it.
                if model.terrainMode.showsAviation, !model.isDrawingArea,
                   model.droneReport.you != nil || model.droneReport.pin != nil {
                    DroneCard(report: model.droneReport)
                }
                if recorder.authorization == .denied || recorder.authorization == .restricted {
                    Hint(text: "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services.")
                } else if recorder.reducedAccuracy {
                    Hint(text: "Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location.")
                }
                Spacer()
                // Map credits are in the Offline maps sheet.
                if !model.isDrawingArea {
                    HStack(alignment: .bottom) {
                        // The contour interval above the scale bar, the elevation colours' heights under it.
                        VStack(alignment: .leading, spacing: 8) {
                            ContourKeyView(legend: model.legend)
                            ScaleBarView(scale: model.scale, onTap: model.nextDistanceUnit)
                            ColourKeyView(legend: model.legend)
                        }
                        Spacer()
                        controls
                    }
                    // Two buttons higher than at the very bottom (My location where Offline maps was), leaving the
                    // bottom of the screen to a tapped spot's coordinates.
                    .padding(.bottom, Self.controlsLift)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .overlay(alignment: .bottom) {
                if let p = model.selectedPoint, !model.isDrawingArea {
                    VStack(spacing: 6) {
                        // A KML point's name over its coordinates.
                        if let name = model.selectedName {
                            Text(name)
                                .font(.subheadline.weight(.bold))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 5)
                                .background(Color.black.opacity(0.65))
                                .clipShape(Capsule())
                                .accessibilityIdentifier("tappedName")
                        }
                        CoordinateRow {
                            CoordinateBar(icon: model.selectedName == nil ? "mappin" : "mappin.circle.fill", text: Format.coordinate(p),
                                          id: "tappedCoordinates", onClose: { model.selectedPoint = nil })
                            HeightBar(elevation: model.selectedElevation, unit: model.heightUnit, id: "tappedHeight",
                                      onTap: model.nextHeightUnit)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
                }
            }
        }
        .sheet(item: $model.sheet) { sheet in
            switch sheet {
            case .blocks:
                BlocksSheet(model: model, library: model.kml)
            case .offline:
                OfflineSheet(model: model, offline: offline, library: model.kml)
            case .coordinates:
                CoordinateEntryView(model: model)
            }
        }
        .background(
            Color.clear.sheet(item: $model.pendingArea, onDismiss: { model.draftArea = nil }) { area in
                NavigationStack {
                    NewAreaView(model: model, area: area, showsCancel: true)
                }
            }
        )
        .confirmationDialog("Clear the recorded track?", isPresented: $confirmClearTrack, titleVisibility: .visible) {
            Button("Clear track", role: .destructive) { model.clearTrack() }
        }
    }

    /// Two side buttons and the gap between them.
    private static let controlsLift: CGFloat = 2 * (50 + 12)

    private var controls: some View {
        VStack(spacing: 12) {
            if let map = model.map?.mapView {
                CompassButton(mapView: map)
                    .frame(width: 44, height: 44)
            }
            MapButton(systemImage: terrainIcon, label: "Contour lines", dimmed: model.terrainMode == .off,
                      heightColours: model.terrainMode.showsColours) { nextTerrainMode() }
                .accessibilityValue(Text(model.terrainMode.title))
                .overlay(alignment: .trailing) {
                    if let text = terrainToast {
                        Text(text)
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(.white)
                            .multilineTextAlignment(.trailing)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.black.opacity(0.75))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .frame(width: 230, alignment: .trailing)
                            .offset(x: -60)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
            MapButton(systemImage: "square.dashed", label: "Blocks") { model.sheet = .blocks }
                .contextMenu { blocksMenu }
            MapButton(systemImage: offline.activeID == nil ? "arrow.down.circle" : "arrow.down.circle.fill",
                      label: "Offline maps", active: offline.activeID != nil) { model.sheet = .offline }
            MapButton(systemImage: "location.magnifyingglass", label: "Go to coordinates") { model.sheet = .coordinates }
            MapButton(systemImage: measureIcon, label: "Measure", active: model.measure != .off) { model.measureButtonTapped() }
            MapButton(systemImage: model.following ? "location.fill" : "location", label: "My location") { model.centerOnUser() }
                .contextMenu {
                    Button(role: .destructive) { confirmClearTrack = true } label: {
                        Label("Clear recorded track", systemImage: "trash")
                    }
                }
        }
    }

    /// Contour lines, contours with elevation colours, colours alone (in the colours: `heightColours`), or neither.
    private var terrainIcon: String {
        switch model.terrainMode {
        case .contours, .contoursAndColours: return "mountain.2.fill"
        case .colours: return "paintpalette.fill"
        case .drone: return "airplane"
        case .off: return "mountain.2"
        }
    }

    /// The terrain button: the next of contours → contours and colours → colours → drone mode → none, said beside it
    /// for a moment.
    private func nextTerrainMode() {
        model.terrainMode = model.terrainMode.next
        terrainToast = model.terrainMode.title
        toastEnd?.cancel()
        let end = DispatchWorkItem { withAnimation { terrainToast = nil } }
        toastEnd = end
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: end)
    }

    /// A long press on Blocks: each file's blocks on or off the map, and their colours, without opening the list.
    @ViewBuilder private var blocksMenu: some View {
        ForEach(model.kml.files) { f in
            Button {
                model.kml.setShown(f.id, !f.isShown)
            } label: {
                Label(f.name, systemImage: f.isShown ? "checkmark.circle.fill" : "circle")
            }
        }
        if !model.kml.files.isEmpty {
            Divider()
            Button {
                model.kml.useFileColours.toggle()
            } label: {
                Label("Colours from the files", systemImage: model.kml.useFileColours ? "checkmark.square.fill" : "square")
            }
        }
    }

    /// The GPS's own height for you, if it is good to 25 m: shown while the elevation data has none.
    private static func gpsAltitude(_ loc: CLLocation) -> Double? {
        loc.verticalAccuracy > 0 && loc.verticalAccuracy <= 25 ? loc.altitude : nil
    }

    private var measureIcon: String {
        switch model.measure {
        case .off: return "ruler"
        case .aiming, .placed, .path: return "scope"
        case .closed: return "xmark"
        }
    }

    /// While a point can be placed with the measure button.
    private var showsCrosshair: Bool {
        switch model.measure {
        case .aiming, .placed, .path: return true
        case .off, .closed: return false
        }
    }
}

/// MapKit's compass (appears only while the map is rotated; tap to point north up again).
struct CompassButton: UIViewRepresentable {
    let mapView: MKMapView

    func makeUIView(context: Context) -> MKCompassButton {
        let b = MKCompassButton(mapView: mapView)
        b.compassVisibility = .adaptive
        return b
    }

    func updateUIView(_ uiView: MKCompassButton, context: Context) {}
}

struct MapButton: View {
    let systemImage: String
    /// Spoken by VoiceOver.
    let label: String
    var active = false
    /// A switch that is off: the icon is faded.
    var dimmed = false
    /// The icon in the elevation colours, low (blue) at the bottom to high (red) at the top.
    var heightColours = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .frame(width: 50, height: 50)
                .foregroundStyle(iconStyle)
                .background(active ? Color.yellow : Color.black.opacity(0.6))
                .clipShape(Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 3)
        }
        .accessibilityLabel(Text(label))
    }

    private var iconStyle: AnyShapeStyle {
        if active { return AnyShapeStyle(Color.black) }
        if heightColours { return AnyShapeStyle(LinearGradient(gradient: ElevationKey.gradient, startPoint: .bottom, endPoint: .top)) }
        return AnyShapeStyle(Color.white.opacity(dimmed ? 0.45 : 1))
    }
}

struct Crosshair: View {
    var body: some View {
        ZStack {
            Circle().stroke(Color.white, lineWidth: 2).frame(width: 36, height: 36)
            Rectangle().fill(Color.white).frame(width: 2, height: 56)
            Rectangle().fill(Color.white).frame(width: 56, height: 2)
            Circle().fill(Color.red).frame(width: 6, height: 6)
        }
        .shadow(color: .black, radius: 1.5)
    }
}

struct Hint: View {
    let text: String
    /// Shows a ✕ ("Stop measuring") after the text.
    var onClose: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(text)
                .font(.footnote.weight(.medium))
            if let onClose = onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.footnote.weight(.bold))
                }
                .accessibilityLabel(Text("Stop measuring"))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.65))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

/// Live distance and height from you to the marker; or the length of the line through the points, the height from
/// A to the last point, and once closed, the shape's area and perimeter.
struct MeasurePanel: View {
    @ObservedObject var model: AppModel
    @ObservedObject var recorder: LocationRecorder

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            let points = model.measure.points
            switch model.measure {
            case .closed:
                Text("Shape \(MeasureMode.label(0))–\(MeasureMode.label(points.count - 1)) · \(points.count) corners")
                    .font(.caption.weight(.semibold)).opacity(0.85)
                Text(model.measuredArea.map(Format.hectares) ?? "–")
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                Text("Perimeter \(model.markerDistance.map { Format.distance($0, model.distanceUnit) } ?? "–")")
                    .font(.headline)
                    .modifier(UnitSwitch(name: "distances", action: model.nextDistanceUnit))
                if let a = model.measuredArea, a < 1_000_000 {
                    Text("\(Format.count(Int(a.rounded()))) m²").font(.caption).opacity(0.85)
                }
                actions(close: false)
            case .path:
                let last = MeasureMode.label(points.count - 1)
                Text(points.count == 2 ? "From A to B" : "From A to \(last) · \(points.count) points")
                    .font(.caption.weight(.semibold)).opacity(0.85)
                Text(model.markerDistance.map { Format.distance($0, model.distanceUnit) } ?? "–")
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                    .modifier(UnitSwitch(name: "distances", action: model.nextDistanceUnit))
                    .accessibilityIdentifier("measuredDistance")
                Text(heightText)
                    .font(.headline)
                if points.count >= 3 {
                    Text("Last leg \(MeasureMode.label(points.count - 2)) → \(last): \(Format.distance(Geo.distance(points[points.count - 2], points[points.count - 1]), model.distanceUnit))")
                        .font(.caption).opacity(0.85)
                }
                if let a = model.markerElevation, let b = model.lastElevation {
                    Text("A \(Format.height(a, model.heightUnit)) · \(last) \(Format.height(b, model.heightUnit)) above sea level")
                        .font(.caption).opacity(0.85)
                }
                actions(close: points.count >= 3)
                Text(points.count >= 3 ? "Move the crosshair and tap the map for more points, or tap A to close the shape and see its area."
                                       : "Move the crosshair and tap the map for more points.")
                    .font(.caption2).opacity(0.7)
            default:
                Text(model.markerDistance.map { Format.distance($0, model.distanceUnit) } ?? "Waiting for GPS…")
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                    .modifier(UnitSwitch(name: "distances", action: model.nextDistanceUnit))
                Text(heightText)
                    .font(.headline)
                if let m = model.markerElevation, let u = model.userElevation {
                    Text("Marker \(Format.height(m, model.heightUnit)) · You \(Format.height(u, model.heightUnit)) above sea level")
                        .font(.caption)
                        .opacity(0.85)
                }
                Text("Move the crosshair to the next spot and tap the map for point B.")
                    .font(.caption2)
                    .opacity(0.7)
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(alignment: .topTrailing) { StopMeasuringButton(model: model) }
    }

    /// Undo the last point; close the shape (three or more points).
    private func actions(close: Bool) -> some View {
        HStack(spacing: 10) {
            Button(action: model.undoMeasurePoint) {
                Label("Undo", systemImage: "arrow.uturn.backward")
            }
            if close {
                Button(action: model.closeMeasureShape) {
                    Label("Close shape", systemImage: "pentagon")
                }
            }
        }
        .font(.subheadline.weight(.semibold))
        .buttonStyle(.bordered)
        .tint(.white)
        .padding(.top, 2)
    }

    /// Marker vs you, or the last point vs A.
    private var heightText: String {
        let isPath: Bool
        if case .path = model.measure { isPath = true } else { isPath = false }
        let first = model.markerElevation
        let second = isPath ? model.lastElevation : model.userElevation
        guard let m = first, let u = second else {
            return Connectivity.shared.isOnline
                ? "Height: loading elevation…"
                : "Height: no elevation data here offline (download this area)"
        }
        let unit = model.heightUnit
        if isPath {
            let last = MeasureMode.label(model.measure.points.count - 1)
            let diff = u - m
            if unit.whole(abs(diff)) == 0 { return "\(last) is level with A" }
            let d = Format.heightDifference(diff, unit)
            return diff > 0 ? "▲ \(last) is \(d) higher than A" : "▼ \(last) is \(d) lower than A"
        }
        let diff = m - u
        if unit.whole(abs(diff)) == 0 { return "Marker is level with you" }
        let d = Format.heightDifference(diff, unit)
        return diff > 0 ? "▲ Marker is \(d) higher than you" : "▼ Marker is \(d) lower than you"
    }
}

/// The map's scale, bottom left: a round distance and its length on the map, changing as the map zooms.
struct ScaleBarView: View {
    @ObservedObject var scale: MapScale
    /// A tap: distances (and the bar) in the next unit.
    let onTap: () -> Void

    var body: some View {
        if scale.width > 0 {
            VStack(alignment: .leading, spacing: 2) {
                // Only the distance takes a tap (for the next unit); the bar leaves the map under it to the map.
                Text(scale.label)
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .padding(.vertical, 3)
                    .padding(.trailing, 6)
                    .modifier(UnitSwitch(name: "distances", action: onTap))
                    .accessibilityLabel("Scale")
                    .accessibilityValue(Text(scale.label))
                ScaleBarShape()
                    .stroke(Color.white, style: StrokeStyle(lineWidth: 2, lineCap: .square))
                    .frame(width: CGFloat(scale.width), height: 6)
                    .allowsHitTesting(false)
            }
            .foregroundColor(.white)
            .shadow(color: .black, radius: 1.5)
            .padding(.bottom, 4)
        }
    }
}

/// The elevation colours' ramp, for the legend and the terrain button.
enum ElevationKey {
    static let gradient = Gradient(stops: ElevationColours.stops.map {
        Gradient.Stop(color: Color(red: $0.1, green: $0.2, blue: $0.3), location: $0.0)
    })
}

/// The contour lines' interval at this zoom, above the scale bar: a thin line every so many metres, a bold one (with
/// its height on it) every so many more. Shown while contour lines are drawn.
struct ContourKeyView: View {
    @ObservedObject var legend: MapLegend

    var body: some View {
        if let c = legend.key.contours {
            VStack(alignment: .leading, spacing: 2) {
                Text("Contours")
                    .font(.caption2.weight(.semibold))
                    .opacity(0.9)
                HStack(spacing: 5) {
                    ContourSample(bold: false)
                    Text(Format.height(c.step))
                    ContourSample(bold: true)
                        .padding(.leading, 4)
                    Text(Format.height(c.index))
                }
                .font(.caption.weight(.semibold).monospacedDigit())
            }
            .foregroundColor(.white)
            .shadow(color: .black, radius: 1.5)
            .allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Contour lines")
            .accessibilityValue(Text("every \(Format.height(c.step)), bold every \(Format.height(c.index))"))
            .accessibilityIdentifier("contourKey")
        }
    }
}

/// A short piece of contour line in the map's colours (`ContourRenderer`), with its dark edge.
private struct ContourSample: View {
    let bold: Bool

    var body: some View {
        ZStack {
            Capsule()
                .fill(Color.black.opacity(0.4))
                .frame(width: 20, height: bold ? 4.5 : 3)
            Capsule()
                .fill(bold ? Color(red: 1.0, green: 0.74, blue: 0.16) : Color(red: 1.0, green: 0.84, blue: 0.32).opacity(0.9))
                .frame(width: 18, height: bold ? 3 : 1.6)
        }
    }
}

/// The heights the elevation colours run between, under the scale bar: blue the lowest, red the highest. Shown while
/// the colours are drawn.
struct ColourKeyView: View {
    @ObservedObject var legend: MapLegend

    var body: some View {
        if let r = legend.key.colours {
            VStack(alignment: .leading, spacing: 3) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(LinearGradient(gradient: ElevationKey.gradient, startPoint: .leading, endPoint: .trailing))
                    .overlay(RoundedRectangle(cornerRadius: 2).stroke(Color.white.opacity(0.85), lineWidth: 1))
                    .frame(width: CGFloat(MapScale.maxWidth), height: 8)
                HStack {
                    Text(Format.height(r.low))
                    Spacer(minLength: 6)
                    Text(Format.height(r.high))
                }
                .frame(width: CGFloat(MapScale.maxWidth))
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundColor(.white)
                .shadow(color: .black, radius: 1.5)
            }
            .allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Elevation colours")
            .accessibilityValue(Text("blue \(Format.height(r.low)) to red \(Format.height(r.high))"))
            .accessibilityIdentifier("colourKey")
        }
    }
}

/// ⊔: a line with a tick up at each end.
private struct ScaleBarShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        return p
    }
}

/// While aiming: the live distance and height from you to the spot under the crosshair, as the map moves.
struct AimPanel: View {
    @ObservedObject var model: AppModel
    @ObservedObject var aim: CrosshairAim
    @ObservedObject var recorder: LocationRecorder

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("You to the crosshair")
                .font(.caption.weight(.semibold)).opacity(0.85)
            Text(distanceText)
                .font(.system(size: 30, weight: .bold).monospacedDigit())
                .modifier(UnitSwitch(name: "distances", action: model.nextDistanceUnit))
            // While the map moves, the height is an earlier spot's until the lookup catches up: dimmed.
            let current = aim.elevationPoint == aim.point
            Text(heightText)
                .font(.headline)
                .opacity(current ? 1 : 0.6)
            if let c = aim.elevation, let u = model.userElevation {
                Text("Crosshair \(Format.height(c, model.heightUnit)) · You \(Format.height(u, model.heightUnit)) above sea level")
                    .font(.caption).opacity(current ? 0.85 : 0.5)
            }
            Text("Move the crosshair onto the spot and tap the map (or the measure button) for point A.")
                .font(.caption2).opacity(0.7)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(alignment: .topTrailing) { StopMeasuringButton(model: model) }
    }

    private var distanceText: String {
        guard let loc = recorder.location else { return "Waiting for GPS…" }
        guard let p = aim.point else { return "–" }
        return Format.distance(loc.distance(from: CLLocation(latitude: p.lat, longitude: p.lon)), model.distanceUnit)
    }

    private var heightText: String {
        guard let c = aim.elevation, let u = model.userElevation else {
            return Connectivity.shared.isOnline
                ? "Height: loading elevation…"
                : "Height: no elevation data here offline (download this area)"
        }
        let unit = model.heightUnit
        let diff = c - u
        if unit.whole(abs(diff)) == 0 { return "Crosshair is level with you" }
        let d = Format.heightDifference(diff, unit)
        return diff > 0 ? "▲ Crosshair is \(d) higher than you" : "▼ Crosshair is \(d) lower than you"
    }
}

/// The ✕ in the corner of the measuring hint and panel.
struct StopMeasuringButton: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Button(action: model.clearMeasure) {
            Image(systemName: "xmark")
                .font(.subheadline.weight(.bold))
                .foregroundColor(.white)
                .padding(12)
        }
        .accessibilityLabel(Text("Stop measuring"))
    }
}

/// A spot's coordinates with its height beside them, or under them when they don't fit side by side (large text).
struct CoordinateRow<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { content() }
            VStack(spacing: 6) { content() }
        }
    }
}

/// The height above sea level of a spot, beside its coordinates: from the elevation data (as the contour lines), or for
/// you, while there is none, the GPS's own (marked).
struct HeightBar: View {
    let elevation: Double?
    var gpsAltitude: Double?
    let unit: HeightUnit
    /// Accessibility identifier, for the UI test.
    let id: String
    /// A tap: heights in the other unit (metres or feet).
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 5) {
                Image(systemName: "mountain.2.fill")
                    .font(.caption)
                Text(text)
                    .font(.subheadline.monospacedDigit().weight(.semibold))
                    .lineLimit(1)
                if elevation == nil, gpsAltitude != nil {
                    Text("GPS")
                        .font(.caption2.weight(.bold))
                        .opacity(0.75)
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color.black.opacity(0.65))
            .clipShape(Capsule())
        }
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Height"))
        .accessibilityValue(Text(spoken))
        .accessibilityHint(Text("Shows heights in \(unit.next.name)"))
        .accessibilityIdentifier(id)
    }

    private var text: String {
        if let h = elevation ?? gpsAltitude { return Format.height(h, unit) }
        return Connectivity.shared.isOnline ? "… \(unit.symbol)" : "– \(unit.symbol)"
    }

    private var spoken: String {
        guard let h = elevation ?? gpsAltitude else {
            return Connectivity.shared.isOnline ? "Loading" : "No elevation data here offline"
        }
        return "\(Format.height(h, unit)) above sea level" + (elevation == nil ? ", from GPS" : "")
    }
}

/// A distance (or the scale bar) that a tap shows in the next unit: kilometres, miles, nautical miles.
struct UnitSwitch: ViewModifier {
    let name: String
    let action: () -> Void

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .onTapGesture(perform: action)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(Text("Tap to show \(name) in other units"))
    }
}

/// Drone mode's reporting lines, as a pilot says where they are: you (cyan, as the distances from you on the map) and
/// the tapped spot (amber, as the distances from it), each from the nearest airfield.
struct DroneCard: View {
    let report: DroneReport

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let you = report.you {
                line("location.fill", "You", you, Color(AviationView.youColour))
            }
            if let pin = report.pin {
                line("mappin", "Pin", pin, Color(AviationView.pinColour))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Color.white.opacity(0.18), lineWidth: 0.5))
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("droneCard")
    }

    private func line(_ icon: String, _ who: String, _ text: String, _ colour: Color) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.caption.weight(.bold))
                .frame(width: 14)
            Text(who)
                .font(.caption.weight(.heavy))
                .opacity(0.8)
            Text(text)
                .font(.subheadline.weight(.bold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .foregroundColor(colour)
    }
}

/// Coordinates you can copy: tap to put them on the clipboard.
struct CoordinateBar: View {
    let icon: String
    let text: String
    /// Accessibility identifier, for the UI test.
    let id: String
    var onClose: (() -> Void)?
    @State private var copied = false

    var body: some View {
        HStack(spacing: 8) {
            Button {
                UIPasteboard.general.string = text
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { copied = false }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: icon)
                    Text(copied ? "Copied" : text)
                        .font(.subheadline.monospacedDigit().weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if !copied {
                        Image(systemName: "doc.on.doc").font(.caption).opacity(0.8)
                    }
                }
            }
            .accessibilityLabel(Text(copied ? "Copied" : "Copy \(text)"))
            .accessibilityIdentifier(id)
            if let onClose = onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.caption.weight(.bold))
                }
                .accessibilityLabel(Text("Hide coordinates"))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.65))
        .clipShape(Capsule())
    }
}

struct DownloadBanner: View {
    let area: OfflineManager.Area
    let progress: DownloadProgress?
    let onPause: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Downloading \(area.name)")
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption.monospacedDigit())
                    .opacity(0.85)
                ProgressView(value: fraction)
                    .progressViewStyle(.linear)
                    .tint(.yellow)
            }
            Spacer()
            Button(action: onPause) {
                Image(systemName: "pause.fill")
                    .padding(8)
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var fraction: Double {
        guard let p = progress, p.levelTotal > 0 else { return 0 }
        return Double(p.levelDone) / Double(p.levelTotal)
    }

    private var detail: String {
        guard let p = progress, !p.layer.isEmpty else { return "Starting…" }
        return "\(p.layer) · zoom \(p.zoom) of up to \(p.maxZoom): \(Format.count(p.levelDone))/\(Format.count(p.levelTotal)) · \(Format.count(p.fetched)) tiles, \(Format.bytes(p.bytes)) saved"
    }
}

/// Shown while choosing an offline area with the lasso.
struct DrawingBar: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(spacing: 8) {
            if model.draftArea == nil {
                Picker("", selection: $model.lassoActive) {
                    Text("Move map").tag(false)
                    Text("Draw").tag(true)
                }
                .pickerStyle(.segmented)
                Text(model.lassoActive
                     ? "Draw a loop around the area with one finger."
                     : "Pan and zoom to the area, then switch to Draw.")
                    .font(.footnote)
                HStack {
                    Button("Cancel", role: .cancel) { model.cancelDrawing() }
                    Spacer()
                }
            } else {
                Text("Download this area?")
                    .font(.headline)
                HStack {
                    Button("Cancel", role: .cancel) { model.cancelDrawing() }
                    Spacer()
                    Button("Redraw") { model.draftArea = nil }
                    Spacer()
                    Button("Continue") { model.confirmDrawnArea() }
                        .font(.body.weight(.semibold))
                }
            }
        }
        .foregroundColor(.white)
        .tint(.yellow)
        .padding(12)
        .background(Color.black.opacity(0.75))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
