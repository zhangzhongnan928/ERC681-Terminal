import Foundation
import OPKTerminalCore

struct AutoSweepCandidate: Equatable {
    let invoiceID: String
    let fingerprint: String
}

/// MainActor-owned single-flight state for foreground auto-sweep preparation. Same-generation
/// triggers are coalesced. Only a newer enable generation may request one follow-up after stale
/// in-flight work exits, so lifecycle, monitor, and settings triggers cannot create an RPC burst.
struct AutoSweepAttemptGate: Equatable {
    struct Token: Equatable {
        fileprivate let generation: UInt64
    }

    private(set) var generation: UInt64 = 0
    private(set) var runningToken: Token?
    private var pendingGeneration: UInt64?

    mutating func invalidate() {
        generation &+= 1
    }

    mutating func acquire(enabled: Bool) -> Token? {
        guard enabled else { return nil }
        if let runningToken {
            if runningToken.generation != generation {
                pendingGeneration = generation
            }
            return nil
        }
        let token = Token(generation: generation)
        runningToken = token
        return token
    }

    func isCurrent(_ token: Token, enabled: Bool) -> Bool {
        enabled && runningToken == token && token.generation == generation
    }

    /// Returns true only when a newer enable generation arrived during this run.
    mutating func release(_ token: Token, enabled: Bool) -> Bool {
        guard runningToken == token else { return false }
        runningToken = nil
        defer { pendingGeneration = nil }
        return enabled
            && pendingGeneration == generation
            && token.generation != generation
    }
}

enum AutoSweepPolicy {
    static let retryDelay: TimeInterval = 60

    static func selectCandidate(
        from invoices: [StoredInvoice],
        excludingActiveInvoiceIDs: Set<String>,
        suppressedFingerprints: Set<String>,
        retryAfter: [String: Date],
        now: Date
    ) -> AutoSweepCandidate? {
        invoices
            .sorted {
                if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
                return $0.invoiceID < $1.invoiceID
            }
            .lazy
            .filter { !excludingActiveInvoiceIDs.contains($0.invoiceID) }
            .compactMap { invoice -> AutoSweepCandidate? in
                guard let fingerprint = invoice.autoSweepFingerprint,
                      !suppressedFingerprints.contains(fingerprint),
                      retryAfter[fingerprint].map({ $0 <= now }) ?? true
                else { return nil }
                return AutoSweepCandidate(
                    invoiceID: invoice.invoiceID,
                    fingerprint: fingerprint
                )
            }
            .first
    }
}

extension StoredInvoice {
    /// Only the original, newly confirmed payment is eligible. A later payment after any sweep is
    /// intentionally left on the existing manual Settlement path.
    var autoSweepFingerprint: String? {
        guard receiptEligible,
              receiptNumber > 0,
              hasIncomingPaymentEvidence,
              statusLabel == "Paid" || statusLabel == "Overpaid",
              cumulativeSweptAtObservation == "0",
              confirmedCumulativeSweptAmount == "0",
              let publicationCursor,
              let fundingCursor = paymentEvidenceFundingCursor,
              paymentThresholdCursor == fundingCursor,
              fundingCursor.blockNumber > publicationCursor.blockNumber,
              let paymentTransactionHash,
              let paymentBlockNumber,
              let paymentBlockHash,
              let paidAtEpochSeconds,
              paidAtEpochSeconds > 0,
              let observed = try? UInt256(decimalString: observedBalance),
              let expected = try? UInt256(decimalString: expectedAmount),
              observed >= expected,
              let confirmedCumulative = try? UInt256(
                  decimalString: confirmedCumulativeSweptAmount
              ),
              hasConfirmedSweepableFunds(confirmedCumulative: confirmedCumulative)
        else { return nil }
        return [
            invoiceID.lowercased(),
            statusLabel,
            String(fundingCursor.blockNumber),
            fundingCursor.blockHash.hex.lowercased(),
            observed.decimalString,
            paymentTransactionHash.lowercased(),
            String(paymentBlockNumber),
            paymentBlockHash.lowercased(),
        ].joined(separator: "|")
    }
}
