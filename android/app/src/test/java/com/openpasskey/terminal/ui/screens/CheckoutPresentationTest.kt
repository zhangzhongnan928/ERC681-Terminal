package com.openpasskey.terminal.ui.screens

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutPresentationTest {
    @Test
    fun `gas blocker uses readable network native amount`() {
        val profile = TerminalPaymentProfile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84_532,
            factoryAddress = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
            receiverImplementationAddress = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
            vaultAddress = "0x1111111111111111111111111111111111111111",
            confirmationBlocks = 2,
            token = PaymentToken(
                "0x2222222222222222222222222222222222222222",
                "AUDM",
                18,
            ),
            protocolVersion = "1.6",
        )

        val copy = checkoutBlockerCopy(
            TerminalSetupStatus.AWAITING_GAS,
            selectedProfile = profile,
        )

        assertTrue(copy.detail.contains("0.0001 ETH"))
        assertFalse(copy.detail.contains("wei"))
    }

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
