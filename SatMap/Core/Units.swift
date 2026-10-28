import Foundation

/// How distances are shown: kilometres, miles or nautical miles. A tap on a measured distance (or the scale bar) moves
/// on to the next.
enum DistanceUnit: String, CaseIterable {
    case metric
    case imperial
    case nautical

    static let metresPerFoot = 0.3048
    static let metresPerMile = 1609.344
    static let metresPerNauticalMile = 1852.0

    var next: DistanceUnit {
        switch self {
        case .metric: return .imperial
        case .imperial: return .nautical
        case .nautical: return .metric
        }
    }

    /// Spoken: "kilometres", "miles", "nautical miles".
    var name: String {
        switch self {
        case .metric: return "kilometres"
        case .imperial: return "miles"
        case .nautical: return "nautical miles"
        }
    }

    /// "850 m", "1.25 km", "12.5 km", "125 km"; "850 ft", "1.25 mi"; "0.46 NM", "12.4 NM".
    func format(_ metres: Double) -> String {
        let m = max(0, metres.isFinite ? metres : 0)
        switch self {
        case .metric:
            if m < 999.5 { return "\(Int(m.rounded())) m" }
            return Self.decimal(m / 1000, "km")
        case .imperial:
            let feet = m / Self.metresPerFoot
            if feet < 999.5 { return "\(Int(feet.rounded())) ft" }
            return Self.decimal(m / Self.metresPerMile, "mi")
        case .nautical:
            return Self.decimal(m / Self.metresPerNauticalMile, "NM")
        }
    }

    /// 2 decimals under 10, 1 under 100, none from 100.
    private static func decimal(_ v: Double, _ symbol: String) -> String {
        if v < 9.995 { return String(format: "%.2f", v) + " " + symbol }
        if v < 99.95 { return String(format: "%.1f", v) + " " + symbol }
        return String(format: "%.0f", v) + " " + symbol
    }

    /// The scale bar: the longest round distance (1, 2 or 5 times a power of ten, in metres or kilometres, feet or
    /// miles, or nautical miles) whose bar fits in `maxWidth` points, its length in points and its label.
    func scale(metersPerPoint: Double, maxWidth: Double) -> (meters: Double, width: Double, label: String)? {
        guard metersPerPoint > 0, metersPerPoint.isFinite, maxWidth > 0 else { return nil }
        let most = metersPerPoint * maxWidth
        let (unitMetres, symbol): (Double, String)
        switch self {
        case .metric:
            guard let s = ScaleBar.choose(metersPerPoint: metersPerPoint, maxWidth: maxWidth) else { return nil }
            return (s.meters, s.width, ScaleBar.label(s.meters))
        case .imperial:
            (unitMetres, symbol) = most < Self.metresPerMile ? (Self.metresPerFoot, "ft") : (Self.metresPerMile, "mi")
        case .nautical:
            (unitMetres, symbol) = (Self.metresPerNauticalMile, "NM")
        }
        let units = most / unitMetres
        let power = pow(10, floor(log10(units)))
        let round = [5, 2, 1].map { $0 * power }.first { $0 <= units * (1 + 1e-9) } ?? power
        let meters = round * unitMetres
        let text = round >= 1 ? String(format: "%.0f", round) : String(format: "%g", (round * 1e6).rounded() / 1e6)
        return (meters, meters / metersPerPoint, "\(text) \(symbol)")
    }
}

/// How heights are shown: metres or feet. A tap on a height moves on to the other.
enum HeightUnit: String, CaseIterable {
    case metres
    case feet

    var next: HeightUnit { self == .metres ? .feet : .metres }
    var symbol: String { self == .metres ? "m" : "ft" }
    var name: String { self == .metres ? "metres" : "feet" }

    /// A height (metres) in this unit, rounded to whole ones.
    func whole(_ metres: Double) -> Int {
        Int((self == .metres ? metres : metres / DistanceUnit.metresPerFoot).rounded())
    }
}
