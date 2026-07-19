package com.openpasskey.terminal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(invoice: Invoice)

    @Query("SELECT * FROM invoices WHERE invoiceId = :invoiceId")
    suspend fun getById(invoiceId: String): Invoice?

    @Query("SELECT * FROM invoices WHERE invoiceId = :invoiceId")
    fun observeById(invoiceId: String): Flow<Invoice?>

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getByStatuses(statuses: List<InvoiceStatus>): List<Invoice>

    @Query("SELECT * FROM invoices WHERE invoiceId IN (:invoiceIds)")
    suspend fun getByIds(invoiceIds: List<String>): List<Invoice>

    @Query(
        """SELECT * FROM invoices
           WHERE status IN ('PAID', 'OVERPAID', 'PARTIALLY_SETTLED') AND settlementId IS NULL
           ORDER BY createdAt ASC"""
    )
    fun observeReadyForSettlement(): Flow<List<Invoice>>

    @Query(
        """UPDATE invoices
           SET receivedAmount = :receivedAmount,
               status = :status,
               firstDetectedBlock = :firstDetectedBlock,
               lastObservedBlock = :lastObservedBlock,
               confirmedAtBlock = :confirmedAtBlock
           WHERE invoiceId = :invoiceId"""
    )
    suspend fun updateObservation(
        invoiceId: String,
        receivedAmount: String,
        status: InvoiceStatus,
        firstDetectedBlock: Long?,
        lastObservedBlock: Long?,
        confirmedAtBlock: Long?
    )

    @Query("UPDATE invoices SET status = :status WHERE invoiceId = :invoiceId")
    suspend fun updateStatus(invoiceId: String, status: InvoiceStatus)

    @Query(
        """UPDATE invoices SET settlementId = :settlementId
           WHERE invoiceId IN (:invoiceIds)
             AND status IN ('PAID', 'OVERPAID', 'PARTIALLY_SETTLED')
             AND settlementId IS NULL"""
    )
    suspend fun attachSettlement(invoiceIds: List<String>, settlementId: String): Int

    @Query("UPDATE invoices SET settlementId = NULL WHERE settlementId = :settlementId AND invoiceId IN (:invoiceIds)")
    suspend fun releaseSettlement(invoiceIds: List<String>, settlementId: String)

    @Query(
        """UPDATE invoices
           SET status = 'SETTLED',
               settlementId = :settlementId,
               settledTxHash = :txHash,
               settledAtBlock = :settledAtBlock
           WHERE invoiceId IN (:invoiceIds)"""
    )
    suspend fun markSettled(
        invoiceIds: List<String>,
        settlementId: String,
        txHash: String,
        settledAtBlock: Long
    )

    @Query(
        """UPDATE invoices
           SET status = 'PARTIALLY_SETTLED',
               settlementId = NULL,
               settledTxHash = :txHash,
               settledAtBlock = :settledAtBlock
           WHERE invoiceId IN (:invoiceIds)"""
    )
    suspend fun markPartiallySettled(
        invoiceIds: List<String>,
        txHash: String,
        settledAtBlock: Long
    )

    @Query(
        """UPDATE invoices
           SET status = 'SETTLEMENT_REVIEW_REQUIRED',
               settlementId = :settlementId,
               settledTxHash = :txHash,
               settledAtBlock = :settledAtBlock
           WHERE invoiceId IN (:invoiceIds)"""
    )
    suspend fun markSettlementReviewRequired(
        invoiceIds: List<String>,
        settlementId: String,
        txHash: String,
        settledAtBlock: Long
    )
}
