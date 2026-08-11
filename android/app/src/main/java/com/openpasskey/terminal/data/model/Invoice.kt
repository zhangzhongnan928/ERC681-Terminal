package com.openpasskey.terminal.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey val invoiceId: String,
    val receiver: String,
    /** Device EOA used as the terminal identifier when this immutable invoice was derived. */
    @ColumnInfo(defaultValue = "''") val operatorAddress: String = "",
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
    // The database default remains 2 so pre-policy legacy rows keep their historical snapshot.
    // New invoices explicitly snapshot a profile, while direct model construction defaults to 1.
    @ColumnInfo(defaultValue = "2") val confirmationBlocks: Int = 1,
    @ColumnInfo(defaultValue = "''") val erc681Uri: String = "",
    /** Canonical checkout head before this receiver QR was published. */
    val publishedAtBlock: Long? = null,
    val publishedAtBlockHash: String? = null,
    val firstDetectedBlock: Long? = null,
    val firstDetectedBlockHash: String? = null,
    val lastObservedBlock: Long? = null,
    val confirmedAtBlock: Long? = null,
    /** Incoming consumer payment evidence. This is never the later merchant sweep hash. */
    val paymentTxHash: String? = null,
    val paymentPayerAddress: String? = null,
    val paymentBlockNumber: Long? = null,
    val paymentBlockHash: String? = null,
    /** Canonical payment-block timestamp, in Unix seconds. */
    val paidAt: Long? = null,
    /** Stable number allocated before the QR is shown, so reprints remain identical. */
    @ColumnInfo(defaultValue = "0") val receiptNumber: Long = 0,
    /** Immutable merchant identity captured when this invoice QR is published. */
    @ColumnInfo(defaultValue = "'OPK Terminal'") val receiptMerchantName: String = "OPK Terminal",
    @ColumnInfo(defaultValue = "''") val receiptMerchantAbn: String = "",
    /** False for migrated rows, whose incoming payment transaction cannot be safely inferred. */
    @ColumnInfo(defaultValue = "0") val receiptAutoPrintEligible: Boolean = false,
    /** Set only after the printer reports successful completion of the whole buffered job. */
    val receiptPrintedAt: Long? = null,
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
