package com.openpasskey.terminal.ui.screens

import org.junit.Assert.assertEquals
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
}
