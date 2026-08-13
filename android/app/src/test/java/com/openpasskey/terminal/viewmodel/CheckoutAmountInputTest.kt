package com.openpasskey.terminal.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutAmountInputTest {
    @Test
    fun `decimal keypad preserves an exact token amount`() {
        var amount = ""
        listOf(
            CheckoutKey.ONE,
            CheckoutKey.TWO,
            CheckoutKey.DECIMAL,
            CheckoutKey.THREE,
            CheckoutKey.FOUR,
        ).forEach { amount = applyCheckoutKey(amount, it, tokenDecimals = 2) }

        assertEquals("12.34", amount)
        assertTrue(isSubmittableCheckoutAmount(amount, tokenDecimals = 2))
    }

    @Test
    fun `keypad refuses fractional digits beyond token precision`() {
        val amount = applyCheckoutKey("12.34", CheckoutKey.FIVE, tokenDecimals = 2)

        assertEquals("12.34", amount)
        assertFalse(isPotentialCheckoutAmount("12.345", tokenDecimals = 2))
    }

    @Test
    fun `decimal key starts a fractional amount with zero`() {
        val amount = applyCheckoutKey("", CheckoutKey.DECIMAL, tokenDecimals = 6)

        assertEquals("0.", amount)
        assertEquals("0.5", applyCheckoutKey(amount, CheckoutKey.FIVE, tokenDecimals = 6))
    }

    @Test
    fun `leading zero is replaced and repeated zero is ignored`() {
        assertEquals("7", applyCheckoutKey("0", CheckoutKey.SEVEN, tokenDecimals = 2))
        assertEquals("0", applyCheckoutKey("0", CheckoutKey.ZERO, tokenDecimals = 2))
    }

    @Test
    fun `backspace supports correction without changing precision rules`() {
        val corrected = applyCheckoutKey("12.30", CheckoutKey.BACKSPACE, tokenDecimals = 2)

        assertEquals("12.3", corrected)
        assertTrue(isSubmittableCheckoutAmount(corrected, tokenDecimals = 2))
    }

    @Test
    fun `zero incomplete and excess precision amounts cannot be submitted`() {
        assertFalse(isSubmittableCheckoutAmount("", tokenDecimals = 2))
        assertFalse(isSubmittableCheckoutAmount("0", tokenDecimals = 2))
        assertFalse(isSubmittableCheckoutAmount("1.", tokenDecimals = 2))
        assertFalse(isSubmittableCheckoutAmount("1.001", tokenDecimals = 2))
    }

    @Test
    fun `uint256 boundary remains available but overflow is rejected`() {
        val maximum = "115792089237316195423570985008687907853269984665640564039457584007913129639935"

        assertTrue(isPotentialCheckoutAmount(maximum, tokenDecimals = 0))
        assertTrue(isSubmittableCheckoutAmount(maximum, tokenDecimals = 0))
        assertFalse(isPotentialCheckoutAmount(maximum + "0", tokenDecimals = 0))
    }

    @Test
    fun `zero-decimal token does not accept a decimal separator`() {
        assertEquals("1", applyCheckoutKey("1", CheckoutKey.DECIMAL, tokenDecimals = 0))
        assertFalse(isPotentialCheckoutAmount("1.", tokenDecimals = 0))
    }

    @Test
    fun `empty amount placeholder respects token display precision`() {
        assertEquals("0", checkoutAmountPlaceholder(tokenDecimals = 0))
        assertEquals("0.0", checkoutAmountPlaceholder(tokenDecimals = 1))
        assertEquals("0.00", checkoutAmountPlaceholder(tokenDecimals = 2))
        assertEquals("0.00", checkoutAmountPlaceholder(tokenDecimals = 18))
    }

    @Test
    fun `entered display text is never replaced by the placeholder`() {
        assertEquals("1.", checkoutAmountDisplay("1.", tokenDecimals = 2))
        assertEquals("0.00", checkoutAmountDisplay("", tokenDecimals = 2))
    }

    @Test
    fun `keypad correction controls have explicit accessibility labels`() {
        assertEquals("Decimal point", CheckoutKey.DECIMAL.accessibilityLabel())
        assertEquals("Delete last digit", CheckoutKey.BACKSPACE.accessibilityLabel())
        assertEquals("Clear amount", CLEAR_AMOUNT_ACCESSIBILITY_LABEL)
    }

    @Test
    fun `repository failure blocks checkout and survives amount edits`() {
        val failed = CreateInvoiceState(amount = "1.", isCreating = true)
            .withRepositoryFailure("RPC chain ID mismatch")
        val edited = failed.withEditedAmount("1.2")

        assertTrue(failed.readinessInvalidated)
        assertEquals(1, failed.readinessFailureSequence)
        assertEquals("RPC chain ID mismatch", edited.error)
        assertEquals("RPC chain ID mismatch", edited.repositoryFailure)
    }

    @Test
    fun `only a successful fresh readiness result reopens checkout`() {
        val failed = CreateInvoiceState()
            .withRepositoryFailure("Token is not whitelisted")

        assertTrue(failed.afterReadinessRefresh(ready = false).readinessInvalidated)
        val refreshed = failed.afterReadinessRefresh(ready = true)
        assertFalse(refreshed.readinessInvalidated)
        assertEquals(null, refreshed.error)
        assertEquals(null, refreshed.repositoryFailure)
    }

    @Test
    fun `long CTA is concise while retaining the exact accessible amount`() {
        val amount = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        val copy = checkoutActionCopy(amount, "AUD")

        assertTrue(copy.amountIsCondensed)
        assertEquals("Show payment QR · Amount shown above", copy.visibleLabel)
        assertEquals("Show payment QR for $amount AUD", copy.accessibilityLabel)
    }

    @Test
    fun `ready presentation requires every fresh safety gate`() {
        assertTrue(
            isCheckoutReady(
                TerminalSetupStatus.READY,
                configurationValidated = true,
                refreshing = false,
                readinessInvalidated = false,
                operatorWalletReady = true,
                hasSelectedToken = true,
            ),
        )
        // Low gas warns but never blocks a sale: the customer's funds land at the receiver
        // regardless, and settlement waits for funding.
        assertTrue(
            isCheckoutReady(
                TerminalSetupStatus.AWAITING_GAS,
                configurationValidated = true,
                refreshing = false,
                readinessInvalidated = false,
                operatorWalletReady = true,
                hasSelectedToken = true,
            ),
        )
        assertFalse(
            isCheckoutReady(
                TerminalSetupStatus.AWAITING_AUTHORIZATION,
                configurationValidated = true,
                refreshing = false,
                readinessInvalidated = false,
                operatorWalletReady = true,
                hasSelectedToken = true,
            ),
        )
        assertFalse(readyWith(configurationValidated = false))
        assertFalse(readyWith(refreshing = true))
        assertFalse(readyWith(readinessInvalidated = true))
        assertFalse(readyWith(operatorWalletReady = false))
        assertFalse(readyWith(hasSelectedToken = false))
    }

    @Test
    fun `only proven checkout-capable statuses may release checkout`() {
        TerminalSetupStatus.entries.forEach { status ->
            assertEquals(
                status == TerminalSetupStatus.READY || status == TerminalSetupStatus.AWAITING_GAS,
                statusAllowsCheckout(status),
            )
        }
    }

    private fun readyWith(
        configurationValidated: Boolean = true,
        refreshing: Boolean = false,
        readinessInvalidated: Boolean = false,
        operatorWalletReady: Boolean = true,
        hasSelectedToken: Boolean = true,
    ): Boolean = isCheckoutReady(
        TerminalSetupStatus.READY,
        configurationValidated,
        refreshing,
        readinessInvalidated,
        operatorWalletReady,
        hasSelectedToken,
    )
}
