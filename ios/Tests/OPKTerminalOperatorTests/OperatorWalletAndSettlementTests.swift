#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalOperator
@testable import OPKTerminalRPC

private actor OperatorQueueTransport: RPCTransport {
    private var responses: [RPCTransportResponse]
    private(set) var requestBodies = [Data]()
    private(set) var requestTimeouts = [TimeInterval]()

    init(_ bodies: [String]) {
        responses = bodies.map {
            RPCTransportResponse(statusCode: 200, body: Data($0.utf8))
        }
    }

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        requestBodies.append(request.httpBody ?? Data())
        requestTimeouts.append(request.timeoutInterval)
        guard !responses.isEmpty else { throw URLError(.badServerResponse) }
        return responses.removeFirst()
    }
}

private actor ConcurrentBalanceBatchTransport: RPCTransport {
    private(set) var batchSizes = [Int]()
    private(set) var maximumInFlight = 0
    private var inFlight = 0

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        guard let body = request.httpBody,
              let payload = try JSONSerialization.jsonObject(with: body) as? [[String: Any]]
        else { throw URLError(.badServerResponse) }
        batchSizes.append(payload.count)
        inFlight += 1
        maximumInFlight = max(maximumInFlight, inFlight)
        try await Task.sleep(for: .milliseconds(10))
        inFlight -= 1
        let result = ABI.word(UInt64(1)).hexString
        let responses: [[String: Any]] = payload.reversed().map { item in
            [
                "jsonrpc": "2.0",
                "id": item["id"]!,
                "result": result,
            ]
        }
        return RPCTransportResponse(
            statusCode: 200,
            body: try JSONSerialization.data(withJSONObject: responses)
        )
    }
}

private actor SharedOriginConcurrencyTransport: RPCTransport {
    private(set) var maximumInFlight = 0
    private var inFlight = 0

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        guard let body = request.httpBody,
              let payload = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let id = payload["id"]
        else { throw URLError(.badServerResponse) }
        inFlight += 1
        maximumInFlight = max(maximumInFlight, inFlight)
        try await Task.sleep(for: .milliseconds(20))
        inFlight -= 1
        return RPCTransportResponse(
            statusCode: 200,
            body: try JSONSerialization.data(withJSONObject: [
                "jsonrpc": "2.0",
                "id": id,
                "result": "0x14a34",
            ])
        )
    }
}

