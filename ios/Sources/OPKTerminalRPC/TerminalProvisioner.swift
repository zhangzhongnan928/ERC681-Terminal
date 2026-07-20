import Foundation
import OPKTerminalCore

public enum TerminalProvisioningValidationError: Error, Equatable, Sendable {
    case unknownChain(UInt64)
    case operatorMismatch(expected: EthereumAddress, actual: EthereumAddress)
    case historicalDeploymentPinMismatch
    case vaultRuntimeCodeHashMismatch(expected: Bytes32, actual: Bytes32)
    case factoryPinMismatch(expected: EthereumAddress, actual: EthereumAddress)
    case implementationPinMismatch(expected: EthereumAddress, actual: EthereumAddress)
}

extension TerminalProvisioningValidationError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .unknownChain(chainID):
            "Chain \(chainID) is an unverified deployment and cannot be provisioned automatically."
        case let .operatorMismatch(expected, actual):
            "This setup QR is bound to operator \(actual.hex), not this terminal's operator \(expected.hex)."
        case .historicalDeploymentPinMismatch:
            "The stored invoice deployment does not match this app's immutable trusted chain profile."
        case let .vaultRuntimeCodeHashMismatch(expected, actual):
            "The vault runtime bytecode hash \(actual.hex) does not match the trusted OPKBeaconProxy hash \(expected.hex)."
        case let .factoryPinMismatch(expected, actual):
            "The vault reported factory \(actual.hex), which does not match the trusted factory \(expected.hex)."
        case let .implementationPinMismatch(expected, actual):
            "The factory reported implementation \(actual.hex), which does not match the trusted implementation \(expected.hex)."
        }
    }
}

public struct ProvisionedTerminalConfiguration: Hashable, Sendable {
    public let profile: TerminalKnownChainProfile
    public let configuration: TerminalConfiguration
    public let validationReport: ConfigurationValidationReport

    public init(
        profile: TerminalKnownChainProfile,
        configuration: TerminalConfiguration,
        validationReport: ConfigurationValidationReport
    ) {
        self.profile = profile
        self.configuration = configuration
        self.validationReport = validationReport
    }
}

public protocol TerminalProvisioningValidating: Sendable {
    func deriveAndValidate(
        _ payload: TerminalProvisioningPayload,
        expectedOperator: EthereumAddress,
        confirmationPolicy: ConfirmationPolicy,
        rpcEndpointOverride: URL?
    ) async throws -> ProvisionedTerminalConfiguration
}

public protocol HistoricalTerminalConfigurationValidating: Sendable {
    func validateHistoricalConfiguration(
        _ configuration: TerminalConfiguration
    ) async throws -> ConfigurationValidationReport
}

