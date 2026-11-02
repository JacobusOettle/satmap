import XCTest
@testable import SatMapCore

/// The iPhone groups screens' logic (SatMap/Groups/GroupsLogic.swift, compiled into this test target through the
/// symlink beside this file): routes and flows (§9.2), state texts (§9.3), dates, prices and the Pro cache (§10).
final class GroupsLogicTests: XCTestCase {
    // MARK: Flows

    func testCreateThenConsentEndsOnTheGroupsScreen() {
        var path = GroupsFlow.afterCreate("g1")
        XCTAssertEqual(path, [.consent("g1", .showDetail)])
        path = GroupsFlow.afterConsent(path, groupId: "g1", next: .showDetail)
        XCTAssertEqual(path, [.detail("g1")])
    }

    func testJoinThenConsentEndsOnTheGroupsScreen() {
        let path = GroupsFlow.afterConsent(GroupsFlow.afterJoin("g2"), groupId: "g2", next: .showDetail)
        XCTAssertEqual(path, [.detail("g2")])
    }

    func testConsentFromTheSwitchPopsBackToTheGroup() {
        let path: [GroupsRoute] = [.detail("g1"), .consent("g1", .back)]
        XCTAssertEqual(GroupsFlow.afterConsent(path, groupId: "g1", next: .back), [.detail("g1")])
        // Another group's consent leaves the stack alone.
        XCTAssertEqual(GroupsFlow.afterConsent(path, groupId: "g9", next: .back), path)
    }

    func testCreateEntryNeedsThePaywallWithoutPro() {
        XCTAssertEqual(GroupsFlow.createEntry(isPro: false), .paywall(.create))
        XCTAssertEqual(GroupsFlow.createEntry(isPro: true), .create)
    }

    func testPurchaseReplacesThePaywall() {
        XCTAssertEqual(GroupsFlow.afterPurchase([.paywall(.create)], next: .create), [.create])
        XCTAssertEqual(GroupsFlow.afterPurchase([.detail("g"), .paywall(.renew("g"))], next: .renew("g")), [.detail("g")])
        XCTAssertEqual(GroupsFlow.afterPurchase([.detail("g"), .paywall(.accept("g"))], next: .accept("g")), [.detail("g")])
    }

    func testScanClosesTheScanner() {
        XCTAssertEqual(GroupsFlow.afterScan([.joinLink, .scan]), [.joinLink])
    }

    func testAGroupThatLeftClosesItsScreens() {
        let path: [GroupsRoute] = [.joinLink, .detail("g1"), .member(groupId: "g1", memberId: "m"),
                                   .report(groupId: "g1", subject: .member("m"))]
        XCTAssertEqual(GroupsFlow.removingGroup(path, "g1"), [.joinLink])
        XCTAssertEqual(GroupsFlow.removingGroup(path, "g2"), path)
        XCTAssertEqual(GroupsFlow.pop(path).count, 3)
    }

    func testRouteGroupIds() {
        XCTAssertNil(GroupsRoute.create.groupId)
        XCTAssertNil(GroupsRoute.paywall(.create).groupId)
        XCTAssertEqual(GroupsRoute.paywall(.accept("g")).groupId, "g")
        XCTAssertEqual(GroupsRoute.dropPin(groupId: "g", lat: 1, lon: 2).groupId, "g")
        XCTAssertEqual(GroupItem.pin(groupId: "g", pinId: "p").id, "pin|g|p")
    }

    // MARK: Texts

    private func summary(members: Int = 5, sharing: Bool = true, paused: Bool = false, offer: Bool = false) -> GroupSummary {
        GroupSummary(groupId: "g", name: "Kruger trip", memberCount: members, isSharing: sharing, isPaused: paused,
                     hasOfferToYou: offer, isShown: false)
    }

    func testListSubtitles() {
        XCTAssertEqual(GroupTexts.listSubtitle(summary()), "5 members · Sharing")
        XCTAssertEqual(GroupTexts.listSubtitle(summary(sharing: false)), "5 members · Not sharing")
        XCTAssertEqual(GroupTexts.listSubtitle(summary(members: 1, sharing: false)), "1 member · Not sharing")
        XCTAssertEqual(GroupTexts.listSubtitle(summary(paused: true)), "Paused")
        XCTAssertEqual(GroupTexts.listSubtitle(summary(paused: true, offer: true)), "Wants you as owner")
    }

    func testMemberStates() {
        let now: Int64 = 1_800_000_000
        XCTAssertEqual(GroupTexts.memberSubtitle(role: .owner, state: .sharing(at: now - 125), now: now), "Owner · Sharing · 2 min ago")
        XCTAssertEqual(GroupTexts.memberSubtitle(role: .admin, state: .notSharing, now: now), "Admin · Not sharing")
        XCTAssertEqual(GroupTexts.memberSubtitle(role: .member, state: .lastSeen(at: now - 1500), now: now), "Last seen 25 min ago")
        XCTAssertEqual(GroupTexts.detailState(.sharing(at: now - 4), now: now), "Sharing · last position just now")
        XCTAssertEqual(GroupTexts.detailState(.lastSeen(at: now - 7200), now: now), "Last seen 2 h ago")
        let row = MemberRow(memberId: "m", name: "Sam", initials: "S", colourHex: "#E5484D", role: .owner, isYou: true,
                            isBlocked: false, state: .notSharing)
        XCTAssertEqual(GroupTexts.memberName(row), "Sam (you)")
    }

