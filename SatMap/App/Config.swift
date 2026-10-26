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
    /// Give up a peek after this long without a usable fix.
    static let peekTimeout: TimeInterval = 15
    /// Fixes worse than this are ignored for the track.
    static let maxTrackAccuracy: Double = 30
    /// Don't add a track point unless we moved at least this far, metres.
    static let minTrackSpacing: Double = 3
    /// Start a new dotted-line segment instead of joining across a gap this long and far.
    static let segmentBreakSeconds: TimeInterval = 300
    static let segmentBreakMeters: Double = 200
}
