package com.openpasskey.terminal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openpasskey.terminal.data.model.SettlementEvent

@Dao
interface SettlementEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<SettlementEvent>)

    @Query("SELECT * FROM settlement_events WHERE settlementId = :settlementId ORDER BY logIndex")
    suspend fun getBySettlementId(settlementId: String): List<SettlementEvent>

    @Query("SELECT * FROM settlement_events WHERE invoiceId = :invoiceId ORDER BY blockNumber, logIndex")
    suspend fun getByInvoiceId(invoiceId: String): List<SettlementEvent>

    @Query(
        """SELECT * FROM settlement_events
           WHERE chainId = :chainId
             AND vaultAddress = :vaultAddress COLLATE NOCASE
             AND invoiceId = :invoiceId COLLATE NOCASE
             AND tokenAddress = :tokenAddress COLLATE NOCASE
           ORDER BY blockNumber, logIndex"""
    )
    suspend fun getByInvoiceScope(
        chainId: Long,
        vaultAddress: String,
        invoiceId: String,
        tokenAddress: String
    ): List<SettlementEvent>
}
