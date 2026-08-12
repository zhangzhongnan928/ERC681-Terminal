package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.payment.Web3jPaymentTransactionResolver
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRpcBudgetTest {
    @Test
    fun `evidence resolution worst envelope stays strictly below coordinator lease`() {
        // The resolver checks its end-to-end budget before every network operation, and each
        // HTTP call is hard-bounded by the whole-call watchdog deadline, which disconnects the
        // socket when it expires. The worst complete pass is therefore the budget plus one
        // deadline-length call, and it must sit strictly below the lease so millisecond-scale
        // watchdog teardown, request writes, response parsing, and coroutine resumption all have
        // headroom.
        val worstCaseMillis = Web3jPaymentTransactionResolver.EVIDENCE_TOTAL_BUDGET_MILLIS +
            Web3jPaymentTransactionResolver.EVIDENCE_RPC_CALL_TIMEOUT_MILLIS

        assertTrue(
            worstCaseMillis < RpcWorkCoordinator.DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
        )
        // A healthy single-read call (connect plus one full read) must fit the call deadline so
        // the watchdog only fires on genuinely stalled or dribbling responses.
        assertTrue(
            Web3jPaymentTransactionResolver.EVIDENCE_RPC_CONNECT_TIMEOUT_MILLIS +
                Web3jPaymentTransactionResolver.EVIDENCE_RPC_READ_TIMEOUT_MILLIS <=
                Web3jPaymentTransactionResolver.EVIDENCE_RPC_CALL_TIMEOUT_MILLIS,
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
