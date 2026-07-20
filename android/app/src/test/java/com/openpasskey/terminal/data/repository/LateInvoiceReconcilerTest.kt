package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.SettlementEvent
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigInteger

class LateInvoiceReconcilerTest {
    @Test
    fun closedPublishedQrStaysClosedAtZeroAndReopensWhenFunded() {
        val closed = invoice(InvoiceStatus.EXPIRED)
        val empty = reconcilePublishedInvoice(
            closed,
            snapshot(BigInteger.ZERO, 100),
        )
        assertEquals(InvoiceStatus.EXPIRED, empty.status)
        assertEquals(100L, empty.lastObservedBlock)

        val detected = reconcilePublishedInvoice(
            closed,
            snapshot(BigInteger("100"), 101),
        )
        assertEquals(InvoiceStatus.CONFIRMING, detected.status)
        assertEquals(101L, detected.firstDetectedBlock)
        assertNull(detected.confirmedAtBlock)

        val confirmed = reconcilePublishedInvoice(
            closed.copy(
                receivedAmount = "100",
                firstDetectedBlock = 101,
                firstDetectedBlockHash = canonicalHash(101),
            ),
            snapshot(BigInteger("100"), 102, firstCursorBlock = 101),
        )
        assertEquals(InvoiceStatus.PAID, confirmed.status)
        assertEquals(102L, confirmed.confirmedAtBlock)
    }

    @Test
    fun increasedExpiredReceiverBalanceStartsANewConfirmationWindow() {
        val partialAtOneHundred = invoice(
            status = InvoiceStatus.EXPIRED,
            receivedAmount = "10",
            firstDetectedBlock = 100,
        )

        val changed = reconcilePublishedInvoice(
            partialAtOneHundred,
            snapshot(BigInteger("100"), 101),
        )
        assertEquals(InvoiceStatus.CONFIRMING, changed.status)
        assertEquals(101L, changed.firstDetectedBlock)
        assertNull(changed.confirmedAtBlock)

        val confirmed = reconcilePublishedInvoice(
            partialAtOneHundred.copy(
                receivedAmount = "100",
                firstDetectedBlock = 101,
                firstDetectedBlockHash = canonicalHash(101),
            ),
            snapshot(BigInteger("100"), 102, firstCursorBlock = 101),
        )
        assertEquals(InvoiceStatus.PAID, confirmed.status)
        assertEquals(102L, confirmed.confirmedAtBlock)
    }

    @Test
    fun expiredReceiverReorgedThresholdRestartsConfirmationWindow() {
        val expired = invoice(
            status = InvoiceStatus.EXPIRED,
            receivedAmount = "100",
            firstDetectedBlock = 100,
        )

        val changed = reconcilePublishedInvoice(
            expired,
            snapshot(
                BigInteger("100"),
                102,
                firstCursorBlock = 100,
                canonicalFirstDetectedBlockHash = OTHER_BLOCK_HASH,
            ),
        )

        assertEquals(InvoiceStatus.CONFIRMING, changed.status)
        assertEquals(102L, changed.firstDetectedBlock)
        assertEquals(canonicalHash(102), changed.firstDetectedBlockHash)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun anyPositiveRepeatPaymentConfirmsAfterPriorFullSettlement() {
        val settled = invoice(
            status = InvoiceStatus.SETTLED,
            settlementId = "settlement-1",
            settledTxHash = TX_HASH,
        )
        val detected = reconcilePublishedInvoice(
            settled,
            snapshot(BigInteger.ONE, 200),
        )
        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, detected.status)
        assertEquals(200L, detected.firstDetectedBlock)

        val confirmed = reconcilePublishedInvoice(
            settled.copy(
                status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                pendingLateAmount = "1",
                lateFirstDetectedBlock = 200,
                lateFirstDetectedBlockHash = canonicalHash(200),
            ),
            snapshot(BigInteger.ONE, 201, lateCursorBlock = 200),
        )
        assertEquals(InvoiceStatus.LATE_PAYMENT_READY, confirmed.status)
        assertEquals(BigInteger.ONE, confirmed.receivedAmount)
        assertEquals(201L, confirmed.confirmedAtBlock)
    }

