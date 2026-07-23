#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalRPC

private actor FixtureRPC: EthereumReadRPC {
    let reportedChainID: UInt64
    let factory: EthereumAddress
    let implementation: EthereumAddress
    let vault: EthereumAddress
    let token: EthereumAddress
    let tokenDecimals: UInt8
    let tokenSymbol: String
    var currentBlock: UInt64
    var receiverBalance: UInt256
    var canonicalHashes: [UInt64: Bytes32]
    private(set) var lastCallBlock: RPCBlockTag?

    init(
        chainID: UInt64,
        factory: EthereumAddress,
        implementation: EthereumAddress,
        vault: EthereumAddress,
        token: EthereumAddress,
        tokenDecimals: UInt8 = 18,
        tokenSymbol: String = "AUD",
        block: UInt64 = 100,
        balance: UInt256 = .zero
    ) {
        reportedChainID = chainID
        self.factory = factory
        self.implementation = implementation
        self.vault = vault
        self.token = token
        self.tokenDecimals = tokenDecimals
        self.tokenSymbol = tokenSymbol
        currentBlock = block
        receiverBalance = balance
        canonicalHashes = [block: fixtureBlockHash(block)]
    }

    func chainID() async throws -> UInt64 { reportedChainID }
    func blockNumber() async throws -> UInt64 { currentBlock }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        canonicalHashes[blockNumber] ?? fixtureBlockHash(blockNumber)
    }

    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        [factory, implementation, vault, token].contains(address) ? Data([0x60, 0x01]) : Data()
    }

    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        lastCallBlock = block
        let selector = Data(data.prefix(4))
        if address == factory && selector == ABI.implementationSelector {
            return ABI.word(implementation)
        }
        if address == vault && selector == ABI.factorySelector {
            return ABI.word(factory)
        }
        if address == vault && selector == ABI.isPaymentTokenSelector {
            return ABI.word(UInt64(1))
        }
        if address == token && selector == ABI.decimalsSelector {
            return ABI.word(UInt64(tokenDecimals))
        }
        if address == token && selector == ABI.symbolSelector {
            return abiDynamicString(tokenSymbol)
        }
        if address == token && selector == ABI.balanceOfSelector {
            return ABI.word(receiverBalance)
        }
        throw URLError(.badServerResponse)
    }

    func set(block: UInt64, balance: UInt256) {
        currentBlock = block
        receiverBalance = balance
        if canonicalHashes[block] == nil {
            canonicalHashes[block] = fixtureBlockHash(block)
        }
    }

    func replaceCanonicalHash(at block: UInt64, fork: UInt64) {
        canonicalHashes[block] = fixtureBlockHash(block, fork: fork)
    }
}

private actor ConcurrentValidationRPC: EthereumReadRPC {
    let factory: EthereumAddress
    let implementation: EthereumAddress
    let vault: EthereumAddress
    let token: EthereumAddress
    private(set) var requestCount = 0
    private(set) var maximumInFlight = 0
    private var inFlight = 0

    init(
        factory: EthereumAddress,
        implementation: EthereumAddress,
        vault: EthereumAddress,
        token: EthereumAddress
    ) {
        self.factory = factory
        self.implementation = implementation
        self.vault = vault
        self.token = token
    }

    func chainID() async throws -> UInt64 { await delayed(84_532) }
    func blockNumber() async throws -> UInt64 { await delayed(100) }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        await delayed(fixtureBlockHash(blockNumber))
    }
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        await delayed(Data([0x60, 0x01]))
    }
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        let selector = Data(data.prefix(4))
        let result: Data
        if address == factory && selector == ABI.implementationSelector {
            result = ABI.word(implementation)
        } else if address == vault && selector == ABI.factorySelector {
            result = ABI.word(factory)
        } else if address == vault && selector == ABI.isPaymentTokenSelector {
            result = ABI.word(UInt64(1))
        } else if address == token && selector == ABI.decimalsSelector {
            result = ABI.word(UInt64(18))
        } else if address == token && selector == ABI.symbolSelector {
            result = abiDynamicString("AUD")
        } else {
            throw URLError(.badServerResponse)
        }
        return await delayed(result)
    }

    private func delayed<T: Sendable>(_ value: T) async -> T {
        requestCount += 1
        inFlight += 1
        maximumInFlight = max(maximumInFlight, inFlight)
        try? await Task.sleep(for: .milliseconds(20))
        inFlight -= 1
        return value
    }
}

