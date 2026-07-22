import Foundation
import OPKTerminalCore
import SwiftData

@Model
final class StoredInvoice {
    @Attribute(.unique) var invoiceID: String
    var terminalIdentifier: String
    var rpcURL: String = ""
    var chainID: Int64
    var protocolVersion: String = OPKProtocolVersion.v1_4_1.rawValue
    var factory: String = ""
    var receiverImplementation: String = ""
    var vault: String
    var receiver: String
    var tokenAddress: String
    var tokenSymbol: String
    var tokenDecimals: Int
    var expectedAmount: String
    var erc681URI: String
    var createdAt: Date
    var expiresAt: Date?
    var statusLabel: String
    var observedBalance: String
    var observedBlock: Int64?
    var observedBlockHash: String?
    var thresholdBlock: Int64?
    var thresholdBlockHash: String?
    var confirmationBlocks: Int64 = 1
    var locallyClosed: Bool = false
    /// Confirmed cumulative sweep evidence whose block was no later than `observedBlock`.
    /// Pairing this baseline with `observedBalance` lets reset safety distinguish an old,
    /// already-swept payment from a new balance received after the original settlement.
    var cumulativeSweptAtObservation: String = "0"
    /// Current canonical cumulative sweep total materialized from the bounded proof index.
    /// `cumulativeSweptAtObservation` remains the exact baseline paired with observedBalance.
    var confirmedCumulativeSweptAmount: String = "0"
    var confirmedCumulativeSweptThroughBlock: Int64?
    var lastReconciliationAttemptAt: Date?
    var nextReconciliationAt: Date = Date.distantPast
    var reconciliationFailureCount: Int = 0
    var lastReconciliationError: String?
    /// Confirmation cursor for the exact live balance and cumulative-proof baseline that can
    /// currently be swept. This is separate from `thresholdBlock`, whose legacy payment-status
    /// classification is always relative to the invoice's original expected amount.
    var sweepableThresholdBlock: Int64?
    var sweepableThresholdBlockHash: String?
    var sweepableCandidateBalance: String?
    var sweepableCandidateCumulative: String?

    init(request: PaymentRequest, configuration: TerminalConfiguration) throws {
        guard let storedChainID = Int64(exactly: request.chainID),
              let storedConfirmationBlocks = Int64(exactly: configuration.confirmationPolicy.requiredBlocks)
        else { throw AppSettingsError.invalidValue }
        invoiceID = request.invoiceID.hex
        terminalIdentifier = request.terminalIdentifier.address.hex
        rpcURL = configuration.rpcEndpoints[0].absoluteString
        chainID = storedChainID
        protocolVersion = configuration.protocolVersion.rawValue
        factory = configuration.deployment.factory.hex
        receiverImplementation = configuration.deployment.receiverImplementation.hex
        vault = request.vault.hex
        receiver = request.receiver.hex
        tokenAddress = request.token.address.hex
        tokenSymbol = request.token.symbol
        tokenDecimals = Int(request.token.decimals)
        expectedAmount = request.expectedAmount.decimalString
        erc681URI = request.erc681URI
        createdAt = request.createdAt
        expiresAt = request.expiresAt
        statusLabel = "Waiting"
        observedBalance = "0"
        confirmationBlocks = storedConfirmationBlocks
    }

    func configurationSnapshot() throws -> TerminalConfiguration {
        guard let endpoint = URL(string: rpcURL),
              let version = OPKProtocolVersion(rawValue: protocolVersion),
              version == .v1_4_1,
              let storedChainID = UInt64(exactly: chainID), storedChainID > 0,
              let decimals = UInt8(exactly: tokenDecimals),
              let blocks = UInt64(exactly: confirmationBlocks), blocks > 0
        else { throw AppSettingsError.invalidValue }
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        return try TerminalConfiguration(
            chainID: storedChainID,
            rpcEndpoints: [endpoint],
            protocolVersion: version,
            deployment: OPKDeployment(
                factory: EthereumAddress(hex: factory, allowZero: false),
                receiverImplementation: EthereumAddress(hex: receiverImplementation, allowZero: false),
                vault: EthereumAddress(hex: vault, allowZero: false)
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: blocks)
        )
    }

