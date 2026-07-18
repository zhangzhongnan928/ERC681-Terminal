package com.openpasskey.terminal

import android.app.Application
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.repository.InvoiceRepository

class OPKTerminalApp : Application() {
    val chainConfig by lazy { ChainConfig(this) }
    val invoiceDatabase by lazy { InvoiceDatabase.getInstance(this) }
    val invoiceRepository by lazy { InvoiceRepository(invoiceDatabase.invoiceDao(), chainConfig) }
}