private actor BatchedValidationRPC: EthereumBatchReadRPC {
    let factory: EthereumAddress
    let implementation: EthereumAddress
    let vault: EthereumAddress
    let token: EthereumAddress
    private(set) var batchSizes = [Int]()
    private(set) var proofBlocks = [RPCBlockTag]()
    private var canonicalHeadReads = 0
    let replaceFinalHeadHash: Bool

    init(
        factory: EthereumAddress,
        implementation: EthereumAddress,
        vault: EthereumAddress,
        token: EthereumAddress,
        replaceFinalHeadHash: Bool = false
    ) {
        self.factory = factory
        self.implementation = implementation
        self.vault = vault
        self.token = token
        self.replaceFinalHeadHash = replaceFinalHeadHash
    }

    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult] {
        batchSizes.append(requests.count)
        return try requests.map { request -> EthereumReadBatchResult in
            switch request {
            case .chainID:
                return .quantity(84_532)
            case .blockNumber:
                return .quantity(123)
            case .latestBlockIdentity:
                return .blockIdentity(number: 123, hash: fixtureBlockHash(123))
            case let .canonicalBlockHash(block):
                canonicalHeadReads += 1
                return .blockHash(fixtureBlockHash(
                    block,
                    fork: replaceFinalHeadHash ? 1 : 0
                ))
            case let .code(_, block):
                proofBlocks.append(block)
                return .data(Data([0x60, 0x01]))
            case let .call(address, data, block):
                proofBlocks.append(block)
                let selector = Data(data.prefix(4))
                if address == factory && selector == ABI.implementationSelector {
                    return .data(ABI.word(implementation))
                }
                if address == vault && selector == ABI.factorySelector {
                    return .data(ABI.word(factory))
                }
                if address == vault && selector == ABI.isPaymentTokenSelector {
                    return .data(ABI.word(UInt64(1)))
                }
                if address == token && selector == ABI.decimalsSelector {
                    return .data(ABI.word(UInt64(18)))
                }
                if address == token && selector == ABI.symbolSelector {
                    return .data(abiDynamicString("AUD"))
                }
                throw URLError(.badServerResponse)
            }
        }
    }

    func chainID() async throws -> UInt64 { 84_532 }
    func blockNumber() async throws -> UInt64 { 123 }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        fixtureBlockHash(blockNumber)
    }
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        Data([0x60, 0x01])
    }
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
}

private actor ConcurrentMultiTokenValidationRPC: EthereumBatchReadRPC {
    let factory: EthereumAddress
    let implementation: EthereumAddress
    let vault: EthereumAddress
    let symbols: [EthereumAddress: String]
    private(set) var batchSizes = [Int]()
    private(set) var maximumInFlight = 0
    private var inFlight = 0

    init(
        factory: EthereumAddress,
        implementation: EthereumAddress,
        vault: EthereumAddress,
        symbols: [EthereumAddress: String]
    ) {
        self.factory = factory
        self.implementation = implementation
        self.vault = vault
        self.symbols = symbols
    }

    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult] {
        batchSizes.append(requests.count)
        inFlight += 1
        maximumInFlight = max(maximumInFlight, inFlight)
        try await Task.sleep(for: .milliseconds(10))
        inFlight -= 1
        return try requests.map { request -> EthereumReadBatchResult in
            switch request {
            case .chainID:
                return .quantity(84_532)
            case .blockNumber:
                return .quantity(123)
            case .latestBlockIdentity:
                return .blockIdentity(number: 123, hash: fixtureBlockHash(123))
            case let .canonicalBlockHash(block):
                return .blockHash(fixtureBlockHash(block))
            case .code:
                return .data(Data([0x60, 0x01]))
            case let .call(address, data, _):
                switch Data(data.prefix(4)) {
                case ABI.implementationSelector where address == factory:
                    return .data(ABI.word(implementation))
                case ABI.factorySelector where address == vault:
                    return .data(ABI.word(factory))
                case ABI.isPaymentTokenSelector where address == vault:
                    return .data(ABI.word(UInt64(1)))
                case ABI.decimalsSelector where symbols[address] != nil:
                    return .data(ABI.word(UInt64(18)))
                case ABI.symbolSelector:
                    guard let symbol = symbols[address] else {
                        throw URLError(.badServerResponse)
                    }
                    return .data(abiDynamicString(symbol))
                default:
                    throw URLError(.badServerResponse)
                }
            }
        }
    }

    func chainID() async throws -> UInt64 { 84_532 }
    func blockNumber() async throws -> UInt64 { 123 }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        fixtureBlockHash(blockNumber)
    }
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        Data([0x60, 0x01])
    }
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
}