    func paymentRequest() throws -> PaymentRequest {
        guard let storedChainID = UInt64(exactly: chainID), storedChainID > 0,
              let decimals = UInt8(exactly: tokenDecimals)
        else { throw AppSettingsError.invalidValue }
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        let request = PaymentRequest(
            invoiceID: try Bytes32(hex: invoiceID),
            terminalIdentifier: TerminalIdentifier(
                address: try EthereumAddress(hex: terminalIdentifier, allowZero: false)
            ),
            chainID: storedChainID,
            vault: try EthereumAddress(hex: vault, allowZero: false),
            receiver: try EthereumAddress(hex: receiver, allowZero: false),
            token: token,
            expectedAmount: try UInt256(decimalString: expectedAmount),
            erc681URI: erc681URI,
            createdAt: createdAt,
            expiresAt: expiresAt
        )
        let parsedURI = try ERC681TransferRequest.parse(erc681URI, expectedChainID: storedChainID)
        guard parsedURI.token == request.token.address,
              parsedURI.recipient == request.receiver,
              parsedURI.amount == request.expectedAmount,
              parsedURI.canonicalString == erc681URI
        else { throw AppSettingsError.invalidValue }
        return request
    }

    func apply(
        _ observation: PaymentObservation,
        cumulativeConfirmedSweptAmount: UInt256 = .zero
    ) throws {
        guard let storedBlock = Int64(exactly: observation.blockNumber) else {
            throw AppSettingsError.invalidValue
        }
        let storedThreshold: Int64?
        let storedThresholdHash: String?
        switch (observation.thresholdBlock, observation.thresholdBlockHash) {
        case let (threshold?, thresholdHash?):
            guard let value = Int64(exactly: threshold) else {
                throw AppSettingsError.invalidValue
            }
            storedThreshold = value
            storedThresholdHash = thresholdHash.hex
        case (nil, nil):
            storedThreshold = nil
            storedThresholdHash = nil
        default:
            throw AppSettingsError.invalidValue
        }
        try updateSweepableConfirmation(
            observation: observation,
            cumulativeConfirmedSweptAmount: cumulativeConfirmedSweptAmount,
            storedBlock: storedBlock
        )
        observedBalance = observation.balance.decimalString
        observedBlock = storedBlock
        observedBlockHash = observation.blockHash.hex
        thresholdBlock = storedThreshold
        thresholdBlockHash = storedThresholdHash
        cumulativeSweptAtObservation = cumulativeConfirmedSweptAmount.decimalString
        switch observation.status {
        case .waiting:
            statusLabel = "Waiting"
        case .partial:
            statusLabel = "Partially funded"
        case let .confirming(_, confirmations, required):
            statusLabel = "Confirming \(confirmations)/\(required)"
        case .paid:
            statusLabel = "Paid"
        case .overpaid:
            statusLabel = "Overpaid"
        case .expired:
            statusLabel = "Expired"
        }
        refreshStatusLabelFromLifecycleEvidence()
    }

    /// Receiver balance is only the currently sweepable amount. Once canonical settlement
    /// evidence exists, later receiver activity must not reopen the original full-amount QR.
    /// Confirmed late value remains recoverable through Settlement, and a locally closed QR
    /// stays closed while its receiver is empty.
    var historyStatusLabel: String {
        guard let balance = try? UInt256(decimalString: observedBalance),
              let cumulative = try? UInt256(decimalString: confirmedCumulativeSweptAmount),
              (try? UInt256(decimalString: cumulativeSweptAtObservation)) != nil,
              let expected = try? UInt256(decimalString: expectedAmount),
              !expected.isZero
        else { return statusLabel }

        // Any canonical sweep closes the original charge lifecycle. A later transfer to the
        // deterministic receiver remains observable and sweepable in Settlement, but it must
        // never make the original full-amount payment QR payable again.
        if cumulative >= expected {
            return "Settled"
        }
        if !cumulative.isZero {
            return "Partially settled"
        }
        if balance.isZero {
            if locallyClosed {
                return statusLabel == "Expired" ? "Expired" : "Closed"
            }
        }
        return statusLabel
    }

