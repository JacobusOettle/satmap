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
                    row(area.status == .complete ? "Downloaded to" : "Downloading to",
                        "zoom \(area.maxZoom)\(area.alongRoads ? ", along roads" : "")")
                    if let d = savedDepth {
                        row("Imagery found down to", "zoom \(d)")
                    }
                }

                Section {
                    Toggle("Allow mobile data", isOn: Binding(
                        get: { area.allowCellular },
                        set: { offline.setAllowCellular(areaID, $0) }))
                } footer: {
                    Text(area.status == .complete
                         ? "Used when you add a deeper level."
                         : "Without mobile data the download waits for Wi-Fi. Switch it on to carry on over mobile data; what is saved is kept.")
                }

                Section {
                    ForEach(zoomChoices, id: \.self) { z in
                        Button { chosenZoom = z } label: { zoomRow(z, area: area) }
                            // Deeper levels can be added; while a download runs, a lower one stops it there.
                            .disabled(z == area.maxZoom || (z < area.maxZoom && area.status == .complete))
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
                        Label(buttonTitle(area), systemImage: "arrow.down.circle.fill")
                            .font(.headline)
                    }
                    .disabled(chosenZoom == nil)
                }
            }
            .navigationTitle(area.name)
            .navigationBarTitleDisplayMode(.inline)
            .task(id: "\(area.maxZoom)-\(area.status.rawValue)") {
                // A lower level to stop at only makes sense while the download runs.
                if area.status == .complete, let z = chosenZoom, z < area.maxZoom { chosenZoom = nil }
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
            Text(z <= area.maxZoom ? (area.status == .complete ? "Downloaded" : "In this download")
                                   : extraSize(to: z, from: area.maxZoom))
                .foregroundColor(.secondary).monospacedDigit()
            Image(systemName: "checkmark")
                .font(.body.weight(.semibold))
                .foregroundColor(z == chosenZoom ? .accentColor : .secondary)
                .opacity(z == chosenZoom || (z <= area.maxZoom && area.status == .complete) ? 1 : 0)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
    }

    /// Extra download for the levels after `from` up to `z`: about, where Esri's imagery usually reaches; an
    /// upper limit deeper down.
    private func extraSize(to z: Int, from: Int) -> String {
        guard let counts = counts else { return "…" }
        let tiles = ((from + 1)...z).reduce(0) { $0 + (counts[$1] ?? 0) }
        let bytes = Format.bytes(Int64(Double(tiles) * imageryTileBytes))
        return z <= typicalDepth ? "+ ≈ \(bytes)" : "+ up to \(bytes)"
    }

    private func buttonTitle(_ area: OfflineManager.Area) -> String {
        guard let z = chosenZoom else { return "Choose a deeper level" }
        if z < area.maxZoom { return "Stop at zoom \(z)" }
        return "Download zoom \(area.maxZoom + 1)\(z > area.maxZoom + 1 ? "–\(z)" : "")"
    }

    private func footer(_ area: OfflineManager.Area) -> String {
        var s = "Choose a deeper level to add it: what is saved is kept and only the new levels are downloaded."
        if area.status != .complete {
            s += " While it downloads, choose a lower level to stop there instead."
        }
        if area.status == .complete, let d = savedDepth, d < area.maxZoom {
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
