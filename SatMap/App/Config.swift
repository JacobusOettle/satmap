import Foundation

/// Map data sources. Change the URL templates here to use a different provider.
///
/// Licensing: check that your imagery source allows offline storage. Esri's terms only allow taking World Imagery
/// offline through Esri's own SDKs, so for licensed offline imagery point `imageryTemplate` at a source you are
/// licensed to cache (or see README.md).
enum TileSources {
    /// Optional ArcGIS Location Platform API key. When set, imagery is requested from Esri's authenticated
    /// endpoint (counts toward your account's free monthly basemap tiles).
    static let esriAPIKey = ""

    /// Esri World Imagery. `blankTile=false` makes the server answer 404 where it has no imagery at a zoom
    /// level, instead of a grey "Map data not yet available" picture, so those tiles are skipped.
    static let imageryTemplate = esriAPIKey.isEmpty
        ? "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"
        : "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false&token=" + esriAPIKey

    /// Deepest imagery zoom ever requested. Rural areas typically stop at z18–19 and towns go to z20–21;
    /// the downloader discovers the real depth per place (see `DownloadLayer.pruneByParent`).
    static let imageryMaxZoom = 22

    /// AWS Open Data "Terrain Tiles" (Terrarium encoding), used for contour lines and height differences.
    static let terrainTemplate = "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png"

    static let attribution = "Powered by Esri · Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community · Elevation: Mapzen Terrain Tiles on AWS (USGS, NOAA, Copernicus and others)"

    static func imageryURL(_ t: TileID) -> URL {
        url(imageryTemplate, t)
    }

    static func terrainURL(_ t: TileID) -> URL {
        url(terrainTemplate, t)
    }

    private static func url(_ template: String, _ t: TileID) -> URL {
        let s = template
            .replacingOccurrences(of: "{z}", with: String(t.z))
            .replacingOccurrences(of: "{x}", with: String(t.x))
            .replacingOccurrences(of: "{y}", with: String(t.y))
        return URL(string: s)!
    }
}

/// GPS power policy.
enum LocationPolicy {
    /// Process (draw / record) at most one fix per interval while the app is on screen.
    static let foregroundInterval: TimeInterval = 2
    /// Record one fix per interval while the app is in the background. Between fixes the GPS is
    /// allowed to power down (accuracy lowered to 3 km) and is woken shortly before the next fix.
    static let backgroundInterval: TimeInterval = 10
    /// How long before the next background fix the GPS is woken (hot-start time).
    static let backgroundWakeLead: TimeInterval = 3
    /// No movement for this long → sleep mode (GPS off).
    static let stationaryTimeout: TimeInterval = 120
    /// Movement threshold used for stationary detection, metres (raised to the fix accuracy if worse).
    static let movementThreshold: Double = 15
    /// While sleeping, briefly turn the GPS on this often to check whether we moved
    /// (backup for when motion detection misses walking, e.g. phone held steady in hand).
    static let sleepPeekIntervalForeground: TimeInterval = 60
    static let sleepPeekIntervalBackground: TimeInterval = 120
    static let sleepPeekIntervalMeasuring: TimeInterval = 15
    /// Give up a peek after this long without a usable fix.
    static let peekTimeout: TimeInterval = 15
    /// Fixes worse than this are ignored for the track.
    static let maxTrackAccuracy: Double = 20
    /// Don't add a track point unless we moved at least this far, metres.
    static let minTrackSpacing: Double = 3
    /// Start a new dotted-line segment instead of joining across a gap this long and far.
    static let segmentBreakSeconds: TimeInterval = 300
    static let segmentBreakMeters: Double = 200
}

/// Groups (docs/groups/SPEC.md §5.1): the invite domain and the other fixed values of the groups feature.
enum GroupConfig {
    /// The invite domain: the build setting `GROUPS_DOMAIN`, through the Info.plist key `SatMapGroupsDomain`. The
    /// placeholder `example.invalid` until the domain is chosen (§13.4).
    static let domain: String = {
        let value = (Bundle.main.object(forInfoDictionaryKey: "SatMapGroupsDomain") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty || value.hasPrefix("$(") ? "example.invalid" : value
    }()

    /// `https://{domain}`: invite links, the terms and the privacy notice.
    static var publicBaseURL: String { "https://" + domain }

    /// The API and WebSocket base: `https://{domain}`, or for tests the launch argument `-groupsBaseURL <url>` (read
    /// only, never saved). Invite links always use `domain`.
    static var apiBaseURL: URL {
        if let text = UserDefaults.standard.string(forKey: "groupsBaseURL"), let url = URL(string: text),
           url.scheme == "http" || url.scheme == "https", url.host != nil {
            return url
        }
        return URL(string: publicBaseURL)!
    }

    static var termsURL: URL { URL(string: publicBaseURL + "/terms")! }
    static var privacyURL: URL { URL(string: publicBaseURL + "/privacy")! }

    /// Shown on the terms, the list, the group screen and the landing page. A release blocker until set (§14 R1).
    static let contactEmail = "groups@example.invalid"
    static let appStoreURL = ""
    static let playStoreURL = ""

    /// The terms version this app shows (§8.13); a later version shows the terms again.
    static let termsVersion = 1

    /// The Pro products (§10.1).
    static let proProducts = ProProducts(monthly: "pro.monthly", yearly: "pro.yearly", lifetime: "pro.lifetime")

    struct ProProducts {
        let monthly: String
        let yearly: String
        let lifetime: String
        var all: [String] { [yearly, monthly, lifetime] }
    }

    /// Demo mode (§8.15): the launch argument `-groupsDemo YES`. Groups then run against the core's fake server with
    /// the demo seed, a fake Pro store and memberships in memory (walkthroughs and screenshots).
    static var isDemo: Bool { UserDefaults.standard.bool(forKey: "groupsDemo") }
}