    func refreshStatusLabelFromLifecycleEvidence() {
        statusLabel = historyStatusLabel
    }

    func closeLocally() {
        locallyClosed = true
        let lifecycleStatus = historyStatusLabel
        switch lifecycleStatus {
        case "Paid", "Overpaid", "Expired", "Settled", "Partially settled":
            statusLabel = lifecycleStatus
        default:
            statusLabel = "Closed"
        }
    }

    var formattedAmount: String {
        guard let raw = try? UInt256(decimalString: expectedAmount),
              let decimals = UInt8(exactly: tokenDecimals)
        else { return expectedAmount }
        return "\(TokenAmount(rawValue: raw, decimals: decimals).displayString()) \(tokenSymbol)"
    }

    var shouldPresentQRCode: Bool {
        guard !locallyClosed else { return false }
        return historyStatusLabel == "Waiting"
            || historyStatusLabel == "Partially funded"
            || historyStatusLabel.hasPrefix("Confirming ")
    }

    var hasObservedFunds: Bool {
        guard let amount = try? UInt256(decimalString: observedBalance) else { return false }
        return !amount.isZero
    }

    var hasConfirmedSweepableFunds: Bool {
        guard let cumulative = try? UInt256(decimalString: cumulativeSweptAtObservation) else {
            return false
        }
        return hasConfirmedSweepableFunds(confirmedCumulative: cumulative)
    }

    func hasConfirmedSweepableFunds(confirmedCumulative: UInt256) -> Bool {
        guard let storedBaseline = try? UInt256(decimalString: cumulativeSweptAtObservation),
              confirmedCumulative == storedBaseline,
              sweepableCandidateCumulative == storedBaseline.decimalString,
              sweepableCandidateBalance == observedBalance,
              let balance = try? UInt256(decimalString: observedBalance),
              let requiredBalance = try? requiredSweepableBalance(
                cumulativeConfirmedSweptAmount: confirmedCumulative
              ),
              !balance.isZero,
              balance >= requiredBalance,
              let thresholdValue = sweepableThresholdBlock,
              let thresholdHash = sweepableThresholdBlockHash,
              (try? Bytes32(hex: thresholdHash)) != nil,
              let observationValue = observedBlock,
              let observationHash = observedBlockHash,
              (try? Bytes32(hex: observationHash)) != nil,
              let threshold = UInt64(exactly: thresholdValue),
              let observation = UInt64(exactly: observationValue),
              let requiredConfirmations = UInt64(exactly: confirmationBlocks),
              requiredConfirmations > 0,
              threshold <= observation
        else { return false }
        return observation - threshold + 1 >= requiredConfirmations
    }

    func confirmedSweepableSnapshot(
        confirmedCumulative: UInt256
    ) -> SweepableConfirmationSnapshot? {
        guard hasConfirmedSweepableFunds(confirmedCumulative: confirmedCumulative) else {
            return nil
        }
        return SweepableConfirmationSnapshot(
            invoiceID: invoiceID,
            observedBalance: observedBalance,
            observedBlock: observedBlock,
            observedBlockHash: observedBlockHash,
            cumulativeSweptAtObservation: cumulativeSweptAtObservation,
            sweepableThresholdBlock: sweepableThresholdBlock,
            sweepableThresholdBlockHash: sweepableThresholdBlockHash,
            sweepableCandidateBalance: sweepableCandidateBalance,
            sweepableCandidateCumulative: sweepableCandidateCumulative,
            confirmationBlocks: confirmationBlocks
        )
    }

    func cumulativeSettlementKey() throws -> CumulativeSettlementKey {
        guard let canonicalChainID = UInt64(exactly: chainID) else {
            throw AppSettingsError.invalidValue
        }
        return CumulativeSettlementKey(
            chainID: canonicalChainID,
            vault: try EthereumAddress(hex: vault, allowZero: false).hex,
            invoiceID: try Bytes32(hex: invoiceID).hex,
            token: try EthereumAddress(hex: tokenAddress, allowZero: false).hex
        )
    }

