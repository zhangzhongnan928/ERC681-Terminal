package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkValidation
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.lifecycle.OperatorNativeBalanceReader
import com.openpasskey.terminal.lifecycle.OperatorNativeBalances
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalProvisionerTest {
    @Test
    fun provisioningUsesKnownNetworkDefaultWithoutLeakingPreviousFinality() = runBlocking {
        val committed = mutableListOf<TerminalConfigSnapshot>()
        val operational = FakeReader().apply {
            provenanceFailure = IllegalStateException("Operational override cannot prove provenance")
        }
        val trusted = FakeReader()
        val opened = mutableListOf<String>()
        val provisioner = TerminalProvisioner(
            snapshot = { previous(rpcUrl = "https://merchant-rpc.example") },
            compareAndCommit = { expected, candidate ->
                assertEquals(previous(rpcUrl = "https://merchant-rpc.example"), expected)
                committed += candidate
                true
            },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { config ->
                opened += config.rpcUrl
                if (config.rpcUrl == "https://merchant-rpc.example") operational else trusted
            },
        )

        val result = provisioner.provision(CANONICAL, wallet()) { commit -> commit() }

        assertEquals(1, committed.size)
        assertEquals(committed.single(), result.configuration)
        assertEquals(
            KnownChainPolicy.requireProfile(84532).defaultConfirmationBlocks,
            result.configuration.confirmationBlocks,
        )
        assertEquals("https://merchant-rpc.example", result.configuration.rpcUrl)
        assertEquals(1, result.configuration.paymentTokens.size)
        assertEquals("AUD", result.token.symbol)
        assertEquals(18, result.token.decimals)
        assertTrue(result.configuration.provisioned)
        assertEquals(OPERATOR, result.configuration.provisionedOperatorAddress)
        assertEquals(
            listOf("https://merchant-rpc.example", "https://sepolia.base.org"),
            opened,
        )
        assertEquals(1, operational.chainIdCalls)
        assertEquals(0, operational.provenanceCalls)
        assertTrue(trusted.provenanceCalls > 0)
        assertTrue(operational.closed)
        assertTrue(trusted.closed)
    }

    @Test
    fun repeatedV1ScansUpsertAndSelectCompleteProfiles() = runBlocking {
        var stored = previous()
        val provisioner = TerminalProvisioner(
            snapshot = { stored },
            compareAndCommit = { expected, candidate ->
                assertEquals(stored, expected)
                stored = candidate
                true
            },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { FakeReader() },
        )

        provisioner.provision(CANONICAL, wallet()) { commit -> commit() }
        val secondPayload = CANONICAL.replace(
            "0x7ffba642bc902880a737cb1c18a4e9540879e211",
            "0x8888888888888888888888888888888888888888",
        )
        val second = provisioner.provision(secondPayload, wallet()) { commit -> commit() }

        assertEquals(2, stored.resolvedPaymentProfiles().size)
        assertEquals(second.profile.id, stored.selectedProfileId)
        assertEquals(second.profile, stored.resolvedPaymentProfiles().last())
    }

    @Test
    fun lyingOperationalOverrideCannotAuthenticateDeploymentProvenance() = runBlocking {
        val operational = FakeReader()
        val trusted = FakeReader().apply { vaultRuntime = byteArrayOf(0x60, 0x00) }
        var writes = 0
        val opened = mutableListOf<String>()
        val provisioner = TerminalProvisioner(
            snapshot = { previous(rpcUrl = "https://merchant-rpc.example") },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { config ->
                opened += config.rpcUrl
                if (config.rpcUrl == "https://merchant-rpc.example") operational else trusted
            },
        )

        assertThrows(VaultRuntimeCodeHashMismatchException::class.java) {
            runBlocking { provisioner.provision(CANONICAL, wallet()) { commit -> commit() } }
        }
        assertEquals(
            listOf("https://merchant-rpc.example", "https://sepolia.base.org"),
            opened,
        )
        assertEquals(1, operational.chainIdCalls)
        assertEquals(0, operational.provenanceCalls)
        assertEquals(1, trusted.vaultRuntimeCalls)
        assertEquals(0, writes)
        assertTrue(operational.closed)
        assertTrue(trusted.closed)
    }

    @Test
    fun wrongChainOperationalOverrideFailsBeforeTrustedProvenanceOrWrite() {
        val operational = FakeReader().apply { remoteChainId = 1 }
        val trusted = FakeReader()
        var writes = 0
        var trustedClients = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous(rpcUrl = "https://merchant-rpc.example") },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { config ->
                if (config.rpcUrl == "https://merchant-rpc.example") operational
                else trusted.also { trustedClients += 1 }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provisioner.provision(CANONICAL, wallet()) { commit -> commit() } }
        }
        assertEquals(1, operational.chainIdCalls)
        assertEquals(0, operational.provenanceCalls)
        assertEquals(0, trustedClients)
        assertEquals(0, writes)
        assertTrue(operational.closed)
    }

    @Test
    fun operatorMismatchAndUnknownChainRejectBeforeRpcAndWrite() {
        listOf(
            CANONICAL.replace(OPERATOR, "0x" + "22".repeat(20)),
            CANONICAL.replace("chainId=84532", "chainId=1"),
            CANONICAL.replace("chainId=84532", "chainId=8453"),
        ).forEach { payload ->
            var clients = 0
            var writes = 0
            val provisioner = TerminalProvisioner(
                snapshot = { previous() },
                compareAndCommit = { _, _ -> writes += 1; true },
                currentWalletSnapshot = ::wallet,
                lifecycleGate = TerminalLifecycleGate(),
                clientFactory = ProvisioningChainReaderFactory { clients += 1; FakeReader() },
            )
            assertThrows(Exception::class.java) {
                runBlocking { provisioner.provision(payload, wallet()) { commit -> commit() } }
            }
            assertEquals(0, clients)
            assertEquals(0, writes)
        }
    }

    @Test
    fun everyRpcPinWhitelistMetadataAndFullValidationFailurePerformsZeroWrites() {
        val failures = listOf<(FakeReader) -> Unit>(
            { it.remoteChainId = 1 },
            { it.factory = EvmAddress.parse("0x" + "33".repeat(20)) },
            { it.implementation = EvmAddress.parse("0x" + "44".repeat(20)) },
            { it.whitelisted = false },
            { it.decimalsFailure = IllegalStateException("bad decimals") },
            { it.symbolFailure = IllegalStateException("bad symbol") },
            { it.validationFailure = IllegalStateException("full validation failed") },
        )
        failures.forEach { mutate ->
            val reader = FakeReader().also(mutate)
            var writes = 0
            val provisioner = TerminalProvisioner(
                snapshot = { previous() },
                compareAndCommit = { _, _ -> writes += 1; true },
                currentWalletSnapshot = ::wallet,
                lifecycleGate = TerminalLifecycleGate(),
                clientFactory = ProvisioningChainReaderFactory { reader },
            )
            assertThrows(Exception::class.java) {
                runBlocking { provisioner.provision(CANONICAL, wallet()) { commit -> commit() } }
            }
            assertEquals(0, writes)
            assertTrue(reader.closed)
        }
    }

    @Test
    fun maliciousVaultGettersCannotBypassPinnedRuntimeCodeHash() {
        val maliciousRuntime = byteArrayOf(0x60, 0x00)
        val reader = FakeReader().apply { vaultRuntime = maliciousRuntime }
        var writes = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        val error = assertThrows(VaultRuntimeCodeHashMismatchException::class.java) {
            runBlocking { provisioner.provision(CANONICAL, wallet()) { commit -> commit() } }
        }

        assertEquals(KNOWN_VAULT_RUNTIME_HASH, error.expected)
        assertEquals(
            "0x07ad118d6cc8642c86c03827f276d8b791a65e5c99a3845faf186be720a1455d",
            error.actual,
        )
        assertEquals(
            "Vault runtime bytecode hash ${error.actual} does not match trusted " +
                "OPKBeaconProxy hash $KNOWN_VAULT_RUNTIME_HASH",
            error.localizedMessage,
        )
        assertEquals(0, reader.getterCalls)
        assertEquals(0, writes)
        assertTrue(reader.closed)
    }

    @Test
    fun failedAtomicCommitDoesNotReportProvisioningSuccess() {
        var writes = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; false },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { FakeReader() },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { provisioner.provision(CANONICAL, wallet()) { commit -> commit() } }
        }
        assertEquals(1, writes)
    }

    @Test
    fun lockedAdminSessionAfterRpcValidationPreventsConfigurationCommit() {
        var authorized = true
        var writes = 0
        val reader = FakeReader().apply {
            validationHook = { authorized = false }
        }
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                provisioner.provision(CANONICAL, wallet()) { commit ->
                    check(authorized) { "Admin/setup session locked during provisioning" }
                    commit()
                }
            }
        }
        assertEquals(0, writes)
        assertTrue(reader.closed)
    }

    @Test
    fun invalidLegacyRpcOverridesFallBackToPinnedHttpsEndpoint() = runBlocking {
        listOf(
            "",
            "http://rpc.example.com",
            "https://user:secret@rpc.example.com",
            "not a URL",
        ).forEach { legacyRpc ->
            var committed: TerminalConfigSnapshot? = null
            val provisioner = TerminalProvisioner(
                snapshot = { previous(rpcUrl = legacyRpc) },
                compareAndCommit = { _, candidate -> committed = candidate; true },
                currentWalletSnapshot = ::wallet,
                lifecycleGate = TerminalLifecycleGate(),
                clientFactory = ProvisioningChainReaderFactory { config ->
                    assertEquals("https://sepolia.base.org", config.rpcUrl)
                    FakeReader()
                },
            )

            provisioner.provision(CANONICAL, wallet()) { commit -> commit() }
            assertEquals("https://sepolia.base.org", committed?.rpcUrl)
        }
    }

    @Test
    fun resetAfterRpcValidationPreventsProvisioningADeletedWallet() = runBlocking {
        val validationReached = CountDownLatch(1)
        val allowProvisioningToContinue = CountDownLatch(1)
        val gate = TerminalLifecycleGate()
        var currentWallet = wallet()
        var writes = 0
        val reader = FakeReader().apply {
            validationHook = {
                validationReached.countDown()
                check(allowProvisioningToContinue.await(5, TimeUnit.SECONDS))
            }
        }
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = { currentWallet },
            lifecycleGate = gate,
            clientFactory = ProvisioningChainReaderFactory { reader },
        )
        val provisioning = async(Dispatchers.Default) {
            runCatching {
                provisioner.provision(CANONICAL, currentWallet) { commit -> commit() }
            }
        }

        assertTrue(validationReached.await(5, TimeUnit.SECONDS))
        TerminalResetCoordinator(
            lifecycleGate = gate,
            resetGuard = OperatorResetGuard { false },
            nativeBalanceReader = OperatorNativeBalanceReader {
                OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO)
            },
            clearProvisioning = { true },
            deleteWallet = {
                currentWallet = OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED)
            },
        ).reset(OPERATOR) { commit -> commit() }
        allowProvisioningToContinue.countDown()

        assertTrue(provisioning.await().isFailure)
        assertEquals(0, writes)
        assertTrue(reader.closed)
    }

    private class FakeReader : ProvisioningChainReader {
        var remoteChainId = 84532L
        var vaultRuntime = CANONICAL_VAULT_RUNTIME.copyOf()
        var factory = FACTORY
        var implementation = IMPLEMENTATION
        var whitelisted = true
        var decimalsFailure: RuntimeException? = null
        var symbolFailure: RuntimeException? = null
        var validationFailure: RuntimeException? = null
        var validationHook: (() -> Unit)? = null
        var provenanceFailure: RuntimeException? = null
        var closed = false
        var getterCalls = 0
        var chainIdCalls = 0
        var provenanceCalls = 0
        var vaultRuntimeCalls = 0

        override fun chainId(): Long {
            chainIdCalls += 1
            return remoteChainId
        }
        override fun vaultRuntimeCode(vault: EvmAddress): ByteArray {
            provenanceCall()
            vaultRuntimeCalls += 1
            return vaultRuntime.copyOf()
        }
        override fun vaultFactory(vault: EvmAddress): EvmAddress {
            provenanceCall()
            getterCalls += 1
            return factory
        }
        override fun factoryImplementation(factory: EvmAddress): EvmAddress {
            provenanceCall()
            return implementation
        }
        override fun isPaymentToken(vault: EvmAddress, token: EvmAddress): Boolean {
            provenanceCall()
            return whitelisted
        }
        override fun tokenDecimals(token: EvmAddress): Int {
            provenanceCall()
            return decimalsFailure?.let { throw it } ?: 18
        }
        override fun tokenSymbol(token: EvmAddress): String {
            provenanceCall()
            return symbolFailure?.let { throw it } ?: "AUD"
        }
        override fun validate(
            token: EvmAddress,
            expectedDecimals: Int,
            expectedSymbol: String,
        ): NetworkValidation {
            provenanceCall()
            validationFailure?.let { throw it }
            validationHook?.invoke()
            return NetworkValidation(
                chainId = remoteChainId,
                factory = FACTORY,
                receiverImplementation = IMPLEMENTATION,
                vault = VAULT,
                token = token,
                tokenWhitelisted = true,
                tokenDecimals = expectedDecimals,
                tokenSymbol = expectedSymbol,
            )
        }

        override fun close() {
            closed = true
        }

        private fun provenanceCall() {
            provenanceCalls += 1
            provenanceFailure?.let { throw it }
        }
    }

    private fun wallet() = OperatorWalletSnapshot(
        OperatorWalletAvailability.READY,
        OPERATOR,
    )

    private fun previous(rpcUrl: String = "https://sepolia.base.org") = TerminalConfigSnapshot(
        networkName = "old",
        rpcUrl = rpcUrl,
        chainId = 84532,
        factoryAddress = FACTORY.value,
        receiverImplementationAddress = IMPLEMENTATION.value,
        vaultAddress = VAULT.value,
        confirmationBlocks = 7,
        paymentTokens = emptyList(),
        protocolVersion = "",
        provisionedOperatorAddress = null,
        provisioned = false,
    )

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val CANONICAL = "opk-terminal:provision?v=1&chainId=84532&vault=" +
            "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1&token=" +
            "0x7ffba642bc902880a737cb1c18a4e9540879e211&operator=$OPERATOR"
        const val KNOWN_VAULT_RUNTIME_HASH =
            "0xe7310159a3c109346b137a989bfd213e65fe48ded6eb84dbe57a37d7a047513e"
        val CANONICAL_VAULT_RUNTIME: ByteArray = Numeric.hexStringToByteArray(
            "0x60806040525f8061000e610081565b368280378136915af43d5f803e15610024573d5ff35b" +
                "3d5ffd5b90601f8019910116810190811067ffffffffffffffff82111761004a57604052565b" +
                "634e487b7160e01b5f52604160045260245ffd5b9081602091031261007d57516001600160a0" +
                "1b038116810361007d5790565b5f80fd5b60ff7f0869949ff70b851fd884d5dedd17ab976d41" +
                "48e809aad6e654ec2c04f1849729541661013157604051635c60da1b60e01b81526020816004" +
                "817f000000000000000000000000d5ed58ded083d3cc9eec949b92f1834f937caa6a60016001" +
                "60a01b03165afa908115610126575f916100fa575090565b61011c915060203d60201161011f" +
                "575b6101148183610028565b81019061005e565b90565b503d61010a565b6040513d5f823e3d" +
                "90fd5b7f50950143dc78ff80b5cdf56436a716933e2b92eb073f4b272dec2e808d8423835460" +
                "01600160a01b03169056fea26469706673582212202e8cd2852b590f2bda79ba8056dd697cc4" +
                "fe00ae07dc3e33ae82e1a68109a5aa64736f6c634300081a0033",
        )
        val FACTORY = EvmAddress.parse("0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5")
        val IMPLEMENTATION = EvmAddress.parse("0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc")
        val VAULT = EvmAddress.parse("0x1ed67E540E6AB92dC3537A7bba3BcAb6FdD69Da1")
    }
}
