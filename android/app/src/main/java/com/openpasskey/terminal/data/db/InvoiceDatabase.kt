package com.openpasskey.terminal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.SettlementEvent
import com.openpasskey.terminal.data.model.SettlementTransaction

@Database(
    entities = [Invoice::class, SettlementTransaction::class, SettlementEvent::class],
    version = 6,
    exportSchema = false
)
abstract class InvoiceDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao
    abstract fun settlementDao(): SettlementDao
    abstract fun settlementEventDao(): SettlementEventDao

    companion object {
        @Volatile private var INSTANCE: InvoiceDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE invoices ADD COLUMN tokenDecimals INTEGER NOT NULL DEFAULT 18")
                database.execSQL("ALTER TABLE invoices ADD COLUMN receivedAmount TEXT NOT NULL DEFAULT '0'")
                database.execSQL("ALTER TABLE invoices ADD COLUMN chainId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE invoices ADD COLUMN networkName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN rpcUrl TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN factoryAddress TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN receiverImplementationAddress TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN vaultAddress TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN confirmationBlocks INTEGER NOT NULL DEFAULT 2")
                database.execSQL("ALTER TABLE invoices ADD COLUMN erc681Uri TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE invoices ADD COLUMN firstDetectedBlock INTEGER")
                database.execSQL("ALTER TABLE invoices ADD COLUMN lastObservedBlock INTEGER")
                database.execSQL("ALTER TABLE invoices ADD COLUMN confirmedAtBlock INTEGER")
                database.execSQL("UPDATE invoices SET status = 'WAITING' WHERE status = 'PRESENTED'")
                database.execSQL("UPDATE invoices SET status = 'PAID' WHERE status = 'FUNDED'")
                // Legacy SETTLED had no canonical receipt/event ledger. Never upgrade it to proof.
                database.execSQL(
                    "UPDATE invoices SET status = 'SETTLEMENT_REVIEW_REQUIRED' WHERE status = 'SETTLED'"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE invoices ADD COLUMN settlementId TEXT")
                database.execSQL("ALTER TABLE invoices ADD COLUMN settledAtBlock INTEGER")
                // v1 used SETTLED without durable receipt evidence. Do not trust the old tx hash.
                database.execSQL(
                    "UPDATE invoices SET status = 'SETTLEMENT_REVIEW_REQUIRED' " +
                        "WHERE settledTxHash IS NOT NULL"
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS settlement_transactions (
                        id TEXT NOT NULL PRIMARY KEY,
                        chainId INTEGER NOT NULL,
                        networkName TEXT NOT NULL,
                        rpcUrl TEXT NOT NULL,
                        vaultAddress TEXT NOT NULL,
                        tokenAddress TEXT NOT NULL,
                        tokenSymbol TEXT NOT NULL,
                        operatorAddress TEXT NOT NULL,
                        invoiceIdsJson TEXT NOT NULL,
                        expectedAmountsJson TEXT NOT NULL,
                        receiverAddressesJson TEXT NOT NULL,
                        requiredConfirmations INTEGER NOT NULL,
                        callData TEXT NOT NULL,
                        nonce TEXT NOT NULL,
                        gasLimit TEXT NOT NULL,
                        feeMode TEXT NOT NULL,
                        gasPrice TEXT,
                        maxPriorityFeePerGas TEXT,
                        maxFeePerGas TEXT,
                        maxGasCostWei TEXT NOT NULL,
                        feeReserveWei TEXT NOT NULL,
                        requiredBalanceWei TEXT NOT NULL,
                        txHash TEXT NOT NULL,
                        signedRawTransaction TEXT,
                        status TEXT NOT NULL,
                        verifiedInvoiceIdsJson TEXT NOT NULL,
                        verifiedEventsJson TEXT NOT NULL,
                        receiptBlock INTEGER,
                        error TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )""".trimIndent()
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS settlement_events (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        settlementId TEXT NOT NULL,
                        invoiceId TEXT NOT NULL,
                        chainId INTEGER NOT NULL,
                        transactionHash TEXT NOT NULL,
                        blockHash TEXT NOT NULL,
                        blockNumber INTEGER NOT NULL,
                        logIndex INTEGER NOT NULL,
                        receiverAddress TEXT NOT NULL,
                        vaultAddress TEXT NOT NULL,
                        tokenAddress TEXT NOT NULL,
                        sweptAmount TEXT NOT NULL,
                        expectedAmount TEXT NOT NULL,
                        feeAmount TEXT NOT NULL,
                        recordedAt INTEGER NOT NULL
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_events_settlementId " +
                        "ON settlement_events (settlementId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_events_invoiceId " +
                        "ON settlement_events (invoiceId)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_settlement_events_chainId_transactionHash_logIndex " +
                        "ON settlement_events (chainId, transactionHash, logIndex)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_events_chainId_vaultAddress_invoiceId_tokenAddress " +
                        "ON settlement_events (chainId, vaultAddress, invoiceId, tokenAddress)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN pendingLateAmount TEXT NOT NULL DEFAULT '0'"
                )
                database.execSQL("ALTER TABLE invoices ADD COLUMN lateFirstDetectedBlock INTEGER")
                database.execSQL("ALTER TABLE invoices ADD COLUMN lateLastObservedBlock INTEGER")
                database.execSQL("ALTER TABLE invoices ADD COLUMN lateConfirmedAtBlock INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN settlementAmbiguous INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN firstDetectedBlockHash TEXT"
                )
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN lateFirstDetectedBlockHash TEXT"
                )
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN openRecoveryLastAttemptAt INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN lateRecoveryLastAttemptAt INTEGER"
                )
                // Every pre-v5 review row lacks conclusive canonical receipt evidence by
                // definition. Persist that fact independently of its temporary recovery status.
                database.execSQL(
                    "UPDATE invoices SET settlementAmbiguous = 1 " +
                        "WHERE status = 'SETTLEMENT_REVIEW_REQUIRED'"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Historical rows cannot recover the preimage terminal EOA from an invoice ID.
                // New invoices persist it explicitly; the empty default keeps old history readable.
                database.execSQL(
                    "ALTER TABLE invoices ADD COLUMN operatorAddress TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): InvoiceDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                InvoiceDatabase::class.java,
                "opk_terminal_invoices.db"
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            ).build().also { INSTANCE = it }
        }
    }
}
