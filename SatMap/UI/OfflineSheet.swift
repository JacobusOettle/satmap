import SwiftUI
import UniformTypeIdentifiers

/// Offline areas: choose a new area (a place, the lasso, a GPX or KML file, your track), manage downloads, and back
/// them up.
struct OfflineSheet: View {
    @ObservedObject var model: AppModel
    @ObservedObject var offline: OfflineManager
    @ObservedObject var library: KMLLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var freeSpace: Int64?
    @State private var importing = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink {
                        PlaceSearchView(model: model)
                    } label: {
                        Label("Find a park, reserve or town", systemImage: "magnifyingglass")
                    }
                    Button {
                        model.startDrawingArea()
                    } label: {
                        Label("Draw an area on the map", systemImage: "lasso")
                    }
                    NavigationLink {
                        KMLAreaPicker(model: model, library: library)
                    } label: {
                        Label("Use a GPX or KML file", systemImage: "square.dashed")
                    }
                    NavigationLink {
                        TrackAreaView(model: model)
                    } label: {
                        Label("Where I've been", systemImage: "figure.walk")
                    }
                } footer: {
                    Text("Saves satellite imagery at the highest detail available plus elevation data for contours and heights, so the map works with no signal.")
                }

                Section {
                    if offline.areas.isEmpty {
                        Text("No offline areas yet.").foregroundColor(.secondary)
                    }
                    ForEach(offline.areas) { area in
                        NavigationLink {
                            AreaDetailView(model: model, offline: offline, areaID: area.id)
                        } label: {
                            AreaRow(area: area, progress: offline.activeID == area.id ? offline.progress : nil,
                                    canDownloadNow: offline.canDownloadNow(area),
                                    onPause: { offline.pause(area.id) },
                                    onResume: { offline.resume(area.id) })
                        }
                            .contextMenu {
                                Button {
                                    offline.export([area.id])
                                } label: {
                                    Label("Export", systemImage: "square.and.arrow.up")
                                }
                                .disabled(offline.backupStatus != nil)
                            }
                    }
                    .onDelete { offsets in
                        offsets.map { offline.areas[$0].id }.forEach(offline.delete)
                    }
                } header: {
                    Text("Offline areas")
                } footer: {
                    if let status = offline.backgroundStatus {
                        Text(status)
                    }
                }

                Section {
                    Button {
                        offline.export(offline.areas.map(\.id))
                    } label: {
                        Label("Export all offline maps", systemImage: "square.and.arrow.up")
                    }
                    .disabled(offline.areas.isEmpty || offline.backupStatus != nil)
                    Button {
                        importing = true
                    } label: {
                        Label("Restore from a backup", systemImage: "square.and.arrow.down")
                    }
                    .disabled(offline.backupStatus != nil)
                    .fileImporter(isPresented: $importing, allowedContentTypes: [.mbtiles], allowsMultipleSelection: true) { result in
                        if case .success(let urls) = result { offline.restore(urls) }
                    }
                    if let busy = offline.backupStatus {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                if offline.backupProgress == nil { ProgressView() }
                                Text(busy).foregroundColor(.secondary)
                            }
                            if let done = offline.backupProgress {
                                ProgressView(value: done)
                                    .progressViewStyle(.linear)
                            }
                        }
                    }
                } header: {
                    Text("Backup")
                } footer: {
                    Text("Saves each area as two MBTiles files, satellite and elevation, to Files, iCloud Drive or a USB drive, or AirDrop them to a computer. To restore, choose both files of each area. Long-press an area to export only that one.")
                }

                Section {
                    if let free = freeSpace {
                        Text("Free space on this iPhone: \(Format.bytes(free))")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                } footer: {
                    Text("Map data: \(TileSources.attribution) · Roads and places: © OpenStreetMap contributors, found with Photon (komoot) and Nominatim · Airfields and navaids: OurAirports")
                }
            }
            .navigationTitle("Offline maps")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { offline.refreshSizes() }
            .alert("Backup", isPresented: Binding(get: { offline.backupMessage != nil },
                                                  set: { if !$0 { offline.clearBackupMessage() } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(offline.backupMessage ?? "")
            }
            .task(id: "\(offline.completedRevision)-\(offline.areas.count)") {
                // The free-space query can be slow: run it off the main thread, not in the view body.
                freeSpace = await Task.detached(priority: .utility) { AppPaths.freeDiskSpace() }.value
            }
        }
    }
}

