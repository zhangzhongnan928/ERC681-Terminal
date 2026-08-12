package com.openpasskey.terminal.ui.screens

import com.openpasskey.terminal.viewmodel.SettingsState
import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun `store links use required public labels and URLs`() {
        assertEquals("Privacy Policy", SettingsExternalLinks.privacyPolicy.label)
        assertEquals(
            "https://www.openpasskey.com/privacy",
            SettingsExternalLinks.privacyPolicy.url,
        )
        assertEquals("Support", SettingsExternalLinks.support.label)
        assertEquals(
            "https://www.openpasskey.com/support",
            SettingsExternalLinks.support.url,
        )
    }

    @Test
    fun `configuration validation is independent while authorization is pending`() {
        val state = SettingsState(
            setupStatus = TerminalSetupStatus.AWAITING_AUTHORIZATION,
            configurationValidated = true,
        )

        assertEquals("On-chain validation passed", configurationValidationLabel(state))
    }

    @Test
    fun `configuration validation remains passed while gas is pending`() {
        val state = SettingsState(
            setupStatus = TerminalSetupStatus.AWAITING_GAS,
            configurationValidated = true,
        )

        assertEquals("On-chain validation passed", configurationValidationLabel(state))
    }

    @Test
    fun `active refresh takes precedence over cached validation`() {
        val state = SettingsState(
            setupStatus = TerminalSetupStatus.READY,
            configurationValidated = true,
            refreshingOperator = true,
        )

        assertEquals("On-chain validation in progress", configurationValidationLabel(state))
    }

    @Test
    fun `privileged setup stays busy for provisioning and every settings save`() {
        assertFalse(privilegedSetupBusy(SettingsState(setupStatus = TerminalSetupStatus.READY)))
        assertTrue(
            privilegedSetupBusy(SettingsState(setupStatus = TerminalSetupStatus.PROVISIONING)),
        )
        assertTrue(
            privilegedSetupBusy(
                SettingsState(
                    setupStatus = TerminalSetupStatus.READY,
                    savingMerchantReceiptProfile = true,
                ),
            ),
        )
        assertTrue(
            privilegedSetupBusy(
                SettingsState(
                    setupStatus = TerminalSetupStatus.READY,
                    savingAutoSweepPreference = true,
                ),
            ),
        )
        assertTrue(
            privilegedSetupBusy(
                SettingsState(
                    setupStatus = TerminalSetupStatus.READY,
                    savingRpcEndpointChainId = 8453,
                ),
            ),
        )
    }

    @Test
    fun `merchant portal scan waits for at least one configured network endpoint`() {
        assertFalse(
            canScanMerchantPortalSetup(
                SettingsState(provisioningRpcEndpointAvailable = false),
            ),
        )
        assertTrue(
            canScanMerchantPortalSetup(
                SettingsState(provisioningRpcEndpointAvailable = true),
            ),
        )
    }
}
