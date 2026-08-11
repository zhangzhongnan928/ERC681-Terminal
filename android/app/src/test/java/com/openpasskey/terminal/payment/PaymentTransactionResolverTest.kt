package com.openpasskey.terminal.payment

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PaymentTransactionResolverTest {
    @Test
    fun `invoice adapter maps only canonical incoming payment inputs`() {
        val request = invoice().toPaymentEvidenceRequestOrNull()

        requireNotNull(request)
        assertEquals(84_532L, request.chainId)
        assertEquals(TOKEN.lowercase(), request.asset.value)
        assertEquals(RECEIVER.lowercase(), request.receiver.value)
        assertEquals("10".toBigInteger(), request.expectedAmount)
        assertEquals(100L, request.publicationCursor.blockNumber)
        assertEquals(hash("11"), request.publicationCursor.blockHash)
        assertEquals(104L, request.fundingCursor.blockNumber)
        assertEquals(hash("22"), request.fundingCursor.blockHash)
    }

    @Test
    fun `missing publication evidence returns null and never falls back to settlement hash`() {
        val invoice = invoice().copy(
            publishedAtBlock = null,
            publishedAtBlockHash = null,
            settledTxHash = hash("ff"),
        )

        assertNull(invoice.toPaymentEvidenceRequestOrNull())
    }

    @Test
    fun `unsupported app chain fails before RPC client construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            invoice().copy(chainId = 1L).toPaymentEvidenceRequestOrNull()
        }
    }

    @Test
    fun `funding cursor must follow publication cursor`() {
        assertThrows(IllegalArgumentException::class.java) {
            invoice().copy(firstDetectedBlock = 100L).toPaymentEvidenceRequestOrNull()
        }
    }

    private fun invoice() = Invoice(
        invoiceId = "invoice-1",
        receiver = RECEIVER,
        token = TOKEN,
        tokenSymbol = "AUD",
        expectedAmount = "10",
        status = InvoiceStatus.PAID,
        createdAt = 1L,
        chainId = 84_532L,
        networkName = "Base Sepolia",
        rpcUrl = "https://rpc.example",
        factoryAddress = "0x3333333333333333333333333333333333333333",
        receiverImplementationAddress = "0x4444444444444444444444444444444444444444",
        vaultAddress = "0x5555555555555555555555555555555555555555",
        publishedAtBlock = 100L,
        publishedAtBlockHash = hash("11"),
        firstDetectedBlock = 104L,
        firstDetectedBlockHash = hash("22"),
    )

    private fun hash(marker: String): String = "0x" + marker.repeat(32)

    private companion object {
        const val TOKEN = "0x1111111111111111111111111111111111111111"
        const val RECEIVER = "0x2222222222222222222222222222222222222222"
    }
}
