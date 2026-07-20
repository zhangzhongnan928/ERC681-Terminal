import Foundation

public enum OPKProtocolVersion: String, Hashable, Sendable, Codable {
    case v1_4_1 = "1.4.1"
    case v1_5 = "1.5"
}

public struct ConfirmationPolicy: Hashable, Sendable, Codable {
    public let requiredBlocks: UInt64

    public init(requiredBlocks: UInt64) {
        self.requiredBlocks = max(requiredBlocks, 1)
    }
}

public struct TerminalConfiguration: Hashable, Sendable, Codable {
    public let chainID: UInt64
    public let rpcEndpoints: [URL]
    public let protocolVersion: OPKProtocolVersion
    public let deployment: OPKDeployment
    public let tokens: [PaymentToken]
    public let confirmationPolicy: ConfirmationPolicy
    public let create2TestVector: Create2TestVector?

    public init(
        chainID: UInt64,
        rpcEndpoints: [URL],
        protocolVersion: OPKProtocolVersion,
        deployment: OPKDeployment,
        tokens: [PaymentToken],
        confirmationPolicy: ConfirmationPolicy = .init(requiredBlocks: 2),
        create2TestVector: Create2TestVector? = nil
    ) throws {
        guard chainID > 0 else { throw ERC681Error.invalidChainID }
        guard !rpcEndpoints.isEmpty else { throw RPCURLPolicyError.missingEndpoint }
        try rpcEndpoints.forEach(RPCURLPolicy.validate)
        guard !tokens.isEmpty else { throw TerminalConfigurationError.noTokens }
        self.chainID = chainID
        self.rpcEndpoints = rpcEndpoints
        self.protocolVersion = protocolVersion
        self.deployment = deployment
        self.tokens = tokens
        self.confirmationPolicy = confirmationPolicy
        self.create2TestVector = create2TestVector
    }
}

public enum TerminalConfigurationError: Error, Equatable, Sendable {
    case noTokens
}

public enum RPCURLPolicyError: Error, Equatable, Sendable {
    case missingEndpoint
    case unsupportedScheme
    case missingHost
    case embeddedCredentials
    case insecureNonLoopback
}

public enum RPCURLPolicy {
    public static func validate(_ url: URL) throws {
        guard let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
            throw RPCURLPolicyError.unsupportedScheme
        }
        guard let host = url.host?.lowercased(), !host.isEmpty else {
            throw RPCURLPolicyError.missingHost
        }
        guard url.user == nil, url.password == nil else {
            throw RPCURLPolicyError.embeddedCredentials
        }
        guard url.fragment == nil else {
            throw RPCURLPolicyError.unsupportedScheme
        }
        if scheme == "http" {
            let loopbackHosts: Set<String> = ["localhost", "127.0.0.1", "::1"]
            guard loopbackHosts.contains(host) else {
                throw RPCURLPolicyError.insecureNonLoopback
            }
        }
    }
}

public struct PaymentRequest: Hashable, Sendable, Codable, Identifiable {
    public let invoiceID: Bytes32
    public let terminalIdentifier: TerminalIdentifier
    public let chainID: UInt64
    public let vault: EthereumAddress
    public let receiver: EthereumAddress
    public let token: PaymentToken
    public let expectedAmount: UInt256
    public let erc681URI: String
    public let createdAt: Date
    public let expiresAt: Date?

    public init(
        invoiceID: Bytes32,
        terminalIdentifier: TerminalIdentifier,
        chainID: UInt64,
        vault: EthereumAddress,
        receiver: EthereumAddress,
        token: PaymentToken,
        expectedAmount: UInt256,
        erc681URI: String,
        createdAt: Date,
        expiresAt: Date?
    ) {
        self.invoiceID = invoiceID
        self.terminalIdentifier = terminalIdentifier
        self.chainID = chainID
        self.vault = vault
        self.receiver = receiver
        self.token = token
        self.expectedAmount = expectedAmount
        self.erc681URI = erc681URI
        self.createdAt = createdAt
        self.expiresAt = expiresAt
    }

    public var id: Bytes32 { invoiceID }
}

public enum PaymentStatus: Hashable, Sendable, Codable {
    case waiting
    case partial(received: UInt256)
    case confirming(received: UInt256, confirmations: UInt64, required: UInt64)
    case paid(received: UInt256)
    case overpaid(received: UInt256, excess: UInt256)
    case expired(lastObserved: UInt256)
}

/// A confirmation cursor is valid only while the saved block number still resolves to the same
/// canonical block hash. Persisting the number alone would allow a replacement fork to inherit
/// confirmations earned by the displaced block.
public struct PaymentConfirmationCursor: Hashable, Sendable, Codable {
    public let blockNumber: UInt64
    public let blockHash: Bytes32

