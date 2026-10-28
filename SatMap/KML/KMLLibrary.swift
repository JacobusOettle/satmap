import Foundation

/// Imported KML/KMZ files (farm blocks etc.), stored in the app and shown on the map (each can be switched off).
final class KMLLibrary: ObservableObject {
    struct Entry: Codable, Identifiable, Equatable {
        let id: UUID
        var name: String
        var fileName: String
        /// Switched off: not on the map. (Optional so the index of an earlier version still reads.)
        var hidden: Bool?
    }

    struct File: Identifiable {
        let entry: Entry
        let document: KMLDocument

        var id: UUID { entry.id }
        var name: String { entry.name }
        var isShown: Bool { entry.hidden != true }
    }

    @Published private(set) var files: [File] = []
    @Published var importError: String?
    /// Blocks in their files' own colours (on unless switched off); else all in the app's white.
    @Published var useFileColours = UserDefaults.standard.object(forKey: "kmlFileColours") == nil
        || UserDefaults.standard.bool(forKey: "kmlFileColours") {
        didSet { UserDefaults.standard.set(useFileColours, forKey: "kmlFileColours") }
    }

    private let indexURL = AppPaths.kml.appendingPathComponent("index.json")
    /// Every imported file, in order. Loaded at once at startup so an import arriving early ("Open in SatMap"
    /// launching the app) can't overwrite it; the documents are parsed in the background.
    private var entries: [Entry] = []

    init() {
        if let data = try? Data(contentsOf: indexURL), let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = saved
        }
        let toLoad = entries
        DispatchQueue.global(qos: .userInitiated).async {
            var loaded: [File] = []
            for e in toLoad {
                guard let d = try? Data(contentsOf: AppPaths.kml.appendingPathComponent(e.fileName)),
                      let doc = try? KMLParser.parse(d) else { continue }
                loaded.append(File(entry: e, document: doc))
            }
            DispatchQueue.main.async {
                // Keep anything imported meanwhile, skip files deleted meanwhile, and take any change made meanwhile.
                let current = Dictionary(uniqueKeysWithValues: self.entries.map { ($0.id, $0) })
                self.files = loaded.compactMap { f in current[f.id].map { File(entry: $0, document: f.document) } }
                    + self.files.filter { f in !loaded.contains { $0.id == f.id } }
            }
        }
    }

    /// Imports a .kml/.kmz picked in Files or shared into the app ("Open in SatMap").
    func importFile(at url: URL, completion: ((File?) -> Void)? = nil) {
        let displayName = url.deletingPathExtension().lastPathComponent
        DispatchQueue.global(qos: .userInitiated).async {
            let scoped = url.startAccessingSecurityScopedResource()
            var data: Data?
            var readError: Error?
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [.withoutChanges], error: &coordinationError) { readURL in
                do { data = try Data(contentsOf: readURL) } catch { readError = error }
            }
            if scoped { url.stopAccessingSecurityScopedResource() }
            // A copy handed to the app (share sheet / "Open in") lives in our own container
            // (Documents/Inbox or tmp/…-Inbox); we keep our own copy, so remove it.
            let home = URL(fileURLWithPath: NSHomeDirectory()).resolvingSymlinksInPath().path
            if url.resolvingSymlinksInPath().path.hasPrefix(home + "/") { try? FileManager.default.removeItem(at: url) }

            guard let data = data else {
                let reason = (readError ?? coordinationError)?.localizedDescription ?? "unknown error"
                DispatchQueue.main.async {
                    self.importError = "Couldn't read \(url.lastPathComponent): \(reason)"
                    completion?(nil)
                }
                return
            }
            do {
                let doc = try KMLParser.parse(data)
                let id = UUID()
                let isZip = data.prefix(2) == Data([0x50, 0x4B])
                let entry = Entry(id: id, name: displayName, fileName: "\(id.uuidString).\(isZip ? "kmz" : "kml")")
                try data.write(to: AppPaths.kml.appendingPathComponent(entry.fileName), options: .atomic)
                let file = File(entry: entry, document: doc)
                DispatchQueue.main.async {
                    self.entries.append(entry)
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

    /// Shows a file's blocks on the map, or takes them off.
    func setShown(_ id: UUID, _ shown: Bool) {
        guard let i = entries.firstIndex(where: { $0.id == id }), (entries[i].hidden != true) != shown else { return }
        entries[i].hidden = shown ? nil : true
        if let j = files.firstIndex(where: { $0.id == id }) {
            files[j] = File(entry: entries[i], document: files[j].document)
        }
        saveIndex()
    }

    func delete(_ id: UUID) {
        guard let i = entries.firstIndex(where: { $0.id == id }) else { return }
        let e = entries.remove(at: i)
        files.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: AppPaths.kml.appendingPathComponent(e.fileName))
        saveIndex()
    }

    private func saveIndex() {
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }
}
