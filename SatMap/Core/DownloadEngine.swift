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
    /// The whole run as one number, for a single progress bar: the tiles planned over every layer and level (an upper
    /// bound, as deeper levels shrink where the imagery stops), and how many of them are behind.
    var overallDone: Int = 0
    var overallTotal: Int = 0
    /// Requests running at once at the moment.
    var parallel: Int = 0
}

enum DownloadOutcome: Equatable {
    /// Every level was processed. `failed` tiles (and their children) are retried by running the job again.
    case completed(failed: Int)
    case cancelled
    case offline
    /// Tiles could not be saved (usually the phone is full).
    case storageError(String)
}

/// How many tile requests to keep going at once. Tile servers answer each request in about a quarter of a second
/// however many run together, so more at once is faster, until the connection is full: then answers queue up behind
/// each other (which also slows the map on screen) or time out. So it starts in the middle, adds one for every `limit`
/// quick answers (up to `max`), and drops by a quarter (down to `min`) at most once per round of requests when one
/// fails or is slow: over `slowSeconds`, or well over the connection's own answer time (the fastest seen lately).
struct ConcurrencyController {
    let min: Int
    let max: Int
    let slowSeconds: Double
    private(set) var limit: Int
    private var quick = 0
    private var finished = 0
    private var lastDrop = Int.min / 2
    /// The fastest answer seen lately (it creeps up slowly, so a change of network is noticed).
    private(set) var baseSeconds: Double?

    init(start: Int, min: Int, max: Int, slowSeconds: Double = 5) {
        self.min = Swift.max(1, min)
        self.max = Swift.max(self.min, max)
        self.slowSeconds = slowSeconds
        limit = Swift.min(self.max, Swift.max(self.min, start))
    }

    mutating func record(seconds: Double, ok: Bool) {
        finished += 1
        let base = Swift.min(baseSeconds.map { $0 * 1.002 } ?? seconds, seconds)
        baseSeconds = base
        if !ok || seconds > slowSeconds || seconds > Swift.max(2 * base, base + 1) {
            quick = 0
            // The requests already under way were sent at the old rate: one drop per round.
            if finished - lastDrop >= limit {
                limit = Swift.max(min, limit * 3 / 4)
                lastDrop = finished
            }
            return
        }
        quick += 1
        if quick >= limit {
            limit = Swift.min(max, limit + 1)
            quick = 0
        }
    }
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
    /// The most requests at once; how many really run adapts to the connection (`ConcurrencyController`).
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
        /// Tiles sent so far, and the count when the current streak of failures began.
        var launched = 0
        var streakStart = 0
        var failedSinceStreak = false
        func emit(force: Bool = false) {
            let now = Date()
            if force || now.timeIntervalSince(lastReport) >= 0.25 {
                lastReport = now
                report(progress)
            }
        }

        // Overall progress, in tiles: the levels done, the one under way, and an estimate of the rest. Where imagery
        // stops (most of the deepest levels on farms), a level only has tiles under the ones that exist one level up,
        // so deeper levels are estimated from the share of tiles found at this one (starting from the share found one
        // level up); levels not started yet count at their full size.
        let planned = layers.map { layer in layer.zooms.map { TileCoverage(shape: layer.shape ?? shape, zoom: $0).tileCount } }
        var behind = 0
        var currentLayer = 0
        var currentLevel = -1
        var pruning = false
        var levelStored = 0
        var previousShare = 1.0
        func updateOverall() {
            var rest = 0
            for li in currentLayer..<planned.count {
                for zi in planned[li].indices where li > currentLayer || zi > currentLevel {
                    guard li == currentLayer && pruning else {
                        rest += planned[li][zi]
                        continue
                    }
                    // Tiles found at this level so far, weighed against the share found one level up.
                    let share = (Double(levelStored) + previousShare * 8) / (Double(progress.levelDone) + 8)
                    let expected = Double(progress.levelTotal) * share * pow(4, Double(zi - currentLevel))
                    rest += min(planned[li][zi], Int(min(expected, Double(Int.max / 4))))
                }
            }
            progress.overallDone = behind + progress.levelDone
            progress.overallTotal = behind + progress.levelTotal + rest
        }
        progress.levelTotal = 0
        updateOverall()

