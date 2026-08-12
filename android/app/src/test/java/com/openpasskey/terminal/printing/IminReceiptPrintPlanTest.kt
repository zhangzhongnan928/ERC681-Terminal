package com.openpasskey.terminal.printing

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IminReceiptPrintPlanTest {
    @Test
    fun `compact plan removes decoration whitespace and raw explorer url`() {
        val document = document()
        val plan = ReceiptFormatter.printContent(document).toIminReceiptPrintPlan()
        val printedText = plan.textBlocks.joinToString("\n") { it.text }

        assertEquals(8, plan.textBlocks.size)
        assertTrue(printedText.contains("Blue Brew"))
        assertTrue(printedText.contains("ABN 61 695 642 285"))
        assertTrue(printedText.contains("PAYMENT RECEIPT"))
        assertTrue(printedText.contains("Powered by OpenPasskey"))
        assertTrue(printedText.contains("Scan for transaction details"))
        assertFalse(printedText.contains("===="))
        assertFalse(printedText.contains("----"))
        assertFalse(printedText.contains("\n\n"))
        assertFalse(printedText.contains(document.explorerUrl))
        assertEquals(document.explorerUrl, plan.explorerUrl)
        assertEquals(80, plan.finalFeed)
    }

    @Test
    fun `reprint plan is deterministic`() {
        val document = document()

        assertEquals(
            ReceiptFormatter.format(document, ZoneOffset.UTC),
            ReceiptFormatter.format(document, ZoneOffset.ofHours(10)),
        )
        assertEquals(
            ReceiptFormatter.printContent(document).toIminReceiptPrintPlan(),
            ReceiptFormatter.printContent(document).toIminReceiptPrintPlan(),
        )
    }

    private fun document() = ReceiptDocument(
        merchantName = "Blue Brew",
        merchantAbn = "61 695 642 285",
        displayAmount = "12.34",
        tokenSymbol = "AUDM",
        networkName = "Base",
        terminalAddress = "0x" + "11".repeat(20),
        paymentTxHash = "0x" + "22".repeat(32),
        receiptNumber = 42,
        paidAtEpochSeconds = 1_704_067_200,
        explorerUrl = "https://basescan.org/tx/0x" + "22".repeat(32),
    )
}
