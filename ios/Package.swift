// swift-tools-version: 6.1

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
        .library(name: "OPKTerminalOperator", targets: ["OPKTerminalOperator"]),
        .executable(name: "OPKTerminalConformance", targets: ["OPKTerminalConformance"]),
    ],
    dependencies: [
        .package(
            url: "https://github.com/21-DOT-DEV/swift-secp256k1",
            exact: "0.23.2"
        ),
    ],
    targets: [
        .target(name: "OPKTerminalCore"),
        .target(
            name: "OPKTerminalRPC",
            dependencies: ["OPKTerminalCore"]
        ),
        .target(
            name: "OPKTerminalOperator",
            dependencies: [
                "OPKTerminalCore",
                "OPKTerminalRPC",
                .product(name: "P256K", package: "swift-secp256k1"),
            ]
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
        .testTarget(
            name: "OPKTerminalOperatorTests",
            dependencies: ["OPKTerminalCore", "OPKTerminalOperator"]
        ),
    ]
)
