import SwiftUI

/// Confirm an offline area: name, mobile data, and a size estimate. Imagery is always downloaded at the
/// highest detail available.
struct NewAreaView: View {
    @ObservedObject var model: AppModel
    let area: PendingArea
    /// Presented on its own (lasso) rather than pushed inside the Offline sheet (KML).
    var showsCancel = false

    @State private var name: String
    private let maxZoom = TileSources.imageryMaxZoom
    @State private var allowCellular = false
    /// Upper-bound tile counts per zoom level (computed in the background).
    @State private var counts: [Int: Int]?
    @State private var freeSpace: Int64?
    @Environment(\.dismiss) private var dismiss

    /// Rough average sizes from Esri JPEG and Terrarium PNG tiles.
    private let imageryTileBytes: Double = 17_000
    private let terrainTileBytes: Double = 45_000
    private let typicalDepth = 19

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

            Section("Estimate") {
                if let km2 = areaKm2 {
                    row("Area", String(format: "%.1f km²", km2))
                }
                if let counts = counts {
                    let typical = estimate(counts, depth: min(typicalDepth, maxZoom))
                    let worst = estimate(counts, depth: maxZoom)
                    row("If imagery goes to zoom \(min(typicalDepth, maxZoom))", "≈ \(Format.bytes(typical.bytes))")
                    if maxZoom > typicalDepth {
                        row("If it goes to zoom \(maxZoom)", "up to \(Format.bytes(worst.bytes))")
                    }
                    row("Tiles (most)", Format.count(worst.tiles))
                    if let free = freeSpace {
                        row("Free space", Format.bytes(free))
                    }
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
                                        maxZoom: maxZoom, allowCellular: allowCellular)
                    // Back to the map, where the download banner shows progress.
                    model.sheet = nil
                    dismiss()
                } label: {
                    Label("Download", systemImage: "arrow.down.circle.fill")
                        .font(.headline)
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
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
    }

    private var areaKm2: Double? {
        let a = area.shape.polygonAreaSquareMeters
        return a > 0 ? a / 1_000_000 : nil
    }

    private func estimate(_ counts: [Int: Int], depth: Int) -> (tiles: Int, bytes: Int64) {
        let imagery = (0...depth).reduce(0) { $0 + (counts[$1] ?? 0) }
        let terrain = (0...Terrarium.maxZoom).reduce(0) { $0 + (counts[$1] ?? 0) }
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
