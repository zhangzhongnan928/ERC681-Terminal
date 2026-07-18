package com.openpasskey.terminal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.openpasskey.terminal.ui.navigation.AppNavigation
import com.openpasskey.terminal.ui.theme.OPKTerminalTheme
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import com.openpasskey.terminal.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as OPKTerminalApp
        val invoiceViewModel = ViewModelProvider(
            this,
            InvoiceViewModel.Factory(app.invoiceRepository, app.chainConfig)
        )[InvoiceViewModel::class.java]
        val settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(app.chainConfig)
        )[SettingsViewModel::class.java]

        setContent {
            OPKTerminalTheme {
                AppNavigation(invoiceViewModel, settingsViewModel)
            }
        }
    }
}
