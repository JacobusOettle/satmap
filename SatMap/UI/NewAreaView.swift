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
    var trackLines: [[GeoPoint]]?

    @State private var name: String
    /// The deepest zoom level to download; remembered for the next area.
    @AppStorage("offlineMaxZoom") private var maxZoom = TileSources.imageryMaxZoom
    /// Zoom levels offered, most detailed first: from about 3 cm to 5 m per pixel.
    private let zoomChoices = Array((15...TileSources.imageryMaxZoom).reversed())
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

    init(model: AppModel, area: PendingArea, showsCancel: Bool = false, presetID: String? = nil, trackLines: [[GeoPoint]]? = nil) {
        self.model = model
        self.area = area
        self.showsCancel = showsCancel
        self.presetID = presetID
        self.trackLines = trackLines
        _name = State(initialValue: area.name)
        _alongRoads = State(initialValue: presetID != nil || trackLines != nil)
    }

    /// Imagery always goes along given lines (a shipped road list or your track), never the whole area.
    private var fixedLines: Bool { presetID != nil || trackLines != nil }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Area name", text: $name)
            }

            detailSection

            if trackLines != nil {
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
                    if let lines = trackLines {
                        model.startTrackDownload(name: trimmed, lines: lines, maxZoom: chosenZoom, allowCellular: allowCellular)
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
            let shape = area.shape
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
                Button { maxZoom = z } label: { zoomRow(z) }
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
            if km > 0 {
                row(trackLines != nil ? "Your track" : "Roads and tracks",
                    km < 10 ? String(format: "%.1f km", km) : "\(Format.count(Int(km.rounded()))) km")
            } else {
                Text("No roads or tracks are mapped in this area.").foregroundColor(.orange)
            }
        case .failed(let message):
            Text(message).foregroundColor(.orange)
            Button("Try again") { Task { await loadRoads() } }
        }
    }

    private var chosenZoom: Int {
        min(max(maxZoom, zoomChoices.last!), zoomChoices.first!)
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
        let bytes = Format.bytes(estimate(imagery: imagery, terrain: counts, depth: z).bytes)
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
            if let track = trackLines {
                lines = track
            } else if let preset = preset {
                lines = preset.roads
            } else {
                lines = try await RoadFetcher.roads(in: shape)
            }
            let result = await Task.detached(priority: .userInitiated) {
                (Roads.length(lines) / 1000, TileCoverage.tileCounts(shape: Roads.corridor(lines), zooms: 0...TileSources.imageryMaxZoom))
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