    @Test
    fun changingRepeatAmountDuringConfirmationRestartsItsThreshold() {
        val confirming = invoice(
            status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
            settlementId = "settlement-1",
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 200,
            lateFirstDetectedBlockHash = canonicalHash(200),
        )

        val changed = reconcilePublishedInvoice(
            confirming,
            snapshot(BigInteger("2"), 201, lateCursorBlock = 200),
        )

        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, changed.status)
        assertEquals(201L, changed.firstDetectedBlock)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun vanishedUnconfirmedLateBalanceReturnsToItsPriorProvenState() {
        val priorFull = reconcilePublishedInvoice(
            invoice(
                status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                settlementId = "settlement-1",
                settledTxHash = TX_HASH,
            ).copy(
                pendingLateAmount = "5",
                lateFirstDetectedBlock = 300,
                lateFirstDetectedBlockHash = canonicalHash(300),
            ),
            snapshot(BigInteger.ZERO, 301, lateCursorBlock = 300),
        )
        assertEquals(InvoiceStatus.SETTLED, priorFull.status)
        assertNull(priorFull.firstDetectedBlock)

        val priorPartial = reconcilePublishedInvoice(
            invoice(
                status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                settlementId = null,
                settledTxHash = TX_HASH,
            ).copy(
                pendingLateAmount = "5",
                lateFirstDetectedBlock = 300,
                lateFirstDetectedBlockHash = canonicalHash(300),
            ),
            snapshot(BigInteger.ZERO, 301, lateCursorBlock = 300),
        )
        assertEquals(InvoiceStatus.PARTIALLY_SETTLED, priorPartial.status)
    }

    @Test
    fun confirmedRepeatValueBelowOriginalAmountIsSettlementEligibleWithPriorProof() {
        val late = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settlementId = null,
            settledTxHash = TX_HASH,
        ).copy(pendingLateAmount = "1", lateConfirmedAtBlock = 401)

