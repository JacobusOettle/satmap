import Foundation
import os

/// Seals the tiles that offline areas downloaded before tiles were encrypted still hold plain, and those of a backup
/// made then once it is restored (`MBTiles.encryptPlainTiles`), so copies of the files can't be shown by another app.
/// One file at a time, on a queue of its own at background priority, through a store of its own: the map draws and
/// other areas download meanwhile. Only while SatMap is open: a large area is minutes of work, not worth the battery
/// in the background. An area's files wait while it downloads (its download store holds long transactions) or is
/// exported (the export seals them itself, sooner). A file that couldn't be finished (busy, storage full) is tried
/// again a little later, and at the next launch in any case; each run carries on where the last one stopped.
/// Used on the main thread only.
final class OfflineEncryption: @unchecked Sendable {
    private struct File {
        let area: UUID
        let url: URL
        /// A run on it didn't finish: it runs again even if the file is marked as sealed, as it may have stopped at
        /// its final checkpoint (the sealed tiles not yet folded into the file).
        var unfinished = false
        /// Runs that failed (busy, storage full), for the wait before the next one.
        var failures = 0
        var notBefore = Date.distantPast
    }

    /// A file being sealed (on the main thread, but for `stop`).
    private final class Run: @unchecked Sendable {
        let file: File
        let stop = StopFlag()
        /// Its area was deleted: the file isn't tried again.
        var discarded = false
        var task: Task<Void, Never>?

        init(file: File) {
            self.file = file
        }
    }

    /// Set on the main thread to stop a run after its batch, read by the run.
    private final class StopFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var stopped = false

        var isSet: Bool { lock.withLock { stopped } }

        func set() {
            lock.withLock { stopped = true }
        }
    }

    private let queue = DispatchQueue(label: "satmap.encryption", qos: .background)
    /// Files that may still hold plain tiles, in the order they are sealed.
    private var pending: [File] = []
    private var current: Run?
    /// Areas whose files wait: one downloading, ones being exported (counted, as both can hold an area).
    private var holds: [UUID: Int] = [:]
    private var wakeScheduled = false

    /// SatMap is in the background: a run stops after its batch, and none starts until SatMap is open again.
    var inBackground = false {
        didSet {
            guard inBackground != oldValue else { return }
            if inBackground { current?.stop.set() } else { startNext() }
        }
    }

    /// Seals an area's files when their turn comes, if they may still hold plain tiles: a file that is marked as
    /// sealed (`MBTiles.isEncrypted`), or doesn't exist, is skipped then.
    func add(_ area: UUID, files: [URL]) {
        for url in files where !pending.contains(where: { $0.url == url }) {
            pending.append(File(area: area, url: url))
        }
        startNext()
    }

    /// Keeps away from these areas' files until `release`. A run in one of them stops after its batch; the task
    /// returned ends when it has (nil if none was running there).
    @discardableResult
    func hold(_ ids: [UUID]) -> Task<Void, Never>? {
        for id in ids { holds[id, default: 0] += 1 }
        guard let run = current, ids.contains(run.file.area) else { return nil }
        run.stop.set()
        return run.task
    }

    func release(_ ids: [UUID]) {
        for id in ids {
            let left = (holds[id] ?? 0) - 1
            holds[id] = left > 0 ? left : nil
        }
        startNext()
    }

    /// Forgets a deleted area's files. The task returned ends when a run in them has stopped (nil if none was running).
    func remove(_ id: UUID) -> Task<Void, Never>? {
        pending.removeAll { $0.area == id }
        guard let run = current, run.file.area == id else { return nil }
        run.discarded = true
        run.stop.set()
        return run.task
    }

    private func startNext() {
        guard current == nil, !inBackground else { return }
        let now = Date()
        let free = pending.indices.filter { holds[pending[$0].area] == nil }
        guard let i = free.first(where: { pending[$0].notBefore <= now }) else {
            // Only files waiting to be tried again: wake up for the first of them.
            if let soonest = free.map({ pending[$0].notBefore }).min() { wake(at: soonest) }
            return
        }
        let run = Run(file: pending.remove(at: i))
        current = run
        let (queue, file, stop) = (self.queue, run.file, run.stop)
        run.task = Task { @MainActor [weak self] in
            let outcome = await withCheckedContinuation { (done: CheckedContinuation<Outcome, Never>) in
                queue.async { done.resume(returning: OfflineEncryption.seal(file, stop: stop)) }
            }
            self?.ended(run, outcome)
        }
    }

    private func ended(_ run: Run, _ outcome: Outcome) {
        current = nil
        if outcome != .done && !run.discarded {
            var file = run.file
            if outcome == .unfinished { file.unfinished = true }
            if run.stop.isSet {
                // Stopped for a download, an export or the background: it goes first when it may.
                pending.insert(file, at: 0)
            } else {
                // The file was busy, or storage is full: tried again later, after a longer wait each time.
                file.failures += 1
                file.notBefore = Date().addingTimeInterval(min(600, 30 * pow(2, Double(file.failures))))
                pending.append(file)
            }
        }
        startNext()
    }

    private func wake(at date: Date) {
        guard !wakeScheduled else { return }
        wakeScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + max(1, date.timeIntervalSinceNow)) { [weak self] in
            self?.wakeScheduled = false
            self?.startNext()
        }
    }

    private enum Outcome {
        /// Every tile sealed, or nothing to do: no file, or one that is marked as sealed.
        case done
        /// Stopped before it began.
        case notStarted
        /// Stopped, or failed, part of the way.
        case unfinished
    }

    /// Seals what the file holds plain (blocking).
    private static func seal(_ file: File, stop: StopFlag) -> Outcome {
        guard !stop.isSet else { return .notStarted }
        guard FileManager.default.fileExists(atPath: file.url.path),
              let store = try? MBTiles(url: file.url, create: false) else { return .done }
        defer { store.close() }
        guard file.unfinished || !store.isEncrypted else { return .done }
        let name = "\(file.area.uuidString)/\(file.url.lastPathComponent)"
        tileLog.info("encrypting the plain tiles of \(name, privacy: .public)")
        guard store.encryptPlainTiles(progress: { _, _ in !stop.isSet }) else {
            if !stop.isSet { tileLog.error("couldn't finish encrypting \(name, privacy: .public), tried again later") }
            return .unfinished
        }
        tileLog.info("encrypted the plain tiles of \(name, privacy: .public)")
        return .done
    }
}