    func testPausedBanners() {
        XCTAssertEqual(GroupTexts.pausedBanner(group: "Drone team", reason: .proEnded, isOwner: false),
                       "Drone team is paused: the owner's SatMap Pro has ended.")
        XCTAssertEqual(GroupTexts.pausedBanner(group: "Drone team", reason: .proEnded, isOwner: true),
                       "Drone team is paused: your SatMap Pro has ended. Tap to renew.")
        XCTAssertEqual(GroupTexts.pausedBanner(group: "D", reason: .proRefunded, isOwner: false),
                       "D is paused: the owner's SatMap Pro was refunded or cancelled.")
        XCTAssertEqual(GroupTexts.pausedBanner(group: "D", reason: .proRefunded, isOwner: true),
                       "D is paused: your SatMap Pro was refunded or cancelled. Tap to renew.")
        XCTAssertEqual(GroupTexts.pausedBanner(group: "D", reason: .proUnverified, isOwner: false),
                       "D is paused: the owner's SatMap Pro couldn't be confirmed.")
        XCTAssertEqual(GroupTexts.pausedBanner(group: "D", reason: .proUnverified, isOwner: true),
                       "D is paused: your SatMap Pro couldn't be confirmed. Tap to fix.")
    }

    func testPausedSections() {
        XCTAssertEqual(GroupTexts.pausedSection(reason: .proEnded, isOwner: false, deleteDate: "29 Nov"),
                       "This group is paused because the owner's SatMap Pro has ended. Nobody can share their location or drop pins until the owner renews Pro or hands the group to a member who has Pro. If it stays paused, everything shared in the group is deleted on 29 Nov.")
        XCTAssertEqual(GroupTexts.pausedSection(reason: .proEnded, isOwner: true, deleteDate: "29 Nov"),
                       "This group is paused because your SatMap Pro has ended. Nobody can share their location or drop pins until you renew Pro or hand the group to a member who has Pro. If it stays paused, everything shared in the group is deleted on 29 Nov.")
        XCTAssertTrue(GroupTexts.pausedSection(reason: .proRefunded, isOwner: false, deleteDate: nil)
            .hasPrefix("This group is paused because the owner's SatMap Pro was refunded or cancelled. "))
        XCTAssertTrue(GroupTexts.pausedSection(reason: .proUnverified, isOwner: true, deleteDate: nil)
            .hasPrefix("This group is paused because your SatMap Pro couldn't be confirmed. "))
        XCTAssertFalse(GroupTexts.pausedSection(reason: nil, isOwner: false, deleteDate: nil).contains("deleted on"))
    }

    func testFootersAndReports() {
        XCTAssertEqual(GroupTexts.sharingFooter(isSharing: true, isPaused: true), "Sharing is off while the group is paused.")
        XCTAssertTrue(GroupTexts.sharingFooter(isSharing: true, isPaused: false).hasPrefix("Members see where you are"))
        XCTAssertTrue(GroupTexts.sharingFooter(isSharing: false, isPaused: false).hasPrefix("Members can't see where you are now."))
        XCTAssertEqual(GroupTexts.dropPinFooter(type: .animal, group: "Kruger trip"),
                       "Animal sightings disappear after 12 hours. Everyone in Kruger trip sees this pin.")
        XCTAssertEqual(GroupTexts.dropPinFooter(type: .water, group: "Kruger trip"), "Everyone in Kruger trip sees this pin until it's deleted.")
        XCTAssertEqual(GroupTexts.reportQuestion(.member("m")), "Why are you reporting this name?")
        XCTAssertEqual(GroupTexts.reportQuestion(.group), "Why are you reporting this group name?")
        XCTAssertEqual(GroupTexts.reportNotice(.pin("p")),
                       "We'll get a copy of this pin's text to review, without any position. We review reports within 24 hours.")
        XCTAssertEqual(ReportReason.allCases.map(GroupTexts.reportReason),
                       ["Offensive or hateful", "Harassment or bullying", "Spam", "Dangerous or illegal", "Something else"])
        XCTAssertEqual(GroupTexts.inviteShareText(group: "Kruger trip", link: "https://x/j/t#k"), "Join \"Kruger trip\" on SatMap: https://x/j/t#k")
    }

