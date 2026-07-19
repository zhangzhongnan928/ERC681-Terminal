#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalOperator

final class OperatorWalletAndSettlementTests: XCTestCase {
    func testPrivateKeyOneDerivesCanonicalEthereumAddress() throws {
        let privateKey = Data(repeating: 0, count: 31) + Data([1])
        XCTAssertEqual(
            try EthereumSecp256k1.address(privateKey: privateKey).hex,
            "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
        )
    }

    func testEIP1559SigningUsesTypedKeccakPayload() throws {
        let privateKey = Data(repeating: 0, count: 31) + Data([1])
        let transaction = EIP1559Transaction(
            chainID: 84_532,
            nonce: 7,
            maxPriorityFeePerGas: 1_000_000,
            maxFeePerGas: 2_000_000,
            gasLimit: 150_000,
            destination: try EthereumAddress(hex: "0x1111111111111111111111111111111111111111"),
            data: Data([0xde, 0xad, 0xbe, 0xef])
        )
        XCTAssertEqual(transaction.signingPayload.first, 0x02)
        XCTAssertEqual(transaction.signingDigest, Keccak256.hash(transaction.signingPayload))
        let signature = try EthereumSecp256k1.sign(
            digest: transaction.signingDigest,
            privateKey: privateKey
        )
        let raw = transaction.serialized(with: signature)
        XCTAssertEqual(raw.first, 0x02)
        XCTAssertEqual(Keccak256.hash(raw).data.count, 32)
    }

    func testDeterministicType2SignatureMatchesSharedVector() throws {
        let vector = try loadFixture().settlementSigningVector
        let privateKey = try Data(hex: vector.privateKey)
        XCTAssertEqual(
            try EthereumSecp256k1.address(privateKey: privateKey).hex,
            vector.operatorAddress
        )
        guard let priority = UInt64(vector.maxPriorityFeePerGas),
              let maximum = UInt64(vector.maxFeePerGas),
              let gasLimit = UInt64(vector.gasLimit)
        else { return XCTFail("invalid shared signing fixture") }
        let transaction = EIP1559Transaction(
            chainID: vector.chainID,
            nonce: vector.nonce,
            maxPriorityFeePerGas: priority,
            maxFeePerGas: maximum,
            gasLimit: gasLimit,
            destination: try EthereumAddress(hex: vector.destination),
            value: .zero,
            data: try Data(hex: vector.calldata)
        )
        let signature = try EthereumSecp256k1.sign(
            digest: transaction.signingDigest,
            privateKey: privateKey
        )
        let raw = transaction.serialized(with: signature)
        XCTAssertEqual(raw.hexString, vector.rawTransaction)
        XCTAssertEqual(Keccak256.hash(raw).hex, vector.transactionHash)
    }

    func testSettlementABIExactlyMatchesSharedFixtures() throws {
        let root = try loadFixture()
        XCTAssertEqual(root.schemaVersion, 2)
        XCTAssertEqual(root.paymentVectorVersion, "1.5")
        XCTAssertEqual(root.deploymentProtocolVersion, "1.4.1")
        let fixture = root.settlementAbi
        let intent = try makeIntent()
        XCTAssertEqual(SettlementABI.sweepSessionsSelector.hexString, fixture.sweepSessionsSelector)
        XCTAssertEqual(
            SettlementABI.encodeIsOperator(
                try EthereumAddress(hex: fixture.operatorAddress)
            ).hexString,
            fixture.isOperatorCalldata
        )
        XCTAssertEqual(SettlementABI.encodeSweepSessions(intent).hexString, fixture.sweepSessionsCalldata)

        let second = SettlementSession(
            invoiceID: try Bytes32(hex: "0x" + String(repeating: "aa", count: 32)),
            receiver: intent.sessions[0].receiver,
            expectedAmount: UInt256(1)
        )
        let twoItemIntent = try SettlementIntent(
            chainID: intent.chainID,
            vault: intent.vault,
            token: intent.token,
            sessions: intent.sessions + [second]
        )
        XCTAssertEqual(
            SettlementABI.encodeSweepSessions(twoItemIntent).hexString,
            fixture.sweepSessionsTwoItemCalldata
        )
    }

