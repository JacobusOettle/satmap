import Foundation

enum AppPaths {
    /// Application Support/SatMap
    static let root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = makeDirectory(base.appendingPathComponent("SatMap", isDirectory: true), excludeFromBackup: false)
        // The track and tiles are written while the phone is locked (after the first unlock since boot).
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                               ofItemAtPath: url.path)
        return url
    }()

    /// Offline areas (many GB): excluded from iCloud/iTunes backup.
    static let offline: URL = makeDirectory(root.appendingPathComponent("Offline", isDirectory: true), excludeFromBackup: true)

    static let kml: URL = makeDirectory(root.appendingPathComponent("KML", isDirectory: true), excludeFromBackup: false)

    static let track: URL = root.appendingPathComponent("track.bin")

    static func offlineArea(_ id: UUID) -> URL {
        makeDirectory(offline.appendingPathComponent(id.uuidString, isDirectory: true), excludeFromBackup: true)
    }

    @discardableResult
    private static func makeDirectory(_ url: URL, excludeFromBackup: Bool) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        if excludeFromBackup {
            var u = url
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? u.setResourceValues(values)
        }
        return url
    }

    /// Total size of the files in a directory, in bytes.
    static func size(of directory: URL) -> Int64 {
        guard let e = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.totalFileAllocatedSizeKey, .fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in e {
            let v = try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey, .fileSizeKey])
            total += Int64(v?.totalFileAllocatedSize ?? v?.fileSize ?? 0)
        }
        return total
    }

    static func freeDiskSpace() -> Int64? {
        let v = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage
    }
}

enum Format {
    static func distance(_ m: Double) -> String {
        if m < 1000 { return "\(Int(m.rounded())) m" }
        return String(format: "%.2f km", m / 1000)
    }

    static func bytes(_ b: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
    }

    /// "0.35 ha", "27.5 ha", "1,250 ha".
    static func hectares(_ squareMeters: Double) -> String {
        let ha = squareMeters / 10_000
        if ha >= 100 { return "\(count(Int(ha.rounded()))) ha" }
        return String(format: ha < 1 ? "%.2f ha" : "%.1f ha", ha)
    }

    /// Latitude, longitude in decimal degrees (6 decimals, about 10 cm): "-24.987654, 31.592134".
    static func coordinate(_ p: GeoPoint) -> String {
        String(format: "%.6f, %.6f", p.lat, p.lon)
    }

    static func count(_ n: Int) -> String {
        NumberFormatter.localizedString(from: NSNumber(value: n), number: .decimal)
    }
}
