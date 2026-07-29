// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

public enum PaymentMonitorError: Error, Equatable, Sendable {
    case canonicalBlockChanged(blockNumber: UInt64)
    case wrongChain(expected: UInt64, actual: UInt64)
    case mixedRequestChains
    case requestChainMismatch(expected: UInt64, request: UInt64)
}

/// Classifies only failures for which repeating a read-only payment observation can recover
/// without changing any local configuration or trust decision. Strict proof/decoding failures,
/// cancellation, authentication failures, and network mismatches intentionally remain terminal.
public enum PaymentMonitorRetryPolicy {
    public static func shouldRetry(_ error: any Error) -> Bool {
        if error is CancellationError {
            return false
        }
        if error is RPCRequestDeadlineError {
            return true
        }
        if let monitorError = error as? PaymentMonitorError {
            guard case .canonicalBlockChanged = monitorError else { return false }
            return true
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut,
                 .cannotFindHost,
                 .cannotConnectToHost,
                 .networkConnectionLost,
                 .dnsLookupFailed,
                 .notConnectedToInternet,
                 .resourceUnavailable,
                 .internationalRoamingOff,
                 .callIsActive,
                 .dataNotAllowed:
                return true
            case .cancelled:
                return false
            default:
                return false
            }
        }
        if let rpcError = error as? JSONRPCError {
            switch rpcError {
            case let .invalidHTTPStatus(status):
                return status == 408
                    || status == 425
                    || status == 429
                    || (500...599).contains(status)
            case let .server(serverError):
                // -32005 is the widely used Ethereum provider "limit exceeded" error;
                // -32016 is used by some public providers for an over-rate-limit response.
                return serverError.code == -32_005 || serverError.code == -32_016
            case .remoteResponseDecodeFailure:
                // Invalid/truncated JSON can be a transient gateway body. It never becomes
                // payment evidence; a syntactically valid but semantically malformed proof is
                // classified separately and remains terminal.
                return true
            case .malformedResponse, .mismatchedID, .batchLimitExceeded:
                return false
            }
        }
        // RPCDecodingError remains terminal: an invalid quantity, address/data word, or proof
        // shape is not availability evidence and can indicate an incompatible endpoint or local
        // programming error. Syntactically valid response-body decoding failures are normalized
        // to terminal malformedResponse at the production JSON-RPC boundary.
        return false
    }
}

/// One immutable invoice read within a shared payment-state sample. Settlement validation uses
/// this form so every selected invoice is bound to the same chain/head anchor and canonical
/// block identity instead of sampling a moving head once per invoice.
public struct PaymentSampleInput: Hashable, Sendable {
    public let request: PaymentRequest
    public let previousThresholdCursor: PaymentConfirmationCursor?
    public let additionalCursors: [PaymentConfirmationCursor]

    public init(
        request: PaymentRequest,
        previousThresholdCursor: PaymentConfirmationCursor? = nil,
        additionalCursors: [PaymentConfirmationCursor] = []
    ) {
        self.request = request
        self.previousThresholdCursor = previousThresholdCursor
        self.additionalCursors = additionalCursors
    }
}

extension PaymentMonitorError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .canonicalBlockChanged(blockNumber):
            "Canonical block \(blockNumber) changed while sampling payment state. Retry the observation."
        case let .wrongChain(expected, actual):
            "Wrong network: expected chain \(expected), received \(actual)."
        case .mixedRequestChains:
            "A payment sample cannot combine invoices from different networks."
        case let .requestChainMismatch(expected, request):
            "The requested network \(expected) does not match invoice chain \(request)."
        }
    }
}

