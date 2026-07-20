package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.lifecycle.OperatorNativeBalanceReader
import com.openpasskey.terminal.lifecycle.OperatorNativeBalances
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.settlement.OperatorResetGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class AdminSessionGateTest {
    @Test
    fun backgroundLockInvalidatesVerificationThatCompletesLater() {
        val session = AdminSessionGate()
        val inFlightAttempt = session.beginUnlock()

        assertFalse(session.lock())
        assertFalse(session.completeUnlock(inFlightAttempt))
        assertFalse(session.isUnlocked())
    }

    @Test
    fun onlyTheLatestNonInvalidatedAttemptCanUnlock() {
        val session = AdminSessionGate()
        val staleAttempt = session.beginUnlock()
        val currentAttempt = session.beginUnlock()

        assertFalse(session.completeUnlock(staleAttempt))
        assertTrue(session.completeUnlock(currentAttempt))
        assertTrue(session.isUnlocked())

        assertTrue(session.lock())
        assertFalse(session.isUnlocked())
    }

    @Test
    fun initialPinCompletionUnlocksSetupAndPostResetCreationRequiresThatSession() {
        val session = AdminSessionGate()
        assertNull(
            requireAdminAuthorizationEpoch(
                pinConfigured = false,
                session = session,
                action = "creating a terminal wallet",
            ),
        )

        val initialPinEpoch = session.beginUnlock()
        assertTrue(session.completeUnlock(initialPinEpoch))
        val authorizedEpoch = requireNotNull(
            requireAdminAuthorizationEpoch(true, session, "provisioning this terminal"),
        )
        assertTrue(session.isAuthorized(authorizedEpoch))

        session.lock() // successful provisioning/reset/backgrounding ends the local admin session
        assertThrows(IllegalStateException::class.java) {
            requireAdminAuthorizationEpoch(true, session, "creating a terminal wallet")
        }
        assertThrows(IllegalStateException::class.java) {
            requireAdminAuthorizationEpoch(true, session, "provisioning this terminal")
        }
    }

    @Test
    fun backgroundLockInvalidatesCapturedWalletAndProvisioningAuthorization() {
        val session = AdminSessionGate()
        val unlock = session.beginUnlock()
        assertTrue(session.completeUnlock(unlock))
        val operationEpoch = requireNotNull(session.unlockedEpochOrNull())

        session.lock()

        assertFalse(session.isAuthorized(operationEpoch))
        assertThrows(IllegalStateException::class.java) {
            session.withAuthorization(operationEpoch) { error("must not execute") }
        }
    }

    @Test
    fun backgroundLockDuringResetRpcWaitPreventsConfigClearAndKeyDeletion() = runBlocking {
        val session = AdminSessionGate()
        val unlock = session.beginUnlock()
        assertTrue(session.completeUnlock(unlock))
        val resetEpoch = requireNotNull(session.unlockedEpochOrNull())
        val secondReadStarted = CompletableDeferred<Unit>()
        val allowSecondReadToFinish = CompletableDeferred<Unit>()
        var reads = 0
        var cleared = false
        var deleted = false
        val coordinator = TerminalResetCoordinator(
            lifecycleGate = TerminalLifecycleGate(),
            resetGuard = OperatorResetGuard { false },
            nativeBalanceReader = OperatorNativeBalanceReader {
                reads += 1
                if (reads == 2) {
                    secondReadStarted.complete(Unit)
                    allowSecondReadToFinish.await()
                }
                OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO)
            },
            clearProvisioning = {
                cleared = true
                true
            },
            deleteWallet = { deleted = true },
        )
        val reset = async(Dispatchers.Default) {
            runCatching {
                coordinator.reset(OPERATOR) { commit ->
                    session.withAuthorization(resetEpoch, commit)
                }
            }
        }

        secondReadStarted.await()
        session.lock() // app entered the background while the final RPC check was suspended
        allowSecondReadToFinish.complete(Unit)

        assertTrue(reset.await().isFailure)
        assertFalse(cleared)
        assertFalse(deleted)
    }

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
    }
}
