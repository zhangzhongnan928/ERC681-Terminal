import Foundation
import OPKTerminalCore
import OPKTerminalOperator
import SwiftData

struct CumulativeSettlementKey: Hashable {
    let chainID: UInt64
    let vault: String
    let invoiceID: String
    let token: String
}

struct CanonicalSettlementEvidence: Hashable {
    let key: CumulativeSettlementKey
    let identity: String
    let amount: UInt256
    let minedBlock: UInt64
}

@Model
final class StoredCanonicalSweepProof {
    @Attribute(.unique) var identity: String
    var settlementID: UUID
    var appliedAt: Date

    init(identity: String, settlementID: UUID, appliedAt: Date = Date()) {
        self.identity = identity
        self.settlementID = settlementID
        self.appliedAt = appliedAt
    }
}

enum OperatorResetSafety {
    static func allowsOperatorKeyDeletion(issuedInvoiceCount: Int) -> Bool {
        issuedInvoiceCount == 0
    }

    static func requireEmptyNativeBalance(
        _ snapshot: OperatorNativeBalanceSnapshot
    ) throws {
        guard snapshot.isExactlyZero else {
            throw OperatorResetSafetyError.nativeBalanceNotZero(
                latest: snapshot.latest,
                pending: snapshot.pending
            )
        }
    }

    static func isUnresolved(_ phase: SettlementTransactionPhase) -> Bool {
        switch phase {
        case .pending, .mined, .unknown, .needsReview:
            true
        case .final, .failed:
            false
        }
    }

    static func confirmedCumulativeTotals(
        _ records: [StoredSettlement]
    ) throws -> [CumulativeSettlementKey: UInt256] {
        var totals = [CumulativeSettlementKey: UInt256]()
        var identities = Set<String>()

        for record in records where record.phase == .final || record.phase == .needsReview {
            for proof in try canonicalEvidence(in: record) {
                guard identities.insert(proof.identity).inserted else { continue }
                let (updated, overflow) = totals[proof.key, default: .zero]
                    .addingReportingOverflow(proof.amount)
                guard !overflow else { throw AppSettingsError.invalidValue }
                totals[proof.key] = updated
            }
        }
        return totals
    }

    static func canonicalEvidence(
        in record: StoredSettlement
    ) throws -> [CanonicalSettlementEvidence] {
        guard record.phase == .final || record.phase == .needsReview else { return [] }
        guard let chainID = UInt64(exactly: record.chainID),
              let minedBlockValue = record.minedBlock,
              let minedBlock = UInt64(exactly: minedBlockValue)
        else { throw AppSettingsError.invalidValue }
        let intent = try record.intent()
        let sessions = Dictionary(
            uniqueKeysWithValues: intent.sessions.map {
                ($0.invoiceID.hex.lowercased(), $0)
            }
        )
        var evidence = [CanonicalSettlementEvidence]()
        var identities = Set<String>()
        var provenSessions = Set<String>()
        var canonicalBlockHash: String?
        for proof in try record.eventProofs() {
            let invoiceID = proof.invoiceID.lowercased()
            guard proof.transactionHash.lowercased() == record.transactionHash.lowercased(),
                  (try? Bytes32(hex: proof.transactionHash)) != nil,
                  proof.token.lowercased() == record.tokenAddress.lowercased(),
                  let session = sessions[invoiceID],
                  proof.receiver.lowercased() == session.receiver.hex.lowercased(),
                  proof.expectedAmount == session.expectedAmount.decimalString,
                  let blockHash = proof.blockHash,
                  (try? Bytes32(hex: blockHash)) != nil,
                  let logIndex = proof.logIndex,
                  let canonicalLogIndex = UInt64(logIndex),
                  String(canonicalLogIndex) == logIndex,
                  let amount = try? UInt256(decimalString: proof.sweptAmount),
                  amount.decimalString == proof.sweptAmount,
                  !amount.isZero,
                  let fee = try? UInt256(decimalString: proof.fee),
                  fee.decimalString == proof.fee
            else { throw AppSettingsError.invalidValue }
            let identity = "\(proof.transactionHash.lowercased()):\(logIndex)"
            guard identities.insert(identity).inserted else {
                throw AppSettingsError.invalidValue
            }
            guard provenSessions.insert(invoiceID).inserted else {
                throw AppSettingsError.invalidValue
            }
            let normalizedBlockHash = blockHash.lowercased()
            guard canonicalBlockHash == nil || canonicalBlockHash == normalizedBlockHash else {
                throw AppSettingsError.invalidValue
            }
            canonicalBlockHash = normalizedBlockHash
            evidence.append(CanonicalSettlementEvidence(
                key: CumulativeSettlementKey(
                    chainID: chainID,
                    vault: record.vault.lowercased(),
                    invoiceID: invoiceID,
                    token: record.tokenAddress.lowercased()
                ),
                identity: identity,
                amount: amount,
                minedBlock: minedBlock
            ))
        }
        if record.phase == .final, evidence.count != intent.sessions.count {
            throw AppSettingsError.invalidValue
        }
        return evidence
    }

