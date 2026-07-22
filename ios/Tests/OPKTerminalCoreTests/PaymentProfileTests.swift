#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class PaymentProfileTests: XCTestCase {
    func testCatalogSelectsOneTokenRouteAcrossNetworksWithoutMixingInvoices() throws {
        let first = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "AUDM"
        )
        let second = try makeProfile(
            chainID: 11_155_111,
            vault: "0x3333333333333333333333333333333333333333",
            token: "0x4444444444444444444444444444444444444444",
            symbol: "USDC"
        )
        let catalog = try TerminalPaymentProfileCatalog(profiles: [first, second])

        XCTAssertEqual(catalog.chainIDs, [84_532, 11_155_111])
        XCTAssertEqual(catalog.profile(id: first.id)?.token.symbol, "AUDM")
        XCTAssertEqual(catalog.profile(id: second.id)?.configuration.chainID, 11_155_111)
        XCTAssertEqual(catalog.selected, first)
        XCTAssertEqual(try catalog.selecting(id: second.id).selected, second)
        XCTAssertNotEqual(first.id, second.id)

        let encoded = try JSONEncoder().encode(catalog)
        XCTAssertTrue(
            try XCTUnwrap(String(data: encoded, encoding: .utf8))
                .contains("\"selectedProfileID\":\"eip155:84532:")
        )
        XCTAssertEqual(
            try JSONDecoder().decode(TerminalPaymentProfileCatalog.self, from: encoded),
            catalog
        )
    }

    func testProfileIdentityIncludesVaultAndRejectsDuplicateRoute() throws {
        let first = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        let otherVault = try makeProfile(
            chainID: 84_532,
            vault: "0x3333333333333333333333333333333333333333",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        XCTAssertNotEqual(first.id, otherVault.id)
        XCTAssertTrue(first.id.rawValue.hasPrefix("eip155:84532:"))
        XCTAssertEqual(
            TerminalPaymentProfileIdentifier(rawValue: first.id.rawValue),
            first.id
        )
        XCTAssertThrowsError(try TerminalPaymentProfileCatalog(profiles: [first, first]))
    }

    func testCatalogCapsUntrustedPersistedProfileGrowth() throws {
        let profile = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        XCTAssertThrowsError(
            try TerminalPaymentProfileCatalog(
                profiles: Array(
                    repeating: profile,
                    count: TerminalPaymentProfileCatalog.maximumProfileCount + 1
                )
            )
        ) { error in
            XCTAssertEqual(
                error as? TerminalPaymentProfileError,
                .profileLimitExceeded(
                    maximum: TerminalPaymentProfileCatalog.maximumProfileCount
                )
            )
        }
    }

    func testCatalogUpsertPreservesOrderAndCommitsSelectionAtomically() throws {
        let first = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "OLD"
        )
        let replacement = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "NEW"
        )
        let second = try makeProfile(
            chainID: 11_155_111,
            vault: "0x3333333333333333333333333333333333333333",
            token: "0x4444444444444444444444444444444444444444",
            symbol: "USDC"
        )

        let initial = try TerminalPaymentProfileCatalog(profiles: [first])
        let expanded = try initial.upserting(second)
        XCTAssertEqual(expanded.profiles.map(\.id), [first.id, second.id])
        XCTAssertEqual(expanded.selected, second)

        let updated = try expanded.upserting(replacement, select: false)
        XCTAssertEqual(updated.profiles.map(\.id), [first.id, second.id])
        XCTAssertEqual(updated.profiles[0].token.symbol, "NEW")
        XCTAssertEqual(updated.selected, second)

        let removedSelected = try updated.removing(id: second.id)
        XCTAssertEqual(removedSelected.profiles, [replacement])
        XCTAssertEqual(removedSelected.selected, replacement)

        let empty = try removedSelected.removing(id: replacement.id)
        XCTAssertTrue(empty.profiles.isEmpty)
        XCTAssertNil(empty.selected)
    }

    func testRemovingSelectedProfileReselectsFirstRemainingInsertion() throws {
        // The first ID intentionally sorts after the second. Every host and SDK uses catalog
        // insertion order rather than independently sorting chain or address identifiers.
        let first = try makeProfile(
            chainID: 84_532,
            vault: "0xffffffffffffffffffffffffffffffffffffffff",
            token: "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            symbol: "AUDM"
        )
        let second = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        let third = try makeProfile(
            chainID: 11_155_111,
            vault: "0x3333333333333333333333333333333333333333",
            token: "0x4444444444444444444444444444444444444444",
            symbol: "EURC"
        )
        let catalog = try TerminalPaymentProfileCatalog(
            profiles: [first, second, third],
            selectedProfileID: third.id
        )

        let remaining = try catalog.removing(id: third.id)

        XCTAssertEqual(remaining.profiles, [first, second])
        XCTAssertEqual(remaining.selected, first)
        XCTAssertEqual(try remaining.removing(id: second.id).selected, first)
    }

    func testCatalogRejectsMissingSelectionAndInvalidDecodedState() throws {
        let first = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "AUDM"
        )
        let missing = TerminalPaymentProfileIdentifier(
            chainID: 1,
            vault: first.id.vault,
            token: first.id.token
        )
        XCTAssertThrowsError(
            try TerminalPaymentProfileCatalog(
                profiles: [first],
                selectedProfileID: missing
            )
        )
        XCTAssertThrowsError(
            try TerminalPaymentProfileCatalog(profiles: [first]).selecting(id: missing)
        )
    }

    func testExplicitTokenSelectionIsRequiredForMultiTokenConfiguration() throws {
        let firstToken = try token(
            "0x2222222222222222222222222222222222222222",
            symbol: "AUDM"
        )
        let secondToken = try token(
            "0x3333333333333333333333333333333333333333",
            symbol: "AUDD"
        )
        let configuration = try makeConfiguration(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            tokens: [firstToken, secondToken]
        )

        XCTAssertThrowsError(try TerminalPaymentProfile(configuration: configuration)) { error in
            XCTAssertEqual(
                error as? TerminalPaymentProfileError,
                .ambiguousTokenSelection
            )
        }
        XCTAssertEqual(
            try TerminalPaymentProfile(configuration: configuration, token: secondToken).token,
            secondToken
        )
    }

    func testInvoiceFactoryProfileOverloadBindsSelectedChainVaultAndToken() throws {
        let profile = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "AUDM"
        )
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(
                address: EthereumAddress(
                    hex: "0x7777777777777777777777777777777777777777",
                    allowZero: false
                )
            ),
            amount: TokenAmount(
                rawValue: UInt256(1_000_000),
                decimals: profile.token.decimals
            ),
            profile: profile,
            createdAt: Date(timeIntervalSince1970: 1_000),
            nonce: Bytes32(
                hex: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )

        XCTAssertEqual(request.chainID, profile.configuration.chainID)
        XCTAssertEqual(request.vault, profile.configuration.deployment.vault)
        XCTAssertEqual(request.token, profile.token)
        XCTAssertTrue(request.erc681URI.contains("@84532/transfer"))
    }

    func testInvoiceFactoryProfileOverloadRejectsAmountForDifferentDecimals() throws {
        let profile = try makeProfile(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        let mismatched = TokenAmount(rawValue: UInt256(1_000_000), decimals: 18)

        XCTAssertThrowsError(
            try InvoiceFactory.create(
                terminalIdentifier: TerminalIdentifier(
                    address: EthereumAddress(
                        hex: "0x7777777777777777777777777777777777777777",
                        allowZero: false
                    )
                ),
                amount: mismatched,
                profile: profile
            )
        ) { error in
            XCTAssertEqual(
                error as? InvoiceFactoryError,
                .amountDecimalsMismatch(expected: 6, actual: 18)
            )
        }
    }

    func testKnownChainRegistryExposesOnlyShippedDeployment() {
        XCTAssertEqual(TerminalKnownChainProfile.supportedChainIDs, [84_532])
        XCTAssertEqual(
            TerminalKnownChainProfile.all.map(\.networkName),
            ["Base Sepolia"]
        )
        XCTAssertNil(TerminalKnownChainProfile.profile(for: 8_453))
        XCTAssertNil(TerminalKnownChainProfile.profile(for: 1))
    }

    func testTerminalConfigurationDefaultsToOneConfirmation() throws {
        let configuration = try makeConfiguration(
            chainID: 84_532,
            vault: "0x1111111111111111111111111111111111111111",
            tokens: [try token(
                "0x2222222222222222222222222222222222222222",
                symbol: "USDC"
            )]
        )

        XCTAssertEqual(configuration.confirmationPolicy.requiredBlocks, 1)
    }

    private func makeProfile(
        chainID: UInt64,
        vault: String,
        token tokenAddress: String,
        symbol: String
    ) throws -> TerminalPaymentProfile {
        let paymentToken = try token(tokenAddress, symbol: symbol)
        return try TerminalPaymentProfile(
            configuration: makeConfiguration(
                chainID: chainID,
                vault: vault,
                tokens: [paymentToken]
            )
        )
    }

    private func makeConfiguration(
        chainID: UInt64,
        vault: String,
        tokens: [PaymentToken]
    ) throws -> TerminalConfiguration {
        try TerminalConfiguration(
            chainID: chainID,
            rpcEndpoints: [URL(string: "https://rpc.example")!],
            protocolVersion: .v1_4_1,
            deployment: OPKDeployment(
                factory: EthereumAddress(
                    hex: "0x5555555555555555555555555555555555555555",
                    allowZero: false
                ),
                receiverImplementation: EthereumAddress(
                    hex: "0x6666666666666666666666666666666666666666",
                    allowZero: false
                ),
                vault: EthereumAddress(hex: vault, allowZero: false)
            ),
            tokens: tokens
        )
    }

    private func token(_ address: String, symbol: String) throws -> PaymentToken {
        try PaymentToken(
            address: EthereumAddress(hex: address, allowZero: false),
            symbol: symbol,
            decimals: 6
        )
    }
}
#endif
