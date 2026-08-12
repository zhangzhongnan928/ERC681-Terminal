package com.openpasskey.terminal.printing

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDocumentTest {
    @Test
    fun `formats a stable receipt in UTC`() {
        val document =
            ReceiptDocument(
                merchantName = "Blue Brew",
                merchantAbn = "61 695 642 285",
                displayAmount = "12.34",
                tokenSymbol = "AUDD",
                networkName = "Base",
                terminalAddress = "0x1234567890abcdef1234567890abcdef12345678",
                paymentTxHash = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                receiptNumber = 42,
                paidAtEpochSeconds = 1_704_067_200,
                explorerUrl =
                    "https://basescan.org/tx/0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            )

        assertEquals(
            """
            |           Blue Brew
            |       ABN 61 695 642 285
            |        PAYMENT RECEIPT
            |Date (UTC):   01 Jan 2024  00:00
            |Receipt:                     #42
            |TOTAL                 12.34 AUDD
            |Paid: 12.34 AUDD (Base)
            |Terminal: 0x12345...45678
            |Tx Hash:  0xabcde...fabcd
            |     Powered by OpenPasskey
            |  Scan for transaction details
            |
            """.trimMargin(),
            ReceiptFormatter.format(document, ZoneId.of("UTC")),
        )
        assertFalse(ReceiptFormatter.format(document, ZoneId.of("UTC")).contains("===="))
        assertFalse(ReceiptFormatter.format(document, ZoneId.of("UTC")).contains("----"))
        assertFalse(ReceiptFormatter.format(document, ZoneId.of("UTC")).contains("\n\n"))
    }

    @Test
    fun `omits an absent or blank merchant ABN`() {
        val absent = ReceiptFormatter.format(document(merchantAbn = null), ZoneId.of("UTC"))
        val blankReceipt =
            ReceiptFormatter.format(document(merchantAbn = " \n\t "), ZoneId.of("UTC"))

        assertFalse(absent.contains("ABN "))
        assertFalse(blankReceipt.contains("ABN "))
        assertEquals(
            null,
            ReceiptFormatter.printContent(document(merchantAbn = " \n\t ")).merchantAbn,
        )
    }

    @Test
    fun `measures CJK fullwidth and emoji conservatively`() {
        assertEquals(1, ReceiptFormatter.displayWidth("A"))
        assertEquals(2, ReceiptFormatter.displayWidth("界"))
        assertEquals(2, ReceiptFormatter.displayWidth("Ａ"))
        assertEquals(2, ReceiptFormatter.displayWidth("😀"))
        assertEquals(1, ReceiptFormatter.displayWidth("e\u0301"))
        assertEquals(6, ReceiptFormatter.displayWidth("界Ａ😀"))
    }

    @Test
    fun `wraps long mixed Unicode without corrupting output`() {
        val merchant =
            "東京咖啡店ＡＢＣ😀👨‍👩‍👧‍👦𝄞مرحبا한글長い名前🚀".repeat(3)
        val document = document(
            merchantName = merchant,
            tokenSymbol = "人民幣😀".repeat(8),
            networkName = "Base 東京 🚀",
        )

        val content = ReceiptFormatter.printContent(document)
        val receipt = ReceiptFormatter.format(document, ZoneId.of("UTC"))

        assertTrue(content.merchantLines.size > 1)
        assertEquals(merchant, content.merchantLines.joinToString(separator = ""))
        assertTrue(content.merchantLines.all { ReceiptFormatter.displayWidth(it) <= 24 })
        assertTrue(content.totalLines.all { ReceiptFormatter.displayWidth(it) <= 32 })
        assertTrue(content.paidLines.all { ReceiptFormatter.displayWidth(it) <= 32 })
        assertTrue(content.merchantLines.none { it.startsWith("\u200D") || it.endsWith("\u200D") })
        assertFalse(receipt.hasUnpairedSurrogate())
        assertFalse(receipt.contains('\uFFFD'))
        assertTrue(receipt.contains("👨‍👩‍👧‍👦"))
    }

    @Test
    fun `renders the same receipt after the device timezone changes`() {
        val document = document()

        val utcReceipt = ReceiptFormatter.format(document, ZoneId.of("UTC"))
        val sydneyReceipt = ReceiptFormatter.format(document, ZoneId.of("Australia/Sydney"))
        val losAngelesReceipt = ReceiptFormatter.format(document, ZoneId.of("America/Los_Angeles"))

        assertEquals(utcReceipt, sydneyReceipt)
        assertEquals(utcReceipt, losAngelesReceipt)
        assertTrue(utcReceipt.contains("Date (UTC):   01 Jan 2024  00:00"))
    }

    @Test
    fun `abbreviates terminal and payment transaction identifiers`() {
        val receipt =
            ReceiptFormatter.format(
                document(
                    terminalAddress = "0x1111111111111111111111111111111111111111",
                    paymentTxHash = "0x2222222222222222222222222222222222222222222222222222222222222222",
                ),
                ZoneId.of("UTC"),
            )

        assertTrue(receipt.contains("Terminal: 0x11111...11111"))
        assertTrue(receipt.contains("Tx Hash:  0x22222...22222"))
    }

    @Test
    fun `retains exact explorer URL for QR without printing it as text`() {
        val explorerUrl = "https://sepolia.basescan.org/tx/0x" + "ab".repeat(32)
        val receipt =
            ReceiptFormatter.format(
                document(explorerUrl = explorerUrl),
                ZoneId.of("Australia/Sydney"),
            )

        assertTrue(receipt.contains("Scan for transaction details"))
        assertEquals(explorerUrl, ReceiptFormatter.printContent(document(explorerUrl = explorerUrl)).explorerUrl)
        assertFalse(receipt.contains(explorerUrl))
    }

    private fun document(
        merchantName: String = "Merchant",
        merchantAbn: String? = null,
        tokenSymbol: String = "USDC",
        networkName: String = "Base Mainnet",
        terminalAddress: String = "0x1234567890abcdef1234567890abcdef12345678",
        paymentTxHash: String = "0x" + "ab".repeat(32),
        explorerUrl: String = "https://basescan.org/tx/0x" + "ab".repeat(32),
    ): ReceiptDocument =
        ReceiptDocument(
            merchantName = merchantName,
            merchantAbn = merchantAbn,
            displayAmount = "1.00",
            tokenSymbol = tokenSymbol,
            networkName = networkName,
            terminalAddress = terminalAddress,
            paymentTxHash = paymentTxHash,
            receiptNumber = 7,
            paidAtEpochSeconds = 1_704_067_200,
            explorerUrl = explorerUrl,
        )

    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                    index += 2
                }
                current.isLowSurrogate() -> return true
                else -> index += 1
            }
        }
        return false
    }
}
