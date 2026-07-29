// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

public enum OperatorWalletError: Error, Equatable, Sendable {
    case walletAlreadyExists
    case walletNotCreated
    case deviceAuthenticationUnavailable
    case authenticationFailed
    case keychainFailure(Int32)
    case invalidPrivateKey
    case invalidPublicKey
    case invalidRecoveryID(Int32)
}

extension OperatorWalletError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .walletAlreadyExists:
            "A settlement operator wallet already exists on this device."
        case .walletNotCreated:
            "Create the settlement operator wallet in Settings first."
        case .deviceAuthenticationUnavailable:
            "Device owner authentication must be configured before creating or using the settlement wallet."
        case .authenticationFailed:
            "Device authentication did not authorize this settlement."
        case let .keychainFailure(status):
            "The settlement key could not be accessed in Keychain (status \(status))."
        case .invalidPrivateKey:
            "The settlement private key is invalid."
        case .invalidPublicKey:
            "The settlement public key is invalid."
        case let .invalidRecoveryID(value):
            "The signing library returned an Ethereum-incompatible recovery ID (\(value))."
        }
    }
}

public enum SettlementOperatorError: Error, Equatable, Sendable {
    case emptyBatch
    case batchTooLarge(maximum: Int)
    case mismatchedBatchLengths
    case duplicateInvoiceID(Bytes32)
    case chainMismatch(expected: UInt64, actual: UInt64)
    case operatorNotAuthorized
    case simulationFailed(String)
    case insufficientGasBalance(required: UInt256, available: UInt256)
    case receiverHasNoSweepableBalance(Bytes32)
    case receiverBalanceBelowRequired(invoiceID: Bytes32, required: UInt256, available: UInt256)
    case receiverBalanceChanged(invoiceID: Bytes32, confirmed: UInt256, current: UInt256)
    case alreadyFullySettled(Bytes32)
    case arithmeticOverflow
    case malformedRPCResponse
    case invalidRPCQuantity(String)
    case invalidRPCData(String)
    case invalidReceipt(String)
    case missingSweptEvent(Bytes32)
    case ambiguousSweptEvent(Bytes32)
    case zeroSweptAmount(Bytes32)
    case transactionHashMismatch(expected: Bytes32, actual: Bytes32)
    case tamperedPreparation
}

extension SettlementOperatorError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .emptyBatch:
            "Select at least one paid invoice to settle."
        case let .batchTooLarge(maximum):
            "A settlement batch can contain at most \(maximum) invoices."
        case .mismatchedBatchLengths:
            "The settlement invoice metadata is inconsistent."
        case let .duplicateInvoiceID(invoiceID):
            "Invoice \(invoiceID.hex) appears more than once in the settlement batch."
        case let .chainMismatch(expected, actual):
            "The settlement expects chain \(expected), but the RPC reported chain \(actual)."
        case .operatorNotAuthorized:
            "This wallet is not an authorized operator for the configured vault."
        case let .simulationFailed(message):
            "Settlement simulation failed: \(message)"
        case let .insufficientGasBalance(required, available):
            "The operator wallet needs up to \(required.decimalString) wei for gas but has \(available.decimalString) wei."
        case let .receiverHasNoSweepableBalance(invoiceID):
            "Invoice \(invoiceID.hex) has no live token balance to sweep."
        case let .receiverBalanceBelowRequired(invoiceID, required, available):
            "Invoice \(invoiceID.hex) needs a live balance of at least \(required.decimalString) token units before signing, but has \(available.decimalString)."
        case let .receiverBalanceChanged(invoiceID, confirmed, current):
            "Invoice \(invoiceID.hex) changed from confirmed balance \(confirmed.decimalString) to \(current.decimalString). Refresh and wait for the current amount to confirm before signing."
        case let .alreadyFullySettled(invoiceID):
            "Invoice \(invoiceID.hex) is already fully settled by confirmed sweep evidence."
        case .arithmeticOverflow:
            "The RPC returned transaction values too large to encode safely."
        case .malformedRPCResponse:
            "The RPC returned a malformed response."
        case let .invalidRPCQuantity(value):
            "The RPC returned an invalid quantity: \(value)."
        case let .invalidRPCData(value):
            "The RPC returned invalid hex data: \(value)."
        case let .invalidReceipt(message):
            "The transaction receipt is invalid: \(message)"
        case let .missingSweptEvent(invoiceID):
            "The mined transaction did not emit a matching Swept event for invoice \(invoiceID.hex)."
        case let .ambiguousSweptEvent(invoiceID):
            "The mined transaction emitted more than one matching Swept event for invoice \(invoiceID.hex)."
        case let .zeroSweptAmount(invoiceID):
            "The Swept event for invoice \(invoiceID.hex) reported zero tokens moved."
        case let .transactionHashMismatch(expected, actual):
            "The RPC returned transaction hash \(actual.hex), but the signed transaction hashes to \(expected.hex)."
        case .tamperedPreparation:
            "The prepared settlement no longer matches its validated zero-value sweep intent."
        }
    }
}

