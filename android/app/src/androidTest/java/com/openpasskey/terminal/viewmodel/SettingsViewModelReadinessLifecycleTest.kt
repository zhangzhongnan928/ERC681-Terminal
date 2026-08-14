package com.openpasskey.terminal.viewmodel

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openpasskey.erc681.RpcTransportException
import com.openpasskey.terminal.admin.AdminPinStore
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.lifecycle.RpcOperatorNativeBalanceReader
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.TerminalProvisioner
import com.openpasskey.terminal.rpc.PinnedRpcEndpointVerifier
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real SettingsViewModel refresh, load(), admin, receipt, and invalidation paths
 * with scripted readiness results, so removing the preserved-notice propagation or one of its
 * begin/end transitions fails here instead of only in a standalone-holder unit test.
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelReadinessLifecycleTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferenceFiles = listOf(
        "opk_chain_config",
        "opk_rpc_endpoint_secrets_v1",
        "opk_operator_wallet_v1",
        "opk_local_admin_pin_v1",
    )

    @Before
    fun clearPreferences() {
        preferenceFiles.forEach { name ->
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit(),
            )
        }
    }

    @After
    fun cleanUpPreferences() = clearPreferences()

    @Test
    fun preservedNoticeSurvivesUnrelatedUpdatesAndEndsOnlyOnLifecycleEvents() = runBlocking {
        val walletStore = OperatorWalletStore(context)
        val operator = requireNotNull(walletStore.createWallet().address)
        val adminPinStore = AdminPinStore(context)
        adminPinStore.setInitialPin(PIN)
        val chainConfig = ChainConfig(context)
        val provisioned = TerminalConfigSnapshot(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84_532,
            factoryAddress = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
            receiverImplementationAddress = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
            vaultAddress = "0x1111111111111111111111111111111111111111",
            confirmationBlocks = 2,
            paymentTokens = listOf(
                PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
            ),
            protocolVersion = "1.6",
            provisionedOperatorAddress = operator,
            provisioned = true,
        )
        assertTrue(chainConfig.compareAndReplaceProvisioned(chainConfig.snapshot(), provisioned))
        val lifecycleGate = TerminalLifecycleGate()
        val rpcEndpointStore = chainConfig.rpcEndpointStore
        val prover = ScriptedReadinessProver(
            KnownChainPolicy.requireProfile(84_532).minimumOperatorNativeReserve,
        )
        val viewModel = SettingsViewModel(
            chainConfig,
            walletStore,
            adminPinStore,
            TerminalProvisioner(
                snapshot = chainConfig::snapshot,
                compareAndCommit = chainConfig::compareAndReplaceProvisioned,
                currentWalletSnapshot = walletStore::snapshot,
                lifecycleGate = lifecycleGate,
                rpcEndpointResolver = rpcEndpointStore,
            ),
            TerminalResetCoordinator(
                lifecycleGate,
                OperatorResetGuard { false },
                RpcOperatorNativeBalanceReader(
                    configSnapshot = chainConfig::snapshot,
                    rpcEndpointResolver = rpcEndpointStore,
                ),
                chainConfig::clearProvisioning,
                walletStore::resetWalletAfterExplicitConfirmation,
            ),
            lifecycleGate,
            RpcWorkCoordinator(),
            rpcEndpointStore,
            PinnedRpcEndpointVerifier(),
            readinessProverOverride = prover,
        )

        // The construction-time automatic refresh proves fresh readiness.
        awaitState(viewModel) {
            it.setupStatus == TerminalSetupStatus.READY && it.preservedReadinessNotice == null
        }

        // A transient production failure preserves the proven result and raises the notice.
        prover.failNext(RpcTransportException())
        viewModel.refreshOperatorStatus()
        val preserved = awaitState(viewModel) { it.preservedReadinessNotice != null }
        assertEquals(TerminalSetupStatus.READY, preserved.setupStatus)
        val notice = requireNotNull(preserved.preservedReadinessNotice)
        assertEquals(notice, preserved.message)

        // Unlocking admin and saving receipt details rebuild state without touching the notice.
        viewModel.unlockAdmin(PIN)
        val unlocked = awaitState(viewModel) { it.adminUnlocked }
        assertEquals(notice, unlocked.preservedReadinessNotice)

        viewModel.updateMerchantReceiptProfile("Lifecycle Cafe", "")
        val savedReceipt = awaitState(viewModel) {
            it.merchantReceiptName == "Lifecycle Cafe" && !it.savingMerchantReceiptProfile
        }
        assertEquals(notice, savedReceipt.preservedReadinessNotice)

        // lockAdmin() replaces the generic message; the dedicated notice must survive verbatim
        // so Checkout can never present the admin status line as an RPC-staleness banner.
        viewModel.lockAdmin()
        val locked = awaitState(viewModel) { !it.adminUnlocked && it.message != notice }
        assertEquals("Admin/setup controls locked.", locked.message)
        assertEquals(notice, locked.preservedReadinessNotice)

        // A fresh successful proof ends the preserved window.
        viewModel.refreshOperatorStatus()
        val refreshed = awaitState(viewModel) {
            it.setupStatus == TerminalSetupStatus.READY &&
                !it.refreshingOperator &&
                it.preservedReadinessNotice == null
        }
        assertEquals("Terminal is ready to create payments.", refreshed.message)

        // Configuration invalidation ends the window; the restarted pass fails transiently
        // with no prior proof for its generation, so readiness demotes instead of preserving.
        prover.failNext(RpcTransportException())
        viewModel.refreshOperatorStatusAfterProfileSelection { }
        val invalidated = awaitState(viewModel) {
            it.setupStatus == TerminalSetupStatus.ERROR && !it.refreshingOperator
        }
        assertNull(invalidated.preservedReadinessNotice)
        assertNotNull(invalidated.message)
        assertNotEquals(notice, invalidated.message)
    }

    private suspend fun awaitState(
        viewModel: SettingsViewModel,
        predicate: (SettingsState) -> Boolean,
    ): SettingsState = withTimeout(STATE_TIMEOUT_MILLIS) {
        viewModel.state.first(predicate)
    }

    private class ScriptedReadinessProver(
        private val provenBalance: java.math.BigInteger,
    ) : OperatorReadinessProver {
        @Volatile
        private var nextError: Exception? = null

        fun failNext(error: Exception) {
            nextError = error
        }

        override suspend fun prove(
            config: TerminalConfigSnapshot,
            priority: ReadinessRpcPriority,
        ): OperatorChainStatus {
            nextError?.let { error ->
                nextError = null
                throw error
            }
            return OperatorChainStatus(balance = provenBalance, authorized = true)
        }
    }

    private companion object {
        const val PIN = "123456"
        const val STATE_TIMEOUT_MILLIS = 10_000L
    }
}
