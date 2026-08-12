package com.openpasskey.terminal.printing

import android.content.Context
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.imin.printer.INeoPrinterCallback
import com.imin.printer.InitPrinterCallback
import com.imin.printer.PrinterHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class IminReceiptAlignment(val sdkValue: Int) {
    LEFT(0),
    CENTER(1),
}

internal data class IminReceiptTextBlock(
    val text: String,
    val size: Int,
    val bold: Boolean,
    val alignment: IminReceiptAlignment,
)

internal data class IminReceiptPrintPlan(
    val textBlocks: List<IminReceiptTextBlock>,
    val explorerUrl: String,
    val finalFeed: Int,
)

/**
 * Builds a compact immutable command plan. Grouping adjacent lines with identical styling is
 * important on the Swift 2: every bitmap-text call crosses the printer-service boundary and a
 * buffered receipt cannot start moving paper until all commands have been queued and committed.
 */
internal fun ReceiptPrintContent.toIminReceiptPrintPlan(): IminReceiptPrintPlan {
    val merchant = merchantLines.joinToString("\n")
    val details = (paidLines + terminalLines + transactionLines).joinToString("\n")
    return IminReceiptPrintPlan(
        textBlocks = listOfNotNull(
            IminReceiptTextBlock(
                text = merchant,
                size = HEADER_TEXT_SIZE,
                bold = true,
                alignment = IminReceiptAlignment.CENTER,
            ),
            merchantAbn?.let {
                IminReceiptTextBlock(
                    text = "ABN $it",
                    size = BODY_TEXT_SIZE,
                    bold = false,
                    alignment = IminReceiptAlignment.CENTER,
                )
            },
            IminReceiptTextBlock(
                text = "PAYMENT RECEIPT",
                size = TITLE_TEXT_SIZE,
                bold = true,
                alignment = IminReceiptAlignment.CENTER,
            ),
            IminReceiptTextBlock(
                text = metadataLines.joinToString("\n"),
                size = BODY_TEXT_SIZE,
                bold = false,
                alignment = IminReceiptAlignment.LEFT,
            ),
            IminReceiptTextBlock(
                text = totalLines.joinToString("\n"),
                size = TOTAL_TEXT_SIZE,
                bold = true,
                alignment = IminReceiptAlignment.LEFT,
            ),
            IminReceiptTextBlock(
                text = details,
                size = DETAIL_TEXT_SIZE,
                bold = false,
                alignment = IminReceiptAlignment.LEFT,
            ),
            IminReceiptTextBlock(
                text = "Powered by OpenPasskey",
                size = FOOTER_TEXT_SIZE,
                bold = true,
                alignment = IminReceiptAlignment.CENTER,
            ),
            IminReceiptTextBlock(
                text = "Scan for transaction details",
                size = BODY_TEXT_SIZE,
                bold = false,
                alignment = IminReceiptAlignment.CENTER,
            ),
        ),
        explorerUrl = explorerUrl,
        finalFeed = FINAL_FEED,
    )
}

private const val TEXT_STYLE_NORMAL = 0
private const val TEXT_STYLE_BOLD = 1
private const val DETAIL_TEXT_SIZE = 20
private const val BODY_TEXT_SIZE = 22
private const val TITLE_TEXT_SIZE = 24
private const val HEADER_TEXT_SIZE = 28
private const val TOTAL_TEXT_SIZE = 24
private const val FOOTER_TEXT_SIZE = 22
private const val QR_SIZE = 5
private const val QR_ERROR_CORRECTION_M = 1
private const val FINAL_FEED = 48

/**
 * Receipt printer for iMin Printer SDK 2.x and the Swift 2 built-in 58 mm printer.
 *
 * The SDK owns a process-wide singleton and AIDL connection, so the application should create one
 * instance of this class with an application [Context]. A whole receipt is submitted in iMin's
 * transaction buffer. Only the transaction's physical-print callback can produce [ReceiptPrintResult.Success].
 */
