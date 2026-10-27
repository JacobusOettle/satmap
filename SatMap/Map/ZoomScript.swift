import MapKit
import os

/// For the zoom test (.github/workflows/zoomtest.yml): with the launch setting `zoomScript`, the map runs through the
/// same zooms and pans every time (no gestures, no following you), logging each step, so recordings of the ways of
/// drawing imagery (`SatelliteMode`) can be compared step by step.
enum ZoomScript {
    private static let log = Logger(subsystem: "com.jacobusoettle.satmap", category: "zoomscript")

    /// (tile zoom, east-west and north-south shift in screens) for each step, five seconds apart.
    private static let steps: [(zoom: Double, dx: Double, dy: Double)] = [
        (14, 0, 0),
        (15, 0, 0), (16, 0, 0), (17, 0, 0), (18, 0, 0), // in, to levels not seen yet
        (17, 0, 0), (16, 0, 0), (15, 0, 0), // out, to levels seen before
        (15, 1, 0), // a new place
        (17, 0, 0), // two levels in there
        (14, 0, 0), // three out
        (14, 0, -1), // and on to a new place
    ]

    static func runIfAsked(on map: MKMapView, model: AppModel) {
        guard UserDefaults.standard.bool(forKey: "zoomScript") else { return }
        let start = CLLocationCoordinate2D(latitude: -33.9183, longitude: 18.9212)
        var center = MKMapPoint(start)
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
            for (i, step) in steps.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 5) {
                    model.following = false
                    let width = MKMapSize.world.width / pow(2, step.zoom) * Double(map.bounds.width) / 256
                    let height = width * Double(map.bounds.height / max(1, map.bounds.width))
                    center = MKMapPoint(x: center.x + step.dx * width, y: center.y + step.dy * height)
                    let rect = MKMapRect(x: center.x - width / 2, y: center.y - height / 2, width: width, height: height)
                    log.info("step \(i, privacy: .public) zoom \(step.zoom, privacy: .public)")
                    map.setVisibleMapRect(rect, animated: i > 0)
                }
            }
        }
    }
}