public struct SettlementSession: Hashable, Sendable, Codable {
    public let invoiceID: Bytes32
    public let receiver: EthereumAddress
    public let expectedAmount: UInt256
    public let priorConfirmedSweptAmount: UInt256

    public init(
        invoiceID: Bytes32,
        receiver: EthereumAddress,
        expectedAmount: UInt256,
        priorConfirmedSweptAmount: UInt256 = .zero
    ) {
        self.invoiceID = invoiceID
        self.receiver = receiver
        self.expectedAmount = expectedAmount
        self.priorConfirmedSweptAmount = priorConfirmedSweptAmount
    }
}

public struct SettlementIntent: Hashable, Sendable, Codable {
    public let chainID: UInt64
    public let vault: EthereumAddress
    public let token: EthereumAddress
    public let sessions: [SettlementSession]

    public init(
        chainID: UInt64,
        vault: EthereumAddress,
        token: EthereumAddress,
        sessions: [SettlementSession]
    ) throws {
        guard !sessions.isEmpty else { throw SettlementOperatorError.emptyBatch }
        guard sessions.count <= 20 else { throw SettlementOperatorError.batchTooLarge(maximum: 20) }
        var seen = Set<Bytes32>()
        for session in sessions {
            guard !session.expectedAmount.isZero else {
                throw SettlementOperatorError.alreadyFullySettled(session.invoiceID)
            }
            guard seen.insert(session.invoiceID).inserted else {
                throw SettlementOperatorError.duplicateInvoiceID(session.invoiceID)
            }
        }
        self.chainID = chainID
        self.vault = vault
        self.token = token
        self.sessions = sessions
    }

    public init(handoff: SettlementHandoff) throws {
        guard handoff.invoiceIDs.count == handoff.expectedAmounts.count,
              handoff.invoiceIDs.count == handoff.receivers.count
        else { throw SettlementOperatorError.mismatchedBatchLengths }
        try self.init(
            chainID: handoff.chainID,
            vault: handoff.vault,
            token: handoff.token,
            sessions: zip(
                zip(handoff.invoiceIDs, handoff.receivers),
                handoff.expectedAmounts
            ).map { pair, amount in
                SettlementSession(invoiceID: pair.0, receiver: pair.1, expectedAmount: amount)
            }
        )
    }
}

public enum FeeQuoteSource: String, Hashable, Sendable, Codable {
    case eip1559
    case gasPriceFallback
}

public struct EIP1559FeeQuote: Hashable, Sendable, Codable {
    public let maxPriorityFeePerGas: UInt64
    public let maxFeePerGas: UInt64
    public let source: FeeQuoteSource

    public init(maxPriorityFeePerGas: UInt64, maxFeePerGas: UInt64, source: FeeQuoteSource) {
        self.maxPriorityFeePerGas = maxPriorityFeePerGas
        self.maxFeePerGas = maxFeePerGas
        self.source = source
    }
}

public struct OperatorChainStatus: Hashable, Sendable {
    public let chainID: UInt64
    public let balance: UInt256
    public let isAuthorizedOperator: Bool
    public let isVaultOwner: Bool
    public let isLowGas: Bool

    init(
        chainID: UInt64,
        balance: UInt256,
        isAuthorizedOperator: Bool,
        isVaultOwner: Bool,
        isLowGas: Bool
    ) {
        self.chainID = chainID
        self.balance = balance
        self.isAuthorizedOperator = isAuthorizedOperator
        self.isVaultOwner = isVaultOwner
        self.isLowGas = isLowGas
    }
}

/// Both views of an operator EOA's native balance used before destructive key deletion.
/// `latest` prevents an unconfirmed withdrawal from making only the pending balance appear empty;
/// `pending` also catches an inbound transfer already visible in the node's pending state.
public struct OperatorNativeBalanceSnapshot: Hashable, Sendable {
    public let latest: UInt256
    public let pending: UInt256

    public init(latest: UInt256, pending: UInt256) {
        self.latest = latest
        self.pending = pending
    }

    public var isExactlyZero: Bool { latest.isZero && pending.isZero }
}

public struct VaultAuthorization: Hashable, Sendable {
    public let isOperator: Bool
    public let isOwner: Bool

    public init(isOperator: Bool, isOwner: Bool) {
        self.isOperator = isOperator
        self.isOwner = isOwner
    }

    public var isAuthorized: Bool { isOperator || isOwner }
}

public struct PreparedSettlement: Hashable, Sendable {
    public let intent: SettlementIntent
    public let operatorAddress: EthereumAddress
    public let calldata: Data
    public let gasLimit: UInt64
    public let feeQuote: EIP1559FeeQuote
    public let l1DataFeeReserve: UInt256
    public let maximumGasCost: UInt256
    public let operatorBalance: UInt256
    public let observedTokenBalances: [UInt256]

