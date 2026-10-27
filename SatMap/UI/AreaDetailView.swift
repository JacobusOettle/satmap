import SwiftUI

/// One offline area: show it on the map, see how deep its imagery goes, and download it to a deeper zoom level
/// (only the new levels are fetched; what is saved is kept).
struct AreaDetailView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var offline: OfflineManager
    let areaID: UUID
    @Environment(\.dismiss) private var dismiss

    @State private var chosenZoom: Int?
    /// Imagery tile counts per zoom for the area (along its roads if it has them), for the levels it doesn't have.
    @State private var counts: [Int: Int]?
    @State private var savedDepth: Int?

    private let imageryTileBytes: Double = 17_000
    private let typicalDepth = 19
    private let zoomChoices = Array((15...TileSources.imageryMaxZoom).reversed())

    private var area: OfflineManager.Area? { offline.areas.first { $0.id == areaID } }

    var body: some View {
        if let area = area {
            Form {
                Section {
                    Button {
                        if let b = area.shape.bounds { model.map?.zoom(to: b) }
                        model.sheet = nil
                    } label: {
                        Label("Show on the map", systemImage: "map")
                    }
                    row("Saved", Format.bytes(area.bytesOnDisk))
                    row("Downloaded to", "zoom \(area.maxZoom)\(area.alongRoads ? ", along roads" : "")")
                    if let d = savedDepth {
                        row("Imagery found down to", "zoom \(d)")
                    }
                }

                Section {
                    ForEach(zoomChoices, id: \.self) { z in
                        Button { chosenZoom = z } label: { zoomRow(z, area: area) }
                            .disabled(z <= area.maxZoom)
                            .accessibilityAddTraits(z == chosenZoom ? .isSelected : [])
                    }
                } header: {
                    Text("Detail")
                } footer: {
                    Text(footer(area))
                }

                Section {
                    Button {
                        if let z = chosenZoom { offline.changeMaxZoom(areaID, to: z) }
                        chosenZoom = nil
                    } label: {
                        Label(chosenZoom.map { "Download zoom \(area.maxZoom + 1)\($0 > area.maxZoom + 1 ? "–\($0)" : "")" } ?? "Choose a deeper level",
                              systemImage: "arrow.down.circle.fill")
                            .font(.headline)
                    }
                    .disabled(chosenZoom == nil)
                }
            }
            .navigationTitle(area.name)
            .navigationBarTitleDisplayMode(.inline)
            .task(id: "\(area.maxZoom)-\(area.status.rawValue)") {
                savedDepth = await offline.savedImageryDepth(areaID)
                guard area.maxZoom < TileSources.imageryMaxZoom else { return }
                let shape = area.roadLines.map(Roads.corridor) ?? area.shape
                let zooms = (area.maxZoom + 1)...TileSources.imageryMaxZoom
                counts = await Task.detached(priority: .userInitiated) { TileCoverage.tileCounts(shape: shape, zooms: zooms) }.value
            }
        } else {
            Text("This area has been deleted.").foregroundColor(.secondary)
        }
    }

    private func zoomRow(_ z: Int, area: OfflineManager.Area) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Zoom \(z)").foregroundColor(z <= area.maxZoom ? .secondary : .primary)
                Text(Format.groundResolution(zoom: z, latitude: area.shape.bounds?.center.lat ?? 0))
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Text(z <= area.maxZoom ? "Downloaded" : extraSize(to: z, from: area.maxZoom))
                .foregroundColor(.secondary).monospacedDigit()
            Image(systemName: "checkmark")
                .font(.body.weight(.semibold))
                .foregroundColor(z <= area.maxZoom ? .secondary : .accentColor)
                .opacity(z <= area.maxZoom || z == chosenZoom ? 1 : 0)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
    }

    /// Extra download for the levels after `from` up to `z`: about, where Esri's imagery usually reaches; an
    /// upper limit deeper down.
    private func extraSize(to z: Int, from: Int) -> String {
        guard let counts = counts else { return "…" }
        let tiles = ((from + 1)...z).reduce(0) { $0 + (counts[$1] ?? 0) }
        let bytes = "+ " + Format.bytes(Int64(Double(tiles) * imageryTileBytes))
        return z <= typicalDepth ? "≈ \(bytes)" : "up to \(bytes)"
    }

    private func footer(_ area: OfflineManager.Area) -> String {
        var s = "Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded."
        if let d = savedDepth, d < area.maxZoom {
            s += " Esri's imagery here stops at zoom \(d), so deeper levels will add little or nothing."
        }
        return s
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundColor(.secondary).monospacedDigit()
        }
    }
}
