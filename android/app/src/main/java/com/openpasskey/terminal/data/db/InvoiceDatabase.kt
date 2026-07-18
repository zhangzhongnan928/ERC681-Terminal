package com.openpasskey.terminal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openpasskey.terminal.data.model.Invoice

@Database(entities = [Invoice::class], version = 2, exportSchema = false)
abstract class InvoiceDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao

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
                database.execSQL("UPDATE invoices SET status = 'PAID' WHERE status IN ('FUNDED', 'SETTLED')")
            }
        }

        fun getInstance(context: Context): InvoiceDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                InvoiceDatabase::class.java,
                "opk_terminal_invoices.db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}
