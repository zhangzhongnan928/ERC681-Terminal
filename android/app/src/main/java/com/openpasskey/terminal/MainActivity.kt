package com.openpasskey.terminal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.openpasskey.terminal.ui.navigation.AppNavigation
import com.openpasskey.terminal.ui.theme.OPKTerminalTheme
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import com.openpasskey.terminal.viewmodel.SettingsViewModel
import com.openpasskey.terminal.viewmodel.SettlementViewModel

class MainActivity : FragmentActivity() {
    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as OPKTerminalApp
        val invoiceViewModel = ViewModelProvider(
            this,
            InvoiceViewModel.Factory(app.invoiceRepository, app.chainConfig)
        )[InvoiceViewModel::class.java]
        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(
                app.chainConfig,
                app.operatorWalletStore,
                app.adminPinStore,
                app.terminalProvisioner,
                app.terminalResetCoordinator,
                app.terminalLifecycleGate,
                app.rpcWorkCoordinator,
            )
        )[SettingsViewModel::class.java]
        val settlementViewModel = ViewModelProvider(
            this,
            SettlementViewModel.Factory(app.settlementRepository)
        )[SettlementViewModel::class.java]

        setContent {
            OPKTerminalTheme {
                AppNavigation(invoiceViewModel, settingsViewModel, settlementViewModel)
            }
        }
    }

    override fun onStop() {
        if (::settingsViewModel.isInitialized) settingsViewModel.lockAdmin()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.refreshOperatorStatusAutomatically()
        }
    }
}
