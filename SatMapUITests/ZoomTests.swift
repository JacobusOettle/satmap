import CoreLocation
import XCTest

/// Zooms and pans the map the same way for each way of drawing the imagery (`SatelliteMode`), while
/// .github/workflows/zoomtest.yml records the screen, to see which one shows empty blocks while tiles load.
final class ZoomTests: XCTestCase {
    private let app = XCUIApplication()

    func testZoomSingle() { zoomAround(mode: "single") }
    func testZoomBackdrop() { zoomAround(mode: "backdrop") }
    func testZoomRenderer() { zoomAround(mode: "renderer") }
    func testZoomBackdropContours() { zoomAround(mode: "backdrop", contours: true) }

    private func zoomAround(mode: String, contours: Bool = false) {
        continueAfterFailure = true
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: -33.918292, longitude: 18.921204),
            altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, timestamp: Date()))
        app.launchArguments = ["-satelliteMode", mode, "-showContours", contours ? "YES" : "NO"]
        app.launch()
        allowPermissionPrompts()
        let map = app.maps.firstMatch
        XCTAssertTrue(map.waitForExistence(timeout: 30), "No map")
        sleep(25) // the first screen of tiles, and the zoom in on the first location fix
        // Zoom in one level at a time, then out, then a pinch each way and a pan, waiting after each.
        for _ in 0..<3 {
            map.doubleTap()
            sleep(4)
        }
        for _ in 0..<3 {
            map.twoFingerTap()
            sleep(4)
        }
        map.pinch(withScale: 6, velocity: 4)
        sleep(4)
        map.pinch(withScale: 0.15, velocity: -4)
        sleep(4)
        map.swipeLeft()
        sleep(4)
        map.swipeUp()
        sleep(4)
    }

    private func allowPermissionPrompts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow While Using App", "Allow Once", "Allow", "OK"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 5) {
                b.tap()
                break
            }
        }
    }
}