    func testSweptEventFixtureRequiresOneNonzeroCanonicalMatch() throws {
        let fixture = try loadFixture().settlementAbi
        let intent = try makeIntent()
        let log = try makeLog(fixture: fixture, data: fixture.sweptLog.data)
        let receipt = EthereumTransactionReceipt(
            transactionHash: .zero,
            blockNumber: 100,
            blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
            succeeded: true,
            logs: [log]
        )
        let verified = try SettlementABI.verifySweptEvents(receipt: receipt, intent: intent)
        XCTAssertEqual(verified.count, 1)
        XCTAssertEqual(verified[0].sweptAmount.decimalString, fixture.sweptLog.sweptAmount)
        XCTAssertEqual(verified[0].expectedAmount.decimalString, fixture.sweptLog.expectedAmount)
        XCTAssertEqual(verified[0].fee.decimalString, fixture.sweptLog.fee)

        let zeroLog = try makeLog(fixture: fixture, data: fixture.sweptLog.zeroSweptData)
        XCTAssertThrowsError(
            try SettlementABI.verifySweptEvents(
                receipt: EthereumTransactionReceipt(
                    transactionHash: .zero,
                    blockNumber: 100,
                    blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
                    succeeded: true,
                    logs: [zeroLog]
                ),
                intent: intent
            )
        ) { error in
            XCTAssertEqual(error as? SettlementOperatorError, .zeroSweptAmount(intent.sessions[0].invoiceID))
        }

        XCTAssertThrowsError(
            try SettlementABI.verifySweptEvents(
                receipt: EthereumTransactionReceipt(
                    transactionHash: .zero,
                    blockNumber: 100,
                    blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
                    succeeded: true,
                    logs: [log, log]
                ),
                intent: intent
            )
        ) { error in
            XCTAssertEqual(error as? SettlementOperatorError, .ambiguousSweptEvent(intent.sessions[0].invoiceID))
        }

        let identityMissingLog = EthereumLog(
            address: log.address,
            topics: log.topics,
            data: log.data
        )
        XCTAssertThrowsError(
            try SettlementABI.verifySweptEvents(
                receipt: EthereumTransactionReceipt(
                    transactionHash: .zero,
                    blockNumber: 100,
                    blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
                    succeeded: true,
                    logs: [identityMissingLog]
                ),
                intent: intent
            )
        )
    }

    func testCanonicalOperatorBooleanFixtures() throws {
        let fixture = try loadFixture().settlementAbi
        XCTAssertFalse(try ABI.decodeBool(Data(hex: fixture.isOperatorFalseResult)))
        XCTAssertTrue(try ABI.decodeBool(Data(hex: fixture.isOperatorTrueResult)))
        var noncanonical = Data(repeating: 0, count: 32)
        noncanonical[31] = 2
        XCTAssertThrowsError(try ABI.decodeBool(noncanonical))
    }

    func testSettlementBatchIsBoundedAndRejectsDuplicateIDs() throws {
        let intent = try makeIntent()
        XCTAssertThrowsError(
            try SettlementIntent(
                chainID: intent.chainID,
                vault: intent.vault,
                token: intent.token,
                sessions: [intent.sessions[0], intent.sessions[0]]
            )
        )
        let sessions = try (0..<21).map { index in
            SettlementSession(
                invoiceID: try Bytes32(data: Data(repeating: UInt8(index + 1), count: 32)),
                receiver: intent.sessions[0].receiver,
                expectedAmount: UInt256(1)
            )
        }
        XCTAssertThrowsError(
            try SettlementIntent(
                chainID: intent.chainID,
                vault: intent.vault,
                token: intent.token,
                sessions: sessions
            )
        )
    }

