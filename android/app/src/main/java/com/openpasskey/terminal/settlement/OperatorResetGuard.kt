package com.openpasskey.terminal.settlement

import androidx.room.withTransaction
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.db.SettlementDao
import com.openpasskey.terminal.data.model.SettlementTransactionStatus

fun interface OperatorResetGuard {
    suspend fun hasBlockingState(operatorAddress: String): Boolean
}

internal fun interface ResetSnapshotTransaction {
    suspend fun read(block: suspend () -> Boolean): Boolean
}

class DaoOperatorResetGuard internal constructor(
    private val settlementDao: SettlementDao,
    private val invoiceDao: InvoiceDao,
    private val transaction: ResetSnapshotTransaction,
) : OperatorResetGuard {
    constructor(database: InvoiceDatabase) : this(
        database.settlementDao(),
        database.invoiceDao(),
        ResetSnapshotTransaction { block -> database.withTransaction { block() } },
    )

    override suspend fun hasBlockingState(operatorAddress: String): Boolean {
        val canonicalOperator = EvmAddress.parse(operatorAddress).value
        return transaction.read {
            settlementDao.countForOperatorWithStatuses(
                canonicalOperator,
                BLOCKING_SETTLEMENT_STATUSES,
            ) > 0 || invoiceDao.countIssuedInvoices() > 0
        }
    }

    companion object {
        // Canonical receipt outcomes (including partial/failed proof classification) are resolved
        // by the invoice proof query below. Only a still-active or broadcast-unknown transaction
        // remains an unconditional operator-key dependency.
        val BLOCKING_SETTLEMENT_STATUSES = listOf(
            SettlementTransactionStatus.SIGNED,
            SettlementTransactionStatus.SUBMITTED,
            SettlementTransactionStatus.CONFIRMING,
            SettlementTransactionStatus.BROADCAST_FAILED,
        )
    }
}

internal fun requireOperatorResetAllowed(hasBlockingState: Boolean) {
    check(!hasBlockingState) {
        "Operator wallet reset is available only before the first payment QR is issued; " +
            "published receivers remain payable forever"
    }
}
