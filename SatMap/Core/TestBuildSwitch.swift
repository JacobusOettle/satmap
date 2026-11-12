import Foundation

/// Test builds can be switched off (README, "Test builds can be switched off"). The test builds CI publishes on GitHub
/// (the IPA and the APK) read a small status file; while it says "off" they lock with a full-screen notice that sends
/// people to the App Store or Google Play, and a later "on" unlocks them. Store builds and debug builds never check.
///
/// The rule is here, the same in both apps: which builds check, reading and checking the file, what is kept between
/// launches, and whether the app locks and with what. The app fetches the file, keeps the state and locks or unlocks
/// (`TestBuildGate`).
///
/// The status file (`schema` 1; unknown fields are ignored):
///
///     {"schema": 1, "testBuilds": "on" | "off", "title": string, "message": string,
///      "appStoreURL": string | null, "playStoreURL": string | null, "websiteURL": string | null, "updated": string}
enum TestBuildSwitch {
    /// The build's channel: the build setting `SATMAP_CHANNEL` through the Info.plist key `SatMapChannel` on the
    /// iPhone (`TestBuildConfig`), a Gradle property through `BuildConfig` on Android (android/DESIGN.md).
    enum Channel: String, Equatable {
        /// A test build (the IPA and APK on GitHub): it checks the switch.
        case test
        /// A store build (App Store, Google Play): it never checks.
        case store

        /// "store" only when the setting says exactly that; anything else, an empty setting or none is "test": a build
        /// that doesn't say it is a store build can be switched off.
        init(setting: String?) {
            self = setting == Channel.store.rawValue ? .store : .test
        }
    }

    /// The platform the lock is shown on: each shows its own store's button.
    enum Platform: Equatable {
        case iPhone
        case android
    }

    /// The only status file format understood.
    static let schema = 1
    /// Longer titles and messages are cut, in characters (Unicode code points, the same count in both apps).
    static let maxTitleLength = 120
    static let maxMessageLength = 500
    /// A longer file gives no information.
    static let maxFileBytes = 64 * 1024

    /// The lock's texts when the file gives none.
    static let defaultTitle = "This test version of Spoor has been switched off"
    static let defaultMessage = "Get Spoor from the App Store or Google Play to keep using it."

    /// The buttons' titles.
    static let appStoreButtonTitle = "Get it on the App Store"
    static let googlePlayButtonTitle = "Get it on Google Play"
    static let websiteButtonTitle = "Website"

    /// Test builds check at launch, on coming to the foreground and this often while in the foreground, in seconds
    /// (never in the background).
    static let checkInterval: TimeInterval = 5 * 60
    /// How long one request may take, in seconds.
    static let timeout: TimeInterval = 10

    /// Where the last known state is kept (UserDefaults on the iPhone, SharedPreferences on Android), as the text of a
    /// status file (`savedText`), so a build that has seen "off" starts locked, also offline.
    static let savedKey = "testBuildStatus"

    /// Links are shown only over https to these hosts, and to github.com only under `githubPathPrefix`.
    static let linkHosts: Set<String> = ["apps.apple.com", "play.google.com", "github.com"]
    static let githubPathPrefix = "/JacobusOettle/"

    /// What a status file says, checked: whether test builds are on, and the lock's texts and links as the file gives
    /// them (plain text, cut to length; only allowed links). Nil texts and links: the file gives none.
    struct Status: Equatable {
        var on: Bool
        var title: String?
        var message: String?
        var appStoreURL: String?
        var playStoreURL: String?
        var websiteURL: String?
    }

    /// A button on the lock: what it says and the link it opens.
    struct Button: Equatable {
        enum Kind: String, Equatable {
            case appStore
            case googlePlay
            case website
        }

        var kind: Kind
        var title: String
        var url: String
    }

    /// What the lock shows.
    struct Lock: Equatable {
        var title: String
        var message: String
        var buttons: [Button]
    }

