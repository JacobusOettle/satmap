import Foundation
#if canImport(Compression)
import Compression
#else
import CZlib
#endif

/// DEFLATE decompression for PNG image data and KMZ (zip) archives.
enum Inflate {
    /// Decompresses raw DEFLATE data (RFC 1951) whose decompressed size is known exactly.
    static func raw(_ input: Data, decompressedSize: Int) -> Data? {
        if decompressedSize == 0 { return Data() }
        guard !input.isEmpty, decompressedSize > 0 else { return nil }
        var out = Data(count: decompressedSize)
        #if canImport(Compression)
        let written = out.withUnsafeMutableBytes { (dst: UnsafeMutableRawBufferPointer) -> Int in
            input.withUnsafeBytes { (src: UnsafeRawBufferPointer) -> Int in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                      let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                // COMPRESSION_ZLIB is raw DEFLATE (no zlib header or checksum).
                return compression_decode_buffer(d, decompressedSize, s, input.count, nil, COMPRESSION_ZLIB)
            }
        }
        return written == decompressedSize ? out : nil
        #else
        var stream = z_stream()
        guard inflateInit2_(&stream, -15, zlibVersion(), Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { inflateEnd(&stream) }
        var src = [UInt8](input)
        let status: Int32 = src.withUnsafeMutableBufferPointer { s in
            out.withUnsafeMutableBytes { (d: UnsafeMutableRawBufferPointer) -> Int32 in
                stream.next_in = s.baseAddress
                stream.avail_in = uInt(s.count)
                stream.next_out = d.bindMemory(to: Bytef.self).baseAddress
                stream.avail_out = uInt(decompressedSize)
                return inflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END, Int(stream.total_out) == decompressedSize else { return nil }
        return out
        #endif
    }

    /// Decompresses a zlib-wrapped stream (RFC 1950), e.g. concatenated PNG IDAT chunks.
    static func zlib(_ input: Data, decompressedSize: Int) -> Data? {
        let bytes = [UInt8](input)
        guard bytes.count > 6 else { return nil }
        let cmf = bytes[0], flg = bytes[1]
        guard cmf & 0x0F == 8, (UInt16(cmf) << 8 | UInt16(flg)) % 31 == 0 else { return nil }
        let header = (flg & 0x20) != 0 ? 6 : 2
        // Drop the trailing Adler-32 checksum; the DEFLATE stream ends before it.
        let end = bytes.count - 4
        guard end > header else { return nil }
        return raw(Data(bytes[header..<end]), decompressedSize: decompressedSize)
    }
}
