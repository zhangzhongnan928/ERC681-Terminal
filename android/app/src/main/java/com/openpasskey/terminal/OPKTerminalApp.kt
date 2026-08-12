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
import com.openpasskey.terminal.rpc.RpcEndpointStore
import com.openpasskey.terminal.rpc.PinnedRpcEndpointVerifier
import com.openpasskey.terminal.printing.IminReceiptPrinter
import com.openpasskey.terminal.printing.ReceiptCoordinator

class OPKTerminalApp : Application() {
    val rpcEndpointStore by lazy {
        RpcEndpointStore(
            context = this,
            buildManagedEndpoints = buildMap {
                BuildConfig.OPK_BASE_MAINNET_RPC_URL.takeIf(String::isNotBlank)?.let {
                    put(8453L, it)
                }
                BuildConfig.OPK_BASE_SEPOLIA_RPC_URL.takeIf(String::isNotBlank)?.let {
                    put(84532L, it)
                }
            },
            allowPublicFallback = BuildConfig.OPK_ALLOW_PUBLIC_RPC_FALLBACK,
        )
    }
    val rpcEndpointVerifier by lazy { PinnedRpcEndpointVerifier() }
    val chainConfig by lazy { ChainConfig(this, rpcEndpointStore) }
    val invoiceDatabase by lazy {
        // Capture and sanitize any legacy active endpoint before Room removes historical plaintext
        // endpoint copies during its version-9 migration.
        chainConfig.snapshot()
        InvoiceDatabase.getInstance(this)
    }
    val operatorWalletStore by lazy { OperatorWalletStore(this) }
    val adminPinStore by lazy { AdminPinStore(this) }
    val terminalLifecycleGate by lazy { TerminalLifecycleGate() }
    val rpcWorkCoordinator by lazy { RpcWorkCoordinator() }
    val receiptPrinter by lazy { IminReceiptPrinter(this) }
    val terminalProvisioner by lazy {
        TerminalProvisioner(
            snapshot = chainConfig::snapshot,
            compareAndCommit = chainConfig::compareAndReplaceProvisioned,
            currentWalletSnapshot = operatorWalletStore::snapshot,
            lifecycleGate = terminalLifecycleGate,
            rpcEndpointResolver = rpcEndpointStore,
        )
    }
    val operatorResetGuard by lazy {
        DaoOperatorResetGuard(invoiceDatabase)
    }
    val terminalResetCoordinator by lazy {
        TerminalResetCoordinator(
            terminalLifecycleGate,
            operatorResetGuard,
            RpcOperatorNativeBalanceReader(
                configSnapshot = chainConfig::snapshot,
                rpcEndpointResolver = rpcEndpointStore,
            ),
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
            rpcEndpointStore,
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
            rpcEndpointStore,
        )
    }
}
