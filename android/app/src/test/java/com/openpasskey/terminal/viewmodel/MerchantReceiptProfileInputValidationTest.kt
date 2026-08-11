package com.openpasskey.terminal.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantReceiptProfileInputValidationTest {
    @Test
    fun `valid input returns the canonical profile without field errors`() {
        val validation = validateMerchantReceiptProfileInput(
            name = "  Blue   Brew  ",
            abn = "61 695 642 285",
        )

        assertTrue(validation.isValid)
        assertNull(validation.nameError)
        assertNull(validation.abnError)
        val profile = requireNotNull(validation.profile)
        assertEquals("Blue Brew", profile.name)
        assertEquals("61 695 642 285", profile.abn)
    }

    @Test
    fun `name and ABN errors are reported independently`() {
        val validation = validateMerchantReceiptProfileInput(
            name = "   ",
            abn = "12 345 678 901",
        )

        assertFalse(validation.isValid)
        assertEquals("Merchant name is required.", validation.nameError)
        assertEquals("Enter a valid Australian ABN.", validation.abnError)
        assertNull(validation.profile)
    }

    @Test
    fun `non ASCII ABN digits and whitespace are rejected`() {
        val unicodeDigits = validateMerchantReceiptProfileInput(
            name = "Blue Brew",
            abn = "٦١ ٦٩٥ ٦٤٢ ٢٨٥",
        )
        val tabSeparated = validateMerchantReceiptProfileInput(
            name = "Blue Brew",
            abn = "61\t695 642 285",
        )

        assertFalse(unicodeDigits.isValid)
        assertEquals("ABN may contain digits and spaces only.", unicodeDigits.abnError)
        assertFalse(tabSeparated.isValid)
        assertEquals("ABN may contain digits and spaces only.", tabSeparated.abnError)
    }
}