private actor BatchedPaymentRPC: EthereumBatchReadRPC {
    let reportedChainID: UInt64
    let token: EthereumAddress
    let balance: UInt256
    private(set) var batches = [[EthereumReadBatchRequest]]()
    private(set) var directBlockHashReads = 0
    private(set) var maximumInFlightBatches = 0
    private var inFlightBatches = 0
    private var headHashReads = 0
    let replaceFinalHeadHash: Bool

    init(
        chainID: UInt64,
        token: EthereumAddress,
        balance: UInt256,
        replaceFinalHeadHash: Bool = false
    ) {
        reportedChainID = chainID
        self.token = token
        self.balance = balance
        self.replaceFinalHeadHash = replaceFinalHeadHash
    }

    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult] {
        batches.append(requests)
        inFlightBatches += 1
        maximumInFlightBatches = max(maximumInFlightBatches, inFlightBatches)
        try await Task.sleep(for: .milliseconds(5))
        inFlightBatches -= 1
        return try requests.map { request in
            switch request {
            case .chainID:
                return .quantity(reportedChainID)
            case .blockNumber:
                return .quantity(100)
            case .latestBlockIdentity:
                return .blockIdentity(number: 100, hash: fixtureBlockHash(100))
            case let .canonicalBlockHash(block):
                if block == 100 {
                    headHashReads += 1
                    let fork: UInt64 = replaceFinalHeadHash ? 1 : 0
                    return .blockHash(fixtureBlockHash(block, fork: fork))
                }
                return .blockHash(fixtureBlockHash(block))
            case let .call(address, data, block):
                guard address == token,
                      Data(data.prefix(4)) == ABI.balanceOfSelector,
                      block == .number(100)
                else { throw URLError(.badServerResponse) }
                return .data(ABI.word(balance))
            case let .code(_, block):
                guard block == .number(100) else { throw URLError(.badServerResponse) }
                return .data(Data())
            }
        }
    }

    func chainID() async throws -> UInt64 { reportedChainID }
    func blockNumber() async throws -> UInt64 { 100 }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        directBlockHashReads += 1
        return fixtureBlockHash(blockNumber)
    }
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
}

private enum PaymentStreamFailure: Equatable, Sendable {
    case http(Int)
    case network(URLError.Code)
    case canonicalReplacement
    case wrongChain
    case inheritedDeadline
}

/// Fails exactly the first payment observation, then serves a stable fixed-head proof. Terminal
/// scenarios are also one-shot deliberately: if the stream retries them by mistake, the test
/// receives a paid observation instead of the expected error.
private actor RetryingPaymentRPC: EthereumBatchReadRPC {
    private let failure: PaymentStreamFailure
    private let token: EthereumAddress
    private var observationAttempt = 0

    init(failure: PaymentStreamFailure, token: EthereumAddress) {
        self.failure = failure
        self.token = token
    }

    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult] {
        if requests.contains(.latestBlockIdentity) {
            observationAttempt += 1
            if failure == .inheritedDeadline, RPCRequestDeadline.current != nil {
                if observationAttempt == 1 {
                    throw RPCRequestDeadlineError.expired
                }
                // Fail terminally on a second inherited attempt so a regression fails quickly
                // instead of leaving the test in the exact infinite retry loop it guards.
                throw RPCDecodingError.invalidData("stream inherited an expired deadline")
            }
            if observationAttempt == 1 {
                switch failure {
                case let .http(status):
                    throw JSONRPCError.invalidHTTPStatus(status)
                case let .network(code):
                    throw URLError(code)
                case .canonicalReplacement, .wrongChain, .inheritedDeadline:
                    break
                }
            }
        }

        return try requests.map { request in
            switch request {
            case .chainID:
                let chainID: UInt64 = failure == .wrongChain && observationAttempt == 1
                    ? 1
                    : 84_532
                return .quantity(chainID)
            case .blockNumber:
                return .quantity(100)
            case .latestBlockIdentity:
                return .blockIdentity(number: 100, hash: fixtureBlockHash(100))
            case let .canonicalBlockHash(block):
                let fork: UInt64 = failure == .canonicalReplacement
                    && observationAttempt == 1 ? 1 : 0
                return .blockHash(fixtureBlockHash(block, fork: fork))
            case let .call(address, data, block):
                guard address == token,
                      Data(data.prefix(4)) == ABI.balanceOfSelector,
                      block == .number(100)
                else { throw URLError(.badServerResponse) }
                return .data(ABI.word(UInt256(1_000)))
            case .code:
                throw URLError(.badServerResponse)
            }
        }
    }

    func attempts() -> Int { observationAttempt }
    func chainID() async throws -> UInt64 { 84_532 }
    func blockNumber() async throws -> UInt64 { 100 }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        fixtureBlockHash(blockNumber)
    }
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        throw URLError(.unsupportedURL)
    }
}

