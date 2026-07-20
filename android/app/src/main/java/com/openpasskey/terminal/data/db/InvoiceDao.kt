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

    /**
     * Durable least-recently-attempted ordering prevents an unreachable historical prefix from
     * starving later open invoices across cancellation or process restarts.
     */
    @Query(
        """SELECT * FROM invoices
           WHERE status IN ('WAITING', 'PARTIAL', 'CONFIRMING')
             AND chainId > 0
             AND rpcUrl != ''
             AND token != ''
             AND receiver != ''
             AND factoryAddress != ''
             AND receiverImplementationAddress != ''
             AND vaultAddress != ''
           ORDER BY
             CASE WHEN openRecoveryLastAttemptAt IS NULL THEN 0 ELSE 1 END ASC,
             openRecoveryLastAttemptAt ASC,
             createdAt ASC,
             invoiceId ASC
           LIMIT :limit"""
    )
    suspend fun getOpenRecoveryCandidates(limit: Int): List<Invoice>

    @Query(
        """UPDATE invoices
           SET openRecoveryLastAttemptAt = :attemptedAt
           WHERE invoiceId = :invoiceId
             AND status IN ('WAITING', 'PARTIAL', 'CONFIRMING')"""
    )
    suspend fun markOpenRecoveryAttempt(invoiceId: String, attemptedAt: Long): Int

    /**
     * A QR cannot be revoked after it is published. Durable least-recently-attempted ordering
     * rotates a bounded pass through closed, swept, and ambiguous-review receiver history.
     */
    @Query(
        """SELECT * FROM invoices
           WHERE status IN (
               'EXPIRED',
               'PAID',
               'OVERPAID',
               'PARTIALLY_SETTLED',
               'SETTLED',
               'SETTLEMENT_REVIEW_REQUIRED',
               'LATE_PAYMENT_CONFIRMING',
               'LATE_PAYMENT_READY'
           )
             AND (status NOT IN ('PAID', 'OVERPAID', 'LATE_PAYMENT_READY') OR settlementId IS NULL)
             AND chainId > 0
             AND rpcUrl != ''
             AND token != ''
             AND receiver != ''
             AND factoryAddress != ''
             AND receiverImplementationAddress != ''
             AND vaultAddress != ''
           ORDER BY
             CASE WHEN lateRecoveryLastAttemptAt IS NULL THEN 0 ELSE 1 END ASC,
             lateRecoveryLastAttemptAt ASC,
             createdAt ASC,
             invoiceId ASC
           LIMIT :limit"""
    )
    suspend fun getLateReconciliationCandidates(limit: Int): List<Invoice>

    @Query(
        """UPDATE invoices
           SET lateRecoveryLastAttemptAt = :attemptedAt
           WHERE invoiceId = :invoiceId
             AND status = :sourceStatus
             AND (status != 'LATE_PAYMENT_READY' OR settlementId IS NULL)"""
    )
    suspend fun markLateRecoveryAttempt(
        invoiceId: String,
        sourceStatus: InvoiceStatus,
        attemptedAt: Long,
    ): Int

    /** A published receiver remains payable forever, including after its current balance is swept. */
    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun countIssuedInvoices(): Int

    @Query("SELECT * FROM invoices WHERE invoiceId IN (:invoiceIds)")
    suspend fun getByIds(invoiceIds: List<String>): List<Invoice>

    @Query(
        """SELECT * FROM invoices
           WHERE status IN ('PAID', 'OVERPAID', 'LATE_PAYMENT_READY') AND settlementId IS NULL
           ORDER BY createdAt ASC"""
    )
    fun observeReadyForSettlement(): Flow<List<Invoice>>

    @Query(
        """UPDATE invoices
           SET receivedAmount = :receivedAmount,
               status = :status,
               firstDetectedBlock = :firstDetectedBlock,
               firstDetectedBlockHash = :firstDetectedBlockHash,
               lastObservedBlock = :lastObservedBlock,
               confirmedAtBlock = :confirmedAtBlock
           WHERE invoiceId = :invoiceId
             AND status IN ('WAITING', 'PARTIAL', 'CONFIRMING')"""
    )
    suspend fun updateObservation(
        invoiceId: String,
        receivedAmount: String,
        status: InvoiceStatus,
        firstDetectedBlock: Long?,
        firstDetectedBlockHash: String?,
        lastObservedBlock: Long?,
        confirmedAtBlock: Long?
    ): Int

    @Query(
        """UPDATE invoices
           SET receivedAmount = :receivedAmount,
               status = :status,
               firstDetectedBlock = :firstDetectedBlock,
               firstDetectedBlockHash = :firstDetectedBlockHash,
               lastObservedBlock = :lastObservedBlock,
               confirmedAtBlock = :confirmedAtBlock
           WHERE invoiceId = :invoiceId
             AND status = 'EXPIRED'"""
    )
    suspend fun updateClosedInvoiceObservation(
        invoiceId: String,
        receivedAmount: String,
        status: InvoiceStatus,
        firstDetectedBlock: Long?,
        firstDetectedBlockHash: String?,
        lastObservedBlock: Long,
        confirmedAtBlock: Long?,
    ): Int

    @Query(
        """UPDATE invoices
           SET receivedAmount = :receivedAmount,
               status = :status,
               firstDetectedBlock = :firstDetectedBlock,
               firstDetectedBlockHash = :firstDetectedBlockHash,
               lastObservedBlock = :lastObservedBlock,
               confirmedAtBlock = :confirmedAtBlock
           WHERE invoiceId = :invoiceId
             AND status = :sourceStatus
             AND settlementId IS NULL"""
    )
    suspend fun updateConfirmedInvoiceObservation(
        invoiceId: String,
        sourceStatus: InvoiceStatus,
        receivedAmount: String,
        status: InvoiceStatus,
        firstDetectedBlock: Long?,
        firstDetectedBlockHash: String?,
        lastObservedBlock: Long,
        confirmedAtBlock: Long?,
    ): Int

    @Query(
        """UPDATE invoices
           SET pendingLateAmount = :observedLateAmount,
               status = :status,
               lateFirstDetectedBlock = :firstDetectedBlock,
               lateFirstDetectedBlockHash = :firstDetectedBlockHash,
               lateLastObservedBlock = :lastObservedBlock,
               lateConfirmedAtBlock = :confirmedAtBlock,
               settlementAmbiguous = CASE
                   WHEN :sourceStatus = 'SETTLEMENT_REVIEW_REQUIRED' THEN 1
                   ELSE settlementAmbiguous
               END,
               settlementId = CASE WHEN :status = 'LATE_PAYMENT_READY' THEN NULL ELSE settlementId END
           WHERE invoiceId = :invoiceId
             AND status = :sourceStatus
             AND (status != 'LATE_PAYMENT_READY' OR settlementId IS NULL)"""
    )
    suspend fun updateSweptInvoiceObservation(
        invoiceId: String,
        sourceStatus: InvoiceStatus,
        observedLateAmount: String,
        status: InvoiceStatus,
        firstDetectedBlock: Long?,
        firstDetectedBlockHash: String?,
        lastObservedBlock: Long,
        confirmedAtBlock: Long?,
    ): Int

    /** Restore the latest canonical proof pointer after a ready late balance disappears. */
    @Query(
        """UPDATE invoices
           SET status = 'SETTLED',
               settlementId = :settlementId,
               settledTxHash = :txHash,
               settledAtBlock = :settledAtBlock,
               settlementAmbiguous = 0,
               pendingLateAmount = '0',
               lateFirstDetectedBlock = NULL,
               lateFirstDetectedBlockHash = NULL,
               lateLastObservedBlock = :lastObservedBlock,
               lateConfirmedAtBlock = NULL
           WHERE invoiceId = :invoiceId
             AND status = 'LATE_PAYMENT_READY'
             AND settlementId IS NULL"""
    )
    suspend fun restoreSettledFromProof(
        invoiceId: String,
        settlementId: String,
        txHash: String,
        settledAtBlock: Long,
        lastObservedBlock: Long,
    ): Int

    /** A confirmed recovery balance vanished without conclusive proof; keep the review invariant. */
    @Query(
        """UPDATE invoices
           SET status = 'SETTLEMENT_REVIEW_REQUIRED',
               pendingLateAmount = '0',
               lateFirstDetectedBlock = NULL,
               lateFirstDetectedBlockHash = NULL,
               lateLastObservedBlock = :lastObservedBlock,
               lateConfirmedAtBlock = NULL
           WHERE invoiceId = :invoiceId
             AND status = 'LATE_PAYMENT_READY'
             AND settlementId IS NULL
             AND settlementAmbiguous = 1"""
    )
    suspend fun restoreSettlementReviewRequired(
        invoiceId: String,
        lastObservedBlock: Long,
    ): Int

    @Query(
        """UPDATE invoices SET status = :status
           WHERE invoiceId = :invoiceId
             AND status IN ('WAITING', 'PARTIAL', 'CONFIRMING')"""
    )
    suspend fun updateStatus(invoiceId: String, status: InvoiceStatus): Int

    @Query(
        """UPDATE invoices SET settlementId = :settlementId
           WHERE invoiceId IN (:invoiceIds)
             AND status IN ('PAID', 'OVERPAID', 'LATE_PAYMENT_READY')
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
               settledAtBlock = :settledAtBlock,
               settlementAmbiguous = 0,
               pendingLateAmount = '0',
               lateFirstDetectedBlock = NULL,
               lateFirstDetectedBlockHash = NULL,
               lateLastObservedBlock = :settledAtBlock,
               lateConfirmedAtBlock = NULL
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
               settledAtBlock = :settledAtBlock,
               settlementAmbiguous = 0,
               pendingLateAmount = '0',
               lateFirstDetectedBlock = NULL,
               lateFirstDetectedBlockHash = NULL,
               lateLastObservedBlock = :settledAtBlock,
               lateConfirmedAtBlock = NULL
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
               settledAtBlock = :settledAtBlock,
               settlementAmbiguous = 1,
               pendingLateAmount = '0',
               lateFirstDetectedBlock = NULL,
               lateFirstDetectedBlockHash = NULL,
               lateLastObservedBlock = :settledAtBlock,
               lateConfirmedAtBlock = NULL
           WHERE invoiceId IN (:invoiceIds)"""
    )
    suspend fun markSettlementReviewRequired(
        invoiceIds: List<String>,
        settlementId: String,
        txHash: String,
        settledAtBlock: Long
    )
}