private struct AreaRow: View {
    let area: OfflineManager.Area
    let progress: DownloadProgress?
    let canDownloadNow: Bool
    let onPause: () -> Void
    let onResume: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(area.name).font(.body.weight(.medium))
                Text(statusText)
                    .font(.caption)
                    .foregroundColor(statusColor)
                Text("\(Format.bytes(area.bytesOnDisk)) · up to zoom \(area.maxZoom)\(area.isTrack == true ? " · along your track" : area.alongRoads ? " · along roads" : "")\(area.allowCellular ? " · mobile data allowed" : "")")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            Spacer()
            switch area.status {
            case .downloading, .queued, .waitingForNetwork:
                Button(action: onPause) { Image(systemName: "pause.circle").font(.title2) }
                    .buttonStyle(.borderless)
            case .paused, .incomplete:
                Button(action: onResume) { Image(systemName: "arrow.clockwise.circle").font(.title2) }
                    .buttonStyle(.borderless)
            case .complete:
                Image(systemName: "checkmark.circle.fill").foregroundColor(.green).font(.title2)
            }
        }
    }

    private var statusText: String {
        switch area.status {
        case .queued: return "Waiting to start"
        case .downloading:
            guard let p = progress, !p.layer.isEmpty else { return "Downloading…" }
            return "Downloading \(p.layer.lowercased()) · zoom \(p.zoom) of \(p.maxZoom) · \(Format.count(p.levelDone))/\(Format.count(p.levelTotal)) tiles"
        case .paused: return "Paused"
        case .waitingForNetwork:
            return area.allowCellular || !Connectivity.shared.isOnline ? "Waiting for internet" : "Waiting for Wi-Fi"
        case .complete: return "Ready offline"
        case .incomplete:
            if let problem = area.problem { return "Stopped: \(problem) Tap ↻ to continue." }
            return "Incomplete: \(Format.count(area.failedTiles)) tiles failed. Tap ↻ to retry."
        }
    }

    private var statusColor: Color {
        switch area.status {
        case .complete: return .green
        case .incomplete: return .orange
        default: return .secondary
        }
    }
}

