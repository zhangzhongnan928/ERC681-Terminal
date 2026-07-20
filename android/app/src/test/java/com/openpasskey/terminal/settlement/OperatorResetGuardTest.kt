package com.openpasskey.terminal.settlement

import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementDao
import com.openpasskey.terminal.data.model.SettlementTransactionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class OperatorResetGuardTest {
    @Test
    fun activeOrUnknownBroadcastSettlementStatusBlocksWalletReset() {
        assertEquals(
            setOf(
                SettlementTransactionStatus.SIGNED,
                SettlementTransactionStatus.SUBMITTED,
                SettlementTransactionStatus.CONFIRMING,
                SettlementTransactionStatus.BROADCAST_FAILED,
            ),
            DaoOperatorResetGuard.BLOCKING_SETTLEMENT_STATUSES.toSet(),
        )
        assertThrows(IllegalStateException::class.java) { requireOperatorResetAllowed(true) }
    }

    @Test
    fun resolvedReceiptStatusesDoNotBlockAfterInvoiceProofBecomesConclusive() {
        val terminalStatuses = setOf(
            SettlementTransactionStatus.VERIFIED,
            SettlementTransactionStatus.PARTIALLY_VERIFIED,
            SettlementTransactionStatus.VERIFICATION_FAILED,
            SettlementTransactionStatus.REVERTED,
        )
        assertEquals(
            emptySet<SettlementTransactionStatus>(),
            terminalStatuses.intersect(DaoOperatorResetGuard.BLOCKING_SETTLEMENT_STATUSES.toSet()),
        )
        requireOperatorResetAllowed(false)
    }

    @Test
    fun anyIssuedInvoicePermanentlyBlocksDestructiveWalletReset() = runBlocking {
        var issuedInvoices = 1
        var transactionCalls = 0
        var insideTransaction = false
        val invoiceDao = daoProxy<InvoiceDao> { method, _ ->
            assertTrue("Invoice snapshot must be read inside the Room transaction", insideTransaction)
            if (method == "countIssuedInvoices") issuedInvoices
            else error("Unexpected InvoiceDao call: $method")
        }
        val settlementDao = daoProxy<SettlementDao> { method, _ ->
            assertTrue("Settlement snapshot must be read inside the Room transaction", insideTransaction)
            if (method == "countForOperatorWithStatuses") 0
            else error("Unexpected SettlementDao call: $method")
        }
        val guard = DaoOperatorResetGuard(
            settlementDao,
            invoiceDao,
            ResetSnapshotTransaction { block ->
                assertFalse("Reset snapshot transaction cannot be nested", insideTransaction)
                transactionCalls += 1
                insideTransaction = true
                try {
                    block()
                } finally {
                    insideTransaction = false
                }
            },
        )

        assertTrue(guard.hasBlockingState(OPERATOR))
        assertEquals(1, transactionCalls)

        // Only a pristine terminal has no receiver that can be funded after the final check.
        issuedInvoices = 0
        assertFalse(guard.hasBlockingState(OPERATOR))
        assertEquals(2, transactionCalls)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> daoProxy(
        crossinline invoke: (method: String, arguments: Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, arguments -> invoke(method.name, arguments) } as T

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
    }
}
