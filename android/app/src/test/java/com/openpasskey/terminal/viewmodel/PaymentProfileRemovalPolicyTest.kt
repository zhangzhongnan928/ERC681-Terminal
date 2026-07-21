package com.openpasskey.terminal.viewmodel

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
}
