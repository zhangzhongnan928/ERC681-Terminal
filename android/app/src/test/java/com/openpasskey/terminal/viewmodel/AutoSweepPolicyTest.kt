package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSweepPolicyTest {
    @Test
    fun canonicalPaidAndOverpaidRowsWithIncomingEvidenceAreEligible() {
        val paid = eligibleInvoice()
        val overpaid = eligibleInvoice(
            invoiceId = "invoice-overpaid",
            status = InvoiceStatus.OVERPAID,
        ).copy(receivedAmount = "125")

        assertEquals(paid.invoiceId, selectAutoSweepCandidate(listOf(paid))?.invoiceId)
        assertEquals(overpaid.invoiceId, selectAutoSweepCandidate(listOf(overpaid))?.invoiceId)
    }

    @Test
    fun migratedUnconfirmedOrIncompleteEvidenceRowsAreNeverSelected() {
        val eligible = eligibleInvoice()
        val ineligible = listOf(
            eligible.copy(receiptAutoPrintEligible = false),
            eligible.copy(receiptNumber = 0),
            eligible.copy(status = InvoiceStatus.CONFIRMING),
            eligible.copy(confirmedAtBlock = 102),
            eligible.copy(paymentTxHash = null),
            eligible.copy(paymentPayerAddress = null),
            eligible.copy(paymentPayerAddress = "0x1234"),
            eligible.copy(paymentPayerAddress = "0x" + "0".repeat(40)),
            eligible.copy(paymentBlockNumber = null),
            eligible.copy(paymentBlockHash = null),
            eligible.copy(paidAt = null),
            eligible.copy(publishedAtBlockHash = null),
            eligible.copy(firstDetectedBlockHash = null),
            eligible.copy(paymentBlockNumber = 100),
            eligible.copy(paymentBlockNumber = 103),
            eligible.copy(chainId = 1),
            eligible.copy(settlementId = "active-settlement"),
            eligible.copy(settledTxHash = hash('9')),
        )

        ineligible.forEach { invoice ->
            assertNull("Unexpectedly selected ${invoice.invoiceId}: $invoice", invoice.autoSweepFingerprint())
        }
    }

    @Test
    fun latePaymentIsManualUntilItsOwnIncomingTransactionEvidenceCanBePersisted() {
        val late = eligibleInvoice(status = InvoiceStatus.LATE_PAYMENT_READY).copy(
            settledTxHash = hash('8'),
            pendingLateAmount = "25",
            lateFirstDetectedBlock = 120,
            lateFirstDetectedBlockHash = hash('7'),
            lateConfirmedAtBlock = 121,
        )

        assertNull(late.autoSweepFingerprint())
        assertNull(late.copy(settledTxHash = null).autoSweepFingerprint())
        assertNull(late.copy(lateFirstDetectedBlockHash = null).autoSweepFingerprint())
        assertNull(late.copy(lateConfirmedAtBlock = 120).autoSweepFingerprint())
        assertNull(late.copy(settledTxHash = null, settlementAmbiguous = true).autoSweepFingerprint())
    }

    @Test
    fun dismissalSuppressesOnlyTheExactCanonicalPaymentEvidence() {
        val initial = eligibleInvoice()
        val first = requireNotNull(selectAutoSweepCandidate(listOf(initial)))

        assertNull(selectAutoSweepCandidate(listOf(initial), setOf(first.fingerprint)))

        val replacementEvidence = initial.copy(
            paymentTxHash = hash('6'),
            paymentBlockHash = hash('5'),
        )
        val replacement = requireNotNull(
            selectAutoSweepCandidate(listOf(replacementEvidence), setOf(first.fingerprint)),
        )
        assertNotEquals(first.fingerprint, replacement.fingerprint)

        val replacementPayer = requireNotNull(
            selectAutoSweepCandidate(
                listOf(initial.copy(paymentPayerAddress = "0x" + "4".repeat(40))),
                setOf(first.fingerprint),
            ),
        )
        assertNotEquals(first.fingerprint, replacementPayer.fingerprint)
    }

    @Test
    fun deferredOldCandidateDoesNotStarveANewerEligiblePayment() {
        val old = eligibleInvoice(invoiceId = "invoice-old")
        val newer = eligibleInvoice(invoiceId = "invoice-newer").copy(createdAt = 2_000)
        val oldFingerprint = requireNotNull(old.autoSweepFingerprint())
        val retryAfter = mapOf(oldFingerprint to 11_000L)

        val deferred = deferredAutoSweepFingerprints(
            retryAfterElapsedRealtimeMillis = retryAfter,
            nowElapsedRealtimeMillis = 10_000L,
        )
        assertEquals(
            newer.invoiceId,
            selectAutoSweepCandidate(listOf(old, newer), deferred)?.invoiceId,
        )
        assertTrue(
            oldFingerprint !in deferredAutoSweepFingerprints(
                retryAfterElapsedRealtimeMillis = retryAfter,
                nowElapsedRealtimeMillis = 11_000L,
            ),
        )
    }

    @Test
    fun navigationAndAttemptGatesRejectDuplicateOrStaleWork() {
        val navigation = OneShotSequenceGate()
        assertFalse(navigation.claim(requestedSequence = 0, currentSequence = 0))
        assertFalse(navigation.claim(requestedSequence = 1, currentSequence = 2))
        assertTrue(navigation.claim(requestedSequence = 2, currentSequence = 2))
        assertFalse(navigation.claim(requestedSequence = 2, currentSequence = 2))
        assertTrue(navigation.claim(requestedSequence = 3, currentSequence = 3))

        val attempts = AutoSweepAttemptGate()
        val cancelled = attempts.begin()
        attempts.invalidate()
        assertFalse(attempts.isCurrent(cancelled))
        val retry = attempts.begin()
        assertTrue(attempts.isCurrent(retry))
        assertFalse(attempts.isCurrent(cancelled))
    }

    @Test
    fun disablingRunningAutoPreparationUnblocksSettlementWithoutCancellingManualPreparation() {
        val automatic = SettlementUiState(
            autoSweepEnabled = true,
            preparing = true,
            message = "Auto-sweep preparation deferred",
            isError = true,
            autoSweepMessage = true,
        )
            .withAutoSweepDisabled(cancelledAutomaticPreparation = true)
        assertFalse(automatic.autoSweepEnabled)
        assertFalse(automatic.preparing)
        assertNull(automatic.message)
        assertFalse(automatic.isError)

        val manual = SettlementUiState(
            autoSweepEnabled = true,
            preparing = true,
            message = "Manual settlement preflight failed",
            isError = true,
        )
            .withAutoSweepDisabled(cancelledAutomaticPreparation = false)
        assertFalse(manual.autoSweepEnabled)
        assertTrue(manual.preparing)
        assertEquals("Manual settlement preflight failed", manual.message)
        assertTrue(manual.isError)
    }

    @Test
    fun dismissalSuppressesTheExactFingerprintThatOpenedTheAutomaticReview() {
        val fingerprint = requireNotNull(eligibleInvoice().autoSweepFingerprint())
        val reviewState = SettlementUiState(
            preparedAutomatically = true,
            preparedAutoSweepFingerprint = fingerprint,
        )

        assertEquals(fingerprint, reviewState.autoSweepFingerprintToSuppressOnDismiss())
        assertNull(
            reviewState.copy(preparedAutomatically = false)
                .autoSweepFingerprintToSuppressOnDismiss(),
        )
    }

    @Test
    fun dismissalCapacityFailureTurnsAutomationOffAndRequiresExplicitReenable() {
        val failed = SettlementUiState(
            autoSweepEnabled = true,
            preparing = true,
            preparedAutomatically = true,
            preparedAutoSweepFingerprint = "new-dismissal",
            autoSweepMessage = true,
            autoSweepSafetyDisableSequence = 4,
        ).withAutoSweepDismissalCapacityFailure()

        assertFalse(failed.autoSweepEnabled)
        assertFalse(failed.preparing)
        assertFalse(failed.preparedAutomatically)
        assertNull(failed.preparedAutoSweepFingerprint)
        assertTrue(failed.isError)
        assertTrue(requireNotNull(failed.message).contains("Explicitly re-enable"))
        assertEquals(5L, failed.autoSweepSafetyDisableSequence)
    }

    private fun eligibleInvoice(
        invoiceId: String = "invoice-paid",
        status: InvoiceStatus = InvoiceStatus.PAID,
    ) = Invoice(
        invoiceId = invoiceId,
        receiver = ADDRESS,
        operatorAddress = ADDRESS,
        token = TOKEN,
        tokenSymbol = "USDC",
        tokenDecimals = 6,
        expectedAmount = "100",
        receivedAmount = "100",
        status = status,
        createdAt = 1_000,
        chainId = 84_532,
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        factoryAddress = ADDRESS,
        receiverImplementationAddress = TOKEN,
        vaultAddress = VAULT,
        confirmationBlocks = 2,
        publishedAtBlock = 100,
        publishedAtBlockHash = hash('1'),
        firstDetectedBlock = 102,
        firstDetectedBlockHash = hash('2'),
        lastObservedBlock = 103,
        confirmedAtBlock = 103,
        paymentTxHash = hash('3'),
        paymentPayerAddress = "0x" + "a".repeat(40),
        paymentBlockNumber = 101,
        paymentBlockHash = hash('4'),
        paidAt = 2_000,
        receiptNumber = 1,
        receiptAutoPrintEligible = true,
    )

    private fun hash(character: Char): String = "0x" + character.toString().repeat(64)

    private companion object {
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        const val TOKEN = "0x2222222222222222222222222222222222222222"
        const val VAULT = "0x3333333333333333333333333333333333333333"
    }
}
