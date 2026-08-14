package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CheckoutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyCheckoutSupportsExactKeypadAndAccessibilityFlow() {
        var amount by mutableStateOf("")
        composeRule.setContent {
            CheckoutReadyScreen(
                amount = amount,
                profile = PROFILE,
                profiles = listOf(PROFILE),
                error = null,
                staleReadinessNotice = null,
                lowGasWarning = null,
                isCreating = false,
                onAmountChanged = { amount = it },
                onProfileSelected = {},
                onCreateInvoice = {},
            )
        }

        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithText("TESTNET").assertIsDisplayed()
        composeRule.onNodeWithTag("checkout_amount")
            .assertContentDescriptionEquals("Checkout amount, 0.00 AUD")
        composeRule.onNodeWithTag("checkout_cta").assertIsNotEnabled()

        composeRule.onNodeWithTag("checkout_key_one").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("checkout_key_decimal")
            .assertContentDescriptionEquals("Decimal point")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("checkout_amount")
            .assertContentDescriptionEquals("Checkout amount, 1. AUD")
        composeRule.onNodeWithTag("checkout_key_two").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("checkout_amount")
            .assertContentDescriptionEquals("Checkout amount, 1.2 AUD")
        composeRule.onNodeWithTag("checkout_cta")
            .assertContentDescriptionEquals("Show payment QR for 1.2 AUD")
            .assertIsEnabled()
            .assertHasClickAction()
        composeRule.onNodeWithTag("checkout_key_backspace")
            .performScrollTo()
            .assertContentDescriptionEquals("Delete last digit")
        composeRule.onNodeWithTag("checkout_clear")
            .performScrollTo()
            .assertContentDescriptionEquals("Clear amount")
            .performClick()
        composeRule.onNodeWithTag("checkout_amount")
            .assertContentDescriptionEquals("Checkout amount, 0.00 AUD")
    }

    @Test
    fun compactLargeFontBlockerKeepsActionReachable() {
        var opened = false
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                Box(Modifier.requiredSize(360.dp, 300.dp).clipToBounds()) {
                    CheckoutBlocker(
                        status = TerminalSetupStatus.AWAITING_GAS,
                        statusMessage = null,
                        onOpenSettings = { opened = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("checkout_blocker_action")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun maximumUintAmountIsAnnouncedInFullWithExplicitConciseAction() {
        val amount =
            "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        composeRule.setContent {
            CheckoutReadyScreen(
                amount = amount,
                profile = PROFILE.copy(token = TOKEN.copy(decimals = 0)),
                profiles = listOf(PROFILE.copy(token = TOKEN.copy(decimals = 0))),
                error = null,
                staleReadinessNotice = null,
                lowGasWarning = null,
                isCreating = false,
                onAmountChanged = {},
                onProfileSelected = {},
                onCreateInvoice = {},
            )
        }

        composeRule.onNodeWithTag("checkout_amount")
            .assertContentDescriptionEquals("Checkout amount, $amount AUD")
        composeRule.onNodeWithTag("checkout_cta")
            .assertContentDescriptionEquals("Show payment QR for $amount AUD")
            .assertIsEnabled()
    }

    @Test
    fun preservedReadinessAndLowGasNoticesRenderInsideReadyCheckout() {
        val staleNotice = "Terminal is ready to create payments. The latest status re-check " +
            "could not reach the RPC provider; showing the last validated result."
        val lowGas = "Operator gas is low. Checkout still works; fund the operator with at " +
            "least 0.0001 ETH so settlement can run."
        composeRule.setContent {
            CheckoutReadyScreen(
                amount = "12.34",
                profile = PROFILE,
                profiles = listOf(PROFILE),
                error = null,
                staleReadinessNotice = staleNotice,
                lowGasWarning = lowGas,
                isCreating = false,
                onAmountChanged = {},
                onProfileSelected = {},
                onCreateInvoice = {},
            )
        }

        composeRule.onNodeWithTag("checkout_stale_readiness_notice").assertIsDisplayed()
        composeRule.onNodeWithText(staleNotice).assertIsDisplayed()
        composeRule.onNodeWithText(lowGas).assertIsDisplayed()
        // Preserved readiness never blocks the sale: a valid amount keeps the CTA enabled.
        composeRule.onNodeWithTag("checkout_cta")
            .assertContentDescriptionEquals("Show payment QR for 12.34 AUD")
            .assertIsEnabled()
        composeRule.onNodeWithTag("checkout_key_one").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withoutPreservationNoStaleNoticeRendersInsideReadyCheckout() {
        composeRule.setContent {
            CheckoutReadyScreen(
                amount = "12.34",
                profile = PROFILE,
                profiles = listOf(PROFILE),
                error = null,
                staleReadinessNotice = null,
                lowGasWarning = null,
                isCreating = false,
                onAmountChanged = {},
                onProfileSelected = {},
                onCreateInvoice = {},
            )
        }

        composeRule.onNodeWithTag("checkout_stale_readiness_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("checkout_cta").assertIsEnabled()
    }

    @Test
    fun sameSymbolAndVaultProfilesExposeTokenAddressAndCanBeSelected() {
        val second = PROFILE.copy(
            token = TOKEN.copy(address = "0x5555555555555555555555555555555555555555"),
        )
        var selected by mutableStateOf(PROFILE)
        composeRule.setContent {
            CheckoutReadyScreen(
                amount = "",
                profile = selected,
                profiles = listOf(PROFILE, second),
                error = null,
                staleReadinessNotice = null,
                lowGasWarning = null,
                isCreating = false,
                onAmountChanged = {},
                onProfileSelected = { selected = it },
                onCreateInvoice = {},
            )
        }

        composeRule.onNodeWithTag("checkout_profile_selector")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(
            "AUD · Base Sepolia\nVault 0x4444…4444 · Asset 0x5555…5555",
        ).performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(second, selected) }
        composeRule.onNodeWithText(
            "AUD · Base Sepolia · 0x4444…4444 · 0x5555…5555",
        ).assertIsDisplayed()
    }

    private companion object {
        val TOKEN = PaymentToken(
            address = "0x1111111111111111111111111111111111111111",
            symbol = "AUD",
            decimals = 2,
        )
        val PROFILE = TerminalPaymentProfile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84532,
            factoryAddress = "0x2222222222222222222222222222222222222222",
            receiverImplementationAddress = "0x3333333333333333333333333333333333333333",
            vaultAddress = "0x4444444444444444444444444444444444444444",
            confirmationBlocks = 2,
            token = TOKEN,
            protocolVersion = "1.6",
        )
    }
}
