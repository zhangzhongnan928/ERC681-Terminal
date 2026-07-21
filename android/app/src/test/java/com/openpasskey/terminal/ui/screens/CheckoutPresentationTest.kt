package com.openpasskey.terminal.ui.screens

import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckoutPresentationTest {
    @Test
    fun `early setup blockers lead to the setup flow`() {
        listOf(
            TerminalSetupStatus.CREATE_WALLET,
            TerminalSetupStatus.SET_ADMIN_PIN,
            TerminalSetupStatus.SCAN_PORTAL,
        ).forEach { status ->
            assertEquals("Finish terminal setup", checkoutBlockerCopy(status).actionLabel)
        }
    }

    @Test
    fun `later degraded states lead to Settings`() {
        listOf(
            TerminalSetupStatus.PROVISIONING,
            TerminalSetupStatus.AWAITING_AUTHORIZATION,
            TerminalSetupStatus.AWAITING_GAS,
            TerminalSetupStatus.READY,
            TerminalSetupStatus.ERROR,
        ).forEach { status ->
            assertEquals("Open Settings", checkoutBlockerCopy(status).actionLabel)
        }
    }

    @Test
    fun `error blocker preserves the exact validation message`() {
        val copy = checkoutBlockerCopy(
            TerminalSetupStatus.ERROR,
            "Receiver implementation pin mismatch",
        )

        assertEquals("Terminal setup needs attention", copy.title)
        assertEquals("Receiver implementation pin mismatch", copy.detail)
    }
}
