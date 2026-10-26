import SwiftUI

/// Confirm an offline area: name, mobile data, only along roads, and a size estimate. Imagery is always
/// downloaded at the highest detail available.
struct NewAreaView: View {
    @ObservedObject var model: AppModel
    let area: PendingArea
    /// Presented on its own (lasso) rather than pushed inside the Offline sheet (KML).
    var showsCancel = false

    @State private var name: String
    private let maxZoom = TileSources.imageryMaxZoom
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

    init(model: AppModel, area: PendingArea, showsCancel: Bool = false) {
        self.model = model
        self.area = area
        self.showsCancel = showsCancel
        _name = State(initialValue: area.name)
    }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Area name", text: $name)
            }

            Section {
                Toggle("Allow mobile data", isOn: $allowCellular)
            } footer: {
                Text("Satellite imagery is saved at the highest detail Esri has for each spot (often zoom 18–19 on farms, 20–21 in towns), plus elevation for contours and heights. Without mobile data the download waits for Wi-Fi. Keep SatMap open while it downloads (the screen stays on); it pauses in the background and continues when you come back.")
            }

            Section {
                Toggle("Only along roads", isOn: $alongRoads)
                if alongRoads {
                    roadsStatus
                }
            } footer: {
                Text("Saves satellite imagery only \(Int(Roads.corridorMeters)) m to each side of every road and track in the area, including gravel and farm roads, as mapped on OpenStreetMap. Elevation for contours and heights is still saved for the whole area.")
            }

            Section("Estimate") {
                if let km2 = areaKm2 {
                    row("Area", String(format: "%.1f km²", km2))
                }
                if let counts = counts, let imagery = imageryCounts {
                    let typical = estimate(imagery: imagery, terrain: counts, depth: min(typicalDepth, maxZoom))
                    let worst = estimate(imagery: imagery, terrain: counts, depth: maxZoom)
                    row("If imagery goes to zoom \(min(typicalDepth, maxZoom))", "≈ \(Format.bytes(typical.bytes))")
                    if maxZoom > typicalDepth {
                        row("If it goes to zoom \(maxZoom)", "up to \(Format.bytes(worst.bytes))")
                    }
                    row("Tiles (most)", Format.count(worst.tiles))
                    if let free = freeSpace {
                        row("Free space", Format.bytes(free))
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

            Section {
                Button {
                    model.startDownload(PendingArea(name: name.trimmingCharacters(in: .whitespaces), shape: area.shape),
                                        roads: alongRoads ? roadLines : nil, maxZoom: maxZoom, allowCellular: allowCellular)
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
            let result = await Task.detached(priority: .userInitiated) {
                (TileCoverage.tileCounts(shape: shape, zooms: 0...TileSources.imageryMaxZoom), AppPaths.freeDiskSpace())
            }.value
            freeSpace = result.1
            counts = result.0
        }
        .task(id: alongRoads) {
            if alongRoads, case .notLoaded = roads { await loadRoads() }
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
                row("Roads and tracks", String(format: km < 10 ? "%.1f km" : "%.0f km", km))
            } else {
                Text("No roads or tracks are mapped in this area.").foregroundColor(.orange)
            }
        case .failed(let message):
            Text(message).foregroundColor(.orange)
            Button("Try again") { Task { await loadRoads() } }
        }
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
        do {
            let lines = try await RoadFetcher.roads(in: shape)
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

    private var areaKm2: Double? {
        let a = area.shape.polygonAreaSquareMeters
        return a > 0 ? a / 1_000_000 : nil
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
