package com.openpasskey.terminal.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantReceiptProfileTest {
    @Test
    fun `canonicalizes the merchant name and formats a valid ABN`() {
        val profile = MerchantReceiptProfile.fromInput(
            name = "  Blue   Brew  ",
            abn = "61 695 642 285",
        )

        assertEquals("Blue Brew", profile.name)
        assertEquals("61 695 642 285", profile.abn)
        assertTrue(MerchantReceiptProfile.isValidAustralianAbn("61695642285"))
    }

    @Test
    fun `ABN is optional`() {
        val profile = MerchantReceiptProfile.fromInput("Blue Brew", "")

        assertEquals("", profile.abn)
    }

    @Test
    fun `rejects blank names and invalid ABNs`() {
        assertThrows(IllegalArgumentException::class.java) {
            MerchantReceiptProfile.fromInput("   ", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MerchantReceiptProfile.fromInput("Blue Brew", "12 345 678 901")
        }
        assertFalse(MerchantReceiptProfile.isValidAustralianAbn("12345678901"))
    }

    @Test
    fun `rejects non ASCII ABN digits even when their numeric value has a valid checksum`() {
        val arabicIndicAbn = "٦١ ٦٩٥ ٦٤٢ ٢٨٥"

        assertThrows(IllegalArgumentException::class.java) {
            MerchantReceiptProfile.fromInput("Blue Brew", arabicIndicAbn)
        }
        assertFalse(MerchantReceiptProfile.isValidAustralianAbn("٦١٦٩٥٦٤٢٢٨٥"))
    }
}
