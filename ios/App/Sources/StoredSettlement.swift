import Foundation
import OPKTerminalCore
import OPKTerminalOperator
import SwiftData

@Model
final class StoredSettlement {
    @Attribute(.unique) var id: UUID
    var createdAt: Date
    var updatedAt: Date
    var rpcURL: String
    var chainID: Int64
    var vault: String
    var tokenAddress: String
    var tokenSymbol: String
    var tokenDecimals: Int
    var operatorAddress: String
    var intentData: Data
    var transactionHash: String
    var nonce: Int64
    var rawTransaction: Data
    var phaseRaw: String
    var requiredConfirmations: Int64
    var confirmations: Int64
    var minedBlock: Int64?
    var maximumGasCost: String
    var l1DataFeeReserve: String
    var broadcastError: String?
    var failureReason: String?
    var eventProofsData: Data?
    /// False for legacy rows and newly finalized receipts until their canonical events have been
    /// applied idempotently to the bounded per-invoice cumulative proof index.
    var cumulativeEvidenceIndexed: Bool = false
    /// Durable retry cursor for fail-closed proof indexing. A malformed row remains visible and
    /// retryable without monopolizing every bounded reconciliation pass.
    var cumulativeEvidenceLastAttemptAt: Date?
    var cumulativeEvidenceNextAttemptAt: Date = Date.distantPast
    var cumulativeEvidenceFailureCount: Int = 0
    var cumulativeEvidenceLastError: String?
    /// Independent durable cursor for repairing needs-review rows from the cumulative invoice
    /// ledger. It cannot share the proof-index cursor because indexed rows park that cursor in
    /// the distant future.
    var cumulativeReviewLastAttemptAt: Date?
    var cumulativeReviewNextAttemptAt: Date = Date.distantPast
    var cumulativeReviewFailureCount: Int = 0
    var cumulativeReviewLastError: String?

    init(
        signed: SignedSettlement,
        prepared: PreparedSettlement,
        rpcURL: URL,
        tokenSymbol: String,
        tokenDecimals: UInt8,
        requiredConfirmations: UInt64
    ) throws {
        guard signed.intent == prepared.intent,
              let storedChainID = Int64(exactly: signed.intent.chainID),
              let storedNonce = Int64(exactly: signed.nonce),
              let storedConfirmations = Int64(exactly: max(requiredConfirmations, 1))
        else { throw AppSettingsError.invalidValue }

        let now = Date()
        id = UUID()
        createdAt = now
        updatedAt = now
        self.rpcURL = rpcURL.absoluteString
        chainID = storedChainID
        vault = signed.intent.vault.hex
        tokenAddress = signed.intent.token.hex
        self.tokenSymbol = tokenSymbol
        self.tokenDecimals = Int(tokenDecimals)
        operatorAddress = prepared.operatorAddress.hex
        intentData = try JSONEncoder().encode(IntentSnapshot(intent: signed.intent))
        transactionHash = signed.transactionHash.hex
        nonce = storedNonce
        rawTransaction = signed.rawTransaction
        phaseRaw = SettlementTransactionPhase.unknown.rawValue
        self.requiredConfirmations = storedConfirmations
        confirmations = 0
        maximumGasCost = prepared.maximumGasCost.decimalString
        l1DataFeeReserve = prepared.l1DataFeeReserve.decimalString
        broadcastError = "Signed and saved locally; broadcast has not completed."
        failureReason = nil
        eventProofsData = nil
    }

    var phase: SettlementTransactionPhase {
        get { SettlementTransactionPhase(rawValue: phaseRaw) ?? .unknown }
        set { phaseRaw = newValue.rawValue }
    }

    var invoiceIDs: [String] {
        (try? snapshot().invoiceIDs) ?? []
    }

    var invoiceCount: Int { invoiceIDs.count }

    var statusLabel: String {
        let phaseLabel = switch phase {
        case .pending: "Pending"
        case .mined: "Mined \(confirmations)/\(requiredConfirmations)"
        case .final: "Final"
        case .failed: "Failed"
        case .unknown: "Unknown"
        case .needsReview: "Needs review"
        }
        if !cumulativeEvidenceIndexed, cumulativeEvidenceLastError != nil {
            return "\(phaseLabel) · Proof review"
        }
        if cumulativeReviewLastError != nil {
            return "\(phaseLabel) · Repair blocked"
        }
        return phaseLabel
    }

    var isActiveClaim: Bool {
        phase == .pending || phase == .mined || phase == .unknown
    }