    func testConfirmedPartialSweepRequiresReview() async throws {
        let fixture = try loadFixture().settlementAbi
        let intent = try makeIntent()
        let blockHash = try Bytes32(hex: "0x" + String(repeating: "bb", count: 32))
        let partialLog = try makeLog(
            fixture: fixture,
            data: fixture.sweptLog.partialSweptData
        )
        let receipt = EthereumTransactionReceipt(
            transactionHash: .zero,
            blockNumber: 100,
            blockHash: blockHash,
            succeeded: true,
            logs: [partialLog]
        )
        let rpc = MockOperatorRPC(receipt: receipt, head: 101, tokenBalance: UInt256(1))
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: NeverSigner(),
            operatorAddress: try EthereumAddress(hex: fixture.operatorAddress)
        )
        let result = try await coordinator.reconcile(
            transactionHash: .zero,
            intent: intent,
            requiredConfirmations: 2,
            priorPhase: .pending
        )
        XCTAssertEqual(result.phase, .needsReview)
        XCTAssertEqual(result.confirmations, 2)
        XCTAssertEqual(result.verifiedSweeps.count, 1)
        XCTAssertLessThan(
            result.verifiedSweeps[0].sweptAmount,
            result.verifiedSweeps[0].expectedAmount
        )
    }

    func testInitialSweepRequiresExpectedLiveBalanceButProvenRetryRequiresRemaining() async throws {
        let baseIntent = try makeIntent()
        let expected = baseIntent.sessions[0].expectedAmount
        let prior = UInt256(5_000_000_000_000_000_000)
        let (remaining, underflow) = expected.subtractingReportingOverflow(prior)
        XCTAssertFalse(underflow)

        let operatorAddress = try EthereumAddress(hex: "0x2222222222222222222222222222222222222222")
        let initialRPC = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalance: remaining
        )
        let initialCoordinator = SettlementCoordinator(
            rpc: initialRPC,
            signer: NeverSigner(),
            operatorAddress: operatorAddress
        )
        await XCTAssertThrowsErrorAsync {
            _ = try await initialCoordinator.prepare(baseIntent)
        }

        let retryIntent = try SettlementIntent(
            chainID: baseIntent.chainID,
            vault: baseIntent.vault,
            token: baseIntent.token,
            sessions: [
                SettlementSession(
                    invoiceID: baseIntent.sessions[0].invoiceID,
                    receiver: baseIntent.sessions[0].receiver,
                    expectedAmount: expected,
                    priorConfirmedSweptAmount: prior
                ),
            ]
        )
        let retryRPC = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalance: remaining
        )
        let retryCoordinator = SettlementCoordinator(
            rpc: retryRPC,
            signer: NeverSigner(),
            operatorAddress: operatorAddress
        )
        let prepared = try await retryCoordinator.prepare(retryIntent)
        XCTAssertEqual(prepared.observedTokenBalances, [remaining])
    }

    private func makeIntent() throws -> SettlementIntent {
        try SettlementIntent(
            chainID: 84_532,
            vault: EthereumAddress(hex: "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"),
            token: EthereumAddress(hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211"),
            sessions: [
                SettlementSession(
                    invoiceID: Bytes32(hex: "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729"),
                    receiver: EthereumAddress(hex: "0x9107decd2cb06c57c40a663648e19cde1d52f606"),
                    expectedAmount: UInt256(decimalString: "12340000000000000000")
                ),
            ]
        )
    }

    private func makeLog(fixture: SettlementFixture, data: String) throws -> EthereumLog {
        EthereumLog(
            address: try EthereumAddress(hex: fixture.sweptLog.address),
            topics: try fixture.sweptLog.topics.map(Bytes32.init(hex:)),
            data: try Data(hex: data),
            logIndex: 3,
            blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
            transactionHash: .zero
        )
    }

    private func loadFixture() throws -> RootFixture {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("conformance/opk-erc681-v1.json"))
        return try JSONDecoder().decode(RootFixture.self, from: data)
    }
}

private struct RootFixture: Decodable {
    let schemaVersion: Int
    let paymentVectorVersion: String
    let deploymentProtocolVersion: String
    let settlementAbi: SettlementFixture
    let settlementSigningVector: SigningFixture
}

