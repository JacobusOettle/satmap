import CarPlay
import Combine
import UIKit

/// SatMap on the car's screen: the map (`CarPlayMapController`) under CarPlay's map template, with buttons to zoom in
/// and out, follow you again, move the map and change what it shows of the terrain (as the phone's terrain button). CarPlay shows it only when the app is signed
/// with Apple's CarPlay entitlement (see SatMap.entitlements).
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, CPMapTemplateDelegate {
    private var interfaceController: CPInterfaceController?
    private var window: CPWindow?
    private var map: CarPlayMapController?
    private let template = CPMapTemplate()
    /// The terrain button's title follows the mode, also when it is changed on the phone.
    private var terrainChanges: AnyCancellable?

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController, to window: CPWindow) {
        self.interfaceController = interfaceController
        self.window = window
        let model = AppModel.shared
        let map = CarPlayMapController(model: model)
        self.map = map
        model.carPlayMap = map
        window.rootViewController = map
        map.isShown = true

        template.mapDelegate = self
        template.automaticallyHidesNavigationBar = true
        template.hidesButtonsWithNavigationBar = false
        updateButtons()
        terrainChanges = model.$terrainMode
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.updateButtons() } }
        interfaceController.setRootTemplate(template, animated: false, completion: nil)
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnect interfaceController: CPInterfaceController, from window: CPWindow) {
        map?.isShown = false
        if AppModel.shared.carPlayMap === map { AppModel.shared.carPlayMap = nil }
        window.rootViewController = nil
        terrainChanges = nil
        map = nil
        self.window = nil
        self.interfaceController = nil
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        map?.isShown = true
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        map?.isShown = false
    }

    // MARK: - Buttons

    /// Zoom in, zoom out and follow you on the map; move the map and the terrain (contour lines, elevation colours,
    /// both, neither: each tap the next, the title says what is shown) in the bar along the top.
    private func updateButtons() {
        let model = AppModel.shared
        let zoomIn = CPMapButton { [weak self] _ in self?.map?.zoom(by: 0.5) }
        zoomIn.image = UIImage(systemName: "plus")
        let zoomOut = CPMapButton { [weak self] _ in self?.map?.zoom(by: 2) }
        zoomOut.image = UIImage(systemName: "minus")
        let follow = CPMapButton { [weak self] _ in self?.map?.recentre() }
        follow.image = UIImage(systemName: "location.fill")
        template.mapButtons = [zoomIn, zoomOut, follow]

        let panning = template.isPanningInterfaceVisible
        let move = CPBarButton(title: panning ? "Done" : "Move") { [weak self] _ in
            guard let self = self else { return }
            if self.template.isPanningInterfaceVisible {
                self.template.dismissPanningInterface(animated: true)
            } else {
                self.template.showPanningInterface(animated: true)
            }
        }
        let terrain = CPBarButton(title: Self.shortTitle(model.terrainMode)) { _ in
            model.terrainMode = model.terrainMode.next
        }
        template.leadingNavigationBarButtons = [move]
        template.trailingNavigationBarButtons = [terrain]
    }

    /// What the map shows of the terrain, short enough for the car's bar.
    private static func shortTitle(_ mode: TerrainMode) -> String {
        switch mode {
        case .contours: return "Contours"
        case .contoursAndColours: return "Contours + colours"
        case .colours: return "Colours"
        case .off: return "No contours"
        }
    }

    // MARK: - CPMapTemplateDelegate

    func mapTemplateDidShowPanningInterface(_ mapTemplate: CPMapTemplate) {
        updateButtons()
    }

    func mapTemplateDidDismissPanningInterface(_ mapTemplate: CPMapTemplate) {
        updateButtons()
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, panWith direction: CPMapTemplate.PanDirection) {
        var dx = 0.0, dy = 0.0
        if direction.contains(.left) { dx -= 1 }
        if direction.contains(.right) { dx += 1 }
        if direction.contains(.up) { dy -= 1 }
        if direction.contains(.down) { dy += 1 }
        map?.pan(dx: dx, dy: dy)
    }

    func mapTemplateDidBeginPanGesture(_ mapTemplate: CPMapTemplate) {
        map?.dragBegan()
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didUpdatePanGestureWithTranslation translation: CGPoint,
                     velocity: CGPoint) {
        map?.dragged(by: translation)
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didEndPanGestureWithVelocity velocity: CGPoint) {
        map?.dragEnded()
    }
}
