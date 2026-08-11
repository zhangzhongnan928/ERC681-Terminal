// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

/// One canonical block and, when requested, its full top-level transactions.
public struct PaymentEvidenceBlock: Hashable, Sendable {
    public let number: UInt64
    public let hash: Bytes32
    public let timestamp: UInt64
    public let transactions: [PaymentEvidenceTransaction]

    public init(
        number: UInt64,
        hash: Bytes32,
        timestamp: UInt64,
        transactions: [PaymentEvidenceTransaction] = []
    ) {
        self.number = number
        self.hash = hash
        self.timestamp = timestamp
        self.transactions = transactions
    }
}

/// Minimal full-transaction fields needed to attribute a direct native-asset payment.
public struct PaymentEvidenceTransaction: Hashable, Sendable {
    public let hash: Bytes32
    public let from: EthereumAddress
    public let to: EthereumAddress?
    public let value: UInt256
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    public let transactionIndex: UInt64

    public init(
        hash: Bytes32,
        from: EthereumAddress,
        to: EthereumAddress?,
        value: UInt256,
        blockNumber: UInt64,
        blockHash: Bytes32,
        transactionIndex: UInt64
    ) {
        self.hash = hash
        self.from = from
        self.to = to
        self.value = value
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.transactionIndex = transactionIndex
    }
}

/// Minimal decoded ERC-20 Transfer fields used for deterministic threshold attribution.
public struct PaymentEvidenceERC20Transfer: Hashable, Sendable {
    public let token: EthereumAddress
    public let transactionHash: Bytes32
    public let payer: EthereumAddress
    public let recipient: EthereumAddress
    public let amount: UInt256
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    public let logIndex: UInt64
    public let removed: Bool

    public init(
        token: EthereumAddress,
        transactionHash: Bytes32,
        payer: EthereumAddress,
        recipient: EthereumAddress,
        amount: UInt256,
        blockNumber: UInt64,
        blockHash: Bytes32,
        logIndex: UInt64,
        removed: Bool = false
    ) {
        self.token = token
        self.transactionHash = transactionHash
        self.payer = payer
        self.recipient = recipient
        self.amount = amount
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.logIndex = logIndex
        self.removed = removed
    }
}

/// A deliberately narrow, read-only RPC boundary for incoming payment attribution. It is separate
/// from `EthereumReadRPC`, so existing payment-monitor and provisioning mocks need no new methods.
public protocol PaymentEvidenceChainClient: Sendable {
    func chainID() async throws -> UInt64
    func paymentEvidenceAssetBalance(
        asset: EthereumAddress,
        holder: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> UInt256
    func paymentEvidenceBlock(
        at blockNumber: UInt64,
        includeTransactions: Bool
    ) async throws -> PaymentEvidenceBlock
    func paymentEvidenceERC20Transfers(
        token: EthereumAddress,
        recipient: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> [PaymentEvidenceERC20Transfer]
}

public enum PaymentEvidenceResolutionError: Error, Equatable, Sendable {
    case wrongChain(expected: UInt64, actual: UInt64)
    case canonicalBlockChanged(blockNumber: UInt64)
    case blockNumberMismatch(expected: UInt64, actual: UInt64)
    case publicationAlreadyFunded
    case removedTransferLog
    case transferLogMismatch
    case nativeTransactionMismatch
    case duplicateTransferLogIndex(UInt64)
    case duplicateNativeTransactionIndex(UInt64)
    case amountOverflow
}

extension PaymentEvidenceResolutionError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .wrongChain(expected, actual):
            "Wrong network: expected chain \(expected), received \(actual)."
        case let .canonicalBlockChanged(blockNumber):
            "Canonical block \(blockNumber) no longer matches the saved payment evidence."
        case let .blockNumberMismatch(expected, actual):
            "RPC returned block \(actual) while block \(expected) was requested."
        case .publicationAlreadyFunded:
            "The receiver already satisfied the invoice at publication."
        case .removedTransferLog:
            "An ERC-20 payment log was marked removed."
        case .transferLogMismatch:
            "An ERC-20 payment log did not match its requested token, recipient, or block."
        case .nativeTransactionMismatch:
            "A native payment transaction did not match its canonical block."
        case let .duplicateTransferLogIndex(index):
            "ERC-20 payment logs contained duplicate log index \(index)."
        case let .duplicateNativeTransactionIndex(index):
            "Native payment transactions contained duplicate transaction index \(index)."
        case .amountOverflow:
            "Incoming payment amounts overflowed UInt256."
        }
    }
}

