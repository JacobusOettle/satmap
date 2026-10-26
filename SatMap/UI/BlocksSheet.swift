import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let kml = UTType(importedAs: "com.google.earth.kml")
    static let kmz = UTType(importedAs: "com.google.earth.kmz")
}

/// Imported KML/KMZ files with the blocks shown on the map.
struct BlocksSheet: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: KMLLibrary
    @State private var showImporter = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if library.files.isEmpty {
                    Text("Import a .kml or .kmz file to show your blocks on the map.")
                        .foregroundColor(.secondary)
                }
                ForEach(library.files) { f in
                    Button {
                        if let b = f.document.bounds { model.map?.zoom(to: b) }
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name).foregroundColor(.primary)
                            Text("\(f.document.features.count) shapes · tap to show on map")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .onDelete { offsets in
                    offsets.map { library.files[$0].id }.forEach(library.delete)
                }
            }
            .navigationTitle("Blocks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showImporter = true
                    } label: {
                        Label("Import KML", systemImage: "plus")
                    }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.kml, .kmz, .xml, .zip],
                          allowsMultipleSelection: true) { result in
                if case .success(let urls) = result {
                    urls.forEach { library.importFile(at: $0) }
                }
            }
            .alert("Couldn't import", isPresented: Binding(get: { library.importError != nil },
                                                           set: { if !$0 { library.importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(library.importError ?? "")
            }
        }
    }
}
