package com.openpasskey.terminal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.model.SettlementTransactionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: SettlementTransaction)

    @Query("SELECT * FROM settlement_transactions WHERE id = :id")
    suspend fun getById(id: String): SettlementTransaction?

    @Query("SELECT * FROM settlement_transactions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SettlementTransaction>>

    @Query("SELECT * FROM settlement_transactions WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getByStatuses(statuses: List<SettlementTransactionStatus>): List<SettlementTransaction>

    @Query(
        "SELECT * FROM settlement_transactions WHERE chainId = :chainId AND operatorAddress = :operatorAddress " +
            "AND status IN (:statuses) ORDER BY createdAt ASC"
    )
    suspend fun getActiveForOperator(
        chainId: Long,
        operatorAddress: String,
        statuses: List<SettlementTransactionStatus>
    ): List<SettlementTransaction>

    @Query(
        "SELECT COUNT(*) FROM settlement_transactions WHERE operatorAddress = :operatorAddress COLLATE NOCASE " +
            "AND status IN (:statuses)"
    )
    suspend fun countForOperatorWithStatuses(
        operatorAddress: String,
        statuses: List<SettlementTransactionStatus>,
    ): Int

    @Update
    suspend fun update(transaction: SettlementTransaction)

    @Query(
        """UPDATE settlement_transactions
           SET status = :status,
               signedRawTransaction = :signedRawTransaction,
               receiptBlock = :receiptBlock,
               verifiedInvoiceIdsJson = :verifiedInvoiceIdsJson,
               verifiedEventsJson = :verifiedEventsJson,
               error = :error,
               updatedAt = :updatedAt
           WHERE id = :id"""
    )
    suspend fun updateState(
        id: String,
        status: SettlementTransactionStatus,
        signedRawTransaction: String?,
        receiptBlock: Long?,
        verifiedInvoiceIdsJson: String,
        verifiedEventsJson: String,
        error: String?,
        updatedAt: Long
    )
}
