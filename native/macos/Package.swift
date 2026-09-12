// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KmpUpdaterHelper",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "kmp-updater-helper", targets: ["UpdaterHelper"]),
        .executable(name: "kmp-updater-fixture-helper", targets: ["AcceptanceHelper"]),
    ],
    targets: [
        .target(name: "InstallerCore"),
        .executableTarget(name: "UpdaterHelper", dependencies: ["InstallerCore"]),
        .executableTarget(
            name: "AcceptanceHelper", dependencies: ["InstallerCore"],
            path: "Tests/AcceptanceHelper"),
        .testTarget(name: "InstallerCoreTests", dependencies: ["InstallerCore"]),
    ]
)
