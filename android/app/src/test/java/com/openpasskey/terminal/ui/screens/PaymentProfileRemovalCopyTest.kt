package com.openpasskey.terminal.ui.screens

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentProfileRemovalCopyTest {
    @Test
    fun lastProfileConfirmationExplainsCheckoutAndSetupConsequences() {
        val message = paymentProfileRemovalConfirmationMessage(profile(), configuredProfileCount = 1)

        assertTrue(message.contains("last payment profile"))
        assertTrue(message.contains("disables Checkout"))
        assertTrue(message.contains("returns the terminal to setup"))
        assertTrue(message.contains("Existing invoices"))
    }

    @Test
    fun nonLastProfileConfirmationDoesNotClaimCheckoutWillBeDisabled() {
        val message = paymentProfileRemovalConfirmationMessage(profile(), configuredProfileCount = 2)

        assertFalse(message.contains("disables Checkout"))
        assertTrue(message.contains("Existing invoices"))
    }

    private fun profile() = TerminalPaymentProfile(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84_532,
        factoryAddress = "0xb69f725999266c6757284ca4169275c3ebde491a",
        receiverImplementationAddress = "0x8ba9739741ecc79b5d69fe5580d2966092e6f77f",
        vaultAddress = "0x1111111111111111111111111111111111111111",
        confirmationBlocks = 2,
        token = PaymentToken(
            "0x2222222222222222222222222222222222222222",
            "AUDM",
            18,
        ),
        protocolVersion = "1.5",
    )
}
