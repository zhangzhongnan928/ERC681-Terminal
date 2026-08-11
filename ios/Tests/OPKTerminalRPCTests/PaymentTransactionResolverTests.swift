#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalRPC

private enum EvidenceFixtureError: Error {
    case missingBlock(UInt64)
}

private actor EvidenceFixtureClient: PaymentEvidenceChainClient {
    let servedChainID: UInt64
    let asset: EthereumAddress
    let receiver: EthereumAddress
    let crossingBlock: UInt64
    let balanceBeforeCrossing: UInt256
    let balanceAtAndAfterCrossing: UInt256
    private let blockResponses: [UInt64: [PaymentEvidenceBlock]]
    private let transfers: [UInt64: [PaymentEvidenceERC20Transfer]]
    private var blockReadCounts = [UInt64: Int]()
    private var balanceReads = [UInt64]()

    init(
        servedChainID: UInt64 = 84_532,
        asset: EthereumAddress,
        receiver: EthereumAddress,
        crossingBlock: UInt64 = 15,
        balanceBeforeCrossing: UInt256 = UInt256(20),
        balanceAtAndAfterCrossing: UInt256 = UInt256(110),
        blockResponses: [UInt64: [PaymentEvidenceBlock]],
        transfers: [UInt64: [PaymentEvidenceERC20Transfer]] = [:]
    ) {
        self.servedChainID = servedChainID
        self.asset = asset
        self.receiver = receiver
        self.crossingBlock = crossingBlock
        self.balanceBeforeCrossing = balanceBeforeCrossing
        self.balanceAtAndAfterCrossing = balanceAtAndAfterCrossing
        self.blockResponses = blockResponses
        self.transfers = transfers
    }

    func chainID() async throws -> UInt64 { servedChainID }

    func paymentEvidenceAssetBalance(
        asset: EthereumAddress,
        holder: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> UInt256 {
        precondition(asset == self.asset)
        precondition(holder == receiver)
        balanceReads.append(blockNumber)
        return blockNumber >= crossingBlock
            ? balanceAtAndAfterCrossing
            : balanceBeforeCrossing
    }

    func paymentEvidenceBlock(
        at blockNumber: UInt64,
        includeTransactions: Bool
    ) async throws -> PaymentEvidenceBlock {
        guard let responses = blockResponses[blockNumber], !responses.isEmpty else {
            throw EvidenceFixtureError.missingBlock(blockNumber)
        }
        let count = blockReadCounts[blockNumber, default: 0]
        blockReadCounts[blockNumber] = count + 1
        return responses[min(count, responses.count - 1)]
    }

    func paymentEvidenceERC20Transfers(
        token: EthereumAddress,
        recipient: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> [PaymentEvidenceERC20Transfer] {
        precondition(token == asset)
        precondition(recipient == receiver)
        return transfers[blockNumber] ?? []
    }

    func recordedBalanceReads() -> [UInt64] { balanceReads }
    func recordedBlockReadCount(_ blockNumber: UInt64) -> Int {
        blockReadCounts[blockNumber, default: 0]
    }
}

final class PaymentTransactionResolverTests: XCTestCase {
    private let token = try! EthereumAddress(
        hex: "0x4444444444444444444444444444444444444444"
    )
    private let receiver = try! EthereumAddress(
        hex: "0x3333333333333333333333333333333333333333"
    )
    private let payerOne = try! EthereumAddress(
        hex: "0x1111111111111111111111111111111111111111"
    )
    private let payerTwo = try! EthereumAddress(
        hex: "0x2222222222222222222222222222222222222222"
    )

    func testERC20SelectsOrderedCumulativeTransferAtFirstBalanceCrossing() async throws {
        let fixture = try makeFixture(asset: token)
        let transfers = [
            transfer(
                token: token,
                payer: payerTwo,
                amount: UInt256(50),
                logIndex: 8,
                transactionByte: 0x82,
                block: fixture.crossing
            ),
            transfer(
                token: token,
                payer: payerOne,
                amount: UInt256(40),
                logIndex: 3,
                transactionByte: 0x31,
                block: fixture.crossing
            ),
        ]
        let client = EvidenceFixtureClient(
            asset: token,
            receiver: receiver,
            blockResponses: fixture.responses,
            transfers: [fixture.crossing.number: transfers]
        )

        let resolved = try await PaymentTransactionResolver(client: client)
            .resolve(fixture.request)
        let evidence = try XCTUnwrap(resolved)

        XCTAssertEqual(evidence.transactionHash, bytes32(0x82))
        XCTAssertEqual(evidence.payer, payerTwo)
        XCTAssertEqual(evidence.blockNumber, fixture.crossing.number)
        XCTAssertEqual(evidence.blockHash, fixture.crossing.hash)
        XCTAssertEqual(evidence.blockTimestamp, fixture.crossing.timestamp)
        let reads = await client.recordedBalanceReads()
        XCTAssertTrue(reads.contains(fixture.request.publicationCursor.blockNumber))
        XCTAssertTrue(reads.contains(fixture.request.fundingCursor.blockNumber))
        XCTAssertTrue(reads.contains(fixture.crossing.number - 1))
        XCTAssertFalse(reads.contains(fixture.crossing.number + 1))
    }

    func testNativeSelectsOrderedDirectTopLevelTransaction() async throws {
        let fixture = try makeFixture(asset: NativeAsset.address)
        let unrelated = try EthereumAddress(
            hex: "0x9999999999999999999999999999999999999999"
        )
        let paymentBlock = PaymentEvidenceBlock(
            number: fixture.crossing.number,
            hash: fixture.crossing.hash,
            timestamp: fixture.crossing.timestamp,
            transactions: [
                transaction(
                    from: payerTwo,
                    to: receiver,
                    value: UInt256(60),
                    index: 9,
                    hashByte: 0x92,
                    block: fixture.crossing
                ),
                transaction(
                    from: payerOne,
                    to: unrelated,
                    value: UInt256(500),
                    index: 1,
                    hashByte: 0x19,
                    block: fixture.crossing
                ),
                transaction(
                    from: payerOne,
                    to: receiver,
                    value: UInt256(30),
                    index: 4,
                    hashByte: 0x41,
                    block: fixture.crossing
                ),
            ]
        )
        var responses = fixture.responses
        responses[fixture.crossing.number] = [paymentBlock]
        let client = EvidenceFixtureClient(
            asset: NativeAsset.address,
            receiver: receiver,
            blockResponses: responses
        )

        let resolved = try await PaymentTransactionResolver(client: client)
            .resolve(fixture.request)
        let evidence = try XCTUnwrap(resolved)

        XCTAssertEqual(evidence.transactionHash, bytes32(0x92))
        XCTAssertEqual(evidence.payer, payerTwo)
        XCTAssertEqual(evidence.blockTimestamp, fixture.crossing.timestamp)
    }

    func testNativeInternalTransferIsNotMisattributedToTopLevelTransaction() async throws {
        let fixture = try makeFixture(asset: NativeAsset.address)
        let unrelated = try EthereumAddress(
            hex: "0x9999999999999999999999999999999999999999"
        )
        let paymentBlock = PaymentEvidenceBlock(
            number: fixture.crossing.number,
            hash: fixture.crossing.hash,
            timestamp: fixture.crossing.timestamp,
            transactions: [
                transaction(
                    from: payerOne,
                    to: unrelated,
                    value: UInt256(500),
                    index: 1,
                    hashByte: 0x19,
                    block: fixture.crossing
                ),
            ]
        )
        var responses = fixture.responses
        responses[fixture.crossing.number] = [paymentBlock]
        let client = EvidenceFixtureClient(
            asset: NativeAsset.address,
            receiver: receiver,
            blockResponses: responses
        )

        let evidence = try await PaymentTransactionResolver(client: client)
            .resolve(fixture.request)

        XCTAssertNil(evidence)
    }

    func testWrongChainRejectsBeforeAnyBlockOrBalanceRead() async throws {
        let fixture = try makeFixture(asset: token)
        let client = EvidenceFixtureClient(
            servedChainID: 1,
            asset: token,
            receiver: receiver,
            blockResponses: fixture.responses
        )

        await assertResolutionError(
            .wrongChain(expected: 84_532, actual: 1),
            try await PaymentTransactionResolver(client: client).resolve(fixture.request)
        )
        let balanceReads = await client.recordedBalanceReads()
        let publicationReads = await client.recordedBlockReadCount(10)
        XCTAssertEqual(balanceReads, [])
        XCTAssertEqual(publicationReads, 0)
    }

    func testPublicationCursorReplacementRejectsBeforeAttribution() async throws {
        let fixture = try makeFixture(asset: token)
        var responses = fixture.responses
        responses[fixture.request.publicationCursor.blockNumber] = [
            PaymentEvidenceBlock(
                number: fixture.request.publicationCursor.blockNumber,
                hash: bytes32(0xfa),
                timestamp: 100
            ),
        ]
        let client = EvidenceFixtureClient(
            asset: token,
            receiver: receiver,
            blockResponses: responses
        )

        await assertResolutionError(
            .canonicalBlockChanged(
                blockNumber: fixture.request.publicationCursor.blockNumber
            ),
            try await PaymentTransactionResolver(client: client).resolve(fixture.request)
        )
        let balanceReads = await client.recordedBalanceReads()
        XCTAssertEqual(balanceReads, [])
    }

    func testFundingCursorReplacementDuringResolutionRejectsEvidence() async throws {
        let fixture = try makeFixture(asset: token)
        var responses = fixture.responses
        responses[fixture.request.fundingCursor.blockNumber] = [
            fixture.funding,
            PaymentEvidenceBlock(
                number: fixture.funding.number,
                hash: bytes32(0xfb),
                timestamp: fixture.funding.timestamp
            ),
        ]
        let client = EvidenceFixtureClient(
            asset: token,
            receiver: receiver,
            blockResponses: responses,
            transfers: [
                fixture.crossing.number: [
                    transfer(
                        token: token,
                        payer: payerOne,
                        amount: UInt256(80),
                        logIndex: 1,
                        transactionByte: 0x11,
                        block: fixture.crossing
                    ),
                ],
            ]
        )

        await assertResolutionError(
            .canonicalBlockChanged(blockNumber: fixture.funding.number),
            try await PaymentTransactionResolver(client: client).resolve(fixture.request)
        )
    }

    func testPaymentBlockReplacementOrTimestampMutationRejectsEvidence() async throws {
        for replacement in [
            PaymentEvidenceBlock(number: 15, hash: bytes32(0xfc), timestamp: 150),
            PaymentEvidenceBlock(number: 15, hash: bytes32(0x15), timestamp: 151),
        ] {
            let fixture = try makeFixture(asset: token)
            var responses = fixture.responses
            responses[fixture.crossing.number] = [fixture.crossing, replacement]
            let client = EvidenceFixtureClient(
                asset: token,
                receiver: receiver,
                blockResponses: responses,
                transfers: [
                    fixture.crossing.number: [
                        transfer(
                            token: token,
                            payer: payerOne,
                            amount: UInt256(80),
                            logIndex: 1,
                            transactionByte: 0x11,
                            block: fixture.crossing
                        ),
                    ],
                ]
            )

            await assertResolutionError(
                .canonicalBlockChanged(blockNumber: fixture.crossing.number),
                try await PaymentTransactionResolver(client: client).resolve(fixture.request)
            )
        }
    }

    func testZeroERC20PayerAndZeroNativePayerFailClosed() async throws {
        let zero = try EthereumAddress(hex: "0x" + String(repeating: "00", count: 20))
        let tokenFixture = try makeFixture(asset: token)
        let tokenClient = EvidenceFixtureClient(
            asset: token,
            receiver: receiver,
            blockResponses: tokenFixture.responses,
            transfers: [
                tokenFixture.crossing.number: [
                    transfer(
                        token: token,
                        payer: zero,
                        amount: UInt256(80),
                        logIndex: 1,
                        transactionByte: 0x11,
                        block: tokenFixture.crossing
                    ),
                ],
            ]
        )
        await assertResolutionError(
            .transferLogMismatch,
            try await PaymentTransactionResolver(client: tokenClient)
                .resolve(tokenFixture.request)
        )

        let nativeFixture = try makeFixture(asset: NativeAsset.address)
        let nativeBlock = PaymentEvidenceBlock(
            number: nativeFixture.crossing.number,
            hash: nativeFixture.crossing.hash,
            timestamp: nativeFixture.crossing.timestamp,
            transactions: [
                transaction(
                    from: zero,
                    to: receiver,
                    value: UInt256(80),
                    index: 1,
                    hashByte: 0x11,
                    block: nativeFixture.crossing
                ),
            ]
        )
        var nativeResponses = nativeFixture.responses
        nativeResponses[nativeFixture.crossing.number] = [nativeBlock]
        let nativeClient = EvidenceFixtureClient(
            asset: NativeAsset.address,
            receiver: receiver,
            blockResponses: nativeResponses
        )
        await assertResolutionError(
            .nativeTransactionMismatch,
            try await PaymentTransactionResolver(client: nativeClient)
                .resolve(nativeFixture.request)
        )
    }

    private func makeFixture(
        asset: EthereumAddress
    ) throws -> (
        request: PaymentEvidenceRequest,
        publication: PaymentEvidenceBlock,
        crossing: PaymentEvidenceBlock,
        funding: PaymentEvidenceBlock,
        responses: [UInt64: [PaymentEvidenceBlock]]
    ) {
        let publication = PaymentEvidenceBlock(
            number: 10,
            hash: bytes32(0x10),
            timestamp: 100
        )
        let crossing = PaymentEvidenceBlock(
            number: 15,
            hash: bytes32(0x15),
            timestamp: 150
        )
        let funding = PaymentEvidenceBlock(
            number: 20,
            hash: bytes32(0x20),
            timestamp: 200
        )
        let request = try PaymentEvidenceRequest(
            chainID: 84_532,
            receiver: receiver,
            asset: asset,
            expectedAmount: UInt256(100),
            publicationCursor: PaymentConfirmationCursor(
                blockNumber: publication.number,
                blockHash: publication.hash
            ),
            fundingCursor: PaymentConfirmationCursor(
                blockNumber: funding.number,
                blockHash: funding.hash
            )
        )
        return (
            request,
            publication,
            crossing,
            funding,
            [
                publication.number: [publication],
                crossing.number: [crossing],
                funding.number: [funding],
            ]
        )
    }

    private func transfer(
        token: EthereumAddress,
        payer: EthereumAddress,
        amount: UInt256,
        logIndex: UInt64,
        transactionByte: UInt8,
        block: PaymentEvidenceBlock
    ) -> PaymentEvidenceERC20Transfer {
        PaymentEvidenceERC20Transfer(
            token: token,
            transactionHash: bytes32(transactionByte),
            payer: payer,
            recipient: receiver,
            amount: amount,
            blockNumber: block.number,
            blockHash: block.hash,
            logIndex: logIndex
        )
    }

    private func transaction(
        from: EthereumAddress,
        to: EthereumAddress?,
        value: UInt256,
        index: UInt64,
        hashByte: UInt8,
        block: PaymentEvidenceBlock
    ) -> PaymentEvidenceTransaction {
        PaymentEvidenceTransaction(
            hash: bytes32(hashByte),
            from: from,
            to: to,
            value: value,
            blockNumber: block.number,
            blockHash: block.hash,
            transactionIndex: index
        )
    }

    private func bytes32(_ byte: UInt8) -> Bytes32 {
        try! Bytes32(data: Data(repeating: byte, count: 32))
    }

    private func assertResolutionError<T>(
        _ expected: PaymentEvidenceResolutionError,
        _ expression: @autoclosure () async throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await expression()
            XCTFail("Expected payment evidence resolution to fail", file: file, line: line)
        } catch {
            XCTAssertEqual(
                error as? PaymentEvidenceResolutionError,
                expected,
                file: file,
                line: line
            )
        }
    }
}
#endif