private struct SettlementFixture: Decodable {
    let operatorAddress: String
    let isOperatorCalldata: String
    let isOperatorFalseResult: String
    let isOperatorTrueResult: String
    let sweepSessionsSelector: String
    let sweepSessionsCalldata: String
    let sweepSessionsTwoItemCalldata: String
    let sweptLog: SweptLogFixture

    private enum CodingKeys: String, CodingKey {
        case operatorAddress = "operator"
        case isOperatorCalldata
        case isOperatorFalseResult
        case isOperatorTrueResult
        case sweepSessionsSelector
        case sweepSessionsCalldata
        case sweepSessionsTwoItemCalldata
        case sweptLog
    }
}

private struct SweptLogFixture: Decodable {
    let address: String
    let topics: [String]
    let data: String
    let zeroSweptData: String
    let partialSweptData: String
    let sweptAmount: String
    let expectedAmount: String
    let fee: String
}

private actor MockOperatorRPC: EthereumOperatorRPC {
    let receiptValue: EthereumTransactionReceipt?
    let head: UInt64
    let tokenBalanceValue: UInt256

    init(receipt: EthereumTransactionReceipt?, head: UInt64, tokenBalance: UInt256) {
        receiptValue = receipt
        self.head = head
        tokenBalanceValue = tokenBalance
    }

    func chainID() async throws -> UInt64 { 84_532 }
    func blockNumber() async throws -> UInt64 { head }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        guard blockNumber == receiptValue?.blockNumber, let hash = receiptValue?.blockHash else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        return hash
    }
    func balance(of address: EthereumAddress) async throws -> UInt256 { UInt256(UInt64.max) }
    func tokenBalance(token: EthereumAddress, account: EthereumAddress) async throws -> UInt256 {
        tokenBalanceValue
    }
    func vaultAuthorization(
        vault: EthereumAddress,
        operatorAddress: EthereumAddress
    ) async throws -> VaultAuthorization {
        VaultAuthorization(isOperator: true, isOwner: false)
    }
    func simulate(from: EthereumAddress, to: EthereumAddress, data: Data) async throws {}
    func estimateGas(from: EthereumAddress, to: EthereumAddress, data: Data) async throws -> UInt64 { 100_000 }
    func feeQuote() async throws -> EIP1559FeeQuote {
        EIP1559FeeQuote(maxPriorityFeePerGas: 1, maxFeePerGas: 2, source: .eip1559)
    }
    func pendingNonce(of address: EthereumAddress) async throws -> UInt64 { 0 }
    func sendRawTransaction(_ rawTransaction: Data) async throws -> Bytes32 {
        Keccak256.hash(rawTransaction)
    }
    func receipt(transactionHash: Bytes32) async throws -> EthereumTransactionReceipt? {
        receiptValue
    }
}

private struct NeverSigner: OperatorTransactionSigning {
    func sign(digest: Bytes32, reason: String) async throws -> EthereumRecoverableSignature {
        throw OperatorWalletError.authenticationFailed
    }
}

private func XCTAssertThrowsErrorAsync(
    _ expression: () async throws -> Void,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        try await expression()
        XCTFail("Expected expression to throw", file: file, line: line)
    } catch {
        // Expected.
    }
}

private struct SigningFixture: Decodable {
    let privateKey: String
    let operatorAddress: String
    let chainID: UInt64
    let nonce: UInt64
    let maxPriorityFeePerGas: String
    let maxFeePerGas: String
    let gasLimit: String
    let destination: String
    let calldata: String
    let rawTransaction: String
    let transactionHash: String

    private enum CodingKeys: String, CodingKey {
        case privateKey
        case operatorAddress = "operator"
        case chainID = "chainId"
        case nonce
        case maxPriorityFeePerGas
        case maxFeePerGas
        case gasLimit
        case destination = "to"
        case calldata
        case rawTransaction
        case transactionHash
    }
}
#endif
