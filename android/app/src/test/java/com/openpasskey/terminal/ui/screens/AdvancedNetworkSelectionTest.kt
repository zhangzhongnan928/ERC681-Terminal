package com.openpasskey.terminal.ui.screens

import com.openpasskey.terminal.rpc.RpcEndpointSource
import com.openpasskey.terminal.viewmodel.RpcEndpointOverrideStatus
import com.openpasskey.terminal.viewmodel.RpcEndpointSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdvancedNetworkSelectionTest {
    @Test
    fun advancedSetupDefaultsUnknownOrFreshStateToBaseMainnet() {
        val profile = advancedSetupInitialNetwork(0)

        assertEquals(8453L, profile.chainId)
        assertEquals("Base Mainnet (8453) · production", advancedSetupNetworkLabel(profile))
    }

    @Test
    fun advancedSetupPreservesExistingSepoliaSelectionAndLabelsItAsTestnet() {
        val profile = advancedSetupInitialNetwork(84532)

        assertEquals(84532L, profile.chainId)
        assertEquals("Base Sepolia (84532) · testnet", advancedSetupNetworkLabel(profile))
    }

    @Test
    fun rpcEndpointPresentationNeverRequiresTheCredentialBearingUrl() {
        val configured = RpcEndpointSetting(
            chainId = 8453,
            networkName = "Base Mainnet",
            isTestnet = false,
            status = RpcEndpointOverrideStatus.READY,
            providerLabel = "Coinbase CDP",
            source = RpcEndpointSource.ADMIN_OVERRIDE,
        )

        assertEquals("Base Mainnet (8453) · production", rpcEndpointNetworkLabel(configured))
        assertEquals(
            "Saved override: Coinbase CDP. The URL remains masked.",
            rpcEndpointStatusLabel(configured),
        )
        assertFalse(rpcEndpointStatusLabel(configured).contains("https://"))
    }

    @Test
    fun unavailableRpcEndpointStatusTellsAdminHowToRecover() {
        val unavailable = RpcEndpointSetting(
            chainId = 84532,
            networkName = "Base Sepolia",
            isTestnet = true,
            status = RpcEndpointOverrideStatus.UNAVAILABLE,
            providerLabel = null,
            source = RpcEndpointSource.UNAVAILABLE,
        )

        assertEquals("Base Sepolia (84532) · testnet", rpcEndpointNetworkLabel(unavailable))
        assertEquals(
            "The saved override cannot be opened securely. Clear it and save a replacement.",
            rpcEndpointStatusLabel(unavailable),
        )
    }

    @Test
    fun buildManagedProviderIsLabeledWithoutBeingCalledAnAdminOverride() {
        val buildDefault = RpcEndpointSetting(
            chainId = 8453,
            networkName = "Base Mainnet",
            isTestnet = false,
            status = RpcEndpointOverrideStatus.NOT_CONFIGURED,
            providerLabel = "Alchemy",
            source = RpcEndpointSource.BUILD_MANAGED,
        )

        assertEquals(
            "Build default: Alchemy. No admin endpoint override is stored.",
            rpcEndpointStatusLabel(buildDefault),
        )
    }

    @Test
    fun missingManagedEndpointIsExplicitlyLabeledAsRateLimitedPublicFallback() {
        val publicFallback = RpcEndpointSetting(
            chainId = 8453,
            networkName = "Base Mainnet",
            isTestnet = false,
            status = RpcEndpointOverrideStatus.NOT_CONFIGURED,
            providerLabel = null,
            source = RpcEndpointSource.PUBLIC_FALLBACK,
        )

        assertEquals(
            "Using the development-only, rate-limited Base public RPC fallback. This build has no " +
                "managed endpoint or admin override for this network.",
            rpcEndpointStatusLabel(publicFallback),
        )
    }

    @Test
    fun productionWithoutEndpointRequiresAdminConfiguration() {
        val missing = RpcEndpointSetting(
            chainId = 8453,
            networkName = "Base Mainnet",
            isTestnet = false,
            status = RpcEndpointOverrideStatus.NOT_CONFIGURED,
            providerLabel = null,
            source = RpcEndpointSource.MISSING,
        )

        assertEquals(
            "Required before use. This production build has no embedded endpoint or saved admin override.",
            rpcEndpointStatusLabel(missing),
        )
    }
}
