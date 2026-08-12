package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.SettlementEvent
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger

internal data class ReceiverBalanceSnapshot(
    val balance: BigInteger,
    val blockNumber: Long,
    val blockHash: String,
    val canonicalFirstDetectedBlockHash: String? = null,
    val canonicalLateFirstDetectedBlockHash: String? = null,
)

internal data class ReconciledInvoiceObservation(
    val receivedAmount: BigInteger,
    val status: InvoiceStatus,
    val firstDetectedBlock: Long?,
    val firstDetectedBlockHash: String?,
    val lastObservedBlock: Long,
    val confirmedAtBlock: Long?,
)

internal fun interface LateReceiverSampler {
    fun sample(invoice: Invoice): ReceiverBalanceSnapshot
}

/**
 * Performs one bounded pass over receivers whose published QR remains payable after local close or
 * settlement. A durable least-recently-attempted timestamp rotates through stable history, so an
 * unreachable early row cannot starve later receivers across cancellation or process restarts.
 */
internal class LateInvoiceReconciler(
    private val invoiceDao: InvoiceDao,
    private val eventDao: SettlementEventDao,
    private val lifecycleGate: TerminalLifecycleGate,
    sampler: LateReceiverSampler? = null,
    private val rpcEndpointResolver: RpcEndpointResolver = RpcEndpointResolver.PASSTHROUGH,
) {
    private val sampler = sampler ?: LateReceiverSampler { invoice ->
        sampleReceiverBalance(invoice, rpcEndpointResolver)
    }
    private val reconciliationMutex = Mutex()

    suspend fun reconcileOnce(limit: Int = MAX_CANDIDATES_PER_PASS): Int =
        reconciliationMutex.withLock { reconcilePass(limit) }

    private suspend fun reconcilePass(limit: Int): Int {
        require(limit in 1..MAX_CANDIDATES_PER_PASS) {
            "Late reconciliation limit must be between 1 and $MAX_CANDIDATES_PER_PASS"
        }
        var persisted = 0
        val candidates = invoiceDao.getLateReconciliationCandidates(limit)
        candidates.forEach { invoice ->
            try {
                // Persist the attempt before the RPC. If this coroutine is cancelled or the
                // process dies during a slow historical endpoint, unattempted rows sort first on
                // the next run instead of being starved by the same stale prefix.
                val claimed = lifecycleGate.withExclusiveMutation {
                    val current = invoiceDao.getById(invoice.invoiceId)
                        ?: return@withExclusiveMutation false
                    if (!current.sameLifecycleStateExceptLateAttempt(invoice) ||
                        current.status !in RECONCILABLE_STATUSES ||
                        (current.status == InvoiceStatus.LATE_PAYMENT_READY &&
                            current.settlementId != null)
                    ) return@withExclusiveMutation false
                    invoiceDao.markLateRecoveryAttempt(
                        current.invoiceId,
                        current.status,
                        System.currentTimeMillis(),
                    ) == 1
                }
                if (!claimed) return@forEach
                // Historical RPC work must not monopolize the process-wide mutation gate. The
                // invoice's network snapshot is immutable; re-read its lifecycle state only when
                // the sampled result is ready to persist.
                val snapshot = sampler.sample(invoice)
                val changed = lifecycleGate.withExclusiveMutation {
                    val current = invoiceDao.getById(invoice.invoiceId)
                        ?: return@withExclusiveMutation 0
                    if (!current.sameLifecycleStateExceptLateAttempt(invoice) ||
                        current.status !in RECONCILABLE_STATUSES ||
                        (current.status == InvoiceStatus.LATE_PAYMENT_READY &&
                            current.settlementId != null)
                    ) return@withExclusiveMutation 0
                    val changed = if (
                        current.status == InvoiceStatus.LATE_PAYMENT_READY &&
                        snapshot.balance.signum() == 0
                    ) {
                        restorePriorProof(current, snapshot.blockNumber)
                    } else {
                        val observation = reconcilePublishedInvoice(current, snapshot)
                        when (current.status) {
                            InvoiceStatus.EXPIRED -> invoiceDao.updateClosedInvoiceObservation(
                                invoiceId = current.invoiceId,
                                receivedAmount = observation.receivedAmount.toString(),
                                status = observation.status,
                                firstDetectedBlock = observation.firstDetectedBlock,
                                firstDetectedBlockHash = observation.firstDetectedBlockHash,
                                lastObservedBlock = observation.lastObservedBlock,
                                confirmedAtBlock = observation.confirmedAtBlock,
                            )

                            InvoiceStatus.PAID,
                            InvoiceStatus.OVERPAID -> invoiceDao.updateConfirmedInvoiceObservation(
                                invoiceId = current.invoiceId,
                                sourceStatus = current.status,
                                receivedAmount = observation.receivedAmount.toString(),
                                status = observation.status,
                                firstDetectedBlock = observation.firstDetectedBlock,
                                firstDetectedBlockHash = observation.firstDetectedBlockHash,
                                lastObservedBlock = observation.lastObservedBlock,
                                confirmedAtBlock = observation.confirmedAtBlock,
                            )

                            InvoiceStatus.PARTIALLY_SETTLED,
                            InvoiceStatus.SETTLED,
                            InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED,
                            InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                            InvoiceStatus.LATE_PAYMENT_READY ->
                                invoiceDao.updateSweptInvoiceObservation(
                                    invoiceId = current.invoiceId,
                                    sourceStatus = current.status,
                                    observedLateAmount = observation.receivedAmount.toString(),
                                    status = observation.status,
                                    firstDetectedBlock = observation.firstDetectedBlock,
                                    firstDetectedBlockHash = observation.firstDetectedBlockHash,
                                    lastObservedBlock = observation.lastObservedBlock,
                                    confirmedAtBlock = observation.confirmedAtBlock,
                                )

                            else -> 0
                        }
                    }
                    changed
                }
                if (changed == 1) persisted += 1
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // One unreachable or malformed historical snapshot must not prevent the remaining
                // bounded candidates from being reconciled during this pass.
            }
        }
        return persisted
    }

    private suspend fun restorePriorProof(invoice: Invoice, observedBlock: Long): Int {
        if (invoice.settlementAmbiguous) {
            return invoiceDao.restoreSettlementReviewRequired(
                invoiceId = invoice.invoiceId,
                lastObservedBlock = observedBlock,
            )
        }
        val matchingEvents = eventDao.getByInvoiceScope(
            invoice.chainId,
            invoice.vaultAddress,
            invoice.invoiceId,
            invoice.token,
        ).filter { event -> event.matches(invoice) && BigInteger(event.sweptAmount).signum() > 0 }
        val cumulative = matchingEvents.fold(BigInteger.ZERO) { total, event ->
            total + BigInteger(event.sweptAmount)
        }
        val latest = matchingEvents.lastOrNull() ?: return 0
        return if (cumulative >= BigInteger(invoice.expectedAmount)) {
            invoiceDao.restoreSettledFromProof(
                invoiceId = invoice.invoiceId,
                settlementId = latest.settlementId,
                txHash = latest.transactionHash,
                settledAtBlock = latest.blockNumber,
                lastObservedBlock = observedBlock,
            )
        } else {
            invoiceDao.updateSweptInvoiceObservation(
                invoiceId = invoice.invoiceId,
                sourceStatus = InvoiceStatus.LATE_PAYMENT_READY,
                observedLateAmount = "0",
                status = InvoiceStatus.PARTIALLY_SETTLED,
                firstDetectedBlock = null,
                firstDetectedBlockHash = null,
                lastObservedBlock = observedBlock,
                confirmedAtBlock = null,
            )
        }
    }

    private fun SettlementEvent.matches(invoice: Invoice): Boolean =
        receiverAddress.equals(invoice.receiver, true) &&
            expectedAmount == invoice.expectedAmount

    private fun Invoice.sameLifecycleStateExceptLateAttempt(other: Invoice): Boolean =
        copy(lateRecoveryLastAttemptAt = null) == other.copy(lateRecoveryLastAttemptAt = null)

    companion object {
        const val MAX_CANDIDATES_PER_PASS = 4
        private val RECONCILABLE_STATUSES = setOf(
            InvoiceStatus.EXPIRED,
            InvoiceStatus.PAID,
            InvoiceStatus.OVERPAID,
            InvoiceStatus.PARTIALLY_SETTLED,
            InvoiceStatus.SETTLED,
            InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED,
            InvoiceStatus.LATE_PAYMENT_CONFIRMING,
            InvoiceStatus.LATE_PAYMENT_READY,
        )

        private fun sampleReceiverBalance(
            invoice: Invoice,
            rpcEndpointResolver: RpcEndpointResolver,
        ): ReceiverBalanceSnapshot {
            val client = ReadOnlyRpcClient(
                invoice.toNetworkConfig(
                    rpcEndpointResolver.resolve(invoice.chainId, invoice.rpcUrl),
                ),
                connectTimeoutMillis = RPC_CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = RPC_READ_TIMEOUT_MILLIS,
            )
            val remoteChainId = client.chainId()
            require(remoteChainId == invoice.chainId) {
                "RPC chain ID $remoteChainId does not match invoice chain ID ${invoice.chainId}"
            }
            val token = EvmAddress.parse(invoice.token)
            val receiver = EvmAddress.parse(invoice.receiver)
            return sampleCanonicalReceiverBalance(
                blockNumber = client::blockNumber,
                blockHash = client::blockHash,
                tokenBalanceAt = { block -> client.tokenBalance(token, receiver, block) },
                firstDetectedBlock = invoice.firstDetectedBlock,
                lateFirstDetectedBlock = invoice.lateFirstDetectedBlock,
            )
        }

        private fun Invoice.toNetworkConfig(resolvedRpcUrl: String) = NetworkConfig(
            chainId = chainId,
            rpcUrl = resolvedRpcUrl,
            factory = EvmAddress.parse(factoryAddress),
            receiverImplementation = EvmAddress.parse(receiverImplementationAddress),
            vault = EvmAddress.parse(vaultAddress),
        )

        // A worst-case recovery sample performs eight bounded reads. It is retryable best-effort
        // work, so use a tight 550ms socket budget per read: at most 4.4s for the whole sample.
        internal const val MAX_RPC_READS_PER_SAMPLE = 8
        internal const val RPC_CONNECT_TIMEOUT_MILLIS = 250
        internal const val RPC_READ_TIMEOUT_MILLIS = 300
    }
}

