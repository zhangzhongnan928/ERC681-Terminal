package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.payment.Web3jPaymentTransactionResolver
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRpcBudgetTest {
    @Test
    fun `evidence resolution budget plus one worst socket stays within coordinator lease`() {
        // The resolver checks its end-to-end budget before every network operation, so the worst
        // complete pass is the budget plus one final worst-case socket operation.
        val worstCaseMillis = Web3jPaymentTransactionResolver.EVIDENCE_TOTAL_BUDGET_MILLIS +
            Web3jPaymentTransactionResolver.EVIDENCE_RPC_CONNECT_TIMEOUT_MILLIS +
            Web3jPaymentTransactionResolver.EVIDENCE_RPC_READ_TIMEOUT_MILLIS

        assertTrue(
            worstCaseMillis <= RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun `three-wave payment sample socket budget stays below coordinator lease`() {
        val monitorBudgetMillis = InvoiceRepository.MONITOR_RPC_WAVES *
            (InvoiceRepository.MONITOR_RPC_CONNECT_TIMEOUT_MILLIS +
                InvoiceRepository.MONITOR_RPC_READ_TIMEOUT_MILLIS)
        val recoveryBudgetMillis = InvoiceRepository.MONITOR_RPC_WAVES *
            (InvoiceRepository.RECOVERY_RPC_CONNECT_TIMEOUT_MILLIS +
                InvoiceRepository.RECOVERY_RPC_READ_TIMEOUT_MILLIS)

        assertTrue(
            monitorBudgetMillis < RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
        )
        assertTrue(
            recoveryBudgetMillis < RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun `late reconciliation worst socket budget stays below coordinator lease`() {
        val budgetMillis = LateInvoiceReconciler.MAX_RPC_READS_PER_SAMPLE *
            (LateInvoiceReconciler.RPC_CONNECT_TIMEOUT_MILLIS +
                LateInvoiceReconciler.RPC_READ_TIMEOUT_MILLIS)

        assertTrue(
            budgetMillis < RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
        )
    }
}
