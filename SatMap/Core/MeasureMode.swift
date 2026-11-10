import Foundation

/// The measuring tool. A point is placed at the crosshair, with the measure button or a tap anywhere on the map; with a
/// spot selected on the map (tapped, or a point in a KML file), the measure button puts A on it instead.
/// off → aiming → placed (distance from you to A) → path (A → B → C …, total distance) → closed (back to A: area).
enum MeasureMode: Equatable {
    case off
    /// Crosshair shown, waiting for the first point.
    case aiming
    /// One point: the live distance and height from you to it.
    case placed(GeoPoint)
    /// Two or more points: the line through them, its total length and the height from first to last.
    case path([GeoPoint])
    /// Three or more points joined back to the first: the shape's area and perimeter.
    case closed([GeoPoint])

    var points: [GeoPoint] {
        switch self {
        case .off, .aiming: return []
        case .placed(let a): return [a]
        case .path(let pts), .closed(let pts): return pts
        }
    }

    /// The same with point `i` moved (`PointAdjuster`).
    func moving(_ i: Int, to p: GeoPoint) -> MeasureMode {
        switch self {
        case .placed:
            return i == 0 ? .placed(p) : self
        case .path(var pts):
            guard pts.indices.contains(i) else { return self }
            pts[i] = p
            return .path(pts)
        case .closed(var pts):
            guard pts.indices.contains(i) else { return self }
            pts[i] = p
            return .closed(pts)
        case .off, .aiming:
            return self
        }
    }

    /// What the measure button does. With measuring off it starts: from the selected spot if there is one (A there at
    /// once, with the live distance and height from you to it; the map stays where it is), else with the crosshair.
    /// While aiming or with points it places one at the crosshair; after a shape is closed it clears.
    func buttonAction(selected: GeoPoint?) -> MeasureButtonAction {
        switch self {
        case .off:
            return .start(selected.map(MeasureMode.placed) ?? .aiming)
        case .aiming, .placed, .path:
            return .addPointAtCrosshair
        case .closed:
            return .clear
        }
    }

    /// "A", "B", … "Z", "AA", "AB", … "ZZ", "AAA", …
    static func label(_ i: Int) -> String {
        let letters = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        var n = i + 1, s = ""
        while n > 0 {
            n -= 1
            s = String(letters[n % 26]) + s
            n /= 26
        }
        return s
    }
}

/// The measure button's effect (`MeasureMode.buttonAction`).
enum MeasureButtonAction: Equatable {
    /// Measuring starts in this state: aiming, or A at the selected spot.
    case start(MeasureMode)
    /// A point at the crosshair (the next one, or A after aiming).
    case addPointAtCrosshair
    /// The closed shape goes.
    case clear
}
