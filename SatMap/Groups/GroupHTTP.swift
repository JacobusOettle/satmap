import Foundation
import UIKit

/// The groups HTTP transport (docs/groups/SPEC.md §6.1, §8.2): one request to "/v1/…" under the API base URL, with
/// the member pass as the Bearer token. No cookies, no cache; the core reads only the status and the body.
final class GroupHTTP: GroupTransport {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL) {
        self.baseURL = baseURL
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.waitsForConnectivity = false
        config.httpAdditionalHeaders = ["User-Agent": "SatMap-iOS"]
        session = URLSession(configuration: config)
    }

    /// The full URL of a path such as "/v1/groups/abc/changes?since=4".
    static func url(base: URL, path: String) -> URL? {
        var text = base.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        return URL(string: text + path)
    }

    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data) {
        guard let url = Self.url(base: baseURL, path: path) else { throw GroupTransportError.failed }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let pass = pass { request.setValue("Bearer \(pass)", forHTTPHeaderField: "Authorization") }
        if let json = json {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = json
        }
        // Uploads go on in the background while sharing (§8.6): each request may finish after the app leaves the screen.
        let task = BackgroundTask.begin("Groups request")
        defer { task.end() }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw GroupTransportError.failed }
            return (http.statusCode, data)
        } catch let e as URLError {
            throw Self.isOffline(e) || !Connectivity.shared.isOnlineNow ? GroupTransportError.offline : GroupTransportError.failed
        } catch let e as GroupTransportError {
            throw e
        } catch {
            throw GroupTransportError.failed
        }
    }

    /// The errors that mean there is no connection (everything else is "something went wrong", §8.14).
    static func isOffline(_ e: URLError) -> Bool {
        switch e.code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff, .callIsActive:
            return true
        default:
            return false
        }
    }
}

/// `UIApplication.beginBackgroundTask` around a piece of work, ended once (by the work, or by iOS when time runs out).
final class BackgroundTask: @unchecked Sendable {
    private let lock = NSLock()
    private var id = UIBackgroundTaskIdentifier.invalid

    static func begin(_ name: String) -> BackgroundTask {
        let task = BackgroundTask()
        let id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak task] in task?.end() }
        task.lock.withLock { task.id = id }
        return task
    }

    func end() {
        let ended: UIBackgroundTaskIdentifier = lock.withLock {
            defer { id = .invalid }
            return id
        }
        if ended != .invalid { UIApplication.shared.endBackgroundTask(ended) }
    }
}
