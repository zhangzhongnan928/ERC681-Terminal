package com.openpasskey.terminal

import android.app.Application
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.repository.InvoiceRepository
import com.openpasskey.terminal.data.repository.SettlementRepository
import com.openpasskey.terminal.wallet.OperatorWalletStore

class OPKTerminalApp : Application() {
    val chainConfig by lazy { ChainConfig(this) }
    val invoiceDatabase by lazy { InvoiceDatabase.getInstance(this) }
    val operatorWalletStore by lazy { OperatorWalletStore(this) }
    val invoiceRepository by lazy {
        InvoiceRepository(invoiceDatabase.invoiceDao(), chainConfig, operatorWalletStore)
    }
    val settlementRepository by lazy {
        SettlementRepository(invoiceDatabase, operatorWalletStore)
    }
}
