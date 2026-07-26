package com.openpasskey.terminal.ui.demo

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class ReviewerDemoScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun demoShowsSafetyLabelsSimulatesPaymentAndKeepsSettlementInert() {
        composeRule.setContent {
            ReviewerDemoScreen(onClose = {})
        }

        composeRule.onNodeWithText(ReviewerDemoCopy.BANNER_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Demo ERC-681 payment QR code")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("reviewer_demo_simulate_payment")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Paid").assertIsDisplayed()

        composeRule.onNodeWithTag("reviewer_demo_nav_history").performClick()
        composeRule.onNodeWithTag("reviewer_demo_safety").assertIsDisplayed()
        composeRule.onNodeWithTag("reviewer_demo_history_card").assertIsDisplayed()
        composeRule.onNodeWithText("Demo session only · never saved").assertIsDisplayed()

        composeRule.onNodeWithTag("reviewer_demo_nav_settlement").performClick()
        composeRule.onNodeWithTag("reviewer_demo_safety").assertIsDisplayed()
        composeRule.onNodeWithTag("reviewer_demo_settlement_card").assertIsDisplayed()
        composeRule.onNodeWithTag("reviewer_demo_settlement_action")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(ReviewerDemoCopy.SETTLEMENT_EXPLANATION)
            .assertIsDisplayed()
    }

    @Test
    fun closingAndReopeningCreatesAFreshWaitingSession() {
        val open = mutableStateOf(true)
        composeRule.setContent {
            if (open.value) {
                ReviewerDemoScreen(onClose = { open.value = false })
            } else {
                Button(
                    onClick = { open.value = true },
                    modifier = Modifier.testTag("reopen_reviewer_demo"),
                ) {
                    Text("Reopen demo")
                }
            }
        }
        composeRule.onNodeWithTag("reviewer_demo_simulate_payment")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Paid").assertIsDisplayed()
        composeRule.onNodeWithTag("reviewer_demo_close").performClick()
        composeRule.onNodeWithTag("reopen_reviewer_demo").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
    }
}
