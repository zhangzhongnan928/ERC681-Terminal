package com.openpasskey.terminal.settlement

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import java.util.Locale

/**
 * The immutable route and confirmation policy shared by every invoice in one settlement batch.
 * Keeping this key outside the UI makes the grouping shown to the cashier identical to the
 * fail-closed check performed again by the repository before simulation and signing.
 */
internal data class SettlementBatchKey(
    val chainId: Long,
    val networkName: String,
    val rpcUrl: String,
    val protocolVersion: String,
    val factoryAddress: String,
    val receiverImplementationAddress: String,
    val vaultAddress: String,
    val tokenAddress: String,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val confirmationBlocks: Int,
)

internal fun Invoice.settlementBatchKey(): SettlementBatchKey = SettlementBatchKey(
    chainId = chainId,
    networkName = networkName,
    rpcUrl = rpcUrl,
    protocolVersion = pinnedProtocolVersion(),
    factoryAddress = factoryAddress.lowercase(Locale.ROOT),
    receiverImplementationAddress = receiverImplementationAddress.lowercase(Locale.ROOT),
    vaultAddress = vaultAddress.lowercase(Locale.ROOT),
    tokenAddress = token.lowercase(Locale.ROOT),
    tokenSymbol = tokenSymbol,
    tokenDecimals = tokenDecimals,
    confirmationBlocks = confirmationBlocks,
)

/**
 * Android's earliest invoice schema predates an explicit protocol-version column. In the shipped
 * app the version is an immutable property of the compiled (chain, factory, implementation) trust
 * profile. Those three values are stored on every invoice, included in this key, and checked again
 * by requirePinnedHistoricalInvoiceSnapshot before settlement, so the version is derived rather
 * than accepted as independent mutable metadata.
 */
private fun Invoice.pinnedProtocolVersion(): String {
    val profile = runCatching { KnownChainPolicy.requireProfile(chainId) }.getOrNull()
        ?: return "unsupported"
    return if (
        factoryAddress.equals(profile.factory.value, ignoreCase = true) &&
        receiverImplementationAddress.equals(
            profile.receiverImplementation.value,
            ignoreCase = true,
        )
    ) {
        profile.protocolVersion
    } else {
        "untrusted-deployment"
    }
}

internal fun requireSameSettlementBatchSnapshot(invoices: List<Invoice>) {
    require(invoices.isNotEmpty()) { "Choose at least one invoice" }
    val expected = invoices.first().settlementBatchKey()
    require(invoices.all { it.settlementBatchKey() == expected }) {
        "Batch invoices must use the same network, vault, token, and confirmation policy snapshot"
    }
}
