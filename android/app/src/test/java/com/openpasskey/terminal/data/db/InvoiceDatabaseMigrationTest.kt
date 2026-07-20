package com.openpasskey.terminal.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class InvoiceDatabaseMigrationTest {
    @Test
    fun v5PersistsAmbiguityAndDurableRecoveryFairness() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
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
}