    /// The walkthroughs' screenshot of the lock (a test-only launch argument on the iPhone, an instrumentation hook on
    /// Android): "off" with sample texts and links. Never saved, never fetched.
    static let sample = Status(
        on: false,
        title: defaultTitle,
        message: "Thanks for testing Spoor. Get Spoor from the App Store or Google Play to keep using it.",
        appStoreURL: "https://apps.apple.com/app/satmap/id0000000000",
        playStoreURL: "https://play.google.com/store/apps/details?id=com.koonap.spoor",
        websiteURL: "https://github.com/JacobusOettle/satmap")

    // MARK: - Which builds check

    /// Whether a build checks the switch at all: test builds that aren't debug builds.
    static func checks(channel: Channel, isDebugBuild: Bool) -> Bool {
        channel == .test && !isDebugBuild
    }

    // MARK: - Fetching

    /// The address to fetch: the status file's, with `t=<unix seconds>` added to its query so that no cache on the way
    /// answers with an old copy.
    static func requestURL(_ base: String, now: Date) -> String {
        let seconds = Int64(now.timeIntervalSince1970.rounded(.down))
        return base + (base.contains("?") ? "&" : "?") + "t=\(seconds)"
    }

    /// The requests' User-Agent: the app and its version, nothing else (no device or user identifier is ever sent).
    static func userAgent(version: String) -> String {
        "Spoor/" + version
    }

    // MARK: - Reading the file

    /// Reads a status file. Nil when it gives no information (the last known state is then kept): not a JSON object,
    /// longer than `maxFileBytes`, `schema` not the number 1, or `testBuilds` neither "on" nor "off". Unknown fields are
    /// ignored; a text or link that isn't a string, or a link that isn't allowed (`allowedLink`), counts as not given.
    static func parse(_ data: Data) -> Status? {
        guard data.count <= maxFileBytes,
              let object = try? JSONDecoder().decode([String: JSONValue].self, from: data),
              case .number(let version)? = object["schema"], version == Double(schema),
              case .string(let state)? = object["testBuilds"], state == "on" || state == "off" else { return nil }
        func string(_ key: String) -> String? {
            if case .string(let s)? = object[key] { return s }
            return nil
        }
        return Status(on: state == "on",
                      title: clean(string("title"), maxLength: maxTitleLength, multiline: false),
                      message: clean(string("message"), maxLength: maxMessageLength, multiline: true),
                      appStoreURL: allowedLink(string("appStoreURL")),
                      playStoreURL: allowedLink(string("playStoreURL")),
                      websiteURL: allowedLink(string("websiteURL")))
    }

    /// A status as the text of a status file, to keep (`savedKey`); `parse` reads it back unchanged.
    static func savedText(_ status: Status) -> String {
        let file = SavedFile(schema: schema, testBuilds: status.on ? "on" : "off", title: status.title,
                             message: status.message, appStoreURL: status.appStoreURL, playStoreURL: status.playStoreURL,
                             websiteURL: status.websiteURL)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(file)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    }

    /// The kept state read back: nil when nothing is kept, or it can't be read (as if never fetched).
    static func saved(_ text: String?) -> Status? {
        text.flatMap { parse(Data($0.utf8)) }
    }

    /// Plain text: control characters removed (line breaks and tabs become spaces in a title; in a message line breaks
    /// stay, as "\n"), spaces and line breaks at both ends trimmed, and cut to `maxLength` Unicode code points (spaces
    /// the cut leaves at the end trimmed too). Nil when nothing is left.
    static func clean(_ text: String?, maxLength: Int, multiline: Bool) -> String? {
        guard let text = text else { return nil }
        var plain = String.UnicodeScalarView()
        var afterCR = false
        for s in text.unicodeScalars {
            defer { afterCR = s == "\r" }
            switch s {
            case "\n" where afterCR:
                continue // the second half of "\r\n"
            case "\r", "\n":
                plain.append(multiline ? "\n" : " ")
            case "\t":
                plain.append(" ")
            default:
                if s.properties.generalCategory != .control { plain.append(s) }
            }
        }
        let trimmed = String(plain).trimmingCharacters(in: .whitespacesAndNewlines)
        let cut = String(String.UnicodeScalarView(trimmed.unicodeScalars.prefix(maxLength)))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cut.isEmpty ? nil : cut
    }

