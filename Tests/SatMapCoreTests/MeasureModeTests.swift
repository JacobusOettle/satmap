import Foundation
import XCTest
@testable import SatMapCore

final class MeasureModeTests: XCTestCase {
    private let spot = GeoPoint(lat: -33.9183, lon: 18.9212)
    private let other = GeoPoint(lat: -33.92, lon: 18.93)

    func testButtonStartsAtTheSelectedSpotOrWithTheCrosshair() {
        // Nothing selected: the crosshair, as before.
        XCTAssertEqual(MeasureMode.off.buttonAction(selected: nil), .start(.aiming))
        // A tapped spot (or KML point) selected: A there at once, measured from you.
        XCTAssertEqual(MeasureMode.off.buttonAction(selected: spot), .start(.placed(spot)))
        XCTAssertEqual(MeasureMode.placed(spot).points, [spot])
    }

    func testButtonOnceMeasuringIgnoresTheSelectedSpot() {
        for m in [MeasureMode.aiming, .placed(spot), .path([spot, other])] {
            XCTAssertEqual(m.buttonAction(selected: nil), .addPointAtCrosshair, "\(m)")
            XCTAssertEqual(m.buttonAction(selected: other), .addPointAtCrosshair, "\(m)")
        }
        let shape = MeasureMode.closed([spot, other, GeoPoint(lat: -33.93, lon: 18.92)])
        XCTAssertEqual(shape.buttonAction(selected: nil), .clear)
        XCTAssertEqual(shape.buttonAction(selected: spot), .clear)
    }

    func testLabelsAndMovingPoints() {
        XCTAssertEqual((0..<3).map(MeasureMode.label), ["A", "B", "C"])
        XCTAssertEqual(MeasureMode.label(26), "AA")
        XCTAssertEqual(MeasureMode.placed(spot).moving(0, to: other), .placed(other))
        XCTAssertEqual(MeasureMode.path([spot, spot]).moving(1, to: other), .path([spot, other]))
        XCTAssertEqual(MeasureMode.path([spot, spot]).moving(2, to: other), .path([spot, spot]))
    }
}