internal fun sampleCanonicalReceiverBalance(
    blockNumber: () -> Long,
    blockHash: (Long) -> String?,
    tokenBalanceAt: (Long) -> BigInteger,
    firstDetectedBlock: Long?,
    lateFirstDetectedBlock: Long?,
): ReceiverBalanceSnapshot {
    val block = blockNumber()
    val blockHashBefore = requireNotNull(blockHash(block)) {
        "Canonical block $block is unavailable"
    }
    val balance = tokenBalanceAt(block)
    val sampledBlockHash = requireNotNull(blockHash(block)) {
        "Canonical block $block became unavailable while sampling receiver balance"
    }
    require(sampledBlockHash.equals(blockHashBefore, true)) {
        "Canonical block $block changed while sampling receiver balance"
    }
    fun canonicalHashAt(cursor: Long?): String? = cursor?.let {
        if (it == block) sampledBlockHash else runCatching { blockHash(it) }.getOrNull()
    }
    val canonicalFirstDetectedBlockHash = canonicalHashAt(firstDetectedBlock)
    val canonicalLateFirstDetectedBlockHash = canonicalHashAt(lateFirstDetectedBlock)
    val finalBlockHash = requireNotNull(blockHash(block)) {
        "Canonical block $block became unavailable after validating confirmation cursors"
    }
    require(finalBlockHash.equals(sampledBlockHash, true)) {
        "Canonical block $block changed while validating confirmation cursors"
    }
    return ReceiverBalanceSnapshot(
        balance = balance,
        blockNumber = block,
        blockHash = finalBlockHash,
        canonicalFirstDetectedBlockHash = canonicalFirstDetectedBlockHash,
        canonicalLateFirstDetectedBlockHash = canonicalLateFirstDetectedBlockHash,
    )
}

