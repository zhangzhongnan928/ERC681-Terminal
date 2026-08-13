// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

public enum TerminalProvisioningValidationError: Error, Equatable, Sendable {
    case unknownChain(UInt64)
    case operatorMismatch(expected: EthereumAddress, actual: EthereumAddress)
    case confirmationPolicyBelowMinimum(chainID: UInt64, minimum: UInt64, actual: UInt64)
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
        case let .confirmationPolicyBelowMinimum(chainID, minimum, actual):
            "Chain \(chainID) requires at least \(minimum) confirmation blocks, but \(actual) was requested."
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
        rpcFactory: @escaping RPCFactory = { try EthereumRPCClientPool.shared.client(for: $0) }
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
        guard confirmationPolicy.requiredBlocks >= profile.minimumConfirmationBlocks else {
            throw TerminalProvisioningValidationError.confirmationPolicyBelowMinimum(
                chainID: profile.chainID,
                minimum: profile.minimumConfirmationBlocks,
                actual: confirmationPolicy.requiredBlocks
            )
        }

        let operationalEndpoint: URL
        if let rpcEndpointOverride {
            try RPCURLPolicy.validate(rpcEndpointOverride)
            operationalEndpoint = rpcEndpointOverride
        } else {
            operationalEndpoint = profile.rpcEndpoint
        }
        let rpc = try rpcFactory(operationalEndpoint)
        let actualChainID = try await rpc.chainID()
        guard actualChainID == profile.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: profile.chainID,
                actual: actualChainID
            )
        }

        // The administrator-selected provider is transport, not a deployment trust anchor.
        // Every value read through it remains constrained by the immutable chain, factory,
        // implementation, CREATE2, and vault-runtime hashes compiled into the known profile.
        // Keeping every read on the operational endpoint also means a rate-limited public fallback
        // cannot break provisioning after a dedicated endpoint has been configured.
        let vaultRuntimeCode = try await rpc.code(at: payload.vault, block: .latest)
        let actualVaultRuntimeCodeHash = Keccak256.hash(vaultRuntimeCode)
        guard actualVaultRuntimeCodeHash == profile.vaultRuntimeCodeHash else {
            throw TerminalProvisioningValidationError.vaultRuntimeCodeHashMismatch(
                expected: profile.vaultRuntimeCodeHash,
                actual: actualVaultRuntimeCodeHash
            )
        }

        // These latest-state reads are bootstrap hints only. The configuration assembled from
        // them is not trusted or persisted until ConfigurationValidator reproduces every code,
        // linkage, whitelist, and metadata dependency at one canonical fixed head below.
        let factoryData = try await rpc.call(
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

        let implementationData = try await rpc.call(
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

        let tokenDecimals: UInt8
        let tokenSymbol: String
        if NativeAsset.isNative(payload.token) {
            let nativeAssetData = try await rpc.call(
                to: payload.vault,
                data: ABI.encodeCall(selector: ABI.nativeAssetSelector),
                block: .latest
            )
            let advertisedNativeAsset = try ABI.decodeAddress(nativeAssetData)
            guard advertisedNativeAsset == NativeAsset.address else {
                throw ConfigurationValidationError.nativeAssetCapabilityMismatch(
                    expected: NativeAsset.address,
                    actual: advertisedNativeAsset
                )
            }
            tokenDecimals = profile.nativeCurrencyDecimals
            tokenSymbol = profile.nativeCurrencySymbol
        } else {
            let decimalsData = try await rpc.call(
                to: payload.token,
                data: ABI.encodeCall(selector: ABI.decimalsSelector),
                block: .latest
            )
            guard let decimals = try ABI.decodeUInt256(decimalsData).uint64Value,
                  let decodedDecimals = UInt8(exactly: decimals)
            else { throw ConfigurationValidationError.invalidDecimals }
            tokenDecimals = decodedDecimals

            let symbolData = try await rpc.call(
                to: payload.token,
                data: ABI.encodeCall(selector: ABI.symbolSelector),
                block: .latest
            )
            do {
                tokenSymbol = try ABI.decodeDynamicString(symbolData)
            } catch {
                throw ConfigurationValidationError.invalidTokenSymbol(payload.token)
            }
        }

        let whitelistData = try await rpc.call(
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

        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [operationalEndpoint],
            protocolVersion: profile.protocolVersion(for: payload.token),
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
        let detailed = try await ConfigurationValidator(rpc: rpc)
            .validateDetailed(configuration)
        // Pin the exact vault bytes covered by the validator's final canonical-head identity
        // check. The earlier latest-state hash is only an early counterfeit-vault rejection and
        // cannot authorize persistence across a reorg or state transition by itself.
        let fixedHeadVaultRuntimeCodeHash = Keccak256.hash(detailed.vaultRuntimeCode)
        guard fixedHeadVaultRuntimeCodeHash == profile.vaultRuntimeCodeHash else {
            throw TerminalProvisioningValidationError.vaultRuntimeCodeHashMismatch(
                expected: profile.vaultRuntimeCodeHash,
                actual: fixedHeadVaultRuntimeCodeHash
            )
        }
        return ProvisionedTerminalConfiguration(
            profile: profile,
            configuration: configuration,
            validationReport: detailed.report
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
        guard configuration.tokens.count == 1,
              let paymentAsset = configuration.tokens.first,
              configuration.protocolVersion == profile.protocolVersion(for: paymentAsset.address),
              configuration.deployment.factory == profile.factory,
              configuration.deployment.receiverImplementation == profile.receiverImplementation,
              configuration.create2TestVector == nil
                || configuration.create2TestVector == profile.create2TestVector,
              !paymentAsset.isNativeAsset
                || (
                    paymentAsset.symbol == profile.nativeCurrencySymbol
                        && paymentAsset.decimals == profile.nativeCurrencyDecimals
                        && paymentAsset.decimals == NativeAsset.decimals
                )
        else { throw TerminalProvisioningValidationError.historicalDeploymentPinMismatch }

        // The saved endpoint is transport, while the immutable profile remains the source of every
        // deployment pin. Legacy invoices did not persist the profile-wide test vector, so restore
        // it before running the same fixed-head validation used for fresh provisioning.
        let validationConfiguration = try TerminalConfiguration(
            chainID: configuration.chainID,
            rpcEndpoints: configuration.rpcEndpoints,
            protocolVersion: configuration.protocolVersion,
            deployment: configuration.deployment,
            tokens: configuration.tokens,
            confirmationPolicy: configuration.confirmationPolicy,
            create2TestVector: profile.create2TestVector
        )

        let rpc = try rpcFactory(configuration.rpcEndpoints[0])
        let detailed = try await ConfigurationValidator(rpc: rpc)
            .validateDetailed(validationConfiguration)

        let actualVaultRuntimeCodeHash = Keccak256.hash(detailed.vaultRuntimeCode)
        guard actualVaultRuntimeCodeHash == profile.vaultRuntimeCodeHash else {
            throw TerminalProvisioningValidationError.vaultRuntimeCodeHashMismatch(
                expected: profile.vaultRuntimeCodeHash,
                actual: actualVaultRuntimeCodeHash
            )
        }
        return detailed.report
    }
}
