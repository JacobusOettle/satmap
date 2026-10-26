import SwiftUI

/// Confirm an offline area: name, level of detail, mobile data, and a size estimate.
struct NewAreaView: View {
    @ObservedObject var model: AppModel
    let area: PendingArea

    @State private var name: String
    @State private var maxZoom = TileSources.imageryMaxZoom
    @State private var allowCellular = false
    /// Upper-bound tile counts per zoom level (computed in the background).
    @State private var counts: [Int: Int]?
    @Environment(\.dismiss) private var dismiss

    /// Rough average sizes from Esri JPEG and Terrarium PNG tiles.
    private let imageryTileBytes: Double = 17_000
    private let terrainTileBytes: Double = 45_000
    private let typicalDepth = 19

    init(model: AppModel, area: PendingArea) {
        self.model = model
        self.area = area
        _name = State(initialValue: area.name)
    }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Area name", text: $name)
            }

            Section {
                Picker("Detail", selection: $maxZoom) {
                    ForEach(Array((17...TileSources.imageryMaxZoom).reversed()), id: \.self) { z in
                        Text(detailLabel(z)).tag(z)
                    }
                }
                Toggle("Allow mobile data", isOn: $allowCellular)
            } footer: {
                Text("Imagery is fetched as deep as Esri has it, up to the chosen zoom (often zoom 18–19 on farms, 20–21 in towns). Where a zoom doesn't exist, nothing is downloaded for it. Without mobile data the download waits for Wi-Fi.")
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
                    if let free = AppPaths.freeDiskSpace() {
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
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.discardPendingArea()
                    dismiss()
                }
            }
        }
        .task {
            let shape = area.shape
            let result = await Task.detached(priority: .userInitiated) {
                TileCoverage.tileCounts(shape: shape, zooms: 0...TileSources.imageryMaxZoom)
            }.value
            counts = result
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

    private func detailLabel(_ z: Int) -> String {
        // Ground size of one imagery pixel at this zoom and latitude.
        let lat = area.shape.geometryBounds?.center.lat ?? 0
        let metres = Geo.metersPerWorldUnit(atLatitude: lat) / Double(256 << z)
        let size = metres >= 1 ? String(format: "%.1f m", metres) : "\(Int((metres * 100).rounded())) cm"
        let base = "Zoom \(z) · \(size) per pixel"
        return z == TileSources.imageryMaxZoom ? "Maximum (\(base))" : base
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundColor(.secondary).monospacedDigit()
        }
    }
}
