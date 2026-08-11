package com.openpasskey.terminal.printing

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.receiptPrintFingerprint
import com.openpasskey.terminal.data.repository.AutomaticPaymentEvidenceResult
import com.openpasskey.terminal.data.repository.InvoiceRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

class ReceiptCoordinatorTest {
    @Test
    fun `automatic success records completion only after the printer callback`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        `when`(
            repository.markReceiptPrinted(
                invoiceId = invoice.invoiceId,
                expectedPaymentTxHash = requireNotNull(invoice.paymentTxHash),
                expectedFundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                expectedFundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                printedAt = 1_704_067_300,
            )
        ).thenReturn(true)
        val printer = RecordingPrinter(ReceiptPrintResult.Success)
        val coordinator = ReceiptCoordinator(
            repository = repository,
            printer = printer,
            automaticClaimStore = RecordingClaimStore(),
            clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_300), ZoneOffset.UTC),
        )

        val result = coordinator.print(invoice.invoiceId, automatic = true)

        assertEquals(ReceiptRequestResult.Printed(wasReprint = false), result)
        assertEquals(invoice.paymentTxHash, printer.document?.paymentTxHash)
        val mark = mockingDetails(repository).invocations.single {
            it.method.name == "markReceiptPrinted"
        }
        assertEquals(invoice.invoiceId, mark.arguments[0])
        assertEquals(invoice.paymentTxHash, mark.arguments[1])
        assertEquals(invoice.firstDetectedBlock, mark.arguments[2])
        assertEquals(invoice.firstDetectedBlockHash, mark.arguments[3])
        assertEquals(1_704_067_300L, mark.arguments[4])
        Unit
    }

    @Test
    fun `a stale successful queue emission cannot print after status is downgraded`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(
            status = InvoiceStatus.CONFIRMING,
            receiptPrintedAt = null,
        )
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val printer = RecordingPrinter(ReceiptPrintResult.Success)

        val result = ReceiptCoordinator(repository, printer, RecordingClaimStore()).print(
            invoiceId = invoice.invoiceId,
            automatic = true,
        )

        assertEquals(
            ReceiptRequestResult.Unavailable("This payment is not currently confirmed."),
            result,
        )
        assertEquals(null, printer.document)
        assertFalse(mockingDetails(repository).invocations.any {
            it.method.name == "markReceiptPrinted"
        })
    }

    @Test
    fun `automatic restart skips a receipt already confirmed as printed`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32))
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        val printer = RecordingPrinter(ReceiptPrintResult.Success)
        val coordinator = ReceiptCoordinator(repository, printer, RecordingClaimStore())

        val result = coordinator.print(invoice.invoiceId, automatic = true)

        assertSame(ReceiptRequestResult.AlreadyPrinted, result)
        assertEquals(null, printer.document)
        assertFalse(mockingDetails(repository).invocations.any {
            it.method.name.startsWith("ensurePaymentEvidence")
        })
        Unit
    }

    @Test
    fun `ambiguous print failure is not persisted as completed`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val printer = RecordingPrinter(
            ReceiptPrintResult.TimedOut(ReceiptPrintResult.TimedOut.Stage.PRINT_COMPLETION),
        )
        val coordinator = ReceiptCoordinator(repository, printer, RecordingClaimStore())

        val result = coordinator.print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Failed(
                "The printer did not confirm receipt completion. " +
                    "Check the paper output before reprinting.",
            ),
            result,
        )
        assertFalse(mockingDetails(repository).invocations.any {
            it.method.name == "markReceiptPrinted"
        })
        Unit
    }

    @Test
    fun `durable ambiguity claim suppresses restart and queued duplicate`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val store = RecordingClaimStore()
        val printer = GatedAmbiguousPrinter()
        val firstCoordinator = ReceiptCoordinator(repository, printer, store)

        val (first, queued) = coroutineScope {
            val firstAttempt = async { firstCoordinator.print(invoice.invoiceId, automatic = true) }
            printer.entered.await()
            assertTrue(invoice.receiptPrintFingerprint() in store.claims())
            val queuedAttempt = async { firstCoordinator.print(invoice.invoiceId, automatic = true) }
            printer.release.complete(Unit)
            firstAttempt.await() to queuedAttempt.await()
        }

        assertTrue(first is ReceiptRequestResult.Failed)
        assertTrue(queued is ReceiptRequestResult.AutomaticSuppressed)
        assertEquals(1, printer.calls)
        assertTrue(invoice.receiptPrintFingerprint() in store.claims())

        val restartedPrinter = RecordingPrinter(ReceiptPrintResult.Success)
        val restarted = ReceiptCoordinator(repository, restartedPrinter, store)
            .print(invoice.invoiceId, automatic = true)
        assertTrue(restarted is ReceiptRequestResult.AutomaticSuppressed)
        assertEquals(null, restartedPrinter.document)
    }

    @Test
    fun `claim persistence failure prevents automatic printer submission`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val printer = RecordingPrinter(ReceiptPrintResult.Success)

        val result = ReceiptCoordinator(
            repository,
            printer,
            RecordingClaimStore(failClaim = true),
        ).print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Unavailable(
                message = "Automatic receipt safety state could not be saved. Nothing was printed.",
                retryAutomatically = true,
            ),
            result,
        )
        assertEquals(null, printer.document)
    }

    @Test
    fun `post-print database failure keeps durable claim for manual recovery`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val store = RecordingClaimStore()

        val result = ReceiptCoordinator(
            repository,
            RecordingPrinter(ReceiptPrintResult.Success),
            store,
        ).print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Failed(
                "Receipt printed, but its completion record could not be saved.",
            ),
            result,
        )
        assertTrue(invoice.receiptPrintFingerprint() in store.claims())
    }

    @Test
    fun `definite automatic failure releases claim and manual success also clears it`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        `when`(repository.ensurePaymentEvidence(invoice.invoiceId)).thenReturn(invoice)
        `when`(
            repository.markReceiptPrinted(
                invoiceId = invoice.invoiceId,
                expectedPaymentTxHash = requireNotNull(invoice.paymentTxHash),
                expectedFundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                expectedFundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                printedAt = 1_704_067_300,
            ),
        ).thenReturn(true)
        val store = RecordingClaimStore()
        val failed = ReceiptCoordinator(
            repository,
            RecordingPrinter(ReceiptPrintResult.Unavailable(3, "Printer unavailable")),
            store,
        ).print(invoice.invoiceId, automatic = true)
        assertTrue((failed as ReceiptRequestResult.Unavailable).retryAutomatically)
        assertTrue(store.claims().isEmpty())

        assertEquals(AutomaticReceiptClaimResult.CLAIMED, store.claim(invoice.receiptPrintFingerprint()))
        val manual = ReceiptCoordinator(
            repository = repository,
            printer = RecordingPrinter(ReceiptPrintResult.Success),
            automaticClaimStore = store,
            clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_300), ZoneOffset.UTC),
        ).print(invoice.invoiceId, automatic = false)
        assertTrue(manual is ReceiptRequestResult.Printed)
        assertTrue(store.claims().isEmpty())
    }

    @Test
    fun `accepted printer failure remains durably suppressed as uncertain`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val store = RecordingClaimStore()
        val coordinator = ReceiptCoordinator(
            repository,
            RecordingPrinter(ReceiptPrintResult.Failure("Printer rejected the job.", code = 1)),
            store,
        )

        val result = coordinator.print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Failed(
                message = "Printer rejected the job.",
            ),
            result,
        )
        assertTrue(store.claims().isNotEmpty())
        assertFalse(mockingDetails(repository).invocations.any {
            it.method.name == "markReceiptPrinted"
        })
    }

    @Test
    fun `content callback failure beats commit success and is never marked printed`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Available(invoice),
        )
        val coordinator = ReceiptCoordinator(
            repository,
            CommandFailureThenCommitSuccessPrinter(),
            RecordingClaimStore(),
        )

        val result = coordinator.print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Failed(
                message = "QR command failed",
            ),
            result,
        )
        assertFalse(mockingDetails(repository).invocations.any {
            it.method.name == "markReceiptPrinted"
        })
    }

    @Test
    fun `automatic evidence deferral is retryable and never reaches the printer`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Deferred,
        )
        val printer = RecordingPrinter(ReceiptPrintResult.Success)

        val result = ReceiptCoordinator(repository, printer, RecordingClaimStore())
            .print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Unavailable(
                message = "Payment transaction details will be retried in the background.",
                retryAutomatically = true,
            ),
            result,
        )
        assertEquals(null, printer.document)
    }

    @Test
    fun `unsupported automatic evidence is suppressed and never reaches the printer`() = runBlocking {
        val invoice = invoice(paymentTxHash = "0x" + "ab".repeat(32)).copy(receiptPrintedAt = null)
        val repository = mock(InvoiceRepository::class.java)
        `when`(repository.getInvoice(invoice.invoiceId)).thenReturn(invoice)
        `when`(repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)).thenReturn(
            AutomaticPaymentEvidenceResult.Unsupported(invoice),
        )
        val printer = RecordingPrinter(ReceiptPrintResult.Success)

        val result = ReceiptCoordinator(repository, printer, RecordingClaimStore())
            .print(invoice.invoiceId, automatic = true)

        assertEquals(
            ReceiptRequestResult.Unavailable(
                "This payment has no supported incoming transaction receipt evidence.",
            ),
            result,
        )
        assertEquals(null, printer.document)
    }

    @Test
    fun `builds a receipt from immutable incoming payment evidence`() {
        val hash = "0x" + "ab".repeat(32)
        val document = invoice(
            paymentTxHash = hash,
            receiptMerchantName = "Blue Brew",
            receiptMerchantAbn = "61 695 642 285",
        ).toReceiptDocument()

        assertEquals("Blue Brew", document.merchantName)
        assertEquals("61 695 642 285", document.merchantAbn)
        assertEquals("12.34", document.displayAmount)
        assertEquals("AUDD", document.tokenSymbol)
        assertEquals("Base", document.networkName)
        assertEquals(hash, document.paymentTxHash)
        assertEquals(9L, document.receiptNumber)
        assertEquals(1_704_067_200L, document.paidAtEpochSeconds)
        assertEquals("https://sepolia.basescan.org/tx/$hash", document.explorerUrl)
    }

    @Test
    fun `never falls back to the settlement sweep hash`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            invoice(paymentTxHash = null).toReceiptDocument()
        }

        assertEquals(
            "Incoming payment transaction details are not available yet.",
            error.message,
        )
    }

    @Test
    fun `rejects a missing snapshotted merchant name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            invoice(
                paymentTxHash = "0x" + "ab".repeat(32),
                receiptMerchantName = " ",
            ).toReceiptDocument()
        }

        assertEquals("Receipt merchant name is unavailable.", error.message)
    }

    @Test
    fun `formats native and token raw values without floating point`() {
        assertEquals("1.25", formatReceiptAmount("1250000", 6))
        assertEquals("0.000000000000000001", formatReceiptAmount("1", 18))
    }

    private fun invoice(
        paymentTxHash: String?,
        receiptMerchantName: String = "OPK Terminal",
        receiptMerchantAbn: String = "",
    ) = Invoice(
        invoiceId = "0x" + "01".repeat(32),
        receiver = "0x" + "02".repeat(20),
        operatorAddress = "0x" + "03".repeat(20),
        token = "0x" + "04".repeat(20),
        tokenSymbol = "AUDD",
        tokenDecimals = 6,
        expectedAmount = "12340000",
        receivedAmount = "12340000",
        status = InvoiceStatus.SETTLED,
        createdAt = 1,
        chainId = 84532,
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        factoryAddress = "0x" + "05".repeat(20),
        receiverImplementationAddress = "0x" + "06".repeat(20),
        vaultAddress = "0x" + "07".repeat(20),
        confirmationBlocks = 2,
        erc681Uri = "ethereum:test",
        publishedAtBlock = 10,
        publishedAtBlockHash = "0x" + "08".repeat(32),
        firstDetectedBlock = 12,
        firstDetectedBlockHash = "0x" + "09".repeat(32),
        lastObservedBlock = 13,
        confirmedAtBlock = 13,
        paymentTxHash = paymentTxHash,
        paymentPayerAddress = "0x" + "0a".repeat(20),
        paymentBlockNumber = 11,
        paymentBlockHash = "0x" + "0b".repeat(32),
        paidAt = 1_704_067_200,
        receiptNumber = 9,
        receiptMerchantName = receiptMerchantName,
        receiptMerchantAbn = receiptMerchantAbn,
        receiptAutoPrintEligible = true,
        receiptPrintedAt = 1_704_067_210,
        settledTxHash = "0x" + "ff".repeat(32),
    )

    private class RecordingPrinter(
        private val result: ReceiptPrintResult,
    ) : ReceiptPrinter {
        var document: ReceiptDocument? = null

        override suspend fun print(document: ReceiptDocument): ReceiptPrintResult {
            this.document = document
            return result
        }

        override fun close() = Unit
    }

    private class GatedAmbiguousPrinter : ReceiptPrinter {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun print(document: ReceiptDocument): ReceiptPrintResult {
            calls += 1
            entered.complete(Unit)
            release.await()
            return ReceiptPrintResult.TimedOut(ReceiptPrintResult.TimedOut.Stage.PRINT_COMPLETION)
        }

        override fun close() = Unit
    }

    private class RecordingClaimStore(
        initial: Set<String> = emptySet(),
        private val failClaim: Boolean = false,
        private val failRelease: Boolean = false,
    ) : AutomaticReceiptClaimStore {
        private val stored = initial.toMutableSet()

        override fun claims(): Set<String> = stored.toSet()

        override fun claim(fingerprint: String): AutomaticReceiptClaimResult {
            if (failClaim) return AutomaticReceiptClaimResult.PERSISTENCE_FAILED
            return if (stored.add(fingerprint)) {
                AutomaticReceiptClaimResult.CLAIMED
            } else {
                AutomaticReceiptClaimResult.ALREADY_CLAIMED
            }
        }

        override fun release(fingerprint: String): Boolean {
            if (failRelease) return false
            stored.remove(fingerprint)
            return true
        }

        override fun retainOnly(liveFingerprints: Set<String>): Boolean {
            stored.retainAll(liveFingerprints)
            return true
        }
    }

    private class CommandFailureThenCommitSuccessPrinter : ReceiptPrinter {
        override suspend fun print(document: ReceiptDocument): ReceiptPrintResult {
            val outcome = IminBufferedReceiptOutcome()
            check(outcome.beginCommit() == null)
            outcome.commandException("explorer QR", code = 73, message = "QR command failed")
            outcome.commitPrintResult(0, "Success")
            return outcome.awaitResult()
        }

        override fun close() = Unit
    }
}
