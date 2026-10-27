import SwiftUI

/// Confirm an offline area: name, detail (deepest zoom level, with the size of each), only along roads,
/// mobile data, and the size of the chosen download.
struct NewAreaView: View {
    @ObservedObject var model: AppModel
    let area: PendingArea
    /// Presented on its own (lasso) rather than pushed inside the Offline sheet (KML).
    var showsCancel = false
    /// A road set shipped with the app (`RoadPresets`): imagery is always downloaded along its roads.
    var presetID: String?
    /// Your recorded track ("Where I've been"): imagery along it, updating the one track area.
    var track: TrackDownload?

    @State private var name: String
    /// The deepest zoom level to download; remembered for the next area.
    @AppStorage("offlineMaxZoom") private var maxZoom = TileSources.imageryMaxZoom
    /// Zoom levels offered, most detailed first: from about 3 cm to 5 m per pixel.
    private let zoomChoices = Array((15...TileSources.imageryMaxZoom).reversed())
    /// For your track: the track area's own zoom, kept apart from the one remembered for other areas.
    @State private var trackZoom: Int?
    @State private var allowCellular = false
    @State private var alongRoads = false
    @State private var roads: RoadsState = .notLoaded
    /// Upper-bound tile counts per zoom level for the whole area (computed in the background).
    @State private var counts: [Int: Int]?
    @State private var freeSpace: Int64?
    @Environment(\.dismiss) private var dismiss

    /// Rough average sizes from Esri JPEG and Terrarium PNG tiles.
    private let imageryTileBytes: Double = 17_000
    private let terrainTileBytes: Double = 45_000
    private let typicalDepth = 19

    private enum RoadsState {
        case notLoaded
        case loading
        /// Roads clipped to the area, their length, and imagery tile counts per zoom along them.
        case loaded(lines: [[GeoPoint]], km: Double, counts: [Int: Int])
        case failed(String)
    }

    init(model: AppModel, area: PendingArea, showsCancel: Bool = false, presetID: String? = nil, track: TrackDownload? = nil) {
        self.model = model
        self.area = area
        self.showsCancel = showsCancel
        self.presetID = presetID
        self.track = track
        _name = State(initialValue: area.name)
        _alongRoads = State(initialValue: presetID != nil || track != nil)
        _trackZoom = State(initialValue: track?.maxZoom)
        _allowCellular = State(initialValue: track?.allowCellular ?? false)
    }

