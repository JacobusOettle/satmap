import MapKit
import SwiftUI
import UIKit

struct MapScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var offline: OfflineManager
    @ObservedObject var recorder: LocationRecorder
    @State private var confirmClearTrack = false

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
                    Hint(text: "Tap the spot on the map, or move the crosshair onto it and tap the measure button.",
                         onClose: model.clearMeasure)
                case .placed, .path, .closed:
                    MeasurePanel(model: model, recorder: recorder)
                case .off:
                    EmptyView()
                }
                if let p = model.selectedPoint, !model.isDrawingArea {
                    CoordinateBar(icon: "mappin", text: Format.coordinate(p), id: "tappedCoordinates",
                                  onClose: { model.selectedPoint = nil })
                        .frame(maxWidth: .infinity)
                }
                if recorder.authorization == .denied || recorder.authorization == .restricted {
                    Hint(text: "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services.")
                } else if recorder.reducedAccuracy {
                    Hint(text: "Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location.")
                }
                Spacer()
                HStack(alignment: .bottom) {
                    // Map credits are in the Offline maps sheet.
                    if let loc = recorder.location, !model.isDrawingArea {
                        CoordinateBar(icon: recorder.power == .sleeping ? "location" : "location.fill",
                                      text: Format.coordinate(GeoPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)),
                                      id: "myCoordinates")
                            .padding(.bottom, 24) // clear of MapKit's "Legal" link in the corner
                    }
                    Spacer()
                    if !model.isDrawingArea {
                        controls
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .sheet(item: $model.sheet) { sheet in
            switch sheet {
            case .blocks:
                BlocksSheet(model: model, library: model.kml)
            case .offline:
                OfflineSheet(model: model, offline: offline, library: model.kml)
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

    private var controls: some View {
        VStack(spacing: 12) {
            if let map = model.map?.mapView {
                CompassButton(mapView: map)
                    .frame(width: 44, height: 44)
            }
            MapButton(systemImage: model.showContours ? "mountain.2.fill" : "mountain.2", label: "Contour lines",
                      dimmed: !model.showContours) { model.showContours.toggle() }
                .accessibilityValue(Text(model.showContours ? "On" : "Off"))
            MapButton(systemImage: "square.dashed", label: "Blocks") { model.sheet = .blocks }
            MapButton(systemImage: offline.activeID == nil ? "arrow.down.circle" : "arrow.down.circle.fill",
                      label: "Offline maps", active: offline.activeID != nil) { model.sheet = .offline }
            MapButton(systemImage: measureIcon, label: "Measure", active: model.measure != .off) { model.measureButtonTapped() }
            MapButton(systemImage: model.following ? "location.fill" : "location", label: "My location") { model.centerOnUser() }
                .contextMenu {
                    Button(role: .destructive) { confirmClearTrack = true } label: {
                        Label("Clear recorded track", systemImage: "trash")
                    }
                }
        }
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .frame(width: 50, height: 50)
                .foregroundColor(active ? .black : .white.opacity(dimmed ? 0.45 : 1))
                .background(active ? Color.yellow : Color.black.opacity(0.6))
                .clipShape(Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 3)
        }
        .accessibilityLabel(Text(label))
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
                Text("Perimeter \(model.markerDistance.map(Format.distance) ?? "–")")
                    .font(.headline)
                if let a = model.measuredArea, a < 1_000_000 {
                    Text("\(Format.count(Int(a.rounded()))) m²").font(.caption).opacity(0.85)
                }
                actions(close: false)
            case .path:
                let last = MeasureMode.label(points.count - 1)
                Text(points.count == 2 ? "From A to B" : "From A to \(last) · \(points.count) points")
                    .font(.caption.weight(.semibold)).opacity(0.85)
                Text(model.markerDistance.map(Format.distance) ?? "–")
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                Text(heightText)
                    .font(.headline)
                if points.count >= 3 {
                    Text("Last leg \(MeasureMode.label(points.count - 2)) → \(last): \(Format.distance(Geo.distance(points[points.count - 2], points[points.count - 1])))")
                        .font(.caption).opacity(0.85)
                }
                if let a = model.markerElevation, let b = model.lastElevation {
                    Text("A \(Int(a.rounded())) m · \(last) \(Int(b.rounded())) m above sea level")
                        .font(.caption).opacity(0.85)
                }
                actions(close: points.count >= 3)
                Text(points.count >= 3 ? "Tap the map for more points, or tap A to close the shape and see its area."
                                       : "Tap the map (or use the crosshair and the measure button) for more points.")
                    .font(.caption2).opacity(0.7)
            default:
                Text(model.markerDistance.map(Format.distance) ?? "Waiting for GPS…")
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                Text(heightText)
                    .font(.headline)
                if let m = model.markerElevation, let u = model.userElevation {
                    Text("Marker \(Int(m.rounded())) m · You \(Int(u.rounded())) m above sea level")
                        .font(.caption)
                        .opacity(0.85)
                }
                Text("Tap the map, or use the crosshair and the measure button, for a second point.")
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
        if isPath {
            let last = MeasureMode.label(model.measure.points.count - 1)
            let diff = (u - m).rounded()
            if diff == 0 { return "\(last) is level with A" }
            return diff > 0 ? "▲ \(last) is \(Int(diff)) m higher than A" : "▼ \(last) is \(Int(-diff)) m lower than A"
        }
        let diff = (m - u).rounded()
        if diff == 0 { return "Marker is level with you" }
        return diff > 0 ? "▲ Marker is \(Int(diff)) m higher than you" : "▼ Marker is \(Int(-diff)) m lower than you"
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
