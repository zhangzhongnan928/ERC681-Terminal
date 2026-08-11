#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class TerminalProvisioningTests: XCTestCase {
    func testSharedCanonicalAndAcceptedProvisioningPayloads() throws {
        let fixture = try loadFixture()
        let canonical = try TerminalProvisioningPayload.parse(fixture.provisioning.canonical)
        XCTAssertEqual(canonical.chainID, fixture.provisioning.chainId)
        XCTAssertEqual(canonical.vault.hex, fixture.provisioning.vault)
        XCTAssertEqual(canonical.token.hex, fixture.provisioning.token)
        XCTAssertEqual(canonical.operatorAddress.hex, fixture.provisioning.operatorAddress)
        XCTAssertEqual(canonical.canonicalString, fixture.provisioning.canonical)

        for rawPayload in fixture.provisioning.mustAccept {
            let parsed = try TerminalProvisioningPayload.parse(rawPayload)
            XCTAssertEqual(try TerminalProvisioningPayload.parse(parsed.canonicalString), parsed)
            XCTAssertEqual(parsed.vault.hex, parsed.vault.hex.lowercased())
            XCTAssertEqual(parsed.token.hex, parsed.token.hex.lowercased())
            XCTAssertEqual(parsed.operatorAddress.hex, parsed.operatorAddress.hex.lowercased())
        }
    }

    func testSharedProvisioningRejectVectors() throws {
        for rawPayload in try loadFixture().provisioning.mustReject {
            XCTAssertThrowsError(
                try TerminalProvisioningPayload.parse(rawPayload),
                "Expected provisioning payload to be rejected: \(rawPayload.debugDescription)"
            )
        }
    }

    func testOperatorPairingCanonicalAndSharedVectors() throws {
        let fixture = try loadFixture().operatorPairing
        let address = try TerminalOperatorPairingPayload.parse(fixture.canonical)
        XCTAssertEqual(address.hex, fixture.address)
        XCTAssertEqual(try TerminalOperatorPairingPayload.encode(address: address), fixture.canonical)

        for rawPayload in fixture.mustAccept {
            let parsed = try TerminalOperatorPairingPayload.parse(rawPayload)
            XCTAssertEqual(
                try TerminalOperatorPairingPayload.encode(address: parsed),
                rawPayload.lowercased()
            )
        }
        for rawPayload in fixture.mustReject {
            XCTAssertThrowsError(
                try TerminalOperatorPairingPayload.parse(rawPayload),
                "Expected pairing payload to be rejected: \(rawPayload.debugDescription)"
            )
        }
    }

    func testKnownNetworkPinsAndShippedCreate2VectorsMatchSharedFixture() throws {
        let fixture = try loadNetworkFixture()
        XCTAssertEqual(
            TerminalKnownChainProfile.supportedChainIDs,
            Set(fixture.networks.map(\.chainId))
        )
        for expected in fixture.networks {
            let profile = try XCTUnwrap(
                TerminalKnownChainProfile.profile(for: expected.chainId)
            )
            XCTAssertEqual(profile.networkName, expected.networkName)
            XCTAssertEqual(profile.isTestnet, expected.isTestnet)
            XCTAssertEqual(profile.nativeCurrencySymbol, expected.nativeCurrencySymbol)
            XCTAssertEqual(profile.nativeCurrencyDecimals, expected.nativeCurrencyDecimals)
            XCTAssertEqual(
                profile.minimumConfirmationBlocks,
                expected.minimumConfirmationBlocks
            )
            XCTAssertEqual(
                profile.defaultConfirmationBlocks,
                expected.defaultConfirmationBlocks
            )
            XCTAssertEqual(
                profile.minimumOperatorNativeReserve.decimalString,
                expected.minimumOperatorNativeReserveWei
            )
            XCTAssertEqual(profile.rpcEndpoint.absoluteString, expected.rpcUrl)
            XCTAssertEqual(profile.protocolVersion.rawValue, expected.protocolVersion)
            XCTAssertEqual(profile.factory.hex, expected.factory)
            XCTAssertEqual(
                profile.receiverImplementation.hex,
                expected.receiverImplementation
            )
            XCTAssertEqual(profile.vaultRuntimeCodeHash.hex, expected.vaultRuntimeCodeHash)
            XCTAssertEqual(profile.create2TestVector.vault.hex, expected.create2TestVector.vault)
            XCTAssertEqual(
                profile.create2TestVector.invoiceID.hex,
                expected.create2TestVector.invoiceId
            )
            XCTAssertEqual(profile.create2TestVector.salt.hex, expected.create2TestVector.salt)
            XCTAssertEqual(
                profile.create2TestVector.initCodeHash.hex,
                expected.create2TestVector.initCodeHash
            )
            XCTAssertEqual(
                profile.create2TestVector.expectedReceiver.hex,
                expected.create2TestVector.expectedReceiver
            )
            try ReceiverDerivation.validate(
                profile.create2TestVector,
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation
            )
        }
        XCTAssertNotNil(TerminalKnownChainProfile.profile(for: 8_453))
        XCTAssertNil(TerminalKnownChainProfile.profile(for: 1))
    }

    func testABIAddressPaddingAndBoundedCanonicalDynamicSymbol() throws {
        let address = try EthereumAddress(
            hex: "0x1111111111111111111111111111111111111111",
            allowZero: false
        )
        XCTAssertEqual(try ABI.decodeAddress(ABI.word(address)), address)
        var invalidAddressWord = ABI.word(address)
        invalidAddressWord[0] = 1
        XCTAssertThrowsError(try ABI.decodeAddress(invalidAddressWord)) { error in
            XCTAssertEqual(error as? ABIError, .invalidAddressPadding)
        }

        XCTAssertEqual(try ABI.decodeDynamicString(abiString("AUD")), "AUD")
        XCTAssertThrowsError(try ABI.decodeDynamicString(abiString("")))
        XCTAssertThrowsError(try ABI.decodeDynamicString(abiString(String(repeating: "A", count: 33))))
        XCTAssertThrowsError(try ABI.decodeDynamicString(abiString("AU\u{202e}D")))

        var nonzeroPadding = abiString("AUD")
        nonzeroPadding[nonzeroPadding.count - 1] = 1
        XCTAssertThrowsError(try ABI.decodeDynamicString(nonzeroPadding))

        var wrongOffset = abiString("AUD")
        wrongOffset.replaceSubrange(0..<32, with: ABI.word(UInt64(64)))
        XCTAssertThrowsError(try ABI.decodeDynamicString(wrongOffset))
    }

    private func abiString(_ value: String) -> Data {
        let bytes = Data(value.utf8)
        let paddedCount = ((bytes.count + 31) / 32) * 32
        return ABI.word(UInt64(32))
            + ABI.word(UInt64(bytes.count))
            + bytes
            + Data(repeating: 0, count: paddedCount - bytes.count)
    }

    private func loadFixture() throws -> ProvisioningFixture {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(
            contentsOf: root.appendingPathComponent("conformance/opk-terminal-provisioning-v1.json")
        )
        return try JSONDecoder().decode(ProvisioningFixture.self, from: data)
    }

    private func loadNetworkFixture() throws -> NetworkRegistryFixture {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(
            contentsOf: root.appendingPathComponent("conformance/opk-terminal-networks-v1.json")
        )
        return try JSONDecoder().decode(NetworkRegistryFixture.self, from: data)
    }
}

