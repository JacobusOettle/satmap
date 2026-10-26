import MapKit
import SwiftUI

struct MapScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var offline: OfflineManager
    @ObservedObject var recorder: LocationRecorder
    @State private var confirmClearTrack = false

    var body: some View {
        ZStack {
            MapViewRepresentable(model: model)
                .ignoresSafeArea()

            if model.measure == .aiming {
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
                    Hint(text: "Move the map so the crosshair is on the spot, then tap the measure button again.")
                case .placed:
                    MeasurePanel(model: model, recorder: recorder)
                case .off:
                    EmptyView()
                }
                if recorder.authorization == .denied || recorder.authorization == .restricted {
                    Hint(text: "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services.")
                } else if recorder.reducedAccuracy {
                    Hint(text: "Precise Location is off, so your track can't be recorded. Turn it on in Settings › SatMap › Location.")
                }
                Spacer()
                HStack(alignment: .bottom) {
                    Text(TileSources.attribution)
                        .font(.system(size: 10))
                        .foregroundColor(.white.opacity(0.9))
                        .shadow(color: .black, radius: 1)
                        .frame(maxWidth: 230, alignment: .leading)
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
            MapButton(systemImage: "square.dashed") { model.sheet = .blocks }
            MapButton(systemImage: offline.activeID == nil ? "arrow.down.circle" : "arrow.down.circle.fill",
                      active: offline.activeID != nil) { model.sheet = .offline }
            MapButton(systemImage: measureIcon, active: model.measure != .off) { model.measureButtonTapped() }
            MapButton(systemImage: model.following ? "location.fill" : "location") { model.centerOnUser() }
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
        case .aiming: return "scope"
        case .placed: return "xmark"
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
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .frame(width: 50, height: 50)
                .foregroundColor(active ? .black : .white)
                .background(active ? Color.yellow : Color.black.opacity(0.6))
                .clipShape(Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 3)
        }
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

    var body: some View {
        Text(text)
            .font(.footnote.weight(.medium))
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.black.opacity(0.65))
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

/// Live distance and height difference from the marker to the current position.
struct MeasurePanel: View {
    @ObservedObject var model: AppModel
    @ObservedObject var recorder: LocationRecorder

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.markerDistance.map(Format.distance) ?? "Waiting for GPS…")
                .font(.system(size: 30, weight: .bold).monospacedDigit())
            Text(heightText)
                .font(.headline)
            if let m = model.markerElevation, let u = model.userElevation {
                Text("Marker \(Int(m.rounded())) m · You \(Int(u.rounded())) m above sea level")
                    .font(.caption)
                    .opacity(0.85)
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var heightText: String {
        guard let m = model.markerElevation, let u = model.userElevation else {
            return Connectivity.shared.isOnline
                ? "Height: loading elevation…"
                : "Height: no elevation data here offline (download this area)"
        }
        let diff = (m - u).rounded()
        if diff == 0 { return "Marker is level with you" }
        return diff > 0 ? "▲ Marker is \(Int(diff)) m higher than you" : "▼ Marker is \(Int(-diff)) m lower than you"
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