public struct ReceiverFreshnessProof: Hashable, Sendable {
    public let blockNumber: UInt64
    public let blockHash: Bytes32
    public let receiverCode: Data
    public let tokenBalance: UInt256
    /// Live vault whitelist state for the invoice token, proven at the same fixed head as the
    /// receiver reads. Mutable authorization facts must never be served from a cached
    /// configuration proof at QR-publication time.
    public let tokenWhitelisted: Bool
    /// Live vault authorization for the terminal operator (isOperator, or vault owner), proven
    /// at the same fixed head and bracketed by the same canonical-identity check as every other
    /// read in this proof. A reorg during sampling fails the whole proof closed instead of
    /// leaving authorization anchored to a different block than the receiver facts.
    public let operatorAuthorized: Bool

    public init(
        blockNumber: UInt64,
        blockHash: Bytes32,
        receiverCode: Data,
        tokenBalance: UInt256,
        tokenWhitelisted: Bool,
        operatorAuthorized: Bool
    ) {
        self.blockNumber = blockNumber
        self.blockHash = blockHash
        self.receiverCode = receiverCode
        self.tokenBalance = tokenBalance
        self.tokenWhitelisted = tokenWhitelisted
        self.operatorAuthorized = operatorAuthorized
    }

    /// Canonical `owner()` words are 32 bytes with 12 leading zero bytes. Anything else is
    /// treated as not-matching rather than a proof failure, because `isOperator` alone can
    /// still authorize publication.
    static func ownerMatches(_ ownerData: Data, operatorAddress: EthereumAddress) -> Bool {
        guard ownerData.count == 32,
              ownerData.prefix(12).allSatisfy({ $0 == 0 }),
              let owner = try? EthereumAddress(data: ownerData.suffix(20))
        else { return false }
        return owner == operatorAddress
    }
}

/// Proves a newly derived receiver is unused at one fixed canonical block. This is intentionally
/// separate from configuration validation: callers can run it in parallel, but must require both
/// proofs before publishing an invoice.
public struct ReceiverFreshnessValidator: Sendable {
    private let rpc: any EthereumReadRPC

    public init(rpc: any EthereumReadRPC) {
        self.rpc = rpc
    }

