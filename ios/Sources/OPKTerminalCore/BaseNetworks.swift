// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

/// Public Base chain identity metadata.
///
/// This deliberately excludes RPC endpoints and OPK deployment addresses. A descriptor does not
/// enable a chain, validate a deployment, or select an application default. Applications must pair
/// an explicitly selected descriptor with their own reviewed deployment configuration.
public struct BaseNetworkDescriptor: Hashable, Sendable {
    public let chainID: UInt64
    public let networkName: String
    public let isTestnet: Bool
    public let nativeCurrencySymbol: String
    public let nativeCurrencyDecimals: UInt8
    public let baseScanURL: URL

    init(
        chainID: UInt64,
        networkName: String,
        isTestnet: Bool,
        nativeCurrencySymbol: String,
        nativeCurrencyDecimals: UInt8,
        baseScanURL: URL
    ) {
        self.chainID = chainID
        self.networkName = networkName
        self.isTestnet = isTestnet
        self.nativeCurrencySymbol = nativeCurrencySymbol
        self.nativeCurrencyDecimals = nativeCurrencyDecimals
        self.baseScanURL = baseScanURL
    }
}

/// Stable identities for Base mainnet and Base Sepolia.
public enum BaseNetworks {
    public static let mainnet = BaseNetworkDescriptor(
        chainID: 8_453,
        networkName: "Base Mainnet",
        isTestnet: false,
        nativeCurrencySymbol: "ETH",
        nativeCurrencyDecimals: 18,
        baseScanURL: URL(string: "https://basescan.org")!
    )

    public static let sepolia = BaseNetworkDescriptor(
        chainID: 84_532,
        networkName: "Base Sepolia",
        isTestnet: true,
        nativeCurrencySymbol: "ETH",
        nativeCurrencyDecimals: 18,
        baseScanURL: URL(string: "https://sepolia.basescan.org")!
    )

    public static let all: [BaseNetworkDescriptor] = [mainnet, sepolia]

    public static func descriptor(for chainID: UInt64) -> BaseNetworkDescriptor? {
        all.first { $0.chainID == chainID }
    }
}