private struct NetworkRegistryFixture: Decodable {
    let networks: [NetworkProfileFixture]
}

private struct NetworkProfileFixture: Decodable {
    let chainId: UInt64
    let networkName: String
    let isTestnet: Bool
    let nativeCurrencySymbol: String
    let nativeCurrencyDecimals: UInt8
    let minimumConfirmationBlocks: UInt64
    let defaultConfirmationBlocks: UInt64
    let minimumOperatorNativeReserveWei: String
    let rpcUrl: String
    let protocolVersion: String
    let factory: String
    let receiverImplementation: String
    let vaultRuntimeCodeHash: String
    let create2TestVector: NetworkCreate2Fixture
}

private struct NetworkCreate2Fixture: Decodable {
    let vault: String
    let invoiceId: String
    let salt: String
    let initCodeHash: String
    let expectedReceiver: String
}

private struct ProvisioningFixture: Decodable {
    let operatorPairing: OperatorFixture
    let provisioning: PayloadFixture
}

private struct OperatorFixture: Decodable {
    let address: String
    let canonical: String
    let mustAccept: [String]
    let mustReject: [String]
}

private struct PayloadFixture: Decodable {
    let chainId: UInt64
    let vault: String
    let token: String
    let operatorAddress: String
    let canonical: String
    let mustAccept: [String]
    let mustReject: [String]

    private enum CodingKeys: String, CodingKey {
        case chainId
        case vault
        case token
        case operatorAddress = "operator"
        case canonical
        case mustAccept
        case mustReject
    }
}
#endif