    public func validate(
        receiver: EthereumAddress,
        token: EthereumAddress,
        vault: EthereumAddress,
        operatorAddress: EthereumAddress,
        expectedChainID: UInt64
    ) async throws -> ReceiverFreshnessProof {
        let whitelistCallData = ABI.encodeCall(
            selector: ABI.isPaymentTokenSelector,
            words: [ABI.word(token)]
        )
        let isOperatorCallData = ABI.encodeCall(
            selector: ABI.isOperatorSelector,
            words: [ABI.word(operatorAddress)]
        )
        let ownerCallData = ABI.encodeCall(selector: ABI.ownerSelector)
        if let batchRPC = rpc as? any EthereumBatchReadRPC {
            let anchor = try await batchRPC.batch([.chainID, .latestBlockIdentity])
            guard anchor.count == 2,
                  case let .quantity(actualChainID) = anchor[0],
                  case let .blockIdentity(head, initialBlockHash) = anchor[1]
            else { throw RPCDecodingError.invalidData("receiver freshness anchor") }
            guard actualChainID == expectedChainID else {
                throw PaymentMonitorError.wrongChain(
                    expected: expectedChainID,
                    actual: actualChainID
                )
            }
            let block = RPCBlockTag.number(head)
            let proof = try await batchRPC.batch([
                .code(address: receiver, block: block),
                .call(
                    address: token,
                    data: ABI.encodeCall(
                        selector: ABI.balanceOfSelector,
                        words: [ABI.word(receiver)]
                    ),
                    block: block
                ),
                .call(address: vault, data: whitelistCallData, block: block),
                .call(address: vault, data: isOperatorCallData, block: block),
                .call(address: vault, data: ownerCallData, block: block),
            ])
            guard proof.count == 5,
                  case let .data(receiverCode) = proof[0],
                  case let .data(balanceData) = proof[1],
                  case let .data(whitelistData) = proof[2],
                  case let .data(isOperatorData) = proof[3],
                  case let .data(ownerData) = proof[4]
            else { throw RPCDecodingError.invalidData("receiver freshness proof") }
            let final = try await batchRPC.batch([.canonicalBlockHash(head)])
            guard final.count == 1, case let .blockHash(finalBlockHash) = final[0] else {
                throw RPCDecodingError.invalidData("receiver freshness final head")
            }
            guard finalBlockHash == initialBlockHash else {
                throw PaymentMonitorError.canonicalBlockChanged(blockNumber: head)
            }
            return ReceiverFreshnessProof(
                blockNumber: head,
                blockHash: finalBlockHash,
                receiverCode: receiverCode,
                tokenBalance: try ABI.decodeUInt256(balanceData),
                tokenWhitelisted: try ABI.decodeBool(whitelistData),
                operatorAuthorized: (try ABI.decodeBool(isOperatorData))
                    || ReceiverFreshnessProof.ownerMatches(
                        ownerData,
                        operatorAddress: operatorAddress
                    )
            )
        }

        async let chainID = rpc.chainID()
        async let head = rpc.blockNumber()
        let (actualChainID, resolvedHead) = try await (chainID, head)
        guard actualChainID == expectedChainID else {
            throw PaymentMonitorError.wrongChain(
                expected: expectedChainID,
                actual: actualChainID
            )
        }
        let block = RPCBlockTag.number(resolvedHead)
        let initialBlockHash = try await rpc.canonicalBlockHash(at: resolvedHead)
        async let receiverCode = rpc.code(at: receiver, block: block)
        async let balanceData = rpc.call(
            to: token,
            data: ABI.encodeCall(
                selector: ABI.balanceOfSelector,
                words: [ABI.word(receiver)]
            ),
            block: block
        )
        async let whitelistData = rpc.call(to: vault, data: whitelistCallData, block: block)
        async let isOperatorData = rpc.call(to: vault, data: isOperatorCallData, block: block)
        async let ownerData = rpc.call(to: vault, data: ownerCallData, block: block)
        let reads = try await (receiverCode, balanceData, whitelistData, isOperatorData, ownerData)
        let finalBlockHash = try await rpc.canonicalBlockHash(at: resolvedHead)
        guard finalBlockHash == initialBlockHash else {
            throw PaymentMonitorError.canonicalBlockChanged(blockNumber: resolvedHead)
        }
        return ReceiverFreshnessProof(
            blockNumber: resolvedHead,
            blockHash: finalBlockHash,
            receiverCode: reads.0,
            tokenBalance: try ABI.decodeUInt256(reads.1),
            tokenWhitelisted: try ABI.decodeBool(reads.2),
            operatorAuthorized: (try ABI.decodeBool(reads.3))
                || ReceiverFreshnessProof.ownerMatches(
                    reads.4,
                    operatorAddress: operatorAddress
                )
        )
    }
}

/// Bounded acceleration controller for payment polling. Acceleration is granted for a short
/// window after a *change* in visible funds — a canonical balance increase, a new or increased
/// advisory pending hint, or confirmation progress inside an unfinished window — and expires on
/// its own. A static partial balance or a stuck pending transaction therefore cannot hold the
/// fast cadence for an entire invoice lifetime, and the accelerated cadence never exceeds the
/// configured default.
public struct PaymentPollCadence: Sendable {
    public static let accelerationWindow: TimeInterval = 30

    private var acceleratedUntil: Date?
    private var lastBalance: UInt256?
    /// High-water mark of every nonzero hint ever seen. A flaky pending endpoint that
    /// intermittently returns `nil` must not let the SAME stuck transaction count as new
    /// progress each time it reappears, so this value never decreases.
    private var highestHint: UInt256 = .zero
    private var lastConfirmations: UInt64?

    public init() {}

