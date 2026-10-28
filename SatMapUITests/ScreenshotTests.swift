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
        setMeasure(.aiming)
        sleep(2)
        snap("02-measure-crosshair")
        setMeasure(.placed)
        walk(walkUphill)
        sleep(10) // elevations for the height difference
        snap("03-measure-live")

        // Blocks from the KML file.
        tap("Blocks")
        if !app.navigationBars["Blocks"].waitForExistence(timeout: 5) { tap("Blocks") } // a tap can be lost while the app is busy
        sleep(2)
        snap("04-blocks-list")
        XCTAssertTrue(app.switches.firstMatch.exists, "No switch to show or hide the file")
        XCTAssertTrue(app.buttons["useFileColours"].exists, "No colours checkbox")
        tapRow(startingWith: "Demo farm")
        sleep(25)
        snap("05-blocks-on-map")

        // Offline: download the area around the blocks.
        tap("Offline maps")
        sleep(2)
        snap("06-offline-maps")
        tap("Use the blocks in a KML file")
        tapRow(startingWith: "Demo farm")
        sleep(8) // size estimate
        snap("07-download-area")
        scrollTo(app.buttons["Download"].firstMatch)
        tap("Download")
        sleep(25)
        snap("08-downloading")

        tap("Offline maps")
        _ = app.staticTexts["Ready offline"].waitForExistence(timeout: 900)
        sleep(2)
        snap("09-offline-areas")

        // Choosing an area with the lasso (measuring switched off first for a clearer view).
        closeSheet("Offline maps")
        setMeasure(.off)
        openOfflineSheet()
        tap("Draw an area on the map")
        sleep(3)
        snap("10-draw-area")

        // The terrain button: contour lines with elevation colours, the colours alone, then neither.
        for _ in 0..<4 {
            if app.buttons["Contour lines"].waitForExistence(timeout: 2) { break }
            tap("Cancel")
        }
        setTerrain("Contour lines and elevation colours")
        sleep(12) // heights for the colours
        XCTAssertTrue(app.descendants(matching: .any)["colourKey"].firstMatch.exists, "No legend for the colours")
        XCTAssertTrue(app.descendants(matching: .any)["contourKey"].firstMatch.exists, "No legend for the contour lines")
        snap("22-contours-and-colours")
        setTerrain("Elevation colours")
        sleep(6)
        snap("23-elevation-colours")
        setTerrain("Contours and colours off")
        sleep(3)
        snap("11-contours-off")
        setTerrain("Contour lines")

        // Tap a spot: its coordinates at the top (your own at the bottom); tapping them copies them.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)).tap()
        let tapped = app.buttons["tappedCoordinates"]
        if tapped.waitForExistence(timeout: 10) {
            sleep(3)
            snap("15-coordinates")
            tapped.tap()
            XCTAssertEqual(tapped.label, "Copied")
            // Pressed and held, the pin is moved precisely with the finger (a glass square shows 15 m around it).
            sleep(3) // "Copied" goes
            let before = tapped.label
            let pin = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45))
            pin.press(forDuration: 0.9, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.43, dy: 0.45)))
            sleep(2)
            XCTAssertNotEqual(tapped.label, before, "Holding and dragging the pin didn't move it")
        } else {
            XCTFail("No coordinates for the tapped spot")
        }
        // A tap on a point in the KML file selects it, with its name.
        // (MapKit may give the point's name as its own text or as the whole annotation, dot on top.)
        let launch = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Launch 1")).firstMatch
        if launch.waitForExistence(timeout: 5) {
            let f = launch.frame
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX, dy: f.minY - 1)).tap()
            let name = app.descendants(matching: .any)["tappedName"].firstMatch
            XCTAssertTrue(name.waitForExistence(timeout: 5), "Tapping the KML point didn't select it")
            sleep(3)
            snap("24-kml-point")
        } else {
            XCTFail("The KML point isn't on the map")
        }
        // Drone mode: the airfields around, with how far they are from you and from the selected point, and where you
        // are from the nearest one. Zoomed out for a few of them (a two-finger tap is a level out; a pinch in the
        // simulator doesn't zoom out), then back.
        setTerrain("Drone mode: airfields and navaids")
        let map = app.maps.firstMatch
        for _ in 0..<5 {
            map.twoFingerTap()
            sleep(2)
        }
        sleep(8)
        XCTAssertTrue(app.descendants(matching: .any)["droneCard"].firstMatch.exists, "No reporting card in drone mode")
        // On the map itself, not only in the card ("6.1 NM NE of FASH Stellenbosch").
        let airfield = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label MATCHES %@", "(Airport |Airstrip )?FASH Stellenbosch")).firstMatch
        XCTAssertTrue(airfield.exists, "Stellenbosch airfield (FASH) isn't on the map in drone mode")
        snap("25-drone-mode")
        setTerrain("Contour lines")
        // Back in close: My location, then again for 300 m across.
        tap("My location")
        sleep(2)
        tap("My location")
        sleep(4)
        XCTAssertTrue(app.buttons["myCoordinates"].exists, "No coordinates of your position")
        // The heights beside both; a tap shows them in feet, another in metres again.
        for id in ["myHeight", "tappedHeight"] {
            let height = app.descendants(matching: .any)[id].firstMatch
            XCTAssertTrue(height.exists, "No height beside \(id)")
            XCTAssertTrue((height.value as? String)?.contains("above sea level") == true,
                          "\(id) has no height: \(String(describing: height.value))")
        }
        let tappedHeight = app.descendants(matching: .any)["tappedHeight"].firstMatch
        tappedHeight.tap()
        sleep(1)
        XCTAssertTrue((tappedHeight.value as? String)?.contains(" ft ") == true, "Height not in feet: \(String(describing: tappedHeight.value))")
        tappedHeight.tap()
        sleep(1)
        XCTAssertTrue((tappedHeight.value as? String)?.contains(" m ") == true, "Height not back in metres")

        // Measuring between two points: a tap anywhere puts a point at the crosshair; the map is moved between them.
        setMeasure(.aiming)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.4)).tap() // A, in the middle
        sleep(2)
        dragMap(dx: 0.25, dy: 0)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.62)).tap() // B
        sleep(8) // elevations of both points
        XCTAssertEqual(currentMeasureState(), .pair)
        snap("16-measure-two-points")
        // A tap on the distance: miles, then nautical miles, then kilometres again.
        let distance = app.descendants(matching: .any)["measuredDistance"].firstMatch
        if distance.exists {
            distance.tap()
            sleep(1)
            XCTAssertTrue(distance.label.hasSuffix(" mi") || distance.label.hasSuffix(" ft"), "Not in miles: \(distance.label)")
            distance.tap()
            sleep(1)
            XCTAssertTrue(distance.label.hasSuffix(" NM"), "Not in nautical miles: \(distance.label)")
            distance.tap()
            sleep(1)
            XCTAssertTrue(distance.label.hasSuffix(" km") || distance.label.hasSuffix(" m"), "Not back in km: \(distance.label)")
        } else {
            XCTFail("No measured distance to tap")
        }

        // Two more points, then tap A again: the shape's area.
        dragMap(dx: 0, dy: 0.2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.45)).tap() // C
        sleep(2)
        dragMap(dx: -0.25, dy: 0)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.45)).tap() // D
        sleep(2)
        tap("Close shape") // (the map glides on a little after each drag, so where A ends up isn't known exactly)
        sleep(3)
        XCTAssertEqual(currentMeasureState(), .shape)
        XCTAssertTrue(app.staticTexts["Shape A–D · 4 corners"].exists, "The shape didn't get four corners")
        snap("19-measure-area")
        setMeasure(.off)

        // A position pasted in (any format, here degrees, minutes and seconds): measuring from it.
        tap("Go to coordinates")
        let field = app.descendants(matching: .any)["coordinateText"].firstMatch
        if field.waitForExistence(timeout: 10) {
            field.tap()
            field.typeText("33°55'10\"S 18°55'20\"E")
            sleep(1)
            snap("21-go-to-coordinates")
            tap("Show and measure from here")
            sleep(3)
            XCTAssertEqual(currentMeasureState(), .placed)
            setMeasure(.off)
        } else {
            XCTFail("No field for coordinates")
        }

        // The one-tap Kruger National Park roads (not downloaded here: several GB).
        openOfflineSheet()
        tap("Kruger National Park roads")
        if app.staticTexts["Roads and tracks"].waitForExistence(timeout: 120) {
            sleep(5) // size estimate
            scrollTo(app.buttons["Download"].firstMatch)
            snap("17-kruger-roads")
        } else {
            XCTFail("Kruger roads didn't load")
        }
        app.navigationBars.buttons.element(boundBy: 0).tap() // back to Offline maps
        sleep(1)

        // A place found by name: the Sabi Sand reserve, within its boundary.
        tap("Find a park, reserve or town")
        let search = app.textFields["placeSearchText"].firstMatch
        if search.waitForExistence(timeout: 10) {
            search.tap()
            search.typeText("Sabi Sand\n")
            let reserve = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Sabi Sand")).firstMatch
            if reserve.waitForExistence(timeout: 40) {
                sleep(1)
                snap("26-find-place")
                reserve.tap()
                // (The Download button is created only once the form is scrolled to it.)
                if app.navigationBars["Download area"].waitForExistence(timeout: 60) {
                    sleep(6) // boundary and size estimate
                    snap("27-place-area")
                    scrollTo(app.buttons["Download"].firstMatch)
                    XCTAssertTrue(app.buttons["Download"].exists, "No Download button for the reserve")
                } else {
                    XCTFail("The reserve's download screen didn't open")
                }
                app.navigationBars.buttons.element(boundBy: 0).tap() // back to the results
                sleep(1)
            } else {
                XCTFail("Sabi Sand wasn't found")
            }
        } else {
            XCTFail("No place search field")
        }
        app.navigationBars.buttons.element(boundBy: 0).tap() // back to Offline maps
        sleep(1)

        // Imagery along the simulated walk (not downloaded here, so the area counts below stay the same).
        tap("Where I've been")
        if app.staticTexts["Your track"].waitForExistence(timeout: 30) {
            sleep(3) // size estimate
            scrollTo(app.buttons["Download"].firstMatch)
            snap("20-where-ive-been")
        } else {
            XCTFail("The track didn't load")
        }
        app.navigationBars.buttons.element(boundBy: 0).tap() // back to Offline maps
        sleep(1)

        // The same blocks again, but satellite imagery only along the roads.
        tap("Use the blocks in a KML file")
        tapRow(startingWith: "Demo farm")
        let name = app.textFields.firstMatch
        if name.waitForExistence(timeout: 10) {
            name.tap()
            name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 20) + "Farm roads\n")
        }
        tapRow(startingWith: "Zoom 20")
        // Scroll until the Download button shows: the switch above it is then well inside the screen.
        scrollTo(app.buttons["Download"].firstMatch)
        // Without roads there is nothing true to show: stop rather than snap a whole-area download.
        guard setSwitch("Only along roads", on: true) else { return }
        // The public road servers are sometimes busy: try again, as a user would.
        let roadsLoaded = app.staticTexts["Roads and tracks"]
        for _ in 0..<60 {
            if roadsLoaded.exists { break }
            let again = app.buttons["Try again"]
            if again.exists && again.isHittable { again.tap() }
            sleep(4)
        }
        guard roadsLoaded.exists else { return XCTFail("Roads didn't load") }
        sleep(3) // size estimate
        scrollTo(app.buttons["Download"].firstMatch)
        snap("12-download-along-roads")
        tap("Download")
        sleep(5)
        openOfflineSheet()
        let ready = app.staticTexts.matching(NSPredicate(format: "label == %@", "Ready offline"))
        let alongRoads = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "along roads")).firstMatch
        for _ in 0..<180 {
            if ready.count >= 2 { break }
            sleep(5)
        }
        guard ready.count >= 2, alongRoads.exists else { return XCTFail("No area downloaded along roads") }
        snap("13-offline-along-roads")

        // Open the roads area and add one deeper level (only zoom 21 is downloaded).
        tapRow(startingWith: "Farm roads")
        if app.staticTexts["Imagery found down to"].waitForExistence(timeout: 20) {
            tapRow(startingWith: "Zoom 21")
            sleep(3) // size of the extra level
            scrollTo(app.buttons["Download zoom 21"].firstMatch)
            snap("18-area-deeper-level")
            tap("Download zoom 21")
            sleep(2)
            app.navigationBars.buttons.element(boundBy: 0).tap() // back to Offline maps
            for _ in 0..<120 {
                if ready.count >= 2 { break }
                sleep(5)
            }
            XCTAssertEqual(ready.count, 2, "The roads area didn't finish its extra level")
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "up to zoom 21")).firstMatch.exists)
        } else {
            XCTFail("The area screen didn't open")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }

        // Backup: export both areas through the share sheet.
        scrollTo(app.buttons["Export all offline maps"].firstMatch)
        tap("Export all offline maps")
        let saveToFiles = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Save to Files")).firstMatch
        guard saveToFiles.waitForExistence(timeout: 30) else { return XCTFail("No share sheet") }
        sleep(2)
        snap("14-export-backup")
    }

    // MARK: - Helpers

    private enum MeasureState {
        case off, aiming, placed, pair, shape
    }

    /// Taps the measure button (or its ✕ to stop) until the screen shows the wanted state (a tap can be lost while
    /// the app is busy).
    private func setMeasure(_ wanted: MeasureState) {
        for _ in 0..<4 {
            if currentMeasureState() == wanted { return }
            if wanted == .off {
                tap("Stop measuring")
            } else {
                tap("Measure")
            }
            sleep(2)
        }
        XCTAssertEqual(currentMeasureState(), wanted)
    }

    /// Taps a sheet's Done until the sheet has gone (a tap can be lost while the app is busy).
    private func closeSheet(_ title: String) {
        let bar = app.navigationBars[title]
        for _ in 0..<4 {
            guard bar.exists else { return }
            tap("Done")
            sleep(2)
        }
        XCTAssertFalse(bar.exists, "The \(title) sheet didn't close")
    }

    /// Moves the map by dragging it, a share of the screen each way (slowly, so it doesn't glide on).
    private func dragMap(dx: CGFloat, dy: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        from.press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5 + dx, dy: 0.45 + dy)))
        sleep(2)
    }

    private func currentMeasureState() -> MeasureState {
        let hint = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Move the crosshair onto the spot")).firstMatch
        if hint.exists { return .aiming }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "From A to")).firstMatch.exists { return .pair }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Shape A")).firstMatch.exists { return .shape }
        let panel = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR label BEGINSWITH %@ OR label BEGINSWITH %@",
                                                         "than you", "Height:", "Waiting for GPS")).firstMatch
        return panel.exists ? .placed : .off
    }

    /// Taps the terrain button (each tap the next of four) until it shows the wanted mode (a tap can be lost while the
    /// app is busy).
    private func setTerrain(_ wanted: String) {
        let button = app.buttons["Contour lines"].firstMatch
        for _ in 0..<8 {
            if button.value as? String == wanted { return }
            tap("Contour lines")
            sleep(2)
        }
        XCTAssertEqual(button.value as? String, wanted)
    }

    /// Opens the Offline maps sheet, tapping again if a tap was lost.
    private func openOfflineSheet() {
        for _ in 0..<4 {
            tap("Offline maps")
            if app.buttons["Draw an area on the map"].waitForExistence(timeout: 5) { return }
        }
        XCTFail("The Offline maps sheet didn't open")
    }

    /// Toggles a switch until it has the wanted value; returns whether it has. The row must be fully on screen.
    @discardableResult
    private func setSwitch(_ label: String, on: Bool) -> Bool {
        let row = app.switches[label].firstMatch
        guard row.waitForExistence(timeout: 10) else {
            XCTFail("No switch '\(label)'")
            return false
        }
        // On recent iOS the row is one switch element with the actual control inside it. Without that, tap the
        // switch's side of the row: its middle is the label, which doesn't toggle it.
        let control = row.switches.firstMatch
        func isOn() -> Bool { [row, control].contains { $0.exists && $0.value as? String == "1" } }
        for _ in 0..<3 {
            if isOn() == on { break }
            if control.exists && control.isHittable {
                control.tap()
            } else {
                row.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            }
            sleep(1)
        }
        XCTAssertEqual(isOn(), on, "Switch '\(label)' didn't change")
        return isOn() == on
    }

    /// Scrolls a form up, a third of the screen at a time, until the element is wholly on screen and clear of the
    /// navigation bar and the home indicator (forms only create the rows near the screen, and a tap on a sliver at
    /// the bottom edge is lost).
    private func scrollTo(_ element: XCUIElement) {
        let window = app.windows.firstMatch.frame
        for _ in 0..<8 {
            if element.exists && element.isHittable,
               element.frame.minY > window.minY + 100, element.frame.maxY < window.maxY - 40 { return }
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            start.press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)))
            sleep(1)
        }
    }

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
        guard button.waitForExistence(timeout: 10) else { return XCTFail("No button '\(label)'") }
        // A button under a sheet that is still closing can't be tapped yet (the tap would end the test).
        for _ in 0..<30 {
            if button.isHittable { break }
            sleep(1)
        }
        guard button.isHittable else {
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "not tappable: \(label)"
            shot.lifetime = .keepAlways
            add(shot)
            return XCTFail("Button '\(label)' can't be tapped")
        }
        button.tap()
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