/// Finds a place by name (a park, a reserve, a town, a camp …) to download: its whole area or only along its roads.
private struct PlaceSearchView: View {
    @ObservedObject var model: AppModel
    @State private var text = ""
    @State private var results: [FoundPlace] = []
    @State private var searching = false
    @State private var message: String?
    @FocusState private var focused: Bool

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("Kruger, Sabi Sand, Skukuza, Hazyview …", text: $text)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .onSubmit(search)
                        .accessibilityIdentifier("placeSearchText")
                    if searching {
                        ProgressView()
                    } else {
                        Button("Search", action: search)
                            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            } footer: {
                Text("Parks and reserves are saved within their boundary; towns, villages and camps with the area around them. Next you choose the detail, and whether to save the whole area or only along its roads.")
            }
            if let message = message {
                Text(message).foregroundColor(.secondary)
            }
            if !results.isEmpty {
                Section("Places") {
                    ForEach(results) { place in
                        NavigationLink {
                            PlaceAreaView(model: model, place: place)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.name).foregroundColor(.primary)
                                Text(detail(place)).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Find a place")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if results.isEmpty { focused = true } }
    }

    /// "Nature reserve · Mpumalanga, South Africa · about 650 km²", or for a spot "Town · … · 4 km around it".
    private func detail(_ p: FoundPlace) -> String {
        var parts = [p.kind]
        if !p.region.isEmpty { parts.append(p.region) }
        if p.hasBoundary, let km2 = p.extentKm2 {
            parts.append(km2 >= 10 ? "up to \(Format.count(Int(km2.rounded()))) km²" : String(format: "up to %.1f km²", km2))
        } else {
            let r = model.distanceUnit == .metric ? "\(Int(p.radiusMeters / 1000)) km" : Format.distance(p.radiusMeters, model.distanceUnit)
            parts.append("\(r) around it")
        }
        return parts.joined(separator: " · ")
    }

    private func search() {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, !searching else { return }
        searching = true
        message = nil
        let near = model.map.map { GeoPoint(lat: $0.centerCoordinate.latitude, lon: $0.centerCoordinate.longitude) }
        Task {
            do {
                let found = try await PlaceSearch.search(q, near: near)
                results = found
                message = found.isEmpty ? "No park, reserve, town or camp called “\(q)” was found. Try another spelling." : nil
            } catch {
                message = (error as? LocalizedError)?.errorDescription ?? "The search didn't work. Try again."
            }
            searching = false
        }
    }
}

/// The chosen place's boundary, once loaded, on the usual download screen (detail, whole area or along its roads).
private struct PlaceAreaView: View {
    @ObservedObject var model: AppModel
    let place: FoundPlace
    @State private var shape: CoverageShape?
    @State private var failed: String?

    var body: some View {
        Group {
            if let shape = shape {
                NewAreaView(model: model, area: PendingArea(name: place.name, shape: shape))
            } else if let failed = failed {
                VStack(spacing: 12) {
                    Text(failed).foregroundColor(.secondary).multilineTextAlignment(.center)
                    Button("Try again") { Task { await load() } }
                }
                .padding()
            } else {
                ProgressView("Getting the boundary of \(place.name) …")
            }
        }
        .task { if shape == nil { await load() } }
    }

    private func load() async {
        failed = nil
        do {
            shape = try await PlaceSearch.area(for: place)
        } catch is CancellationError {
            return
        } catch {
            failed = (error as? LocalizedError)?.errorDescription ?? "Couldn't get the boundary. Try again."
        }
    }
}

/// A road set shipped with the app, opened in the download screen (loaded when shown). Offline maps no longer offers
/// Kruger's: areas saved with it still download, resume and go deeper (`OfflineArea.roadLines`), and an area inside
/// Kruger takes its roads from it (`RoadPresets.covering`).
private struct PresetAreaView: View {
    @ObservedObject var model: AppModel
    let presetID: String

    var body: some View {
        if let preset = RoadPresets.load(presetID) {
            NewAreaView(model: model, area: PendingArea(name: preset.name, shape: CoverageShape(polygons: preset.boundary)),
                        presetID: presetID)
        } else {
            Text("The road list is missing from this build of SatMap.").foregroundColor(.secondary)
        }
    }
}

/// "Where I've been": the download screen for imagery along the recorded track, plus the earlier trips already in
/// the track area (the track may have been cleared since). Worked out once when shown.
private struct TrackAreaView: View {
    @ObservedObject var model: AppModel
    @State private var download: TrackDownload?
    @State private var worked = false

    var body: some View {
        Group {
            if let download = download {
                NewAreaView(model: model,
                            area: PendingArea(name: model.offline.areas.first { $0.isTrack == true }?.name ?? "Where I've been",
                                              shape: Roads.corridor(download.lines)),
                            track: download)
            } else if worked {
                Text("No track recorded yet. Your dotted track is recorded while SatMap knows where you are.")
                    .foregroundColor(.secondary)
                    .padding()
            } else {
                ProgressView()
            }
        }
        .task {
            guard !worked else { return }
            let track = model.track
            let existing = model.offline.areas.first { $0.isTrack == true }
            let previous = existing?.roads ?? []
            let completeTo = existing?.status == .complete ? existing?.maxZoom : nil
            let result = await Task.detached(priority: .userInitiated) { () -> TrackDownload? in
                let latest = Roads.trackLines(track)
                let lines = Roads.mergeTrack(previous: previous, latest: latest)
                guard !lines.isEmpty else { return nil }
                return TrackDownload(lines: lines,
                                     newLines: completeTo == nil ? lines : Roads.newParts(of: latest, comparedWith: previous),
                                     previousLines: previous,
                                     maxZoom: existing?.maxZoom, allowCellular: existing?.allowCellular, completeTo: completeTo)
            }.value
            download = result
            worked = true
        }
    }
}

/// Pick an imported KML or GPX file to download: inside its shapes, and 15 m around its lines and points
/// (`FileShapes`).
private struct KMLAreaPicker: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: KMLLibrary

    var body: some View {
        List {
            if library.files.isEmpty {
                Text("Import a KML or GPX file first (Blocks button on the map).")
                    .foregroundColor(.secondary)
            }
            ForEach(library.files) { f in
                NavigationLink {
                    NewAreaView(model: model, area: PendingArea(name: f.name, shape: f.shapes.downloadShape, file: f.shapes))
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.name)
                        Text(Format.fileContents(f.shapes))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Choose a file")
    }
}