private func fixtureBlockHash(_ block: UInt64, fork: UInt64 = 0) -> Bytes32 {
    let prefix = String(repeating: "0", count: 32)
    let forkHex = String(format: "%016llx", fork)
    let blockHex = String(format: "%016llx", block)
    return try! Bytes32(hex: "0x\(prefix)\(forkHex)\(blockHex)")
}

private func abiDynamicString(_ value: String) -> Data {
    let bytes = Data(value.utf8)
    let paddedCount = ((bytes.count + 31) / 32) * 32
    return ABI.word(UInt64(32))
        + ABI.word(UInt64(bytes.count))
        + bytes
        + Data(repeating: 0, count: paddedCount - bytes.count)
}

final class ValidationAndMonitorTests: XCTestCase {
    private let factory = try! EthereumAddress(hex: "0xb69f725999266c6757284ca4169275c3ebde491a")
    private let implementation = try! EthereumAddress(hex: "0x8ba9739741ecc79b5d69fe5580d2966092e6f77f")
    private let vault = try! EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
    private let tokenAddress = try! EthereumAddress(hex: "0x7fFbA642bc902880a737cb1c18a4E9540879e211")

    func testConfigurationValidationChecksFullReadOnlyWiring() async throws {
        let rpc = FixtureRPC(
            chainID: 84_532,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let report = try await ConfigurationValidator(rpc: rpc).validate(configuration)
        XCTAssertEqual(report.chainID, 84_532)
        XCTAssertEqual(report.checks.count, 5)
        XCTAssertTrue(report.checks.contains { $0.name == "vault factory" })
        XCTAssertTrue(report.checks.contains { $0.name == "token AUD" })
    }

    func testConfigurationValidationFailsClosedOnWrongChain() async throws {
        let rpc = FixtureRPC(
            chainID: 1,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )
        do {
            _ = try await ConfigurationValidator(rpc: rpc).validate(makeConfiguration(requiredBlocks: 2))
            XCTFail("Expected chain mismatch")
        } catch let ConfigurationValidationError.wrongChain(expected, actual) {
            XCTAssertEqual(expected, 84_532)
            XCTAssertEqual(actual, 1)
        }
    }

    func testConfigurationValidationUsesThreeFixedHeadBatches() async throws {
        let rpc = BatchedValidationRPC(
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )

        let report = try await ConfigurationValidator(rpc: rpc).validate(
            makeConfiguration(requiredBlocks: 2)
        )

        XCTAssertEqual(report.chainID, 84_532)
        let batchSizes = await rpc.batchSizes
        XCTAssertEqual(batchSizes, [2, 9, 1])
        let proofBlocks = await rpc.proofBlocks
        XCTAssertEqual(proofBlocks.count, 9)
        XCTAssertTrue(proofBlocks.allSatisfy { $0 == .number(123) })
    }

    func testMultiTokenConfigurationProofUsesConcurrentOrderedTenItemChunks() async throws {
        let tokens = try (1...20).map { index in
            try PaymentToken(
                address: EthereumAddress(
                    hex: "0x" + String(format: "%040llx", index + 100)
                ),
                symbol: "T\(index)",
                decimals: 18
            )
        }
        let rpc = ConcurrentMultiTokenValidationRPC(
            factory: factory,
            implementation: implementation,
            vault: vault,
            symbols: Dictionary(uniqueKeysWithValues: tokens.map { ($0.address, $0.symbol) })
        )
        let configuration = try TerminalConfiguration(
            chainID: 84_532,
            rpcEndpoints: [URL(string: "https://rpc.example")!],
            protocolVersion: .v1_5,
            deployment: OPKDeployment(
                factory: factory,
                receiverImplementation: implementation,
                vault: vault
            ),
            tokens: tokens,
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: nil
        )

        let report = try await ConfigurationValidator(rpc: rpc).validate(configuration)

        XCTAssertEqual(report.checks.filter { $0.name.hasPrefix("token ") }.count, 20)
        let sizes = await rpc.batchSizes
        XCTAssertEqual(sizes.reduce(0, +), 88) // 2 anchor + (5 + 4*20) proof + 1 final.
        XCTAssertTrue(sizes.allSatisfy { $0 <= 10 })
        XCTAssertEqual(sizes.count, 11)
        let peak = await rpc.maximumInFlight
        XCTAssertEqual(peak, 6)
    }

    func testConfigurationFallbackParallelismIsBoundedByValidationPhase() async throws {
        let rpc = ConcurrentValidationRPC(
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )

        _ = try await ConfigurationValidator(rpc: rpc).validate(
            makeConfiguration(requiredBlocks: 2)
        )

        let requestCount = await rpc.requestCount
        let maximumInFlight = await rpc.maximumInFlight
        XCTAssertEqual(requestCount, 13)
        XCTAssertEqual(maximumInFlight, 5)
    }

    func testConfigurationValidationRejectsCanonicalHeadReplacement() async throws {
        let rpc = BatchedValidationRPC(
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress,
            replaceFinalHeadHash: true
        )

        do {
            _ = try await ConfigurationValidator(rpc: rpc).validate(
                makeConfiguration(requiredBlocks: 2)
            )
            XCTFail("Expected canonical replacement to reject the configuration proof")
        } catch let ConfigurationValidationError.canonicalBlockChanged(blockNumber) {
            XCTAssertEqual(blockNumber, 123)
        }
    }

    func testMonitorPartialConfirmingPaidAndOverpaid() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let token = configuration.tokens[0]
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: token,
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = FixtureRPC(
            chainID: 84_532,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress,
            block: 100,
            balance: UInt256(400)
        )
        let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: .init(requiredBlocks: 2))

