import Foundation
import XCTest
@testable import SatMapCore

final class TrackAutoDownloadTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let gigabyte: Int64 = 1 << 30

    /// A walk east along -24.99° from `start` (in steps of 0.0001°, about 10.09 m), one point a step.
    private func walk(from start: Int = 0, steps: Int, newSegment: Bool = true) -> [TrackPoint] {
        (0...steps).map { i in
            TrackPoint(lat: -24.99, lon: 31.59 + Double(start + i) * 0.0001, time: Double(start + i),
                       startsSegment: newSegment && i == 0)
        }
    }

    private func area(_ lines: [[GeoPoint]], leaveAlone: Bool = false) -> TrackAutoDownload.Area {
        TrackAutoDownload.Area(name: "My trips", lines: lines, maxZoom: 19, allowCellular: true, leaveAlone: leaveAlone)
    }

    private func noNewArea(_ lines: [[GeoPoint]]) -> Int {
        XCTFail("A track area has its own zoom")
        return 0
    }

    func testChecksAtMostEveryTenMinutes() {
        XCTAssertEqual(TrackAutoDownload.settingKey, "trackAutoDownload")
        XCTAssertTrue(TrackAutoDownload.enabledByDefault)
        XCTAssertTrue(TrackAutoDownload.isDue(enabled: true, lastCheck: nil, now: now))
        XCTAssertFalse(TrackAutoDownload.isDue(enabled: false, lastCheck: nil, now: now))
        XCTAssertFalse(TrackAutoDownload.isDue(enabled: true, lastCheck: now.addingTimeInterval(-599), now: now))
        XCTAssertTrue(TrackAutoDownload.isDue(enabled: true, lastCheck: now.addingTimeInterval(-600), now: now))
        XCTAssertFalse(TrackAutoDownload.isDue(enabled: false, lastCheck: now.addingTimeInterval(-3600), now: now))
        // The clock was put back since the last check: not held up until it catches up.
        XCTAssertTrue(TrackAutoDownload.isDue(enabled: true, lastCheck: now.addingTimeInterval(3600), now: now))
    }

    func testNewTrackAreaOnceTheTrackIsHalfAKilometre() {
        let short = walk(steps: 48) // about 484 m
        XCTAssertNil(TrackAutoDownload.update(track: short, area: nil, freeBytes: 50 * gigabyte) { _ in 20 })

        let track = walk(steps: 52) // about 525 m
        var asked: [[GeoPoint]]?
        let update = TrackAutoDownload.update(track: track, area: nil, freeBytes: 50 * gigabyte) { lines in
            asked = lines
            return 20
        }
        let lines = Roads.trackLines(track)
        XCTAssertEqual(update, TrackAutoDownload.Update(name: "Where I've been", lines: lines, maxZoom: 20, allowCellular: false))
        XCTAssertEqual(asked, lines, "The new area's zoom is chosen for its own lines")
        // A lone point has no length.
        XCTAssertNil(TrackAutoDownload.update(track: walk(steps: 0), area: nil, freeBytes: 50 * gigabyte) { _ in 20 })
    }

    func testTrackAreaUpdatedOnlyForHalfAKilometreAwayFromItsLines() {
        let saved = Roads.trackLines(walk(steps: 60))
        // The same walk, 300 m further: not enough yet.
        let little = walk(steps: 90)
        XCTAssertNil(TrackAutoDownload.update(track: little, area: area(saved), freeBytes: 50 * gigabyte, newAreaZoom: noNewArea))
        // 600 m further: the area's own settings, its lines and the new ones together.
        let more = walk(steps: 120)
        let update = TrackAutoDownload.update(track: more, area: area(saved), freeBytes: 50 * gigabyte, newAreaZoom: noNewArea)
        XCTAssertEqual(update, TrackAutoDownload.Update(name: "My trips",
                                                        lines: Roads.mergeTrack(previous: saved, latest: Roads.trackLines(more)),
                                                        maxZoom: 19, allowCellular: true))
        // Nothing new at all (the track is what the area has).
        XCTAssertNil(TrackAutoDownload.update(track: walk(steps: 60), area: area(saved), freeBytes: 50 * gigabyte, newAreaZoom: noNewArea))
    }

    func testEarlierTripsStayBesideANewOne() throws {
        let earlier = [[GeoPoint(lat: -25.05, lon: 31.70), GeoPoint(lat: -25.05, lon: 31.71)]]
        let track = walk(steps: 60)
        let update = try XCTUnwrap(TrackAutoDownload.update(track: track, area: area(earlier), freeBytes: 50 * gigabyte,
                                                            newAreaZoom: noNewArea))
        XCTAssertEqual(update.lines.count, 2)
        XCTAssertEqual(Roads.length(update.lines), Roads.length(earlier + Roads.trackLines(track)), accuracy: 1)
    }

    func testNothingWithoutATrack() {
        // Cleared, or no location yet: the track area keeps its lines (nothing to update), and none is made.
        let earlier = Roads.trackLines(walk(steps: 120))
        XCTAssertNil(TrackAutoDownload.update(track: [], area: area(earlier), freeBytes: 50 * gigabyte, newAreaZoom: noNewArea))
        XCTAssertNil(TrackAutoDownload.update(track: [], area: nil, freeBytes: 50 * gigabyte) { _ in 20 })
    }

    func testLeavesARunningOrPausedDownloadAndLowStorageAlone() {
        let saved = Roads.trackLines(walk(steps: 10))
        let far = walk(steps: 200)
        // Downloading right now (that run is left alone), or paused (the pause stays): nothing, however far you went.
        XCTAssertNil(TrackAutoDownload.update(track: far, area: area(saved, leaveAlone: true), freeBytes: 50 * gigabyte,
                                              newAreaZoom: noNewArea))
        // Under 1 GB free, or not known.
        XCTAssertNil(TrackAutoDownload.update(track: far, area: area(saved), freeBytes: gigabyte - 1, newAreaZoom: noNewArea))
        XCTAssertNil(TrackAutoDownload.update(track: far, area: nil, freeBytes: gigabyte - 1) { _ in 20 })
        XCTAssertNil(TrackAutoDownload.update(track: far, area: area(saved), freeBytes: nil, newAreaZoom: noNewArea))
        XCTAssertNotNil(TrackAutoDownload.update(track: far, area: area(saved), freeBytes: gigabyte, newAreaZoom: noNewArea))
    }
}
