package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NativeAsset
import com.openpasskey.erc681.NetworkValidation
import com.openpasskey.erc681.RpcRateLimitResponseException
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.lifecycle.OperatorNativeBalanceReader
import com.openpasskey.terminal.lifecycle.OperatorNativeBalances
import com.openpasskey.terminal.rpc.PinnedRpcEndpointVerifier
import com.openpasskey.terminal.rpc.RpcEndpointOverrideState
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import com.openpasskey.terminal.rpc.RpcEndpointSnapshot
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalProvisionerTest {
    @Test
    fun trustedProvisioningReusesValidationEvidenceWithoutStandaloneVaultCodeRead() = runBlocking {
        val reader = FakeReader()
        var authorizationCalls = 0
        var writes = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        provisioner.provision(CANONICAL, wallet()) { commit ->
            authorizationCalls += 1
            commit()
        }

        assertEquals(0, reader.chainIdCalls)
        assertEquals(1, reader.validationEvidenceCalls)
        assertEquals(0, reader.vaultRuntimeCalls)
        assertEquals(1, authorizationCalls)
        assertEquals(1, writes)
    }

    @Test
    fun nativeProvisioningUsesProtocol16TrustedMetadataAndOneEvidenceProof() = runBlocking {
        val reader = FakeReader()
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        val result = provisioner.provision(CANONICAL_NATIVE, wallet()) { commit -> commit() }

        assertEquals(NativeAsset.address.value, result.token.address)
        assertEquals("ETH", result.token.symbol)
        assertEquals(NativeAsset.DECIMALS, result.token.decimals)
        assertEquals(NativeAsset.PROTOCOL_VERSION, result.profile.protocolVersion)
        assertEquals(1, reader.validationEvidenceCalls)
        assertEquals(0, reader.vaultRuntimeCalls)
    }

    @Test
    fun baseMainnetProvisioningUsesPinnedProductionDeploymentAndRpc() = runBlocking {
        val mainnet = KnownChainPolicy.requireProfile(8453)
        val reader = FakeReader().apply {
            remoteChainId = mainnet.chainId
            factory = mainnet.factory
            implementation = mainnet.receiverImplementation
            vaultRuntime = MAINNET_VAULT_RUNTIME.copyOf()
        }
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { config ->
                assertEquals("https://mainnet.base.org", config.rpcUrl)
                reader
            },
        )

        val result = provisioner.provision(MAINNET_CANONICAL, wallet()) { commit -> commit() }

        assertEquals("Base Mainnet", result.profile.networkName)
        assertEquals(8453L, result.profile.chainId)
        assertEquals(mainnet.factory.value, result.profile.factoryAddress)
        assertEquals(
            mainnet.receiverImplementation.value,
            result.profile.receiverImplementationAddress,
        )
        assertEquals(
            mainnet.vaultRuntimeCodeHash,
            Numeric.toHexString(org.web3j.crypto.Hash.sha3(MAINNET_VAULT_RUNTIME)),
        )
        assertEquals(1, result.profile.confirmationBlocks)
        assertEquals(1, reader.validationEvidenceCalls)
    }

    @Test
    fun resolvedEndpointIsUsedButNeverCopiedIntoProvisionedProfile() = runBlocking {
        val managedEndpoint = "https://api.developer.coinbase.com/rpc/v1/base-sepolia/client-key"
        val opened = mutableListOf<String>()
        val resolver = object : RpcEndpointResolver {
            override fun snapshot(chainId: Long) = RpcEndpointSnapshot(
                chainId,
                RpcEndpointOverrideState.NOT_CONFIGURED,
                "Coinbase CDP",
            )

            override fun resolve(chainId: Long, fallbackUrl: String): String = managedEndpoint
        }
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            rpcEndpointResolver = resolver,
            clientFactory = ProvisioningChainReaderFactory { config ->
                opened += config.rpcUrl
                FakeReader()
            },
        )

        val result = provisioner.provision(CANONICAL, wallet()) { commit -> commit() }

        assertEquals(listOf(managedEndpoint), opened)
        assertEquals("https://sepolia.base.org", result.profile.rpcUrl)
        assertFalse(result.configuration.toString().contains("client-key"))
    }

    @Test
    fun rpcEndpointVerifierAcceptsConfiguredRouteOnlyWithPinnedEvidence() = runBlocking {
        val reader = FakeReader()
        val verifier = PinnedRpcEndpointVerifier(
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        verifier.verify(
            chainId = 84532,
            rpcUrl = "https://rpc.example/client-key",
            currentConfiguration = provisionedPrevious(confirmationBlocks = 7),
        )

        assertEquals(1, reader.validationEvidenceCalls)
        assertTrue(reader.closed)
    }

    @Test
    fun rpcEndpointVerifierRejectsWrongChainBeforeAnUnprovisionedEndpointCanBeSaved() {
        val reader = FakeReader().apply { remoteChainId = 8453 }
        val verifier = PinnedRpcEndpointVerifier(
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                verifier.verify(
                    chainId = 84532,
                    rpcUrl = "https://rpc.example/client-key",
                    currentConfiguration = previous(),
                )
            }
        }

        assertEquals(1, reader.chainIdCalls)
        assertTrue(reader.closed)
    }

    @Test
    fun rpcEndpointVerifierChecksEveryConfiguredRouteOnTheSelectedChain() = runBlocking {
        val firstConfiguration = provisionedPrevious(confirmationBlocks = 7)
        val first = firstConfiguration.paymentProfiles.single()
        val second = first.copy(
            vaultAddress = "0x5555555555555555555555555555555555555555",
        )
        val configuration = firstConfiguration.copy(
            paymentProfiles = listOf(first, second),
            selectedProfileId = first.id,
        )
        val openedVaults = mutableListOf<EvmAddress>()
        val readers = mutableListOf<FakeReader>()
        val verifier = PinnedRpcEndpointVerifier(
            clientFactory = ProvisioningChainReaderFactory { network ->
                openedVaults += network.vault
                FakeReader().apply {
                    validationVault = network.vault
                    readers += this
                }
            },
        )

        verifier.verify(
            chainId = 84532,
            rpcUrl = "https://rpc.example/client-key",
            currentConfiguration = configuration,
        )

        assertEquals(setOf(first.vaultAddress, second.vaultAddress), openedVaults.map { it.value }.toSet())
        assertEquals(2, readers.size)
        assertTrue(readers.all { it.validationEvidenceCalls == 1 && it.closed })
    }

    @Test
    fun newProfileUsesKnownNetworkDefaultWithoutLeakingLegacyFallbackFinality() = runBlocking {
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
        assertEquals(1, result.configuration.confirmationBlocks)
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
        assertEquals(0, trusted.chainIdCalls)
        assertEquals(0, operational.provenanceCalls)
        assertTrue(trusted.provenanceCalls > 0)
        assertEquals(1, trusted.validationEvidenceCalls)
        assertEquals(0, trusted.vaultRuntimeCalls)
        assertTrue(operational.closed)
        assertTrue(trusted.closed)
    }

    @Test
    fun reprovisioningAndNewRoutesPreserveMerchantNetworkFinality() = runBlocking {
        var stored = provisionedPrevious(confirmationBlocks = 7)
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

        val result = provisioner.provision(CANONICAL, wallet()) { commit -> commit() }
        val secondPayload = CANONICAL.replace(
            "0x7ffba642bc902880a737cb1c18a4e9540879e211",
            "0x8888888888888888888888888888888888888888",
        )
        val second = provisioner.provision(secondPayload, wallet()) { commit -> commit() }

        assertEquals(7, result.profile.confirmationBlocks)
        assertEquals(7, second.profile.confirmationBlocks)
        assertEquals(7, second.configuration.confirmationBlocks)
        assertEquals(setOf(7), stored.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet())
        assertEquals(7, stored.selectedPaymentProfile()?.confirmationBlocks)
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
        assertEquals(0, trusted.chainIdCalls)
        assertEquals(0, operational.provenanceCalls)
        assertEquals(1, trusted.validationEvidenceCalls)
        assertEquals(0, trusted.vaultRuntimeCalls)
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
            CANONICAL.replace("chainId=84532", "chainId=10"),
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
    fun exhaustedRateLimitFailurePerformsNoAuthorizationOrConfigurationCommit() {
        val reader = FakeReader().apply {
            validationFailure = RpcRateLimitResponseException(
                rpcCode = -32016,
                rpcMessage = "over rate limit",
            )
        }
        var authorizationCalls = 0
        var writes = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )

        assertThrows(RpcRateLimitResponseException::class.java) {
            runBlocking {
                provisioner.provision(CANONICAL, wallet()) { commit ->
                    authorizationCalls += 1
                    commit()
                }
            }
        }

        assertEquals(0, authorizationCalls)
        assertEquals(0, writes)
        assertTrue(reader.closed)
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
        assertEquals(1, reader.validationEvidenceCalls)
        assertEquals(0, reader.vaultRuntimeCalls)
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
                listOf(
                    OperatorNativeBalances(
                        networkName = "Base Sepolia",
                        chainId = 84532,
                        nativeCurrencySymbol = "ETH",
                        latest = BigInteger.ZERO,
                        pending = BigInteger.ZERO,
                    ),
                )
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

    @Test
    fun cancellingProvisioningInterruptsTrustedRpcWaitAndPerformsNoCommit() = runBlocking {
        val validationStarted = CountDownLatch(1)
        val validationInterrupted = CountDownLatch(1)
        val reader = FakeReader().apply {
            validationHook = {
                validationStarted.countDown()
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30))
                } catch (error: InterruptedException) {
                    validationInterrupted.countDown()
                    throw error
                }
            }
        }
        var authorizationCalls = 0
        var writes = 0
        val provisioner = TerminalProvisioner(
            snapshot = { previous() },
            compareAndCommit = { _, _ -> writes += 1; true },
            currentWalletSnapshot = ::wallet,
            lifecycleGate = TerminalLifecycleGate(),
            clientFactory = ProvisioningChainReaderFactory { reader },
        )
        val provisioning = async(Dispatchers.Default) {
            provisioner.provision(CANONICAL, wallet()) { commit ->
                authorizationCalls += 1
                commit()
            }
        }

        assertTrue(validationStarted.await(5, TimeUnit.SECONDS))
        provisioning.cancelAndJoin()

        assertTrue(validationInterrupted.await(5, TimeUnit.SECONDS))
        assertEquals(0, authorizationCalls)
        assertEquals(0, writes)
        assertTrue(reader.closed)
    }

    private class FakeReader : ProvisioningChainReader {
        var remoteChainId = 84532L
        var vaultRuntime = CANONICAL_VAULT_RUNTIME.copyOf()
        var validationVault = VAULT
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
        var validationEvidenceCalls = 0

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
        override fun validate(token: EvmAddress): NetworkValidation {
            provenanceCall()
            validationFailure?.let { throw it }
            validationHook?.invoke()
            val decimals = tokenDecimals(token)
            val symbol = tokenSymbol(token)
            return NetworkValidation(
                chainId = remoteChainId,
                factory = factory,
                receiverImplementation = implementation,
                vault = validationVault,
                token = token,
                tokenWhitelisted = whitelisted,
                tokenDecimals = decimals,
                tokenSymbol = symbol,
            )
        }
        override fun validateWithEvidence(token: EvmAddress): ProvisioningValidationEvidence {
            provenanceCall()
            validationEvidenceCalls += 1
            validationFailure?.let { throw it }
            validationHook?.invoke()
            val decimals = decimalsFailure?.let { throw it } ?: 18
            val symbol = symbolFailure?.let { throw it } ?: "AUD"
            return ProvisioningValidationEvidence(
                validation = NetworkValidation(
                    chainId = remoteChainId,
                    factory = factory,
                    receiverImplementation = implementation,
                    vault = validationVault,
                    token = token,
                    tokenWhitelisted = whitelisted,
                    tokenDecimals = decimals,
                    tokenSymbol = symbol,
                ),
                vaultRuntimeCode = vaultRuntime,
            )
        }
        override fun validateWithEvidence(
            token: EvmAddress,
            expectedDecimals: Int,
            expectedSymbol: String,
        ): ProvisioningValidationEvidence {
            provenanceCall()
            validationEvidenceCalls += 1
            validationFailure?.let { throw it }
            validationHook?.invoke()
            return ProvisioningValidationEvidence(
                validation = NetworkValidation(
                    chainId = remoteChainId,
                    factory = factory,
                    receiverImplementation = implementation,
                    vault = validationVault,
                    token = token,
                    tokenWhitelisted = whitelisted,
                    tokenDecimals = expectedDecimals,
                    tokenSymbol = expectedSymbol,
                ),
                vaultRuntimeCode = vaultRuntime,
            )
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
                vault = validationVault,
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

    private fun provisionedPrevious(confirmationBlocks: Int): TerminalConfigSnapshot {
        val token = PaymentToken(
            "0x7ffba642bc902880a737cb1c18a4e9540879e211",
            "AUD",
            18,
        )
        val profile = TerminalPaymentProfile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84532,
            factoryAddress = FACTORY.value,
            receiverImplementationAddress = IMPLEMENTATION.value,
            vaultAddress = VAULT.value,
            confirmationBlocks = confirmationBlocks,
            token = token,
            protocolVersion = "1.6",
        )
        return TerminalConfigSnapshot(
            networkName = profile.networkName,
            rpcUrl = profile.rpcUrl,
            chainId = profile.chainId,
            factoryAddress = profile.factoryAddress,
            receiverImplementationAddress = profile.receiverImplementationAddress,
            vaultAddress = profile.vaultAddress,
            confirmationBlocks = profile.confirmationBlocks,
            paymentTokens = listOf(token),
            protocolVersion = profile.protocolVersion,
            provisionedOperatorAddress = OPERATOR,
            provisioned = true,
            paymentProfiles = listOf(profile),
            selectedProfileId = profile.id,
        )
    }

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val CANONICAL = "opk-terminal:provision?v=1&chainId=84532&vault=" +
            "0x3333333333333333333333333333333333333333&token=" +
            "0x7ffba642bc902880a737cb1c18a4e9540879e211&operator=$OPERATOR"
        const val CANONICAL_NATIVE = "opk-terminal:provision?v=1&chainId=84532&vault=" +
            "0x3333333333333333333333333333333333333333&token=" +
            "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE&operator=$OPERATOR"
        const val MAINNET_CANONICAL = "opk-terminal:provision?v=1&chainId=8453&vault=" +
            "0x3333333333333333333333333333333333333333&token=" +
            "0x7ffba642bc902880a737cb1c18a4e9540879e211&operator=$OPERATOR"
        const val KNOWN_VAULT_RUNTIME_HASH =
            "0x32ad6b6076f449fbc39e115afc2645c65071280af2d461dc315544ac0a1d7e58"
        val CANONICAL_VAULT_RUNTIME: ByteArray = Numeric.hexStringToByteArray(
            "0x60806040525f8061000e610081565b368280378136915af43d5f803e15610024573d5ff35b" +
                "3d5ffd5b90601f8019910116810190811067ffffffffffffffff82111761004a57604052565b" +
                "634e487b7160e01b5f52604160045260245ffd5b9081602091031261007d57516001600160a0" +
                "1b038116810361007d5790565b5f80fd5b60ff7f0869949ff70b851fd884d5dedd17ab976d41" +
                "48e809aad6e654ec2c04f1849729541661013157604051635c60da1b60e01b81526020816004" +
                "817f000000000000000000000000c9c24c87f55c46d42419bc181d427acd1755e46c60016001" +
                "60a01b03165afa908115610126575f916100fa575090565b61011c915060203d60201161011f" +
                "575b6101148183610028565b81019061005e565b90565b503d61010a565b6040513d5f823e3d" +
                "90fd5b7f50950143dc78ff80b5cdf56436a716933e2b92eb073f4b272dec2e808d8423835460" +
                "01600160a01b03169056fea26469706673582212202e8cd2852b590f2bda79ba8056dd697cc4" +
                "fe00ae07dc3e33ae82e1a68109a5aa64736f6c634300081a0033",
        )
        val MAINNET_VAULT_RUNTIME: ByteArray = Numeric.hexStringToByteArray(
            Numeric.toHexString(CANONICAL_VAULT_RUNTIME).replace(
                "c9c24c87f55c46d42419bc181d427acd1755e46c",
                "d051ba174636a1bb663559e9c454053a543488ef",
            ),
        )
        val FACTORY = EvmAddress.parse("0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f")
        val IMPLEMENTATION = EvmAddress.parse("0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18")
        val VAULT = EvmAddress.parse("0x3333333333333333333333333333333333333333")
    }
}
