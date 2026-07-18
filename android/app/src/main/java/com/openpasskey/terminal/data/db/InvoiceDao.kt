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
}
