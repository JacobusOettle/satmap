#if canImport(CoreGraphics)
import CoreGraphics // CGPoint's and CGSize's Equatable, which Foundation alone doesn't bring on Apple platforms
#endif
import Foundation
import XCTest
@testable import SatMapCore

/// The map's buttons in one column, or a grid where it doesn't fit (groups §9.1); the same cases as Android's
/// `MapScreenPiecesTest`.
final class MapButtonGridTests: XCTestCase {
    /// The compass slot (44), then Groups, Terrain, Blocks, Offline maps, Go to coordinates, Measure and My location.
    private let sizes = [CGSize(width: 44, height: 44)] + Array(repeating: CGSize(width: 50, height: 50), count: 7)

    private func grid(_ maxHeight: CGFloat) -> MapButtonGrid {
        MapButtonGrid(sizes: sizes, cell: 50, gap: 12, maxHeight: maxHeight)
    }

    func testTheButtonsStayInOneColumnWhileItFits() {
        for maxHeight: CGFloat in [478, 900, .infinity] {
            let g = grid(maxHeight)
            XCTAssertEqual(g.size, CGSize(width: 50, height: 478))
            // The compass slot centred across the column at its top, the buttons 12 apart under it.
            XCTAssertEqual(g.offsets[0], CGPoint(x: 3, y: 0))
            XCTAssertEqual(Array(g.offsets.dropFirst()), (0..<7).map { CGPoint(x: 0, y: 56 + 62 * CGFloat($0)) })
        }
        // No map, so no compass slot: Groups at the top.
        let noCompass = MapButtonGrid(sizes: Array(sizes.dropFirst()), cell: 50, gap: 12, maxHeight: 422)
        XCTAssertEqual(noCompass.size, CGSize(width: 50, height: 422))
        XCTAssertEqual(noCompass.offsets, (0..<7).map { CGPoint(x: 0, y: 62 * CGFloat($0)) })
    }

    func testButtonsThatDontFitFillAGridFromTheBottomRight() {
        // One short: seven rows, the compass alone at the top of a second column, centred in its cell, beside Groups.
        var g = grid(477)
        XCTAssertEqual(g.size, CGSize(width: 112, height: 422))
        XCTAssertEqual(g.offsets[0], CGPoint(x: 3, y: 3))
        XCTAssertEqual(Array(g.offsets.dropFirst()), (0..<7).map { CGPoint(x: 62, y: 62 * CGFloat($0)) })

        // Room for three rows (landscape): Go to, Measure and My location on the right; Terrain, Blocks and Offline
        // maps; then the compass and Groups from the top of the leftmost column.
        g = grid(200)
        XCTAssertEqual(g.size, CGSize(width: 174, height: 174))
        XCTAssertEqual(g.offsets, [
            CGPoint(x: 3, y: 3), CGPoint(x: 0, y: 62),
            CGPoint(x: 62, y: 0), CGPoint(x: 62, y: 62), CGPoint(x: 62, y: 124),
            CGPoint(x: 124, y: 0), CGPoint(x: 124, y: 62), CGPoint(x: 124, y: 124),
        ])

        // No room even for one: a single row, My location at its right.
        g = grid(0)
        XCTAssertEqual(g.size, CGSize(width: 8 * 50 + 7 * 12, height: 50))
        XCTAssertEqual(g.offsets[0], CGPoint(x: 3, y: 3))
        XCTAssertEqual(Array(g.offsets.dropFirst()), (1..<8).map { CGPoint(x: 62 * CGFloat($0), y: 0) })
    }
}
