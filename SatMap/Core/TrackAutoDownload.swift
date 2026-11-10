import Foundation

/// "Auto download" on the Where I've been screen: while it is on (the default, for the whole app), SatMap updates the
/// track area by itself, exactly as that screen's Download button would, once you've been somewhere new. The rule is
/// here, the same in both apps; the app says when to look, gives it what it knows, and starts what it returns.
enum TrackAutoDownload {
    /// The setting (UserDefaults on the iPhone, the app's SharedPreferences on Android); on while never set.
    static let settingKey = "trackAutoDownload"
    static let enabledByDefault = true
    /// New parts of the track shorter than this in total wait for more.
    static let minimumNewMeters = 500.0
    /// At most one check this often (wall clock), whatever it finds.
    static let checkInterval: TimeInterval = 10 * 60
    /// Nothing starts with less than this free (downloads stop at 500 MB: `OfflineManager.minimumFreeSpace`).
    static let minimumFreeBytes: Int64 = 1 << 30
    /// A new track area's name, as the screen offers it.
    static let defaultName = "Where I've been"

    /// The track area, as far as the rule needs it.
    struct Area: Equatable {
        var name: String
        var lines: [[GeoPoint]]
        var maxZoom: Int
        var allowCellular: Bool
        /// Downloading right now (that run is left to go on) or paused (with Pause, for an export, or as restored from a
        /// backup: it stays paused until you resume it). Queued or waiting for the network doesn't count.
        var leaveAlone: Bool
    }

    /// What the Download button would be given (`updateTrackArea`).
    struct Update: Equatable {
        var name: String
        var lines: [[GeoPoint]]
        var maxZoom: Int
        var allowCellular: Bool
    }

    /// Whether to check now: the setting is on and the last check began at least `checkInterval` ago (or never, or the
    /// clock has been put back since).
    static func isDue(enabled: Bool, lastCheck: Date?, now: Date) -> Bool {
        guard enabled else { return false }
        guard let last = lastCheck else { return true }
        let since = now.timeIntervalSince(last)
        return since >= checkInterval || since < 0
    }

    /// The check: the update to start, or nil when there is nothing to do. Not while the track area is downloading or
    /// paused (`Area.leaveAlone`: a run goes on as it is, a pause stays), not with less than `minimumFreeBytes` free (or
    /// free space unknown), and only once the parts of the track away from the area's lines (`Roads.newParts`; with no
    /// track area, the whole track) are at least `minimumNewMeters` long in total. The settings are the track area's, or
    /// for a new one the screen's: its name, `newAreaZoom` (the zoom the screen would choose for a track area along these
    /// lines; asked only then) and no mobile data. Slow for a long track: not on the main thread.
    static func update(track: [TrackPoint], area: Area?, freeBytes: Int64?,
                       newAreaZoom: ([[GeoPoint]]) -> Int) -> Update? {
        if area?.leaveAlone == true { return nil }
        guard let free = freeBytes, free >= minimumFreeBytes else { return nil }
        let latest = Roads.trackLines(track)
        let previous = area?.lines ?? []
        guard Roads.length(Roads.newParts(of: latest, comparedWith: previous)) >= minimumNewMeters else { return nil }
        let lines = Roads.mergeTrack(previous: previous, latest: latest)
        guard let area = area else {
            return Update(name: defaultName, lines: lines, maxZoom: newAreaZoom(lines), allowCellular: false)
        }
        return Update(name: area.name, lines: lines, maxZoom: area.maxZoom, allowCellular: area.allowCellular)
    }
}
