package com.openpasskey.terminal

import android.app.Application
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.admin.AdminPinStore
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.repository.InvoiceRepository
import com.openpasskey.terminal.data.repository.SettlementRepository
import com.openpasskey.terminal.wallet.OperatorWalletStore
import com.openpasskey.terminal.provisioning.TerminalProvisioner
import com.openpasskey.terminal.settlement.DaoOperatorResetGuard
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.lifecycle.RpcOperatorNativeBalanceReader
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.printing.IminReceiptPrinter
import com.openpasskey.terminal.printing.ReceiptCoordinator

class OPKTerminalApp : Application() {
    val chainConfig by lazy { ChainConfig(this) }
    val invoiceDatabase by lazy { InvoiceDatabase.getInstance(this) }
    val operatorWalletStore by lazy { OperatorWalletStore(this) }
    val adminPinStore by lazy { AdminPinStore(this) }
    val terminalLifecycleGate by lazy { TerminalLifecycleGate() }
    val rpcWorkCoordinator by lazy { RpcWorkCoordinator() }
    val receiptPrinter by lazy { IminReceiptPrinter(this) }
    val terminalProvisioner by lazy {
        TerminalProvisioner(
            chainConfig::snapshot,
            chainConfig::compareAndReplaceProvisioned,
            operatorWalletStore::snapshot,
            terminalLifecycleGate,
        )
    }
    val operatorResetGuard by lazy {
        DaoOperatorResetGuard(invoiceDatabase)
    }
    val terminalResetCoordinator by lazy {
        TerminalResetCoordinator(
            terminalLifecycleGate,
            operatorResetGuard,
            RpcOperatorNativeBalanceReader(chainConfig::snapshot),
            chainConfig::clearProvisioning,
            operatorWalletStore::resetWalletAfterExplicitConfirmation,
        )
    }
    val invoiceRepository by lazy {
        InvoiceRepository(
            invoiceDatabase.invoiceDao(),
            invoiceDatabase.settlementEventDao(),
            chainConfig,
            operatorWalletStore,
            terminalLifecycleGate,
            rpcWorkCoordinator,
        )
    }
    val receiptCoordinator by lazy {
        ReceiptCoordinator(invoiceRepository, receiptPrinter, chainConfig)
    }
    val settlementRepository by lazy {
        SettlementRepository(
            invoiceDatabase,
            operatorWalletStore,
            chainConfig,
            terminalLifecycleGate,
            rpcWorkCoordinator,
        )
    }
}
