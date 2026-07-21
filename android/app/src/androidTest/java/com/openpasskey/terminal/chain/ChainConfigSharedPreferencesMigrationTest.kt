package com.openpasskey.terminal.chain

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        // The first catalog replacement is the durable v2 -> v3 write used by provisioning.
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

    private companion object {
        const val PREFERENCES_NAME = "opk_chain_config"
        const val V2_CONFIG_KEY = "provisioned_config_v2"
        const val V2_PROVISIONED_KEY = "is_provisioned_v2"
        const val V3_CONFIG_KEY = "provisioned_config_v3"
        const val V3_PROVISIONED_KEY = "is_provisioned_v3"

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
    }
}
