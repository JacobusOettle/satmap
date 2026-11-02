import Foundation
import XCTest
@testable import SatMapCore

/// Invite links (docs/groups/SPEC.md §5.2): the shared `invite` and `inviteParse` vectors, as in the Android core.
final class InviteLinkTests: XCTestCase {
    private var v: GroupVectors!

    override func setUpWithError() throws {
        v = try GroupVectors.load()
    }

    func testURLsRoundTrip() throws {
        let link = try XCTUnwrap(InviteLink(token: v.invite.token, groupKey: unhex(v.inputs.groupKeyHex)))
        XCTAssertEqual(link.groupId, unhex(v.inputs.groupIdHex))
        XCTAssertEqual(link.groupIdText, v.inputs.groupId)
        XCTAssertEqual(link.url(domain: v.invite.domain), v.invite.url)
        XCTAssertEqual(link.schemeURL, v.invite.schemeUrl)
        XCTAssertEqual(InviteLink.parse(link.url(domain: v.invite.domain), domain: v.invite.domain), .invite(link))
        XCTAssertEqual(InviteLink.parse(link.schemeURL, domain: v.invite.domain), .invite(link))
        // The token is the group id and the invite secret.
        XCTAssertEqual(Base64URL.decode(v.invite.token), unhex(v.inputs.groupIdHex + v.inputs.inviteSecretHex))
    }

    func testEveryParseCase() throws {
        XCTAssertEqual(v.inviteParse.cases.count, 21)
        for c in v.inviteParse.cases {
            let result = InviteLink.parse(c.text, domain: v.inviteParse.domain)
            switch c.result {
            case "invite":
                guard case .invite(let link) = result else {
                    XCTFail("\(c.text): \(result)")
                    continue
                }
                XCTAssertEqual(link.token, c.token, c.text)
                XCTAssertEqual(hex(link.groupId), c.groupIdHex, c.text)
                XCTAssertEqual(hex(link.groupKey), c.groupKeyHex, c.text)
            case "missingKey":
                XCTAssertEqual(result, .missingKey, c.text)
            case "notInvite":
                XCTAssertEqual(result, .notInvite, c.text)
            default:
                XCTFail("unknown result \(c.result)")
            }
        }
    }

    func testOtherDomainsAndValidation() throws {
        let key = unhex(v.inputs.groupKeyHex)
        let link = try XCTUnwrap(InviteLink(token: v.invite.token, groupKey: key))
        XCTAssertEqual(InviteLink.parse(link.url(domain: "groups.satmap.app"), domain: "groups.satmap.app"), .invite(link))
        XCTAssertEqual(InviteLink.parse(link.url(domain: "groups.satmap.app"), domain: "example.invalid"), .notInvite)
        XCTAssertNil(InviteLink(token: String(v.invite.token.dropLast()), groupKey: key))
        XCTAssertNil(InviteLink(token: v.invite.token, groupKey: key.dropLast()))
        XCTAssertNil(InviteLink(token: String(v.invite.token.dropLast()) + "=", groupKey: key))
        // The scheme form wins when it comes first.
        let text = "first \(link.schemeURL) then \(link.url(domain: "example.invalid"))"
        XCTAssertEqual(InviteLink.parse(text, domain: "example.invalid"), .invite(link))
    }
}
