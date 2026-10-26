import Foundation

/// Imported KML/KMZ files (farm blocks etc.), stored in the app and shown on the map.
final class KMLLibrary: ObservableObject {
    struct Entry: Codable, Identifiable, Equatable {
        let id: UUID
        var name: String
        var fileName: String
    }

    struct File: Identifiable {
        let entry: Entry
        let document: KMLDocument

        var id: UUID { entry.id }
        var name: String { entry.name }
    }

    @Published private(set) var files: [File] = []
    @Published var importError: String?

    private let indexURL = AppPaths.kml.appendingPathComponent("index.json")

    init() {
        let indexURL = self.indexURL
        DispatchQueue.global(qos: .userInitiated).async {
            guard let data = try? Data(contentsOf: indexURL),
                  let entries = try? JSONDecoder().decode([Entry].self, from: data) else { return }
            var loaded: [File] = []
            for e in entries {
                guard let d = try? Data(contentsOf: AppPaths.kml.appendingPathComponent(e.fileName)),
                      let doc = try? KMLParser.parse(d) else { continue }
                loaded.append(File(entry: e, document: doc))
            }
            DispatchQueue.main.async { self.files = loaded }
        }
    }

    /// Imports a .kml/.kmz picked in Files or shared into the app ("Open in SatMap").
    func importFile(at url: URL, completion: ((File?) -> Void)? = nil) {
        let scoped = url.startAccessingSecurityScopedResource()
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            if scoped { url.stopAccessingSecurityScopedResource() }
            importError = "Couldn't read \(url.lastPathComponent): \(error.localizedDescription)"
            completion?(nil)
            return
        }
        if scoped { url.stopAccessingSecurityScopedResource() }
        // Files shared into the app land in Documents/Inbox; we keep our own copy.
        if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }

        let displayName = url.deletingPathExtension().lastPathComponent
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let doc = try KMLParser.parse(data)
                let id = UUID()
                let isZip = data.prefix(2) == Data([0x50, 0x4B])
                let entry = Entry(id: id, name: displayName, fileName: "\(id.uuidString).\(isZip ? "kmz" : "kml")")
                try data.write(to: AppPaths.kml.appendingPathComponent(entry.fileName), options: .atomic)
                let file = File(entry: entry, document: doc)
                DispatchQueue.main.async {
                    self.files.append(file)
                    self.saveIndex()
                    completion?(file)
                }
            } catch {
                DispatchQueue.main.async {
                    self.importError = "\(url.lastPathComponent): \(error.localizedDescription)"
                    completion?(nil)
                }
            }
        }
    }

    func delete(_ id: UUID) {
        guard let i = files.firstIndex(where: { $0.id == id }) else { return }
        let f = files.remove(at: i)
        try? FileManager.default.removeItem(at: AppPaths.kml.appendingPathComponent(f.entry.fileName))
        saveIndex()
    }

    private func saveIndex() {
        if let data = try? JSONEncoder().encode(files.map(\.entry)) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }
}
