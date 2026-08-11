package com.openpasskey.terminal.printing

/** Prints one immutable receipt snapshot. Implementations must serialize physical print jobs. */
interface ReceiptPrinter : AutoCloseable {
    suspend fun print(document: ReceiptDocument): ReceiptPrintResult

    override fun close()
}

sealed interface ReceiptPrintResult {
    data object Success : ReceiptPrintResult

    data object Closed : ReceiptPrintResult

    /** The printer cannot currently accept a job. [status] is the raw vendor status, if known. */
    data class Unavailable(
        val status: Int?,
        val message: String,
    ) : ReceiptPrintResult

    /** The service accepted the job, but setup, queuing, or physical printing failed. */
    data class Failure(
        val message: String,
        val code: Int? = null,
    ) : ReceiptPrintResult

    data class TimedOut(
        val stage: Stage,
    ) : ReceiptPrintResult {
        enum class Stage {
            SERVICE_CONNECTION,
            PRINT_COMPLETION,
        }
    }
}
