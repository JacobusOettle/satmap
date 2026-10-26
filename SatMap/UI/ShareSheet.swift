import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The system share sheet (Save to Files, AirDrop, …).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let onComplete: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.completionWithItemsHandler = { _, _, _, _ in onComplete() }
        return vc
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

extension UTType {
    /// MBTiles tile database (declared in Info.plist).
    static let mbtiles = UTType(importedAs: "com.mapbox.mbtiles")
}
