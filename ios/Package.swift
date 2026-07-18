// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "OPKTerminalSDK",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "OPKTerminalCore", targets: ["OPKTerminalCore"]),
        .library(name: "OPKTerminalRPC", targets: ["OPKTerminalRPC"]),
        .executable(name: "OPKTerminalConformance", targets: ["OPKTerminalConformance"]),
    ],
    targets: [
        .target(name: "OPKTerminalCore"),
        .target(
            name: "OPKTerminalRPC",
            dependencies: ["OPKTerminalCore"]
        ),
        .executableTarget(
            name: "OPKTerminalConformance",
            dependencies: ["OPKTerminalCore", "OPKTerminalRPC"]
        ),
        .testTarget(
            name: "OPKTerminalCoreTests",
            dependencies: ["OPKTerminalCore"]
        ),
        .testTarget(
            name: "OPKTerminalRPCTests",
            dependencies: ["OPKTerminalCore", "OPKTerminalRPC"]
        ),
    ]
)