    /// The link as given when the lock may show it, else nil: only the characters a URL may hold (RFC 3986: letters,
    /// digits and `-._~:/?#[]@!$&'()*+,;=%`, so no spaces or backslashes), https (any case), no user name, password or
    /// port, the host exactly one of `linkHosts` (any case), on github.com a path starting with `githubPathPrefix`, and
    /// no "." or ".." path segment (also percent-encoded), which could lead somewhere else once resolved.
    static func allowedLink(_ text: String?) -> String? {
        guard let text = text, text.unicodeScalars.allSatisfy(isURLCharacter), let c = URLComponents(string: text),
              c.scheme?.lowercased() == "https", c.user == nil, c.password == nil, c.port == nil,
              let host = c.percentEncodedHost?.lowercased(), linkHosts.contains(host) else { return nil }
        if host == "github.com" && !c.percentEncodedPath.hasPrefix(githubPathPrefix) { return nil }
        let segments = c.path.split(separator: "/", omittingEmptySubsequences: false)
        guard !segments.contains(where: { $0 == "." || $0 == ".." }), !c.path.contains("\\") else { return nil }
        return text
    }

    private static func isURLCharacter(_ s: Unicode.Scalar) -> Bool {
        switch s {
        case "a"..."z", "A"..."Z", "0"..."9": return true
        default: return "-._~:/?#[]@!$&'()*+,;=%".unicodeScalars.contains(s)
        }
    }

    // MARK: - The decision

    /// The state to keep after a check: what was fetched when it gives information, else what was known before (nil:
    /// never fetched, which counts as on).
    static func known(saved: Status?, fetched: Status?) -> Status? {
        fetched ?? saved
    }

    /// Whether the build locks, and with what. Only a build that checks (`checks`) with "off" known locks; never
    /// fetched counts as on. The title and message are the file's or the defaults; the buttons are for the links the
    /// file gives that this platform shows, in this order: the App Store (iPhone) or Google Play (Android), then the
    /// website (both).
    static func lock(channel: Channel, isDebugBuild: Bool, status: Status?, platform: Platform) -> Lock? {
        guard checks(channel: channel, isDebugBuild: isDebugBuild), let s = status, !s.on else { return nil }
        var buttons: [Button] = []
        switch platform {
        case .iPhone:
            if let url = s.appStoreURL { buttons.append(Button(kind: .appStore, title: appStoreButtonTitle, url: url)) }
        case .android:
            if let url = s.playStoreURL { buttons.append(Button(kind: .googlePlay, title: googlePlayButtonTitle, url: url)) }
        }
        if let url = s.websiteURL { buttons.append(Button(kind: .website, title: websiteButtonTitle, url: url)) }
        return Lock(title: s.title ?? defaultTitle, message: s.message ?? defaultMessage, buttons: buttons)
    }

    // MARK: - JSON

    /// A JSON value, as far as the status file needs it: booleans, numbers and strings apart from everything else.
    private enum JSONValue: Decodable {
        case bool(Bool)
        case number(Double)
        case string(String)
        /// null, an array or an object.
        case other

        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() {
                self = .other
            } else if let b = try? c.decode(Bool.self) {
                self = .bool(b)
            } else if let n = try? c.decode(Double.self) {
                self = .number(n)
            } else if let s = try? c.decode(String.self) {
                self = .string(s)
            } else {
                self = .other
            }
        }
    }

    /// The kept form: a status file (nil texts and links left out).
    private struct SavedFile: Encodable {
        let schema: Int
        let testBuilds: String
        let title: String?
        let message: String?
        let appStoreURL: String?
        let playStoreURL: String?
        let websiteURL: String?
    }
}
