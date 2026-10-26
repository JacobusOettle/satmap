import Foundation

enum TileFetchResult {
    case data(Data)
    /// The server has no tile at this address (e.g. HTTP 404 with Esri `blankTile=false`).
    case missing
    /// A transient failure worth retrying (timeout, 5xx, 429).
    case failed
    /// The device has no network; the job stops and resumes later.
    case offline
}

protocol TileFetching: AnyObject {
    func fetch(_ tile: TileID) async -> TileFetchResult
}

/// One layer of an offline download (satellite imagery or elevation).
struct DownloadLayer {
    let name: String
    let store: MBTiles
    let fetcher: TileFetching
    let zooms: ClosedRange<Int>
    /// Only request a tile if its parent exists. Imagery providers stop at different zooms in different
    /// places (rural areas often at z18, towns at z20+); a missing parent means every child is missing too,
    /// so this avoids millions of pointless requests when downloading "up to the maximum".
    let pruneByParent: Bool
    /// Download this shape instead of the job's (imagery only along roads); nil = the job's shape.
    var shape: CoverageShape? = nil
}

struct DownloadProgress: Equatable {
    var layer: String = ""
    var zoom: Int = 0
    var maxZoom: Int = 0
    /// Tiles handled at the current zoom level / tiles to handle at this level.
    var levelDone: Int = 0
    var levelTotal: Int = 0
    /// Tiles fetched during this run.
    var fetched: Int = 0
    var bytes: Int64 = 0
    var missing: Int = 0
    var failed: Int = 0
}

enum DownloadOutcome: Equatable {
    /// Every level was processed. `failed` tiles (and their children) are retried by running the job again.
    case completed(failed: Int)
    case cancelled
    case offline
    /// Tiles could not be saved (usually the phone is full).
    case storageError(String)
}

/// Downloads every tile of a coverage shape, level by level, skipping tiles already stored, so
/// running it again resumes where it left off.
final class TileDownloadJob {
    private struct Packed {
        let x: Int32
        let y: Int32
    }

    let shape: CoverageShape
    let layers: [DownloadLayer]
    let concurrency: Int
    let maxAttempts: Int
    /// Seconds to wait before retry n (n = 1, 2, ...).
    let retryDelay: (Int) -> Double
    /// This many failed tiles in a row (after retries) with no success means the connection is down even if the
    /// phone says it is online (e.g. Wi-Fi up but the internet behind it is not): stop as `.offline` instead of
    /// marking the rest of the area failed.
    let outageThreshold: Int

    init(shape: CoverageShape, layers: [DownloadLayer], concurrency: Int = 8, maxAttempts: Int = 4,
         outageThreshold: Int = 16,
         retryDelay: @escaping (Int) -> Double = { [1, 3, 8, 15][min($0 - 1, 3)] }) {
        self.shape = shape
        self.layers = layers
        self.concurrency = max(1, concurrency)
        self.maxAttempts = max(1, maxAttempts)
        self.outageThreshold = max(1, outageThreshold)
        self.retryDelay = retryDelay
    }

    func run(progress report: @escaping (DownloadProgress) -> Void) async -> DownloadOutcome {
        var progress = DownloadProgress()
        var lastReport = Date.distantPast
        var failuresInARow = 0
        func emit(force: Bool = false) {
            let now = Date()
            if force || now.timeIntervalSince(lastReport) >= 0.25 {
                lastReport = now
                report(progress)
            }
        }

        for layer in layers {
            progress.layer = layer.name
            progress.maxZoom = layer.zooms.upperBound
            defer { layer.store.checkpoint() }

            for z in layer.zooms {
                if Task.isCancelled { return .cancelled }
                let coverage = TileCoverage(shape: layer.shape ?? shape, zoom: z)
                let prune = layer.pruneByParent && z > layer.zooms.lowerBound && z > 0

                var todo: [Packed] = []
                var total = 0
                var alreadyDone = 0
                for row in coverage.rows {
                    if Task.isCancelled { return .cancelled }
                    for range in row.ranges {
                        let existing = layer.store.existingColumns(z: z, y: row.y, xs: range)
                        let missing = layer.store.missingColumns(z: z, y: row.y, xs: range)
                        let parents: Set<Int>? = prune
                            ? layer.store.existingColumns(z: z - 1, y: row.y >> 1, xs: (range.lowerBound >> 1)...(range.upperBound >> 1))
                            : nil
                        for x in range {
                            if let p = parents, !p.contains(x >> 1) { continue }
                            total += 1
                            if existing.contains(x) || missing.contains(x) {
                                alreadyDone += 1
                            } else {
                                todo.append(Packed(x: Int32(x), y: Int32(row.y)))
                            }
                        }
                    }
                }

                progress.zoom = z
                progress.levelTotal = total
                progress.levelDone = alreadyDone
                emit(force: true)

                // Nothing exists at this level, so nothing can exist deeper either.
                if prune && total == 0 { break }
                if todo.isEmpty { continue }

                var wentOffline = false
                var storeFailed = false
                await withTaskGroup(of: (TileID, TileFetchResult).self) { group in
                    var next = 0
                    var inFlight = 0
                    func launch() {
                        let p = todo[next]
                        next += 1
                        inFlight += 1
                        let tile = TileID(z: z, x: Int(p.x), y: Int(p.y))
                        group.addTask { [self] in (tile, await self.fetchWithRetry(layer.fetcher, tile)) }
                    }
                    while next < todo.count && inFlight < concurrency { launch() }
                    while let (tile, result) = await group.next() {
                        inFlight -= 1
                        if case .failed = result {
                            failuresInARow += 1
                            if failuresInARow >= outageThreshold { wentOffline = true }
                        } else {
                            failuresInARow = 0
                        }
                        switch result {
                        case .data(let d):
                            if layer.store.put(tile, data: d) {
                                progress.fetched += 1
                                progress.bytes += Int64(d.count)
                            } else {
                                storeFailed = true
                            }
                        case .missing:
                            if layer.store.putMissing(tile) { progress.missing += 1 } else { storeFailed = true }
                        case .failed:
                            progress.failed += 1
                        case .offline:
                            wentOffline = true
                        }
                        progress.levelDone += 1
                        emit()
                        if wentOffline || storeFailed || Task.isCancelled {
                            group.cancelAll()
                            continue
                        }
                        if next < todo.count { launch() }
                    }
                }
                if !layer.store.flush() || storeFailed {
                    return .storageError(layer.store.writeError ?? "Couldn't save map tiles.")
                }
                emit(force: true)
                if wentOffline { return .offline }
                if Task.isCancelled { return .cancelled }
            }
        }
        return .completed(failed: progress.failed)
    }

    private func fetchWithRetry(_ fetcher: TileFetching, _ tile: TileID) async -> TileFetchResult {
        var attempt = 1
        while true {
            if Task.isCancelled { return .failed }
            let r = await fetcher.fetch(tile)
            guard case .failed = r, attempt < maxAttempts else { return r }
            let delay = retryDelay(attempt)
            if delay > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
            attempt += 1
        }
    }
}
