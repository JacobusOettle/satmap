import SwiftUI
import UIKit

@main
struct SatMapApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            let model = AppModel.shared
            MapScreen(model: model, offline: model.offline, recorder: model.recorder)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    // A .kml/.kmz shared into the app ("Open in SatMap" / "Copy to SatMap").
                    model.kml.importFile(at: url) { file in
                        if let b = file?.document.bounds {
                            model.map?.zoom(to: b)
                        } else {
                            model.sheet = .blocks // shows why the import failed
                        }
                    }
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Create the model (and start recording) at launch, independent of the UI.
        _ = AppModel.shared
        return true
    }

    func applicationDidReceiveMemoryWarning(_ application: UIApplication) {
        TileRepository.shared.trimMemory()
        ImageryMemory.trimMemory()
    }
}