        var observation = try await monitor.sample(request)
        XCTAssertEqual(observation.status, .partial(received: UInt256(400)))
        XCTAssertNil(observation.thresholdBlock)
        let lastCallBlock = await rpc.lastCallBlock
        XCTAssertEqual(lastCallBlock, .number(100))

        await rpc.set(block: 101, balance: UInt256(1_000))
        observation = try await monitor.sample(request)
        XCTAssertEqual(
            observation.status,
            .confirming(received: UInt256(1_000), confirmations: 1, required: 2)
        )
        XCTAssertEqual(observation.thresholdBlock, 101)
        XCTAssertEqual(observation.thresholdBlockHash, fixtureBlockHash(101))
        let thresholdCursor = try XCTUnwrap(observation.thresholdCursor)

        await rpc.set(block: 102, balance: UInt256(1_000))
        observation = try await monitor.sample(
            request,
            previousThresholdCursor: thresholdCursor
        )
        XCTAssertEqual(observation.status, .paid(received: UInt256(1_000)))

        await rpc.set(block: 103, balance: UInt256(1_250))
        observation = try await monitor.sample(
            request,
            previousThresholdCursor: thresholdCursor
        )
        XCTAssertEqual(
            observation.status,
            .overpaid(received: UInt256(1_250), excess: UInt256(250))
        )
    }

    func testMonitorBatchesNetworkAnchorAndFixedHeadBalanceRead() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(400)
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy
        )

        let observation = try await monitor.sample(
            request,
            expectedChainID: configuration.chainID
        )

        XCTAssertEqual(observation.status, .partial(received: UInt256(400)))
        let batches = await rpc.batches
        XCTAssertEqual(batches.map(\.count), [2, 1, 1])
        XCTAssertEqual(batches[0], [.chainID, .latestBlockIdentity])
        let directBlockHashReads = await rpc.directBlockHashReads
        XCTAssertEqual(directBlockHashReads, 0)
    }

    func testObservationStreamRetriesAvailabilityFailuresAndCanonicalReplacement() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 1)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let failures: [PaymentStreamFailure] = [
            .http(429),
            .http(503),
            .network(.networkConnectionLost),
            .canonicalReplacement,
        ]

        for failure in failures {
            let rpc = RetryingPaymentRPC(failure: failure, token: tokenAddress)
            let monitor = PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: configuration.confirmationPolicy,
                pollIntervalNanoseconds: 1_000_000
            )
            var iterator = monitor.observations(for: request).makeAsyncIterator()

            let observation = try await iterator.next()

            XCTAssertEqual(observation?.status, .paid(received: UInt256(1_000)))
            let attempts = await rpc.attempts()
            XCTAssertEqual(attempts, 2, "Expected one retry for \(failure)")
        }
    }

    func testObservationStreamKeepsWrongChainAndHTTP400Terminal() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 1)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )

        let wrongChainRPC = RetryingPaymentRPC(
            failure: .wrongChain,
            token: tokenAddress
        )
        var wrongChainIterator = PaymentMonitor(
            rpc: wrongChainRPC,
            confirmationPolicy: configuration.confirmationPolicy,
            pollIntervalNanoseconds: 1_000_000
        ).observations(for: request).makeAsyncIterator()
        do {
            _ = try await wrongChainIterator.next()
            XCTFail("Expected wrong-chain failure to terminate monitoring")
        } catch let PaymentMonitorError.wrongChain(expected, actual) {
            XCTAssertEqual(expected, configuration.chainID)
            XCTAssertEqual(actual, 1)
        }
        let wrongChainAttempts = await wrongChainRPC.attempts()
        XCTAssertEqual(wrongChainAttempts, 1)

        let badRequestRPC = RetryingPaymentRPC(failure: .http(400), token: tokenAddress)
        var badRequestIterator = PaymentMonitor(
            rpc: badRequestRPC,
            confirmationPolicy: configuration.confirmationPolicy,
            pollIntervalNanoseconds: 1_000_000
        ).observations(for: request).makeAsyncIterator()
        do {
            _ = try await badRequestIterator.next()
            XCTFail("Expected HTTP 400 to terminate monitoring")
        } catch let JSONRPCError.invalidHTTPStatus(status) {
            XCTAssertEqual(status, 400)
        }
        let badRequestAttempts = await badRequestRPC.attempts()
        XCTAssertEqual(badRequestAttempts, 1)
    }

    func testObservationStreamClearsInheritedExpiredAbsoluteDeadline() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 1)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = RetryingPaymentRPC(
            failure: .inheritedDeadline,
            token: tokenAddress
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy,
            pollIntervalNanoseconds: 1_000_000
        )

        let observation = try await RPCRequestDeadline.withDeadline(
            after: .milliseconds(1)
        ) {
            try await Task.sleep(for: .milliseconds(5))
            var iterator = monitor.observations(for: request).makeAsyncIterator()
            return try await iterator.next()
        }

        XCTAssertEqual(observation?.status, .paid(received: UInt256(1_000)))
        let attempts = await rpc.attempts()
        XCTAssertEqual(attempts, 1)
    }

    func testPaymentMonitorRetryPolicyIsNarrowAndDefaultsToFiveSeconds() throws {
        let rpc = FixtureRPC(
            chainID: 84_532,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )
        XCTAssertEqual(
            PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: .init(requiredBlocks: 1)
            ).pollIntervalNanoseconds,
            5_000_000_000
        )

        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(RPCRequestDeadlineError.expired))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(
            PaymentMonitorError.canonicalBlockChanged(blockNumber: 100)
        ))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(URLError(.notConnectedToInternet)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(408)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(425)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(429)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(500)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.server(
            RPCServerError(code: -32_005, message: "limit exceeded")
        )))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.server(
            RPCServerError(code: -32_016, message: "over rate limit")
        )))

        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(CancellationError()))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(URLError(.cancelled)))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(
            PaymentMonitorError.wrongChain(expected: 84_532, actual: 1)
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(
            PaymentMonitorError.requestChainMismatch(expected: 1, request: 84_532)
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(
            PaymentMonitorError.mixedRequestChains
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(400)))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.invalidHTTPStatus(401)))
        XCTAssertTrue(PaymentMonitorRetryPolicy.shouldRetry(
            JSONRPCError.remoteResponseDecodeFailure
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.malformedResponse))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.mismatchedID))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(
            JSONRPCError.batchLimitExceeded(maximum: 10)
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(
            RPCDecodingError.invalidData("invalid balance")
        ))
        XCTAssertFalse(PaymentMonitorRetryPolicy.shouldRetry(JSONRPCError.server(
            RPCServerError(code: -32_000, message: "execution reverted")
        )))
    }

    func testReceiverFreshnessUsesAnchoredFixedBlockAndRejectsReorg() async throws {
        let stableRPC = BatchedPaymentRPC(
            chainID: 84_532,
            token: tokenAddress,
            balance: .zero
        )
        let proof = try await ReceiverFreshnessValidator(rpc: stableRPC).validate(
            receiver: vault,
            token: tokenAddress,
            expectedChainID: 84_532
        )
        XCTAssertEqual(proof.blockNumber, 100)
        XCTAssertEqual(proof.blockHash, fixtureBlockHash(100))
        XCTAssertTrue(proof.receiverCode.isEmpty)
        XCTAssertTrue(proof.tokenBalance.isZero)
        let stableBatches = await stableRPC.batches
        XCTAssertEqual(stableBatches.map(\.count), [2, 2, 1])
        XCTAssertEqual(stableBatches[0], [.chainID, .latestBlockIdentity])
        XCTAssertTrue(stableBatches[1].allSatisfy { request in
            switch request {
            case let .code(_, block), let .call(_, _, block): block == .number(100)
            default: false
            }
        })

        let reorgRPC = BatchedPaymentRPC(
            chainID: 84_532,
            token: tokenAddress,
            balance: .zero,
            replaceFinalHeadHash: true
        )
        do {
            _ = try await ReceiverFreshnessValidator(rpc: reorgRPC).validate(
                receiver: vault,
                token: tokenAddress,
                expectedChainID: 84_532
            )
            XCTFail("Expected receiver freshness to reject a canonical replacement")
        } catch let PaymentMonitorError.canonicalBlockChanged(blockNumber) {
            XCTAssertEqual(blockNumber, 100)
        }
    }

    func testTwentyInvoiceTwoCursorSampleUsesThreeWavesAndStrictBoundedBatches() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        var inputs = [PaymentSampleInput]()
        for index in 0..<20 {
            let nonce = try Bytes32(
                hex: "0x" + String(repeating: "0", count: 62)
                    + String(format: "%02x", index + 1)
            )
            let request = try InvoiceFactory.create(
                terminalIdentifier: .init(address: vault),
                amount: UInt256(1_000),
                token: configuration.tokens[0],
                configuration: configuration,
                createdAt: Date(timeIntervalSince1970: 1_700_000_000),
                nonce: nonce
            )
            inputs.append(PaymentSampleInput(
                request: request,
                previousThresholdCursor: PaymentConfirmationCursor(
                    blockNumber: UInt64(10 + index),
                    blockHash: fixtureBlockHash(UInt64(10 + index))
                ),
                additionalCursors: [PaymentConfirmationCursor(
                    blockNumber: UInt64(40 + index),
                    blockHash: fixtureBlockHash(UInt64(40 + index))
                )]
            ))
        }
        let rpc = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(1_000)
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy
        )

        let observations = try await monitor.sampleBatch(
            inputs,
            expectedChainID: configuration.chainID
        )

        XCTAssertEqual(observations.count, 20)
        XCTAssertTrue(observations.allSatisfy {
            $0.status == .paid(received: UInt256(1_000))
        })
        let batchSizes = await rpc.batches.map(\.count)
        XCTAssertEqual(batchSizes.first, 2)
        XCTAssertEqual(batchSizes.last, 1)
        XCTAssertEqual(batchSizes.count, 8)
        XCTAssertEqual(batchSizes.reduce(0, +), 63)
        XCTAssertTrue(batchSizes.allSatisfy { $0 <= 10 })
        let maximumInFlight = await rpc.maximumInFlightBatches
        XCTAssertEqual(maximumInFlight, 6)
    }

    func testBatchSampleRejectsHeadReorgAndDoesNotValidateReplacedCursor() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let cursor = PaymentConfirmationCursor(
            blockNumber: 99,
            blockHash: fixtureBlockHash(99, fork: 1)
        )
        let stableRPC = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(1_000)
        )
        let stableMonitor = PaymentMonitor(
            rpc: stableRPC,
            confirmationPolicy: configuration.confirmationPolicy
        )
        let observation = try await stableMonitor.sampleBatch(
            [PaymentSampleInput(
                request: request,
                previousThresholdCursor: cursor,
                additionalCursors: [cursor]
            )],
            expectedChainID: configuration.chainID
        )[0]
        XCTAssertFalse(observation.validated(cursor))
        XCTAssertEqual(observation.thresholdBlock, 100)

        let reorgRPC = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(1_000),
            replaceFinalHeadHash: true
        )
        do {
            _ = try await PaymentMonitor(
                rpc: reorgRPC,
                confirmationPolicy: configuration.confirmationPolicy
            ).sampleBatch(
                [PaymentSampleInput(request: request)],
                expectedChainID: configuration.chainID
            )
            XCTFail("Expected the final canonical-head mismatch to fail closed")
        } catch let PaymentMonitorError.canonicalBlockChanged(blockNumber) {
            XCTAssertEqual(blockNumber, 100)
        }
    }

    func testMonitorRejectsWrongChainBeforeAnyPaymentStateRead() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = BatchedPaymentRPC(
            chainID: 1,
            token: tokenAddress,
            balance: UInt256(1_000)
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy
        )

        do {
            // Omitting the optional override must still derive the required network from the
            // immutable invoice and prove it before reading payment state.
            _ = try await monitor.sample(request)
            XCTFail("Expected chain mismatch")
        } catch let PaymentMonitorError.wrongChain(expected, actual) {
            XCTAssertEqual(expected, configuration.chainID)
            XCTAssertEqual(actual, 1)
        }
        let batches = await rpc.batches
        XCTAssertEqual(batches, [[.chainID, .latestBlockIdentity]])
        let directBlockHashReads = await rpc.directBlockHashReads
        XCTAssertEqual(directBlockHashReads, 0)
    }

    func testMonitorRejectsExplicitNetworkThatDisagreesWithInvoiceBeforeRPC() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(1_000)
        )

        do {
            _ = try await PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: configuration.confirmationPolicy
            ).sample(request, expectedChainID: 1)
            XCTFail("Expected invoice/override mismatch")
        } catch let PaymentMonitorError.requestChainMismatch(expected, invoice) {
            XCTAssertEqual(expected, 1)
            XCTAssertEqual(invoice, configuration.chainID)
        }
        let batches = await rpc.batches
        XCTAssertTrue(batches.isEmpty)
    }

    func testBatchMonitorRejectsMixedInvoiceNetworksBeforeRPC() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let otherNetworkRequest = PaymentRequest(
            invoiceID: request.invoiceID,
            terminalIdentifier: request.terminalIdentifier,
            chainID: 1,
            vault: request.vault,
            receiver: request.receiver,
            token: request.token,
            expectedAmount: request.expectedAmount,
            erc681URI: request.erc681URI,
            createdAt: request.createdAt,
            expiresAt: request.expiresAt
        )
        let rpc = BatchedPaymentRPC(
            chainID: configuration.chainID,
            token: tokenAddress,
            balance: UInt256(1_000)
        )

        do {
            _ = try await PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: configuration.confirmationPolicy
            ).sampleBatch([
                PaymentSampleInput(request: request),
                PaymentSampleInput(request: otherNetworkRequest),
            ])
            XCTFail("Expected mixed-network sample rejection")
        } catch PaymentMonitorError.mixedRequestChains {
            // Expected.
        }
        let batches = await rpc.batches
        XCTAssertTrue(batches.isEmpty)
    }

    func testReplacementForkCannotInheritPaymentConfirmationsAtSameBalance() async throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let rpc = FixtureRPC(
            chainID: 84_532,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress,
            block: 101,
            balance: UInt256(1_000)
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: .init(requiredBlocks: 2)
        )

        let original = try await monitor.sample(request)
        let displacedCursor = try XCTUnwrap(original.thresholdCursor)
        XCTAssertEqual(
            original.status,
            .confirming(received: UInt256(1_000), confirmations: 1, required: 2)
        )

        await rpc.replaceCanonicalHash(at: 101, fork: 1)
        await rpc.set(block: 102, balance: UInt256(1_000))
        let replacement = try await monitor.sample(
            request,
            previousThresholdCursor: displacedCursor
        )

        XCTAssertEqual(replacement.thresholdBlock, 102)
        XCTAssertEqual(replacement.thresholdBlockHash, fixtureBlockHash(102))
        XCTAssertEqual(
            replacement.status,
            .confirming(received: UInt256(1_000), confirmations: 1, required: 2)
        )
        XCTAssertFalse(replacement.validated(displacedCursor))
    }

    func testExpiredInvoiceClosesWithZeroOrPartialFunds() throws {
        let configuration = try makeConfiguration(requiredBlocks: 2)
        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vault),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_000),
            expiresAt: Date(timeIntervalSince1970: 2_000),
            nonce: .zero
        )
        let rpc = FixtureRPC(
            chainID: 84_532,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress
        )
        let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: .init(requiredBlocks: 2))
        XCTAssertEqual(
            monitor.classify(
                request,
                balance: .zero,
                block: 10,
                blockHash: fixtureBlockHash(10),
                now: Date(timeIntervalSince1970: 3_000)
            ).status,
            .expired(lastObserved: .zero)
        )
        XCTAssertEqual(
            monitor.classify(
                request,
                balance: UInt256(10),
                block: 11,
                blockHash: fixtureBlockHash(11),
                now: Date(timeIntervalSince1970: 3_000)
            ).status,
            .expired(lastObserved: UInt256(10))
        )
    }

    private func makeConfiguration(requiredBlocks: UInt64) throws -> TerminalConfiguration {
        let deployment = try OPKDeployment(
            factory: factory,
            receiverImplementation: implementation,
            vault: vault
        )
        let token = try PaymentToken(address: tokenAddress, symbol: "AUD", decimals: 18)
        return try TerminalConfiguration(
            chainID: 84_532,
            rpcEndpoints: [URL(string: "https://sepolia.base.org")!],
            protocolVersion: .v1_5,
            deployment: deployment,
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: requiredBlocks)
        )
    }
}
#endif
