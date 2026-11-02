import Foundation

/// Opens the shown group's WebSocket (docs/groups/SPEC.md §6.6, §8.7): `wss://{host}/v1/groups/{groupId}/ws` with the
/// member pass as the Bearer token. Events reach the hub on the main thread, and only those of the socket the hub
/// opened last: the late frames and the close of a socket the hub already closed are dropped, so a stale close can't
/// end the new socket's state.
final class GroupSocketFactoryImpl: GroupSocketFactory {
    private let baseURL: URL
    weak var hub: GroupHub?
    /// The socket the hub opened last.
    private weak var current: URLSessionGroupSocket?

    init(baseURL: URL) {
        self.baseURL = baseURL
    }

    /// The socket URL for a base URL: https → wss, http → ws (tests against a local server).
    static func url(base: URL, groupId: String) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        c.scheme = c.scheme == "http" ? "ws" : "wss"
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        c.path = path + "/v1/groups/\(groupId)/ws"
        c.query = nil
        c.fragment = nil
        return c.url
    }

    func open(groupId: String, pass: String) -> GroupSocket {
        current?.closeQuietly()
        let socket = URLSessionGroupSocket(groupId: groupId)
        current = socket
        socket.onEvent = { [weak self, weak socket] event in
            guard let self = self, let socket = socket, socket === self.current, let hub = self.hub else { return }
            switch event {
            case .open: hub.socketDidOpen(groupId: groupId)
            case .text(let text): hub.socketDidReceive(groupId: groupId, text: text)
            case .closed(let code):
                self.current = nil
                hub.socketDidClose(groupId: groupId, code: code)
            }
        }
        if let url = Self.url(base: baseURL, groupId: groupId) {
            socket.connect(url: url, pass: pass)
        } else {
            DispatchQueue.main.async { socket.report(.closed(1006)) }
        }
        return socket
    }
}

/// One WebSocket on its own URLSession (its delegate), delivering events on the main queue until it is closed.
final class URLSessionGroupSocket: NSObject, GroupSocket, URLSessionWebSocketDelegate, @unchecked Sendable {
    enum Event {
        case open
        case text(String)
        case closed(Int)
    }

    let groupId: String
    var onEvent: ((Event) -> Void)?
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    /// Set once the hub closed it or a close was reported: nothing more is reported.
    private var finished = false

    init(groupId: String) {
        self.groupId = groupId
    }

    func connect(url: URL, pass: String) {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(pass)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 30
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.urlCache = nil
        let session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        let task = session.webSocketTask(with: request)
        task.maximumMessageSize = 1 << 20 // the server sends at most 512 KiB (§6.6)
        self.session = session
        self.task = task
        task.resume()
        receive()
    }

    func send(_ text: String) {
        guard !finished, let task = task else { return }
        task.send(.string(text)) { _ in } // a failed send ends the socket, which is reported as its close
    }

    /// The hub closes it: nothing more of it is reported.
    func close(code: Int) {
        finished = true
        onEvent = nil
        task?.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .normalClosure, reason: nil)
        session?.finishTasksAndInvalidate()
    }

    /// Replaced by a newer socket before the hub closed it.
    func closeQuietly() {
        close(code: 1000)
    }

    func report(_ event: Event) {
        guard !finished else { return }
        if case .closed = event {
            finished = true
            session?.finishTasksAndInvalidate()
        }
        onEvent?(event)
        if finished { onEvent = nil }
    }

    private func receive() {
        task?.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self, !self.finished else { return }
                switch result {
                case .success(.string(let text)):
                    self.report(.text(text))
                    self.receive()
                case .success(.data(let data)):
                    if let text = String(data: data, encoding: .utf8) { self.report(.text(text)) }
                    self.receive()
                case .success:
                    self.receive()
                case .failure:
                    // The close (or the refused upgrade) is reported by the delegate.
                    break
                }
            }
        }
    }

    // MARK: URLSessionWebSocketDelegate (on the main queue)

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol proto: String?) {
        report(.open)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        report(.closed(closeCode.rawValue))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // A refused upgrade is an HTTP answer before the upgrade (§8.7).
        let status = (task.response as? HTTPURLResponse)?.statusCode
        report(.closed(Self.closeCode(httpStatus: status, socketCloseCode: (task as? URLSessionWebSocketTask)?.closeCode.rawValue)))
    }

    /// The close code to report when the task ended: the server's close code if it sent one, else the refused
    /// upgrade's status as a close code. `401` and `404` become 4401 (the hub checks with E2). A `410` is reported as
    /// 4401 too: URLSession doesn't hand over the body that says "removed" or "group_deleted", and E2 then answers with
    /// the code, so the right alert shows. Anything else (or no answer) is 1006, which reconnects with backoff.
    static func closeCode(httpStatus: Int?, socketCloseCode: Int?) -> Int {
        if let c = socketCloseCode, c != URLSessionWebSocketTask.CloseCode.invalid.rawValue { return c }
        switch httpStatus {
        case 401, 404, 410: return 4401
        default: return 1006
        }
    }
}
