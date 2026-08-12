package com.openpasskey.terminal.rpc

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.protectingRpcEndpointOverrides
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcEndpointStoreTest {
    @Test
    fun missingOverrideUsesValidatedFallbackWithoutPersistingIt() {
        val records = MemoryRecords()
        val store = store(records)

        assertEquals(PUBLIC_MAINNET, store.resolve(8453, PUBLIC_MAINNET))
        assertEquals(
            RpcEndpointSnapshot(8453, RpcEndpointOverrideState.NOT_CONFIGURED, null),
            store.snapshot(8453),
        )
        assertTrue(records.values.isEmpty())
    }

    @Test
    fun productionModeFailsClosedWithoutAdminOrBuildManagedEndpoint() {
        val records = MemoryRecords()
        val store = RpcEndpointStore(
            records = records,
            cipher = ReversingCipher(),
            allowPublicFallback = false,
        )

        assertEquals(
            RpcEndpointSnapshot(
                chainId = 8453,
                state = RpcEndpointOverrideState.NOT_CONFIGURED,
                providerLabel = null,
                source = RpcEndpointSource.MISSING,
            ),
            store.snapshot(8453),
        )
        val error = assertThrows(RpcEndpointNotConfiguredException::class.java) {
            store.resolve(8453, PUBLIC_MAINNET)
        }
        assertFalse(error.message.orEmpty().contains(PUBLIC_MAINNET))
        assertTrue(records.values.isEmpty())
    }

    @Test
    fun buildManagedEndpointPrecedesPublicFallbackWithoutBeingPersisted() {
        val records = MemoryRecords()
        val managed = "https://api.developer.coinbase.com/rpc/v1/base/build-key"
        val store = RpcEndpointStore(
            records = records,
            cipher = ReversingCipher(),
            buildManagedEndpoints = mapOf(8453L to managed),
        )

        assertEquals(managed, store.resolve(8453, PUBLIC_MAINNET))
        assertEquals(
            RpcEndpointSnapshot(
                chainId = 8453,
                state = RpcEndpointOverrideState.NOT_CONFIGURED,
                providerLabel = "Coinbase CDP",
            ),
            store.snapshot(8453),
        )
        assertTrue(records.values.isEmpty())
    }

    @Test
    fun encryptedAdminOverridePrecedesBuildManagedEndpointAndClearRestoresIt() {
        val managed = "https://api.developer.coinbase.com/rpc/v1/base/build-key"
        val admin = "https://base-mainnet.g.alchemy.com/v2/admin-key"
        val store = RpcEndpointStore(
            records = MemoryRecords(),
            cipher = ReversingCipher(),
            buildManagedEndpoints = mapOf(8453L to managed),
        )

        assertTrue(store.setOverride(8453, admin))
        assertEquals(admin, store.resolve(8453, PUBLIC_MAINNET))
        assertEquals("Alchemy", store.snapshot(8453).providerLabel)

        assertTrue(store.clearOverride(8453))
        assertEquals(managed, store.resolve(8453, PUBLIC_MAINNET))
        assertEquals(RpcEndpointOverrideState.NOT_CONFIGURED, store.snapshot(8453).state)
        assertEquals("Coinbase CDP", store.snapshot(8453).providerLabel)
    }

    @Test
    fun endpointResolutionBecomesStaleAfterOverrideReplacementOrClear() {
        val store = store()
        val publicResolution = store.resolveCurrent(8453, PUBLIC_MAINNET)
        assertTrue(store.isCurrent(publicResolution))

        assertTrue(store.setOverride(8453, "https://rpc.example/first-key"))
        assertFalse(store.isCurrent(publicResolution))
        val firstOverride = store.resolveCurrent(8453, PUBLIC_MAINNET)
        assertTrue(store.isCurrent(firstOverride))

        assertTrue(store.setOverride(8453, "https://rpc.example/second-key"))
        assertFalse(store.isCurrent(firstOverride))
        val secondOverride = store.resolveCurrent(8453, PUBLIC_MAINNET)
        assertTrue(store.isCurrent(secondOverride))

        assertTrue(store.clearOverride(8453))
        assertFalse(store.isCurrent(secondOverride))
        assertTrue(store.isCurrent(store.resolveCurrent(8453, PUBLIC_MAINNET)))
    }

    @Test
    fun credentialBearingUrlIsEncryptedAndSnapshotIsRedacted() {
        val records = MemoryRecords()
        val store = store(records)
        val secret = "terminal-secret-123"
        val endpoint = "https://api.developer.coinbase.com/rpc/v1/base/$secret"

        assertTrue(store.setOverride(8453, endpoint))

        val stored = requireNotNull(records.values[8453])
        assertFalse(stored.contains(endpoint))
        assertFalse(stored.contains(secret))
        assertEquals(endpoint, store.resolve(8453, PUBLIC_MAINNET))
        assertEquals(
            RpcEndpointSnapshot(8453, RpcEndpointOverrideState.READY, "Coinbase CDP"),
            store.snapshot(8453),
        )
        assertFalse(store.snapshot(8453).toString().contains(secret))
    }

    @Test
    fun providerClassificationNeverReturnsAKeyBearingHostname() {
        val store = store()
        val secretHost = "terminal-key-987.rpc-provider.example"

        assertTrue(store.setOverride(8453, "https://$secretHost/base"))

        val snapshot = store.snapshot(8453)
        assertEquals("Custom HTTPS provider", snapshot.providerLabel)
        assertFalse(snapshot.toString().contains(secretHost))
    }

    @Test
    fun alchemyEndpointGetsOnlyGenericProviderLabel() {
        val store = store()
        val endpoint = "https://base-mainnet.g.alchemy.com/v2/private-key"

        assertTrue(store.setOverride(8453, endpoint))

        assertEquals("Alchemy", store.snapshot(8453).providerLabel)
    }

    @Test
    fun longEndpointRoundTripsWithoutTruncation() {
        val store = store()
        val prefix = "https://rpc.example/"
        val endpoint = prefix + "a".repeat(RpcEndpointStore.MAX_RPC_URL_LENGTH - prefix.length)

        assertTrue(store.setOverride(8453, endpoint))

        assertEquals(RpcEndpointStore.MAX_RPC_URL_LENGTH, store.resolve(8453, PUBLIC_MAINNET).length)
        assertEquals(endpoint, store.resolve(8453, PUBLIC_MAINNET))
    }

    @Test
    fun invalidUrlsAreRejectedWithoutEchoingSubmittedSecret() {
        val secret = "do-not-echo-this-key"
        val invalid = listOf(
            "http://rpc.example/$secret",
            "https://user:$secret@rpc.example/base",
            "https://rpc.example/base#$secret",
            " https://rpc.example/$secret",
            "https://rpc.example/${"a".repeat(RpcEndpointStore.MAX_RPC_URL_LENGTH)}",
        )

        invalid.forEach { endpoint ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                store().setOverride(8453, endpoint)
            }
            assertFalse(error.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun corruptedRecordFailsClosedAndDoesNotExposeCiphertext() {
        val records = MemoryRecords(mutableMapOf(8453L to "broken-record"))
        val store = store(records)

        assertEquals(RpcEndpointOverrideState.UNAVAILABLE, store.snapshot(8453).state)
        val error = assertThrows(RpcEndpointStorageException::class.java) {
            store.resolve(8453, PUBLIC_MAINNET)
        }
        assertFalse(error.message.orEmpty().contains("broken-record"))
    }

    @Test
    fun clearRemovesOnlyRequestedChainOverride() {
        val store = store()
        assertTrue(store.setOverride(8453, "https://rpc.example/mainnet-key"))
        assertTrue(store.setOverride(84532, "https://rpc.example/testnet-key"))

        assertTrue(store.clearOverride(8453))

        assertEquals(RpcEndpointOverrideState.NOT_CONFIGURED, store.snapshot(8453).state)
        assertEquals(RpcEndpointOverrideState.READY, store.snapshot(84532).state)
    }

    @Test
    fun unknownChainsCannotReadOrWriteOverrides() {
        assertThrows(IllegalArgumentException::class.java) {
            store().snapshot(1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().setOverride(1, "https://rpc.example/key")
        }
    }

    @Test
    fun historicalCatalogOverrideIsEncryptedBeforeEveryProfileIsSanitized() {
        val records = MemoryRecords()
        val endpointStore = store(records)
        val custom = "https://base-sepolia.g.alchemy.com/v2/terminal-secret"
        val first = testProfile(custom)
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken("0x3333333333333333333333333333333333333333", "USDC", 6),
        )
        val snapshot = testSnapshot(first, listOf(first, second))

        val secured = requireNotNull(snapshot.protectingRpcEndpointOverrides(endpointStore))

        assertEquals(custom, endpointStore.resolve(84532, PUBLIC_SEPOLIA))
        assertTrue(secured.paymentProfiles.all { it.rpcUrl == PUBLIC_SEPOLIA })
        assertEquals(PUBLIC_SEPOLIA, secured.rpcUrl)
        assertEquals(first.id, secured.selectedPaymentProfile()?.id)
        assertFalse(records.values.getValue(84532).contains("terminal-secret"))
    }

    @Test
    fun ambiguousHistoricalPerChainOverridesFailBeforeWritingEitherOne() {
        val records = MemoryRecords()
        val endpointStore = store(records)
        val first = testProfile("https://rpc-one.example/key")
        val second = first.copy(
            rpcUrl = "https://rpc-two.example/key",
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken("0x3333333333333333333333333333333333333333", "USDC", 6),
        )

        val secured = testSnapshot(first, listOf(first, second))
            .protectingRpcEndpointOverrides(endpointStore)

        assertNull(secured)
        assertTrue(records.values.isEmpty())
    }

    private fun store(records: MemoryRecords = MemoryRecords()): RpcEndpointStore =
        RpcEndpointStore(
            records = records,
            cipher = ReversingCipher(),
            allowPublicFallback = true,
        )

    private fun testProfile(rpcUrl: String): TerminalPaymentProfile {
        val policy = KnownChainPolicy.requireProfile(84532)
        return TerminalPaymentProfile(
            networkName = policy.networkName,
            rpcUrl = rpcUrl,
            chainId = policy.chainId,
            factoryAddress = policy.factory.value,
            receiverImplementationAddress = policy.receiverImplementation.value,
            vaultAddress = "0x1111111111111111111111111111111111111111",
            confirmationBlocks = policy.defaultConfirmationBlocks,
            token = PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
            protocolVersion = "1.6",
        )
    }

    private fun testSnapshot(
        selected: TerminalPaymentProfile,
        profiles: List<TerminalPaymentProfile>,
    ) = TerminalConfigSnapshot(
        networkName = selected.networkName,
        rpcUrl = selected.rpcUrl,
        chainId = selected.chainId,
        factoryAddress = selected.factoryAddress,
        receiverImplementationAddress = selected.receiverImplementationAddress,
        vaultAddress = selected.vaultAddress,
        confirmationBlocks = selected.confirmationBlocks,
        paymentTokens = listOf(selected.token),
        protocolVersion = selected.protocolVersion,
        provisionedOperatorAddress = "0x4444444444444444444444444444444444444444",
        provisioned = true,
        paymentProfiles = profiles,
        selectedProfileId = selected.id,
    )

    private class MemoryRecords(
        val values: MutableMap<Long, String> = mutableMapOf(),
    ) : RpcEndpointRecordStorage {
        override fun read(chainId: Long): String? = values[chainId]
        override fun write(chainId: Long, record: String): Boolean {
            values[chainId] = record
            return true
        }
        override fun remove(chainId: Long): Boolean {
            values.remove(chainId)
            return true
        }
    }

    private class ReversingCipher : RpcEndpointCipher {
        override fun encrypt(chainId: Long, plaintext: String): String =
            "$chainId:${plaintext.reversed()}"

        override fun decrypt(chainId: Long, record: String): String {
            val prefix = "$chainId:"
            require(record.startsWith(prefix))
            return record.removePrefix(prefix).reversed()
        }
    }

    private companion object {
        const val PUBLIC_MAINNET = "https://mainnet.base.org"
        const val PUBLIC_SEPOLIA = "https://sepolia.base.org"
    }
}