    func testPinTypesAndAlerts() {
        XCTAssertEqual(PinType.allCases.map(GroupTexts.pinShortLabel), ["Animal", "Shelter", "Water", "Danger", "Pothole", "Police"])
        XCTAssertEqual(GroupTexts.pinTitle(nil), "Pin")
        XCTAssertEqual(GroupTexts.pinTitle(.animal), "Animal sighting")
        XCTAssertEqual(GroupTexts.pinSymbol(.police), "shield.lefthalf.filled")
        XCTAssertEqual(GroupTexts.pinSymbol(nil), "mappin")
        XCTAssertEqual(GroupTexts.alert(GroupAlert(kind: .removed, groupId: "g", groupName: "Kruger trip")).title, "Removed from Kruger trip")
        XCTAssertEqual(GroupTexts.alert(GroupAlert(kind: .deleted, groupId: "g", groupName: "Kruger trip")).title, "Kruger trip was deleted")
        XCTAssertEqual(GroupTexts.alert(GroupAlert(kind: .gone, groupId: "g", groupName: "K")).message,
                       "The group no longer exists, or your membership ended.")
    }

    // MARK: Dates

    func testDatesFollowTheLocale() {
        let dates = GroupDates(locale: Locale(identifier: "en_GB"), timeZone: TimeZone(identifier: "Africa/Johannesburg")!)
        // 9 Nov 2026 14:05 in Johannesburg (UTC+2).
        let t: Int64 = 1_794_225_900
        let now: Int64 = 1_793_620_000 // 2 Nov 2026
        XCTAssertEqual(dates.day(t, now: now), "9 Nov")
        XCTAssertEqual(dates.time(t), "14:05")
        XCTAssertEqual(dates.day(t, now: t - 400 * 86_400), "9 Nov 2026")
        XCTAssertEqual(dates.moment(t, now: t + 60), "14:05")
        XCTAssertEqual(dates.moment(t, now: t + 86_400), "9 Nov, 14:05")
    }

    // MARK: Pro

    func testSavePercent() {
        XCTAssertEqual(ProPricing.savePercent(yearly: Decimal(string: "399.99")!, monthly: Decimal(string: "79.99")!), 58)
        XCTAssertNil(ProPricing.savePercent(yearly: 960, monthly: 80))
        XCTAssertNil(ProPricing.savePercent(yearly: 1200, monthly: 80))
        XCTAssertNil(ProPricing.savePercent(yearly: 100, monthly: 0))
    }

    func testPlans() {
        XCTAssertEqual(ProPlan.allCases, [.yearly, .monthly, .lifetime])
        XCTAssertEqual(ProPlan.yearly.detail(price: "R399.99"), "R399.99 a year")
        XCTAssertEqual(ProPlan.monthly.detail(price: "R79.99"), "R79.99 a month")
        XCTAssertEqual(ProPlan.lifetime.detail(price: "R1,199.99"), "R1,199.99 once")
        XCTAssertEqual(ProPlan.lifetime.buyTitle, "Buy")
        XCTAssertEqual(ProPlan.monthly.buyTitle, "Subscribe")
        XCTAssertEqual(ProPlan(productId: "pro.lifetime"), .lifetime)
        XCTAssertNil(ProPlan(productId: "pro"))
    }

    func testProCache() throws {
        let cache = ProCache(productId: "pro.yearly", expiresAt: 2_000, checkedAt: 1_000)
        XCTAssertTrue(cache.isPro(now: 1_999))
        XCTAssertFalse(cache.isPro(now: 2_000))
        XCTAssertTrue(ProCache(productId: "pro.lifetime", expiresAt: nil, checkedAt: 1).isPro(now: .max))
        XCTAssertEqual(ProCache.decode(cache.encoded()), cache)
        let json = try XCTUnwrap(String(data: XCTUnwrap(ProCache(productId: "pro.lifetime", expiresAt: nil, checkedAt: 5).encoded()), encoding: .utf8))
        XCTAssertTrue(json.contains("\"productId\":\"pro.lifetime\""))
        XCTAssertNil(ProCache.decode(Data("{}".utf8)))
    }

    /// The links the iPhone walkthrough types and opens (SatMapUITests/ScreenshotTests.swift) are the demo's.
    func testWalkthroughInviteLinks() throws {
        XCTAssertEqual(GroupDemo.inviteLink,
                       "https://example.invalid/j/EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8#MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8")
        guard case .invite(let braai) = InviteLink.parse(GroupDemo.inviteLink2, domain: GroupDemo.domain) else {
            return XCTFail("inviteLink2 doesn't parse")
        }
        XCTAssertEqual(braai.schemeURL,
                       "satmap://join/YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8#gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8")
        XCTAssertEqual(InviteLink.parse(braai.schemeURL, domain: GroupDemo.domain), .invite(braai))
    }

    func testColours() {
        let c = GroupColour.rgb("#E5484D")
        XCTAssertEqual(c?.red ?? 0, 229.0 / 255, accuracy: 1e-9)
        XCTAssertEqual(c?.green ?? 0, 72.0 / 255, accuracy: 1e-9)
        XCTAssertEqual(c?.blue ?? 0, 77.0 / 255, accuracy: 1e-9)
        XCTAssertNil(GroupColour.rgb("#E5484"))
        XCTAssertNil(GroupColour.rgb("nothex"))
    }
}
