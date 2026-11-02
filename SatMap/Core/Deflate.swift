import Foundation
#if canImport(zlib)
import zlib
#else
import CZlib
#endif

/// Raw DEFLATE (RFC 1951, no zlib header) for group trail chunks (docs/groups/SPEC.md §4.2, encoding 1).
///
/// `inflateExact` accepts a stream only if it ends (its final block) exactly after producing the declared size and no
/// input follows it, the same rule on Apple platforms, on Linux and in the Android core. Both directions use zlib on
/// every platform (the system's on Apple platforms, through the CZlib shim on Linux): Apple's Compression framework
/// doesn't report input left after the end of a stream, so it can't apply that rule, and zlib also gives the same
/// compressed bytes as the Android core's Deflater. `Inflate.raw` isn't used for chunks: on Apple platforms it decodes
/// into an exact-size buffer, so it would accept a longer stream.
enum Deflate {
    /// The data compressed as raw DEFLATE, or nil if compressing failed.
    static func raw(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        var stream = z_stream()
        // Level 6, as the Android core's Deflater(6, true); windowBits −15 is raw DEFLATE.
        guard deflateInit2_(&stream, 6, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion(),
                            Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { deflateEnd(&stream) }
        let capacity = Int(deflateBound(&stream, uLong(data.count)))
        var out = [UInt8](repeating: 0, count: capacity)
        var src = [UInt8](data)
        let status: Int32 = src.withUnsafeMutableBufferPointer { s in
            out.withUnsafeMutableBufferPointer { d in
                stream.next_in = s.baseAddress
                stream.avail_in = uInt(s.count)
                stream.next_out = d.baseAddress
                stream.avail_out = uInt(capacity)
                return deflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END else { return nil }
        return Data(out[..<Int(stream.total_out)])
    }

    /// The decompressed bytes, only if the stream ends exactly after `size` bytes and no input byte follows its end.
    static func inflateExact(_ data: Data, size: Int) -> Data? {
        guard size >= 0, !data.isEmpty else { return nil }
        // One byte more than declared, so a stream that goes on past `size` is seen.
        let capacity = size + 1
        var out = [UInt8](repeating: 0, count: capacity)
        var src = [UInt8](data)
        var stream = z_stream()
        guard inflateInit2_(&stream, -15, zlibVersion(), Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { inflateEnd(&stream) }
        let status: Int32 = src.withUnsafeMutableBufferPointer { s in
            out.withUnsafeMutableBufferPointer { d in
                stream.next_in = s.baseAddress
                stream.avail_in = uInt(s.count)
                stream.next_out = d.baseAddress
                stream.avail_out = uInt(capacity)
                return inflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END, Int(stream.total_out) == size, stream.avail_in == 0 else { return nil }
        return Data(out[..<size])
    }
}
