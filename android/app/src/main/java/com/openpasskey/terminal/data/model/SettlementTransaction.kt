package com.openpasskey.terminal.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable record of one signed sweepSessions transaction. Amounts and fee fields are decimal
 * strings so no EVM uint256 value is truncated by SQLite's signed 64-bit INTEGER representation.
 */
@Entity(tableName = "settlement_transactions")
data class SettlementTransaction(
    @PrimaryKey val id: String,
    val chainId: Long,
    val networkName: String,
    val rpcUrl: String,
    val vaultAddress: String,
    val tokenAddress: String,
    val tokenSymbol: String,
    val operatorAddress: String,
    val invoiceIdsJson: String,
    val expectedAmountsJson: String,
    val receiverAddressesJson: String,
    val requiredConfirmations: Int,
    val callData: String,
    val nonce: String,
    val gasLimit: String,
    val feeMode: SettlementFeeMode,
    val gasPrice: String?,
    val maxPriorityFeePerGas: String?,
    val maxFeePerGas: String?,
    val maxGasCostWei: String,
    val feeReserveWei: String,
    val requiredBalanceWei: String,
    val txHash: String,
    val signedRawTransaction: String?,
    val status: SettlementTransactionStatus,
    val verifiedInvoiceIdsJson: String = "[]",
    val verifiedEventsJson: String = "[]",
    val receiptBlock: Long? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

enum class SettlementFeeMode {
    LEGACY,
    EIP1559
}

enum class SettlementTransactionStatus {
    SIGNED,
    SUBMITTED,
    CONFIRMING,
    VERIFIED,
    PARTIALLY_VERIFIED,
    REVERTED,
    BROADCAST_FAILED,
    VERIFICATION_FAILED
}
