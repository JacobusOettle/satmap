import Foundation
import XCTest
@testable import SatMapCore

/// Text cleaning (§2.5), initials (§9.5) and "ago" times (§9.3): the shared vectors, as in the Android core.
final class GroupTextTests: XCTestCase {
    private var v: GroupVectors!

    override func setUpWithError() throws {
        v = try GroupVectors.load()
    }

    private func field(_ name: String) throws -> GroupTextField {
        switch name {
        case "groupName": return .groupName
        case "displayName": return .displayName
        case "animal": return .animal
        case "note": return .note
        case "reportComment": return .reportComment
        default: throw XCTSkip("unknown field \(name)")
        }
    }

    func testCleaningVectors() throws {
        XCTAssertEqual(v.groupText.count, 30)
        for c in v.groupText {
            XCTAssertEqual(GroupText.clean(c.input, try field(c.field)), c.output, "\(c.field) \(c.input.unicodeScalars.map { String($0.value, radix: 16) })")
        }
    }

    func testCleaningDetails() {
        // NFC: a decomposed é counts as one code point.
        XCTAssertEqual(GroupText.clean("Zoe\u{308}", .displayName), "Zoë")
        XCTAssertEqual(GroupText.clean("Zoe\u{308}", .displayName)?.unicodeScalars.count, 3)
        XCTAssertEqual(GroupText.clean(String(repeating: "e\u{301}", count: 30), .displayName)?.unicodeScalars.count, 30)
        // The byte limit: 30 four-byte emoji are 120 bytes, the display name's maximum.
        XCTAssertNotNil(GroupText.clean(String(repeating: "🦏", count: 30), .displayName))
        XCTAssertNil(GroupText.clean("Kudu\u{7F}", .animal))
        XCTAssertNil(GroupText.clean("a\u{2029}b", .note))
        XCTAssertEqual(GroupText.clean("a\nb", .reportComment), "a\nb")
        XCTAssertNil(GroupText.clean("a\nb", .displayName))
        XCTAssertEqual(GroupText.clean("", .reportComment), "")
        XCTAssertNil(GroupText.clean("", .groupName))
        XCTAssertEqual(GroupText.clean("\u{200B}", .groupName), "\u{200B}")
        XCTAssertEqual(GroupText.codePoints("Zoë 🦏"), 5)
    }

    func testInitialsVectors() {
        XCTAssertEqual(v.initials.count, 14)
        for c in v.initials {
            XCTAssertEqual(GroupText.initials(c.name), c.initials, c.name)
        }
        XCTAssertEqual(GroupText.initials(""), "?")
        XCTAssertEqual(GroupText.initials("Sam\u{3000}Jones"), "SJ")
        XCTAssertEqual(GroupText.initials("1️⃣ one"), "1O")
        XCTAssertEqual(GroupText.initials("🇿"), "🇿")
    }

    func testAgoVectors() {
        XCTAssertEqual(v.ago.count, 12)
        for c in v.ago {
            XCTAssertEqual(GroupFormat.ago(c.seconds), c.text, "\(c.seconds)")
        }
    }
}
