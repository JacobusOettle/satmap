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
        // Groups run in demo mode (docs/groups/SPEC.md §8.15): the core's fake server with a fixed seed and a fake Pro
        // store. "Kruger trip" starts hidden, so shots 01–27 show only the (inactive) Groups button.
        app.launchArguments += ["-groupsDemo", "YES"]
        // Auto download stays on (shot 20 shows it) but doesn't act on the simulated walk, so the offline lists show
        // only the areas downloaded here.
        app.launchArguments += ["-skipTrackAutoDownload", "YES"]
        // The real test-build switch never locks the walkthrough: the check is turned off through this launch argument
        // (read only in the simulator and debug builds, `TestBuildConfig`). Shot 41 forces the lock instead.
        app.launchArguments += ["-testBuildSwitch", "skip"]
        // Drone link replays a recorded flight near the farm with three ADS-B aircraft through its decoder instead of
        // listening on the network (read only in the simulator and debug builds, `DroneLinkConfig`): shots 42 and 43.
        app.launchArguments += ["-droneLinkDemo", "YES"]
        mapScreenshots()
        groupScreenshots()
        testBuildOffScreenshot()
    }

    /// Shot 41: the full-screen lock test builds show when the switch is off, forced with sample texts and links
    /// (`-testBuildSwitch sampleOff`, the simulator / debug only), from a fresh start. Then the checks that nothing
    /// opens over it and that it scrolls.
    private func testBuildOffScreenshot() {
        app.terminate()
        app.launchArguments = ["-groupsDemo", "YES", "-testBuildSwitch", "sampleOff"]
        app.launch()
        let lock = app.descendants(matching: .any)["testBuildLock"].firstMatch
        guard lock.waitForExistence(timeout: 20) else { return XCTFail("The test-build lock didn't show") }
        XCTAssertTrue(app.buttons["testBuildLink-appStore"].exists, "No App Store button on the lock")
        XCTAssertTrue(app.buttons["testBuildLink-website"].exists, "No Website button on the lock")
        // A locked build asks for nothing, but a permission prompt left over from the earlier (unlocked) launches is a
        // SpringBoard alert that floats over every app window and can re-appear a moment after launch. Tap any such
        // alert away for a few seconds until the screen is clear, then snap.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        var clear = 0
        for _ in 0..<15 where clear < 3 {
            let button = ["Allow While Using App", "Allow Once", "Allow", "OK", "Don't Allow"]
                .map { springboard.buttons[$0] }.first { $0.exists && $0.isHittable }
            if let button = button {
                button.tap()
                clear = 0
            } else {
                clear += 1
            }
            sleep(1)
        }
        snap("41-test-build-off")

        // Nothing opens over the lock: the landing page's satmap:// invite link does nothing while locked.
        XCUIDevice.shared.system.open(URL(string: braaiClubSchemeLink)!)
        let open = springboard.buttons["Open"]
        if open.waitForExistence(timeout: 5) { open.tap() }
        sleep(4)
        XCTAssertFalse(app.buttons["joinGroup"].exists, "An invite link opened the join sheet while locked")
        XCTAssertTrue(app.buttons["testBuildLink-appStore"].isHittable, "Something covers the lock after an invite link")

        testBuildLockScrolls()
    }

    /// The lock scrolls when its texts don't fit (here the largest accessibility text size), so its buttons can always
    /// be reached.
    private func testBuildLockScrolls() {
        app.terminate()
        app.launchArguments = ["-groupsDemo", "YES", "-testBuildSwitch", "sampleOff",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let website = app.buttons["testBuildLink-website"]
        guard website.waitForExistence(timeout: 20) else { return XCTFail("The test-build lock didn't show with large text") }
        for _ in 0..<8 {
            if website.isHittable { break }
            app.swipeUp()
            sleep(1)
        }
        XCTAssertTrue(website.isHittable, "The lock's Website button can't be reached with large text")
    }

    /// Shots 01–27: the map, measuring, blocks, offline maps and drone mode. A step that can't go on returns early;
    /// the groups shots follow either way.
    private func mapScreenshots() {
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
        for _ in 0..<3 {
            sleep(2)
            guard app.navigationBars["Blocks"].exists else { break }
            tapRow(startingWith: "Demo farm") // a tap can be lost while the app is busy: the sheet then stays
        }
        sleep(23)
        snap("05-blocks-on-map")

        // Offline: download the blocks (and 15 m around the launch point).
        tap("Offline maps")
        sleep(2)
        snap("06-offline-maps")
        tap("Use a GPX or KML file")
        if app.navigationBars["Choose a file"].waitForExistence(timeout: 5) {
            sleep(1)
            snap("17-choose-file")
        } else {
            XCTFail("The list of files didn't open")
        }
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
        // Drone mode: the airfields around, with how far they are from you and from a tapped spot, and where you are
        // from the nearest one. Zoomed out by going to a spot three times as far as Stellenbosch's airfield (FASH)
        // (the map then fits you and the spot; two-finger gestures aren't reliable in the simulator), centred on you
        // again with My location, then a spot is tapped.
        setTerrain("Drone mode: airfields and navaids")
        tap("Go to coordinates")
        let goTo = app.descendants(matching: .any)["coordinateText"].firstMatch
        if goTo.waitForExistence(timeout: 10) {
            goTo.tap()
            goTo.typeText("-34.093892, 18.620506") // you + 3 × (FASH − you)
            sleep(1)
            tap("Show and measure from here")
            sleep(3)
            setMeasure(.off)
            tap("My location")
            sleep(3)
        } else {
            XCTFail("No field for coordinates")
        }
        let airfield = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label MATCHES %@", "(Airport |Airstrip )?FASH Stellenbosch")).firstMatch
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.62)).tap()
        sleep(6)
        XCTAssertTrue(app.descendants(matching: .any)["droneCard"].firstMatch.exists, "No reporting card in drone mode")
        XCTAssertTrue(app.buttons["tappedCoordinates"].exists, "Tapping the map in drone mode didn't place a pin")
        // On the map itself, not only in the card ("6.1 NM NE of FASH Stellenbosch").
        XCTAssertTrue(airfield.exists, "Stellenbosch airfield (FASH) isn't on the map in drone mode")
        snap("25-drone-mode")
        droneLinkScreenshots()
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

        // With a spot selected (the one tapped in drone mode), the measure button measures from it: A there at once,
        // with the distance from you (from off with a spot selected, the one tap that lands gives that). ✕ ends it, and
        // the spot with it.
        let tappedSpot = app.buttons["tappedCoordinates"]
        if tappedSpot.exists {
            let spot = tappedSpot.label
            setMeasure(.placed)
            XCTAssertEqual(tappedSpot.exists ? tappedSpot.label : nil, spot, "Point A isn't at the tapped spot")
            setMeasure(.off)
            XCTAssertFalse(tappedSpot.exists, "The tapped spot stayed after measuring from it")
        } else {
            XCTFail("No tapped spot to measure from")
        }
        // Nothing selected for what follows, or the measure button would place A rather than show the crosshair.
        for _ in 0..<3 where tappedSpot.exists {
            tap("Hide coordinates")
            sleep(1)
        }

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
        tap("Close shape") // (rather than A itself, whose spot after the drags isn't known to the point)
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
            dismissTypingTip()
            snap("21-go-to-coordinates")
            tap("Show and measure from here")
            sleep(3)
            XCTAssertEqual(currentMeasureState(), .placed)
            setMeasure(.off)
        } else {
            XCTFail("No field for coordinates")
        }

        // A place found by name: the Sabi Sand reserve, within its boundary.
        openOfflineSheet()
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
            let auto = app.switches["Auto download"].firstMatch
            XCTAssertTrue(auto.exists, "No Auto download switch")
            XCTAssertTrue([auto, auto.switches.firstMatch].contains { $0.exists && $0.value as? String == "1" },
                          "Auto download isn't on by default")
            snap("20-where-ive-been")
        } else {
            XCTFail("The track didn't load")
        }
        app.navigationBars.buttons.element(boundBy: 0).tap() // back to Offline maps
        sleep(1)

        // The same blocks again, but satellite imagery only along the roads inside them.
        tap("Use a GPX or KML file")
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
        let explained = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@",
                                                             "Satellite imagery only 15 m to each side of the roads inside the file's shapes")).firstMatch
        XCTAssertTrue(explained.exists, "No explanation of what a file saves along roads")
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

    // MARK: - Groups (docs/groups/SPEC.md §9.7)

    /// `GroupDemo.inviteLink` ("Weekend ride", not joined) and the satmap:// form of `GroupDemo.inviteLink2` ("Braai
    /// club"), as the core makes them (pinned by GroupsLogicTests).
    private let weekendRideLink = "https://example.invalid/j/EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8#MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"
    private let braaiClubSchemeLink = "satmap://join/YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8#gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8"

    /// Shots 28–40 and the link check (step 41), from a fresh start in demo mode: the terms not yet accepted, the seed
    /// made anew (whatever the steps before left open).
    private func groupScreenshots() {
        app.terminate()
        app.launch()
        sleep(6) // the map and the demo seed

        // 28: the terms, the first time Groups opens.
        openGroups()
        guard app.buttons["agreeTerms"].waitForExistence(timeout: 10) else { return XCTFail("The terms didn't show") }
        sleep(1)
        snap("28-groups-terms")

        // 29: the list.
        tap("agreeTerms")
        XCTAssertTrue(text(containing: "Kruger trip").waitForExistence(timeout: 10), "Kruger trip isn't listed")
        XCTAssertTrue(text(containing: "Drone team").exists, "Drone team isn't listed")
        sleep(1)
        snap("29-groups-list")

        // 30: the paywall, with the fake store's prices.
        tap("createGroupRow")
        XCTAssertTrue(text(containing: "R399.99 a year").waitForExistence(timeout: 10), "No yearly price")
        XCTAssertTrue(text(containing: "Save 58%").exists, "No saving on the yearly plan")
        sleep(1)
        snap("30-paywall")

        // 31: the fake purchase succeeds; the new group's names typed.
        tap("buyPro")
        let groupName = app.textFields["groupName"]
        if groupName.waitForExistence(timeout: 10) {
            groupName.tap()
            groupName.typeText("Weekend braai")
            let displayName = app.textFields["displayName"]
            displayName.tap()
            displayName.typeText("Sam\n")
            sleep(1)
            XCTAssertTrue(app.buttons["createGroup"].isEnabled, "Create group stays disabled with both names")
            snap("31-create-group")
        } else {
            XCTFail("The purchase didn't lead to the new group's screen")
        }
        tap("Cancel")

        // 32: the group's screen.
        tapRow(startingWith: "Kruger trip")
        guard app.switches["showOnMap"].waitForExistence(timeout: 10) else { return XCTFail("The group's screen didn't open") }
        sleep(3) // members' states and the invites
        snap("32-group-detail")

        // 33: an invite's QR code.
        scrollTo(app.buttons["inviteSomeone"].firstMatch)
        tap("inviteSomeone")
        if app.descendants(matching: .any)["inviteQR"].firstMatch.waitForExistence(timeout: 10) {
            sleep(1)
            snap("33-invite-qr")
        } else {
            XCTFail("No invite QR code")
        }
        tap("Done")

        // 34: joining with a pasted link.
        tap("Back")
        tap("joinLinkRow")
        let field = app.descendants(matching: .any)["inviteText"].firstMatch
        guard field.waitForExistence(timeout: 10) else { return XCTFail("No field for the invite link") }
        field.tap()
        field.typeText(weekendRideLink)
        tap("continueJoin")
        guard app.buttons["joinGroup"].waitForExistence(timeout: 15) else { return XCTFail("The join sheet didn't show the invite") }
        XCTAssertTrue(text(containing: "Weekend ride").exists, "The join sheet doesn't name the group")
        sleep(1)
        snap("34-join-group")

        // 35: consent after joining, then sharing. (The name is prefilled with the one typed in step 31.)
        if !app.buttons["joinGroup"].isEnabled {
            let name = app.textFields["displayName"]
            name.tap()
            name.typeText("Sam\n")
        }
        tap("joinGroup")
        guard app.buttons["consentShare"].waitForExistence(timeout: 15) else { return XCTFail("No consent screen after joining") }
        sleep(1)
        snap("35-share-consent")
        tap("consentShare")
        XCTAssertTrue(app.switches["showOnMap"].waitForExistence(timeout: 10), "The joined group's screen didn't follow the consent")

        // 36: Kruger trip on the map: the camera fits the members.
        tap("Back")
        tapRow(startingWith: "Kruger trip")
        guard app.switches["showOnMap"].waitForExistence(timeout: 10) else { return XCTFail("Kruger trip didn't open") }
        setSwitch("showOnMap", on: true)
        tap("Back")
        closeSheet("Groups")
        sleep(10) // the camera, the tiles, the members' live positions
        let members = app.descendants(matching: .any).matching(identifier: "groupMember")
        let pins = app.descendants(matching: .any).matching(identifier: "groupPin")
        XCTAssertGreaterThanOrEqual(members.count, 3, "The members' dots aren't on the map")
        XCTAssertEqual(pins.count, 6, "The group's six pins aren't on the map")
        XCTAssertTrue(app.buttons["Groups"].exists)
        snap("36-group-map")

        // 37: a pin dropped at an empty spot.
        if let spot = emptyMapSpot(avoiding: (members.allElementsBoundByIndex + pins.allElementsBoundByIndex).map(\.frame)) {
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: spot.x, dy: spot.y)).tap()
            if app.buttons["dropPin"].waitForExistence(timeout: 10) {
                tap("dropPin")
                tap("pinType-animal")
                let animal = app.textFields["pinAnimal"]
                if animal.waitForExistence(timeout: 10) {
                    animal.tap()
                    animal.typeText("Rhino\n")
                    sleep(1)
                    snap("37-drop-pin")
                } else {
                    XCTFail("No field for the animal")
                }
                scrollTo(app.buttons["dropPinConfirm"].firstMatch)
                tap("dropPinConfirm")
                sleep(3)
            } else {
                XCTFail("No Drop pin button at the tapped spot")
            }
        } else {
            XCTFail("No empty spot on the map")
        }

        // 38: the Kudu pin.
        let kudu = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Kudu")).firstMatch
        if kudu.waitForExistence(timeout: 10) {
            // The pin's circle is the top 30 pt of its view (its label is under it).
            let f = kudu.frame
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX, dy: f.minY + min(15, f.height / 2))).tap()
            if app.navigationBars["Animal sighting"].waitForExistence(timeout: 10) {
                sleep(1)
                snap("38-pin-detail")
            } else {
                XCTFail("The pin didn't open")
            }
            tap("Done")
        } else {
            XCTFail("The Kudu pin isn't on the map")
        }
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Rhino")).firstMatch.exists,
                      "The dropped pin isn't on the map")

        // 39: a member.
        openGroups()
        tapRow(startingWith: "Kruger trip")
        // The member row, not Thandi's dot on the map under the sheet (its label starts with the name too).
        let thandi = app.buttons.matching(identifier: "memberRow").matching(NSPredicate(format: "label BEGINSWITH %@", "Thandi")).firstMatch
        if thandi.waitForExistence(timeout: 10) {
            thandi.tap()
        } else {
            XCTFail("No member row for Thandi")
        }
        if app.navigationBars["Thandi"].waitForExistence(timeout: 10) {
            sleep(1)
            snap("39-member-detail")
        } else {
            XCTFail("Thandi didn't open")
        }

        // 40: a paused group on the map, with its banner.
        tap("Back")
        tap("Back")
        tapRow(startingWith: "Drone team")
        if app.switches["showOnMap"].waitForExistence(timeout: 10) {
            setSwitch("showOnMap", on: true)
        } else {
            XCTFail("Drone team didn't open")
        }
        tap("Back")
        closeSheet("Groups")
        if app.descendants(matching: .any)["groupPausedHint"].firstMatch.waitForExistence(timeout: 10) {
            sleep(3)
            snap("40-group-paused")
        } else {
            XCTFail("No paused banner on the map")
        }

        // 41: the link check (§5.6): the landing page's satmap:// link opens the join sheet.
        XCUIDevice.shared.system.open(URL(string: braaiClubSchemeLink)!)
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 5) { open.tap() }
        XCTAssertTrue(app.buttons["joinGroup"].waitForExistence(timeout: 10), "The invite link didn't open the join sheet")
        XCTAssertTrue(text(containing: "Braai club").exists, "The join sheet doesn't name Braai club")
    }

    /// The keyboard shows a one-time "slide to type" tip in place of its keys on some runs: it goes, so the screenshot
    /// shows the keyboard as on other runs.
    private func dismissTypingTip() {
        let tip = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Speed up your typing")).firstMatch
        guard tip.exists else { return }
        let continueButton = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "Continue", "continueJoin")).firstMatch
        if continueButton.exists {
            continueButton.tap()
            sleep(1)
        }
    }

    /// Any element whose label holds the text (a row's texts may be one button's label).
    private func text(containing t: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", t)).firstMatch
    }

    /// The Groups button, tapped again if a tap was lost.
    private func openGroups() {
        for _ in 0..<4 {
            tap("Groups")
            if app.navigationBars["Groups"].waitForExistence(timeout: 5) { return }
        }
        XCTFail("The Groups sheet didn't open")
    }

    /// Step 37: the first point of a 40 pt grid, scanning outwards from the map's middle, at least 44 pt from every
    /// member dot and group pin (and the KML point) and clear of the screen's overlays.
    private func emptyMapSpot(avoiding frames: [CGRect]) -> CGPoint? {
        let window = app.windows.firstMatch.frame
        var avoid = frames
        let launch = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Launch 1")).firstMatch
        if launch.exists { avoid.append(launch.frame) }
        // The coordinates and hints at the top, the controls (Groups first) on the right, the legends on the left, the
        // tapped spot's coordinates at the bottom.
        let clear = CGRect(x: window.minX + 90, y: window.minY + 200, width: window.width - 190, height: window.height - 460)
        let centre = CGPoint(x: window.midX, y: window.midY)
        var grid: [CGPoint] = []
        for i in -8...8 {
            for j in -10...10 {
                grid.append(CGPoint(x: centre.x + CGFloat(i) * 40, y: centre.y + CGFloat(j) * 40))
            }
        }
        grid.sort { hypot($0.x - centre.x, $0.y - centre.y) < hypot($1.x - centre.x, $1.y - centre.y) }
        return grid.first { p in
            clear.contains(p) && avoid.allSatisfy { f in
                hypot(p.x - min(max(p.x, f.minX), f.maxX), p.y - min(max(p.y, f.minY), f.maxY)) >= 44
            }
        }
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

    /// Moves the map by dragging it, a share of the screen each way: slowly, and held still before the finger is
    /// lifted, so the map has no speed to glide on with and moves by the drag.
    private func dragMap(dx: CGFloat, dy: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5 + dx, dy: 0.45 + dy))
        from.press(forDuration: 0.2, thenDragTo: to, withVelocity: .slow, thenHoldForDuration: 0.5)
        sleep(2)
    }

    private func currentMeasureState() -> MeasureState {
        let hint = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Move the crosshair onto the spot")).firstMatch
        if hint.exists { return .aiming }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "From A to")).firstMatch.exists { return .pair }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Shape A")).firstMatch.exists { return .shape }
        let panel = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label BEGINSWITH %@ OR label BEGINSWITH %@",
                                                         "than you", "level with you", "Height:", "Waiting for GPS")).firstMatch
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

    /// Shots 42–43: Drone link's settings, switched on for the first time (the disclaimer first), receiving the demo
    /// replay; then the drone on the map with the traffic around it, and the drone's panel.
    private func droneLinkScreenshots() {
        let button = app.buttons["droneLinkButton"]
        guard button.waitForExistence(timeout: 10) else { return XCTFail("No Drone link button in drone mode") }
        button.tap()
        let row = app.switches["droneLinkSwitch"].firstMatch
        guard row.waitForExistence(timeout: 10) else { return XCTFail("Drone link's settings didn't open") }
        let control = row.switches.firstMatch
        func isOn() -> Bool { [row, control].contains { $0.exists && $0.value as? String == "1" } }
        XCTAssertFalse(isOn(), "Drone link is on before it was switched on")
        let understand = app.alerts.buttons["I understand"]
        for _ in 0..<3 where !understand.exists && !isOn() {
            if control.exists && control.isHittable {
                control.tap()
            } else {
                row.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            }
            _ = understand.waitForExistence(timeout: 4)
        }
        XCTAssertTrue(understand.exists, "No disclaimer when Drone link is first switched on")
        if understand.exists { understand.tap() }
        sleep(1)
        XCTAssertTrue(isOn(), "Drone link didn't come on after the disclaimer")
        let status = app.descendants(matching: .any)["droneLinkStatus"].firstMatch
        let receiving = NSPredicate(format: "label CONTAINS %@", "Receiving from 1 drone")
        let heard = expectation(for: receiving, evaluatedWith: status)
        if XCTWaiter().wait(for: [heard], timeout: 20) != .completed {
            XCTFail("Drone link isn't receiving the demo flight: \(status.exists ? status.label : "no status")")
        }
        XCTAssertTrue(app.descendants(matching: .any)["droneLinkQGC"].firstMatch.label.contains("MAVLink Forwarding"),
                      "No QGroundControl steps")
        sleep(1)
        snap("42-drone-link-settings")
        tap("Done")
        sleep(4) // the flight climbs away from home and the aircraft move

        // On the map: the drone (over the farm, above your dot) and the aircraft; a tap on the drone opens its panel.
        let drone = app.descendants(matching: .any)["droneLinkDrone"].firstMatch
        guard drone.waitForExistence(timeout: 10) else { return XCTFail("The drone isn't on the map") }
        let aircraft = app.descendants(matching: .any).matching(identifier: "droneLinkAircraft")
        XCTAssertGreaterThanOrEqual(aircraft.count, 2, "The ADS-B aircraft aren't on the map")
        XCTAssertTrue(app.descendants(matching: .any)["droneLinkHome"].firstMatch.exists, "No home point")
        let panel = app.descendants(matching: .any)["droneLinkPanel"].firstMatch
        for _ in 0..<3 where !panel.exists {
            let f = drone.frame
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX, dy: f.minY + 16)).tap()
            _ = panel.waitForExistence(timeout: 4)
        }
        XCTAssertTrue(panel.exists, "Tapping the drone didn't open its panel")
        XCTAssertTrue(app.descendants(matching: .any)["droneLinkPanelDisclaimer"].firstMatch.exists, "No disclaimer in the panel")
        sleep(2)
        snap("43-drone-on-map")
        let close = app.buttons["droneLinkPanelClose"]
        if close.exists { close.tap() }
        sleep(1)
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
