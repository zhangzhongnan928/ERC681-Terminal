package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOperatorBindingPolicyTest {
    @Test
    fun invoiceFailureRequiresARefreshThatStartsAfterTheFailure() {
        assertTrue(
            shouldRestartActiveReadinessRefresh(
                ReadinessRefreshTrigger.INVOICE_FAILURE,
                refreshActive = true,
            ),
        )
        assertFalse(
            shouldRestartActiveReadinessRefresh(
                ReadinessRefreshTrigger.NORMAL,
                refreshActive = true,
            ),
        )
        assertFalse(
            shouldRestartActiveReadinessRefresh(
                ReadinessRefreshTrigger.INVOICE_FAILURE,
                refreshActive = false,
            ),
        )
        assertTrue(
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.READY,
            ),
        )
        assertFalse(
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = false,
                setupStatus = TerminalSetupStatus.READY,
            ),
        )
    }

    @Test
    fun fundingQrRequiresReadyWalletBoundToProvisioningQrOperator() {
        assertEquals(
            "ethereum:$OPERATOR@84532",
            operatorFundingPayload(config(), wallet()),
        )
        assertNull(operatorFundingPayload(config().copy(provisionedOperatorAddress = null), wallet()))
        assertNull(operatorFundingPayload(config().copy(provisionedOperatorAddress = OTHER), wallet()))
        assertNull(operatorFundingPayload(config().copy(provisioned = false), wallet()))
        assertNull(operatorFundingPayload(config().copy(chainId = 1), wallet()))
        assertNull(
            operatorFundingPayload(
                config().copy(factoryAddress = "0x3333333333333333333333333333333333333333"),
                wallet(),
            ),
        )
        assertNull(
            operatorFundingPayload(
                config(),
                OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED),
            ),
        )
    }

    @Test
    fun automaticReadinessDefersToCheckoutWithoutRunningRpcBlock() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val scheduler = ReadinessRpcScheduler(coordinator)
        val checkoutEntered = CompletableDeferred<Unit>()
        val releaseCheckout = CompletableDeferred<Unit>()
        val checkout = launch {
            coordinator.withInteractiveOperation {
                checkoutEntered.complete(Unit)
                releaseCheckout.await()
            }
        }
        checkoutEntered.await()
        var readinessRan = false

        val result = scheduler.run(ReadinessRpcPriority.AUTOMATIC) {
            readinessRan = true
            "ready"
        }

        assertNull(result)
        assertFalse(readinessRan)
        releaseCheckout.complete(Unit)
        checkout.join()
    }

    @Test
    fun manualReadinessAndUserMutationPublishPriorityBeforeBackground() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val scheduler = ReadinessRpcScheduler(coordinator)
        val manualEntered = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        val manual = launch {
            scheduler.run(ReadinessRpcPriority.INTERACTIVE) {
                manualEntered.complete(Unit)
                releaseManual.await()
                Unit
            }
        }
        manualEntered.await()
        assertNull(coordinator.withBackgroundOperation { "background" })
        releaseManual.complete(Unit)
        manual.join()

        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = launch {
            runUserRpcMutation(coordinator) {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()
        assertNull(coordinator.withBackgroundOperation { "background" })
        releaseMutation.complete(Unit)
        mutation.join()
    }

    @Test
    fun automaticReadinessSocketBudgetFitsBackgroundLease() {
        val budgetMillis = SettingsViewModel.AUTOMATIC_RPC_WAVES *
            (SettingsViewModel.AUTOMATIC_RPC_CONNECT_TIMEOUT_MILLIS +
                SettingsViewModel.AUTOMATIC_RPC_READ_TIMEOUT_MILLIS)

        assertTrue(budgetMillis < RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS)
    }

    private fun wallet() = OperatorWalletSnapshot(
        availability = OperatorWalletAvailability.READY,
        address = OPERATOR,
    )

    private fun config() = TerminalConfigSnapshot(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84532,
        factoryAddress = "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
        receiverImplementationAddress = "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
        vaultAddress = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
        confirmationBlocks = 2,
        paymentTokens = listOf(
            PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
        ),
        protocolVersion = "1.4.1",
        provisionedOperatorAddress = OPERATOR,
        provisioned = true,
    )

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val OTHER = "0x2222222222222222222222222222222222222222"
    }
}