        requireSettlementObservation(late, previouslyProven = BigInteger("100"))
        assertThrows(IllegalArgumentException::class.java) {
            requireSettlementObservation(late, previouslyProven = BigInteger.ZERO)
        }
        requireSettlementObservation(
            late.copy(settlementAmbiguous = true),
            previouslyProven = BigInteger.ZERO,
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireSettlementObservation(late.copy(lateConfirmedAtBlock = null), BigInteger("100"))
        }
    }

    @Test
    fun settlementPreflightRevalidatesPersistedActiveAndLateConfirmationCursors() {
        val paid = invoice(
            status = InvoiceStatus.PAID,
            receivedAmount = "100",
            firstDetectedBlock = 100,
        ).copy(confirmedAtBlock = 101)
        requireCanonicalSettlementCursor(paid) { block -> canonicalHash(block) }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalSettlementCursor(paid) { OTHER_BLOCK_HASH }
        }

        val late = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 200,
            lateFirstDetectedBlockHash = canonicalHash(200),
            lateConfirmedAtBlock = 201,
        )
        requireCanonicalSettlementCursor(late) { block -> canonicalHash(block) }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalSettlementCursor(late) { null }
        }
    }

    @Test
    fun readyLatePaymentRestartsConfirmationWhenItsBalanceChanges() {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 400,
            lateFirstDetectedBlockHash = canonicalHash(400),
            lateConfirmedAtBlock = 401,
        )

        val refreshed = reconcilePublishedInvoice(
            ready,
            snapshot(BigInteger("2"), 402, lateCursorBlock = 400),
        )

        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, refreshed.status)
        assertEquals(BigInteger("2"), refreshed.receivedAmount)
        assertEquals(402L, refreshed.firstDetectedBlock)
        assertNull(refreshed.confirmedAtBlock)
    }

    @Test
    fun readyLatePaymentStaysReadyWhenExactConfirmedBalanceIsUnchanged() {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 400,
            lateFirstDetectedBlockHash = canonicalHash(400),
            lateConfirmedAtBlock = 401,
        )

        val refreshed = reconcilePublishedInvoice(
            ready,
            snapshot(BigInteger.ONE, 402, lateCursorBlock = 400),
        )

        assertEquals(InvoiceStatus.LATE_PAYMENT_READY, refreshed.status)
        assertEquals(400L, refreshed.firstDetectedBlock)
        assertEquals(401L, refreshed.confirmedAtBlock)
    }

    @Test
    fun changedInitialConfirmedBalanceMustEarnANewConfirmationWindow() {
        val paid = invoice(InvoiceStatus.PAID, receivedAmount = "100").copy(
            firstDetectedBlock = 500,
            confirmedAtBlock = 501,
        )

        val changed = reconcilePublishedInvoice(
            paid,
            snapshot(BigInteger("101"), 502, firstCursorBlock = 500),
        )

        assertEquals(InvoiceStatus.CONFIRMING, changed.status)
        assertEquals(502L, changed.firstDetectedBlock)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun paidReceiverReorgedThresholdReturnsToConfirming() {
        val paid = invoice(InvoiceStatus.PAID, receivedAmount = "100").copy(
            firstDetectedBlock = 500,
            firstDetectedBlockHash = canonicalHash(500),
            confirmedAtBlock = 501,
        )

        val changed = reconcilePublishedInvoice(
            paid,
            snapshot(
                BigInteger("100"),
                502,
                firstCursorBlock = 500,
                canonicalFirstDetectedBlockHash = OTHER_BLOCK_HASH,
            ),
        )

        assertEquals(InvoiceStatus.CONFIRMING, changed.status)
        assertEquals(502L, changed.firstDetectedBlock)
        assertEquals(canonicalHash(502), changed.firstDetectedBlockHash)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun lateConfirmingReorgedThresholdRestartsAtCurrentBlock() {
        val confirming = invoice(
            status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 600,
            lateFirstDetectedBlockHash = canonicalHash(600),
        )

        val changed = reconcilePublishedInvoice(
            confirming,
            snapshot(
                BigInteger.ONE,
                602,
                lateCursorBlock = 600,
                canonicalLateFirstDetectedBlockHash = OTHER_BLOCK_HASH,
            ),
        )

        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, changed.status)
        assertEquals(602L, changed.firstDetectedBlock)
        assertEquals(canonicalHash(602), changed.firstDetectedBlockHash)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun lateReadyReorgedThresholdReturnsToConfirming() {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 610,
            lateFirstDetectedBlockHash = canonicalHash(610),
            lateConfirmedAtBlock = 611,
        )

        val changed = reconcilePublishedInvoice(
            ready,
            snapshot(
                BigInteger.ONE,
                612,
                lateCursorBlock = 610,
                canonicalLateFirstDetectedBlockHash = OTHER_BLOCK_HASH,
            ),
        )

        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, changed.status)
        assertEquals(612L, changed.firstDetectedBlock)
        assertEquals(canonicalHash(612), changed.firstDetectedBlockHash)
        assertNull(changed.confirmedAtBlock)
    }

    @Test
    fun vanishedReadyBalanceRestoresCanonicalFullProofInsteadOfBlockingForever() = runBlocking {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settlementId = null,
            settledTxHash = TX_HASH,
        ).copy(
            pendingLateAmount = "1",
            lateFirstDetectedBlock = 700,
            lateConfirmedAtBlock = 701,
        )
        var restored: Array<out Any?>? = null
        val invoiceDao = daoProxy<InvoiceDao> { method, arguments ->
            when (method) {
                "getLateReconciliationCandidates" -> listOf(ready)
                "getById" -> ready
                "markLateRecoveryAttempt" -> 1
                "restoreSettledFromProof" -> {
                    restored = arguments
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val eventDao = daoProxy<SettlementEventDao> { method, _ ->
            if (method == "getByInvoiceScope") listOf(
                proofEvent("40"),
                proofEvent("60").copy(
                    eventId = "84532:$SECOND_TX_HASH:2",
                    settlementId = "settlement-proof-2",
                    transactionHash = SECOND_TX_HASH,
                    blockNumber = 700,
                    logIndex = 2,
                ),
            )
            else error("Unexpected SettlementEventDao call: $method")
        }
        val reconciler = LateInvoiceReconciler(
            invoiceDao,
            eventDao,
            TerminalLifecycleGate(),
            LateReceiverSampler { snapshot(BigInteger.ZERO, 702) },
        )

        assertEquals(1, reconciler.reconcileOnce(limit = 1))
        assertEquals(ready.invoiceId, restored?.get(0))
        assertEquals("settlement-proof-2", restored?.get(1))
        assertEquals(SECOND_TX_HASH, restored?.get(2))
        assertEquals(700L, restored?.get(3))
        assertEquals(702L, restored?.get(4))
    }

    @Test
    fun vanishedReadyBalanceWithOnlyPartialCumulativeProofReturnsToPartialState() = runBlocking {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settlementId = null,
            settledTxHash = TX_HASH,
        ).copy(pendingLateAmount = "1", lateConfirmedAtBlock = 801)
        var restoredStatus: InvoiceStatus? = null
        val invoiceDao = daoProxy<InvoiceDao> { method, arguments ->
            when (method) {
                "getLateReconciliationCandidates" -> listOf(ready)
                "getById" -> ready
                "markLateRecoveryAttempt" -> 1
                "updateSweptInvoiceObservation" -> {
                    restoredStatus = arguments?.get(3) as InvoiceStatus
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val eventDao = daoProxy<SettlementEventDao> { method, _ ->
            if (method == "getByInvoiceScope") listOf(proofEvent("50"))
            else error("Unexpected SettlementEventDao call: $method")
        }
        val reconciler = LateInvoiceReconciler(
            invoiceDao,
            eventDao,
            TerminalLifecycleGate(),
            LateReceiverSampler { snapshot(BigInteger.ZERO, 802) },
        )

        assertEquals(1, reconciler.reconcileOnce(limit = 1))
        assertEquals(InvoiceStatus.PARTIALLY_SETTLED, restoredStatus)
    }

    @Test
    fun ambiguousReviewCanConfirmPositiveRecoveryWithoutPriorProof() {
        val review = invoice(
            status = InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED,
            settlementId = "ambiguous-settlement",
            settledTxHash = TX_HASH,
            settlementAmbiguous = true,
        )
        val detected = reconcilePublishedInvoice(
            review,
            snapshot(BigInteger.ONE, 810),
        )
        assertEquals(InvoiceStatus.LATE_PAYMENT_CONFIRMING, detected.status)
        assertEquals(810L, detected.firstDetectedBlock)

        val ready = reconcilePublishedInvoice(
            review.copy(
                status = InvoiceStatus.LATE_PAYMENT_CONFIRMING,
                pendingLateAmount = "1",
                lateFirstDetectedBlock = 810,
                lateFirstDetectedBlockHash = canonicalHash(810),
            ),
            snapshot(BigInteger.ONE, 811, lateCursorBlock = 810),
        )
        assertEquals(InvoiceStatus.LATE_PAYMENT_READY, ready.status)

        requireSettlementObservation(
            review.copy(
                status = InvoiceStatus.LATE_PAYMENT_READY,
                settlementId = null,
                pendingLateAmount = "1",
                lateConfirmedAtBlock = 811,
            ),
            previouslyProven = BigInteger.ZERO,
        )
    }

    @Test
    fun vanishedAmbiguousReadyBalanceReturnsToReviewWithoutTrustingOldProof() = runBlocking {
        val ready = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settlementId = null,
            settledTxHash = TX_HASH,
            settlementAmbiguous = true,
        ).copy(pendingLateAmount = "1", lateConfirmedAtBlock = 820)
        var restored = false
        val invoiceDao = daoProxy<InvoiceDao> { method, _ ->
            when (method) {
                "getLateReconciliationCandidates" -> listOf(ready)
                "getById" -> ready
                "markLateRecoveryAttempt" -> 1
                "restoreSettlementReviewRequired" -> {
                    restored = true
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val eventDao = daoProxy<SettlementEventDao> { method, _ ->
            error("Ambiguous ready recovery must not trust old proof through $method")
        }
        val reconciler = LateInvoiceReconciler(
            invoiceDao,
            eventDao,
            TerminalLifecycleGate(),
            LateReceiverSampler { snapshot(BigInteger.ZERO, 821) },
        )

        assertEquals(1, reconciler.reconcileOnce(limit = 1))
        assertTrue(restored)
    }

    @Test
    fun staleSnapshotIsDiscardedWhenSettlementChangesLifecycleStateDuringRpc() = runBlocking {
        val sampled = invoice(
            status = InvoiceStatus.LATE_PAYMENT_READY,
            settlementId = null,
            settledTxHash = TX_HASH,
        ).copy(pendingLateAmount = "1", lateConfirmedAtBlock = 900)
        val settledDuringSample = sampled.copy(
            status = InvoiceStatus.SETTLED,
            settlementId = "new-settlement",
            pendingLateAmount = "0",
            lateConfirmedAtBlock = null,
        )
        var reads = 0
        val dao = daoProxy<InvoiceDao> { method, _ ->
            when (method) {
                "getLateReconciliationCandidates" -> listOf(sampled)
                "getById" -> if (reads++ == 0) sampled else settledDuringSample
                "markLateRecoveryAttempt" -> 1
                else -> error("Stale snapshot must not persist through $method")
            }
        }
        val reconciler = LateInvoiceReconciler(
            dao,
            emptyEventDao(),
            TerminalLifecycleGate(),
            LateReceiverSampler { snapshot(BigInteger.ONE, 901) },
        )

        assertEquals(0, reconciler.reconcileOnce(limit = 1))
    }

    @Test
    fun boundedPassIsolatesRpcFailureAndContinuesToTheNextRotatingCandidate() = runBlocking {
        val first = invoice(InvoiceStatus.EXPIRED)
        val second = first.copy(invoiceId = "0x" + "22".repeat(32), createdAt = 2)
        var requestedLimit = 0
        val updated = mutableListOf<String>()
        val dao = daoProxy<InvoiceDao> { method, arguments ->
            when (method) {
                "getLateReconciliationCandidates" -> {
                    requestedLimit = arguments?.get(0) as Int
                    listOf(first, second)
                }
                "getById" -> listOf(first, second).first { it.invoiceId == arguments?.get(0) }
                "markLateRecoveryAttempt" -> 1
                "updateClosedInvoiceObservation" -> {
                    updated += arguments?.get(0) as String
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val reconciler = LateInvoiceReconciler(
            dao,
            emptyEventDao(),
            TerminalLifecycleGate(),
            LateReceiverSampler { candidate ->
                if (candidate.invoiceId == first.invoiceId) error("RPC unavailable")
                snapshot(BigInteger.ZERO, 500)
            },
        )

        assertEquals(1, reconciler.reconcileOnce(limit = 2))
        assertEquals(2, requestedLimit)
        assertEquals(listOf(second.invoiceId), updated)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                reconciler.reconcileOnce(LateInvoiceReconciler.MAX_CANDIDATES_PER_PASS + 1)
            }
        }
        Unit
    }

    @Test
    fun failingFirstBatchCannotStarveLaterPublishedReceivers() = runBlocking {
        val candidates = (1..6).map { index ->
            invoice(InvoiceStatus.EXPIRED).copy(
                invoiceId = "0x" + index.toString(16).padStart(64, '0'),
                createdAt = index.toLong(),
            )
        }
        val updated = mutableListOf<String>()
        val attempts = mutableMapOf<String, Long?>()
        val observedBatches = mutableListOf<List<String>>()
        val dao = daoProxy<InvoiceDao> { method, arguments ->
            when (method) {
                "getLateReconciliationCandidates" -> {
                    val limit = arguments?.get(0) as Int
                    val batch = candidates.sortedWith(
                        compareBy<Invoice> { attempts[it.invoiceId] != null }
                            .thenBy { attempts[it.invoiceId] ?: Long.MIN_VALUE }
                            .thenBy(Invoice::createdAt),
                    ).take(limit)
                    observedBatches += batch.map(Invoice::invoiceId)
                    batch
                }
                "getById" -> candidates.first { it.invoiceId == arguments?.get(0) }
                "markLateRecoveryAttempt" -> {
                    attempts[arguments?.get(0) as String] = arguments[2] as Long
                    1
                }
                "updateClosedInvoiceObservation" -> {
                    updated += arguments?.get(0) as String
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val reconciler = LateInvoiceReconciler(
            dao,
            emptyEventDao(),
            TerminalLifecycleGate(),
            LateReceiverSampler { candidate ->
                if (candidate.createdAt <= 4) error("RPC unavailable")
                snapshot(BigInteger.ZERO, 600)
            },
        )

        assertEquals(0, reconciler.reconcileOnce())
        assertEquals(2, reconciler.reconcileOnce())
        assertEquals(candidates.take(4).map(Invoice::invoiceId), observedBatches.first())
        assertEquals(candidates.drop(4).map(Invoice::invoiceId), observedBatches.last().take(2))
        assertEquals(candidates.drop(4).map(Invoice::invoiceId), updated)
    }

    @Test
    fun reconciliationNeverSwallowsCoroutineCancellation() {
        val candidate = invoice(InvoiceStatus.EXPIRED)
        var attemptMarked = false
        val dao = daoProxy<InvoiceDao> { method, _ ->
            when (method) {
                "getLateReconciliationCandidates" -> listOf(candidate)
                "getById" -> candidate
                "markLateRecoveryAttempt" -> {
                    attemptMarked = true
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val reconciler = LateInvoiceReconciler(
            dao,
            emptyEventDao(),
            TerminalLifecycleGate(),
            LateReceiverSampler { throw CancellationException("stop") },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { reconciler.reconcileOnce() }
        }
        assertTrue(attemptMarked)
    }

    @Test
    fun durableOpenRecoveryAttemptsBoundEachPassAndAdvancePastFailingPrefix() = runBlocking {
        val candidates = (1..5).map { index ->
            invoice(InvoiceStatus.WAITING).copy(
                invoiceId = "0x" + (index + 20).toString(16).padStart(64, '0'),
                createdAt = index.toLong(),
            )
        }
        val attempts = mutableMapOf<String, Long?>()
        val batches = mutableListOf<List<String>>()
        var clock = 1L
        val dao = daoProxy<InvoiceDao> { method, arguments ->
            when (method) {
                "getOpenRecoveryCandidates" -> {
                    val batch = candidates.sortedWith(
                        compareBy<Invoice> { attempts[it.invoiceId] != null }
                            .thenBy { attempts[it.invoiceId] ?: Long.MIN_VALUE }
                            .thenBy(Invoice::createdAt),
                    ).take(requireNotNull(arguments)[0] as Int)
                    batches += batch.map(Invoice::invoiceId)
                    batch
                }
                "markOpenRecoveryAttempt" -> {
                    val args = requireNotNull(arguments)
                    attempts[args[0] as String] = args[1] as Long
                    1
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }

        assertEquals(2, recoverOpenInvoiceBatch(dao, attemptedAt = { clock++ }) { error("offline") })
        // A new scheduler/process still advances because the attempts were persisted in Room.
        assertEquals(2, recoverOpenInvoiceBatch(dao, attemptedAt = { clock++ }) { error("offline") })
        assertEquals(candidates.take(2).map(Invoice::invoiceId), batches[0])
        assertEquals(candidates.drop(2).take(2).map(Invoice::invoiceId), batches[1])
        assertTrue(batches.all { it.size <= MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS })
    }

    @Test
    fun historicalInvoicePinsAreRevalidatedIndependentlyOfCurrentProvisioning() {
        val profile = KnownChainPolicy.requireProfile(84532)
        val historical = invoice(InvoiceStatus.PAID).copy(
            invoiceId = profile.fixtureInvoiceId.hex,
            receiver = profile.fixtureReceiver.value,
            networkName = profile.networkName,
            rpcUrl = "https://merchant-operational-rpc.example",
            factoryAddress = profile.factory.value,
            receiverImplementationAddress = profile.receiverImplementation.value,
            vaultAddress = profile.fixtureVault.value,
        )

        val pinned = requirePinnedHistoricalInvoiceSnapshot(historical)
        assertEquals(profile.rpcUrl, pinned.rpcUrl)
        assertEquals(profile.fixtureVault, pinned.vault)
        assertThrows(IllegalArgumentException::class.java) {
            requirePinnedHistoricalInvoiceSnapshot(historical.copy(networkName = "Mislabelled chain"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requirePinnedHistoricalInvoiceSnapshot(historical.copy(receiver = RECEIVER))
        }
    }

    @Test
    fun canonicalHeadReorgDuringCursorLookupRejectsReceiverSample() {
        val sampledHead = canonicalHash(900)
        val changedHead = "0x" + "ff".repeat(32)
        var currentHeadReads = 0

        assertThrows(IllegalArgumentException::class.java) {
            sampleCanonicalReceiverBalance(
                blockNumber = { 900 },
                blockHash = { block ->
                    if (block == 900L) {
                        currentHeadReads += 1
                        if (currentHeadReads < 3) sampledHead else changedHead
                    } else {
                        canonicalHash(block)
                    }
                },
                tokenBalanceAt = { block ->
                    assertEquals(900L, block)
                    BigInteger("100")
                },
                firstDetectedBlock = 898,
                lateFirstDetectedBlock = 899,
            )
        }
        assertEquals(3, currentHeadReads)
    }

    private fun invoice(
        status: InvoiceStatus,
        receivedAmount: String = "0",
        firstDetectedBlock: Long? = null,
        settlementId: String? = null,
        settledTxHash: String? = null,
        settlementAmbiguous: Boolean = false,
    ) = Invoice(
        invoiceId = INVOICE_ID,
        receiver = RECEIVER,
        token = TOKEN,
        tokenSymbol = "TEST",
        tokenDecimals = 18,
        expectedAmount = "100",
        receivedAmount = receivedAmount,
        status = status,
        createdAt = 1,
        chainId = 84532,
        networkName = "Base Sepolia",
        rpcUrl = "https://example.invalid",
        factoryAddress = FACTORY,
        receiverImplementationAddress = IMPLEMENTATION,
        vaultAddress = VAULT,
        confirmationBlocks = 2,
        erc681Uri = "ethereum:test",
        firstDetectedBlock = firstDetectedBlock,
        firstDetectedBlockHash = firstDetectedBlock?.let(::canonicalHash),
        settledTxHash = settledTxHash,
        settlementId = settlementId,
        settledAtBlock = settledTxHash?.let { 99 },
        settlementAmbiguous = settlementAmbiguous,
    )

    private fun proofEvent(sweptAmount: String) = SettlementEvent(
        eventId = "84532:$TX_HASH:1",
        settlementId = "settlement-proof",
        invoiceId = INVOICE_ID,
        chainId = 84532,
        transactionHash = TX_HASH,
        blockHash = "0x" + "bb".repeat(32),
        blockNumber = 699,
        logIndex = 1,
        receiverAddress = RECEIVER,
        vaultAddress = VAULT,
        tokenAddress = TOKEN,
        sweptAmount = sweptAmount,
        expectedAmount = "100",
        feeAmount = "0",
        recordedAt = 699,
    )

    private fun snapshot(
        balance: BigInteger,
        blockNumber: Long,
        firstCursorBlock: Long? = null,
        lateCursorBlock: Long? = null,
        canonicalFirstDetectedBlockHash: String? = firstCursorBlock?.let(::canonicalHash),
        canonicalLateFirstDetectedBlockHash: String? = lateCursorBlock?.let(::canonicalHash),
    ) = ReceiverBalanceSnapshot(
        balance = balance,
        blockNumber = blockNumber,
        blockHash = canonicalHash(blockNumber),
        canonicalFirstDetectedBlockHash = canonicalFirstDetectedBlockHash,
        canonicalLateFirstDetectedBlockHash = canonicalLateFirstDetectedBlockHash,
    )

    private fun canonicalHash(block: Long): String =
        "0x" + block.toString(16).padStart(64, '0')

    private companion object {
        val INVOICE_ID = "0x" + "11".repeat(32)
        const val RECEIVER = "0x1111111111111111111111111111111111111111"
        const val TOKEN = "0x2222222222222222222222222222222222222222"
        const val FACTORY = "0x3333333333333333333333333333333333333333"
        const val IMPLEMENTATION = "0x4444444444444444444444444444444444444444"
        const val VAULT = "0x5555555555555555555555555555555555555555"
        val TX_HASH = "0x" + "aa".repeat(32)
        val SECOND_TX_HASH = "0x" + "cc".repeat(32)
        val OTHER_BLOCK_HASH = "0x" + "ff".repeat(32)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> daoProxy(
        crossinline invoke: (method: String, arguments: Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, arguments -> invoke(method.name, arguments) } as T

    private fun emptyEventDao(): SettlementEventDao = daoProxy<SettlementEventDao> { method, _ ->
        if (method == "getByInvoiceScope") emptyList<SettlementEvent>()
        else error("Unexpected SettlementEventDao call: $method")
    }
}
