import Foundation
import XCTest
@testable import SatMapCore

/// The shared examples in Fixtures/testbuild/cases.json, which the Android core's tests read too.
final class TestBuildSwitchTests: XCTestCase {
    private func cases() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/testbuild/cases.json", withExtension: nil))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    /// A string from the fixture, nil for null or a missing key.
    private func text(_ value: Any?) -> String? {
        value as? String
    }

    /// A checked status as the fixture writes it (null: no information).
    private func status(_ value: Any?) throws -> TestBuildSwitch.Status? {
        guard let d = value as? [String: Any] else { return nil }
        let state = try XCTUnwrap(d["testBuilds"] as? String)
        XCTAssertTrue(state == "on" || state == "off", state)
        return TestBuildSwitch.Status(on: state == "on", title: text(d["title"]), message: text(d["message"]),
                                      appStoreURL: text(d["appStoreURL"]), playStoreURL: text(d["playStoreURL"]),
                                      websiteURL: text(d["websiteURL"]))
    }

    private func lock(_ value: Any?) throws -> TestBuildSwitch.Lock? {
        guard let d = value as? [String: Any] else { return nil }
        let buttons = try XCTUnwrap(d["buttons"] as? [[String: Any]]).map { b in
            TestBuildSwitch.Button(kind: try XCTUnwrap(TestBuildSwitch.Button.Kind(rawValue: XCTUnwrap(b["kind"] as? String))),
                                   title: try XCTUnwrap(b["title"] as? String), url: try XCTUnwrap(b["url"] as? String))
        }
        return TestBuildSwitch.Lock(title: try XCTUnwrap(d["title"] as? String), message: try XCTUnwrap(d["message"] as? String),
                                    buttons: buttons)
    }

    func testDefaults() throws {
        let d = try XCTUnwrap(try cases()["defaults"] as? [String: Any])
        XCTAssertEqual(d["title"] as? String, TestBuildSwitch.defaultTitle)
        XCTAssertEqual(d["message"] as? String, TestBuildSwitch.defaultMessage)
        XCTAssertEqual(d["maxTitleLength"] as? Int, TestBuildSwitch.maxTitleLength)
        XCTAssertEqual(d["maxMessageLength"] as? Int, TestBuildSwitch.maxMessageLength)
        XCTAssertEqual(d["maxFileBytes"] as? Int, TestBuildSwitch.maxFileBytes)
        XCTAssertEqual(d["checkIntervalSeconds"] as? Double, TestBuildSwitch.checkInterval)
        XCTAssertEqual(d["timeoutSeconds"] as? Double, TestBuildSwitch.timeout)
        XCTAssertEqual(d["savedKey"] as? String, TestBuildSwitch.savedKey)
        let ua = try XCTUnwrap(d["userAgent"] as? [String: Any])
        XCTAssertEqual(TestBuildSwitch.userAgent(version: try XCTUnwrap(ua["version"] as? String)), ua["value"] as? String)
        XCTAssertEqual(try status(try cases()["sample"]), TestBuildSwitch.sample)
    }

