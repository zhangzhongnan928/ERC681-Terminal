package com.openpasskey.terminal.chain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainConfigPolicyTest {
    @Test
    fun missingLegacyOperatorBindingIsNeverCompleteProvisioning() {
        assertTrue(config().hasCompleteProvisioning())
        assertFalse(config().copy(provisionedOperatorAddress = null).hasCompleteProvisioning())
        assertFalse(
            config().copy(
                provisionedOperatorAddress = "0x0000000000000000000000000000000000000000",
            ).hasCompleteProvisioning(),
        )
    }

    @Test
    fun legacySingleConfigMigratesToASelectedProfile() {
        val legacy = config()

        assertEquals(1, ChainConfig.DEFAULT_CONFIRMATION_BLOCKS)
        assertEquals(1, legacy.resolvedPaymentProfiles().size)
        assertEquals(legacy.resolvedPaymentProfiles().single(), legacy.selectedPaymentProfile())
    }

    @Test
    fun networkConfirmationUpdateAppliesToEveryRouteAndRejectsOutsideRange() {
        val operator = requireNotNull(config().provisionedOperatorAddress)
        val first = requireNotNull(config().selectedPaymentProfile())
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            confirmationBlocks = 1,
        )
        val catalog = config()
            .upsertingProfile(second, operator)
            .updatingNetworkConfirmationBlocks(84532, 7)
        val lowered = catalog.updatingNetworkConfirmationBlocks(84532, 1)

        assertEquals(7, catalog.selectingProfile(first.id).confirmationBlocks)
        assertEquals(7, catalog.selectingProfile(second.id).confirmationBlocks)
        assertEquals(setOf(1), lowered.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet())
        assertEquals(second.id, lowered.selectedProfileId)
        assertEquals(1, lowered.confirmationBlocks)
        assertTrue(lowered.hasCompleteProvisioning())
        assertTrue(config().copy(confirmationBlocks = 1).hasCompleteProvisioning())
        assertFalse(config().copy(confirmationBlocks = 0).hasCompleteProvisioning())
        assertFalse(config().copy(confirmationBlocks = 65).hasCompleteProvisioning())
        assertThrows(IllegalArgumentException::class.java) {
            catalog.updatingNetworkConfirmationBlocks(84532, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.updatingNetworkConfirmationBlocks(84532, 65)
        }
    }

    @Test
    fun provisioningUpsertsByChainVaultTokenAndPreservesOtherProfiles() {
        val original = config()
        val first = requireNotNull(original.selectedPaymentProfile())
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken("0x3333333333333333333333333333333333333333", "USDC", 6),
        )

        val catalog = original.upsertingProfile(second, requireNotNull(original.provisionedOperatorAddress))
        val refreshed = catalog.upsertingProfile(
            first.copy(token = first.token.copy(symbol = "AUDM")),
            requireNotNull(original.provisionedOperatorAddress),
        )

        assertEquals(2, catalog.resolvedPaymentProfiles().size)
        assertEquals(second.id, catalog.selectedProfileId)
        assertEquals(2, refreshed.resolvedPaymentProfiles().size)
        assertEquals("AUDM", requireNotNull(refreshed.selectedPaymentProfile()).token.symbol)
        assertTrue(refreshed.hasCompleteProvisioning())
    }

    @Test
    fun sameRouteAndNewRouteInheritTheMerchantNetworkFinality() {
        val original = config()
        val operator = requireNotNull(original.provisionedOperatorAddress)
        val originalProfile = requireNotNull(original.selectedPaymentProfile())
        val raised = original.updatingNetworkConfirmationBlocks(84532, 7)

        val refreshed = raised.upsertingProfile(
            originalProfile.copy(
                confirmationBlocks = 1,
                token = originalProfile.token.copy(symbol = "AUDM"),
            ),
            operator,
        )
        val differentRoute = originalProfile.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken(
                "0x3333333333333333333333333333333333333333",
                "USDC",
                6,
            ),
            confirmationBlocks = 1,
        )
        val withNewRoute = refreshed.upsertingProfile(differentRoute, operator)

        assertEquals(7, refreshed.selectedPaymentProfile()?.confirmationBlocks)
        assertEquals("AUDM", refreshed.selectedPaymentProfile()?.token?.symbol)
        assertEquals(7, withNewRoute.selectedPaymentProfile()?.confirmationBlocks)
        assertEquals(
            setOf(7),
            withNewRoute.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet(),
        )
    }

    @Test
    fun catalogLoadAndUpsertRepairSameChainDivergenceWithoutWeakening() {
        val original = config()
        val operator = requireNotNull(original.provisionedOperatorAddress)
        val first = requireNotNull(original.selectedPaymentProfile())
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken(
                "0x3333333333333333333333333333333333333333",
                "USDC",
                6,
            ),
        )
        val catalog = original.upsertingProfile(second, operator)
        val divergentProfiles = catalog.resolvedPaymentProfiles().map { profile ->
            profile.copy(confirmationBlocks = if (profile.id == first.id) 3 else 7)
        }
        val divergent = catalog.copy(paymentProfiles = divergentProfiles)
            .selectingProfile(second.id)

        val loaded = divergent.normalizingNetworkConfirmationBlocks()
        val rescanned = divergent.upsertingProfile(
            first.copy(
                confirmationBlocks = 1,
                token = first.token.copy(symbol = "AUDM"),
            ),
            operator,
        )
        val incomingDefault = catalog.upsertingProfile(
            first.copy(confirmationBlocks = 9),
            operator,
        )

        assertTrue(divergent.hasCompleteProvisioning(requireUniformNetworkConfirmations = false))
        assertFalse(divergent.hasCompleteProvisioning())
        assertEquals(setOf(7), loaded.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet())
        assertEquals(7, loaded.confirmationBlocks)
        assertEquals(setOf(7), rescanned.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet())
        assertEquals("AUDM", rescanned.selectedPaymentProfile()?.token?.symbol)
        assertEquals(
            setOf(1),
            incomingDefault.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet(),
        )
    }

    @Test
    fun provisioningCannotMixProfilesBoundToDifferentDeviceOperators() {
        val original = config()
        val second = requireNotNull(original.selectedPaymentProfile()).copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken("0x3333333333333333333333333333333333333333", "USDC", 6),
        )

        assertThrows(IllegalArgumentException::class.java) {
            original.upsertingProfile(
                second,
                "0x4444444444444444444444444444444444444444",
            )
        }
    }

    @Test
    fun catalogIsBounded() {
        var catalog = config()
        val operator = requireNotNull(catalog.provisionedOperatorAddress)
        repeat(ChainConfig.MAX_PAYMENT_PROFILES - 1) { index ->
            val suffix = (index + 2).toString(16).padStart(40, '0')
            catalog = catalog.upsertingProfile(
                requireNotNull(config().selectedPaymentProfile()).copy(
                    vaultAddress = "0x$suffix",
                    token = PaymentToken(
                        "0x${(index + 100).toString(16).padStart(40, '0')}",
                        "T$index",
                        18,
                    ),
                ),
                operator,
            )
        }
        assertEquals(ChainConfig.MAX_PAYMENT_PROFILES, catalog.resolvedPaymentProfiles().size)
        assertThrows(IllegalArgumentException::class.java) {
            catalog.upsertingProfile(
                requireNotNull(config().selectedPaymentProfile()).copy(
                    vaultAddress = "0x${"ff".repeat(20)}",
                    token = PaymentToken("0x${"ee".repeat(20)}", "OVER", 18),
                ),
                operator,
            )
        }
    }

    @Test
    fun removalPreservesOtherProfilesAndReselectsFirstInsertion() {
        val first = requireNotNull(config().selectedPaymentProfile())
        val second = first.copy(
            vaultAddress = "0x1111111111111111111111111111111111111111",
            token = PaymentToken("0x2222222222222222222222222222222222222222", "USDC", 6),
        )
        val third = first.copy(
            vaultAddress = "0x3333333333333333333333333333333333333333",
            token = PaymentToken("0x4444444444444444444444444444444444444444", "EURC", 6),
        )
        val operator = requireNotNull(config().provisionedOperatorAddress)
        val catalog = config()
            .upsertingProfile(second, operator)
            .upsertingProfile(third, operator)

        val remaining = requireNotNull(catalog.removingPaymentProfile(third.id))

        assertEquals(listOf(first, second), remaining.resolvedPaymentProfiles())
        assertEquals(first.id, remaining.selectedProfileId)
        assertEquals(second.id, remaining.removingPaymentProfile(first.id)?.selectedProfileId)
    }

    private fun config() = TerminalConfigSnapshot(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84532,
        factoryAddress = "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
        receiverImplementationAddress = "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
        vaultAddress = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
        confirmationBlocks = 1,
        paymentTokens = listOf(
            PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
        ),
        protocolVersion = "1.4.1",
        provisionedOperatorAddress = "0x1111111111111111111111111111111111111111",
        provisioned = true,
    )
}
