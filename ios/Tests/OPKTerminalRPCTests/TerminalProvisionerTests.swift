#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalRPC

private actor ProvisioningRPC: EthereumReadRPC {
    let chain: UInt64
    let vault: EthereumAddress
    let token: EthereumAddress
    let reportedFactory: EthereumAddress
    let reportedImplementation: EthereumAddress
    let vaultRuntimeCode: Data
    let symbolResult: Data
    let whitelisted: Bool
    private(set) var getterCallCount = 0

    init(
        chain: UInt64 = 84_532,
        vault: EthereumAddress,
        token: EthereumAddress,
        reportedFactory: EthereumAddress = TerminalKnownChainProfile.baseSepolia.factory,
        reportedImplementation: EthereumAddress = TerminalKnownChainProfile.baseSepolia.receiverImplementation,
        vaultRuntimeCode: Data = TerminalProvisionerTests.canonicalVaultRuntimeCode,
        symbolResult: Data = TerminalProvisionerTests.abiString("AUD"),
        whitelisted: Bool = true
    ) {
        self.chain = chain
        self.vault = vault
        self.token = token
        self.reportedFactory = reportedFactory
        self.reportedImplementation = reportedImplementation
        self.vaultRuntimeCode = vaultRuntimeCode
        self.symbolResult = symbolResult
        self.whitelisted = whitelisted
    }

    func chainID() async throws -> UInt64 { chain }
    func blockNumber() async throws -> UInt64 { 1 }
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        try Bytes32(hex: "0x" + String(repeating: "0", count: 63) + "1")
    }

    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data {
        if address == vault { return vaultRuntimeCode }
        return [token, reportedFactory, reportedImplementation].contains(address)
            ? Data([0x60, 0x00]) : Data()
    }

    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data {
        getterCallCount += 1
        let selector = Data(data.prefix(4))
        if address == vault && selector == ABI.factorySelector {
            return ABI.word(reportedFactory)
        }
        if address == reportedFactory && selector == ABI.implementationSelector {
            return ABI.word(reportedImplementation)
        }
        if address == vault && selector == ABI.isPaymentTokenSelector {
            return ABI.word(UInt64(whitelisted ? 1 : 0))
        }
        if address == token && selector == ABI.decimalsSelector {
            return ABI.word(UInt64(18))
        }
        if address == token && selector == ABI.symbolSelector {
            return symbolResult
        }
        throw URLError(.badServerResponse)
    }
}

private final class RPCFactoryProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var storedCalls = [URL]()

    var calls: [URL] {
        lock.withLock { storedCalls }
    }

    func record(_ url: URL) {
        lock.withLock { storedCalls.append(url) }
    }
}

final class TerminalProvisionerTests: XCTestCase {
    private let vault = try! EthereumAddress(
        hex: "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
        allowZero: false
    )
    private let token = try! EthereumAddress(
        hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211",
        allowZero: false
    )
    private let operatorAddress = try! EthereumAddress(
        hex: "0x1111111111111111111111111111111111111111",
        allowZero: false
    )

    func testDerivesPinnedConfigurationAndFullyValidatesSymbolAndVector() async throws {
        let rpc = ProvisioningRPC(vault: vault, token: token)
        let provisioner = TerminalProvisioner(rpcFactory: { _ in rpc })
        let result = try await provisioner.deriveAndValidate(
            payload(),
            expectedOperator: operatorAddress,
            confirmationPolicy: .init(requiredBlocks: 7)
        )

        XCTAssertEqual(result.configuration.chainID, 84_532)
        XCTAssertEqual(result.configuration.deployment.factory, result.profile.factory)
        XCTAssertEqual(
            result.configuration.deployment.receiverImplementation,
            result.profile.receiverImplementation
        )
        XCTAssertEqual(result.configuration.tokens.count, 1)
        XCTAssertEqual(result.configuration.tokens[0].symbol, "AUD")
        XCTAssertEqual(result.configuration.tokens[0].decimals, 18)
        XCTAssertEqual(result.configuration.confirmationPolicy.requiredBlocks, 7)
        XCTAssertNotNil(result.configuration.create2TestVector)
        XCTAssertTrue(result.validationReport.checks.contains { $0.name == "CREATE2 vector" })
    }