final class OperatorWalletAndSettlementTests: XCTestCase {
    func testOperatorTaskLocalDeadlineBoundsPhysicalRequestTimeout() async throws {
        let transport = OperatorQueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":"0x14a34"}"#,
        ])
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        let chainID = try await RPCRequestDeadline.withDeadline(after: .seconds(5)) {
            try await client.chainID()
        }

        XCTAssertEqual(chainID, 84_532)
        let timeouts = await transport.requestTimeouts
        let timeout = try XCTUnwrap(timeouts.first)
        XCTAssertGreaterThan(timeout, 0)
        XCTAssertLessThanOrEqual(timeout, 5)
        XCTAssertLessThan(timeout, 20)
    }

    func testReadAndOperatorClientsShareSixRequestPerOriginTransportLimit() async throws {
        let transport = SharedOriginConcurrencyTransport()
        let origin = "https://shared-limit.example"
        let readClients = try (0..<6).map { index in
            try JSONRPCEthereumClient(
                endpoint: URL(string: "\(origin)/read/\(index)")!,
                transport: transport
            )
        }
        let operatorClients = try (0..<6).map { index in
            try OperatorRPCClient(
                endpoint: URL(string: "\(origin)/operator/\(index)")!,
                transport: transport
            )
        }

        try await withThrowingTaskGroup(of: UInt64.self) { group in
            for client in readClients {
                group.addTask { try await client.chainID() }
            }
            for client in operatorClients {
                group.addTask { try await client.chainID() }
            }
            var values = [UInt64]()
            for try await value in group { values.append(value) }
            XCTAssertEqual(values, Array(repeating: 84_532, count: 12))
        }

        let peak = await transport.maximumInFlight
        XCTAssertEqual(peak, 6)
    }

    func testProductionFeeQuoteUsesOneStrictThreeReadBatch() async throws {
        let transport = OperatorQueueTransport([
            #"[{"jsonrpc":"2.0","id":3,"result":"0xa"},{"jsonrpc":"2.0","id":2,"result":{"baseFeePerGas":"0x64"}},{"jsonrpc":"2.0","id":1,"result":"0x78"}]"#,
        ])
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        let quote = try await client.feeQuote()

        XCTAssertEqual(quote.maxPriorityFeePerGas, 10)
        XCTAssertEqual(quote.maxFeePerGas, 210)
        XCTAssertEqual(quote.source, .eip1559)
        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 1)
        let batch = try XCTUnwrap(
            JSONSerialization.jsonObject(with: bodies[0]) as? [[String: Any]]
        )
        XCTAssertEqual(batch.count, 3)
    }

    func testProductionAuthorizationBatchesOwnerAndOperatorWithSafeOwnerFallback() async throws {
        let transport = OperatorQueueTransport([
            #"[{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"owner unavailable"}},{"jsonrpc":"2.0","id":1,"result":"0x0000000000000000000000000000000000000000000000000000000000000001"}]"#,
        ])
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let address = try EthereumAddress(
            hex: "0x1111111111111111111111111111111111111111"
        )

        let authorization = try await client.vaultAuthorization(
            vault: address,
            operatorAddress: address
        )

        XCTAssertTrue(authorization.isAuthorized)
        XCTAssertFalse(authorization.isOwner)
        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 1)
    }

    func testProductionTokenBalancesUseParallelStrictTenItemBatches() async throws {
        let transport = ConcurrentBalanceBatchTransport()
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let token = try EthereumAddress(
            hex: "0x1111111111111111111111111111111111111111"
        )
        let accounts = try (1...20).map { index in
            try EthereumAddress(
                hex: "0x" + String(format: "%040llx", index)
            )
        }

        let balances = try await client.tokenBalances(token: token, accounts: accounts)

        XCTAssertEqual(balances, Array(repeating: UInt256(1), count: 20))
        let batchSizes = await transport.batchSizes.sorted()
        XCTAssertEqual(batchSizes, [10, 10])
        let maximumInFlight = await transport.maximumInFlight
        XCTAssertEqual(maximumInFlight, 2)
    }

    func testProductionNativeSettlementBalancesUseReceiverEthGetBalance() async throws {
        let transport = OperatorQueueTransport([
            #"[{"jsonrpc":"2.0","id":2,"result":"0x2"},{"jsonrpc":"2.0","id":1,"result":"0x1"}]"#,
        ])
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let accounts = try [1, 2].map { index in
            try EthereumAddress(hex: "0x" + String(format: "%040llx", index))
        }

        let balances = try await client.tokenBalances(
            token: NativeAsset.address,
            accounts: accounts
        )

        XCTAssertEqual(balances, [UInt256(1), UInt256(2)])
        let bodies = await transport.requestBodies
        let batch = try XCTUnwrap(
            JSONSerialization.jsonObject(with: bodies[0]) as? [[String: Any]]
        )
        XCTAssertEqual(batch.map { $0["method"] as? String }, [
            "eth_getBalance",
            "eth_getBalance",
        ])
        for (request, account) in zip(batch, accounts) {
            let params = try XCTUnwrap(request["params"] as? [String])
            XCTAssertEqual(params, [account.hex, "latest"])
            XCTAssertFalse(params.joined().lowercased().contains(NativeAsset.address.hex))
        }
    }

    func testOperatorEndpointPoolReusesClientIdentity() throws {
        let pool = OperatorRPCClientPool(transport: OperatorQueueTransport([]))
        let endpoint = URL(string: "https://rpc.example")!
        XCTAssertTrue(try pool.client(for: endpoint) === pool.client(for: endpoint))
    }

    func testOperatorCanonicalBlockHashRejectsMismatchedReturnedBlockNumber() async throws {
        let transport = OperatorQueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":{"number":"0x11","hash":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"#,
        ])
        let client = try OperatorRPCClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        do {
            _ = try await client.canonicalBlockHash(at: 16)
            XCTFail("Expected a mismatched block number to be rejected")
        } catch SettlementOperatorError.malformedRPCResponse {
            // Expected: the hash is not proof of the requested canonical block.
        }
    }

    func testOperatorResponseIDsRejectDecimalExponentStringAndBooleanForms() async throws {
        let invalidIDs = ["1.0", "1e0", "\"1\"", "true"]
        for invalidID in invalidIDs {
            let single = OperatorQueueTransport([
                "{\"jsonrpc\":\"2.0\",\"id\":\(invalidID),\"result\":\"0x14a34\"}",
            ])
            let singleClient = try OperatorRPCClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: single
            )
            do {
                _ = try await singleClient.chainID()
                XCTFail("Expected strict single-response ID rejection for \(invalidID)")
            } catch {}

            let batch = OperatorQueueTransport([
                "[{\"jsonrpc\":\"2.0\",\"id\":\(invalidID),\"result\":\"0x78\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"baseFeePerGas\":\"0x64\"}},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":\"0xa\"}]",
            ])
            let batchClient = try OperatorRPCClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: batch
            )
            do {
                _ = try await batchClient.feeQuote()
                XCTFail("Expected strict batch-response ID rejection for \(invalidID)")
            } catch {}
        }
    }

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
        XCTAssertEqual(root.paymentVectorVersion, "1.6")
        XCTAssertEqual(root.deploymentProtocolVersion, "1.6")
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
        XCTAssertEqual(
            try SettlementABI.verifySweptEvents(
                receipt: EthereumTransactionReceipt(
                    transactionHash: .zero,
                    blockNumber: 100,
                    blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
                    succeeded: true,
                    logs: [zeroLog]
                ),
                intent: intent
            ),
            []
        )

        XCTAssertEqual(
            try SettlementABI.verifySweptEvents(
                receipt: EthereumTransactionReceipt(
                    transactionHash: .zero,
                    blockNumber: 100,
                    blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
                    succeeded: true,
                    logs: [log, log]
                ),
                intent: intent
            ),
            []
        )

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

    func testConfirmedBatchRetainsPositiveProofWhenAnotherSessionSweepsZero() async throws {
        let fixture = try loadFixture().settlementAbi
        let baseIntent = try makeIntent()
        let secondSession = SettlementSession(
            invoiceID: try Bytes32(hex: "0x" + String(repeating: "aa", count: 32)),
            receiver: baseIntent.sessions[0].receiver,
            expectedAmount: baseIntent.sessions[0].expectedAmount
        )
        let intent = try SettlementIntent(
            chainID: baseIntent.chainID,
            vault: baseIntent.vault,
            token: baseIntent.token,
            sessions: baseIntent.sessions + [secondSession]
        )
        let blockHash = try Bytes32(hex: "0x" + String(repeating: "bb", count: 32))
        let positiveLog = try makeLog(fixture: fixture, data: fixture.sweptLog.data)
        let zeroLog = try makeLog(
            fixture: fixture,
            data: fixture.sweptLog.zeroSweptData,
            invoiceID: secondSession.invoiceID,
            logIndex: 4
        )
        let receipt = EthereumTransactionReceipt(
            transactionHash: .zero,
            blockNumber: 100,
            blockHash: blockHash,
            succeeded: true,
            logs: [positiveLog, zeroLog]
        )

        let parsed = try SettlementABI.verifySweptEvents(receipt: receipt, intent: intent)
        XCTAssertEqual(parsed.map(\.invoiceID), [baseIntent.sessions[0].invoiceID])

        let coordinator = SettlementCoordinator(
            rpc: MockOperatorRPC(receipt: receipt, head: 101, tokenBalance: UInt256(1)),
            signer: NeverSigner(),
            operatorAddress: try EthereumAddress(hex: fixture.operatorAddress)
        )
        let result = try await coordinator.reconcile(
            transactionHash: .zero,
            intent: intent,
            requiredConfirmations: 2,
            priorPhase: .mined
        )

        XCTAssertEqual(result.phase, .needsReview)
        XCTAssertEqual(result.verifiedSweeps.map(\.invoiceID), [baseIntent.sessions[0].invoiceID])
        XCTAssertNotNil(result.failureReason)
    }

    func testCanonicalReceiptDoesNotFinalizeAfterHeadRegressionAboveReceipt() async throws {
        let fixture = try loadFixture().settlementAbi
        let intent = try makeIntent()
        let receipt = EthereumTransactionReceipt(
            transactionHash: .zero,
            blockNumber: 100,
            blockHash: try Bytes32(hex: "0x" + String(repeating: "bb", count: 32)),
            succeeded: true,
            logs: [try makeLog(fixture: fixture, data: fixture.sweptLog.data)]
        )
        let rpc = MockOperatorRPC(
            receipt: receipt,
            heads: [101, 100],
            tokenBalance: UInt256(1)
        )
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: NeverSigner(),
            operatorAddress: try EthereumAddress(hex: fixture.operatorAddress)
        )

        let result = try await coordinator.reconcile(
            transactionHash: .zero,
            intent: intent,
            requiredConfirmations: 2,
            priorPhase: .mined
        )

        XCTAssertEqual(result.phase, .mined)
        XCTAssertEqual(result.blockNumber, receipt.blockNumber)
        XCTAssertEqual(result.confirmations, 1)
        XCTAssertEqual(result.verifiedSweeps.count, 1)
        XCTAssertNil(result.failureReason)
    }

    func testRevertedAndMalformedReceiptsWaitForCanonicalFinalityBeforeFailure() async throws {
        let fixture = try loadFixture().settlementAbi
        let intent = try makeIntent()
        let blockHash = try Bytes32(hex: "0x" + String(repeating: "bb", count: 32))
        let validLog = try makeLog(fixture: fixture, data: fixture.sweptLog.data)
        let malformedLog = EthereumLog(
            address: validLog.address,
            topics: validLog.topics,
            data: validLog.data
        )
        let receipts = [
            EthereumTransactionReceipt(
                transactionHash: .zero,
                blockNumber: 100,
                blockHash: blockHash,
                succeeded: false,
                logs: []
            ),
            EthereumTransactionReceipt(
                transactionHash: .zero,
                blockNumber: 100,
                blockHash: blockHash,
                succeeded: true,
                logs: [malformedLog]
            ),
        ]
        let operatorAddress = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222"
        )

        for receipt in receipts {
            let unconfirmed = SettlementCoordinator(
                rpc: MockOperatorRPC(receipt: receipt, head: 100, tokenBalance: UInt256(1)),
                signer: NeverSigner(),
                operatorAddress: operatorAddress
            )
            let mined = try await unconfirmed.reconcile(
                transactionHash: .zero,
                intent: intent,
                requiredConfirmations: 2,
                priorPhase: .pending
            )
            XCTAssertEqual(mined.phase, .mined)
            XCTAssertEqual(mined.confirmations, 1)
            XCTAssertNotNil(mined.failureReason)

            let confirmed = SettlementCoordinator(
                rpc: MockOperatorRPC(receipt: receipt, head: 101, tokenBalance: UInt256(1)),
                signer: NeverSigner(),
                operatorAddress: operatorAddress
            )
            let failed = try await confirmed.reconcile(
                transactionHash: .zero,
                intent: intent,
                requiredConfirmations: 2,
                priorPhase: .mined
            )
            XCTAssertEqual(failed.phase, .failed)
            XCTAssertEqual(failed.confirmations, 2)
            XCTAssertNotNil(failed.failureReason)
        }
    }

    func testConfirmedLateRepeatSweepIsFinalAfterOriginalCumulativeProof() async throws {
        let fixture = try loadFixture().settlementAbi
        let original = try makeIntent()
        let session = original.sessions[0]
        let intent = try SettlementIntent(
            chainID: original.chainID,
            vault: original.vault,
            token: original.token,
            sessions: [
                SettlementSession(
                    invoiceID: session.invoiceID,
                    receiver: session.receiver,
                    expectedAmount: session.expectedAmount,
                    priorConfirmedSweptAmount: session.expectedAmount
                ),
            ]
        )
        let blockHash = try Bytes32(hex: "0x" + String(repeating: "bb", count: 32))
        let receipt = EthereumTransactionReceipt(
            transactionHash: .zero,
            blockNumber: 100,
            blockHash: blockHash,
            succeeded: true,
            logs: [try makeLog(fixture: fixture, data: fixture.sweptLog.partialSweptData)]
        )
        let coordinator = SettlementCoordinator(
            rpc: MockOperatorRPC(receipt: receipt, head: 101, tokenBalance: UInt256(1)),
            signer: NeverSigner(),
            operatorAddress: try EthereumAddress(hex: fixture.operatorAddress)
        )

        let result = try await coordinator.reconcile(
            transactionHash: .zero,
            intent: intent,
            requiredConfirmations: 2,
            priorPhase: .pending
        )

        XCTAssertEqual(result.phase, .final)
        XCTAssertEqual(result.verifiedSweeps.count, 1)
        XCTAssertFalse(result.verifiedSweeps[0].sweptAmount.isZero)
    }

    func testInitialSweepRequiresExpectedLiveBalanceButProvenAndRepeatSweepsUseLiveBalance() async throws {
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

        // ClearingVault is idempotent per receiver and sweeps its current balance. Once
        // cumulative proof covers the original amount, any later nonzero payment remains
        // sweepable even when it is smaller than the immutable invoice expectation.
        let lateBalance = UInt256(7)
        let repeatIntent = try SettlementIntent(
            chainID: baseIntent.chainID,
            vault: baseIntent.vault,
            token: baseIntent.token,
            sessions: [
                SettlementSession(
                    invoiceID: baseIntent.sessions[0].invoiceID,
                    receiver: baseIntent.sessions[0].receiver,
                    expectedAmount: expected,
                    priorConfirmedSweptAmount: expected
                ),
            ]
        )
        let repeatCoordinator = SettlementCoordinator(
            rpc: MockOperatorRPC(receipt: nil, head: 100, tokenBalance: lateBalance),
            signer: NeverSigner(),
            operatorAddress: operatorAddress
        )
        let repeatPrepared = try await repeatCoordinator.prepare(repeatIntent)
        XCTAssertEqual(repeatPrepared.observedTokenBalances, [lateBalance])
    }

    func testSignRejectsBalanceAddedAfterPreparedObservation() async throws {
        let intent = try makeIntent()
        let confirmed = intent.sessions[0].expectedAmount
        let (changed, overflow) = confirmed.addingReportingOverflow(UInt256(1))
        XCTAssertFalse(overflow)
        let rpc = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalances: [confirmed, confirmed, changed]
        )
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: NeverSigner(),
            operatorAddress: try EthereumAddress(
                hex: "0x2222222222222222222222222222222222222222"
            )
        )
        let prepared = try await coordinator.prepare(intent)

        do {
            _ = try await coordinator.sign(prepared, authenticationReason: "test")
            XCTFail("Expected a changed receiver balance to invalidate confirmation")
        } catch let error as SettlementOperatorError {
            XCTAssertEqual(
                error,
                .receiverBalanceChanged(
                    invoiceID: intent.sessions[0].invoiceID,
                    confirmed: confirmed,
                    current: changed
                )
            )
        }
    }

    func testPostAuthenticationBalanceMutationProducesNoSignature() async throws {
        let intent = try makeIntent()
        let confirmed = intent.sessions[0].expectedAmount
        let (changed, overflow) = confirmed.addingReportingOverflow(UInt256(1))
        XCTAssertFalse(overflow)
        let rpc = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalances: [confirmed, confirmed, confirmed, confirmed]
        )
        let signer = SuspendedAuthenticationSigner()
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: signer,
            operatorAddress: try EthereumAddress(
                hex: "0x2222222222222222222222222222222222222222"
            )
        )
        let prepared = try await coordinator.prepare(intent)
        let signing = Task {
            try await coordinator.sign(prepared, authenticationReason: "test")
        }

        await signer.waitUntilAuthenticationIsPending()
        await rpc.replaceTokenBalances(with: [changed])
        await signer.completeAuthentication()

        do {
            _ = try await signing.value
            XCTFail("Expected the post-authentication balance check to reject signing")
        } catch let error as SettlementOperatorError {
            XCTAssertEqual(
                error,
                .receiverBalanceChanged(
                    invoiceID: intent.sessions[0].invoiceID,
                    confirmed: confirmed,
                    current: changed
                )
            )
        }
        let privateKeyUses = await signer.privateKeyUseCount()
        XCTAssertEqual(privateKeyUses, 0)
    }

    func testPostAuthenticationCursorHashMutationProducesNoSignature() async throws {
        let intent = try makeIntent()
        let confirmed = intent.sessions[0].expectedAmount
        let rpc = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalances: [confirmed, confirmed, confirmed, confirmed]
        )
        let signer = SuspendedAuthenticationSigner()
        let cursorState = MutableConfirmationHashState()
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: signer,
            operatorAddress: try EthereumAddress(
                hex: "0x2222222222222222222222222222222222222222"
            )
        )
        let prepared = try await coordinator.prepare(intent)
        let signing = Task {
            try await coordinator.sign(
                prepared,
                authenticationReason: "test",
                postAuthenticationValidation: {
                    try await cursorState.validateExpectedHash()
                }
            )
        }

        await signer.waitUntilAuthenticationIsPending()
        try await cursorState.replaceHash()
        await signer.completeAuthentication()

        do {
            _ = try await signing.value
            XCTFail("Expected the post-authentication cursor check to reject signing")
        } catch let error as PostAuthenticationTestError {
            XCTAssertEqual(error, .confirmationHashChanged)
        }
        let privateKeyUses = await signer.privateKeyUseCount()
        XCTAssertEqual(privateKeyUses, 0)
    }

    func testPostAuthenticationFinalValidationFailureProducesNoSignature() async throws {
        let intent = try makeIntent()
        let confirmed = intent.sessions[0].expectedAmount
        let rpc = MockOperatorRPC(
            receipt: nil,
            head: 100,
            tokenBalances: [confirmed, confirmed, confirmed, confirmed]
        )
        let signer = SuspendedAuthenticationSigner()
        let coordinator = SettlementCoordinator(
            rpc: rpc,
            signer: signer,
            operatorAddress: try EthereumAddress(
                hex: "0x2222222222222222222222222222222222222222"
            )
        )
        let prepared = try await coordinator.prepare(intent)
        let signing = Task {
            try await coordinator.sign(
                prepared,
                authenticationReason: "test",
                postAuthenticationFinalValidation: {
                    throw PostAuthenticationTestError.validationProofExpired
                }
            )
        }

        await signer.waitUntilAuthenticationIsPending()
        await signer.completeAuthentication()

        do {
            _ = try await signing.value
            XCTFail("Expected the final post-authentication proof-age check to reject signing")
        } catch let error as PostAuthenticationTestError {
            XCTAssertEqual(error, .validationProofExpired)
        }
        let privateKeyUses = await signer.privateKeyUseCount()
        XCTAssertEqual(privateKeyUses, 0)
    }

    func testResetSafetyReadsLatestAndPendingBalancesSeparately() async throws {
        let operatorAddress = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222"
        )
        let coordinator = SettlementCoordinator(
            rpc: MockOperatorRPC(
                receipt: nil,
                head: 100,
                tokenBalance: UInt256(1),
                latestNativeBalance: .zero,
                pendingNativeBalance: UInt256(7)
            ),
            signer: NeverSigner(),
            operatorAddress: operatorAddress
        )

        let snapshot = try await coordinator.resetSafetyBalances(expectedChainID: 84_532)

        XCTAssertEqual(snapshot.latest, .zero)
        XCTAssertEqual(snapshot.pending, UInt256(7))
        XCTAssertFalse(snapshot.isExactlyZero)
    }

    private func makeIntent() throws -> SettlementIntent {
        try SettlementIntent(
            chainID: 84_532,
            vault: EthereumAddress(hex: "0x1111111111111111111111111111111111111111"),
            token: EthereumAddress(hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211"),
            sessions: [
                SettlementSession(
                    invoiceID: Bytes32(hex: "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729"),
                    receiver: EthereumAddress(hex: "0xbbd352de4428d535ac79849abefa8d69bb51c671"),
                    expectedAmount: UInt256(decimalString: "12340000000000000000")
                ),
            ]
        )
    }

    private func makeLog(
        fixture: SettlementFixture,
        data: String,
        invoiceID: Bytes32? = nil,
        logIndex: UInt64 = 3
    ) throws -> EthereumLog {
        var eventData = try Data(hex: data)
        if let invoiceID {
            eventData.replaceSubrange(0..<32, with: invoiceID.data)
        }
        return EthereumLog(
            address: try EthereumAddress(hex: fixture.sweptLog.address),
            topics: try fixture.sweptLog.topics.map(Bytes32.init(hex:)),
            data: eventData,
            logIndex: logIndex,
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
    var headValues: [UInt64]
    var tokenBalanceValues: [UInt256]
    let latestNativeBalance: UInt256
    let pendingNativeBalance: UInt256

    init(
        receipt: EthereumTransactionReceipt?,
        head: UInt64,
        tokenBalance: UInt256,
        latestNativeBalance: UInt256 = UInt256(UInt64.max),
        pendingNativeBalance: UInt256 = UInt256(UInt64.max)
    ) {
        receiptValue = receipt
        headValues = [head]
        tokenBalanceValues = [tokenBalance]
        self.latestNativeBalance = latestNativeBalance
        self.pendingNativeBalance = pendingNativeBalance
    }

    init(
        receipt: EthereumTransactionReceipt?,
        head: UInt64,
        tokenBalances: [UInt256],
        latestNativeBalance: UInt256 = UInt256(UInt64.max),
        pendingNativeBalance: UInt256 = UInt256(UInt64.max)
    ) {
        receiptValue = receipt
        headValues = [head]
        tokenBalanceValues = tokenBalances
        self.latestNativeBalance = latestNativeBalance
        self.pendingNativeBalance = pendingNativeBalance
    }

    init(
        receipt: EthereumTransactionReceipt?,
        heads: [UInt64],
        tokenBalance: UInt256,
        latestNativeBalance: UInt256 = UInt256(UInt64.max),
        pendingNativeBalance: UInt256 = UInt256(UInt64.max)
    ) {
        receiptValue = receipt
        headValues = heads
        tokenBalanceValues = [tokenBalance]
        self.latestNativeBalance = latestNativeBalance
        self.pendingNativeBalance = pendingNativeBalance
    }

    func chainID() async throws -> UInt64 { 84_532 }
    func blockNumber() async throws -> UInt64 {
        guard let head = headValues.first else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        if headValues.count > 1 {
            headValues.removeFirst()
        }
        return head
    }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        guard blockNumber == receiptValue?.blockNumber, let hash = receiptValue?.blockHash else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        return hash
    }
    func balance(of address: EthereumAddress) async throws -> UInt256 { pendingNativeBalance }
    func latestBalance(of address: EthereumAddress) async throws -> UInt256 {
        latestNativeBalance
    }
    func tokenBalance(token: EthereumAddress, account: EthereumAddress) async throws -> UInt256 {
        guard let value = tokenBalanceValues.first else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        if tokenBalanceValues.count > 1 {
            tokenBalanceValues.removeFirst()
        }
        return value
    }
    func replaceTokenBalances(with values: [UInt256]) {
        tokenBalanceValues = values
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
    func sign(
        digest: Bytes32,
        reason: String,
        postAuthenticationValidation: @Sendable () async throws -> Void
    ) async throws -> EthereumRecoverableSignature {
        throw OperatorWalletError.authenticationFailed
    }
}

private actor SuspendedAuthenticationSigner: OperatorTransactionSigning {
    private var authenticationPending = false
    private var authenticationContinuation: CheckedContinuation<Void, Never>?
    private var pendingWaiters = [CheckedContinuation<Void, Never>]()
    private var privateKeyUses = 0

    func sign(
        digest: Bytes32,
        reason: String,
        postAuthenticationValidation: @Sendable () async throws -> Void
    ) async throws -> EthereumRecoverableSignature {
        authenticationPending = true
        pendingWaiters.forEach { $0.resume() }
        pendingWaiters.removeAll()
        await withCheckedContinuation { continuation in
            authenticationContinuation = continuation
        }
        try await postAuthenticationValidation()
        privateKeyUses += 1
        return try EthereumRecoverableSignature(
            r: Data(repeating: 1, count: 32),
            s: Data(repeating: 2, count: 32),
            yParity: 0
        )
    }

    func waitUntilAuthenticationIsPending() async {
        guard !authenticationPending else { return }
        await withCheckedContinuation { continuation in
            pendingWaiters.append(continuation)
        }
    }

    func completeAuthentication() {
        authenticationContinuation?.resume()
        authenticationContinuation = nil
    }

    func privateKeyUseCount() -> Int { privateKeyUses }
}

private actor MutableConfirmationHashState {
    private let expected = try! Bytes32(
        hex: "0x" + String(repeating: "11", count: 32)
    )
    private var current = try! Bytes32(
        hex: "0x" + String(repeating: "11", count: 32)
    )

    func replaceHash() throws {
        current = try Bytes32(hex: "0x" + String(repeating: "22", count: 32))
    }

    func validateExpectedHash() throws {
        guard current == expected else {
            throw PostAuthenticationTestError.confirmationHashChanged
        }
    }
}

private enum PostAuthenticationTestError: Error, Equatable {
    case confirmationHashChanged
    case validationProofExpired
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
