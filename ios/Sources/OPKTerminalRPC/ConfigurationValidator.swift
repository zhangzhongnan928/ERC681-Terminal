import Foundation
import OPKTerminalCore

public struct ValidationCheck: Hashable, Sendable, Codable {
    public let name: String
    public let detail: String

    public init(name: String, detail: String) {
        self.name = name
        self.detail = detail
    }
}

public struct ConfigurationValidationReport: Hashable, Sendable, Codable {
    public let chainID: UInt64
    public let checks: [ValidationCheck]

    public init(chainID: UInt64, checks: [ValidationCheck]) {
        self.chainID = chainID
        self.checks = checks
    }
}

public enum ConfigurationValidationError: Error, Equatable, Sendable {
    case wrongChain(expected: UInt64, actual: UInt64)
    case noCode(label: String, address: EthereumAddress)
    case factoryImplementationMismatch(expected: EthereumAddress, actual: EthereumAddress)
    case vaultFactoryMismatch(expected: EthereumAddress, actual: EthereumAddress)
    case tokenNotWhitelisted(EthereumAddress)
    case tokenDecimalsMismatch(token: EthereumAddress, expected: UInt8, actual: UInt8)
    case invalidDecimals
    case invalidTokenSymbol(EthereumAddress)
    case tokenSymbolMismatch(token: EthereumAddress, expected: String, actual: String)
}

extension ConfigurationValidationError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .wrongChain(expected, actual):
            "The RPC reported chain \(actual), but chain \(expected) is required."
        case let .noCode(label, address):
            "The configured \(label) \(address.hex) has no deployed code."
        case let .factoryImplementationMismatch(expected, actual):
            "Factory implementation \(actual.hex) does not match \(expected.hex)."
        case let .vaultFactoryMismatch(expected, actual):
            "Vault factory \(actual.hex) does not match \(expected.hex)."
        case let .tokenNotWhitelisted(token):
            "Token \(token.hex) is not whitelisted by this vault."
        case let .tokenDecimalsMismatch(token, expected, actual):
            "Token \(token.hex) reported \(actual) decimals, not \(expected)."
        case .invalidDecimals:
            "The token returned invalid decimals metadata."
        case let .invalidTokenSymbol(token):
            "Token \(token.hex) returned an invalid symbol."
        case let .tokenSymbolMismatch(token, expected, actual):
            "Token \(token.hex) reported symbol \(actual), not \(expected)."
        }
    }
}

public struct ConfigurationValidator: Sendable {
    private let rpc: any EthereumReadRPC

    public init(rpc: any EthereumReadRPC) {
        self.rpc = rpc
    }

    public func validate(_ configuration: TerminalConfiguration) async throws -> ConfigurationValidationReport {
        var checks = [ValidationCheck]()
        let actualChainID = try await rpc.chainID()
        guard actualChainID == configuration.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: configuration.chainID,
                actual: actualChainID
            )
        }
        checks.append(.init(name: "chain", detail: "chain ID \(actualChainID)"))

        let deployment = configuration.deployment
        try await requireCode(deployment.factory, label: "factory")
        try await requireCode(deployment.receiverImplementation, label: "receiver implementation")
        try await requireCode(deployment.vault, label: "vault")
        checks.append(.init(name: "contracts", detail: "factory, implementation, and vault have code"))

        let implementationData = try await rpc.call(
            to: deployment.factory,
            data: ABI.encodeCall(selector: ABI.implementationSelector),
            block: .latest
        )
        let actualImplementation = try ABI.decodeAddress(implementationData)
        guard actualImplementation == deployment.receiverImplementation else {
            throw ConfigurationValidationError.factoryImplementationMismatch(
                expected: deployment.receiverImplementation,
                actual: actualImplementation
            )
        }
        checks.append(.init(name: "factory implementation", detail: actualImplementation.hex))

        let factoryData = try await rpc.call(
            to: deployment.vault,
            data: ABI.encodeCall(selector: ABI.factorySelector),
            block: .latest
        )
        let actualFactory = try ABI.decodeAddress(factoryData)
        guard actualFactory == deployment.factory else {
            throw ConfigurationValidationError.vaultFactoryMismatch(
                expected: deployment.factory,
                actual: actualFactory
            )
        }
        checks.append(.init(name: "vault factory", detail: actualFactory.hex))

        if let vector = configuration.create2TestVector {
            try ReceiverDerivation.validate(
                vector,
                factory: deployment.factory,
                receiverImplementation: deployment.receiverImplementation
            )
            checks.append(.init(name: "CREATE2 vector", detail: vector.expectedReceiver.hex))
        }

        for token in configuration.tokens {
            try await requireCode(token.address, label: "token")
            let whitelistData = try await rpc.call(
                to: deployment.vault,
                data: ABI.encodeCall(
                    selector: ABI.isPaymentTokenSelector,
                    words: [ABI.word(token.address)]
                ),
                block: .latest
            )
            guard try ABI.decodeBool(whitelistData) else {
                throw ConfigurationValidationError.tokenNotWhitelisted(token.address)
            }
            let decimalsData = try await rpc.call(
                to: token.address,
                data: ABI.encodeCall(selector: ABI.decimalsSelector),
                block: .latest
            )
            guard let decimals = try ABI.decodeUInt256(decimalsData).uint64Value,
                  let actualDecimals = UInt8(exactly: decimals)
            else { throw ConfigurationValidationError.invalidDecimals }
            guard actualDecimals == token.decimals else {
                throw ConfigurationValidationError.tokenDecimalsMismatch(
                    token: token.address,
                    expected: token.decimals,
                    actual: actualDecimals
                )
            }
            let symbolData = try await rpc.call(
                to: token.address,
                data: ABI.encodeCall(selector: ABI.symbolSelector),
                block: .latest
            )
            let actualSymbol: String
            do {
                actualSymbol = try ABI.decodeDynamicString(symbolData)
            } catch {
                throw ConfigurationValidationError.invalidTokenSymbol(token.address)
            }
            guard actualSymbol == token.symbol else {
                throw ConfigurationValidationError.tokenSymbolMismatch(
                    token: token.address,
                    expected: token.symbol,
                    actual: actualSymbol
                )
            }
            checks.append(.init(
                name: "token \(token.symbol)",
                detail: "whitelisted, \(actualDecimals) decimals, symbol verified"
            ))
        }

        return ConfigurationValidationReport(chainID: actualChainID, checks: checks)
    }

    private func requireCode(_ address: EthereumAddress, label: String) async throws {
        let code = try await rpc.code(at: address, block: .latest)
        guard !code.isEmpty else {
            throw ConfigurationValidationError.noCode(label: label, address: address)
        }
    }
}
