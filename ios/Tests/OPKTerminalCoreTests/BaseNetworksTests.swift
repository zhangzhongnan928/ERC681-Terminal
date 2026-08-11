// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class BaseNetworksTests: XCTestCase {
    func testKnownBaseDescriptorsMatchSharedFixture() throws {
        let expected = try loadFixture().networks.map { network in
            BaseNetworkDescriptor(
                chainID: network.chainId,
                networkName: network.networkName,
                isTestnet: network.isTestnet,
                nativeCurrencySymbol: network.nativeCurrencySymbol,
                nativeCurrencyDecimals: network.nativeCurrencyDecimals,
                baseScanURL: try XCTUnwrap(URL(string: network.baseScanUrl))
            )
        }

        XCTAssertEqual(BaseNetworks.all, expected)
        XCTAssertEqual(Set(BaseNetworks.all.map(\.chainID)), [8_453, 84_532])
        XCTAssertEqual(BaseNetworks.descriptor(for: 8_453), BaseNetworks.mainnet)
        XCTAssertEqual(BaseNetworks.descriptor(for: 84_532), BaseNetworks.sepolia)
        XCTAssertNil(BaseNetworks.descriptor(for: 1))
    }

    func testMainnetAndSepoliaIdentitiesAreNotInterchangeable() {
        XCTAssertFalse(BaseNetworks.mainnet.isTestnet)
        XCTAssertTrue(BaseNetworks.sepolia.isTestnet)
        XCTAssertEqual(BaseNetworks.mainnet.baseScanURL.absoluteString, "https://basescan.org")
        XCTAssertEqual(
            BaseNetworks.sepolia.baseScanURL.absoluteString,
            "https://sepolia.basescan.org"
        )
    }

    private func loadFixture() throws -> BaseNetworkFixture {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(
            contentsOf: root.appendingPathComponent("conformance/opk-base-networks-v1.json")
        )
        return try JSONDecoder().decode(BaseNetworkFixture.self, from: data)
    }
}

private struct BaseNetworkFixture: Decodable {
    let networks: [BaseNetworkDescriptorFixture]
}

private struct BaseNetworkDescriptorFixture: Decodable {
    let chainId: UInt64
    let networkName: String
    let isTestnet: Bool
    let nativeCurrencySymbol: String
    let nativeCurrencyDecimals: UInt8
    let baseScanUrl: String
}
#endif
