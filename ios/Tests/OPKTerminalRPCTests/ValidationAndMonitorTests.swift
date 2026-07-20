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
    private let factory = try! EthereumAddress(hex: "0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5")
    private let implementation = try! EthereumAddress(hex: "0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc")
    private let vault = try! EthereumAddress(hex: "0x1ed67E540E6AB92dC3537A7bba3BcAb6FdD69Da1")
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
            protocolVersion: .v1_4_1,
            deployment: deployment,
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: requiredBlocks)
        )
    }
}
#endif