internal fun reconcilePublishedInvoice(
    invoice: Invoice,
    snapshot: ReceiverBalanceSnapshot,
): ReconciledInvoiceObservation {
    require(snapshot.balance.signum() >= 0) { "Receiver balance cannot be negative" }
    require(snapshot.blockNumber >= 0) { "Observed block cannot be negative" }
    val requiredConfirmations = invoice.confirmationBlocks.coerceAtLeast(1)
    val expectedAmount = BigInteger(invoice.expectedAmount)

    return when (invoice.status) {
        InvoiceStatus.EXPIRED -> {
            val preservedCursor = if (snapshot.balance >= expectedAmount) {
                invoice.firstDetectedBlock?.takeIf { cursor ->
                    BigInteger(invoice.receivedAmount) == snapshot.balance &&
                        cursor <= snapshot.blockNumber &&
                        canonicalCursorMatches(
                            invoice.firstDetectedBlockHash,
                            snapshot.canonicalFirstDetectedBlockHash,
                        )
                }
            } else {
                null
            }
            val fundedAt = if (snapshot.balance >= expectedAmount) {
                preservedCursor ?: snapshot.blockNumber
            } else null
            val fundedAtHash = if (fundedAt == null) null else {
                if (preservedCursor != null) invoice.firstDetectedBlockHash else snapshot.blockHash
            }
            val confirmations = fundedAt?.let {
                (snapshot.blockNumber - it + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } ?: 0
            val status = when {
                snapshot.balance.signum() == 0 -> InvoiceStatus.EXPIRED
                snapshot.balance < expectedAmount -> InvoiceStatus.PARTIAL
                confirmations < requiredConfirmations -> InvoiceStatus.CONFIRMING
                snapshot.balance > expectedAmount -> InvoiceStatus.OVERPAID
                else -> InvoiceStatus.PAID
            }
            ReconciledInvoiceObservation(
                receivedAmount = snapshot.balance,
                status = status,
                firstDetectedBlock = fundedAt,
                firstDetectedBlockHash = fundedAtHash,
                lastObservedBlock = snapshot.blockNumber,
                confirmedAtBlock = snapshot.blockNumber.takeIf {
                    status == InvoiceStatus.PAID || status == InvoiceStatus.OVERPAID
                },
            )
        }

        InvoiceStatus.PAID,
        InvoiceStatus.OVERPAID -> {
            val storedBalance = BigInteger(invoice.receivedAmount)
            val preservedCursor = invoice.firstDetectedBlock?.takeIf { cursor ->
                snapshot.balance == storedBalance && snapshot.balance >= expectedAmount &&
                    cursor <= snapshot.blockNumber &&
                    canonicalCursorMatches(
                        invoice.firstDetectedBlockHash,
                        snapshot.canonicalFirstDetectedBlockHash,
                    )
            }
            val fundedAt = if (snapshot.balance >= expectedAmount) {
                preservedCursor ?: snapshot.blockNumber
            } else null
            val fundedAtHash = if (fundedAt == null) null else {
                if (preservedCursor != null) invoice.firstDetectedBlockHash else snapshot.blockHash
            }
            val confirmations = fundedAt?.let { cursor ->
                (snapshot.blockNumber - cursor + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } ?: 0
            val status = when {
                snapshot.balance.signum() == 0 -> InvoiceStatus.WAITING
                snapshot.balance < expectedAmount -> InvoiceStatus.PARTIAL
                confirmations < requiredConfirmations -> InvoiceStatus.CONFIRMING
                snapshot.balance > expectedAmount -> InvoiceStatus.OVERPAID
                else -> InvoiceStatus.PAID
            }
            ReconciledInvoiceObservation(
                receivedAmount = snapshot.balance,
                status = status,
                firstDetectedBlock = fundedAt,
                firstDetectedBlockHash = fundedAtHash,
                lastObservedBlock = snapshot.blockNumber,
                confirmedAtBlock = when {
                    status != InvoiceStatus.PAID && status != InvoiceStatus.OVERPAID -> null
                    preservedCursor != null -> invoice.confirmedAtBlock ?: snapshot.blockNumber
                    else -> snapshot.blockNumber
                },
            )
        }

        InvoiceStatus.PARTIALLY_SETTLED,
        InvoiceStatus.SETTLED,
        InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED,
        InvoiceStatus.LATE_PAYMENT_CONFIRMING,
        InvoiceStatus.LATE_PAYMENT_READY -> {
            if (snapshot.balance.signum() == 0) {
                check(invoice.status != InvoiceStatus.LATE_PAYMENT_READY) {
                    "A vanished ready balance requires cumulative proof-ledger restoration"
                }
                val provenStatus = when {
                    invoice.settlementAmbiguous ||
                        invoice.status == InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED ->
                        InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED
                    invoice.settlementId == null -> InvoiceStatus.PARTIALLY_SETTLED
                    else -> InvoiceStatus.SETTLED
                }
                ReconciledInvoiceObservation(
                    receivedAmount = BigInteger.ZERO,
                    status = provenStatus,
                    firstDetectedBlock = null,
                    firstDetectedBlockHash = null,
                    lastObservedBlock = snapshot.blockNumber,
                    confirmedAtBlock = null,
                )
            } else {
                if (invoice.status == InvoiceStatus.LATE_PAYMENT_READY) {
                    val cursorStillCanonical = invoice.lateFirstDetectedBlock != null &&
                        invoice.lateFirstDetectedBlock <= snapshot.blockNumber &&
                        canonicalCursorMatches(
                            invoice.lateFirstDetectedBlockHash,
                            snapshot.canonicalLateFirstDetectedBlockHash,
                        )
                    if (snapshot.balance != BigInteger(invoice.pendingLateAmount) ||
                        !cursorStillCanonical
                    ) {
                        return ReconciledInvoiceObservation(
                            receivedAmount = snapshot.balance,
                            status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                            firstDetectedBlock = snapshot.blockNumber,
                            firstDetectedBlockHash = snapshot.blockHash,
                            lastObservedBlock = snapshot.blockNumber,
                            confirmedAtBlock = null,
                        )
                    }
                    return ReconciledInvoiceObservation(
                        receivedAmount = snapshot.balance,
                        status = InvoiceStatus.LATE_PAYMENT_READY,
                        firstDetectedBlock = requireNotNull(invoice.lateFirstDetectedBlock),
                        firstDetectedBlockHash = requireNotNull(invoice.lateFirstDetectedBlockHash),
                        lastObservedBlock = snapshot.blockNumber,
                        confirmedAtBlock = invoice.lateConfirmedAtBlock ?: snapshot.blockNumber,
                    )
                }
                val fundedAt = invoice.lateFirstDetectedBlock?.takeIf {
                    invoice.status == InvoiceStatus.LATE_PAYMENT_CONFIRMING &&
                        BigInteger(invoice.pendingLateAmount).signum() > 0 &&
                        BigInteger(invoice.pendingLateAmount) == snapshot.balance &&
                        it <= snapshot.blockNumber &&
                        canonicalCursorMatches(
                            invoice.lateFirstDetectedBlockHash,
                            snapshot.canonicalLateFirstDetectedBlockHash,
                        )
                } ?: snapshot.blockNumber
                val fundedAtHash = if (fundedAt == snapshot.blockNumber) {
                    snapshot.blockHash
                } else {
                    requireNotNull(invoice.lateFirstDetectedBlockHash)
                }
                val confirmations = (snapshot.blockNumber - fundedAt + 1)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val ready = confirmations >= requiredConfirmations
                ReconciledInvoiceObservation(
                    receivedAmount = snapshot.balance,
                    status = if (ready) {
                        InvoiceStatus.LATE_PAYMENT_READY
                    } else {
                        InvoiceStatus.LATE_PAYMENT_CONFIRMING
                    },
                    firstDetectedBlock = fundedAt,
                    firstDetectedBlockHash = fundedAtHash,
                    lastObservedBlock = snapshot.blockNumber,
                    confirmedAtBlock = snapshot.blockNumber.takeIf { ready },
                )
            }
        }

        else -> error("Invoice ${invoice.status} is not eligible for late reconciliation")
    }
}

private fun canonicalCursorMatches(savedHash: String?, canonicalHash: String?): Boolean =
    savedHash != null && canonicalHash?.equals(savedHash, ignoreCase = true) == true