    public init(blockNumber: UInt64, blockHash: Bytes32) {
        self.blockNumber = blockNumber
        self.blockHash = blockHash
    }
}

public struct PaymentObservation: Hashable, Sendable, Codable {
    public let invoiceID: Bytes32
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    public let balance: UInt256
    public let status: PaymentStatus
    public let thresholdBlock: UInt64?
    public let thresholdBlockHash: Bytes32?
    /// Saved cursors that the RPC sampler re-read and matched against the canonical chain during
    /// this observation. App-specific confirmation windows may only be preserved from this set.
    public let validatedPreviousCursors: [PaymentConfirmationCursor]

    public init(
        invoiceID: Bytes32,
        blockNumber: UInt64,
        blockHash: Bytes32,
        balance: UInt256,
        status: PaymentStatus,
        thresholdBlock: UInt64?,
        thresholdBlockHash: Bytes32?,
        validatedPreviousCursors: [PaymentConfirmationCursor] = []
    ) {
        self.invoiceID = invoiceID
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.balance = balance
        self.status = status
        self.thresholdBlock = thresholdBlock
        self.thresholdBlockHash = thresholdBlockHash
        self.validatedPreviousCursors = validatedPreviousCursors
    }

    public var thresholdCursor: PaymentConfirmationCursor? {
        guard let thresholdBlock, let thresholdBlockHash else { return nil }
        return PaymentConfirmationCursor(
            blockNumber: thresholdBlock,
            blockHash: thresholdBlockHash
        )
    }

    public func validated(_ cursor: PaymentConfirmationCursor) -> Bool {
        validatedPreviousCursors.contains(cursor)
    }
}

public enum InvoiceFactory {
    public static func invoiceID(
        terminal: EthereumAddress,
        timestamp: UInt64,
        nonce: UInt256
    ) -> Bytes32 {
        Keccak256.hash(ABI.encodeInvoiceID(terminal: terminal, timestamp: timestamp, nonce: nonce))
    }

    public static func create(
        terminalIdentifier: TerminalIdentifier,
        amount: UInt256,
        token: PaymentToken,
        configuration: TerminalConfiguration,
        createdAt: Date = Date(),
        expiresAt: Date? = nil,
        nonce: Bytes32 = .random()
    ) throws -> PaymentRequest {
        guard configuration.tokens.contains(where: { $0.address == token.address }) else {
            throw InvoiceFactoryError.tokenNotConfigured
        }
        guard !amount.isZero else { throw TokenAmountError.zeroNotAllowed }
        let timestamp = UInt64(max(0, createdAt.timeIntervalSince1970.rounded(.down)))
        let nonceValue = UInt256(bigEndian: nonce.data)
        let invoiceID = invoiceID(
            terminal: terminalIdentifier.address,
            timestamp: timestamp,
            nonce: nonceValue
        )
        let deployment = configuration.deployment
        let receiver = try ReceiverDerivation.receiver(
            factory: deployment.factory,
            receiverImplementation: deployment.receiverImplementation,
            vault: deployment.vault,
            invoiceID: invoiceID
        )
        let erc681 = try ERC681TransferRequest(
            token: token.address,
            chainID: configuration.chainID,
            recipient: receiver,
            amount: amount
        )
        return PaymentRequest(
            invoiceID: invoiceID,
            terminalIdentifier: terminalIdentifier,
            chainID: configuration.chainID,
            vault: deployment.vault,
            receiver: receiver,
            token: token,
            expectedAmount: amount,
            erc681URI: erc681.canonicalString,
            createdAt: createdAt,
            expiresAt: expiresAt
        )
    }
}

public enum InvoiceFactoryError: Error, Equatable, Sendable {
    case tokenNotConfigured
}

public struct SettlementHandoff: Hashable, Sendable, Codable {
    public let chainID: UInt64
    public let vault: EthereumAddress
    public let token: EthereumAddress
    public let invoiceIDs: [Bytes32]
    public let expectedAmounts: [UInt256]
    public let receivers: [EthereumAddress]

    public static func make(
        chainID: UInt64,
        vault: EthereumAddress,
        token: EthereumAddress,
        invoices: [PaymentRequest]
    ) throws -> SettlementHandoff {
        guard !invoices.isEmpty else { throw SettlementHandoffError.noInvoices }
        guard invoices.allSatisfy({
            $0.chainID == chainID && $0.vault == vault && $0.token.address == token
        }) else { throw SettlementHandoffError.mixedBatch }
        let ids = invoices.map(\.invoiceID)
        let amounts = invoices.map(\.expectedAmount)
        return SettlementHandoff(
            chainID: chainID,
            vault: vault,
            token: token,
            invoiceIDs: ids,
            expectedAmounts: amounts,
            receivers: invoices.map(\.receiver)
        )
    }
}

public enum SettlementHandoffError: Error, Equatable, Sendable {
    case noInvoices
    case mixedBatch
}
