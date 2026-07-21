import XCTest
@testable import OPKTerminalApp
import OPKTerminalCore
import OPKTerminalOperator

final class ResetAndSettlementGroupingTests: XCTestCase {
    func testResetBalanceFailureNamesFundedNetworkAndFormatsItsNativeCurrency() throws {
        let network = OperatorResetNetworkContext(.baseSepolia)
        let result = OperatorResetNetworkBalance(
            network: network,
            snapshot: OperatorNativeBalanceSnapshot(latest: UInt256(1), pending: .zero)
        )

        XCTAssertThrowsError(try OperatorResetSafety.requireEmptyNativeBalance(result)) { error in
            let message = error.localizedDescription
            XCTAssertTrue(message.contains("Base Sepolia (chain 84532)"))
            XCTAssertTrue(message.contains("ETH"))
            XCTAssertTrue(message.contains("1 wei"))
        }
    }

    func testResetReadFailureNamesUnreachableOrMismatchedNetwork() {
        let network = OperatorResetNetworkContext(.baseSepolia)
        let error = OperatorResetSafety.networkReadFailure(
            TestFailure.rpcChainMismatch,
            network: network
        )

        XCTAssertTrue(error.localizedDescription.contains("Base Sepolia (chain 84532)"))
        XCTAssertTrue(error.localizedDescription.contains("RPC chain mismatch"))
        XCTAssertTrue(error.localizedDescription.contains("reset was cancelled"))
    }

    func testSettlementGroupingUsesCompleteRouteAndConfirmationPolicy() throws {
        let original = try storedInvoice(nonceDigit: "1")
        let sameSnapshot = try storedInvoice(nonceDigit: "2")
        let sameSymbolDifferentVault = try storedInvoice(nonceDigit: "3")
        sameSymbolDifferentVault.vault = address("4")
        let sameSymbolDifferentToken = try storedInvoice(nonceDigit: "4")
        sameSymbolDifferentToken.tokenAddress = address("5")
        let differentChain = try storedInvoice(nonceDigit: "5")
        differentChain.chainID = 11_155_111
        let differentConfirmationPolicy = try storedInvoice(nonceDigit: "6")
        differentConfirmationPolicy.confirmationBlocks = 12

        XCTAssertEqual(
            InvoiceSettlementGroupKey(original),
            InvoiceSettlementGroupKey(sameSnapshot)
        )
        for mismatch in [
            sameSymbolDifferentVault,
            sameSymbolDifferentToken,
            differentChain,
            differentConfirmationPolicy,
        ] {
            XCTAssertNotEqual(
                InvoiceSettlementGroupKey(original),
                InvoiceSettlementGroupKey(mismatch)
            )
            XCTAssertFalse(settlementBatchSnapshotsMatch([original, mismatch]))
        }
        XCTAssertTrue(settlementBatchSnapshotsMatch([original, sameSnapshot]))
        XCTAssertEqual(
            groupedSettlementInvoices([
                original,
                sameSnapshot,
                sameSymbolDifferentVault,
                sameSymbolDifferentToken,
                differentChain,
                differentConfirmationPolicy,
            ]).count,
            5
        )
    }

    private func storedInvoice(nonceDigit: String) throws -> StoredInvoice {
        let known = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: known.factory,
            symbol: "USD",
            decimals: 6
        )
        let configuration = try TerminalConfiguration(
            chainID: known.chainID,
            rpcEndpoints: [known.rpcEndpoint],
            protocolVersion: known.protocolVersion,
            deployment: OPKDeployment(
                factory: known.factory,
                receiverImplementation: known.receiverImplementation,
                vault: known.create2TestVector.vault
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: known.create2TestVector
        )
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(address: known.receiverImplementation),
            amount: UInt256(10_500_000),
            token: token,
            configuration: configuration,
            nonce: try Bytes32(hex: "0x" + String(repeating: nonceDigit, count: 64))
        )
        return try StoredInvoice(request: request, configuration: configuration)
    }

    private func address(_ digit: String) -> String {
        "0x" + String(repeating: digit, count: 40)
    }

    private enum TestFailure: LocalizedError {
        case rpcChainMismatch

        var errorDescription: String? { "RPC chain mismatch" }
    }
}