    func testFiles() throws {
        let files = try XCTUnwrap(try cases()["files"] as? [[String: Any]])
        XCTAssertGreaterThan(files.count, 20)
        for c in files {
            let name = try XCTUnwrap(c["name"] as? String)
            let data: Data
            if let raw = c["text"] as? String {
                data = Data(raw.utf8)
            } else {
                data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(c["file"]), options: [.fragmentsAllowed])
            }
            let parsed = TestBuildSwitch.parse(data)
            XCTAssertEqual(parsed, try status(c["expect"]), name)
            // What is kept reads back unchanged.
            if let p = parsed {
                XCTAssertEqual(TestBuildSwitch.saved(TestBuildSwitch.savedText(p)), p, name)
            }
        }
    }

    func testLinks() throws {
        let links = try XCTUnwrap(try cases()["links"] as? [[String: Any]])
        XCTAssertGreaterThan(links.count, 20)
        for l in links {
            let url = try XCTUnwrap(l["url"] as? String)
            let allowed = try XCTUnwrap(l["allowed"] as? Bool)
            XCTAssertEqual(TestBuildSwitch.allowedLink(url), allowed ? url : nil, url)
            // The same in a status file, for each link.
            for key in ["appStoreURL", "playStoreURL", "websiteURL"] {
                let file = try JSONSerialization.data(withJSONObject: ["schema": 1, "testBuilds": "off", key: url])
                let s = try XCTUnwrap(TestBuildSwitch.parse(file), url)
                let got = key == "appStoreURL" ? s.appStoreURL : key == "playStoreURL" ? s.playStoreURL : s.websiteURL
                XCTAssertEqual(got, allowed ? url : nil, "\(key): \(url)")
            }
        }
        XCTAssertNil(TestBuildSwitch.allowedLink(nil))
    }

    func testDecisions() throws {
        let decisions = try XCTUnwrap(try cases()["decisions"] as? [[String: Any]])
        XCTAssertGreaterThan(decisions.count, 8)
        for d in decisions {
            let name = try XCTUnwrap(d["name"] as? String)
            let channel = try XCTUnwrap(TestBuildSwitch.Channel(rawValue: XCTUnwrap(d["channel"] as? String)))
            let debug = try XCTUnwrap(d["debugBuild"] as? Bool)
            let expect = try XCTUnwrap(d["expect"] as? [String: Any])
            let known = TestBuildSwitch.known(saved: try status(d["saved"]), fetched: try status(d["fetched"]))
            XCTAssertEqual(known, try status(expect["known"]), name)
            XCTAssertEqual(TestBuildSwitch.lock(channel: channel, isDebugBuild: debug, status: known, platform: .iPhone),
                           try lock(expect["iPhone"]), "\(name) (iPhone)")
            XCTAssertEqual(TestBuildSwitch.lock(channel: channel, isDebugBuild: debug, status: known, platform: .android),
                           try lock(expect["android"]), "\(name) (Android)")
        }
    }

    func testChannels() throws {
        let channels = try XCTUnwrap(try cases()["channels"] as? [[String: Any]])
        for c in channels {
            let setting = text(c["setting"])
            let channel = TestBuildSwitch.Channel(setting: setting)
            XCTAssertEqual(channel.rawValue, c["channel"] as? String, String(describing: setting))
            XCTAssertEqual(TestBuildSwitch.checks(channel: channel, isDebugBuild: false), c["checks"] as? Bool,
                           String(describing: setting))
            XCTAssertFalse(TestBuildSwitch.checks(channel: channel, isDebugBuild: true), "A debug build never checks")
        }
    }

    func testRequestURLs() throws {
        let urls = try XCTUnwrap(try cases()["requestUrls"] as? [[String: Any]])
        for u in urls {
            let now = Date(timeIntervalSince1970: try XCTUnwrap(u["now"] as? Double))
            XCTAssertEqual(TestBuildSwitch.requestURL(try XCTUnwrap(u["base"] as? String), now: now), u["url"] as? String)
        }
    }

    func testTooLongAFileGivesNoInformation() throws {
        let padding = String(repeating: " ", count: TestBuildSwitch.maxFileBytes)
        let file = Data("{\"schema\": 1, \"testBuilds\": \"off\"}\(padding)".utf8)
        XCTAssertNil(TestBuildSwitch.parse(file))
        XCTAssertNotNil(TestBuildSwitch.parse(file.prefix(TestBuildSwitch.maxFileBytes)))
    }

    func testNothingKeptOrUnreadableCountsAsNeverFetched() {
        XCTAssertNil(TestBuildSwitch.saved(nil))
        XCTAssertNil(TestBuildSwitch.saved(""))
        XCTAssertNil(TestBuildSwitch.saved("off"))
        XCTAssertNil(TestBuildSwitch.lock(channel: .test, isDebugBuild: false, status: TestBuildSwitch.saved("{"), platform: .iPhone))
        // The kept text is a status file.
        let kept = TestBuildSwitch.savedText(TestBuildSwitch.Status(on: false, title: "Off"))
        XCTAssertEqual(kept, #"{"schema":1,"testBuilds":"off","title":"Off"}"#)
    }

    func testTheSampleLocksWithEveryButtonOfItsPlatform() {
        let iPhone = TestBuildSwitch.lock(channel: .test, isDebugBuild: false, status: TestBuildSwitch.sample, platform: .iPhone)
        XCTAssertEqual(iPhone?.title, TestBuildSwitch.defaultTitle)
        XCTAssertEqual(iPhone?.buttons.map(\.kind), [.appStore, .website])
        XCTAssertEqual(iPhone?.buttons.map(\.title), ["Get it on the App Store", "Website"])
        let android = TestBuildSwitch.lock(channel: .test, isDebugBuild: false, status: TestBuildSwitch.sample, platform: .android)
        XCTAssertEqual(android?.buttons.map(\.kind), [.googlePlay, .website])
        // Every sample link passes the link check.
        for url in [TestBuildSwitch.sample.appStoreURL, TestBuildSwitch.sample.playStoreURL, TestBuildSwitch.sample.websiteURL] {
            XCTAssertNotNil(TestBuildSwitch.allowedLink(url))
        }
    }
}
