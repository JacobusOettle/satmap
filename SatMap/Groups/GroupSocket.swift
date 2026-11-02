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
        if let url = GroupEndpoints.socketURL(base: baseURL, groupId: groupId) {
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
        // Not 30 s, the ping interval: if URLSession timed out a socket with no frame for that long, a quiet group's
        // socket would drop and download the whole welcome again about every 30 s.
        request.timeoutInterval = GroupEndpoints.socketTimeout
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = GroupEndpoints.socketTimeout
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
        report(.closed(GroupEndpoints.closeCode(httpStatus: status,
                                                socketCloseCode: (task as? URLSessionWebSocketTask)?.closeCode.rawValue)))
    }
}
