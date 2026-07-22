package com.openpasskey.terminal.chain

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android storage boundary, rather than only the snapshot helpers. */
@RunWith(AndroidJUnit4::class)
class ChainConfigSharedPreferencesMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferences
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        assertTrue(preferences.edit().clear().commit())
    }

    @After
    fun cleanUpPreferences() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun storedV2ProfileMigratesIntoV3CatalogAndSurvivesReopen() {
        assertTrue(
            preferences.edit()
                .putString(V2_CONFIG_KEY, V2_SINGLE_PROFILE_JSON)
                .putBoolean(V2_PROVISIONED_KEY, true)
                .commit(),
        )

        // A process reopening an untouched v2 install must immediately see one selected profile.
        val firstOpen = ChainConfig(context).snapshot()
        val legacyProfile = firstOpen.resolvedPaymentProfiles().single()
        assertTrue(firstOpen.provisioned)
        assertEquals(legacyProfile.id, firstOpen.selectedPaymentProfile()?.id)
        assertEquals(84532L, legacyProfile.chainId)
        assertEquals("AUD", legacyProfile.token.symbol)

        val reopenedV2 = ChainConfig(context).snapshot()
        assertEquals(firstOpen, reopenedV2)

        // The first safe read has already made the v2 -> v3 normalization durable.
        assertTrue(preferences.getBoolean(V3_PROVISIONED_KEY, false))
        assertFalse(preferences.contains(V2_PROVISIONED_KEY))
        assertFalse(preferences.contains(V2_CONFIG_KEY))
        val secondProfile = legacyProfile.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken(
                address = "0x3333333333333333333333333333333333333333",
                symbol = "USDC",
                decimals = 6,
            ),
        )
        val v3Catalog = firstOpen.upsertingProfile(
            secondProfile,
            requireNotNull(firstOpen.provisionedOperatorAddress),
        )
        assertTrue(ChainConfig(context).compareAndReplaceProvisioned(firstOpen, v3Catalog))

        assertTrue(preferences.getBoolean(V3_PROVISIONED_KEY, false))
        assertNotNull(preferences.getString(V3_CONFIG_KEY, null))
        assertFalse(preferences.contains(V2_PROVISIONED_KEY))
        assertFalse(preferences.contains(V2_CONFIG_KEY))

        // A fresh production object must reopen the canonical two-profile v3 catalog.
        val reopenedV3 = ChainConfig(context).snapshot()
        assertEquals(2, reopenedV3.resolvedPaymentProfiles().size)
        assertEquals(secondProfile.id, reopenedV3.selectedProfileId)
        assertEquals(secondProfile.id, reopenedV3.selectedPaymentProfile()?.id)
        assertEquals(84532L, reopenedV3.chainId)
        assertEquals("USDC", reopenedV3.paymentTokens.single().symbol)
        assertTrue(reopenedV3.hasCompleteProvisioning())
    }

    @Test
    fun selectingProfilePersistsTheCashierChoiceAcrossReopen() {
        assertTrue(
            preferences.edit()
                .putString(V2_CONFIG_KEY, V2_SINGLE_PROFILE_JSON)
                .putBoolean(V2_PROVISIONED_KEY, true)
                .commit(),
        )
        val config = ChainConfig(context)
        val initial = config.snapshot()
        val first = initial.resolvedPaymentProfiles().single()
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken(
                address = "0x3333333333333333333333333333333333333333",
                symbol = "USDC",
                decimals = 6,
            ),
        )
        val catalog = initial.upsertingProfile(
            second,
            requireNotNull(initial.provisionedOperatorAddress),
        )
        assertTrue(config.compareAndReplaceProvisioned(initial, catalog))
        assertEquals(second.id, config.snapshot().selectedProfileId)

        assertTrue(config.selectProfile(first.id))

        val reopened = ChainConfig(context).snapshot()
        assertEquals(first.id, reopened.selectedProfileId)
        assertEquals(first.id, reopened.selectedPaymentProfile()?.id)
        assertEquals("AUD", reopened.paymentTokens.single().symbol)
        assertEquals(2, reopened.resolvedPaymentProfiles().size)
        assertTrue(reopened.hasCompleteProvisioning())
    }

    @Test
    fun storedV2OneConfirmationMigratesWithoutAdjustmentOrNotice() {
        assertTrue(
            preferences.edit()
                .putString(
                    V2_CONFIG_KEY,
                    V2_SINGLE_PROFILE_JSON.replace(
                        "\"confirmationBlocks\": 2",
                        "\"confirmationBlocks\": 1",
                    ),
                )
                .putBoolean(V2_PROVISIONED_KEY, true)
                .commit(),
        )

        val firstOpen = ChainConfig(context)
        val migrated = firstOpen.snapshot()
        val selected = requireNotNull(migrated.selectedPaymentProfile())

        assertTrue(migrated.provisioned)
        assertEquals(1, migrated.confirmationBlocks)
        assertEquals(1, selected.confirmationBlocks)
        assertNull(firstOpen.pendingMigrationNotice())
        assertTrue(preferences.getBoolean(V3_PROVISIONED_KEY, false))
        assertFalse(preferences.contains(V2_PROVISIONED_KEY))
        assertFalse(preferences.contains(V2_CONFIG_KEY))

        val reopened = ChainConfig(context)
        assertEquals(migrated, reopened.snapshot())
        assertNull(reopened.pendingMigrationNotice())
        assertTrue(reopened.isConfigured())
    }

    @Test
    fun networkConfirmationUpdatePersistsForEveryProfileAcrossReopen() {
        assertTrue(
            preferences.edit()
                .putString(
                    V2_CONFIG_KEY,
                    V2_SINGLE_PROFILE_JSON.replace(
                        "\"confirmationBlocks\": 2",
                        "\"confirmationBlocks\": 1",
                    ),
                )
                .putBoolean(V2_PROVISIONED_KEY, true)
                .commit(),
        )
        val config = ChainConfig(context)
        val migrated = config.snapshot()
        val first = requireNotNull(migrated.selectedPaymentProfile())
        val second = first.copy(
            vaultAddress = "0x2222222222222222222222222222222222222222",
            token = PaymentToken(
                address = "0x3333333333333333333333333333333333333333",
                symbol = "USDC",
                decimals = 6,
            ),
        )
        val twoProfiles = migrated.upsertingProfile(
            second,
            requireNotNull(migrated.provisionedOperatorAddress),
        )
        assertTrue(config.compareAndReplaceProvisioned(migrated, twoProfiles))

        assertTrue(config.updateNetworkConfirmationBlocks(84532, 7))

        val reopened = ChainConfig(context).snapshot()
        assertEquals(2, reopened.resolvedPaymentProfiles().size)
        assertEquals(setOf(7), reopened.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet())
        assertEquals(7, reopened.confirmationBlocks)
        assertEquals(second.id, reopened.selectedProfileId)
        assertTrue(reopened.hasCompleteProvisioning())
    }

    @Test
    fun divergentV3NetworkConfirmationsNormalizeToStrongestAndPersistAcrossReopen() {
        assertTrue(
            preferences.edit()
                .putString(
                    V3_CONFIG_KEY,
                    v3CatalogJson(
                        confirmationBlocks = 1,
                        profileEntries = "${profileJson(1)},${secondProfileJson(7)}",
                    ),
                )
                .putBoolean(V3_PROVISIONED_KEY, true)
                .commit(),
        )

        val firstOpen = ChainConfig(context)
        val normalized = firstOpen.snapshot()

        assertEquals(2, normalized.resolvedPaymentProfiles().size)
        assertEquals(
            setOf(7),
            normalized.resolvedPaymentProfiles().map { it.confirmationBlocks }.toSet(),
        )
        assertEquals(7, normalized.confirmationBlocks)
        assertEquals(PROFILE_ID, normalized.selectedProfileId)
        assertEquals(
            setOf(PROFILE_ID),
            firstOpen.pendingMigrationNotice()?.adjustedConfirmationProfileIds,
        )

        val reopened = ChainConfig(context)
        assertEquals(normalized, reopened.snapshot())
        assertEquals(
            setOf(PROFILE_ID),
            reopened.pendingMigrationNotice()?.adjustedConfirmationProfileIds,
        )
        assertTrue(reopened.isConfigured())
    }

    @Test
    fun removingLastV3ProfileClearsProvisioningStorage() {
        assertTrue(
            preferences.edit()
                .putString(V3_CONFIG_KEY, v3CatalogJson())
                .putBoolean(V3_PROVISIONED_KEY, true)
                .commit(),
        )
        val config = ChainConfig(context)
        assertTrue(config.isConfigured())

        assertTrue(config.removeProfile(PROFILE_ID))

        assertFalse(preferences.contains(V3_PROVISIONED_KEY))
        assertFalse(preferences.contains(V3_CONFIG_KEY))
        assertFalse(config.snapshot().provisioned)
        assertFalse(config.isConfigured())
    }

    @Test
    fun malformedV3CatalogsFailClosedWithoutFallingBack() {
        val malformedCatalogs = mapOf(
            "corrupt JSON" to "{not-json",
            "empty catalog" to v3CatalogJson(profileEntries = ""),
            "duplicate identity" to v3CatalogJson(
                profileEntries = "${profileJson()},${profileJson()}",
            ),
            "missing selected identity" to v3CatalogJson(
                selectedProfileId =
                    "eip155:84532:0x9999999999999999999999999999999999999999:" +
                        "0x8888888888888888888888888888888888888888",
            ),
            "invalid zero finality" to v3CatalogJson(confirmationBlocks = 0),
        )

        malformedCatalogs.forEach { (label, json) ->
            assertTrue(preferences.edit().clear().commit())
            assertTrue(
                preferences.edit()
                    .putString(V3_CONFIG_KEY, json)
                    .putBoolean(V3_PROVISIONED_KEY, true)
                    // A valid v2 value must not rescue an authoritative malformed v3 catalog.
                    .putString(V2_CONFIG_KEY, V2_SINGLE_PROFILE_JSON)
                    .putBoolean(V2_PROVISIONED_KEY, true)
                    .commit(),
            )

            val config = ChainConfig(context)
            assertFalse(label, config.snapshot().provisioned)
            assertFalse(label, config.isConfigured())
            assertTrue(label, preferences.getBoolean(V3_PROVISIONED_KEY, false))
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "opk_chain_config"
        const val V2_CONFIG_KEY = "provisioned_config_v2"
        const val V2_PROVISIONED_KEY = "is_provisioned_v2"
        const val V3_CONFIG_KEY = "provisioned_config_v3"
        const val V3_PROVISIONED_KEY = "is_provisioned_v3"
        const val PROFILE_ID =
            "eip155:84532:0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1:" +
                "0x7ffba642bc902880a737cb1c18a4e9540879e211"

        val V2_SINGLE_PROFILE_JSON =
            """
            {
              "networkName": "Base Sepolia",
              "rpcUrl": "https://sepolia.base.org",
              "chainId": 84532,
              "factoryAddress": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
              "receiverImplementationAddress": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
              "vaultAddress": "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
              "confirmationBlocks": 2,
              "paymentTokens": [
                {
                  "address": "0x7ffba642bc902880a737cb1c18a4e9540879e211",
                  "symbol": "AUD",
                  "decimals": 18
                }
              ],
              "protocolVersion": "1.4.1",
              "provisionedOperatorAddress": "0x1111111111111111111111111111111111111111",
              "provisioned": true
            }
            """.trimIndent()

        fun profileJson(confirmationBlocks: Int = 1) =
            """
            {
              "networkName": "Base Sepolia",
              "rpcUrl": "https://sepolia.base.org",
              "chainId": 84532,
              "factoryAddress": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
              "receiverImplementationAddress": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
              "vaultAddress": "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
              "confirmationBlocks": $confirmationBlocks,
              "token": {
                "address": "0x7ffba642bc902880a737cb1c18a4e9540879e211",
                "symbol": "AUD",
                "decimals": 18
              },
              "protocolVersion": "1.4.1"
            }
            """.trimIndent()

        fun secondProfileJson(confirmationBlocks: Int = 1) =
            """
            {
              "networkName": "Base Sepolia",
              "rpcUrl": "https://sepolia.base.org",
              "chainId": 84532,
              "factoryAddress": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
              "receiverImplementationAddress": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
              "vaultAddress": "0x2222222222222222222222222222222222222222",
              "confirmationBlocks": $confirmationBlocks,
              "token": {
                "address": "0x3333333333333333333333333333333333333333",
                "symbol": "USDC",
                "decimals": 6
              },
              "protocolVersion": "1.4.1"
            }
            """.trimIndent()

        fun v3CatalogJson(
            confirmationBlocks: Int = 1,
            selectedProfileId: String = PROFILE_ID,
            profileEntries: String = profileJson(confirmationBlocks),
        ) =
            """
            {
              "networkName": "Base Sepolia",
              "rpcUrl": "https://sepolia.base.org",
              "chainId": 84532,
              "factoryAddress": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
              "receiverImplementationAddress": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
              "vaultAddress": "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
              "confirmationBlocks": $confirmationBlocks,
              "paymentTokens": [{
                "address": "0x7ffba642bc902880a737cb1c18a4e9540879e211",
                "symbol": "AUD",
                "decimals": 18
              }],
              "protocolVersion": "1.4.1",
              "provisionedOperatorAddress": "0x1111111111111111111111111111111111111111",
              "provisioned": true,
              "paymentProfiles": [$profileEntries],
              "selectedProfileId": "$selectedProfileId"
            }
            """.trimIndent()
    }
}
