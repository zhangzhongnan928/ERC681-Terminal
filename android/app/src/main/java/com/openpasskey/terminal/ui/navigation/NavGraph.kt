package com.openpasskey.terminal.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openpasskey.terminal.ui.screens.HistoryScreen
import com.openpasskey.terminal.ui.screens.InvoiceScreen
import com.openpasskey.terminal.ui.screens.PaymentScreen
import com.openpasskey.terminal.ui.screens.SettingsScreen
import com.openpasskey.terminal.ui.screens.SettlementScreen
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import com.openpasskey.terminal.viewmodel.SettingsViewModel
import com.openpasskey.terminal.viewmodel.SettlementViewModel

private object Routes {
    const val INVOICE = "invoice"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SETTLEMENT = "settlement"
    const val PAYMENT = "payment/{invoiceId}"
    fun payment(invoiceId: String) = "payment/$invoiceId"
}

private data class NavItem(val label: String, val icon: ImageVector, val route: String)
private val navItems = listOf(
    NavItem("Checkout", Icons.Default.PointOfSale, Routes.INVOICE),
    NavItem("History", Icons.Default.History, Routes.HISTORY),
    NavItem("Settle", Icons.Default.SyncAlt, Routes.SETTLEMENT),
    NavItem("Settings", Icons.Default.Settings, Routes.SETTINGS)
)

@Composable
fun AppNavigation(
    invoiceViewModel: InvoiceViewModel,
    settingsViewModel: SettingsViewModel,
    settlementViewModel: SettlementViewModel
) {
    val controller = rememberNavController()
    val settingsState by settingsViewModel.state.collectAsState()
    val current by controller.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    val showBottomBar = currentRoute in navItems.map { it.route }

    LaunchedEffect(settingsState.selectedProfileId, settingsState.paymentProfiles) {
        invoiceViewModel.refreshConfiguration()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) BottomNavigationBar(controller, currentRoute)
        }
    ) { padding ->
        NavHost(
            navController = controller,
            startDestination = Routes.INVOICE,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.INVOICE) {
                InvoiceScreen(
                    viewModel = invoiceViewModel,
                    terminalStatus = settingsState.setupStatus,
                    terminalStatusMessage = settingsState.message,
                    terminalRefreshing = settingsState.refreshingOperator,
                    terminalConfigurationValidated = settingsState.configurationValidated,
                    onRefreshTerminalStatus = {
                        settingsViewModel.refreshOperatorStatusAutomatically(
                            invoiceViewModel::completeReadinessRefresh,
                        )
                    },
                    onProfileSelection = { sequence, profileId ->
                        settingsViewModel.refreshOperatorStatusAfterProfileSelection(
                            { ready ->
                                invoiceViewModel.completeProfileSelectionReadinessRefresh(
                                    sequence = sequence,
                                    profileId = profileId,
                                    ready = ready,
                                )
                            },
                        )
                    },
                    onRecoverFromInvoiceFailure = {
                        settingsViewModel.refreshOperatorStatusAfterInvoiceFailure(
                            invoiceViewModel::completeReadinessRefresh,
                        )
                    },
                    onOpenSettings = {
                        controller.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    },
                ) { controller.navigate(Routes.payment(it)) }
            }
            composable(Routes.HISTORY) {
                HistoryScreen(invoiceViewModel) { controller.navigate(Routes.payment(it)) }
            }
            composable(Routes.SETTLEMENT) { SettlementScreen(settlementViewModel) }
            composable(Routes.SETTINGS) { SettingsScreen(settingsViewModel) }
            composable(
                route = Routes.PAYMENT,
                arguments = listOf(navArgument("invoiceId") { type = NavType.StringType })
            ) { entry ->
                val invoiceId = entry.arguments?.getString("invoiceId") ?: return@composable
                PaymentScreen(invoiceId, invoiceViewModel) { controller.popBackStack() }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(controller: NavHostController, currentRoute: String?) {
    NavigationBar {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    controller.navigate(item.route) {
                        popUpTo(Routes.INVOICE) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}
