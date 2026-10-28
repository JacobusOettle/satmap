import CoreLocation
import XCTest

/// Zooms and pans the map the same way each time, without and with contour lines, while
/// .github/workflows/zoomtest.yml records the screen, to see where it shows empty blocks while tiles load.
final class ZoomTests: XCTestCase {
    private let app = XCUIApplication()

    func testZoomRenderer() { zoomAround() }
    func testZoomRendererContours() { zoomAround(contours: true) }

    // The app moves the map itself through the same steps each time (ZoomScript): no gestures.
    func testScriptRenderer() { scripted() }
    func testScriptRendererContours() { scripted(contours: true) }

    private func scripted(contours: Bool = false) {
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: -33.918292, longitude: 18.921204),
            altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, timestamp: Date()))
        app.launchArguments = ["-showContours", contours ? "YES" : "NO", "-zoomScript", "YES"]
        app.launch()
        allowPermissionPrompts()
        XCTAssertTrue(app.maps.firstMatch.waitForExistence(timeout: 30), "No map")
        sleep(20 + 12 * 5 + 6 + 54) // the script's steps, with room for a slow start on the simulator
    }

    private func zoomAround(contours: Bool = false) {
        continueAfterFailure = true
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: -33.918292, longitude: 18.921204),
            altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, timestamp: Date()))
        app.launchArguments = ["-showContours", contours ? "YES" : "NO"]
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
