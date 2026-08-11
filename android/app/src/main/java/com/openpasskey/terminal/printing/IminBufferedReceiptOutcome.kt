package com.openpasskey.terminal.printing

import kotlinx.coroutines.CompletableDeferred

/**
 * Aggregates all callbacks belonging to one iMin transaction-buffer job.
 *
 * Content-command failures are sticky. A later successful transaction callback proves only that
 * the submitted buffer finished; it cannot turn a receipt with rejected text, columns, or QR data
 * into a successful receipt. The iMin transaction callback is terminal for the whole buffer, so
 * any content callbacks emitted by a service precede its final transaction result.
 */
internal class IminBufferedReceiptOutcome {
    private val lock = Any()
    private val terminalSignal = CompletableDeferred<Unit>()

    private var commandFailure: ReceiptPrintResult.Failure? = null
    private var commitStarted = false
    private var commitResult: ReceiptPrintResult? = null

    fun commandRunResult(stage: String, isSuccess: Boolean) {
        if (!isSuccess) {
            recordCommandFailure(
                ReceiptPrintResult.Failure("$stage command was rejected."),
            )
        }
    }

    fun commandException(stage: String, code: Int, message: String?) {
        recordCommandFailure(
            ReceiptPrintResult.Failure(
                message = message.nonBlankOr("$stage command raised an exception."),
                code = code,
            ),
        )
    }

    fun commandPrintResult(stage: String, code: Int, message: String?) {
        // Content commands use the regular iMin callback contract: 1 is success and 0 is failure.
        // Buffered transaction completion has a separate contract decoded below: 2 is progress
        // and 0 is final success.
        if (code != COMMAND_CALLBACK_SUCCESS) {
            recordCommandFailure(
                ReceiptPrintResult.Failure(
                    message = message.nonBlankOr("$stage command failed."),
                    code = code,
                ),
            )
        }
    }

    /**
     * Atomically closes the queuing phase. A returned failure means commit must not be called and
     * the caller must discard the transaction buffer.
     */
    fun beginCommit(): ReceiptPrintResult.Failure? = synchronized(lock) {
        commandFailure ?: run {
            commitStarted = true
            null
        }
    }

    fun commitRunResult(isSuccess: Boolean) {
        if (!isSuccess) {
            recordCommitResult(
                ReceiptPrintResult.Failure("Printer rejected the receipt transaction."),
            )
        }
    }

    fun commitException(code: Int, message: String?) {
        recordCommitResult(
            ReceiptPrintResult.Failure(
                message = message.nonBlankOr("Printer transaction raised an exception."),
                code = code,
            ),
        )
    }

    fun commitPrintResult(code: Int, message: String?) {
        decodeTransactionPrintResult(code, message)?.let(::recordCommitResult)
    }

    suspend fun awaitResult(): ReceiptPrintResult {
        terminalSignal.await()
        return synchronized(lock) {
            commandFailure
                ?: commitResult
                ?: ReceiptPrintResult.Failure("Printer transaction ended without a result.")
        }
    }

    private fun recordCommandFailure(failure: ReceiptPrintResult.Failure) {
        val signalTerminal = synchronized(lock) {
            if (commandFailure == null) commandFailure = failure
            commitStarted
        }
        if (signalTerminal) terminalSignal.complete(Unit)
    }

    private fun recordCommitResult(result: ReceiptPrintResult) {
        synchronized(lock) {
            if (commitResult == null) commitResult = result
        }
        terminalSignal.complete(Unit)
    }

    private companion object {
        const val COMMAND_CALLBACK_SUCCESS = 1
    }
}

private fun String?.nonBlankOr(fallback: String): String =
    this?.takeIf(String::isNotBlank) ?: fallback