/// Resolves transaction identity only after the existing balance monitor has established payment.
/// The saved publication and funding cursors remain the authority for the search bracket.
public struct PaymentTransactionResolver: Sendable {
    private let client: any PaymentEvidenceChainClient

    public init(client: any PaymentEvidenceChainClient) {
        self.client = client
    }

    public func resolve(
        _ request: PaymentEvidenceRequest
    ) async throws -> PaymentTransactionEvidence? {
        let actualChainID = try await client.chainID()
        guard actualChainID == request.chainID else {
            throw PaymentEvidenceResolutionError.wrongChain(
                expected: request.chainID,
                actual: actualChainID
            )
        }

        _ = try await requireCanonical(
            request.publicationCursor,
            includeTransactions: false
        )
        _ = try await requireCanonical(
            request.fundingCursor,
            includeTransactions: false
        )

        var balanceCache = [UInt64: UInt256]()
        func balance(at blockNumber: UInt64) async throws -> UInt256 {
            if let cached = balanceCache[blockNumber] { return cached }
            let value = try await client.paymentEvidenceAssetBalance(
                asset: request.asset,
                holder: request.receiver,
                blockNumber: blockNumber
            )
            balanceCache[blockNumber] = value
            return value
        }

        let publicationBalance = try await balance(
            at: request.publicationCursor.blockNumber
        )
        guard publicationBalance < request.expectedAmount else {
            throw PaymentEvidenceResolutionError.publicationAlreadyFunded
        }

        let crossingBlockNumber = try await firstBlockMeetingAmount(
            firstCandidateBlock: request.publicationCursor.blockNumber + 1,
            lastCandidateBlock: request.fundingCursor.blockNumber,
            expectedAmount: request.expectedAmount,
            balanceAt: balance
        )
        guard let crossingBlockNumber else { return nil }

        let priorBalance = try await balance(at: crossingBlockNumber - 1)
        let isNative = NativeAsset.isNative(request.asset)
        let paymentBlock = try await client.paymentEvidenceBlock(
            at: crossingBlockNumber,
            includeTransactions: isNative
        )
        try requireRequestedBlock(paymentBlock, number: crossingBlockNumber)

        let selected: (transactionHash: Bytes32, payer: EthereumAddress)?
        if isNative {
            selected = try selectNativeTransaction(
                from: paymentBlock,
                receiver: request.receiver,
                priorBalance: priorBalance,
                expectedAmount: request.expectedAmount
            )
        } else {
            let transfers = try await client.paymentEvidenceERC20Transfers(
                token: request.asset,
                recipient: request.receiver,
                blockNumber: crossingBlockNumber
            )
            selected = try selectERC20Transfer(
                transfers,
                token: request.asset,
                receiver: request.receiver,
                block: paymentBlock,
                priorBalance: priorBalance,
                expectedAmount: request.expectedAmount
            )
        }
        guard let selected else { return nil }

        let finalPaymentBlock = try await requireCanonical(
            PaymentConfirmationCursor(
                blockNumber: crossingBlockNumber,
                blockHash: paymentBlock.hash
            ),
            includeTransactions: false
        )
        guard finalPaymentBlock.timestamp == paymentBlock.timestamp else {
            throw PaymentEvidenceResolutionError.canonicalBlockChanged(
                blockNumber: crossingBlockNumber
            )
        }
        _ = try await requireCanonical(
            request.publicationCursor,
            includeTransactions: false
        )
        _ = try await requireCanonical(
            request.fundingCursor,
            includeTransactions: false
        )

        return try PaymentTransactionEvidence(
            transactionHash: selected.transactionHash,
            payer: selected.payer,
            blockNumber: paymentBlock.number,
            blockHash: paymentBlock.hash,
            blockTimestamp: paymentBlock.timestamp
        )
    }