    public mutating func interval(
        after observation: PaymentObservation,
        defaultInterval: UInt64,
        acceleratedInterval: UInt64,
        now: Date = Date()
    ) -> UInt64 {
        if hasProgressSignal(observation) {
            acceleratedUntil = now.addingTimeInterval(Self.accelerationWindow)
        }
        lastBalance = observation.balance
        if let hint = observation.pendingBalanceHint, hint > highestHint {
            highestHint = hint
        }
        if case let .confirming(_, confirmations, _) = observation.status {
            lastConfirmations = confirmations
        } else {
            lastConfirmations = nil
        }
        switch observation.status {
        case .paid, .overpaid, .expired:
            return defaultInterval
        case .waiting, .partial, .confirming:
            guard let acceleratedUntil, now < acceleratedUntil else {
                return defaultInterval
            }
            return min(defaultInterval, acceleratedInterval)
        }
    }

    private func hasProgressSignal(_ observation: PaymentObservation) -> Bool {
        if observation.balance > (lastBalance ?? .zero) { return true }
        if let hint = observation.pendingBalanceHint,
           !hint.isZero,
           hint > highestHint {
            return true
        }
        if case let .confirming(_, confirmations, required) = observation.status,
           confirmations < required,
           confirmations != lastConfirmations {
            return true
        }
        return false
    }
}

public struct PaymentMonitor: Sendable {
    public static let defaultPollIntervalNanoseconds: UInt64 = 5_000_000_000
    /// Cadence used only inside a fresh acceleration window. Two seconds tracks the Base block
    /// interval so an in-progress confirmation window is observed roughly once per new block.
    public static let acceleratedPollIntervalNanoseconds: UInt64 = 2_000_000_000
    /// Upper bound on how long the advisory pending read may delay a completed canonical
    /// sample. The hint is dropped, never awaited further, once this budget elapses.
    public static let advisoryPendingHintTimeout: Duration = .milliseconds(1_500)

    private let rpc: any EthereumReadRPC
    public let confirmationPolicy: ConfirmationPolicy
    public let pollIntervalNanoseconds: UInt64

    public init(
        rpc: any EthereumReadRPC,
        confirmationPolicy: ConfirmationPolicy,
        pollIntervalNanoseconds: UInt64 = PaymentMonitor.defaultPollIntervalNanoseconds
    ) {
        self.rpc = rpc
        self.confirmationPolicy = confirmationPolicy
        self.pollIntervalNanoseconds = pollIntervalNanoseconds
    }

    public func sample(
        _ request: PaymentRequest,
        previousThresholdCursor: PaymentConfirmationCursor? = nil,
        additionalCursors: [PaymentConfirmationCursor] = [],
        expectedChainID: UInt64? = nil,
        includePendingBalanceHint: Bool = false,
        now: Date = Date()
    ) async throws -> PaymentObservation {
        let observations = try await sampleBatch(
            [PaymentSampleInput(
                request: request,
                previousThresholdCursor: previousThresholdCursor,
                additionalCursors: additionalCursors
            )],
            expectedChainID: expectedChainID,
            includePendingBalanceHints: includePendingBalanceHint,
            now: now
        )
        guard let observation = observations.first else {
            throw RPCDecodingError.invalidData("empty payment sample")
        }
        return observation
    }

