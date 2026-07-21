package com.openpasskey.terminal.lifecycle

import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.settlement.SettlementChainClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class TerminalLifecycleGateTest {
    @Test
    fun processGateSerializesConcurrentCriticalSections() = runBlocking {
        val gate = TerminalLifecycleGate()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        (1..20).map {
            async(Dispatchers.Default) {
                gate.withExclusiveMutation {
                    val nowActive = active.incrementAndGet()
                    maximum.accumulateAndGet(nowActive, ::maxOf)
                    delay(2)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }

    @Test
    fun resetClearsProvisioningBeforeWalletDeletionAndStaysClearedOnDeleteFailure() = runBlocking {
        val events = mutableListOf<String>()
        var provisioned = true
        val coordinator = TerminalResetCoordinator(
            lifecycleGate = TerminalLifecycleGate(),
            resetGuard = OperatorResetGuard { false },
            nativeBalanceReader = emptyBalanceReader(),
            clearProvisioning = {
                events += "clear"
                provisioned = false
                true
            },
            deleteWallet = {
                events += "delete"
                throw IllegalStateException("Keystore deletion failed")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.reset(OPERATOR) { commit -> commit() } }
        }
        assertEquals(listOf("clear", "delete"), events)
        assertFalse(provisioned)
    }

    @Test
    fun blockingStateOrFailedConfigClearNeverDeletesWallet() = runBlocking {
        var deleted = false
        val blocked = TerminalResetCoordinator(
            TerminalLifecycleGate(),
            OperatorResetGuard { true },
            emptyBalanceReader(),
            { true },
            { deleted = true },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { blocked.reset(OPERATOR) { commit -> commit() } }
        }
        assertFalse(deleted)

        val failedClear = TerminalResetCoordinator(
            TerminalLifecycleGate(),
            OperatorResetGuard { false },
            emptyBalanceReader(),
            { false },
            { deleted = true },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { failedClear.reset(OPERATOR) { commit -> commit() } }
        }
        assertFalse(deleted)
    }

    @Test
    fun fundingMutationAndResetCannotInterleaveAcrossTheProcessGate() = runBlocking {
        val gate = TerminalLifecycleGate()
        val mutationStarted = CompletableDeferred<Unit>()
        var invoiceHasUnsettledValue = false
        var deleted = false

        val observation = async(Dispatchers.Default) {
            gate.withExclusiveMutation {
                mutationStarted.complete(Unit)
                delay(20)
                invoiceHasUnsettledValue = true
            }
        }
        mutationStarted.await()

        val coordinator = TerminalResetCoordinator(
            lifecycleGate = gate,
            resetGuard = OperatorResetGuard { invoiceHasUnsettledValue },
            nativeBalanceReader = emptyBalanceReader(),
            clearProvisioning = { true },
            deleteWallet = { deleted = true },
        )
        val reset = async(Dispatchers.Default) {
            runCatching { coordinator.reset(OPERATOR) { commit -> commit() } }
        }

        observation.await()
        val resetResult = reset.await()
        assertTrue(resetResult.isFailure)
        assertFalse(deleted)
    }

    @Test
    fun fundedOrUncertainOperatorBalanceNeverDeletesTheKey() = runBlocking {
        var deleted = false
        var cleared = false
        val funded = TerminalResetCoordinator(
            TerminalLifecycleGate(),
            OperatorResetGuard { false },
            OperatorNativeBalanceReader {
                OperatorNativeBalances(BigInteger.ONE, BigInteger.ONE)
            },
            { cleared = true; true },
            { deleted = true },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { funded.reset(OPERATOR) { commit -> commit() } }
        }
        assertFalse(cleared)
        assertFalse(deleted)

        val unreadable = TerminalResetCoordinator(
            TerminalLifecycleGate(),
            OperatorResetGuard { false },
            OperatorNativeBalanceReader { error("RPC unavailable") },
            { cleared = true; true },
            { deleted = true },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { unreadable.reset(OPERATOR) { commit -> commit() } }
        }
        assertFalse(cleared)
        assertFalse(deleted)
    }

    @Test
    fun resetRechecksLatestAndPendingImmediatelyBeforeDeletion() = runBlocking {
        var reads = 0
        var cleared = false
        var deleted = false
        val coordinator = TerminalResetCoordinator(
            TerminalLifecycleGate(),
            OperatorResetGuard { false },
            OperatorNativeBalanceReader {
                reads += 1
                if (reads == 1) OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO)
                else OperatorNativeBalances(BigInteger.ONE, BigInteger.ZERO)
            },
            { cleared = true; true },
            { deleted = true },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.reset(OPERATOR) { commit -> commit() } }
        }
        assertEquals(2, reads)
        assertFalse(cleared)
        assertFalse(deleted)
    }

    @Test
    fun pendingZeroDoesNotHideCanonicalFundsDuringReset() {
        assertThrows(IllegalStateException::class.java) {
            requireOperatorNativeBalancesEmpty(
                OperatorNativeBalances(latest = BigInteger.ONE, pending = BigInteger.ZERO),
            )
        }
    }

    @Test
    fun productionBalanceReaderIgnoresPersistedRpcOverrideAndChecksRemovedProfileNetworks() = runBlocking {
        val profile = KnownChainPolicy.requireProfile(84532)
        val openedUrls = mutableListOf<String>()
        val reader = RpcOperatorNativeBalanceReader(
            configSnapshot = {
                TerminalConfigSnapshot(
                    networkName = profile.networkName,
                    rpcUrl = "https://merchant-controlled.example",
                    chainId = profile.chainId,
                    factoryAddress = profile.factory.value,
                    receiverImplementationAddress = profile.receiverImplementation.value,
                    vaultAddress = profile.fixtureVault.value,
                    confirmationBlocks = 2,
                    paymentTokens = emptyList(),
                    protocolVersion = profile.protocolVersion,
                    provisionedOperatorAddress = OPERATOR,
                    provisioned = true,
                )
            },
            clientFactory = { url ->
                openedUrls += url
                val openedProfile = KnownChainPolicy.enabledProfiles().first { it.rpcUrl == url }
                @Suppress("UNCHECKED_CAST")
                Proxy.newProxyInstance(
                    SettlementChainClient::class.java.classLoader,
                    arrayOf(SettlementChainClient::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "chainId" -> openedProfile.chainId
                        "latestNativeBalance", "nativeBalance" -> BigInteger.ZERO
                        "close" -> Unit
                        else -> error("Unexpected balance-reader RPC call: ${method.name}")
                    }
                } as SettlementChainClient
            },
        )

        assertEquals(OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO), reader.read(OPERATOR))
        assertEquals(
            KnownChainPolicy.enabledProfiles().map { it.rpcUrl }.toSet(),
            openedUrls.toSet(),
        )
        assertFalse(openedUrls.contains("https://merchant-controlled.example"))
    }

    @Test
    fun productionBalanceReaderChecksEveryEnabledNetworkTrustRoot() = runBlocking {
        val knownProfiles = KnownChainPolicy.enabledProfiles()
        val profiles = knownProfiles.map { known ->
            TerminalPaymentProfile(
                networkName = known.networkName,
                rpcUrl = "https://merchant-controlled.example",
                chainId = known.chainId,
                factoryAddress = known.factory.value,
                receiverImplementationAddress = known.receiverImplementation.value,
                vaultAddress = known.fixtureVault.value,
                confirmationBlocks = 2,
                token = PaymentToken("0x1111111111111111111111111111111111111111", "T", 18),
                protocolVersion = known.protocolVersion,
            )
        }
        val active = profiles.first()
        val opened = mutableListOf<String>()
        val reader = RpcOperatorNativeBalanceReader(
            configSnapshot = {
                TerminalConfigSnapshot(
                    networkName = active.networkName,
                    rpcUrl = active.rpcUrl,
                    chainId = active.chainId,
                    factoryAddress = active.factoryAddress,
                    receiverImplementationAddress = active.receiverImplementationAddress,
                    vaultAddress = active.vaultAddress,
                    confirmationBlocks = active.confirmationBlocks,
                    paymentTokens = listOf(active.token),
                    protocolVersion = active.protocolVersion,
                    provisionedOperatorAddress = OPERATOR,
                    provisioned = true,
                    paymentProfiles = profiles,
                    selectedProfileId = active.id,
                )
            },
            clientFactory = { url ->
                opened += url
                val chain = knownProfiles.first { it.rpcUrl == url }.chainId
                @Suppress("UNCHECKED_CAST")
                Proxy.newProxyInstance(
                    SettlementChainClient::class.java.classLoader,
                    arrayOf(SettlementChainClient::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "chainId" -> chain
                        "latestNativeBalance" -> BigInteger.valueOf(chain % 10)
                        "nativeBalance" -> BigInteger.valueOf((chain % 10) + 1)
                        "close" -> Unit
                        else -> error("Unexpected balance-reader RPC call: ${method.name}")
                    }
                } as SettlementChainClient
            },
        )

        assertEquals(OperatorNativeBalances(BigInteger.valueOf(2), BigInteger.valueOf(3)), reader.read(OPERATOR))
        assertEquals(knownProfiles.map { it.rpcUrl }.toSet(), opened.toSet())
    }

    private fun emptyBalanceReader() = OperatorNativeBalanceReader {
        OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO)
    }

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
    }
}