    private func requireCanonical(
        _ cursor: PaymentConfirmationCursor,
        includeTransactions: Bool
    ) async throws -> PaymentEvidenceBlock {
        let block = try await client.paymentEvidenceBlock(
            at: cursor.blockNumber,
            includeTransactions: includeTransactions
        )
        try requireRequestedBlock(block, number: cursor.blockNumber)
        guard block.hash == cursor.blockHash else {
            throw PaymentEvidenceResolutionError.canonicalBlockChanged(
                blockNumber: cursor.blockNumber
            )
        }
        return block
    }

    private func requireRequestedBlock(
        _ block: PaymentEvidenceBlock,
        number: UInt64
    ) throws {
        guard block.number == number else {
            throw PaymentEvidenceResolutionError.blockNumberMismatch(
                expected: number,
                actual: block.number
            )
        }
    }

    private func firstBlockMeetingAmount(
        firstCandidateBlock: UInt64,
        lastCandidateBlock: UInt64,
        expectedAmount: UInt256,
        balanceAt: (UInt64) async throws -> UInt256
    ) async throws -> UInt64? {
        guard try await balanceAt(lastCandidateBlock) >= expectedAmount else {
            return nil
        }
        var low = firstCandidateBlock
        var high = lastCandidateBlock
        while low < high {
            let middle = low + (high - low) / 2
            if try await balanceAt(middle) >= expectedAmount {
                high = middle
            } else {
                low = middle + 1
            }
        }
        return low
    }

    private func selectERC20Transfer(
        _ transfers: [PaymentEvidenceERC20Transfer],
        token: EthereumAddress,
        receiver: EthereumAddress,
        block: PaymentEvidenceBlock,
        priorBalance: UInt256,
        expectedAmount: UInt256
    ) throws -> (transactionHash: Bytes32, payer: EthereumAddress)? {
        var seenIndexes = Set<UInt64>()
        let ordered = try transfers.sorted { lhs, rhs in
            lhs.logIndex < rhs.logIndex
        }.map { transfer in
            guard !transfer.removed else {
                throw PaymentEvidenceResolutionError.removedTransferLog
            }
            guard transfer.token == token,
                  transfer.recipient == receiver,
                  !transfer.payer.isZero,
                  transfer.blockNumber == block.number,
                  transfer.blockHash == block.hash
            else { throw PaymentEvidenceResolutionError.transferLogMismatch }
            guard seenIndexes.insert(transfer.logIndex).inserted else {
                throw PaymentEvidenceResolutionError.duplicateTransferLogIndex(
                    transfer.logIndex
                )
            }
            return transfer
        }

        var cumulative = priorBalance
        for transfer in ordered {
            let addition = cumulative.addingReportingOverflow(transfer.amount)
            guard !addition.overflow else {
                throw PaymentEvidenceResolutionError.amountOverflow
            }
            cumulative = addition.partialValue
            if cumulative >= expectedAmount {
                return (transfer.transactionHash, transfer.payer)
            }
        }
        return nil
    }

    private func selectNativeTransaction(
        from block: PaymentEvidenceBlock,
        receiver: EthereumAddress,
        priorBalance: UInt256,
        expectedAmount: UInt256
    ) throws -> (transactionHash: Bytes32, payer: EthereumAddress)? {
        let matching = block.transactions.filter { $0.to == receiver }
            .sorted { $0.transactionIndex < $1.transactionIndex }
        var seenIndexes = Set<UInt64>()
        var cumulative = priorBalance
        for transaction in matching {
            guard transaction.blockNumber == block.number,
                  transaction.blockHash == block.hash,
                  !transaction.from.isZero
            else { throw PaymentEvidenceResolutionError.nativeTransactionMismatch }
            guard seenIndexes.insert(transaction.transactionIndex).inserted else {
                throw PaymentEvidenceResolutionError.duplicateNativeTransactionIndex(
                    transaction.transactionIndex
                )
            }
            let addition = cumulative.addingReportingOverflow(transaction.value)
            guard !addition.overflow else {
                throw PaymentEvidenceResolutionError.amountOverflow
            }
            cumulative = addition.partialValue
            if cumulative >= expectedAmount {
                return (transaction.hash, transaction.from)
            }
        }
        return nil
    }
}
