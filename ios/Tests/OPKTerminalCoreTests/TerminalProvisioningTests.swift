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

    func testKnownBaseSepoliaPinsAndShippedCreate2Vector() throws {
        let profile = TerminalKnownChainProfile.baseSepolia
        XCTAssertEqual(profile.chainID, 84_532)
        XCTAssertEqual(
            profile.factory.hex,
            "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5"
        )
        XCTAssertEqual(
            profile.receiverImplementation.hex,
            "0xdaa292b1bf533737c5ce5d27f220273971db3bdc"
        )
        XCTAssertEqual(
            profile.vaultRuntimeCodeHash.hex,
            "0xe7310159a3c109346b137a989bfd213e65fe48ded6eb84dbe57a37d7a047513e"
        )
        XCTAssertNil(TerminalKnownChainProfile.profile(for: 1))
        try ReceiverDerivation.validate(
            profile.create2TestVector,
            factory: profile.factory,
            receiverImplementation: profile.receiverImplementation
        )
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
