package com.openpasskey.terminal.lifecycle

import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.settlement.SettlementChainClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigInteger

class TerminalResetDiagnosticsTest {
    @Test
    fun fundedBalanceNamesItsNetworkAndDoesNotCollapseBalancesAcrossChains() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireOperatorNativeBalancesEmpty(
                listOf(
                    balance("Base Sepolia", 84532, "ETH", BigInteger.ZERO, BigInteger.ZERO),
                    balance("Example EVM", 11155111, "TETH", BigInteger.ONE, BigInteger.ZERO),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("Example EVM (chain 11155111)"))
        assertTrue(error.message.orEmpty().contains("TETH"))
        assertFalse(error.message.orEmpty().contains("Base Sepolia"))
    }

    @Test
    fun unreachableTrustedRpcNamesTheNetworkAndChainAtTheResetBoundary() = runBlocking {
        val known = KnownChainPolicy.defaultProfile()
        val reader = RpcOperatorNativeBalanceReader(
            configSnapshot = { unprovisionedSnapshot(known.chainId) },
            clientFactory = { error("RPC offline") },
        )
        var deleted = false
        val coordinator = TerminalResetCoordinator(
            lifecycleGate = TerminalLifecycleGate(),
            resetGuard = OperatorResetGuard { false },
            nativeBalanceReader = reader,
            clearProvisioning = { true },
            deleteWallet = { deleted = true },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.reset(OPERATOR) { commit -> commit() } }
        }

        assertTrue(error.message.orEmpty().contains(known.networkName))
        assertTrue(error.message.orEmpty().contains("chain ${known.chainId}"))
        assertFalse(deleted)
    }

    @Test
    fun emptyNetworkReportFailsClosed() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireOperatorNativeBalancesEmpty(emptyList())
        }
        assertTrue(error.message.orEmpty().contains("No trusted network balances"))
    }

    @Test
    fun mismatchedTrustedRpcNamesTheExpectedNetworkAndReportedChain() {
        val known = KnownChainPolicy.defaultProfile()
        val reportedChain = known.chainId + 1
        val reader = RpcOperatorNativeBalanceReader(
            configSnapshot = { unprovisionedSnapshot(known.chainId) },
            clientFactory = {
                @Suppress("UNCHECKED_CAST")
                Proxy.newProxyInstance(
                    SettlementChainClient::class.java.classLoader,
                    arrayOf(SettlementChainClient::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "chainId" -> reportedChain
                        "close" -> Unit
                        else -> error("Unexpected RPC call: ${method.name}")
                    }
                } as SettlementChainClient
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { reader.read(OPERATOR) }
        }

        assertTrue(error.message.orEmpty().contains(known.networkName))
        assertTrue(error.message.orEmpty().contains("chain ${known.chainId}"))
        assertTrue(error.message.orEmpty().contains("reported chain $reportedChain"))
    }

    private fun balance(
        name: String,
        chainId: Long,
        symbol: String,
        latest: BigInteger,
        pending: BigInteger,
    ) = OperatorNativeBalances(name, chainId, symbol, latest, pending)

    private fun unprovisionedSnapshot(chainId: Long): TerminalConfigSnapshot {
        val known = KnownChainPolicy.requireProfile(chainId)
        return TerminalConfigSnapshot(
            networkName = known.networkName,
            rpcUrl = known.rpcUrl,
            chainId = known.chainId,
            factoryAddress = known.factory.value,
            receiverImplementationAddress = known.receiverImplementation.value,
            vaultAddress = known.fixtureVault.value,
            confirmationBlocks = known.defaultConfirmationBlocks,
            paymentTokens = emptyList(),
            protocolVersion = known.protocolVersion,
            provisionedOperatorAddress = "",
            provisioned = false,
        )
    }

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
    }
}
