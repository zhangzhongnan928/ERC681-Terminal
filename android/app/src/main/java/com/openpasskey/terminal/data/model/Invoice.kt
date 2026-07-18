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
    val lastObservedBlock: Long? = null,
    val confirmedAtBlock: Long? = null,
    // Kept for the non-destructive v1 -> v2 database migration. QR-only code never writes it.
    val settledTxHash: String? = null
)

enum class InvoiceStatus {
    WAITING,
    PARTIAL,
    CONFIRMING,
    PAID,
    OVERPAID,
    EXPIRED
}
