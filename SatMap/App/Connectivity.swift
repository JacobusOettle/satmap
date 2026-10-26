import Foundation
import Network

/// Tracks whether the device can reach the network (and whether that network is cellular / a hotspot),
/// so the map doesn't wait on requests while offline and Wi-Fi-only downloads know when to continue.
final class Connectivity: ObservableObject {
    struct State: Equatable {
        var isOnline: Bool
        /// Cellular or personal hotspot.
        var isExpensive: Bool
    }

    static let shared = Connectivity()

    @Published private(set) var state = State(isOnline: true, isExpensive: false)

    var isOnline: Bool { state.isOnline }

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var current = State(isOnline: true, isExpensive: false)

    /// Thread-safe snapshot for use off the main thread.
    var isOnlineNow: Bool {
        lock.withLock { current.isOnline }
    }

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let value = State(isOnline: path.status == .satisfied, isExpensive: path.isExpensive)
            self.lock.withLock { self.current = value }
            DispatchQueue.main.async {
                if self.state != value { self.state = value }
            }
        }
        monitor.start(queue: DispatchQueue(label: "satmap.connectivity"))
    }
}
