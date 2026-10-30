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
    /// "850 m", "1.25 km"; "850 ft", "1.25 mi"; "0.46 NM" (`DistanceUnit`).
    static func distance(_ m: Double, _ unit: DistanceUnit = .metric) -> String {
        unit.format(m)
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

    /// Ground size of one satellite image pixel at a zoom level and latitude: "25 cm per pixel", "1.0 m per pixel".
    static func groundResolution(zoom z: Int, latitude: Double) -> String {
        let m = 156_543.03 * cos(latitude * .pi / 180) / Double(1 << z)
        if m < 0.95 { return "\(Int((m * 100).rounded())) cm per pixel" }
        return String(format: m < 10 ? "%.1f m per pixel" : "%.0f m per pixel", m)
    }

    /// Latitude, longitude in decimal degrees (6 decimals, about 10 cm): "-24.987654, 31.592134".
    static func coordinate(_ p: GeoPoint) -> String {
        String(format: "%.6f, %.6f", p.lat, p.lon)
    }

    /// A height in whole metres or feet ("1 250 m", "4 101 ft").
    static func height(_ m: Double, _ unit: HeightUnit = .metres) -> String {
        "\(count(unit.whole(m))) \(unit.symbol)"
    }

    /// A height difference without its sign ("12 m", "39 ft").
    static func heightDifference(_ m: Double, _ unit: HeightUnit) -> String {
        "\(count(unit.whole(abs(m)))) \(unit.symbol)"
    }

    static func count(_ n: Int) -> String {
        NumberFormatter.localizedString(from: NSNumber(value: n), number: .decimal)
    }

    /// What a KML or GPX file holds, as Blocks and "Choose a file" show it: "2 shapes · 1 line · 3 points", only the
    /// kinds it has (a file has at least one). Its shapes are its areas (`FileShapes`).
    static func fileContents(_ s: FileShapes) -> String {
        func part(_ n: Int, _ one: String, _ many: String) -> String? {
            n == 0 ? nil : "\(Format.count(n)) \(n == 1 ? one : many)"
        }
        return [part(s.areas.count, "shape", "shapes"), part(s.lines.count, "line", "lines"),
                part(s.dots.count, "point", "points")].compactMap { $0 }.joined(separator: " · ")
    }
}
