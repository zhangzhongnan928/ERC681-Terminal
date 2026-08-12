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
    fun v8ToV9SanitizesRpcUrlsAndPreservesInvoiceAndSettlementEvidence() {
        val secretInvoiceRpc = "https://api.developer.coinbase.com/rpc/v1/base/invoice-secret"
        val secretSettlementRpc = "https://base-sepolia.g.alchemy.com/v2/settlement-secret"
        val secretSettlementError =
            "Unable to reach https://settlement-secret.rpc-provider.example/base"
        helper.createDatabase(TEST_DATABASE_V8_V9, 8).apply {
            execSQL(
                """
                INSERT INTO invoices (
                    invoiceId, receiver, token, tokenSymbol, expectedAmount, status, createdAt,
                    chainId, networkName, rpcUrl
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    INVOICE_ID,
                    RECEIVER,
                    TOKEN,
                    "AUDM",
                    "1250000",
                    "PAID",
                    1_721_000_000L,
                    8453L,
                    "Base Mainnet",
                    secretInvoiceRpc,
                ),
            )
            execSQL(
                """
                INSERT INTO settlement_transactions (
                    id, chainId, networkName, rpcUrl, vaultAddress, tokenAddress, tokenSymbol,
                    operatorAddress, invoiceIdsJson, expectedAmountsJson, receiverAddressesJson,
                    requiredConfirmations, callData, nonce, gasLimit, feeMode, maxGasCostWei,
                    feeReserveWei, requiredBalanceWei, txHash, status, verifiedInvoiceIdsJson,
                    verifiedEventsJson, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "settlement-1",
                    84532L,
                    "Base Sepolia",
                    secretSettlementRpc,
                    "0x4444444444444444444444444444444444444444",
                    TOKEN,
                    "AUD",
                    "0x5555555555555555555555555555555555555555",
                    "[\"$INVOICE_ID\"]",
                    "[\"1250000\"]",
                    "[\"$RECEIVER\"]",
                    2,
                    "0x1234",
                    "7",
                    "21000",
                    "EIP1559",
                    "100000",
                    "1000",
                    "101000",
                    "0x${"66".repeat(32)}",
                    "SIGNED",
                    "[]",
                    "[]",
                    1_721_000_100L,
                    1_721_000_101L,
                ),
            )
            execSQL(
                "UPDATE settlement_transactions SET error = ? WHERE id = 'settlement-1'",
                arrayOf<Any?>(secretSettlementError),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_V8_V9,
            9,
            true,
            *InvoiceDatabase.ALL_MIGRATIONS,
        )

        migrated.query(
            "SELECT rpcUrl, tokenSymbol, expectedAmount, status, createdAt " +
                "FROM invoices WHERE invoiceId = ?",
            arrayOf(INVOICE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://mainnet.base.org", cursor.getString(0))
            assertEquals("AUDM", cursor.getString(1))
            assertEquals("1250000", cursor.getString(2))
            assertEquals("PAID", cursor.getString(3))
            assertEquals(1_721_000_000L, cursor.getLong(4))
        }
        migrated.query(
            "SELECT rpcUrl, vaultAddress, txHash, status, createdAt, updatedAt, error " +
                "FROM settlement_transactions WHERE id = 'settlement-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://sepolia.base.org", cursor.getString(0))
            assertEquals("0x4444444444444444444444444444444444444444", cursor.getString(1))
            assertEquals("0x${"66".repeat(32)}", cursor.getString(2))
            assertEquals("SIGNED", cursor.getString(3))
            assertEquals(1_721_000_100L, cursor.getLong(4))
            assertEquals(1_721_000_101L, cursor.getLong(5))
            assertEquals(
                "Previous settlement diagnostic removed during secure RPC migration",
                cursor.getString(6),
            )
        }
        migrated.close()
    }

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

    @Test
    fun v7ToV8PreservesThePreviouslyPrintedMerchantHeaderDefaults() {
        helper.createDatabase(TEST_DATABASE_V7_V8, 7).apply {
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
            TEST_DATABASE_V7_V8,
            8,
            true,
            *InvoiceDatabase.ALL_MIGRATIONS,
        )

        migrated.query(
            "SELECT invoiceId, receiver, token, tokenSymbol, expectedAmount, status, createdAt, " +
                "receiptMerchantName, receiptMerchantAbn FROM invoices WHERE invoiceId = ?",
            arrayOf(INVOICE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(INVOICE_ID, cursor.getString(0))
            assertEquals(RECEIVER, cursor.getString(1))
            assertEquals(TOKEN, cursor.getString(2))
            assertEquals("AUD", cursor.getString(3))
            assertEquals("1250000000000000000", cursor.getString(4))
            assertEquals("PAID", cursor.getString(5))
            assertEquals(1_721_000_000L, cursor.getLong(6))
            assertEquals("OPK Terminal", cursor.getString(7))
            assertEquals("", cursor.getString(8))
        }
        migrated.close()
    }

    @Test
    fun v6ToV8KeepsLegacyInvoiceReceiptIneligibleAndAddsStableHeaderDefaults() {
        helper.createDatabase(TEST_DATABASE_V6_V8, 6).apply {
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
            TEST_DATABASE_V6_V8,
            8,
            true,
            *InvoiceDatabase.ALL_MIGRATIONS,
        )

        migrated.query(
            "SELECT invoiceId, receiver, token, expectedAmount, status, createdAt, " +
                "publishedAtBlock, paymentTxHash, receiptNumber, receiptAutoPrintEligible, " +
                "receiptPrintedAt, receiptMerchantName, receiptMerchantAbn " +
                "FROM invoices WHERE invoiceId = ?",
            arrayOf(INVOICE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(INVOICE_ID, cursor.getString(0))
            assertEquals(RECEIVER, cursor.getString(1))
            assertEquals(TOKEN, cursor.getString(2))
            assertEquals("1250000000000000000", cursor.getString(3))
            assertEquals("PAID", cursor.getString(4))
            assertEquals(1_721_000_000L, cursor.getLong(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertEquals(0L, cursor.getLong(8))
            assertEquals(0L, cursor.getLong(9))
            assertTrue(cursor.isNull(10))
            assertEquals("OPK Terminal", cursor.getString(11))
            assertEquals("", cursor.getString(12))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DATABASE = "invoice-v5-v6-migration-test"
        const val TEST_DATABASE_V7_V8 = "invoice-v7-v8-merchant-receipt-migration-test"
        const val TEST_DATABASE_V6_V8 = "invoice-v6-v8-receipt-migration-test"
        const val TEST_DATABASE_V8_V9 = "invoice-v8-v9-rpc-sanitization-migration-test"
        val INVOICE_ID = "0x${"11".repeat(32)}"
        const val RECEIVER = "0x2222222222222222222222222222222222222222"
        const val TOKEN = "0x3333333333333333333333333333333333333333"
    }
}