    /// Imagery always goes along given lines (a shipped road list or your track), never the whole area.
    private var fixedLines: Bool { presetID != nil || track != nil }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Area name", text: $name)
            }

            detailSection

            if track != nil {
                Section {
                    roadsStatus
                } footer: {
                    Text("Satellite imagery \(Int(Roads.corridorMeters)) m to each side of everywhere you've been (your dotted track), with elevation under it. Come back here after a trip to add the new parts: what is already saved is skipped.")
                }
            } else if let preset = presetID.flatMap(RoadPresets.load) {
                Section {
                    roadsStatus
                } footer: {
                    Text("Satellite imagery \(Int(Roads.corridorMeters)) m to each side of every tar and gravel road in the park, including management tracks: \(preset.note). Elevation for contours and heights is saved for the whole park.")
                }
            } else {
                Section {
                    Toggle("Only along roads", isOn: $alongRoads)
                    if alongRoads {
                        roadsStatus
                    }
                } footer: {
                    Text("Saves satellite imagery only \(Int(Roads.corridorMeters)) m to each side of every road and track in the area, including gravel and farm roads, as mapped on OpenStreetMap. Elevation for contours and heights is still saved for the whole area.")
                }
            }

            Section {
                Toggle("Allow mobile data", isOn: $allowCellular)
            } footer: {
                Text("Without mobile data the download waits for Wi-Fi. Keep SatMap open while it downloads (the screen stays on); it pauses in the background and continues when you come back.")
            }

            sizeSection

            Section {
                Button {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if let track = track {
                        model.startTrackDownload(name: trimmed, lines: track.lines, maxZoom: chosenZoom, allowCellular: allowCellular)
                    } else {
                        model.startDownload(PendingArea(name: trimmed, shape: area.shape),
                                            roads: presetID == nil && alongRoads ? roadLines : nil, preset: presetID,
                                            maxZoom: chosenZoom, allowCellular: allowCellular)
                    }
                    // Back to the map, where the download banner shows progress.
                    model.sheet = nil
                    dismiss()
                } label: {
                    Label("Download", systemImage: "arrow.down.circle.fill")
                        .font(.headline)
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || (alongRoads && (roadLines ?? []).isEmpty))
            }
        }
        .navigationTitle("Download area")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsCancel {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        model.discardPendingArea()
                        dismiss()
                    }
                }
            }
        }
        .task {
            // Elevation already saved for your track isn't downloaded again.
            let shape = track.map { Roads.corridor($0.newLines) } ?? area.shape
            // Along a road list or your track the whole area only matters for elevation; its imagery counts for a big
            // park would take long to work out.
            let zooms = fixedLines ? 0...Terrarium.maxZoom : 0...TileSources.imageryMaxZoom
            let result = await Task.detached(priority: .userInitiated) {
                (TileCoverage.tileCounts(shape: shape, zooms: zooms), AppPaths.freeDiskSpace())
            }.value
            freeSpace = result.1
            counts = result.0
        }
        .task(id: alongRoads) {
            if alongRoads, case .notLoaded = roads { await loadRoads() }
        }
    }

    /// Every zoom level with its detail and download size; tap one to choose it.
    private var detailSection: some View {
        Section {
            ForEach(zoomChoices, id: \.self) { z in
                Button {
                    if track != nil { trackZoom = z } else { maxZoom = z }
                } label: { zoomRow(z) }
                    .accessibilityAddTraits(z == chosenZoom ? .isSelected : [])
            }
        } header: {
            Text("Detail")
        } footer: {
            Text("Each zoom level has twice the detail of the one before and takes about 4 times the space. Esri's imagery often stops at zoom 18–19 on farms and 20–21 in towns; the download stops where the imagery does, so deeper levels can add little. Sizes include elevation for contours and heights.")
        }
    }

    private var sizeSection: some View {
        Section("Download size") {
            if let m2 = areaSquareMeters {
                row("Area", Format.hectares(m2))
            }
            if let e = chosenEstimate {
                row("Up to zoom \(chosenZoom)", sizeText(chosenZoom))
                row("Tiles (most)", Format.count(e.tiles))
                if let free = freeSpace {
                    row("Free space", Format.bytes(free))
                    if e.bytes > free - OfflineManager.minimumFreeSpace {
                        Text("This may not fit. The download stops when less than 500 MB is free; choose a lower zoom or a smaller area.")
                            .foregroundColor(.orange)
                    }
                }
            } else if alongRoads, case .failed = roads {
                Text("Needs the roads first.").foregroundColor(.secondary)
            } else {
                HStack {
                    ProgressView()
                    Text("Calculating…").foregroundColor(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var roadsStatus: some View {
        switch roads {
        case .notLoaded, .loading:
            HStack {
                ProgressView()
                Text("Finding roads…").foregroundColor(.secondary)
            }
        case .loaded(_, let km, _):
            if let track = track {
                row("Your track", kilometres(km))
                if track.completeTo != nil {
                    row("New since the last download", kilometres(Roads.length(track.newLines) / 1000))
                }
            } else if km > 0 {
                row("Roads and tracks", kilometres(km))
            } else {
                Text("No roads or tracks are mapped in this area.").foregroundColor(.orange)
            }
        case .failed(let message):
            Text(message).foregroundColor(.orange)
            Button("Try again") { Task { await loadRoads() } }
        }
    }

    private var chosenZoom: Int {
        min(max(trackZoom ?? maxZoom, zoomChoices.last!), zoomChoices.first!)
    }

    private func kilometres(_ km: Double) -> String {
        km < 10 ? String(format: "%.1f km", km) : "\(Format.count(Int(km.rounded()))) km"
    }

    private func zoomRow(_ z: Int) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Zoom \(z)").foregroundColor(.primary)
                Text(resolution(z)).font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Text(sizeText(z)).foregroundColor(.secondary).monospacedDigit()
            Image(systemName: "checkmark")
                .font(.body.weight(.semibold))
                .foregroundColor(.accentColor)
                .opacity(z == chosenZoom ? 1 : 0)
                .accessibilityHidden(true) // the row is marked selected instead
        }
        .contentShape(Rectangle())
    }

    /// Ground size of one image pixel at zoom z in the middle of the area.
    private func resolution(_ z: Int) -> String {
        Format.groundResolution(zoom: z, latitude: area.shape.bounds?.center.lat ?? 0)
    }

    /// Download size up to zoom z: about, where Esri's imagery usually reaches; an upper limit deeper down.
    private func sizeText(_ z: Int) -> String {
        guard let counts = counts, let imagery = imageryCounts else {
            if alongRoads, case .failed = roads { return "–" }
            return "…"
        }
        let e = estimate(imagery: imagery, terrain: counts, depth: z)
        if e.tiles == 0 { return "Nothing new" } // your track, already saved to this level
        let bytes = Format.bytes(e.bytes)
        return z <= typicalDepth ? "≈ \(bytes)" : "up to \(bytes)"
    }

    private var chosenEstimate: (tiles: Int, bytes: Int64)? {
        guard let counts = counts, let imagery = imageryCounts else { return nil }
        return estimate(imagery: imagery, terrain: counts, depth: chosenZoom)
    }

    private var roadLines: [[GeoPoint]]? {
        if case .loaded(let lines, _, _) = roads { return lines }
        return nil
    }

    /// Imagery tile counts per zoom: the whole area, or only along the roads.
    private var imageryCounts: [Int: Int]? {
        guard alongRoads else { return counts }
        if case .loaded(_, _, let c) = roads { return c }
        return nil
    }

    @MainActor
    private func loadRoads() async {
        roads = .loading
        let shape = area.shape
        let preset = presetID.flatMap(RoadPresets.load)
        do {
            let lines: [[GeoPoint]]
            if let track = track {
                lines = track.lines
            } else if let preset = preset {
                lines = preset.roads
            } else {
                lines = try await RoadFetcher.roads(in: shape)
            }
            // For your track, the levels the area already has are only needed for the new parts.
            let fresh = track.map { Roads.corridor($0.newLines) }
            let savedTo = track?.completeTo ?? -1
            let result = await Task.detached(priority: .userInitiated) { () -> (Double, [Int: Int]) in
                let whole = Roads.corridor(lines)
                var counts: [Int: Int] = [:]
                for z in 0...TileSources.imageryMaxZoom {
                    counts[z] = TileCoverage(shape: z <= savedTo ? fresh ?? whole : whole, zoom: z).tileCount
                }
                return (Roads.length(lines) / 1000, counts)
            }.value
            roads = .loaded(lines: lines, km: result.0, counts: result.1)
        } catch is CancellationError {
            roads = .notLoaded
        } catch {
            roads = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't get the roads. Try again.")
        }
    }

    private var areaSquareMeters: Double? {
        let a = area.shape.polygonAreaSquareMeters
        return a > 0 ? a : nil
    }

    private func estimate(imagery imageryCounts: [Int: Int], terrain terrainCounts: [Int: Int], depth: Int) -> (tiles: Int, bytes: Int64) {
        let imagery = (0...depth).reduce(0) { $0 + (imageryCounts[$1] ?? 0) }
        let terrain = (0...Terrarium.maxZoom).reduce(0) { $0 + (terrainCounts[$1] ?? 0) }
        let bytes = Double(imagery) * imageryTileBytes + Double(terrain) * terrainTileBytes
        return (imagery + terrain, Int64(bytes))
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundColor(.secondary).monospacedDigit()
        }
    }
}

/// "Where I've been": what a download along your track covers.
struct TrackDownload {
    /// The area's lines after this download: your track plus the parts of earlier trips already in the area.
    var lines: [[GeoPoint]]
    /// The parts not saved yet: the parts of your track away from the area's lines, or all of `lines`.
    var newLines: [[GeoPoint]]
    /// The track area's own settings, if it exists.
    var maxZoom: Int?
    var allowCellular: Bool?
    /// The zoom the track area is completely downloaded to, or nil (no area yet, or its download didn't finish).
    var completeTo: Int?
}
