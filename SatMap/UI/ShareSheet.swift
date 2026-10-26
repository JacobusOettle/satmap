import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The system share sheet (Save to Files, AirDrop, …), presented over whatever is on screen.
enum SharePresenter {
    /// `completion` runs when the share sheet closes, after the chosen action has finished with the items.
    static func present(_ items: [Any], completion: @escaping () -> Void) {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        guard var top = window?.rootViewController else { return completion() }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.completionWithItemsHandler = { _, _, _, _ in completion() }
        vc.popoverPresentationController?.sourceView = top.view
        vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        top.present(vc, animated: true)
    }
}

extension UTType {
    /// MBTiles tile database (declared in Info.plist).
    static let mbtiles = UTType(importedAs: "com.mapbox.mbtiles")
}
