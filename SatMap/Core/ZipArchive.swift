import Foundation

/// Read-only access to a (non-ZIP64) zip archive held in memory — enough for KMZ files.
struct ZipArchive {
    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    enum ZipError: Error {
        case notAZip
        case corrupt
        case unsupportedCompression
    }

    let entries: [Entry]
    private let bytes: [UInt8]

    init(data: Data) throws {
        bytes = [UInt8](data)
        // Locate the End Of Central Directory record (it may be followed by a comment of up to 64 KiB).
        guard bytes.count >= 22 else { throw ZipError.notAZip }
        var eocd = -1
        var i = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 65_535)
        while i >= lowest {
            if bytes[i] == 0x50, bytes[i + 1] == 0x4B, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 {
                eocd = i
                break
            }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.notAZip }
        let count = Int(Self.u16(bytes, eocd + 10))
        var pos = Int(Self.u32(bytes, eocd + 16))
        var list: [Entry] = []
        for _ in 0..<count {
            guard pos + 46 <= bytes.count, Self.u32(bytes, pos) == 0x0201_4B50 else { throw ZipError.corrupt }
            let method = Self.u16(bytes, pos + 10)
            let compressed = Int(Self.u32(bytes, pos + 20))
            let uncompressed = Int(Self.u32(bytes, pos + 24))
            let nameLen = Int(Self.u16(bytes, pos + 28))
            let extraLen = Int(Self.u16(bytes, pos + 30))
            let commentLen = Int(Self.u16(bytes, pos + 32))
            let offset = Int(Self.u32(bytes, pos + 42))
            guard pos + 46 + nameLen <= bytes.count else { throw ZipError.corrupt }
            let nameBytes = Array(bytes[(pos + 46)..<(pos + 46 + nameLen)])
            let name = String(decoding: nameBytes, as: UTF8.self)
            list.append(Entry(name: name, method: method, compressedSize: compressed,
                              uncompressedSize: uncompressed, localHeaderOffset: offset))
            pos += 46 + nameLen + extraLen + commentLen
        }
        entries = list
    }

    func extract(_ entry: Entry) throws -> Data {
        let h = entry.localHeaderOffset
        guard h + 30 <= bytes.count, Self.u32(bytes, h) == 0x0403_4B50 else { throw ZipError.corrupt }
        let nameLen = Int(Self.u16(bytes, h + 26))
        let extraLen = Int(Self.u16(bytes, h + 28))
        let start = h + 30 + nameLen + extraLen
        guard start + entry.compressedSize <= bytes.count else { throw ZipError.corrupt }
        let payload = Data(bytes[start..<(start + entry.compressedSize)])
        switch entry.method {
        case 0:
            return payload
        case 8:
            guard let out = Inflate.raw(payload, decompressedSize: entry.uncompressedSize) else { throw ZipError.corrupt }
            return out
        default:
            throw ZipError.unsupportedCompression
        }
    }

    private static func u16(_ b: [UInt8], _ i: Int) -> UInt16 {
        UInt16(b[i]) | (UInt16(b[i + 1]) << 8)
    }

    private static func u32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | (UInt32(b[i + 1]) << 8) | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
    }
}