    func intent() throws -> SettlementIntent {
        let value = try snapshot()
        guard let restoredChainID = UInt64(exactly: chainID),
              value.invoiceIDs.count == value.receivers.count,
              value.invoiceIDs.count == value.expectedAmounts.count,
              (value.priorConfirmedSweptAmounts?.count ?? value.invoiceIDs.count)
                == value.invoiceIDs.count
        else { throw AppSettingsError.invalidValue }
        return try SettlementIntent(
            chainID: restoredChainID,
            vault: EthereumAddress(hex: vault, allowZero: false),
            token: EthereumAddress(hex: tokenAddress, allowZero: false),
            sessions: value.invoiceIDs.indices.map { index in
                SettlementSession(
                    invoiceID: try Bytes32(hex: value.invoiceIDs[index]),
                    receiver: try EthereumAddress(hex: value.receivers[index], allowZero: false),
                    expectedAmount: try UInt256(decimalString: value.expectedAmounts[index]),
                    priorConfirmedSweptAmount: try UInt256(
                        decimalString: value.priorConfirmedSweptAmounts?[index] ?? "0"
                    )
                )
            }
        )
    }

    func apply(_ submission: SettlementSubmission) throws {
        guard submission.transactionHash.hex == transactionHash,
              submission.intent == (try intent())
        else { throw AppSettingsError.invalidValue }
        phase = submission.phase
        broadcastError = submission.broadcastError
        updatedAt = Date()
    }

    func apply(_ reconciliation: SettlementReconciliation) throws {
        phase = reconciliation.phase
        guard let storedConfirmations = Int64(exactly: reconciliation.confirmations) else {
            throw AppSettingsError.invalidValue
        }
        confirmations = storedConfirmations
        if let block = reconciliation.blockNumber {
            guard let storedBlock = Int64(exactly: block) else {
                throw AppSettingsError.invalidValue
            }
            minedBlock = storedBlock
            // A canonical receipt supersedes any ambiguous/duplicate broadcast response.
            broadcastError = nil
        }
        failureReason = reconciliation.failureReason
        switch reconciliation.phase {
        case .mined, .final, .needsReview:
            // Store the exact proof set belonging to the currently observed receipt, including
            // an empty canonical subset. Otherwise a pre-finality receipt that later reorgs can
            // leave orphaned event bytes available for cumulative credit.
            eventProofsData = try JSONEncoder().encode(
                reconciliation.verifiedSweeps.map(StoredSweepEvidence.init)
            )
        case .pending, .unknown, .failed:
            // No stable receipt identity exists in these phases. Fail closed rather than
            // retaining provisional proof material from an earlier fork.
            eventProofsData = nil
        }
        cumulativeEvidenceIndexed = false
        cumulativeEvidenceLastAttemptAt = nil
        cumulativeEvidenceNextAttemptAt = .distantPast
        cumulativeEvidenceFailureCount = 0
        cumulativeEvidenceLastError = nil
        updatedAt = Date()
    }

    private func snapshot() throws -> IntentSnapshot {
        try JSONDecoder().decode(IntentSnapshot.self, from: intentData)
    }

    func eventProofs() throws -> [StoredSweepEvidence] {
        guard let eventProofsData else { return [] }
        return try JSONDecoder().decode([StoredSweepEvidence].self, from: eventProofsData)
    }
}

private struct IntentSnapshot: Codable {
    let invoiceIDs: [String]
    let receivers: [String]
    let expectedAmounts: [String]
    let priorConfirmedSweptAmounts: [String]?

    init(intent: SettlementIntent) {
        invoiceIDs = intent.sessions.map(\.invoiceID.hex)
        receivers = intent.sessions.map(\.receiver.hex)
        expectedAmounts = intent.sessions.map(\.expectedAmount.decimalString)
        priorConfirmedSweptAmounts = intent.sessions.map(\.priorConfirmedSweptAmount.decimalString)
    }
}

struct StoredSweepEvidence: Codable, Hashable {
    let transactionHash: String
    let logIndex: String?
    let blockHash: String?
    let invoiceID: String
    let receiver: String
    let token: String
    let sweptAmount: String
    let expectedAmount: String
    let fee: String

    init(_ proof: VerifiedSweep) {
        transactionHash = proof.transactionHash.hex
        logIndex = String(proof.logIndex)
        blockHash = proof.blockHash.hex
        invoiceID = proof.invoiceID.hex
        receiver = proof.receiver.hex
        token = proof.token.hex
        sweptAmount = proof.sweptAmount.decimalString
        expectedAmount = proof.expectedAmount.decimalString
        fee = proof.fee.decimalString
    }

    var identity: String {
        "\(transactionHash.lowercased()):\(logIndex ?? "missing")"
    }
}