class IminReceiptPrinter(
    context: Context,
    private val serviceConnectionTimeoutMillis: Long = DEFAULT_SERVICE_CONNECTION_TIMEOUT_MILLIS,
    private val printCompletionTimeoutMillis: Long = DEFAULT_PRINT_COMPLETION_TIMEOUT_MILLIS,
) : ReceiptPrinter {
    init {
        require(serviceConnectionTimeoutMillis > 0) {
            "Service connection timeout must be positive"
        }
        require(printCompletionTimeoutMillis > 0) {
            "Print completion timeout must be positive"
        }
    }

    private val appContext = context.applicationContext ?: context
    private val printer = PrinterHelper.getInstance()
    private val printMutex = Mutex()
    private val stateLock = Any()
    private val closed = AtomicBoolean()
    private val transactionBufferActive = AtomicBoolean()

    @Volatile
    private var serviceConnected = false

    @Volatile
    private var bindingRequested = false

    private var connectionWaiter: CompletableDeferred<Boolean>? = null

    private val initCallback = object : InitPrinterCallback {
        override fun onConnected() {
            var staleConnection = false
            synchronized(stateLock) {
                if (closed.get() || !bindingRequested) {
                    staleConnection = true
                } else {
                    serviceConnected = true
                    connectionWaiter?.complete(true)
                    connectionWaiter = null
                }
            }

            if (staleConnection) {
                Log.w(TAG, "Releasing stale printer service connection")
                unbindServiceSafely()
            } else {
                Log.i(TAG, "Printer service connected")
            }
        }

        override fun onDisconnected() {
            Log.w(TAG, "Printer service disconnected")
            synchronized(stateLock) {
                serviceConnected = false
                // Android keeps this ServiceConnection bound after a transient disconnect and
                // calls onConnected again when the service returns. Keep the binding and any
                // bounded waiter alive; resetTimedOutBinding performs the single matching unbind
                // if reconnection does not happen in time.
            }
        }
    }

    init {
        // Start the AIDL bind while the payment UI is being prepared. A first receipt then normally
        // reaches an already-connected service instead of spending part of checkout waiting here.
        requestServiceConnection()
    }

    override suspend fun print(document: ReceiptDocument): ReceiptPrintResult =
        printMutex.withLock {
            if (closed.get()) return@withLock ReceiptPrintResult.Closed

            ensureServiceConnected()?.let { return@withLock it }
            if (closed.get()) return@withLock ReceiptPrintResult.Closed

            val printerStatus = try {
                printer.getPrinterStatus()
            } catch (error: RuntimeException) {
                Log.e(TAG, "getPrinterStatus failed", error)
                return@withLock ReceiptPrintResult.Failure(
                    message = "Unable to read printer status.",
                )
            }
            Log.d(TAG, "getPrinterStatus status=$printerStatus")

            statusFailure(printerStatus)?.let { return@withLock it }

            val printPlan = try {
                ReceiptFormatter.printContent(document).toIminReceiptPrintPlan()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Receipt formatting failed", error)
                return@withLock ReceiptPrintResult.Failure(
                    message = "Unable to format receipt.",
                )
            }

            val outcome = IminBufferedReceiptOutcome()
            val commitCallback = commitCallback(outcome)

            try {
                printer.initPrinterParams()
                printer.enterPrinterBuffer(true)
                transactionBufferActive.set(true)

                val queueStartedAt = SystemClock.elapsedRealtime()
                queueCompactReceipt(printPlan, outcome)
                printer.printQRCodeWithFull(
                    printPlan.explorerUrl,
                    QR_SIZE,
                    QR_ERROR_CORRECTION_M,
                    IminReceiptAlignment.CENTER.sdkValue,
                    commandCallback("explorer QR", outcome),
                )
                printer.printAndFeedPaper(printPlan.finalFeed)
                Log.i(
                    TAG,
                    "Compact receipt queued in " +
                        "${SystemClock.elapsedRealtime() - queueStartedAt}ms " +
                        "(${printPlan.textBlocks.size} text blocks)",
                )
                val preCommitFailure = outcome.beginCommit()
                if (preCommitFailure != null) {
                    // A synchronous content callback rejected the buffered job. Do not submit a
                    // receipt that is already known to be incomplete; finally discards the queue.
                    preCommitFailure
                } else {
                    printer.commitPrinterBuffer(commitCallback)

                    withTimeoutOrNull(printCompletionTimeoutMillis) {
                        outcome.awaitResult()
                    } ?: ReceiptPrintResult.TimedOut(
                        ReceiptPrintResult.TimedOut.Stage.PRINT_COMPLETION,
                    )
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Receipt print submission failed", error)
                ReceiptPrintResult.Failure(
                    message = "Unable to submit receipt to the printer.",
                )
            } finally {
                // commitPrinterBuffer is the only operation allowed to print this receipt. Exiting
                // with commit=false leaves anything left behind unprinted; the next enter(true)
                // clears it before a new job, and a completed job is never re-submitted here.
                exitTransactionBufferSafely(printQueuedContent = false)
            }
        }

    /** Queues the compact receipt while keeping the whole physical job transactional. */
    private fun queueCompactReceipt(
        plan: IminReceiptPrintPlan,
        outcome: IminBufferedReceiptOutcome,
    ) {
        plan.textBlocks.forEachIndexed { index, block ->
            printer.setTextBitmapSize(block.size)
            printer.setTextBitmapStyle(if (block.bold) TEXT_STYLE_BOLD else TEXT_STYLE_NORMAL)
            printer.printTextBitmapWithAli(
                "${block.text}\n",
                block.alignment.sdkValue,
                commandCallback("receipt text ${index + 1}", outcome),
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val waiter: CompletableDeferred<Boolean>?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            waiter = connectionWaiter
            connectionWaiter = null
            shouldUnbind = bindingRequested
            bindingRequested = false
        }
        waiter?.complete(false)

        // Closing must never flush a partially queued receipt.
        exitTransactionBufferSafely(printQueuedContent = false)
        serviceConnected = false
        if (shouldUnbind) unbindServiceSafely()
    }

    private suspend fun ensureServiceConnected(): ReceiptPrintResult? {
        if (serviceConnected) return null
        if (closed.get()) return ReceiptPrintResult.Closed
        val waiter = requestServiceConnection() ?: return when {
            closed.get() -> ReceiptPrintResult.Closed
            serviceConnected -> null
            else -> ReceiptPrintResult.Unavailable(
                status = STATUS_NOT_CONNECTED,
                message = "Printer service is not connected.",
            )
        }

        val connected = withTimeoutOrNull(serviceConnectionTimeoutMillis) {
            waiter.await()
        }

        return when {
            closed.get() -> ReceiptPrintResult.Closed
            connected == true && serviceConnected -> null
            connected == null -> {
                resetTimedOutBinding(waiter)
                ReceiptPrintResult.TimedOut(
                    ReceiptPrintResult.TimedOut.Stage.SERVICE_CONNECTION,
                )
            }
            else -> ReceiptPrintResult.Unavailable(
                status = STATUS_NOT_CONNECTED,
                message = "Printer service is not connected.",
            )
        }
    }

    /** Starts or joins the one process-wide service bind without waiting for it. */
    private fun requestServiceConnection(): CompletableDeferred<Boolean>? {
        if (closed.get() || serviceConnected) return null

        var shouldBind = false
        val waiter = synchronized(stateLock) {
            if (closed.get() || serviceConnected) {
                null
            } else {
                connectionWaiter?.takeIf { it.isActive }
                    ?: CompletableDeferred<Boolean>().also { connectionWaiter = it }
            }.also {
                if (it != null && !bindingRequested) {
                    bindingRequested = true
                    shouldBind = true
                }
            }
        } ?: return null

        if (shouldBind) {
            val accepted = try {
                printer.initPrinterService(appContext, initCallback)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Printer service binding failed", error)
                false
            }

            if (!accepted) {
                var completeFailure = false
                synchronized(stateLock) {
                    // Some service implementations invoke onConnected synchronously. Never undo
                    // that successful callback merely because their boolean return is stale.
                    if (!serviceConnected && connectionWaiter === waiter) {
                        bindingRequested = false
                        connectionWaiter = null
                        completeFailure = true
                    }
                }
                if (completeFailure) waiter.complete(false)
            }
        }
        return waiter
    }

    private fun resetTimedOutBinding(waiter: CompletableDeferred<Boolean>) {
        var shouldUnbind = false
        synchronized(stateLock) {
            if (!serviceConnected && connectionWaiter === waiter) {
                connectionWaiter = null
                shouldUnbind = bindingRequested
                bindingRequested = false
            }
        }
        if (shouldUnbind) unbindServiceSafely()
    }

    private fun statusFailure(status: Int): ReceiptPrintResult.Unavailable? = when (status) {
        STATUS_NORMAL -> null
        STATUS_NOT_CONNECTED -> ReceiptPrintResult.Unavailable(
            status = status,
            message = "Printer service is not connected.",
        )
        STATUS_COVER_OPEN -> ReceiptPrintResult.Unavailable(
            status = status,
            message = "Printer cover is open.",
        )
        STATUS_OUT_OF_PAPER -> ReceiptPrintResult.Unavailable(
            status = status,
            message = "Printer is out of paper.",
        )
        else -> ReceiptPrintResult.Unavailable(
            status = status,
            message = "Printer is unavailable (status $status).",
        )
    }

    private fun commandCallback(
        stage: String,
        outcome: IminBufferedReceiptOutcome,
    ): INeoPrinterCallback =
        object : INeoPrinterCallback() {
            @Throws(RemoteException::class)
            override fun onRunResult(isSuccess: Boolean) {
                Log.d(TAG, "$stage onRunResult success=$isSuccess")
                outcome.commandRunResult(stage, isSuccess)
            }

            @Throws(RemoteException::class)
            override fun onReturnString(result: String?) {
                Log.d(TAG, "$stage onReturnString result=$result")
            }

            @Throws(RemoteException::class)
            override fun onRaiseException(code: Int, message: String?) {
                Log.e(TAG, "$stage onRaiseException code=$code message=$message")
                outcome.commandException(stage, code, message)
            }

            @Throws(RemoteException::class)
            override fun onPrintResult(code: Int, message: String?) {
                Log.i(TAG, "$stage onPrintResult code=$code message=$message")
                outcome.commandPrintResult(stage, code, message)
            }
        }

    private fun commitCallback(
        outcome: IminBufferedReceiptOutcome,
    ): INeoPrinterCallback = object : INeoPrinterCallback() {
        @Throws(RemoteException::class)
        override fun onRunResult(isSuccess: Boolean) {
            Log.d(TAG, "commit onRunResult success=$isSuccess")
            outcome.commitRunResult(isSuccess)
        }

        @Throws(RemoteException::class)
        override fun onReturnString(result: String?) {
            Log.d(TAG, "commit onReturnString result=$result")
        }

        @Throws(RemoteException::class)
        override fun onRaiseException(code: Int, message: String?) {
            Log.e(TAG, "commit onRaiseException code=$code message=$message")
            outcome.commitException(code, message)
        }

        @Throws(RemoteException::class)
        override fun onPrintResult(code: Int, message: String?) {
            Log.i(TAG, "commit onPrintResult code=$code message=$message")
            outcome.commitPrintResult(code, message)
        }
    }

    private fun exitTransactionBufferSafely(printQueuedContent: Boolean) {
        if (!transactionBufferActive.compareAndSet(true, false)) return
        try {
            printer.exitPrinterBuffer(printQueuedContent)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to exit printer transaction buffer", error)
        }
    }

    private fun unbindServiceSafely() {
        try {
            printer.deInitPrinterService(appContext)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Printer service unbind failed", error)
        }
    }

    private companion object {
        const val TAG = "IminReceiptPrinter"

        const val STATUS_NOT_CONNECTED = -1
        const val STATUS_NORMAL = 0
        const val STATUS_COVER_OPEN = 3
        const val STATUS_OUT_OF_PAPER = 7

        const val DEFAULT_SERVICE_CONNECTION_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_PRINT_COMPLETION_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * iMin SDK 2 emits code 2 when a buffered transaction starts, then emits its terminal result.
 * Only terminal code 0 proves successful completion. Returning null keeps the waiter alive for the
 * physical completion callback instead of treating the progress event as either success or failure.
 */
internal fun decodeTransactionPrintResult(
    code: Int,
    message: String?,
): ReceiptPrintResult? = when (code) {
    2 -> null
    0 -> ReceiptPrintResult.Success
    else -> ReceiptPrintResult.Failure(
        message = message ?: "Receipt printing failed.",
        code = code,
    )
}
