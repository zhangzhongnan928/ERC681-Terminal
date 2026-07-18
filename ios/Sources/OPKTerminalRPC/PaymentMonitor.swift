import Foundation
import OPKTerminalCore

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
        previousThresholdBlock: UInt64? = nil,
        now: Date = Date()
    ) async throws -> PaymentObservation {
        let block = try await rpc.blockNumber()
        let balanceData = try await rpc.call(
            to: request.token.address,
            data: ABI.encodeCall(
                selector: ABI.balanceOfSelector,
                words: [ABI.word(request.receiver)]
            ),
            block: .number(block)
        )
        let balance = try ABI.decodeUInt256(balanceData)
        return classify(
            request,
            balance: balance,
            block: block,
            previousThresholdBlock: previousThresholdBlock,
            now: now
        )
    }

    public func classify(
        _ request: PaymentRequest,
        balance: UInt256,
        block: UInt64,
        previousThresholdBlock: UInt64? = nil,
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
                balance: balance,
                status: status,
                thresholdBlock: nil
            )
        }

        let threshold: UInt64
        if let previousThresholdBlock, previousThresholdBlock <= block {
            threshold = previousThresholdBlock
        } else {
            threshold = block
        }
        let confirmations = block - threshold + 1
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
            balance: balance,
            status: status,
            thresholdBlock: threshold
        )
    }

    public func observations(
        for request: PaymentRequest,
        startingThresholdBlock: UInt64? = nil
    ) -> AsyncThrowingStream<PaymentObservation, Error> {
        let monitor = self
        return AsyncThrowingStream { continuation in
            let task = Task {
                var threshold = startingThresholdBlock
                do {
                    while !Task.isCancelled {
                        let observation = try await monitor.sample(
                            request,
                            previousThresholdBlock: threshold
                        )
                        threshold = observation.thresholdBlock
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
