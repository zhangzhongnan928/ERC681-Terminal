// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

public enum PaymentEvidenceRequestError: Error, Equatable, Sendable {
    case invalidChainID
    case zeroReceiver
    case zeroAsset
    case zeroExpectedAmount
    case fundingDoesNotFollowPublication
}

/// Immutable inputs for resolving the customer transaction that first completed an invoice.
///
/// The publication cursor proves the receiver was not already funded when the invoice was
/// published. The funding cursor is the canonical threshold cursor retained by the payment
/// monitor. A resolver must revalidate both cursors before attributing any transaction.
public struct PaymentEvidenceRequest: Hashable, Sendable, Codable {
    public let chainID: UInt64
    public let receiver: EthereumAddress
    public let asset: EthereumAddress
    public let expectedAmount: UInt256
    public let publicationCursor: PaymentConfirmationCursor
    public let fundingCursor: PaymentConfirmationCursor

    public init(
        chainID: UInt64,
        receiver: EthereumAddress,
        asset: EthereumAddress,
        expectedAmount: UInt256,
        publicationCursor: PaymentConfirmationCursor,
        fundingCursor: PaymentConfirmationCursor
    ) throws {
        guard chainID > 0 else { throw PaymentEvidenceRequestError.invalidChainID }
        guard !receiver.isZero else { throw PaymentEvidenceRequestError.zeroReceiver }
        guard !asset.isZero else { throw PaymentEvidenceRequestError.zeroAsset }
        guard !expectedAmount.isZero else {
            throw PaymentEvidenceRequestError.zeroExpectedAmount
        }
        guard fundingCursor.blockNumber > publicationCursor.blockNumber else {
            throw PaymentEvidenceRequestError.fundingDoesNotFollowPublication
        }
        self.chainID = chainID
        self.receiver = receiver
        self.asset = asset
        self.expectedAmount = expectedAmount
        self.publicationCursor = publicationCursor
        self.fundingCursor = fundingCursor
    }

    private enum CodingKeys: String, CodingKey {
        case chainID
        case receiver
        case asset
        case expectedAmount
        case publicationCursor
        case fundingCursor
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            chainID: container.decode(UInt64.self, forKey: .chainID),
            receiver: container.decode(EthereumAddress.self, forKey: .receiver),
            asset: container.decode(EthereumAddress.self, forKey: .asset),
            expectedAmount: container.decode(UInt256.self, forKey: .expectedAmount),
            publicationCursor: container.decode(
                PaymentConfirmationCursor.self,
                forKey: .publicationCursor
            ),
            fundingCursor: container.decode(
                PaymentConfirmationCursor.self,
                forKey: .fundingCursor
            )
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(chainID, forKey: .chainID)
        try container.encode(receiver, forKey: .receiver)
        try container.encode(asset, forKey: .asset)
        try container.encode(expectedAmount, forKey: .expectedAmount)
        try container.encode(publicationCursor, forKey: .publicationCursor)
        try container.encode(fundingCursor, forKey: .fundingCursor)
    }
}

/// Canonical, read-only evidence for the incoming customer transaction that completed a payment.
/// This type deliberately has no settlement or sweep transaction field.
public enum PaymentTransactionEvidenceError: Error, Equatable, Sendable {
    case zeroPayer
}

public struct PaymentTransactionEvidence: Hashable, Sendable, Codable {
    public let transactionHash: Bytes32
    public let payer: EthereumAddress
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    /// Canonical payment-block timestamp in Unix seconds.
    public let blockTimestamp: UInt64

    public init(
        transactionHash: Bytes32,
        payer: EthereumAddress,
        blockNumber: UInt64,
        blockHash: Bytes32,
        blockTimestamp: UInt64
    ) throws {
        guard !payer.isZero else { throw PaymentTransactionEvidenceError.zeroPayer }
        self.transactionHash = transactionHash
        self.payer = payer
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.blockTimestamp = blockTimestamp
    }

    private enum CodingKeys: String, CodingKey {
        case transactionHash
        case payer
        case blockNumber
        case blockHash
        case blockTimestamp
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            transactionHash: container.decode(Bytes32.self, forKey: .transactionHash),
            payer: container.decode(EthereumAddress.self, forKey: .payer),
            blockNumber: container.decode(UInt64.self, forKey: .blockNumber),
            blockHash: container.decode(Bytes32.self, forKey: .blockHash),
            blockTimestamp: container.decode(UInt64.self, forKey: .blockTimestamp)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(transactionHash, forKey: .transactionHash)
        try container.encode(payer, forKey: .payer)
        try container.encode(blockNumber, forKey: .blockNumber)
        try container.encode(blockHash, forKey: .blockHash)
        try container.encode(blockTimestamp, forKey: .blockTimestamp)
    }
}
