import Foundation

/// A decoded 8-bit RGBA image.
struct RGBAImage {
    let width: Int
    let height: Int
    /// Row-major from the top, 4 bytes per pixel.
    let pixels: [UInt8]
}

/// Minimal, exact PNG decoder (8-bit, non-interlaced; grey, RGB, palette, grey+alpha, RGBA).
/// Used for Terrarium elevation tiles where every bit of the RGB value matters, so we avoid
/// any colour-managed decode path that could alter pixel values.
enum PNGDecoder {
    private static let signature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]

    static func decode(_ data: Data) -> RGBAImage? {
        let bytes = [UInt8](data)
        guard bytes.count > 33, Array(bytes[0..<8]) == signature else { return nil }

        var pos = 8
        var width = 0, height = 0, bitDepth = 0, colorType = -1, interlace = 0
        var palette: [UInt8] = []
        var transparency: [UInt8] = []
        var idat = Data()

        chunks: while pos + 12 <= bytes.count {
            let length = Int(be32(bytes, pos))
            let typeStart = pos + 4
            let start = pos + 8
            guard length >= 0, start + length + 4 <= bytes.count else { return nil }
            let type = (UInt32(bytes[typeStart]) << 24) | (UInt32(bytes[typeStart + 1]) << 16)
                | (UInt32(bytes[typeStart + 2]) << 8) | UInt32(bytes[typeStart + 3])
            switch type {
            case 0x4948_4452: // IHDR
                guard length >= 13 else { return nil }
                width = Int(be32(bytes, start))
                height = Int(be32(bytes, start + 4))
                bitDepth = Int(bytes[start + 8])
                colorType = Int(bytes[start + 9])
                interlace = Int(bytes[start + 12])
            case 0x504C_5445: // PLTE
                palette = Array(bytes[start..<(start + length)])
            case 0x7452_4E53: // tRNS
                transparency = Array(bytes[start..<(start + length)])
            case 0x4944_4154: // IDAT
                idat.append(contentsOf: bytes[start..<(start + length)])
            case 0x4945_4E44: // IEND
                break chunks
            default:
                break
            }
            pos = start + length + 4
        }

        guard width > 0, height > 0, width <= 8192, height <= 8192, bitDepth == 8, interlace == 0 else { return nil }
        let channels: Int
        switch colorType {
        case 0: channels = 1
        case 2: channels = 3
        case 3: channels = 1
        case 4: channels = 2
        case 6: channels = 4
        default: return nil
        }
        if colorType == 3 && palette.isEmpty { return nil }

        let stride = width * channels
        guard let raw = Inflate.zlib(idat, decompressedSize: height * (stride + 1)) else { return nil }
        let src = [UInt8](raw)

        var out = [UInt8](repeating: 255, count: width * height * 4)
        var prev = [UInt8](repeating: 0, count: stride)
        var cur = [UInt8](repeating: 0, count: stride)
        let bpp = channels

        for row in 0..<height {
            let rowStart = row * (stride + 1)
            let filter = src[rowStart]
            let line = rowStart + 1
            switch filter {
            case 0:
                for i in 0..<stride { cur[i] = src[line + i] }
            case 1:
                for i in 0..<stride { cur[i] = src[line + i] &+ (i >= bpp ? cur[i - bpp] : 0) }
            case 2:
                for i in 0..<stride { cur[i] = src[line + i] &+ prev[i] }
            case 3:
                for i in 0..<stride {
                    let left = i >= bpp ? Int(cur[i - bpp]) : 0
                    cur[i] = src[line + i] &+ UInt8((left + Int(prev[i])) / 2)
                }
            case 4:
                for i in 0..<stride {
                    let a = i >= bpp ? Int(cur[i - bpp]) : 0
                    let b = Int(prev[i])
                    let c = i >= bpp ? Int(prev[i - bpp]) : 0
                    cur[i] = src[line + i] &+ UInt8(paeth(a, b, c))
                }
            default:
                return nil
            }

            var o = row * width * 4
            switch colorType {
            case 0:
                for x in 0..<width {
                    let g = cur[x]
                    out[o] = g; out[o + 1] = g; out[o + 2] = g
                    o += 4
                }
            case 2:
                for x in 0..<width {
                    out[o] = cur[x * 3]; out[o + 1] = cur[x * 3 + 1]; out[o + 2] = cur[x * 3 + 2]
                    o += 4
                }
            case 3:
                for x in 0..<width {
                    let idx = Int(cur[x])
                    guard idx * 3 + 2 < palette.count else { return nil }
                    out[o] = palette[idx * 3]; out[o + 1] = palette[idx * 3 + 1]; out[o + 2] = palette[idx * 3 + 2]
                    out[o + 3] = idx < transparency.count ? transparency[idx] : 255
                    o += 4
                }
            case 4:
                for x in 0..<width {
                    let g = cur[x * 2]
                    out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = cur[x * 2 + 1]
                    o += 4
                }
            default: // 6
                for x in 0..<width {
                    out[o] = cur[x * 4]; out[o + 1] = cur[x * 4 + 1]; out[o + 2] = cur[x * 4 + 2]; out[o + 3] = cur[x * 4 + 3]
                    o += 4
                }
            }
            swap(&prev, &cur)
        }
        return RGBAImage(width: width, height: height, pixels: out)
    }

    private static func be32(_ b: [UInt8], _ i: Int) -> UInt32 {
        (UInt32(b[i]) << 24) | (UInt32(b[i + 1]) << 16) | (UInt32(b[i + 2]) << 8) | UInt32(b[i + 3])
    }

    private static func paeth(_ a: Int, _ b: Int, _ c: Int) -> Int {
        let p = a + b - c
        let pa = abs(p - a), pb = abs(p - b), pc = abs(p - c)
        if pa <= pb && pa <= pc { return a }
        if pb <= pc { return b }
        return c
    }
}