    init(
        intent: SettlementIntent,
        operatorAddress: EthereumAddress,
        calldata: Data,
        gasLimit: UInt64,
        feeQuote: EIP1559FeeQuote,
        l1DataFeeReserve: UInt256,
        maximumGasCost: UInt256,
        operatorBalance: UInt256,
        observedTokenBalances: [UInt256]
    ) {
        self.intent = intent
        self.operatorAddress = operatorAddress
        self.calldata = calldata
        self.gasLimit = gasLimit
        self.feeQuote = feeQuote
        self.l1DataFeeReserve = l1DataFeeReserve
        self.maximumGasCost = maximumGasCost
        self.operatorBalance = operatorBalance
        self.observedTokenBalances = observedTokenBalances
    }
}

public enum SettlementTransactionPhase: String, Hashable, Sendable, Codable {
    case pending
    case mined
    case final
    case failed
    case unknown
    case needsReview
}

public struct SettlementSubmission: Hashable, Sendable {
    public let intent: SettlementIntent
    public let transactionHash: Bytes32
    public let nonce: UInt64
    public let rawTransaction: Data
    public let phase: SettlementTransactionPhase
    public let broadcastError: String?

    init(
        intent: SettlementIntent,
        transactionHash: Bytes32,
        nonce: UInt64,
        rawTransaction: Data,
        phase: SettlementTransactionPhase,
        broadcastError: String?
    ) {
        self.intent = intent
        self.transactionHash = transactionHash
        self.nonce = nonce
        self.rawTransaction = rawTransaction
        self.phase = phase
        self.broadcastError = broadcastError
    }
}

public struct SignedSettlement: Hashable, Sendable {
    public let intent: SettlementIntent
    public let transactionHash: Bytes32
    public let nonce: UInt64
    public let rawTransaction: Data

    init(
        intent: SettlementIntent,
        transactionHash: Bytes32,
        nonce: UInt64,
        rawTransaction: Data
    ) {
        self.intent = intent
        self.transactionHash = transactionHash
        self.nonce = nonce
        self.rawTransaction = rawTransaction
    }
}

public struct EthereumLog: Hashable, Sendable {
    public let address: EthereumAddress
    public let topics: [Bytes32]
    public let data: Data
    public let logIndex: UInt64?
    public let blockHash: Bytes32?
    public let removed: Bool
    public let transactionHash: Bytes32?

    public init(
        address: EthereumAddress,
        topics: [Bytes32],
        data: Data,
        logIndex: UInt64? = nil,
        blockHash: Bytes32? = nil,
        removed: Bool = false,
        transactionHash: Bytes32? = nil
    ) {
        self.address = address
        self.topics = topics
        self.data = data
        self.logIndex = logIndex
        self.blockHash = blockHash
        self.removed = removed
        self.transactionHash = transactionHash
    }
}

public struct EthereumTransactionReceipt: Hashable, Sendable {
    public let transactionHash: Bytes32
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    public let succeeded: Bool
    public let logs: [EthereumLog]

    public init(
        transactionHash: Bytes32,
        blockNumber: UInt64,
        blockHash: Bytes32,
        succeeded: Bool,
        logs: [EthereumLog]
    ) {
        self.transactionHash = transactionHash
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.succeeded = succeeded
        self.logs = logs
    }
}

public struct VerifiedSweep: Hashable, Sendable {
    public let invoiceID: Bytes32
    public let receiver: EthereumAddress
    public let token: EthereumAddress
    public let sweptAmount: UInt256
    public let expectedAmount: UInt256
    public let fee: UInt256
    public let logIndex: UInt64
    public let blockHash: Bytes32
    public let transactionHash: Bytes32

    public init(
        invoiceID: Bytes32,
        receiver: EthereumAddress,
        token: EthereumAddress,
        sweptAmount: UInt256,
        expectedAmount: UInt256,
        fee: UInt256,
        logIndex: UInt64,
        blockHash: Bytes32,
        transactionHash: Bytes32
    ) {
        self.invoiceID = invoiceID
        self.receiver = receiver
        self.token = token
        self.sweptAmount = sweptAmount
        self.expectedAmount = expectedAmount
        self.fee = fee
        self.logIndex = logIndex
        self.blockHash = blockHash
        self.transactionHash = transactionHash
    }
}

public struct SettlementReconciliation: Hashable, Sendable {
    public let phase: SettlementTransactionPhase
    public let blockNumber: UInt64?
    public let confirmations: UInt64
    public let verifiedSweeps: [VerifiedSweep]
    public let failureReason: String?

    public init(
        phase: SettlementTransactionPhase,
        blockNumber: UInt64?,
        confirmations: UInt64,
        verifiedSweeps: [VerifiedSweep],
        failureReason: String?
    ) {
        self.phase = phase
        self.blockNumber = blockNumber
        self.confirmations = confirmations
        self.verifiedSweeps = verifiedSweeps
        self.failureReason = failureReason
    }
}
