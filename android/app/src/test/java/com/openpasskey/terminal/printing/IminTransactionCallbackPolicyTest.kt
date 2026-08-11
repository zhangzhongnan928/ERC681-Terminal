package com.openpasskey.terminal.printing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IminTransactionCallbackPolicyTest {
    @Test
    fun startCallbackIsProgressAndDoesNotCompleteTheReceipt() {
        assertNull(decodeTransactionPrintResult(2, "Start transaction printing"))
    }

    @Test
    fun terminalSuccessIsTheOnlySuccessfulCompletion() {
        assertSame(ReceiptPrintResult.Success, decodeTransactionPrintResult(0, "Success"))
    }

    @Test
    fun terminalFailureRetainsTheSdkCodeAndMessage() {
        assertEquals(
            ReceiptPrintResult.Failure("Transaction print failed", code = 1),
            decodeTransactionPrintResult(1, "Transaction print failed"),
        )
    }

    @Test
    fun textCommandRejectionBlocksCommitAndReturnsFailure() {
        val outcome = IminBufferedReceiptOutcome()

        outcome.commandRunResult("receipt text", isSuccess = false)

        assertEquals(
            ReceiptPrintResult.Failure("receipt text command was rejected."),
            outcome.beginCommit(),
        )
    }

    @Test
    fun columnExceptionWinsEvenIfCommitLaterReportsSuccess() = runBlocking {
        val outcome = IminBufferedReceiptOutcome()
        assertNull(outcome.beginCommit())

        outcome.commandException("receipt columns", code = 91, message = "Bad columns")
        outcome.commitPrintResult(0, "Success")

        assertEquals(
            ReceiptPrintResult.Failure("Bad columns", code = 91),
            outcome.awaitResult(),
        )
    }

    @Test
    fun qrPrintFailureWinsEvenIfCommitLaterReportsSuccess() = runBlocking {
        val outcome = IminBufferedReceiptOutcome()
        assertNull(outcome.beginCommit())

        outcome.commandPrintResult("explorer QR", code = 0, message = "QR rejected")
        outcome.commitPrintResult(0, "Success")

        assertEquals(
            ReceiptPrintResult.Failure("QR rejected", code = 0),
            outcome.awaitResult(),
        )
    }

    @Test
    fun commandSuccessAndCommitProgressDoNotOverrideCommitSuccess() = runBlocking {
        val outcome = IminBufferedReceiptOutcome()

        outcome.commandPrintResult("receipt text", code = 1, message = "Printed")
        assertNull(outcome.beginCommit())
        outcome.commitPrintResult(2, "Start transaction printing")
        outcome.commitPrintResult(0, "Success")

        assertSame(ReceiptPrintResult.Success, outcome.awaitResult())
    }

    @Test
    fun commitRunFailureRemainsStickyAgainstLaterCodeZero() = runBlocking {
        val outcome = IminBufferedReceiptOutcome()
        assertNull(outcome.beginCommit())

        outcome.commitRunResult(isSuccess = false)
        outcome.commitPrintResult(0, "Success")

        assertEquals(
            ReceiptPrintResult.Failure("Printer rejected the receipt transaction."),
            outcome.awaitResult(),
        )
    }
}
