import Foundation
import XCTest
@testable import SatMapCore

/// The word filter (docs/groups/SPEC.md §8.12): the spec's table and the shared `wordFilter` vectors, as in the
/// Android core.
final class WordFilterTests: XCTestCase {
    private let testList = "badword\nworse*\n*nasty*\n"

    func testSpecTable() {
        let filter = WordFilter(list: testList)
        let cases: [(String, Bool, String?)] = [
            ("Badword", false, "•••"),
            ("b4dw0rd!", false, "•••!"),
            ("worsening", false, "•••"),
            ("supernastything", false, "•••"),
            ("n.a.s.t.y", false, "•••"),
            ("bad word", true, nil),
            ("Scunthorpe", true, nil),
            ("Kudu at the bridge", true, nil),
        ]
        for (text, allows, masked) in cases {
            XCTAssertEqual(filter.allows(text), allows, text)
            XCTAssertEqual(filter.masked(text), masked ?? text, text)
        }
    }

    func testVectors() throws {
        let v = try GroupVectors.load()
        let filter = WordFilter(list: v.wordFilter.list)
        XCTAssertEqual(v.wordFilter.cases.count, 16)
        for c in v.wordFilter.cases {
            XCTAssertEqual(filter.allows(c.text), c.allows, c.text)
            XCTAssertEqual(filter.masked(c.text), c.masked, c.text)
        }
    }

    func testListParsing() {
        // Comments, blank lines, spaces, tabs and CR around entries; "*" and "**" alone are exact entries.
        let filter = WordFilter(list: "# comment\r\n  badword \t\r\n\n\t*nasty*\r\n#worse*\nworse*\r\n*\n")
        XCTAssertFalse(filter.allows("badword"))
        XCTAssertFalse(filter.allows("supernasty"))
        XCTAssertFalse(filter.allows("worsen"))
        XCTAssertTrue(filter.allows("comment"))
        XCTAssertTrue(filter.allows("bad"))
        XCTAssertTrue(WordFilter(list: "").allows("anything at all"))
        XCTAssertEqual(WordFilter(list: "").masked("anything"), "anything")
        // Overlapping matches merge; text around them stays.
        let two = WordFilter(list: "kak\nkak*\n")
        XCTAssertEqual(two.masked("Kakkerlak kak!"), "••• •••!")
    }
}