        for (li, layer) in layers.enumerated() {
            progress.layer = layer.name
            progress.maxZoom = layer.zooms.upperBound
            defer { layer.store.checkpoint() }
            // Each layer comes from its own server.
            var parallel = ConcurrencyController(start: min(16, concurrency), min: min(4, concurrency), max: concurrency)
            currentLayer = li
            previousShare = 1

            for (zi, z) in layer.zooms.enumerated() {
                if Task.isCancelled { return .cancelled }
                let coverage = TileCoverage(shape: layer.shape ?? shape, zoom: z)
                let prune = layer.pruneByParent && z > layer.zooms.lowerBound && z > 0

                var todo: [Packed] = []
                var total = 0
                var alreadyDone = 0
                var alreadyStored = 0
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
                            if existing.contains(x) {
                                alreadyDone += 1
                                alreadyStored += 1
                            } else if missing.contains(x) {
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
                currentLevel = zi
                pruning = layer.pruneByParent
                levelStored = alreadyStored
                updateOverall()
                emit(force: true)

                // Nothing exists at this level, so nothing can exist deeper either.
                if prune && total == 0 {
                    currentLevel = layer.zooms.count - 1
                    progress.levelTotal = 0
                    progress.levelDone = 0
                    updateOverall()
                    break
                }
                if todo.isEmpty {
                    behind += total
                    previousShare = total > 0 ? Double(levelStored) / Double(total) : previousShare
                    progress.levelTotal = 0
                    progress.levelDone = 0
                    continue
                }

                var wentOffline = false
                var storeFailed = false
                await withTaskGroup(of: (TileID, TileFetchResult, Double, Int, Int).self) { group in
                    var next = 0
                    var inFlight = 0
                    func launch() {
                        let p = todo[next]
                        next += 1
                        inFlight += 1
                        let tile = TileID(z: z, x: Int(p.x), y: Int(p.y))
                        let seq = launched
                        launched += 1
                        group.addTask { [self] in
                            let (result, seconds, attempts) = await self.fetchWithRetry(layer.fetcher, tile)
                            return (tile, result, seconds, attempts, seq)
                        }
                    }
                    while next < todo.count && inFlight < parallel.limit { launch() }
                    while let (tile, result, seconds, attempts, seq) = await group.next() {
                        inFlight -= 1
                        switch result {
                        // A tile that needed another try counts as trouble too.
                        case .data, .missing: parallel.record(seconds: seconds, ok: attempts == 1)
                        case .failed: parallel.record(seconds: seconds, ok: false)
                        case .offline: break
                        }
                        progress.parallel = parallel.limit
                        if case .failed = result {
                            if failuresInARow == 0 { streakStart = launched }
                            failuresInARow += 1
                            // Many requests run at once, so one short gap fails a whole round of them together: the
                            // connection counts as down only when tiles sent after the failures began fail as well.
                            if seq >= streakStart { failedSinceStreak = true }
                            if failuresInARow >= outageThreshold && failedSinceStreak { wentOffline = true }
                        } else {
                            failuresInARow = 0
                            failedSinceStreak = false
                        }
                        switch result {
                        case .data(let d):
                            if layer.store.put(tile, data: d) {
                                progress.fetched += 1
                                progress.bytes += Int64(d.count)
                                levelStored += 1
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
                        updateOverall()
                        emit()
                        if wentOffline || storeFailed || Task.isCancelled {
                            group.cancelAll()
                            continue
                        }
                        while next < todo.count && inFlight < parallel.limit { launch() }
                    }
                }
                if !layer.store.flush() || storeFailed {
                    return .storageError(layer.store.writeError ?? "Couldn't save map tiles.")
                }
                if wentOffline || Task.isCancelled {
                    emit(force: true)
                    return wentOffline ? .offline : .cancelled
                }
                behind += total
                previousShare = total > 0 ? Double(levelStored) / Double(total) : previousShare
                progress.levelTotal = 0
                progress.levelDone = 0
                updateOverall()
                emit(force: true)
            }
            // The layer is done (or stopped where its imagery stops): nothing more of it is to come.
            currentLevel = layer.zooms.count - 1
            pruning = false
        }
        currentLayer = layers.count
        progress.overallTotal = behind
        progress.overallDone = behind
        emit(force: true)
        return .completed(failed: progress.failed)
    }

    /// The result, how long the last attempt took and how many attempts it took.
    private func fetchWithRetry(_ fetcher: TileFetching, _ tile: TileID) async -> (TileFetchResult, Double, Int) {
        var attempt = 1
        while true {
            if Task.isCancelled { return (.failed, 0, attempt) }
            let started = Date()
            let r = await fetcher.fetch(tile)
            let seconds = Date().timeIntervalSince(started)
            guard case .failed = r, attempt < maxAttempts else { return (r, seconds, attempt) }
            let delay = retryDelay(attempt)
            if delay > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
            attempt += 1
        }
    }
}
