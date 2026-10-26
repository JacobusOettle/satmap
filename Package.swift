// swift-tools-version:5.9
// Builds and tests only the platform-independent core of the app (SatMap/Core) so it can be
// verified on Linux/CI with `swift test`. The iOS app itself is built from SatMap.xcodeproj.
import PackageDescription

let package = Package(
    name: "SatMapCore",
    platforms: [.macOS(.v13), .iOS(.v16)],
    targets: [
        .systemLibrary(name: "CSQLite", path: "Tests/Shims/CSQLite"),
        .systemLibrary(name: "CZlib", path: "Tests/Shims/CZlib"),
        .target(
            name: "SatMapCore",
            dependencies: [
                .target(name: "CSQLite", condition: .when(platforms: [.linux])),
                .target(name: "CZlib", condition: .when(platforms: [.linux])),
            ],
            path: "SatMap/Core"
        ),
        .testTarget(
            name: "SatMapCoreTests",
            dependencies: ["SatMapCore"],
            path: "Tests/SatMapCoreTests",
            resources: [.copy("Fixtures")]
        ),
    ]
)
