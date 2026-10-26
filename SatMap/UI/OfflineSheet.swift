import SwiftUI
import UniformTypeIdentifiers

/// Offline areas: choose a new area (lasso or KML), manage downloads, and back them up.
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
                    Button {
                        model.startDrawingArea()
                    } label: {
                        Label("Draw an area on the map", systemImage: "lasso")
                    }
                    NavigationLink {
                        KMLAreaPicker(model: model, library: library)
                    } label: {
                        Label("Use the blocks in a KML file", systemImage: "square.dashed")
                    }
                } footer: {
                    Text("Saves satellite imagery at the highest detail available plus elevation data for contours and heights, so the map works with no signal.")
                }

                Section("Offline areas") {
                    if offline.areas.isEmpty {
                        Text("No offline areas yet.").foregroundColor(.secondary)
                    }
                    ForEach(offline.areas) { area in
                        AreaRow(area: area, progress: offline.activeID == area.id ? offline.progress : nil,
                                canDownloadNow: offline.canDownloadNow(area),
                                onPause: { offline.pause(area.id) },
                                onResume: { offline.resume(area.id) })
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if let b = area.shape.bounds { model.map?.zoom(to: b) }
                                dismiss()
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
                        HStack {
                            ProgressView()
                            Text(busy).foregroundColor(.secondary)
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
                    Text("Map data: \(TileSources.attribution) · Roads: © OpenStreetMap contributors")
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
                Text("\(Format.bytes(area.bytesOnDisk)) · up to zoom \(area.maxZoom)\(area.roads != nil ? " · along roads" : "")\(area.allowCellular ? " · mobile data allowed" : "")")
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

/// Pick an imported KML file to download the area around its blocks.
private struct KMLAreaPicker: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: KMLLibrary

    var body: some View {
        List {
            if library.files.isEmpty {
                Text("Import a KML file first (Blocks button on the map).")
                    .foregroundColor(.secondary)
            }
            ForEach(library.files) { f in
                NavigationLink {
                    NewAreaView(model: model, area: PendingArea(name: f.name, shape: f.document.coverageShape(bufferMeters: 150)))
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.name)
                        Text("\(f.document.features.count) shapes · includes 150 m around them")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Choose KML")
    }
}