    var paymentThresholdCursor: PaymentConfirmationCursor? {
        confirmationCursor(block: thresholdBlock, hash: thresholdBlockHash)
    }

    var sweepableConfirmationCursor: PaymentConfirmationCursor? {
        confirmationCursor(
            block: sweepableThresholdBlock,
            hash: sweepableThresholdBlockHash
        )
    }

    func beginForegroundReconciliation(at date: Date) {
        lastReconciliationAttemptAt = date
        // Reserve this invoice while its RPC work is in flight. The result replaces this
        // conservative deadline with the normal success cadence or exponential backoff.
        nextReconciliationAt = date.addingTimeInterval(
            ForegroundInvoiceReconciliationPolicy.inFlightLeaseDelay
        )
    }

    func recordForegroundReconciliationSuccess(
        _ observation: PaymentObservation,
        cumulativeConfirmedSweptAmount: UInt256,
        at date: Date
    ) throws {
        let preservesNewerObservation: Bool
        if let currentBlock = observedBlock,
           let canonicalCurrentBlock = UInt64(exactly: currentBlock),
           canonicalCurrentBlock > observation.blockNumber {
            // An active monitor or newer foreground run already persisted fresher chain state.
            // Keep that observation while still treating this RPC attempt as healthy.
            preservesNewerObservation = true
        } else {
            preservesNewerObservation = false
            try apply(
                observation,
                cumulativeConfirmedSweptAmount: cumulativeConfirmedSweptAmount
            )
        }
        reconciliationFailureCount = 0
        lastReconciliationError = nil
        nextReconciliationAt = date.addingTimeInterval(
            ForegroundInvoiceReconciliationPolicy.successDelay(
                hasObservedFunds: preservesNewerObservation
                    ? hasObservedFunds
                    : !observation.balance.isZero,
                isHistorical: locallyClosed || (expiresAt.map { $0 <= date } ?? false)
            )
        )
    }

    func recordForegroundReconciliationFailure(_ message: String, at date: Date) {
        reconciliationFailureCount = min(reconciliationFailureCount + 1, 16)
        lastReconciliationError = String(message.prefix(512))
        nextReconciliationAt = date.addingTimeInterval(
            ForegroundInvoiceReconciliationPolicy.failureDelay(
                failureCount: reconciliationFailureCount
            )
        )
    }

    func cancelForegroundReconciliation(at date: Date) {
        // Cancellation is lifecycle control, not an RPC-health signal. Preserve any prior
        // diagnostics/backoff count while releasing this reservation for the next bounded pass.
        nextReconciliationAt = date
    }

    private func requiredSweepableBalance(
        cumulativeConfirmedSweptAmount: UInt256
    ) throws -> UInt256 {
        let expected = try UInt256(decimalString: expectedAmount)
        guard cumulativeConfirmedSweptAmount < expected else { return UInt256(1) }
        let (remaining, underflow) = expected.subtractingReportingOverflow(
            cumulativeConfirmedSweptAmount
        )
        guard !underflow, !remaining.isZero else { throw AppSettingsError.invalidValue }
        return remaining
    }

    private func updateSweepableConfirmation(
        observation: PaymentObservation,
        cumulativeConfirmedSweptAmount: UInt256,
        storedBlock: Int64
    ) throws {
        let requiredBalance = try requiredSweepableBalance(
            cumulativeConfirmedSweptAmount: cumulativeConfirmedSweptAmount
        )
        let balanceValue = observation.balance.decimalString
        let cumulativeValue = cumulativeConfirmedSweptAmount.decimalString
        guard !observation.balance.isZero, observation.balance >= requiredBalance else {
            sweepableThresholdBlock = nil
            sweepableThresholdBlockHash = nil
            sweepableCandidateBalance = nil
            sweepableCandidateCumulative = nil
            return
        }

        let canPreserveThreshold = sweepableCandidateBalance == balanceValue
            && sweepableCandidateCumulative == cumulativeValue
            && sweepableThresholdBlock.map { $0 <= storedBlock } == true
            && sweepableConfirmationCursor.map(observation.validated) == true
        if !canPreserveThreshold {
            sweepableThresholdBlock = storedBlock
            sweepableThresholdBlockHash = observation.blockHash.hex
        }
        sweepableCandidateBalance = balanceValue
        sweepableCandidateCumulative = cumulativeValue
    }