    /// Samples up to the app's twenty-invoice settlement limit in three sequential network waves:
    /// a chain plus latest block-identity anchor, fixed-block balances plus unique cursor hashes,
    /// then the final canonical identity check. Every middle-wave batch is capped at ten items and
    /// only six HTTP batches run concurrently, covering twenty balances and two distinct cursors
    /// per invoice without self-throttling a free endpoint.
    public func sampleBatch(
        _ inputs: [PaymentSampleInput],
        expectedChainID: UInt64? = nil,
        includePendingBalanceHints: Bool = false,
        now: Date = Date()
    ) async throws -> [PaymentObservation] {
        guard !inputs.isEmpty else { return [] }
        guard inputs.count <= 20 else {
            throw RPCDecodingError.invalidData("payment sample exceeds settlement limit")
        }

        let requestChainIDs = Set(inputs.map(\.request.chainID))
        guard requestChainIDs.count == 1, let requestChainID = requestChainIDs.first else {
            throw PaymentMonitorError.mixedRequestChains
        }
        if let expectedChainID, expectedChainID != requestChainID {
            throw PaymentMonitorError.requestChainMismatch(
                expected: expectedChainID,
                request: requestChainID
            )
        }
        // The invoice itself is the default network authority. Even SDK callers that omit the
        // optional override must prove the live endpoint is serving that exact chain before any
        // balance or confirmation state is accepted.
        let requiredChainID = expectedChainID ?? requestChainID
        let anchor = try await resolve([.chainID, .latestBlockIdentity])
        let block: UInt64
        let initialBlockHash: Bytes32
        guard anchor.count == 2,
              case let .quantity(actualChainID) = anchor[0],
              case let .blockIdentity(number, hash) = anchor[1]
        else { throw RPCDecodingError.invalidData("payment network anchor batch") }
        guard actualChainID == requiredChainID else {
            throw PaymentMonitorError.wrongChain(
                expected: requiredChainID,
                actual: actualChainID
            )
        }
        block = number
        initialBlockHash = hash

        // Advisory mempool hints run beside the fixed-head proof and tolerate every failure.
        // An empty input list resolves immediately, so the child task is free when disabled.
        async let advisoryHints = advisoryPendingBalances(
            for: includePendingBalanceHints ? inputs : []
        )

        var cursorSets = [[PaymentConfirmationCursor]]()
        cursorSets.reserveCapacity(inputs.count)
        var historicalBlocks = Set<UInt64>()
        for input in inputs {
            var seen = Set<PaymentConfirmationCursor>()
            let cursors = ([input.previousThresholdCursor].compactMap { $0 }
                + input.additionalCursors).filter {
                    seen.insert($0).inserted && $0.blockNumber <= block
                }
            cursorSets.append(cursors)
            historicalBlocks.formUnion(cursors.map(\.blockNumber))
        }
        historicalBlocks.remove(block)
        let orderedHistoricalBlocks = historicalBlocks.sorted()

        var proofRequests = [EthereumReadBatchRequest]()
        proofRequests.reserveCapacity(inputs.count + orderedHistoricalBlocks.count)
        proofRequests.append(contentsOf: inputs.map { input in
            .call(
                address: input.request.token.address,
                data: ABI.encodeCall(
                    selector: ABI.balanceOfSelector,
                    words: [ABI.word(input.request.receiver)]
                ),
                block: .number(block)
            )
        })
        proofRequests.append(contentsOf: orderedHistoricalBlocks.map {
            .canonicalBlockHash($0)
        })
        let proof = try await resolve(proofRequests)
        guard proof.count == proofRequests.count else {
            throw RPCDecodingError.invalidData("payment proof batch")
        }

        var balances = [UInt256]()
        balances.reserveCapacity(inputs.count)
        for index in inputs.indices {
            guard case let .data(data) = proof[index] else {
                throw RPCDecodingError.invalidData("payment balance batch")
            }
            balances.append(try ABI.decodeUInt256(data))
        }
        var historicalHashes = [UInt64: Bytes32]()
        let hashOffset = inputs.count
        for (index, historicalBlock) in orderedHistoricalBlocks.enumerated() {
            guard case let .blockHash(hash) = proof[hashOffset + index] else {
                throw RPCDecodingError.invalidData("canonical cursor batch")
            }
            historicalHashes[historicalBlock] = hash
        }

        // The bounded advisory read must settle BEFORE the final canonical-identity check so a
        // stalled hint cannot widen the window between proving the head and consuming the
        // evidence. If the checked block is replaced while the hint is pending, the final wave
        // below still observes the replacement and fails the sample closed.
        let pendingHints = await advisoryHints

        let final = try await resolve([.canonicalBlockHash(block)])
        guard final.count == 1, case let .blockHash(finalBlockHash) = final[0] else {
            throw RPCDecodingError.invalidData("payment final head batch")
        }
        guard finalBlockHash == initialBlockHash else {
            throw PaymentMonitorError.canonicalBlockChanged(blockNumber: block)
        }

        var observations = [PaymentObservation]()
        observations.reserveCapacity(inputs.count)
        for (index, input) in inputs.enumerated() {
            let validatedCursors = cursorSets[index].filter { cursor in
                let canonicalHash = cursor.blockNumber == block
                    ? finalBlockHash
                    : historicalHashes[cursor.blockNumber]
                return canonicalHash == cursor.blockHash
            }
            observations.append(classify(
                input.request,
                balance: balances[index],
                block: block,
                blockHash: finalBlockHash,
                previousThresholdCursor: input.previousThresholdCursor,
                validatedCursors: validatedCursors,
                pendingBalanceHint: index < pendingHints.count ? pendingHints[index] : nil,
                now: now
            ))
        }
        return observations
    }

