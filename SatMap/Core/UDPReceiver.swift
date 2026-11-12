import Dispatch
import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// A receive-only UDP socket on 0.0.0.0:<port> (Drone link): every datagram that arrives is handed on, and nothing is
/// ever sent. A plain BSD socket, so the core's tests can send it datagrams over the loopback on any platform.
final class UDPReceiver {
    enum Failure: Error, Equatable {
        case socket(Int32)
        case bind(Int32)
    }

    /// The port asked for (0: any free one, for tests; `port` then says which).
    private let requestedPort: UInt16
    private let queue: DispatchQueue
    private let onDatagram: ([UInt8]) -> Void
    private var source: DispatchSourceRead?

    /// The port it listens on while started.
    private(set) var port: UInt16 = 0

    /// `onDatagram` is called on `queue` with each datagram's bytes.
    init(port: UInt16, queue: DispatchQueue, onDatagram: @escaping ([UInt8]) -> Void) {
        requestedPort = port
        self.queue = queue
        self.onDatagram = onDatagram
    }

    deinit {
        stop()
    }

    var isRunning: Bool { source != nil }

    /// Opens and binds the socket. Throws if the port can't be had (another app has it).
    func start() throws {
        guard source == nil else { return }
        #if canImport(Glibc)
        let s = socket(AF_INET, Int32(SOCK_DGRAM.rawValue), Int32(IPPROTO_UDP))
        #else
        let s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        #endif
        guard s >= 0 else { throw Failure.socket(errno) }
        // No SO_REUSEADDR: on Linux (Android) it would let a second socket share the port, and UDP has no TIME_WAIT
        // to wait out after a close.
        var addr = sockaddr_in()
        #if canImport(Darwin)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = requestedPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else {
            let e = errno
            close(s)
            throw Failure.bind(e)
        }
        var actual = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(s, $0, &len) }
        }
        port = UInt16(bigEndian: actual.sin_port)
        let flags = fcntl(s, F_GETFL, 0)
        _ = fcntl(s, F_SETFL, flags | O_NONBLOCK)
        let src = DispatchSource.makeReadSource(fileDescriptor: s, queue: queue)
        src.setEventHandler { [weak self] in self?.drain(s) }
        src.setCancelHandler { close(s) }
        source = src
        src.resume()
    }

    /// Closes the socket.
    func stop() {
        guard let src = source else { return }
        source = nil
        port = 0
        src.cancel() // closes the socket once the handler is done
    }

    /// Reads every datagram waiting.
    private func drain(_ fd: Int32) {
        var buffer = [UInt8](repeating: 0, count: 65_536)
        for _ in 0..<256 {
            let n = buffer.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
            guard n > 0 else { return }
            onDatagram(Array(buffer[0..<n]))
        }
    }

    /// The IPv4 addresses of the interfaces that are up (name and dotted address), loopback included; which of them
    /// to show is `DroneLink.addresses`.
    static func interfaceAddresses() -> [(name: String, address: String)] {
        var out: [(name: String, address: String)] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let i = p {
            defer { p = i.pointee.ifa_next }
            let flags = Int32(i.pointee.ifa_flags)
            guard flags & Int32(IFF_UP) != 0, flags & Int32(IFF_RUNNING) != 0,
                  let a = i.pointee.ifa_addr, a.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            let ok = a.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin -> Bool in
                var addr = sin.pointee.sin_addr
                return inet_ntop(AF_INET, &addr, &host, socklen_t(INET_ADDRSTRLEN)) != nil
            }
            guard ok else { continue }
            out.append((String(cString: i.pointee.ifa_name), String(cString: host)))
        }
        return out
    }
}
