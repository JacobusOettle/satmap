import Foundation
import os

private let roadLog = Logger(subsystem: "com.jacobusoettle.satmap", category: "roads")

/// Road sets shipped with the app (SatMap/Resources/<id>.json), loaded once.
enum RoadPresets {
    static let kruger = "KrugerRoads"
    private static let lock = NSLock()
    private static var loaded: [String: RoadPreset] = [:]

    static func load(_ id: String) -> RoadPreset? {
        lock.lock()
        defer { lock.unlock() }
        if let p = loaded[id] { return p }
        guard let url = Bundle.main.url(forResource: id, withExtension: "json"),
              let data = try? Data(contentsOf: url), let p = try? RoadPreset(json: data) else {
            roadLog.error("road preset \(id, privacy: .public) missing or unreadable")
            return nil
        }
        loaded[id] = p
        return p
    }
}

/// Roads and tracks in an area from OpenStreetMap, through the public Overpass API (no account needed).
enum RoadFetcher {
    /// The main server, then mirrors for when it is busy (public Overpass servers are often overloaded). The main
    /// server's "busy" errors pass quickly, so it gets a second turn.
    private static let servers = [
        URL(string: "https://overpass-api.de/api/interpreter")!,
        URL(string: "https://overpass.private.coffee/api/interpreter")!,
        URL(string: "https://overpass-api.de/api/interpreter")!,
        URL(string: "https://overpass.kumi.systems/api/interpreter")!,
    ]

    enum Failure: LocalizedError {
        case offline
        case unavailable

        var errorDescription: String? {
            switch self {
            case .offline: return "No internet connection. Roads are looked up online; try again when connected."
            case .unavailable: return "The OpenStreetMap road server is busy or unreachable. Try again in a minute."
            }
        }
    }

    /// How long to wait for a server before also asking the next one.
    private static let nextServerAfter: UInt64 = 12_000_000_000

    private enum Attempt {
        case roads([[GeoPoint]])
        case failed(offline: Bool)
        case nextServerDue
    }

    /// Road centre lines inside the area (clipped to it). Asks the main server first; if it hasn't answered after
    /// a few seconds, or fails, the next one too, and so on. The first good answer wins and the rest are cancelled.
    static func roads(in area: CoverageShape) async throws -> [[GeoPoint]] {
        guard let query = Roads.overpassQuery(for: area) else { return [] }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let body = Data(("data=" + (query.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")).utf8)

        let lines: [[GeoPoint]] = try await withThrowingTaskGroup(of: Attempt.self) { group in
            var started = 0
            var running = 0
            var offline = false
            var startNext = true
            while true {
                if startNext, started < servers.count {
                    let server = servers[started]
                    started += 1
                    running += 1
                    group.addTask { await RoadFetcher.attempt(server, body: body) }
                    if started < servers.count {
                        group.addTask {
                            try await Task.sleep(nanoseconds: RoadFetcher.nextServerAfter)
                            return .nextServerDue
                        }
                    }
                }
                startNext = false
                guard running > 0, let result = try await group.next() else { break }
                switch result {
                case .roads(let lines):
                    group.cancelAll()
                    return lines
                case .failed(let noNetwork):
                    running -= 1
                    offline = offline || noNetwork
                    startNext = true
                case .nextServerDue:
                    startNext = true
                }
            }
            group.cancelAll()
            throw offline ? Failure.offline : Failure.unavailable
        }
        return Roads.clip(lines, to: area)
    }

    private static func attempt(_ server: URL, body: Data) async -> Attempt {
        let host = server.host ?? "?"
        let start = Date()
        var request = URLRequest(url: server, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else {
                roadLog.error("\(host, privacy: .public): HTTP \(status) after \(ms) ms")
                return .failed(offline: false)
            }
            guard let lines = try? Roads.parse(data) else {
                roadLog.error("\(host, privacy: .public): unreadable answer (\(data.count) bytes) after \(ms) ms")
                return .failed(offline: false)
            }
            roadLog.info("\(host, privacy: .public): \(lines.count) roads, \(data.count) bytes in \(ms) ms")
            return .roads(lines)
        } catch {
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            let code = (error as? URLError)?.code
            if code != .cancelled {
                roadLog.error("\(host, privacy: .public): \(error.localizedDescription, privacy: .public) after \(ms) ms")
            }
            return .failed(offline: code == .notConnectedToInternet || code == .dataNotAllowed)
        }
    }
}
