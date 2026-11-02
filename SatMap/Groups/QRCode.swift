import CoreImage
import UIKit

/// The invite QR code (docs/groups/SPEC.md §5.3): the https link exactly, byte mode, error correction M, a quiet zone
/// of 4 modules, black on white, scaled by whole pixels so the modules stay sharp.
enum QRCode {
    /// The code as an image with `pixelsPerModule` pixels per module, or nil if CoreImage can't make it.
    static func image(for text: String, pixelsPerModule: Int = 8) -> UIImage? {
        guard let modules = modules(for: text) else { return nil }
        let quiet = 4
        let size = modules.count + 2 * quiet
        let scale = max(1, pixelsPerModule)
        let side = size * scale
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            UIColor.black.setFill()
            for (y, row) in modules.enumerated() {
                for (x, dark) in row.enumerated() where dark {
                    ctx.fill(CGRect(x: (x + quiet) * scale, y: (y + quiet) * scale, width: scale, height: scale))
                }
            }
        }
    }

    /// The symbol's modules, row by row (true = dark), without any quiet zone: CoreImage's `CIQRCodeGenerator` output
    /// at one pixel per module, cropped to its dark modules (whatever margin CoreImage adds).
    static func modules(for text: String) -> [[Bool]]? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let extent = output.extent.integral
        let width = Int(extent.width), height = Int(extent.height)
        guard width > 0, height > 0, let cg = CIContext(options: [.useSoftwareRenderer: false]).createCGImage(output, from: extent) else {
            return nil
        }
        var pixels = [UInt8](repeating: 255, count: width * height)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        // The bitmap's first row is the image's top row.
        var minX = width, minY = height, maxX = -1, maxY = -1
        for y in 0..<height {
            for x in 0..<width where pixels[y * width + x] < 128 {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        return (minY...maxY).map { y in (minX...maxX).map { x in pixels[y * width + x] < 128 } }
    }
}
