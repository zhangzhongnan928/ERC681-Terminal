// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

private struct LiveSigningState: Sendable {
    let chainID: UInt64
    let authorization: VaultAuthorization
    let tokenBalances: [UInt256]
    let gasEstimate: UInt64
    let gasBalance: UInt256
    let pendingNonce: UInt64
}

public actor SettlementCoordinator {
    /// Conservative extra balance reserved for the OP Stack L1 data charge, which is not
    /// represented by `gasLimit * maxFeePerGas`.
    public static let defaultL1DataFeeReserve = UInt256(20_000_000_000_000)
    public static let minimumRecommendedBalance = UInt256(100_000_000_000_000)

    private let rpc: any EthereumOperatorRPC
    private let signer: any OperatorTransactionSigning
    private let operatorAddress: EthereumAddress
    private let nonceGate = NonceSubmissionGate()
    private var nextLocalNonceByChain = [UInt64: UInt64]()

    public init(
        rpc: OperatorRPCClient,
        wallet: KeychainOperatorWallet,
        operatorAddress: EthereumAddress
    ) {
        self.rpc = rpc
        self.signer = wallet
        self.operatorAddress = operatorAddress
    }

    init(
        rpc: any EthereumOperatorRPC,
        signer: any OperatorTransactionSigning,
        operatorAddress: EthereumAddress
    ) {
        self.rpc = rpc
        self.signer = signer
        self.operatorAddress = operatorAddress
    }

    public func refreshStatus(
        expectedChainID: UInt64,
        vault: EthereumAddress
    ) async throws -> OperatorChainStatus {
        async let chainID = rpc.chainID()
        async let balance = rpc.balance(of: operatorAddress)
        async let authorization = rpc.vaultAuthorization(
            vault: vault,
            operatorAddress: operatorAddress
        )
        let (actualChainID, resolvedBalance, resolvedAuthorization) = try await (
            chainID,
            balance,
            authorization
        )
        guard actualChainID == expectedChainID else {
            throw SettlementOperatorError.chainMismatch(
                expected: expectedChainID,
                actual: actualChainID
            )
        }
        return OperatorChainStatus(
            chainID: actualChainID,
            balance: resolvedBalance,
            isAuthorizedOperator: resolvedAuthorization.isAuthorized,
            isVaultOwner: resolvedAuthorization.isOwner,
            isLowGas: resolvedBalance < Self.minimumRecommendedBalance
        )
    }

    /// Reads both canonical/latest and pending native balances for destructive reset safety.
    /// Fee-readiness intentionally continues to use the pending view through `refreshStatus`.
    public func resetSafetyBalances(
        expectedChainID: UInt64
    ) async throws -> OperatorNativeBalanceSnapshot {
        let actualChainID = try await rpc.chainID()
        guard actualChainID == expectedChainID else {
            throw SettlementOperatorError.chainMismatch(
                expected: expectedChainID,
                actual: actualChainID
            )
        }
        async let latest = rpc.latestBalance(of: operatorAddress)
        async let pending = rpc.balance(of: operatorAddress)
        return try await OperatorNativeBalanceSnapshot(latest: latest, pending: pending)
    }

    /// Performs authorization, simulation, gas estimation, fee selection, and balance checks.
    /// It never accesses the private key or prompts for authentication.
    public func prepare(_ intent: SettlementIntent) async throws -> PreparedSettlement {
        let calldata = SettlementABI.encodeSweepSessions(intent)
        async let chainID = rpc.chainID()
        async let authorizationRead = rpc.vaultAuthorization(
            vault: intent.vault,
            operatorAddress: operatorAddress
        )
        async let balancesRead = liveTokenBalances(for: intent)
        async let simulation: Void = rpc.simulate(
            from: operatorAddress,
            to: intent.vault,
            data: calldata
        )
        async let gasEstimate = rpc.estimateGas(
            from: operatorAddress,
            to: intent.vault,
            data: calldata
        )
        async let quote = rpc.feeQuote()
        async let balance = rpc.balance(of: operatorAddress)
        let (
            actualChainID,
            authorization,
            observedTokenBalances,
            _,
            estimate,
            resolvedQuote,
            resolvedBalance
        ) = try await (
            chainID,
            authorizationRead,
            balancesRead,
            simulation,
            gasEstimate,
            quote,
            balance
        )
        guard actualChainID == intent.chainID else {
            throw SettlementOperatorError.chainMismatch(
                expected: intent.chainID,
                actual: actualChainID
            )
        }
        guard authorization.isAuthorized else {
            throw SettlementOperatorError.operatorNotAuthorized
        }
        let gasLimit = try paddedGasLimit(estimate)
        let executionCost = try product(gasLimit, resolvedQuote.maxFeePerGas)
        let (maximumGasCost, overflow) = executionCost.addingReportingOverflow(
            Self.defaultL1DataFeeReserve
        )
        guard !overflow else { throw SettlementOperatorError.arithmeticOverflow }
        guard resolvedBalance >= maximumGasCost else {
            throw SettlementOperatorError.insufficientGasBalance(
                required: maximumGasCost,
                available: resolvedBalance
            )
        }
        return PreparedSettlement(
            intent: intent,
            operatorAddress: operatorAddress,
            calldata: calldata,
            gasLimit: gasLimit,
            feeQuote: resolvedQuote,
            l1DataFeeReserve: Self.defaultL1DataFeeReserve,
            maximumGasCost: maximumGasCost,
            operatorBalance: resolvedBalance,
            observedTokenBalances: observedTokenBalances
        )
    }

    /// Authenticates and signs, but deliberately does not broadcast. Callers must durably
    /// persist the returned raw transaction and hash before calling `broadcast`.
    public func sign(
        _ prepared: PreparedSettlement,
        authenticationReason: String,
        postAuthenticationValidation: @escaping @Sendable () async throws -> Void = {},
        postAuthenticationFinalValidation: @escaping @Sendable () throws -> Void = {}
    ) async throws -> SignedSettlement {
        await nonceGate.acquire()
        do {
            let signed = try await signWhileHoldingNonceGate(
                prepared,
                authenticationReason: authenticationReason,
                postAuthenticationValidation: postAuthenticationValidation,
                postAuthenticationFinalValidation: postAuthenticationFinalValidation
            )
            await nonceGate.release()
            return signed
        } catch {
            await nonceGate.release()
            throw error
        }
    }

    /// Broadcasts a transaction that the app has already persisted. Transport failures are
    /// conservatively returned as `unknown`, because the RPC may have accepted the bytes.
    public func broadcast(_ signed: SignedSettlement) async -> SettlementSubmission {
        let expectedHash = Keccak256.hash(signed.rawTransaction)
        guard expectedHash == signed.transactionHash else {
            return SettlementSubmission(
                intent: signed.intent,
                transactionHash: signed.transactionHash,
                nonce: signed.nonce,
                rawTransaction: signed.rawTransaction,
                phase: .failed,
                broadcastError: "The persisted raw transaction no longer matches its hash."
            )
        }
        do {
            let returnedHash = try await rpc.sendRawTransaction(signed.rawTransaction)
            guard returnedHash == expectedHash else {
                throw SettlementOperatorError.transactionHashMismatch(
                    expected: expectedHash,
                    actual: returnedHash
                )
            }
            return SettlementSubmission(
                intent: signed.intent,
                transactionHash: expectedHash,
                nonce: signed.nonce,
                rawTransaction: signed.rawTransaction,
                phase: .pending,
                broadcastError: nil
            )
        } catch {
            return SettlementSubmission(
                intent: signed.intent,
                transactionHash: expectedHash,
                nonce: signed.nonce,
                rawTransaction: signed.rawTransaction,
                phase: .unknown,
                broadcastError: error.localizedDescription
            )
        }
    }

    /// Re-broadcasts only a persisted, canonical type-2 zero-value sweep. It never signs or
    /// changes the nonce/fees/calldata, so an ambiguous first response cannot create a
    /// replacement transaction or a second authorization prompt.
    public func retryPersistedBroadcast(
        transactionHash: Bytes32,
        rawTransaction: Data,
        intent: SettlementIntent,
        nonce: UInt64
    ) async -> SettlementSubmission {
        do {
            guard Keccak256.hash(rawTransaction) == transactionHash else {
                throw SettlementOperatorError.tamperedPreparation
            }
            try EIP1559Transaction.validatePersistedSweep(
                rawTransaction: rawTransaction,
                intent: intent,
                nonce: nonce
            )
            let actualChainID = try await rpc.chainID()
            guard actualChainID == intent.chainID else {
                throw SettlementOperatorError.chainMismatch(
                    expected: intent.chainID,
                    actual: actualChainID
                )
            }
            let returnedHash = try await rpc.sendRawTransaction(rawTransaction)
            guard returnedHash == transactionHash else {
                throw SettlementOperatorError.transactionHashMismatch(
                    expected: transactionHash,
                    actual: returnedHash
                )
            }
            return SettlementSubmission(
                intent: intent,
                transactionHash: transactionHash,
                nonce: nonce,
                rawTransaction: rawTransaction,
                phase: .pending,
                broadcastError: nil
            )
        } catch {
            return SettlementSubmission(
                intent: intent,
                transactionHash: transactionHash,
                nonce: nonce,
                rawTransaction: rawTransaction,
                phase: .unknown,
                broadcastError: error.localizedDescription
            )
        }
    }

    public func reconcile(
        transactionHash: Bytes32,
        intent: SettlementIntent,
        requiredConfirmations: UInt64,
        priorPhase: SettlementTransactionPhase
    ) async throws -> SettlementReconciliation {
        guard let receipt = try await rpc.receipt(transactionHash: transactionHash) else {
            return SettlementReconciliation(
                phase: priorPhase == .unknown ? .unknown : .pending,
                blockNumber: nil,
                confirmations: 0,
                verifiedSweeps: [],
                failureReason: priorPhase == .unknown
                    ? "Broadcast acceptance has not yet been confirmed by a receipt."
                    : nil
            )
        }
        let preliminarySweeps: [VerifiedSweep]
        let preliminaryFailure: String?
        if receipt.succeeded {
            do {
                preliminarySweeps = try SettlementABI.verifySweptEvents(
                    receipt: receipt,
                    intent: intent
                )
                preliminaryFailure = preliminarySweeps.count == intent.sessions.count
                    ? nil
                    : "The successful receipt does not yet contain one unique nonzero Swept proof for every session."
            } catch {
                preliminarySweeps = []
                preliminaryFailure = error.localizedDescription
            }
        } else {
            preliminarySweeps = []
            preliminaryFailure = "The transaction receipt reports a revert."
        }

        let head = try await rpc.blockNumber()
        guard let confirmations = Self.confirmationDepth(
            head: head,
            receiptBlock: receipt.blockNumber
        ) else {
            return SettlementReconciliation(
                phase: .unknown,
                blockNumber: receipt.blockNumber,
                confirmations: 0,
                verifiedSweeps: preliminarySweeps,
                failureReason: "The RPC head is behind the receipt block."
            )
        }
        let required = max(requiredConfirmations, 1)
        guard confirmations >= required else {
            return SettlementReconciliation(
                phase: .mined,
                blockNumber: receipt.blockNumber,
                confirmations: confirmations,
                verifiedSweeps: preliminarySweeps,
                failureReason: preliminaryFailure.map {
                    "\($0) Waiting for the configured finality window before marking this transaction failed."
                }
            )
        }

        guard let confirmedReceipt = try await rpc.receipt(transactionHash: transactionHash),
              confirmedReceipt == receipt,
              try await rpc.canonicalBlockHash(at: receipt.blockNumber) == receipt.blockHash
        else {
            return SettlementReconciliation(
                phase: .unknown,
                blockNumber: receipt.blockNumber,
                confirmations: confirmations,
                verifiedSweeps: [],
                failureReason: "The receipt or canonical block identity changed during finality verification."
            )
        }

        // Receipt identity alone is not enough: blocks above an unchanged receipt may have
        // reorganized while the verification calls were in flight. Re-read the head after
        // identity verification and gate every terminal phase on the depth that still exists.
        let finalHead = try await rpc.blockNumber()
        guard let finalConfirmations = Self.confirmationDepth(
            head: finalHead,
            receiptBlock: confirmedReceipt.blockNumber
        ) else {
            return SettlementReconciliation(
                phase: .unknown,
                blockNumber: confirmedReceipt.blockNumber,
                confirmations: 0,
                verifiedSweeps: [],
                failureReason: "The RPC head moved behind the receipt block during finality verification."
            )
        }
        guard finalConfirmations >= required else {
            return SettlementReconciliation(
                phase: .mined,
                blockNumber: confirmedReceipt.blockNumber,
                confirmations: finalConfirmations,
                verifiedSweeps: preliminarySweeps,
                failureReason: preliminaryFailure.map {
                    "\($0) The finality window no longer has the configured depth."
                }
            )
        }
        guard confirmedReceipt.succeeded else {
            return SettlementReconciliation(
                phase: .failed,
                blockNumber: confirmedReceipt.blockNumber,
                confirmations: finalConfirmations,
                verifiedSweeps: [],
                failureReason: "The same canonical reverted receipt survived the configured finality window."
            )
        }
        let confirmedSweeps: [VerifiedSweep]
        do {
            confirmedSweeps = try SettlementABI.verifySweptEvents(
                receipt: confirmedReceipt,
                intent: intent
            )
        } catch {
            return SettlementReconciliation(
                phase: .failed,
                blockNumber: confirmedReceipt.blockNumber,
                confirmations: finalConfirmations,
                verifiedSweeps: [],
                failureReason: "The same canonical malformed receipt survived the configured finality window: \(error.localizedDescription)"
            )
        }
        let sweepsByInvoiceID = Dictionary(
            uniqueKeysWithValues: confirmedSweeps.map { ($0.invoiceID, $0) }
        )
        var isCumulativelyIncomplete = confirmedSweeps.count != intent.sessions.count
        for session in intent.sessions {
            guard let sweep = sweepsByInvoiceID[session.invoiceID] else {
                isCumulativelyIncomplete = true
                continue
            }
            let (cumulative, overflow) = session.priorConfirmedSweptAmount
                .addingReportingOverflow(sweep.sweptAmount)
            guard !overflow else { throw SettlementOperatorError.arithmeticOverflow }
            if cumulative < session.expectedAmount {
                isCumulativelyIncomplete = true
            }
        }
        if isCumulativelyIncomplete {
            return SettlementReconciliation(
                phase: .needsReview,
                blockNumber: receipt.blockNumber,
                confirmations: finalConfirmations,
                verifiedSweeps: confirmedSweeps,
                failureReason: "At least one confirmed session lacks a unique nonzero event or remains below its immutable expected amount. Preserved canonical proofs can be indexed, and a later nonzero sweep may complete the batch cumulatively."
            )
        }
        return SettlementReconciliation(
            phase: .final,
            blockNumber: receipt.blockNumber,
            confirmations: finalConfirmations,
            verifiedSweeps: confirmedSweeps,
            failureReason: nil
        )
    }

    private func signWhileHoldingNonceGate(
        _ prepared: PreparedSettlement,
        authenticationReason: String,
        postAuthenticationValidation: @escaping @Sendable () async throws -> Void,
        postAuthenticationFinalValidation: @escaping @Sendable () throws -> Void
    ) async throws -> SignedSettlement {
        guard prepared.operatorAddress == operatorAddress,
              prepared.calldata == SettlementABI.encodeSweepSessions(prepared.intent),
              prepared.gasLimit > 0,
              prepared.feeQuote.maxFeePerGas >= prepared.feeQuote.maxPriorityFeePerGas,
              prepared.l1DataFeeReserve == Self.defaultL1DataFeeReserve,
              prepared.observedTokenBalances.count == prepared.intent.sessions.count,
              prepared.observedTokenBalances.allSatisfy({ !$0.isZero })
        else { throw SettlementOperatorError.tamperedPreparation }
        let initialState = try await liveSigningState(for: prepared)
        try validateSigningState(initialState, against: prepared)

        let remoteNonce = initialState.pendingNonce
        let nonce = max(remoteNonce, nextLocalNonceByChain[prepared.intent.chainID] ?? remoteNonce)
        let transaction = EIP1559Transaction(
            chainID: prepared.intent.chainID,
            nonce: nonce,
            maxPriorityFeePerGas: prepared.feeQuote.maxPriorityFeePerGas,
            maxFeePerGas: prepared.feeQuote.maxFeePerGas,
            gasLimit: prepared.gasLimit,
            destination: prepared.intent.vault,
            data: prepared.calldata
        )
        // Recheck after simulation, gas, and nonce reads so newly added value cannot inherit
        // an older balance's confirmation window immediately before the signing call.
        try await assertPreparedBalancesStillCurrent(prepared)
        let signature = try await signer.sign(
            digest: transaction.signingDigest,
            reason: authenticationReason,
            postAuthenticationValidation: { [self] in
                // The authentication prompt is an unbounded suspension point. Recheck every
                // mutable signing prerequisite while the app concurrently revalidates its exact
                // persisted balance/canonical-cursor proof. No private-key operation occurs until
                // both fail-closed paths complete.
                try await validateAfterAuthentication(
                    prepared,
                    expectedNonce: nonce,
                    confirmationProof: postAuthenticationValidation,
                    finalValidation: postAuthenticationFinalValidation
                )
            }
        )
        let rawTransaction = transaction.serialized(with: signature)
        let (nextNonce, overflow) = nonce.addingReportingOverflow(1)
        guard !overflow else { throw SettlementOperatorError.arithmeticOverflow }
        // Reserve before returning the signed bytes so a second foreground request cannot
        // reuse this nonce, even if the first broadcast response is lost.
        nextLocalNonceByChain[prepared.intent.chainID] = nextNonce
        return SignedSettlement(
            intent: prepared.intent,
            transactionHash: Keccak256.hash(rawTransaction),
            nonce: nonce,
            rawTransaction: rawTransaction
        )
    }

    private func paddedGasLimit(_ estimate: UInt64) throws -> UInt64 {
        let (margin, marginOverflow) = estimate.addingReportingOverflow(estimate / 5)
        let (padded, paddingOverflow) = margin.addingReportingOverflow(20_000)
        guard !marginOverflow, !paddingOverflow else {
            throw SettlementOperatorError.arithmeticOverflow
        }
        return padded
    }

    private static func confirmationDepth(
        head: UInt64,
        receiptBlock: UInt64
    ) -> UInt64? {
        let (distance, underflow) = head.subtractingReportingOverflow(receiptBlock)
        guard !underflow else { return nil }
        let (depth, overflow) = distance.addingReportingOverflow(1)
        // A mathematical depth of UInt64.max + 1 exceeds any representable requirement.
        return overflow ? UInt64.max : depth
    }

    private func product(_ left: UInt64, _ right: UInt64) throws -> UInt256 {
        let (value, overflow) = left.multipliedReportingOverflow(by: right)
        guard !overflow else { throw SettlementOperatorError.arithmeticOverflow }
        return UInt256(value)
    }

    private func liveTokenBalances(for intent: SettlementIntent) async throws -> [UInt256] {
        let sessions = intent.sessions
        let balances = try await rpc.tokenBalances(
            token: intent.token,
            accounts: sessions.map(\.receiver)
        )
        guard balances.count == sessions.count else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        for (session, balance) in zip(sessions, balances) {
            guard !balance.isZero else {
                throw SettlementOperatorError.receiverHasNoSweepableBalance(
                    session.invoiceID
                )
            }
            if session.priorConfirmedSweptAmount < session.expectedAmount {
                let (requiredBalance, underflow) = session.expectedAmount
                    .subtractingReportingOverflow(session.priorConfirmedSweptAmount)
                guard !underflow, !requiredBalance.isZero else {
                    throw SettlementOperatorError.arithmeticOverflow
                }
                guard balance >= requiredBalance else {
                    throw SettlementOperatorError.receiverBalanceBelowRequired(
                        invoiceID: session.invoiceID,
                        required: requiredBalance,
                        available: balance
                    )
                }
            }
        }
        return balances
    }

    private func liveSigningState(
        for prepared: PreparedSettlement
    ) async throws -> LiveSigningState {
        async let chainID = rpc.chainID()
        async let authorization = rpc.vaultAuthorization(
            vault: prepared.intent.vault,
            operatorAddress: operatorAddress
        )
        async let balances = liveTokenBalances(for: prepared.intent)
        async let simulation: Void = rpc.simulate(
            from: operatorAddress,
            to: prepared.intent.vault,
            data: prepared.calldata
        )
        async let gasEstimate = rpc.estimateGas(
            from: operatorAddress,
            to: prepared.intent.vault,
            data: prepared.calldata
        )
        async let gasBalance = rpc.balance(of: operatorAddress)
        async let pendingNonce = rpc.pendingNonce(of: operatorAddress)
        let resolved = try await (
            chainID,
            authorization,
            balances,
            simulation,
            gasEstimate,
            gasBalance,
            pendingNonce
        )
        return LiveSigningState(
            chainID: resolved.0,
            authorization: resolved.1,
            tokenBalances: resolved.2,
            gasEstimate: resolved.4,
            gasBalance: resolved.5,
            pendingNonce: resolved.6
        )
    }

    private func validateAfterAuthentication(
        _ prepared: PreparedSettlement,
        expectedNonce: UInt64,
        confirmationProof: @escaping @Sendable () async throws -> Void,
        finalValidation: @escaping @Sendable () throws -> Void
    ) async throws {
        async let state = liveSigningState(for: prepared)
        async let persistedProof: Void = confirmationProof()
        let (freshState, _) = try await (state, persistedProof)
        try validateSigningState(freshState, against: prepared)
        let freshNonce = max(
            freshState.pendingNonce,
            nextLocalNonceByChain[prepared.intent.chainID] ?? freshState.pendingNonce
        )
        guard freshNonce == expectedNonce else {
            throw SettlementOperatorError.tamperedPreparation
        }
        // This synchronous check is deliberately last: it runs after both the live signing
        // prerequisites and the caller's canonical confirmation proof, immediately before the
        // signer is allowed to touch private-key material.
        try finalValidation()
    }

    private func validateSigningState(
        _ state: LiveSigningState,
        against prepared: PreparedSettlement
    ) throws {
        guard state.chainID == prepared.intent.chainID else {
            throw SettlementOperatorError.chainMismatch(
                expected: prepared.intent.chainID,
                actual: state.chainID
            )
        }
        guard state.authorization.isAuthorized else {
            throw SettlementOperatorError.operatorNotAuthorized
        }
        try assertPreparedBalances(state.tokenBalances, stillMatch: prepared)
        guard state.gasEstimate <= prepared.gasLimit else {
            throw SettlementOperatorError.tamperedPreparation
        }
        guard state.gasBalance >= prepared.maximumGasCost else {
            throw SettlementOperatorError.insufficientGasBalance(
                required: prepared.maximumGasCost,
                available: state.gasBalance
            )
        }
    }

    private func assertPreparedBalancesStillCurrent(
        _ prepared: PreparedSettlement
    ) async throws {
        let currentBalances = try await liveTokenBalances(for: prepared.intent)
        try assertPreparedBalances(currentBalances, stillMatch: prepared)
    }

    private func assertPreparedBalances(
        _ currentBalances: [UInt256],
        stillMatch prepared: PreparedSettlement
    ) throws {
        guard currentBalances.count == prepared.observedTokenBalances.count else {
            throw SettlementOperatorError.tamperedPreparation
        }
        for (index, current) in currentBalances.enumerated()
            where current != prepared.observedTokenBalances[index] {
            throw SettlementOperatorError.receiverBalanceChanged(
                invoiceID: prepared.intent.sessions[index].invoiceID,
                confirmed: prepared.observedTokenBalances[index],
                current: current
            )
        }
    }
}

private actor NonceSubmissionGate {
    private var isLocked = false
    private var waiters = [CheckedContinuation<Void, Never>]()

    func acquire() async {
        if !isLocked {
            isLocked = true
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func release() {
        if waiters.isEmpty {
            isLocked = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}