    /// Runs even when there are no network-reconcilable transactions. This repairs a persisted
    /// Needs review record after another already-final record completes its invoice cumulatively.
    static func applyCumulativeSettlementEvidence(
        _ records: [StoredSettlement]
    ) throws {
        let totals = try confirmedCumulativeTotals(records)
        for record in records where record.phase == .needsReview {
            guard let chainID = UInt64(exactly: record.chainID) else {
                throw AppSettingsError.invalidValue
            }
            let intent = try record.intent()
            // A needs-review receipt with a per-session proof subset stays visibly incomplete;
            // later transactions can settle its invoices without converting this receipt into
            // a falsely complete final batch. Full-proof partial sweeps may still heal
            // cumulatively when later canonical evidence covers the remaining amount.
            guard try canonicalEvidence(in: record).count == intent.sessions.count else {
                continue
            }
            let isCumulativelyComplete = intent.sessions.allSatisfy { session in
                let key = CumulativeSettlementKey(
                    chainID: chainID,
                    vault: record.vault.lowercased(),
                    invoiceID: session.invoiceID.hex.lowercased(),
                    token: record.tokenAddress.lowercased()
                )
                return totals[key, default: .zero] >= session.expectedAmount
            }
            if isCumulativelyComplete {
                record.phase = .final
                record.failureReason = nil
                record.updatedAt = Date()
            }
        }
    }
}

enum OperatorResetSafetyError: LocalizedError, Equatable {
    case nativeBalanceNotZero(latest: UInt256, pending: UInt256)

    var errorDescription: String? {
        switch self {
        case let .nativeBalanceNotZero(latest, pending):
            "Withdraw all operator gas before deleting this key. Latest balance: \(latest.decimalString) wei; pending balance: \(pending.decimalString) wei. The key is unchanged."
        }
    }
}

/// Acquired and released only from AppModel's main actor. Synchronous acquisition makes lifecycle
/// operations mutually exclusive before provision, sale, settlement, or reset work can suspend.
@MainActor
final class AppModelOperationGate {
    private(set) var isHeld = false

    func acquire() -> Bool {
        guard !isHeld else { return false }
        isHeld = true
        return true
    }

    func release() {
        precondition(isHeld)
        isHeld = false
    }
}

/// Owns foreground reconciliation independently from the lifecycle mutation gate. Its opaque
/// token prevents an overlapping invocation from clearing another invocation's in-flight state.
@MainActor
final class ForegroundInvoiceReconciliationGate {
    private var token: UUID?

    var isInFlight: Bool { token != nil }

    func acquire() -> UUID? {
        guard token == nil else { return nil }
        let acquired = UUID()
        token = acquired
        return acquired
    }

    func release(_ candidate: UUID) {
        guard token == candidate else { return }
        token = nil
    }
}

enum SettlementReconciliationPolicy {
    static let activeBatchLimit = 4
    static let evidenceBatchLimit = 4
    static let cumulativeReviewBatchLimit = 4
    static let evidenceInitialFailureDelay: TimeInterval = 30
    static let evidenceMaximumFailureDelay: TimeInterval = 60 * 60
    static let cumulativeReviewRotationDelay: TimeInterval = 10

    static func activeFetchDescriptor() -> FetchDescriptor<StoredSettlement> {
        var descriptor = FetchDescriptor<StoredSettlement>(
            predicate: #Predicate { record in
                record.phaseRaw == "pending"
                    || record.phaseRaw == "mined"
                    || record.phaseRaw == "unknown"
            },
            sortBy: [
                SortDescriptor(\.updatedAt, order: .forward),
                SortDescriptor(\.createdAt, order: .forward),
            ]
        )
        descriptor.fetchLimit = activeBatchLimit
        return descriptor
    }

    static func evidenceFetchDescriptor(
        now: Date = Date(),
        limit: Int = evidenceBatchLimit
    ) -> FetchDescriptor<StoredSettlement> {
        var descriptor = FetchDescriptor<StoredSettlement>(
            predicate: #Predicate { record in
                !record.cumulativeEvidenceIndexed
                    && record.cumulativeEvidenceNextAttemptAt <= now
                    && (record.phaseRaw == "final" || record.phaseRaw == "needsReview")
            },
            sortBy: [
                SortDescriptor(\.cumulativeEvidenceNextAttemptAt, order: .forward),
                SortDescriptor(\.cumulativeEvidenceLastAttemptAt, order: .forward),
                SortDescriptor(\.createdAt, order: .forward),
            ]
        )
        descriptor.fetchLimit = max(1, min(limit, evidenceBatchLimit))
        return descriptor
    }

    static func evidenceFailureDelay(failureCount: Int) -> TimeInterval {
        let exponent = max(0, min(failureCount - 1, 10))
        return min(
            evidenceInitialFailureDelay * pow(2, Double(exponent)),
            evidenceMaximumFailureDelay
        )
    }

    static func cumulativeReviewFetchDescriptor(
        now: Date = Date(),
        limit: Int = cumulativeReviewBatchLimit
    ) -> FetchDescriptor<StoredSettlement> {
        var descriptor = FetchDescriptor<StoredSettlement>(
            predicate: #Predicate { record in
                record.cumulativeEvidenceIndexed
                    && record.phaseRaw == "needsReview"
                    && record.cumulativeReviewNextAttemptAt <= now
            },
            sortBy: [
                SortDescriptor(\.cumulativeReviewNextAttemptAt, order: .forward),
                SortDescriptor(\.cumulativeReviewLastAttemptAt, order: .forward),
                SortDescriptor(\.createdAt, order: .forward),
            ]
        )
        descriptor.fetchLimit = max(1, min(limit, cumulativeReviewBatchLimit))
        return descriptor
    }
}
