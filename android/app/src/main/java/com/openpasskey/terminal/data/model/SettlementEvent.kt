package com.openpasskey.terminal.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable receipt evidence. uint256 values remain decimal strings to avoid SQLite truncation. */
@Entity(
    tableName = "settlement_events",
    indices = [
        Index(value = ["settlementId"]),
        Index(value = ["invoiceId"]),
        Index(value = ["chainId", "vaultAddress", "invoiceId", "tokenAddress"]),
        Index(value = ["chainId", "transactionHash", "logIndex"], unique = true)
    ]
)
data class SettlementEvent(
    @PrimaryKey val eventId: String,
    val settlementId: String,
    val invoiceId: String,
    val chainId: Long,
    val transactionHash: String,
    val blockHash: String,
    val blockNumber: Long,
    val logIndex: Long,
    val receiverAddress: String,
    val vaultAddress: String,
    val tokenAddress: String,
    val sweptAmount: String,
    val expectedAmount: String,
    val feeAmount: String,
    val recordedAt: Long
)
