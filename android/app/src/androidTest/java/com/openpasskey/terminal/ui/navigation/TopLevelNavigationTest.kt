package com.openpasskey.terminal.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test

class TopLevelNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsOpenedFromCheckoutReturnsToCheckout() {
        composeRule.setContent {
            val controller = rememberNavController()
            val current by controller.currentBackStackEntryAsState()
            Column {
                NavHost(
                    navController = controller,
                    startDestination = CHECKOUT_ROUTE,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(CHECKOUT_ROUTE) {
                        Column {
                            Text("Checkout destination", Modifier.testTag("checkout_destination"))
                            Button(
                                onClick = { controller.navigateTopLevel(SETTINGS_ROUTE) },
                                modifier = Modifier.testTag("open_settings"),
                            ) {
                                Text("Open Settings")
                            }
                        }
                    }
                    composable(SETTINGS_ROUTE) {
                        Text("Settings destination", Modifier.testTag("settings_destination"))
                    }
                }
                BottomNavigationBar(controller, current?.destination?.route)
            }
        }

        composeRule.onNodeWithTag("checkout_destination").assertIsDisplayed()
        composeRule.onNodeWithTag("open_settings").performClick()
        composeRule.onNodeWithTag("settings_destination").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Checkout", useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("checkout_destination").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_destination").assertDoesNotExist()
    }

    private companion object {
        const val CHECKOUT_ROUTE = "invoice"
        const val SETTINGS_ROUTE = "settings"
    }
}
