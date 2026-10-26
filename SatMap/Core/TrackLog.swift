import Foundation

struct TrackPoint: Equatable {
    var lat: Double
    var lon: Double
    /// Seconds since 1970.
    var time: Double
    /// True if the dotted line should not connect this point to the previous one (e.g. after a long gap).
    var startsSegment: Bool

    var geo: GeoPoint { GeoPoint(lat: lat, lon: lon) }
}

/// Append-only binary log of the recorded track (32 bytes per point), cheap enough to write from the background.
final class TrackLog {
    static let recordSize = 32

    private let url: URL
    private let queue = DispatchQueue(label: "satmap.tracklog")
    private var handle: FileHandle?

    init(url: URL) {
        self.url = url
    }

    /// Reads every complete record. Call once at startup.
    func loadAll() -> [TrackPoint] {
        queue.sync {
            guard let data = try? Data(contentsOf: url) else { return [] }
            let count = data.count / Self.recordSize
            var out: [TrackPoint] = []
            out.reserveCapacity(count)
            data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                for i in 0..<count {
                    let base = i * Self.recordSize
                    let lat = Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: base, as: UInt64.self)))
                    let lon = Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: base + 8, as: UInt64.self)))
                    let time = Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: base + 16, as: UInt64.self)))
                    let flags = UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: base + 24, as: UInt64.self))
                    guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { continue }
                    out.append(TrackPoint(lat: lat, lon: lon, time: time, startsSegment: flags & 1 == 1))
                }
            }
            return out
        }
    }

    func append(_ p: TrackPoint) {
        var bytes = Data(capacity: Self.recordSize)
        for v in [p.lat.bitPattern, p.lon.bitPattern, p.time.bitPattern, UInt64(p.startsSegment ? 1 : 0)] {
            withUnsafeBytes(of: v.littleEndian) { bytes.append(contentsOf: $0) }
        }
        let record = bytes
        queue.async { [weak self] in
            guard let self = self, let h = self.openHandle() else { return }
            do {
                try h.write(contentsOf: record)
            } catch {
                try? h.close()
                self.handle = nil
            }
        }
    }

    func clear() {
        queue.sync {
            try? handle?.close()
            handle = nil
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// Opens the file for appending, trimming any partial record left by an interrupted write.
    private func openHandle() -> FileHandle? {
        if let h = handle { return h }
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            _ = fm.createFile(atPath: url.path, contents: nil)
        }
        guard let h = try? FileHandle(forWritingTo: url) else { return nil }
        do {
            let end = try h.seekToEnd()
            let aligned = end - end % UInt64(Self.recordSize)
            if aligned != end {
                try h.truncate(atOffset: aligned)
                try h.seek(toOffset: aligned)
            }
        } catch {
            try? h.close()
            return nil
        }
        handle = h
        return h
    }
}
