package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReceiptQueuePolicyTest {
    @Test
    fun `cooperative rpc deferral uses a prompt non escalating retry`() {
        val retry = promptAutoReceiptRetryState(
            nowElapsedRealtimeMillis = 10_000,
            retryDelayMillis = 1_000,
        )

        assertEquals(0, retry.failureCount)
        assertEquals(11_000L, retry.retryAfterElapsedRealtimeMillis)
    }

    @Test
    fun `queued duplicate is rejected after serialized attempt becomes ambiguous`() {
        val invoice = invoice("old")
        val fingerprint = invoice.autoReceiptFingerprint()

        assertTrue(
            automaticReceiptAttemptAllowed(
                queuedFingerprint = fingerprint,
                currentFingerprint = fingerprint,
                suppressedFingerprints = emptySet(),
                retryAfterElapsedRealtimeMillis = 0,
                nowElapsedRealtimeMillis = 1,
            ),
        )
        assertFalse(
            automaticReceiptAttemptAllowed(
                queuedFingerprint = fingerprint,
                currentFingerprint = fingerprint,
                suppressedFingerprints = setOf(fingerprint),
                retryAfterElapsedRealtimeMillis = 0,
                nowElapsedRealtimeMillis = 1,
            ),
        )
    }

    @Test
    fun `stale queued snapshot is rejected and canonical replacement can retry`() {
        val original = invoice("old")
        val replacement = original.copy(
            paymentTxHash = hash("66"),
            paymentBlockHash = hash("77"),
        )
        val oldFingerprint = original.autoReceiptFingerprint()
        val newFingerprint = replacement.autoReceiptFingerprint()

        assertNotEquals(oldFingerprint, newFingerprint)
        assertFalse(
            automaticReceiptAttemptAllowed(
                queuedFingerprint = oldFingerprint,
                currentFingerprint = newFingerprint,
                suppressedFingerprints = emptySet(),
                retryAfterElapsedRealtimeMillis = 0,
                nowElapsedRealtimeMillis = 1,
            ),
        )
        assertEquals(
            replacement.invoiceId,
            selectAutomaticReceiptCandidate(
                pending = listOf(replacement),
                suppressedFingerprints = setOf(oldFingerprint),
                retryStates = emptyMap(),
                nowElapsedRealtimeMillis = 1,
            )?.invoiceId,
        )
    }

    @Test
    fun `deferred old receipt does not starve another invoice and retry is exponentially capped`() {
        val old = invoice("old")
        val newer = invoice("new")
        val oldFingerprint = old.autoReceiptFingerprint()
        val first = nextAutoReceiptRetryState(
            previous = null,
            nowElapsedRealtimeMillis = 1_000,
            baseDelayMillis = 30_000,
            maximumDelayMillis = 120_000,
            maximumBackoffSteps = 7,
        )

        assertEquals(31_000L, first.retryAfterElapsedRealtimeMillis)
        assertEquals(
            newer.invoiceId,
            selectAutomaticReceiptCandidate(
                pending = listOf(old, newer),
                suppressedFingerprints = emptySet(),
                retryStates = mapOf(oldFingerprint to first),
                nowElapsedRealtimeMillis = 2_000,
            )?.invoiceId,
        )

        val second = nextAutoReceiptRetryState(
            first,
            nowElapsedRealtimeMillis = 31_000,
            baseDelayMillis = 30_000,
            maximumDelayMillis = 120_000,
            maximumBackoffSteps = 7,
        )
        val capped = nextAutoReceiptRetryState(
            second.copy(failureCount = 7),
            nowElapsedRealtimeMillis = 91_000,
            baseDelayMillis = 30_000,
            maximumDelayMillis = 120_000,
            maximumBackoffSteps = 7,
        )
        assertEquals(91_000L, second.retryAfterElapsedRealtimeMillis)
        assertEquals(211_000L, capped.retryAfterElapsedRealtimeMillis)
    }

    @Test
    fun `durable claim survives transient confirming and suppresses paid return`() {
        val paid = invoice("canonical")
        val fingerprint = paid.autoReceiptFingerprint()
        val confirming = paid.copy(status = InvoiceStatus.CONFIRMING)

        assertEquals(fingerprint, confirming.autoReceiptFingerprint())
        assertEquals(setOf(fingerprint), unprintedReceiptFingerprints(listOf(confirming)))

        val returnedToPaid = confirming.copy(status = InvoiceStatus.PAID)
        assertFalse(
            automaticReceiptAttemptAllowed(
                queuedFingerprint = returnedToPaid.autoReceiptFingerprint(),
                currentFingerprint = returnedToPaid.autoReceiptFingerprint(),
                suppressedFingerprints = setOf(fingerprint),
                retryAfterElapsedRealtimeMillis = 0,
                nowElapsedRealtimeMillis = 1,
            ),
        )
    }

    private fun invoice(marker: String) = Invoice(
        invoiceId = "invoice-$marker",
        receiver = address("11"),
        operatorAddress = address("12"),
        token = address("13"),
        tokenSymbol = "USDC",
        tokenDecimals = 6,
        expectedAmount = "100",
        receivedAmount = "100",
        status = InvoiceStatus.PAID,
        createdAt = 1,
        chainId = 84_532,
        firstDetectedBlock = 12,
        firstDetectedBlockHash = hash("22"),
        paymentTxHash = hash("33"),
        paymentPayerAddress = address("44"),
        paymentBlockNumber = 11,
        paymentBlockHash = hash("55"),
        paidAt = 100,
        receiptNumber = 1,
        receiptAutoPrintEligible = true,
    )

    private fun address(marker: String) = "0x" + marker.repeat(20)
    private fun hash(marker: String) = "0x" + marker.repeat(32)
}
