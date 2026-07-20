import Foundation
import OPKTerminalCore

public enum PaymentMonitorError: Error, Equatable, Sendable {
    case canonicalBlockChanged(blockNumber: UInt64)
}

extension PaymentMonitorError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .canonicalBlockChanged(blockNumber):
            "Canonical block \(blockNumber) changed while sampling payment state. Retry the observation."
        }
    }
}

public struct PaymentMonitor: Sendable {
    private let rpc: any EthereumReadRPC
    public let confirmationPolicy: ConfirmationPolicy
    public let pollIntervalNanoseconds: UInt64

    public init(
        rpc: any EthereumReadRPC,
        confirmationPolicy: ConfirmationPolicy,
        pollIntervalNanoseconds: UInt64 = 2_000_000_000
    ) {
        self.rpc = rpc
        self.confirmationPolicy = confirmationPolicy
        self.pollIntervalNanoseconds = pollIntervalNanoseconds
    }

    public func sample(
        _ request: PaymentRequest,
        previousThresholdCursor: PaymentConfirmationCursor? = nil,
        additionalCursors: [PaymentConfirmationCursor] = [],
        now: Date = Date()
    ) async throws -> PaymentObservation {
        let block = try await rpc.blockNumber()
        let initialBlockHash = try await rpc.canonicalBlockHash(at: block)
        let balanceData = try await rpc.call(
            to: request.token.address,
            data: ABI.encodeCall(
                selector: ABI.balanceOfSelector,
                words: [ABI.word(request.receiver)]
            ),
            block: .number(block)
        )
        let balance = try ABI.decodeUInt256(balanceData)
        let sampledBlockHash = try await rpc.canonicalBlockHash(at: block)
        guard sampledBlockHash == initialBlockHash else {
            throw PaymentMonitorError.canonicalBlockChanged(blockNumber: block)
        }

        let cursors = [previousThresholdCursor].compactMap { $0 } + additionalCursors
        var validatedCursors = [PaymentConfirmationCursor]()
        var seenCursors = Set<PaymentConfirmationCursor>()
        for cursor in cursors where seenCursors.insert(cursor).inserted {
            guard cursor.blockNumber <= block else { continue }
            let canonicalHash = cursor.blockNumber == block
                ? sampledBlockHash
                : try await rpc.canonicalBlockHash(at: cursor.blockNumber)
            if canonicalHash == cursor.blockHash {
                validatedCursors.append(cursor)
            }
        }
        let finalBlockHash = try await rpc.canonicalBlockHash(at: block)
        guard finalBlockHash == sampledBlockHash else {
            throw PaymentMonitorError.canonicalBlockChanged(blockNumber: block)
        }
        return classify(
            request,
            balance: balance,
            block: block,
            blockHash: finalBlockHash,
            previousThresholdCursor: previousThresholdCursor,
            validatedCursors: validatedCursors,
            now: now
        )
    }

    public func classify(
        _ request: PaymentRequest,
        balance: UInt256,
        block: UInt64,
        blockHash: Bytes32,
        previousThresholdCursor: PaymentConfirmationCursor? = nil,
        validatedCursors: [PaymentConfirmationCursor] = [],
        now: Date = Date()
    ) -> PaymentObservation {
        let expected = request.expectedAmount
        if balance < expected {
            let status: PaymentStatus
            if let expiry = request.expiresAt, now >= expiry {
                status = .expired(lastObserved: balance)
            } else if balance.isZero {
                status = .waiting
            } else {
                status = .partial(received: balance)
            }
            return PaymentObservation(
                invoiceID: request.invoiceID,
                blockNumber: block,
                blockHash: blockHash,
                balance: balance,
                status: status,
                thresholdBlock: nil,
                thresholdBlockHash: nil,
                validatedPreviousCursors: validatedCursors
            )
        }

        let thresholdCursor: PaymentConfirmationCursor
        if let previousThresholdCursor,
           previousThresholdCursor.blockNumber <= block,
           validatedCursors.contains(previousThresholdCursor) {
            thresholdCursor = previousThresholdCursor
        } else {
            thresholdCursor = PaymentConfirmationCursor(
                blockNumber: block,
                blockHash: blockHash
            )
        }
        let confirmations = block - thresholdCursor.blockNumber + 1
        let status: PaymentStatus
        if confirmations < confirmationPolicy.requiredBlocks {
            status = .confirming(
                received: balance,
                confirmations: confirmations,
                required: confirmationPolicy.requiredBlocks
            )
        } else if balance == expected {
            status = .paid(received: balance)
        } else {
            let excess = balance.subtractingReportingOverflow(expected).partialValue
            status = .overpaid(received: balance, excess: excess)
        }
        return PaymentObservation(
            invoiceID: request.invoiceID,
            blockNumber: block,
            blockHash: blockHash,
            balance: balance,
            status: status,
            thresholdBlock: thresholdCursor.blockNumber,
            thresholdBlockHash: thresholdCursor.blockHash,
            validatedPreviousCursors: validatedCursors
        )
    }

    public func observations(
        for request: PaymentRequest,
        startingThresholdCursor: PaymentConfirmationCursor? = nil
    ) -> AsyncThrowingStream<PaymentObservation, Error> {
        let monitor = self
        return AsyncThrowingStream { continuation in
            let task = Task {
                var thresholdCursor = startingThresholdCursor
                do {
                    while !Task.isCancelled {
                        let observation = try await monitor.sample(
                            request,
                            previousThresholdCursor: thresholdCursor
                        )
                        thresholdCursor = observation.thresholdCursor
                        continuation.yield(observation)
                        switch observation.status {
                        case .paid, .overpaid, .expired:
                            continuation.finish()
                            return
                        default:
                            break
                        }
                        try await Task.sleep(nanoseconds: monitor.pollIntervalNanoseconds)
                    }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }
}
