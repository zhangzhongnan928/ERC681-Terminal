package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class PaymentProfileRemovalPolicyTest {
    @Test
    fun lastProfileRemovalSuccessExplainsCheckoutIsUnavailableUntilSetup() {
        val message = paymentProfileRemovalSuccessMessage(profile(), remainingProfileCount = 0)

        assertTrue(message.contains("No payment profiles remain"))
        assertTrue(message.contains("Checkout is unavailable"))
        assertTrue(message.contains("add a portal payment profile in setup"))
        assertTrue(message.contains("Existing invoices and settlements are unchanged"))
    }

    @Test
    fun removalWithProfilesRemainingDoesNotClaimCheckoutIsUnavailable() {
        val message = paymentProfileRemovalSuccessMessage(profile(), remainingProfileCount = 2)

        assertTrue(message.contains("2 payment profile(s) remain"))
        assertFalse(message.contains("Checkout is unavailable"))
    }

    @Test
    fun removalWaitsUntilInvoicePublicationLeavesLifecycleGate() = runBlocking {
        val gate = TerminalLifecycleGate()
        val invoiceReachedFinalCheck = CompletableDeferred<Unit>()
        val allowInvoicePublication = CompletableDeferred<Unit>()
        val removed = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val invoice = async(Dispatchers.Default) {
            gate.withExclusiveMutation {
                events += "invoice-final-check"
                invoiceReachedFinalCheck.complete(Unit)
                allowInvoicePublication.await()
                events += "invoice-published"
            }
        }
        invoiceReachedFinalCheck.await()

        val removal = async(Dispatchers.Default) {
            removePaymentProfileExclusively(
                lifecycleGate = gate,
                profileId = "profile-a",
                commitWithAuthorization = { commit -> commit() },
                removeProfile = {
                    removed.set(true)
                    events += "profile-removed"
                    true
                },
            )
        }

        delay(20)
        assertFalse(removed.get())
        allowInvoicePublication.complete(Unit)
        invoice.await()
        removal.await()

        assertTrue(removed.get())
        assertEquals(
            listOf("invoice-final-check", "invoice-published", "profile-removed"),
            events,
        )
    }

    private fun profile() = TerminalPaymentProfile(
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
}