    func testUnknownChainAndOperatorMismatchRejectBeforeRPCFactory() async throws {
        let probe = RPCFactoryProbe()
        let provisioner = TerminalProvisioner(rpcFactory: { url in
            probe.record(url)
            throw URLError(.unsupportedURL)
        })
        let unknown = try TerminalProvisioningPayload(
            chainID: 8_453,
            vault: vault,
            token: token,
            operatorAddress: operatorAddress
        )
        await XCTAssertThrowsErrorAsync(
            try await provisioner.deriveAndValidate(
                unknown,
                expectedOperator: operatorAddress,
                confirmationPolicy: .init(requiredBlocks: 2)
            )
        )
        let otherOperator = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222",
            allowZero: false
        )
        await XCTAssertThrowsErrorAsync(
            try await provisioner.deriveAndValidate(
                payload(),
                expectedOperator: otherOperator,
                confirmationPolicy: .init(requiredBlocks: 2)
            )
        )
        XCTAssertTrue(probe.calls.isEmpty)
    }

    func testFactoryAndImplementationPinMismatchesReject() async throws {
        let wrongFactory = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222",
            allowZero: false
        )
        let wrongFactoryRPC = ProvisioningRPC(
            vault: vault,
            token: token,
            reportedFactory: wrongFactory
        )
        await XCTAssertThrowsErrorAsync(
            try await TerminalProvisioner(rpcFactory: { _ in wrongFactoryRPC })
                .deriveAndValidate(
                    payload(),
                    expectedOperator: operatorAddress,
                    confirmationPolicy: .init(requiredBlocks: 2)
                )
        )

        let wrongImplementation = try EthereumAddress(
            hex: "0x3333333333333333333333333333333333333333",
            allowZero: false
        )
        let wrongImplementationRPC = ProvisioningRPC(
            vault: vault,
            token: token,
            reportedImplementation: wrongImplementation
        )
        await XCTAssertThrowsErrorAsync(
            try await TerminalProvisioner(rpcFactory: { _ in wrongImplementationRPC })
                .deriveAndValidate(
                    payload(),
                    expectedOperator: operatorAddress,
                    confirmationPolicy: .init(requiredBlocks: 2)
                )
        )
    }

    func testMaliciousVaultGettersCannotBypassPinnedRuntimeCodeHash() async throws {
        let maliciousRuntime = Data([0x60, 0x00])
        let rpc = ProvisioningRPC(
            vault: vault,
            token: token,
            vaultRuntimeCode: maliciousRuntime
        )
        let expected = TerminalKnownChainProfile.baseSepolia.vaultRuntimeCodeHash
        let actual = Keccak256.hash(maliciousRuntime)

        do {
            _ = try await TerminalProvisioner(rpcFactory: { _ in rpc })
                .deriveAndValidate(
                    payload(),
                    expectedOperator: operatorAddress,
                    confirmationPolicy: .init(requiredBlocks: 2)
                )
            XCTFail("Expected counterfeit vault runtime bytecode to be rejected")
        } catch let error as TerminalProvisioningValidationError {
            XCTAssertEqual(
                error,
                .vaultRuntimeCodeHashMismatch(expected: expected, actual: actual)
            )
            XCTAssertEqual(
                error.localizedDescription,
                "The vault runtime bytecode hash \(actual.hex) does not match the trusted OPKBeaconProxy hash \(expected.hex)."
            )
        }
        let getterCallCount = await rpc.getterCallCount
        XCTAssertEqual(getterCallCount, 0, "Counterfeit code must reject before trusting its getters")
    }

    func testInvalidSymbolRejectsAndRPCOverrideIsPreserved() async throws {
        let invalidSymbolRPC = ProvisioningRPC(
            vault: vault,
            token: token,
            symbolResult: ABI.word(UInt64(1))
        )
        await XCTAssertThrowsErrorAsync(
            try await TerminalProvisioner(rpcFactory: { _ in invalidSymbolRPC })
                .deriveAndValidate(
                    payload(),
                    expectedOperator: operatorAddress,
                    confirmationPolicy: .init(requiredBlocks: 2)
                )
        )

        let override = URL(string: "https://rpc.example.invalid")!
        let probe = RPCFactoryProbe()
        let validRPC = ProvisioningRPC(vault: vault, token: token)
        let result = try await TerminalProvisioner(rpcFactory: { url in
            probe.record(url)
            return validRPC
        }).deriveAndValidate(
            payload(),
            expectedOperator: operatorAddress,
            confirmationPolicy: .init(requiredBlocks: 2),
            rpcEndpointOverride: override
        )
        XCTAssertEqual(
            probe.calls,
            [override, TerminalKnownChainProfile.baseSepolia.rpcEndpoint]
        )
        XCTAssertEqual(result.configuration.rpcEndpoints, [override])
    }

    func testHistoricalConfigurationUsesTrustedProfileRPCForProvenance() async throws {
        let profile = TerminalKnownChainProfile.baseSepolia
        let operationalEndpoint = URL(string: "https://rpc.example.invalid")!
        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [operationalEndpoint],
            protocolVersion: profile.protocolVersion,
            deployment: try OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: vault
            ),
            tokens: [try PaymentToken(address: token, symbol: "AUD", decimals: 18)],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: nil
        )
        // The saved operational endpoint can report the correct chain while lying about every
        // contract. Historical provenance must not ask it any contract getter.
        let operationalRPC = ProvisioningRPC(
            vault: vault,
            token: token,
            vaultRuntimeCode: Data([0x60, 0x00]),
            symbolResult: ABI.word(UInt64(1)),
            whitelisted: false
        )
        let trustedRPC = ProvisioningRPC(vault: vault, token: token)
        let probe = RPCFactoryProbe()
        let report = try await TerminalProvisioner(rpcFactory: { endpoint in
            probe.record(endpoint)
            return endpoint == profile.rpcEndpoint ? trustedRPC : operationalRPC
        }).validateHistoricalConfiguration(configuration)

        XCTAssertEqual(report.chainID, profile.chainID)
        XCTAssertTrue(report.checks.contains { $0.name == "CREATE2 vector" })
        XCTAssertEqual(probe.calls, [operationalEndpoint, profile.rpcEndpoint])
        let operationalGetterCalls = await operationalRPC.getterCallCount
        let trustedGetterCalls = await trustedRPC.getterCallCount
        XCTAssertEqual(operationalGetterCalls, 0)
        XCTAssertGreaterThan(trustedGetterCalls, 0)
    }

    func testHistoricalConfigurationRejectsUntrustedSnapshotPinsBeforeRPC() async throws {
        let profile = TerminalKnownChainProfile.baseSepolia
        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [URL(string: "https://rpc.example.invalid")!],
            protocolVersion: profile.protocolVersion,
            deployment: try OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: vault
            ),
            tokens: [try PaymentToken(address: token, symbol: "AUD", decimals: 18)],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: profile.create2TestVector
        )
        let probe = RPCFactoryProbe()
        let trustedRPC = ProvisioningRPC(vault: vault, token: token)
        let alteredConfiguration = try TerminalConfiguration(
            chainID: configuration.chainID,
            rpcEndpoints: configuration.rpcEndpoints,
            protocolVersion: configuration.protocolVersion,
            deployment: try OPKDeployment(
                factory: try EthereumAddress(
                    hex: "0x2222222222222222222222222222222222222222",
                    allowZero: false
                ),
                receiverImplementation: configuration.deployment.receiverImplementation,
                vault: configuration.deployment.vault
            ),
            tokens: configuration.tokens,
            confirmationPolicy: configuration.confirmationPolicy,
            create2TestVector: configuration.create2TestVector
        )
        do {
            _ = try await TerminalProvisioner(rpcFactory: { endpoint in
                probe.record(endpoint)
                return trustedRPC
            }).validateHistoricalConfiguration(alteredConfiguration)
            XCTFail("Expected the altered deployment snapshot to be rejected")
        } catch let error as TerminalProvisioningValidationError {
            XCTAssertEqual(error, .historicalDeploymentPinMismatch)
        }
        XCTAssertTrue(probe.calls.isEmpty)
    }

    private func payload() throws -> TerminalProvisioningPayload {
        try TerminalProvisioningPayload(
            chainID: 84_532,
            vault: vault,
            token: token,
            operatorAddress: operatorAddress
        )
    }

    static func abiString(_ value: String) -> Data {
        let bytes = Data(value.utf8)
        let paddedCount = ((bytes.count + 31) / 32) * 32
        return ABI.word(UInt64(32))
            + ABI.word(UInt64(bytes.count))
            + bytes
            + Data(repeating: 0, count: paddedCount - bytes.count)
    }

    static let canonicalVaultRuntimeCode = try! Data(
        hex: "0x60806040525f8061000e610081565b368280378136915af43d5f803e15610024573d5ff35b" +
            "3d5ffd5b90601f8019910116810190811067ffffffffffffffff82111761004a57604052565b" +
            "634e487b7160e01b5f52604160045260245ffd5b9081602091031261007d57516001600160a0" +
            "1b038116810361007d5790565b5f80fd5b60ff7f0869949ff70b851fd884d5dedd17ab976d41" +
            "48e809aad6e654ec2c04f1849729541661013157604051635c60da1b60e01b81526020816004" +
            "817f000000000000000000000000d5ed58ded083d3cc9eec949b92f1834f937caa6a60016001" +
            "60a01b03165afa908115610126575f916100fa575090565b61011c915060203d60201161011f" +
            "575b6101148183610028565b81019061005e565b90565b503d61010a565b6040513d5f823e3d" +
            "90fd5b7f50950143dc78ff80b5cdf56436a716933e2b92eb073f4b272dec2e808d8423835460" +
            "01600160a01b03169056fea26469706673582212202e8cd2852b590f2bda79ba8056dd697cc4" +
            "fe00ae07dc3e33ae82e1a68109a5aa64736f6c634300081a0033"
    )
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw", file: file, line: line)
    } catch {
        // Expected.
    }
}
#endif
