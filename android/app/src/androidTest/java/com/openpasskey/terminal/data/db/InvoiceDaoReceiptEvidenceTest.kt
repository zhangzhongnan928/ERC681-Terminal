package com.openpasskey.terminal.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.receiptPrintFingerprint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceDaoReceiptEvidenceTest {
    private lateinit var database: InvoiceDatabase
    private lateinit var dao: InvoiceDao

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            InvoiceDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.invoiceDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun fundingCursorChangeAtomicallyClearsIncomingEvidenceAndPrintMarker() = runBlocking {
        val invoice = invoice()
        dao.insert(invoice)

        assertEquals(
            1,
            dao.updateConfirmedInvoiceObservation(
                invoiceId = invoice.invoiceId,
                sourceStatus = InvoiceStatus.PAID,
                receivedAmount = invoice.receivedAmount,
                status = InvoiceStatus.PAID,
                firstDetectedBlock = 13,
                firstDetectedBlockHash = hash("13"),
                lastObservedBlock = 13,
                confirmedAtBlock = 13,
            ),
        )

        val stored = requireNotNull(dao.getById(invoice.invoiceId))
        assertEquals(13L, stored.firstDetectedBlock)
        assertNull(stored.paymentTxHash)
        assertNull(stored.paymentPayerAddress)
        assertNull(stored.paymentBlockNumber)
        assertNull(stored.paymentBlockHash)
        assertNull(stored.paidAt)
        assertNull(stored.receiptPrintedAt)
    }

    @Test
    fun closedInvoiceReconciliationAlsoClearsEvidenceWhenItsCursorChanges() = runBlocking {
        val invoice = invoice().copy(status = InvoiceStatus.EXPIRED)
        dao.insert(invoice)

        assertEquals(
            1,
            dao.updateClosedInvoiceObservation(
                invoiceId = invoice.invoiceId,
                receivedAmount = invoice.receivedAmount,
                status = InvoiceStatus.PAID,
                firstDetectedBlock = 14,
                firstDetectedBlockHash = hash("14"),
                lastObservedBlock = 14,
                confirmedAtBlock = 14,
            ),
        )

        val stored = requireNotNull(dao.getById(invoice.invoiceId))
        assertEquals(14L, stored.firstDetectedBlock)
        assertNull(stored.paymentTxHash)
        assertNull(stored.paymentBlockHash)
        assertNull(stored.paidAt)
        assertNull(stored.receiptPrintedAt)
    }

    @Test
    fun printCompletionRequiresExactEvidenceCursorAndSuccessfulStatus() = runBlocking {
        val paid = invoice()
        val confirming = paid.copy(
            invoiceId = hash("22"),
            status = InvoiceStatus.CONFIRMING,
            receiptPrintedAt = null,
        )
        dao.insert(paid.copy(receiptPrintedAt = null))
        dao.insert(confirming)

        assertEquals(
            0,
            dao.markReceiptPrinted(
                invoiceId = paid.invoiceId,
                expectedPaymentTxHash = hash("ff"),
                expectedFundingCursorBlock = requireNotNull(paid.firstDetectedBlock),
                expectedFundingCursorHash = requireNotNull(paid.firstDetectedBlockHash),
                printedAt = 200,
            ),
        )
        assertEquals(
            0,
            dao.markReceiptPrinted(
                invoiceId = confirming.invoiceId,
                expectedPaymentTxHash = requireNotNull(confirming.paymentTxHash),
                expectedFundingCursorBlock = requireNotNull(confirming.firstDetectedBlock),
                expectedFundingCursorHash = requireNotNull(confirming.firstDetectedBlockHash),
                printedAt = 200,
            ),
        )
        assertEquals(
            1,
            dao.markReceiptPrinted(
                invoiceId = paid.invoiceId,
                expectedPaymentTxHash = requireNotNull(paid.paymentTxHash),
                expectedFundingCursorBlock = requireNotNull(paid.firstDetectedBlock),
                expectedFundingCursorHash = requireNotNull(paid.firstDetectedBlockHash),
                printedAt = 200,
            ),
        )
        assertEquals(200L, dao.getById(paid.invoiceId)?.receiptPrintedAt)
        assertNull(dao.getById(confirming.invoiceId)?.receiptPrintedAt)
    }

    @Test
    fun receiptHistoryIncludesOlderEligiblePaymentsAndExcludesIrrelevantRecentRows() = runBlocking {
        val eligible = invoice().copy(
            invoiceId = "eligible-old-payment",
            createdAt = 1,
            receiptPrintedAt = 110,
        )
        dao.insert(eligible)
        repeat(125) { index ->
            dao.insert(
                invoice().copy(
                    invoiceId = "irrelevant-open-$index",
                    createdAt = 1_000L + index,
                    status = InvoiceStatus.WAITING,
                    firstDetectedBlock = null,
                    firstDetectedBlockHash = null,
                    paymentTxHash = null,
                    paymentPayerAddress = null,
                    paymentBlockNumber = null,
                    paymentBlockHash = null,
                    paidAt = null,
                    receiptPrintedAt = null,
                ),
            )
        }
        dao.insert(
            invoice().copy(
                invoiceId = "migrated-success",
                createdAt = 2_000,
                receiptNumber = 0,
                receiptAutoPrintEligible = false,
            ),
        )

        val history = dao.observeReceiptHistory().first()

        assertEquals(listOf(eligible.invoiceId), history.map(Invoice::invoiceId))
    }

    @Test
    fun unprintedSnapshotFlowRetainsSameClaimAcrossTransientConfirming() = runBlocking {
        val paid = invoice().copy(receiptPrintedAt = null)
        dao.insert(paid)
        val fingerprint = paid.receiptPrintFingerprint()

        assertEquals(
            setOf(fingerprint),
            dao.observeUnprintedReceiptSnapshots().first()
                .mapTo(mutableSetOf(), Invoice::receiptPrintFingerprint),
        )
        assertEquals(
            1,
            dao.updateConfirmedInvoiceObservation(
                invoiceId = paid.invoiceId,
                sourceStatus = InvoiceStatus.PAID,
                receivedAmount = paid.receivedAmount,
                status = InvoiceStatus.CONFIRMING,
                firstDetectedBlock = paid.firstDetectedBlock,
                firstDetectedBlockHash = paid.firstDetectedBlockHash,
                lastObservedBlock = requireNotNull(paid.lastObservedBlock),
                confirmedAtBlock = null,
            ),
        )
        val confirming = requireNotNull(dao.getById(paid.invoiceId))
        assertEquals(InvoiceStatus.CONFIRMING, confirming.status)
        assertEquals(fingerprint, confirming.receiptPrintFingerprint())
        assertEquals(
            setOf(fingerprint),
            dao.observeUnprintedReceiptSnapshots().first()
                .mapTo(mutableSetOf(), Invoice::receiptPrintFingerprint),
        )

        assertEquals(
            1,
            dao.updateObservation(
                invoiceId = confirming.invoiceId,
                receivedAmount = confirming.receivedAmount,
                status = InvoiceStatus.PAID,
                firstDetectedBlock = confirming.firstDetectedBlock,
                firstDetectedBlockHash = confirming.firstDetectedBlockHash,
                lastObservedBlock = confirming.lastObservedBlock,
                confirmedAtBlock = confirming.lastObservedBlock,
                paymentTxHash = confirming.paymentTxHash,
                paymentPayerAddress = confirming.paymentPayerAddress,
                paymentBlockNumber = confirming.paymentBlockNumber,
                paymentBlockHash = confirming.paymentBlockHash,
                paidAt = confirming.paidAt,
            ),
        )
        val returned = requireNotNull(dao.getById(paid.invoiceId))
        assertEquals(InvoiceStatus.PAID, returned.status)
        assertEquals(fingerprint, returned.receiptPrintFingerprint())
    }

    private fun invoice() = Invoice(
        invoiceId = hash("01"),
        receiver = address("02"),
        operatorAddress = address("03"),
        token = address("04"),
        tokenSymbol = "AUDD",
        tokenDecimals = 6,
        expectedAmount = "12340000",
        receivedAmount = "12340000",
        status = InvoiceStatus.PAID,
        createdAt = 1,
        chainId = 84532,
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        factoryAddress = address("05"),
        receiverImplementationAddress = address("06"),
        vaultAddress = address("07"),
        confirmationBlocks = 1,
        erc681Uri = "ethereum:test",
        publishedAtBlock = 10,
        publishedAtBlockHash = hash("08"),
        firstDetectedBlock = 12,
        firstDetectedBlockHash = hash("09"),
        lastObservedBlock = 12,
        confirmedAtBlock = 12,
        paymentTxHash = hash("ab"),
        paymentPayerAddress = address("0a"),
        paymentBlockNumber = 11,
        paymentBlockHash = hash("0b"),
        paidAt = 100,
        receiptNumber = 1,
        receiptAutoPrintEligible = true,
        receiptPrintedAt = 110,
    )

    private fun address(marker: String): String = "0x" + marker.repeat(20)
    private fun hash(marker: String): String = "0x" + marker.repeat(32)
}
