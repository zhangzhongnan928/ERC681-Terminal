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

class NetworkConfirmationPolicyTest {
    @Test
    fun successCopyDoesNotReportAPreMutationProfileCount() {
        val singular = networkConfirmationUpdateSuccessMessage("Base Sepolia", 1)
        val plural = networkConfirmationUpdateSuccessMessage("Base Sepolia", 7)

        assertTrue(singular.contains("requires 1 confirmation"))
        assertTrue(plural.contains("requires 7 confirmations"))
        assertTrue(plural.contains("all configured payment profiles on this network"))
        assertTrue(plural.contains("Existing invoices keep their original policy"))
        assertFalse(plural.contains("payment profile(s)"))
    }

    @Test
    fun updateRequiresTheCurrentUnlockedAdminEpoch() = runBlocking {
        val gate = TerminalLifecycleGate()
        val session = AdminSessionGate()
        val epoch = session.beginUnlock()
        assertTrue(session.completeUnlock(epoch))
        var updateCount = 0

        updateNetworkConfirmationBlocksExclusively(
            lifecycleGate = gate,
            chainId = 84_532,
            confirmationBlocks = 1,
            commitWithAuthorization = { commit -> session.withAuthorization(epoch, commit) },
            update = { _, _ ->
                updateCount += 1
                true
            },
        )
        assertEquals(1, updateCount)

        session.lock()
        val failure = runCatching {
            updateNetworkConfirmationBlocksExclusively(
                lifecycleGate = gate,
                chainId = 84_532,
                confirmationBlocks = 2,
                commitWithAuthorization = { commit -> session.withAuthorization(epoch, commit) },
                update = { _, _ ->
                    updateCount += 1
                    true
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, updateCount)
    }

    @Test
    fun updateWaitsUntilInvoicePublicationLeavesLifecycleGate() = runBlocking {
        val gate = TerminalLifecycleGate()
        val invoiceReachedFinalCheck = CompletableDeferred<Unit>()
        val allowInvoicePublication = CompletableDeferred<Unit>()
        val updated = AtomicBoolean(false)
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

        val update = async(Dispatchers.Default) {
            updateNetworkConfirmationBlocksExclusively(
                lifecycleGate = gate,
                chainId = 84_532,
                confirmationBlocks = 1,
                commitWithAuthorization = { commit -> commit() },
                update = { chainId, confirmations ->
                    assertEquals(84_532, chainId)
                    assertEquals(1, confirmations)
                    updated.set(true)
                    events += "confirmations-updated"
                    true
                },
            )
        }

        delay(20)
        assertFalse(updated.get())
        allowInvoicePublication.complete(Unit)
        invoice.await()
        update.await()

        assertTrue(updated.get())
        assertEquals(
            listOf("invoice-final-check", "invoice-published", "confirmations-updated"),
            events,
        )
    }
}
