// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

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

/// Internal proof material needed by the trusted historical-provisioning boundary. Keeping the
/// runtime code from the same fixed-head validation avoids a second, potentially inconsistent
/// `eth_getCode` round trip solely to calculate the compiled vault code hash.
struct DetailedConfigurationValidation: Sendable {
    let report: ConfigurationValidationReport
    let vaultRuntimeCode: Data
}

public enum ConfigurationValidationError: Error, Equatable, Sendable {
    case wrongChain(expected: UInt64, actual: UInt64)
    case canonicalBlockChanged(blockNumber: UInt64)
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
        case let .canonicalBlockChanged(blockNumber):
            "Canonical block \(blockNumber) changed while validating terminal configuration. Retry."
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
        try await validateDetailed(configuration).report
    }

    func validateDetailed(
        _ configuration: TerminalConfiguration
    ) async throws -> DetailedConfigurationValidation {
        let evidence: ValidationEvidence
        if let batchRPC = rpc as? any EthereumBatchReadRPC {
            evidence = try await loadBatchedEvidence(
                configuration,
                rpc: batchRPC
            )
        } else {
            evidence = try await loadConcurrentEvidence(configuration)
        }
        return try DetailedConfigurationValidation(
            report: report(configuration, evidence: evidence),
            vaultRuntimeCode: evidence.vaultCode
        )
    }

    private func loadBatchedEvidence(
        _ configuration: TerminalConfiguration,
        rpc: any EthereumBatchReadRPC
    ) async throws -> ValidationEvidence {
        let anchor = try await rpc.batch([.chainID, .latestBlockIdentity])
        guard anchor.count == 2,
              case let .quantity(actualChainID) = anchor[0],
              case let .blockIdentity(blockNumber, initialBlockHash) = anchor[1]
        else { throw RPCDecodingError.invalidData("configuration anchor batch") }
        guard actualChainID == configuration.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: configuration.chainID,
                actual: actualChainID
            )
        }

        let deployment = configuration.deployment
        let block = RPCBlockTag.number(blockNumber)
        var requests: [EthereumReadBatchRequest] = [
            .code(address: deployment.factory, block: block),
            .code(address: deployment.receiverImplementation, block: block),
            .code(address: deployment.vault, block: block),
            .call(
                address: deployment.factory,
                data: ABI.encodeCall(selector: ABI.implementationSelector),
                block: block
            ),
            .call(
                address: deployment.vault,
                data: ABI.encodeCall(selector: ABI.factorySelector),
                block: block
            ),
        ]
        for token in configuration.tokens {
            requests.append(contentsOf: [
                .code(address: token.address, block: block),
                .call(
                    address: deployment.vault,
                    data: ABI.encodeCall(
                        selector: ABI.isPaymentTokenSelector,
                        words: [ABI.word(token.address)]
                    ),
                    block: block
                ),
                .call(
                    address: token.address,
                    data: ABI.encodeCall(selector: ABI.decimalsSelector),
                    block: block
                ),
                .call(
                    address: token.address,
                    data: ABI.encodeCall(selector: ABI.symbolSelector),
                    block: block
                ),
            ])
        }
        let results = try await resolveBatchedProofRequests(requests, rpc: rpc)
        guard results.count == requests.count else {
            throw RPCDecodingError.invalidData("configuration proof batch")
        }
        var index = 0
        func nextData() throws -> Data {
            guard index < results.count, case let .data(value) = results[index] else {
                throw RPCDecodingError.invalidData("configuration proof batch")
            }
            index += 1
            return value
        }
        let factoryCode = try nextData()
        let implementationCode = try nextData()
        let vaultCode = try nextData()
        let implementationData = try nextData()
        let factoryData = try nextData()
        var tokens = [TokenValidationEvidence]()
        tokens.reserveCapacity(configuration.tokens.count)
        for token in configuration.tokens {
            tokens.append(TokenValidationEvidence(
                token: token,
                code: try nextData(),
                whitelistData: try nextData(),
                decimalsData: try nextData(),
                symbolData: try nextData()
            ))
        }
        let final = try await rpc.batch([.canonicalBlockHash(blockNumber)])
        guard final.count == 1, case let .blockHash(finalBlockHash) = final[0] else {
            throw RPCDecodingError.invalidData("configuration final head")
        }
        guard finalBlockHash == initialBlockHash else {
            throw ConfigurationValidationError.canonicalBlockChanged(
                blockNumber: blockNumber
            )
        }
        return ValidationEvidence(
            chainID: actualChainID,
            factoryCode: factoryCode,
            implementationCode: implementationCode,
            vaultCode: vaultCode,
            implementationData: implementationData,
            factoryData: factoryData,
            tokens: tokens
        )
    }

    private func resolveBatchedProofRequests(
        _ requests: [EthereumReadBatchRequest],
        rpc: any EthereumBatchReadRPC
    ) async throws -> [EthereumReadBatchResult] {
        let chunks = stride(from: 0, to: requests.count, by: 10).map { start in
            Array(requests[start..<min(start + 10, requests.count)])
        }
        return try await withThrowingTaskGroup(
            of: (Int, [EthereumReadBatchResult]).self,
            returning: [EthereumReadBatchResult].self
        ) { group in
            var nextChunk = 0
            let maximumConcurrentBatches = min(6, chunks.count)
            func enqueue(_ index: Int) {
                let chunk = chunks[index]
                group.addTask { (index, try await rpc.batch(chunk)) }
            }
            while nextChunk < maximumConcurrentBatches {
                enqueue(nextChunk)
                nextChunk += 1
            }
            var resolved = Array<[EthereumReadBatchResult]?>(
                repeating: nil,
                count: chunks.count
            )
            for try await (index, values) in group {
                guard values.count == chunks[index].count else {
                    throw RPCDecodingError.invalidData("partial configuration proof batch")
                }
                resolved[index] = values
                if nextChunk < chunks.count {
                    enqueue(nextChunk)
                    nextChunk += 1
                }
            }
            guard resolved.allSatisfy({ $0 != nil }) else {
                throw RPCDecodingError.invalidData("missing configuration proof batch")
            }
            return resolved.flatMap { $0! }
        }
    }

    private func loadConcurrentEvidence(
        _ configuration: TerminalConfiguration
    ) async throws -> ValidationEvidence {
        async let chainID = rpc.chainID()
        async let head = rpc.blockNumber()
        let (actualChainID, blockNumber) = try await (chainID, head)
        guard actualChainID == configuration.chainID else {
            throw ConfigurationValidationError.wrongChain(
                expected: configuration.chainID,
                actual: actualChainID
            )
        }
        let deployment = configuration.deployment
        let block = RPCBlockTag.number(blockNumber)
        let initialBlockHash = try await rpc.canonicalBlockHash(at: blockNumber)
        async let factoryCode = rpc.code(at: deployment.factory, block: block)
        async let implementationCode = rpc.code(
            at: deployment.receiverImplementation,
            block: block
        )
        async let vaultCode = rpc.code(at: deployment.vault, block: block)
        async let implementationData = rpc.call(
            to: deployment.factory,
            data: ABI.encodeCall(selector: ABI.implementationSelector),
            block: block
        )
        async let factoryData = rpc.call(
            to: deployment.vault,
            data: ABI.encodeCall(selector: ABI.factorySelector),
            block: block
        )
        let baseEvidence = try await (
            factoryCode,
            implementationCode,
            vaultCode,
            implementationData,
            factoryData
        )

        var tokenEvidence = [TokenValidationEvidence]()
        tokenEvidence.reserveCapacity(configuration.tokens.count)
        for token in configuration.tokens {
            async let code = rpc.code(at: token.address, block: block)
            async let whitelistData = rpc.call(
                to: deployment.vault,
                data: ABI.encodeCall(
                    selector: ABI.isPaymentTokenSelector,
                    words: [ABI.word(token.address)]
                ),
                block: block
            )
            async let decimalsData = rpc.call(
                to: token.address,
                data: ABI.encodeCall(selector: ABI.decimalsSelector),
                block: block
            )
            async let symbolData = rpc.call(
                to: token.address,
                data: ABI.encodeCall(selector: ABI.symbolSelector),
                block: block
            )
            tokenEvidence.append(try await TokenValidationEvidence(
                token: token,
                code: code,
                whitelistData: whitelistData,
                decimalsData: decimalsData,
                symbolData: symbolData
            ))
        }
        let finalBlockHash = try await rpc.canonicalBlockHash(at: blockNumber)
        guard finalBlockHash == initialBlockHash else {
            throw ConfigurationValidationError.canonicalBlockChanged(
                blockNumber: blockNumber
            )
        }
        return ValidationEvidence(
            chainID: actualChainID,
            factoryCode: baseEvidence.0,
            implementationCode: baseEvidence.1,
            vaultCode: baseEvidence.2,
            implementationData: baseEvidence.3,
            factoryData: baseEvidence.4,
            tokens: tokenEvidence
        )
    }

    private func report(
        _ configuration: TerminalConfiguration,
        evidence: ValidationEvidence
    ) throws -> ConfigurationValidationReport {
        var checks = [ValidationCheck]()
        checks.append(.init(name: "chain", detail: "chain ID \(evidence.chainID)"))
        let deployment = configuration.deployment
        try requireCode(evidence.factoryCode, address: deployment.factory, label: "factory")
        try requireCode(
            evidence.implementationCode,
            address: deployment.receiverImplementation,
            label: "receiver implementation"
        )
        try requireCode(evidence.vaultCode, address: deployment.vault, label: "vault")
        checks.append(.init(name: "contracts", detail: "factory, implementation, and vault have code"))

        let actualImplementation = try ABI.decodeAddress(evidence.implementationData)
        guard actualImplementation == deployment.receiverImplementation else {
            throw ConfigurationValidationError.factoryImplementationMismatch(
                expected: deployment.receiverImplementation,
                actual: actualImplementation
            )
        }
        checks.append(.init(name: "factory implementation", detail: actualImplementation.hex))

        let actualFactory = try ABI.decodeAddress(evidence.factoryData)
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

        for tokenEvidence in evidence.tokens {
            let token = tokenEvidence.token
            try requireCode(tokenEvidence.code, address: token.address, label: "token")
            guard try ABI.decodeBool(tokenEvidence.whitelistData) else {
                throw ConfigurationValidationError.tokenNotWhitelisted(token.address)
            }
            guard let decimals = try ABI.decodeUInt256(tokenEvidence.decimalsData).uint64Value,
                  let actualDecimals = UInt8(exactly: decimals)
            else { throw ConfigurationValidationError.invalidDecimals }
            guard actualDecimals == token.decimals else {
                throw ConfigurationValidationError.tokenDecimalsMismatch(
                    token: token.address,
                    expected: token.decimals,
                    actual: actualDecimals
                )
            }
            let actualSymbol: String
            do {
                actualSymbol = try ABI.decodeDynamicString(tokenEvidence.symbolData)
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

        return ConfigurationValidationReport(chainID: evidence.chainID, checks: checks)
    }

    private func requireCode(_ code: Data, address: EthereumAddress, label: String) throws {
        guard !code.isEmpty else {
            throw ConfigurationValidationError.noCode(label: label, address: address)
        }
    }
}

private struct ValidationEvidence: Sendable {
    let chainID: UInt64
    let factoryCode: Data
    let implementationCode: Data
    let vaultCode: Data
    let implementationData: Data
    let factoryData: Data
    let tokens: [TokenValidationEvidence]
}

private struct TokenValidationEvidence: Sendable {
    let token: PaymentToken
    let code: Data
    let whitelistData: Data
    let decimalsData: Data
    let symbolData: Data
}
