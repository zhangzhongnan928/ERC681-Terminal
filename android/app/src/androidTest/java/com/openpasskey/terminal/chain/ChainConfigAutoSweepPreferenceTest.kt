package com.openpasskey.terminal.chain

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openpasskey.terminal.printing.AutomaticReceiptClaimResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChainConfigAutoSweepPreferenceTest {
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
    fun preferenceDefaultsOffPersistsAndIsClearedByOperatorReset() {
        val config = ChainConfig(context)
        assertFalse(config.autoSweepEnabled())
        assertTrue(config.updateAutoSweepEnabled(true))
        assertTrue(ChainConfig(context).autoSweepEnabled())
        val dismissed = setOf("invoice|paid|canonical-payment")
        assertTrue(config.updateAutoSweepDismissedFingerprints(dismissed))
        assertEquals(dismissed, ChainConfig(context).autoSweepDismissedFingerprints())

        val merchantProfile = MerchantReceiptProfile.fromInput(
            name = "Blue Brew",
            abn = "61 695 642 285",
        )
        assertTrue(config.updateMerchantReceiptProfile(merchantProfile.name, merchantProfile.abn))

        assertTrue(config.clearProvisioning())

        val reopened = ChainConfig(context)
        assertFalse(reopened.autoSweepEnabled())
        assertTrue(reopened.autoSweepDismissedFingerprints().isEmpty())
        assertEquals(merchantProfile, reopened.merchantReceiptProfile())
    }

    @Test
    fun dismissalCapacityPreservesEveryOlderReviewAndTurnsAutoSweepOff() {
        val config = ChainConfig(context)
        val existing = (0 until 512).mapTo(linkedSetOf()) { index -> "dismissed-$index" }
        val newest = "dismissed-newest"
        assertTrue(config.updateAutoSweepDismissedFingerprints(existing))
        assertTrue(config.updateAutoSweepEnabled(true))

        assertFalse(
            config.updateAutoSweepDismissedFingerprints(
                fingerprints = existing + newest,
                retainFingerprint = newest,
            ),
        )

        val stored = config.autoSweepDismissedFingerprints()
        assertEquals(existing, stored)
        assertFalse(newest in stored)
        assertFalse(config.autoSweepEnabled())
    }

    @Test
    fun automaticReceiptClaimSurvivesReopenAndPrunesOnlyStaleSnapshots() {
        val first = "receipt-v1:" + "a".repeat(64)
        val second = "receipt-v1:" + "b".repeat(64)
        val config = ChainConfig(context)

        assertEquals(AutomaticReceiptClaimResult.CLAIMED, config.claim(first))
        assertEquals(AutomaticReceiptClaimResult.CLAIMED, config.claim(second))

        val reopened = ChainConfig(context)
        assertEquals(setOf(first, second), reopened.claims())
        assertEquals(AutomaticReceiptClaimResult.ALREADY_CLAIMED, reopened.claim(first))
        assertTrue(reopened.retainOnly(setOf(second)))
        assertEquals(setOf(second), ChainConfig(context).claims())
        assertTrue(reopened.release(second))
        assertTrue(ChainConfig(context).claims().isEmpty())
    }

    private companion object {
        const val PREFERENCES_NAME = "opk_chain_config"
    }
}