    private func confirmationCursor(
        block: Int64?,
        hash: String?
    ) -> PaymentConfirmationCursor? {
        guard let block,
              let blockNumber = UInt64(exactly: block),
              let hash,
              let blockHash = try? Bytes32(hex: hash)
        else { return nil }
        return PaymentConfirmationCursor(
            blockNumber: blockNumber,
            blockHash: blockHash
        )
    }
}

struct SweepableConfirmationSnapshot: Equatable, Sendable {
    let invoiceID: String
    let observedBalance: String
    let observedBlock: Int64?
    let observedBlockHash: String?
    let cumulativeSweptAtObservation: String
    let sweepableThresholdBlock: Int64?
    let sweepableThresholdBlockHash: String?
    let sweepableCandidateBalance: String?
    let sweepableCandidateCumulative: String?
    let confirmationBlocks: Int64

    var confirmationCursor: PaymentConfirmationCursor? {
        guard let threshold = sweepableThresholdBlock,
              let blockNumber = UInt64(exactly: threshold),
              let hash = sweepableThresholdBlockHash,
              let blockHash = try? Bytes32(hex: hash)
        else { return nil }
        return PaymentConfirmationCursor(
            blockNumber: blockNumber,
            blockHash: blockHash
        )
    }

    /// A fresh sample must see the exact selected balance and independently revalidate the saved
    /// threshold block hash. Equal balances on a replacement fork are deliberately insufficient.
    func isRevalidated(by observation: PaymentObservation) -> Bool {
        guard observation.invoiceID.hex == invoiceID,
              observation.balance.decimalString == observedBalance,
              let cursor = confirmationCursor,
              observation.validated(cursor),
              cursor.blockNumber <= observation.blockNumber,
              let required = UInt64(exactly: confirmationBlocks),
              required > 0,
              observation.blockNumber - cursor.blockNumber + 1 >= required
        else { return false }
        return true
    }
}

enum ForegroundInvoiceReconciliationPolicy {
    static let batchLimit = 4
    static let activeSuccessDelay: TimeInterval = 30
    static let fundedSuccessDelay: TimeInterval = 30
    static let historicalSuccessDelay: TimeInterval = 15 * 60
    static let initialFailureDelay: TimeInterval = 30
    static let maximumFailureDelay: TimeInterval = 60 * 60
    static let inFlightLeaseDelay: TimeInterval = 90

    /// Oldest-attempted-first is a persistent round-robin cursor. A failing first invoice
    /// receives backoff and therefore cannot starve never-attempted or healthy invoices.
    static func fetchDescriptor(
        now: Date,
        limit: Int = batchLimit
    ) -> FetchDescriptor<StoredInvoice> {
        var descriptor = FetchDescriptor<StoredInvoice>(
            predicate: #Predicate { invoice in
                invoice.nextReconciliationAt <= now
            },
            sortBy: [
                SortDescriptor(\.lastReconciliationAttemptAt, order: .forward),
                SortDescriptor(\.createdAt, order: .forward),
                SortDescriptor(\.invoiceID, order: .forward),
            ]
        )
        descriptor.fetchLimit = max(1, min(limit, batchLimit))
        return descriptor
    }

    static func successDelay(hasObservedFunds: Bool, isHistorical: Bool) -> TimeInterval {
        if hasObservedFunds { return fundedSuccessDelay }
        return isHistorical ? historicalSuccessDelay : activeSuccessDelay
    }

    static func failureDelay(failureCount: Int) -> TimeInterval {
        let exponent = max(0, min(failureCount - 1, 10))
        return min(initialFailureDelay * pow(2, Double(exponent)), maximumFailureDelay)
    }
}
