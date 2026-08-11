package com.openpasskey.terminal.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class InvoiceDatabaseMigrationTest {
    @Test
    fun v8AddsStableMerchantReceiptIdentity() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        InvoiceDatabase.MIGRATION_7_8.migrate(database)

        assertTrue(statements.any {
            "receiptMerchantName TEXT NOT NULL DEFAULT 'OPK Terminal'" in it
        })
        assertTrue(statements.any {
            "receiptMerchantAbn TEXT NOT NULL DEFAULT ''" in it
        })
    }

    @Test
    fun v7PersistsIncomingPaymentAndStableReceiptStateWithoutEnablingLegacyAutoPrint() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        InvoiceDatabase.MIGRATION_6_7.migrate(database)

        assertTrue(statements.any { "publishedAtBlock INTEGER" in it })
        assertTrue(statements.any { "paymentTxHash TEXT" in it })
        assertTrue(statements.any { "paymentBlockHash TEXT" in it })
        assertTrue(statements.any { "paidAt INTEGER" in it })
        assertTrue(statements.any { "receiptNumber INTEGER NOT NULL DEFAULT 0" in it })
        assertTrue(statements.any {
            "receiptAutoPrintEligible INTEGER NOT NULL DEFAULT 0" in it
        })
        assertTrue(statements.any { "receiptPrintedAt INTEGER" in it })
        assertTrue(statements.any { "receiptAutoPrintEligible = 0" in it })
    }

    @Test
    fun v6PersistsTheInvoiceOperatorSnapshot() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        InvoiceDatabase.MIGRATION_5_6.migrate(database)

        assertTrue(statements.any {
            "operatorAddress TEXT NOT NULL DEFAULT ''" in it
        })
    }

    @Test
    fun v5PersistsAmbiguityAndDurableRecoveryFairness() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        InvoiceDatabase.MIGRATION_4_5.migrate(database)

        assertTrue(statements.any { "settlementAmbiguous INTEGER NOT NULL DEFAULT 0" in it })
        assertTrue(statements.any { "firstDetectedBlockHash TEXT" in it })
        assertTrue(statements.any { "lateFirstDetectedBlockHash TEXT" in it })
        assertTrue(statements.any { "openRecoveryLastAttemptAt INTEGER" in it })
        assertTrue(statements.any { "lateRecoveryLastAttemptAt INTEGER" in it })
        assertTrue(statements.any {
            "settlementAmbiguous = 1" in it && "SETTLEMENT_REVIEW_REQUIRED" in it
        })
    }

    private fun recordingDatabase(statements: MutableList<String>) = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "execSQL" -> {
                statements += requireNotNull(arguments)[0] as String
                Unit
            }
            else -> error("Unexpected migration database call: ${method.name}")
        }
    } as SupportSQLiteDatabase
}
