import CoreLocation
import XCTest

/// Drives the app through its main screens on a simulator and saves screenshots
/// (run by .github/workflows/screenshots.yml). The simulated walk is on a vineyard near Stellenbosch;
/// the matching demo blocks (DemoFarm.kml) are placed in the app's storage by the workflow.
final class ScreenshotTests: XCTestCase {
    private let arrival: [(Double, Double)] = [
        (-33.918292, 18.921204),
        (-33.918426, 18.921365),
        (-33.918559, 18.921525),
        (-33.918693, 18.921686),
        (-33.918827, 18.921847),
        (-33.918960, 18.922008),
        (-33.919094, 18.922169),
        (-33.919227, 18.922330),
        (-33.919361, 18.922491),
        (-33.919539, 18.922616),
        (-33.919717, 18.922741),
        (-33.919895, 18.922867),
        (-33.920073, 18.922992),
        (-33.920251, 18.923117),
        (-33.920429, 18.923242),
        (-33.920620, 18.923319),
        (-33.920811, 18.923395),
        (-33.921001, 18.923472),
        (-33.921192, 18.923549),
        (-33.921383, 18.923625),
        (-33.921574, 18.923702),
        (-33.921765, 18.923779),
    ]

    private let walkUphill: [(Double, Double)] = [
        (-33.921765, 18.923779),
        (-33.921957, 18.923736),
        (-33.922150, 18.923693),
        (-33.922343, 18.923650),
        (-33.922536, 18.923607),
        (-33.922729, 18.923564),
        (-33.922922, 18.923521),
        (-33.923100, 18.923490),
        (-33.923278, 18.923459),
        (-33.923456, 18.923428),
        (-33.923634, 18.923397),
        (-33.923812, 18.923366),
        (-33.923990, 18.923335),
        (-33.924168, 18.923304),
        (-33.924346, 18.923273),
        (-33.924524, 18.923242),
    ]

    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    func testScreenshots() throws {
        setLocation(arrival[0])
        app.launch()
        allowPermissionPrompts()

        // Walk in along the farm road: the dotted track.
        walk(arrival)
        sleep(15) // satellite tiles and contours load
        snap("01-map")

        // Measure: crosshair, then marker, then walk uphill.
        tap("Measure")
        sleep(2)
        snap("02-measure-crosshair")
        tap("Measure")
        walk(walkUphill)
        sleep(10) // elevations for the height difference
        snap("03-measure-live")

        // Blocks from the KML file.
        tap("Blocks")
        sleep(2)
        snap("04-blocks-list")
        tapRow(startingWith: "Demo farm")
        sleep(12)
        snap("05-blocks-on-map")

        // Offline: download the area around the blocks.
        tap("Offline maps")
        sleep(2)
        snap("06-offline-maps")
        tap("Use the blocks in a KML file")
        tapRow(startingWith: "Demo farm")
        sleep(8) // size estimate
        snap("07-download-area")
        tap("Download")
        sleep(25)
        snap("08-downloading")

        tap("Offline maps")
        _ = app.staticTexts["Ready offline"].waitForExistence(timeout: 300)
        sleep(2)
        snap("09-offline-areas")

        // Choosing an area with the lasso.
        tap("Draw an area on the map")
        sleep(3)
        snap("10-draw-area")
    }

    // MARK: - Helpers

    private func setLocation(_ p: (Double, Double)) {
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: p.0, longitude: p.1),
            altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, timestamp: Date()))
    }

    private func walk(_ points: [(Double, Double)]) {
        for p in points {
            setLocation(p)
            Thread.sleep(forTimeInterval: 2.4) // the app uses one fix every 2 s on screen
        }
    }

    private func tap(_ label: String) {
        let button = app.buttons[label].firstMatch
        if button.waitForExistence(timeout: 10) {
            button.tap()
        } else {
            XCTFail("No button '\(label)'")
        }
    }

    /// List rows are buttons whose label combines their texts ("Demo farm, 7 shapes · …").
    private func tapRow(startingWith text: String) {
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", text)).firstMatch
        if row.waitForExistence(timeout: 10) {
            row.tap()
            return
        }
        let label = app.staticTexts[text].firstMatch
        if label.waitForExistence(timeout: 2) {
            label.tap()
        } else {
            XCTFail("No row '\(text)'")
        }
    }

    /// Location (and, on a device, motion) permission prompts come from SpringBoard.
    private func allowPermissionPrompts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<2 {
            var handled = false
            for label in ["Allow While Using App", "Allow Once", "Allow", "OK"] {
                let b = springboard.buttons[label]
                if b.waitForExistence(timeout: 6) {
                    b.tap()
                    handled = true
                    break
                }
            }
            if !handled { break }
        }
    }

    private func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        }
    }
}