/// Read-only bootstrap validator. Unknown chain IDs are rejected before an RPC client is created.
public struct TerminalProvisioner:
    TerminalProvisioningValidating,
    HistoricalTerminalConfigurationValidating,
    Sendable
{
    public typealias RPCFactory = @Sendable (URL) throws -> any EthereumReadRPC

    private let rpcFactory: RPCFactory

    public init(
        rpcFactory: @escaping RPCFactory = { try JSONRPCEthereumClient(endpoint: $0) }
    ) {
        self.rpcFactory = rpcFactory
    }

    public func deriveAndValidate(
        _ payload: TerminalProvisioningPayload,
        expectedOperator: EthereumAddress,
        confirmationPolicy: ConfirmationPolicy,
        rpcEndpointOverride: URL? = nil
    ) async throws -> ProvisionedTerminalConfiguration {
        guard payload.operatorAddress == expectedOperator else {
            throw TerminalProvisioningValidationError.operatorMismatch(
                expected: expectedOperator,
                actual: payload.operatorAddress
            )
        }
        guard let profile = TerminalKnownChainProfile.profile(for: payload.chainID) else {
            throw TerminalProvisioningValidationError.unknownChain(payload.chainID)
        }

        let operationalEndpoint: URL
        if let rpcEndpointOverride {
            try RPCURLPolicy.validate(rpcEndpointOverride)
            operationalEndpoint = rpcEndpointOverride
        } else {
            operationalEndpoint = profile.rpcEndpoint
        }
        let operationalRPC = try rpcFactory(operationalEndpoint)
        let operationalChainID = try await operationalRPC.chainID()
        guard operationalChainID == profile.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: profile.chainID,
                actual: operationalChainID
            )
        }

        // A persisted override is useful for operations, but it is not a deployment trust
        // anchor. All provisioning provenance comes from the immutable endpoint shipped with
        // the known-chain profile.
        let trustedRPC: any EthereumReadRPC
        if operationalEndpoint == profile.rpcEndpoint {
            trustedRPC = operationalRPC
        } else {
            trustedRPC = try rpcFactory(profile.rpcEndpoint)
        }
        let trustedChainID = try await trustedRPC.chainID()
        guard trustedChainID == profile.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: profile.chainID,
                actual: trustedChainID
            )
        }

        let vaultRuntimeCode = try await trustedRPC.code(at: payload.vault, block: .latest)
        let actualVaultRuntimeCodeHash = Keccak256.hash(vaultRuntimeCode)
        guard actualVaultRuntimeCodeHash == profile.vaultRuntimeCodeHash else {
            throw TerminalProvisioningValidationError.vaultRuntimeCodeHashMismatch(
                expected: profile.vaultRuntimeCodeHash,
                actual: actualVaultRuntimeCodeHash
            )
        }

        let factoryData = try await trustedRPC.call(
            to: payload.vault,
            data: ABI.encodeCall(selector: ABI.factorySelector),
            block: .latest
        )
        let derivedFactory = try ABI.decodeAddress(factoryData)
        guard derivedFactory == profile.factory else {
            throw TerminalProvisioningValidationError.factoryPinMismatch(
                expected: profile.factory,
                actual: derivedFactory
            )
        }

        let implementationData = try await trustedRPC.call(
            to: derivedFactory,
            data: ABI.encodeCall(selector: ABI.implementationSelector),
            block: .latest
        )
        let derivedImplementation = try ABI.decodeAddress(implementationData)
        guard derivedImplementation == profile.receiverImplementation else {
            throw TerminalProvisioningValidationError.implementationPinMismatch(
                expected: profile.receiverImplementation,
                actual: derivedImplementation
            )
        }

        let whitelistData = try await trustedRPC.call(
            to: payload.vault,
            data: ABI.encodeCall(
                selector: ABI.isPaymentTokenSelector,
                words: [ABI.word(payload.token)]
            ),
            block: .latest
        )
        guard try ABI.decodeBool(whitelistData) else {
            throw ConfigurationValidationError.tokenNotWhitelisted(payload.token)
        }

        let decimalsData = try await trustedRPC.call(
            to: payload.token,
            data: ABI.encodeCall(selector: ABI.decimalsSelector),
            block: .latest
        )
        guard let decimals = try ABI.decodeUInt256(decimalsData).uint64Value,
              let tokenDecimals = UInt8(exactly: decimals)
        else { throw ConfigurationValidationError.invalidDecimals }

        let symbolData = try await trustedRPC.call(
            to: payload.token,
            data: ABI.encodeCall(selector: ABI.symbolSelector),
            block: .latest
        )
        let tokenSymbol: String
        do {
            tokenSymbol = try ABI.decodeDynamicString(symbolData)
        } catch {
            throw ConfigurationValidationError.invalidTokenSymbol(payload.token)
        }

        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [operationalEndpoint],
            protocolVersion: profile.protocolVersion,
            deployment: OPKDeployment(
                factory: derivedFactory,
                receiverImplementation: derivedImplementation,
                vault: payload.vault
            ),
            tokens: [PaymentToken(
                address: payload.token,
                symbol: tokenSymbol,
                decimals: tokenDecimals
            )],
            confirmationPolicy: confirmationPolicy,
            create2TestVector: profile.create2TestVector
        )
        let report = try await ConfigurationValidator(rpc: trustedRPC).validate(configuration)
        return ProvisionedTerminalConfiguration(
            profile: profile,
            configuration: configuration,
            validationReport: report
        )
    }

    /// Revalidates an immutable invoice snapshot without consulting current terminal settings.
    /// This permits a reprovisioned terminal to settle an older receiver while retaining the same
    /// compiled deployment trust anchors used during initial provisioning.
    public func validateHistoricalConfiguration(
        _ configuration: TerminalConfiguration
    ) async throws -> ConfigurationValidationReport {
        guard let profile = TerminalKnownChainProfile.profile(for: configuration.chainID) else {
            throw TerminalProvisioningValidationError.unknownChain(configuration.chainID)
        }
        guard configuration.protocolVersion == profile.protocolVersion,
              configuration.deployment.factory == profile.factory,
              configuration.deployment.receiverImplementation == profile.receiverImplementation,
              configuration.create2TestVector == nil
                || configuration.create2TestVector == profile.create2TestVector,
              configuration.tokens.count == 1
        else { throw TerminalProvisioningValidationError.historicalDeploymentPinMismatch }

        // The saved endpoint remains the operational endpoint for balances and broadcasts, but
        // it is not a provenance authority. Check that it still serves the expected chain, then
        // prove contract code, linkage, whitelist, and token metadata through the immutable
        // endpoint shipped in the known-chain profile.
        let operationalRPC = try rpcFactory(configuration.rpcEndpoints[0])
        let operationalChainID = try await operationalRPC.chainID()
        guard operationalChainID == profile.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: profile.chainID,
                actual: operationalChainID
            )
        }

        let trustedRPC: any EthereumReadRPC
        if configuration.rpcEndpoints[0] == profile.rpcEndpoint {
            trustedRPC = operationalRPC
        } else {
            trustedRPC = try rpcFactory(profile.rpcEndpoint)
        }
        let trustedChainID = try await trustedRPC.chainID()
        guard trustedChainID == profile.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: profile.chainID,
                actual: trustedChainID
            )
        }
        let vaultRuntimeCode = try await trustedRPC.code(
            at: configuration.deployment.vault,
            block: .latest
        )
        let actualVaultRuntimeCodeHash = Keccak256.hash(vaultRuntimeCode)
        guard actualVaultRuntimeCodeHash == profile.vaultRuntimeCodeHash else {
            throw TerminalProvisioningValidationError.vaultRuntimeCodeHashMismatch(
                expected: profile.vaultRuntimeCodeHash,
                actual: actualVaultRuntimeCodeHash
            )
        }
        // Legacy invoices did not persist the profile-wide test vector. Reconstitute it from the
        // immutable profile so historical validation performs the same derivation self-test as
        // fresh provisioning without trusting local snapshot data.
        let trustedConfiguration = try TerminalConfiguration(
            chainID: configuration.chainID,
            rpcEndpoints: [profile.rpcEndpoint],
            protocolVersion: configuration.protocolVersion,
            deployment: configuration.deployment,
            tokens: configuration.tokens,
            confirmationPolicy: configuration.confirmationPolicy,
            create2TestVector: profile.create2TestVector
        )
        return try await ConfigurationValidator(rpc: trustedRPC).validate(trustedConfiguration)
    }
}
