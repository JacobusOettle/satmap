import Foundation

/// Backups of offline areas as plain MBTiles files, one per layer ("<area> - satellite.mbtiles" and
/// "<area> - elevation.mbtiles"). Each file carries the area's settings in its metadata, so the files can be
/// restored into the app later, and other map apps can open them meanwhile.
enum OfflineBackup {
    struct Layer {
        /// File name inside the area's folder.
        let fileName: String
        /// Stored in the file's metadata to tell the layers apart on restore.
        let key: String
        /// Part of the exported file's name.
        let label: String
    }

    static let areaKey = "satmap_area"
    static let layerKey = "satmap_layer"

    enum Failure: LocalizedError {
        case busy(String)
        case notABackup(String)

        var errorDescription: String? {
            switch self {
            case .busy(let name): return "Couldn't prepare \(name) for export. Try again in a moment."
            case .notABackup(let name): return "\(name) isn't a SatMap backup."
            }
        }
    }

    /// Writes the area's settings into its tile files, folds their write-ahead logs into them, and puts them into
    /// `folder` under readable names. Hard links: no copy, no extra space (they share the files' storage). Layers
    /// not downloaded yet are skipped. `usedNames` avoids two areas getting the same file names.
    static func export(areaFolder: URL, layers: [Layer], name: String, areaJSON: String, into folder: URL,
                       usedNames: inout Set<String>) throws -> [URL] {
        let fm = FileManager.default
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        var base = fileSafe(name)
        var n = 2
        while usedNames.contains(base.lowercased()) {
            base = "\(fileSafe(name)) (\(n))"
            n += 1
        }
        usedNames.insert(base.lowercased())

        var out: [URL] = []
        for layer in layers {
            let source = areaFolder.appendingPathComponent(layer.fileName)
            guard fm.fileExists(atPath: source.path) else { continue }
            let store = try MBTiles(url: source, create: false)
            store.setMetadata([areaKey: areaJSON, layerKey: layer.key])
            let ready = store.checkpoint()
            store.close()
            guard ready else { throw Failure.busy(name) }
            let target = folder.appendingPathComponent("\(base) - \(layer.label).mbtiles")
            try? fm.removeItem(at: target)
            do {
                try fm.linkItem(at: source, to: target)
            } catch {
                try fm.copyItem(at: source, to: target)
            }
            out.append(target)
        }
        return out
    }

    /// The layer key and area settings stored in an exported file (opened from the app's own storage).
    static func inspect(_ url: URL) throws -> (layerKey: String, areaJSON: Data) {
        let store = try MBTiles(url: url, create: false)
        defer { store.close() }
        guard let layer = store.metadata(layerKey), let json = store.metadata(areaKey) else {
            throw Failure.notABackup(url.lastPathComponent)
        }
        return (layer, Data(json.utf8))
    }

    /// The name without characters that file systems reject.
    static func fileSafe(_ name: String) -> String {
        let cleaned = name.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|").union(.controlCharacters))
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        return cleaned.isEmpty ? "Offline area" : String(cleaned.prefix(80))
    }
}
