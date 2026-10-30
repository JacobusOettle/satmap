import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let kml = UTType(importedAs: "com.google.earth.kml")
    static let kmz = UTType(importedAs: "com.google.earth.kmz")
    static let gpx = UTType(importedAs: "com.topografix.gpx")
}

/// Imported KML, KMZ and GPX files: each one's blocks switched on or off the map, and whether they keep the files'
/// colours (GPX has none: always white).
struct BlocksSheet: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: KMLLibrary
    @State private var showImporter = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if library.files.isEmpty {
                    Text("Import a .kml, .kmz or .gpx file to show your blocks on the map.")
                        .foregroundColor(.secondary)
                }
                Section {
                    ForEach(library.files) { f in
                        HStack(spacing: 12) {
                            Button {
                                library.setShown(f.id, true)
                                if let b = f.document.bounds { model.map?.zoom(to: b) }
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.name).foregroundColor(.primary)
                                    Text("\(Format.fileContents(f.shapes)) · tap to show on map")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            Toggle(isOn: Binding(get: { f.isShown }, set: { library.setShown(f.id, $0) })) {
                                Text("Show \(f.name) on the map")
                            }
                            .labelsHidden()
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { library.files[$0].id }.forEach(library.delete)
                    }
                } footer: {
                    if !library.files.isEmpty {
                        Text("Switch a file off to take its blocks off the map.")
                    }
                }
                if !library.files.isEmpty {
                    Section {
                        Button {
                            library.useFileColours.toggle()
                        } label: {
                            Label {
                                Text("Use the colours in the files").foregroundColor(.primary)
                            } icon: {
                                Image(systemName: library.useFileColours ? "checkmark.square.fill" : "square")
                                    .font(.title3)
                            }
                        }
                        .accessibilityValue(Text(library.useFileColours ? "On" : "Off"))
                        .accessibilityIdentifier("useFileColours")
                    } footer: {
                        Text("Off: every block is drawn in white.")
                    }
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
                        Label("Import KML or GPX", systemImage: "plus")
                    }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.kml, .kmz, .gpx, .xml, .zip],
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
