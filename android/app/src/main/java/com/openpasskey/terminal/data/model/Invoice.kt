package com.openpasskey.terminal.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey val invoiceId: String,
    val receiver: String,
    val token: String,
    val tokenSymbol: String,
    @ColumnInfo(defaultValue = "18") val tokenDecimals: Int = 18,
    val expectedAmount: String,
    @ColumnInfo(defaultValue = "'0'") val receivedAmount: String = "0",
    val status: InvoiceStatus,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val chainId: Long = 0,
    @ColumnInfo(defaultValue = "''") val networkName: String = "",
    @ColumnInfo(defaultValue = "''") val rpcUrl: String = "",
    @ColumnInfo(defaultValue = "''") val factoryAddress: String = "",
    @ColumnInfo(defaultValue = "''") val receiverImplementationAddress: String = "",
    @ColumnInfo(defaultValue = "''") val vaultAddress: String = "",
    @ColumnInfo(defaultValue = "2") val confirmationBlocks: Int = 2,
    @ColumnInfo(defaultValue = "''") val erc681Uri: String = "",
    val firstDetectedBlock: Long? = null,
    val firstDetectedBlockHash: String? = null,
    val lastObservedBlock: Long? = null,
    val confirmedAtBlock: Long? = null,
    val settledTxHash: String? = null,
    val settlementId: String? = null,
    val settledAtBlock: Long? = null,
    @ColumnInfo(defaultValue = "'0'") val pendingLateAmount: String = "0",
    val lateFirstDetectedBlock: Long? = null,
    val lateFirstDetectedBlockHash: String? = null,
    val lateLastObservedBlock: Long? = null,
    val lateConfirmedAtBlock: Long? = null,
    /** True until canonical receipt evidence conclusively accounts for the expected amount. */
    @ColumnInfo(defaultValue = "0") val settlementAmbiguous: Boolean = false,
    /** Durable fairness cursor for bounded recovery of still-open published receivers. */
    val openRecoveryLastAttemptAt: Long? = null,
    /** Durable fairness cursor for perpetual reconciliation of closed/swept receivers. */
    val lateRecoveryLastAttemptAt: Long? = null,
)

enum class InvoiceStatus {
    WAITING,
    PARTIAL,
    CONFIRMING,
    PAID,
    OVERPAID,
    PARTIALLY_SETTLED,
    LATE_PAYMENT_CONFIRMING,
    LATE_PAYMENT_READY,
    SETTLED,
    SETTLEMENT_REVIEW_REQUIRED,
    EXPIRED
}