    /// Advisory-only mempool balances used for cashier feedback while a QR is on screen. Any
    /// transport, batching, endpoint-compatibility, or decoding failure degrades to `nil`
    /// hints, and a stalled read is abandoned after `advisoryPendingHintTimeout` so it can
    /// neither block the canonical sample nor hold the shared endpoint budget. Nothing read
    /// here may influence the fixed-head evidence, status classification thresholds, or
    /// persisted confirmation state.
    private func advisoryPendingBalances(
        for inputs: [PaymentSampleInput]
    ) async -> [UInt256?] {
        guard !inputs.isEmpty else { return [] }
        let fallback = [UInt256?](repeating: nil, count: inputs.count)
        return await withTaskGroup(
            of: [UInt256?]?.self,
            returning: [UInt256?].self
        ) { group in
            group.addTask { await self.readAdvisoryPendingBalances(for: inputs) }
            group.addTask {
                try? await Task.sleep(for: Self.advisoryPendingHintTimeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? fallback
        }
    }

    private func readAdvisoryPendingBalances(
        for inputs: [PaymentSampleInput]
    ) async -> [UInt256?] {
        let requests = inputs.map { input in
            EthereumReadBatchRequest.call(
                address: input.request.token.address,
                data: ABI.encodeCall(
                    selector: ABI.balanceOfSelector,
                    words: [ABI.word(input.request.receiver)]
                ),
                block: .pending
            )
        }
        guard let results = try? await resolve(requests),
              results.count == requests.count
        else { return [UInt256?](repeating: nil, count: inputs.count) }
        return results.map { result in
            guard case let .data(data) = result else { return nil }
            return try? ABI.decodeUInt256(data)
        }
    }

    private func resolve(
        _ requests: [EthereumReadBatchRequest]
    ) async throws -> [EthereumReadBatchResult] {
        guard !requests.isEmpty else { return [] }
        if let batchRPC = rpc as? any EthereumBatchReadRPC {
            let chunks = stride(from: 0, to: requests.count, by: 10).map { start in
                Array(requests[start..<min(start + 10, requests.count)])
            }
            return try await withThrowingTaskGroup(
                of: (Int, [EthereumReadBatchResult]).self,
                returning: [EthereumReadBatchResult].self
            ) { group in
                var nextChunk = 0
                let maximumConcurrentBatches = min(6, chunks.count)
                func enqueue(_ index: Int) {
                    let chunk = chunks[index]
                    group.addTask { (index, try await batchRPC.batch(chunk)) }
                }
                while nextChunk < maximumConcurrentBatches {
                    enqueue(nextChunk)
                    nextChunk += 1
                }
                var resolved = Array<[EthereumReadBatchResult]?>(
                    repeating: nil,
                    count: chunks.count
                )
                for try await (index, values) in group {
                    guard values.count == chunks[index].count else {
                        throw RPCDecodingError.invalidData("partial payment proof batch")
                    }
                    resolved[index] = values
                    if nextChunk < chunks.count {
                        enqueue(nextChunk)
                        nextChunk += 1
                    }
                }
                guard resolved.allSatisfy({ $0 != nil }) else {
                    throw RPCDecodingError.invalidData("missing payment proof batch")
                }
                return resolved.flatMap { $0! }
            }
        }

        return try await withThrowingTaskGroup(
            of: (Int, EthereumReadBatchResult).self,
            returning: [EthereumReadBatchResult].self
        ) { group in
            var nextRequest = 0
            // Alternate RPC implementations still perform one HTTP call per read. Keep the
            // same six-request ceiling as the production batch path so a fallback cannot create
            // a larger burst against a rate-limited endpoint.
            let maximumConcurrentReads = min(6, requests.count)
            func enqueue(_ index: Int) {
                let request = requests[index]
                group.addTask { (index, try await resolveOne(request)) }
            }
            while nextRequest < maximumConcurrentReads {
                enqueue(nextRequest)
                nextRequest += 1
            }
            var resolved = Array<EthereumReadBatchResult?>(
                repeating: nil,
                count: requests.count
            )
            for try await (index, value) in group {
                resolved[index] = value
                if nextRequest < requests.count {
                    enqueue(nextRequest)
                    nextRequest += 1
                }
            }
            guard resolved.allSatisfy({ $0 != nil }) else {
                throw RPCDecodingError.invalidData("missing payment proof read")
            }
            return resolved.map { $0! }
        }
    }

    private func resolveOne(
        _ request: EthereumReadBatchRequest
    ) async throws -> EthereumReadBatchResult {
        switch request {
        case .chainID:
            .quantity(try await rpc.chainID())
        case .blockNumber:
            .quantity(try await rpc.blockNumber())
        case .latestBlockIdentity:
            try await {
                let number = try await rpc.blockNumber()
                return .blockIdentity(
                    number: number,
                    hash: try await rpc.canonicalBlockHash(at: number)
                )
            }()
        case let .canonicalBlockHash(block):
            .blockHash(try await rpc.canonicalBlockHash(at: block))
        case let .code(address, block):
            .data(try await rpc.code(at: address, block: block))
        case let .call(address, data, block):
            .data(try await rpc.call(to: address, data: data, block: block))
        }
    }

    public func classify(
        _ request: PaymentRequest,
        balance: UInt256,
        block: UInt64,
        blockHash: Bytes32,
        previousThresholdCursor: PaymentConfirmationCursor? = nil,
        validatedCursors: [PaymentConfirmationCursor] = [],
        pendingBalanceHint: UInt256? = nil,
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
                validatedPreviousCursors: validatedCursors,
                pendingBalanceHint: pendingBalanceHint
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
            validatedPreviousCursors: validatedCursors,
            pendingBalanceHint: pendingBalanceHint
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
                        let observation: PaymentObservation
                        do {
                            // This is an indefinite, cadence-owning stream. Never inherit one
                            // caller-installed absolute background deadline across all samples:
                            // once expired it could otherwise make every retry fail immediately.
                            observation = try await RPCRequestDeadline.$current.withValue(nil) {
                                try await monitor.sample(
                                    request,
                                    previousThresholdCursor: thresholdCursor
                                )
                            }
                        } catch {
                            guard PaymentMonitorRetryPolicy.shouldRetry(error) else {
                                throw error
                            }
                            // Preserve the last verified cursor and retry at the normal polling
                            // cadence. A transient sample never becomes payment evidence.
                            try await Task.sleep(
                                nanoseconds: monitor.pollIntervalNanoseconds
                            )
                            continue
                        }
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
