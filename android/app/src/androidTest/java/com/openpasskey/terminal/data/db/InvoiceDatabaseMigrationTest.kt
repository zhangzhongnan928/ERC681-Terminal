package com.openpasskey.terminal.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production migration registry against a real SQLite v5 database and exported schemas. */
@RunWith(AndroidJUnit4::class)
class InvoiceDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        InvoiceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v5ToV6PreservesExistingInvoiceAndAddsEmptyOperatorSnapshot() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                """
                INSERT INTO invoices (
                    invoiceId,
                    receiver,
                    token,
                    tokenSymbol,
                    expectedAmount,
                    status,
                    createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    INVOICE_ID,
                    RECEIVER,
                    TOKEN,
                    "AUD",
                    "1250000000000000000",
                    "PAID",
                    1_721_000_000L,
                ),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            6,
            true,
            *InvoiceDatabase.ALL_MIGRATIONS,
        )

        migrated.query(
            "SELECT receiver, token, expectedAmount, status, createdAt, operatorAddress " +
                "FROM invoices WHERE invoiceId = ?",
            arrayOf(INVOICE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(RECEIVER, cursor.getString(0))
            assertEquals(TOKEN, cursor.getString(1))
            assertEquals("1250000000000000000", cursor.getString(2))
            assertEquals("PAID", cursor.getString(3))
            assertEquals(1_721_000_000L, cursor.getLong(4))
            assertEquals("", cursor.getString(5))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DATABASE = "invoice-v5-v6-migration-test"
        val INVOICE_ID = "0x${"11".repeat(32)}"
        const val RECEIVER = "0x2222222222222222222222222222222222222222"
        const val TOKEN = "0x3333333333333333333333333333333333333333"
    }
}
