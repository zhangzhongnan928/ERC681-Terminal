package com.openpasskey.terminal.printing

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.hasSuccessfulPrimaryPayment
import com.openpasskey.terminal.data.model.receiptPrintFingerprint
import com.openpasskey.terminal.data.repository.AutomaticPaymentEvidenceResult
import com.openpasskey.terminal.data.repository.InvoiceRepository
import java.math.BigDecimal
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ReceiptRequestResult {
    data class Printed(val wasReprint: Boolean) : ReceiptRequestResult
    data object AlreadyPrinted : ReceiptRequestResult
    data class AutomaticSuppressed(val message: String) : ReceiptRequestResult
    data class Unavailable(
        val message: String,
        /** True only for a cooperative/background or printer-availability retry. */
        val retryAutomatically: Boolean = false,
    ) : ReceiptRequestResult
    data class Failed(
        val message: String,
        /** True only when the printer positively reported that the job did not complete. */
        val retryAutomatically: Boolean = false,
    ) : ReceiptRequestResult
}

/** One serialized path shared by automatic printing and explicit History reprints. */
class ReceiptCoordinator(
    private val repository: InvoiceRepository,
    private val printer: ReceiptPrinter,
    private val automaticClaimStore: AutomaticReceiptClaimStore,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val mutex = Mutex()

    suspend fun print(invoiceId: String, automatic: Boolean): ReceiptRequestResult = mutex.withLock {
        val stored = repository.getInvoice(invoiceId)
            ?: return@withLock ReceiptRequestResult.Unavailable("Payment was not found.")
        if (automatic && stored.receiptPrintedAt != null) {
            return@withLock ReceiptRequestResult.AlreadyPrinted
        }

        val invoice = try {
            if (automatic) {
                when (val result = repository.ensurePaymentEvidenceAutomatically(invoiceId)) {
                    is AutomaticPaymentEvidenceResult.Available -> result.invoice
                    AutomaticPaymentEvidenceResult.Deferred -> return@withLock ReceiptRequestResult.Unavailable(
                            message = "Payment transaction details will be retried in the background.",
                            retryAutomatically = true,
                        )
                    is AutomaticPaymentEvidenceResult.Unsupported -> return@withLock ReceiptRequestResult.Unavailable(
                            "This payment has no supported incoming transaction receipt evidence.",
                        )
                }
            } else {
                repository.ensurePaymentEvidence(invoiceId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withLock ReceiptRequestResult.Unavailable(
                error.message ?: "Payment transaction details are not available yet.",
            )
        } ?: return@withLock ReceiptRequestResult.Unavailable("Payment was not found.")

        if (automatic && invoice.receiptPrintedAt != null) {
            return@withLock ReceiptRequestResult.AlreadyPrinted
        }

        val document = try {
            invoice.toReceiptDocument()
        } catch (error: IllegalArgumentException) {
            return@withLock ReceiptRequestResult.Unavailable(
                error.message ?: "Receipt details are incomplete.",
            )
        }

        val wasReprint = invoice.receiptPrintedAt != null
        val fingerprint = invoice.receiptPrintFingerprint()
        if (automatic) {
            val claim = try {
                automaticClaimStore.claim(fingerprint)
            } catch (_: Exception) {
                AutomaticReceiptClaimResult.PERSISTENCE_FAILED
            }
            when (claim) {
                AutomaticReceiptClaimResult.CLAIMED -> Unit
                AutomaticReceiptClaimResult.ALREADY_CLAIMED -> return@withLock ReceiptRequestResult.AutomaticSuppressed(
                        "Automatic printing is paused because this exact receipt may already have printed. " +
                            "Check the paper output and use History to reprint manually if needed.",
                    )
                AutomaticReceiptClaimResult.PERSISTENCE_FAILED -> return@withLock ReceiptRequestResult.Unavailable(
                        message = "Automatic receipt safety state could not be saved. Nothing was printed.",
                        retryAutomatically = true,
                    )
            }
        }
        when (val result = printer.print(document)) {
            ReceiptPrintResult.Success -> {
                if (!wasReprint) {
                    val marked = try {
                        repository.markReceiptPrinted(
                            invoiceId = invoiceId,
                            expectedPaymentTxHash = requireNotNull(invoice.paymentTxHash),
                            expectedFundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                            expectedFundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                            printedAt = clock.instant().epochSecond,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    if (!marked) {
                        return@withLock ReceiptRequestResult.Failed(
                            "Receipt printed, but its completion record could not be saved.",
                        )
                    }
                }
                // A durable invoice completion or an explicit manual success makes the uncertainty
                // marker obsolete. Failure to remove it is safe because it can only suppress auto.
                runCatching { automaticClaimStore.release(fingerprint) }
                ReceiptRequestResult.Printed(wasReprint)
            }
            ReceiptPrintResult.Closed -> retryAfterDefiniteNoPrint(
                automatic = automatic,
                fingerprint = fingerprint,
                retryable = ReceiptRequestResult.Unavailable(
                    message = "Printer service is closed.",
                    retryAutomatically = true,
                ),
            )
            is ReceiptPrintResult.Unavailable -> retryAfterDefiniteNoPrint(
                automatic = automatic,
                fingerprint = fingerprint,
                retryable = ReceiptRequestResult.Unavailable(
                    message = result.message,
                    retryAutomatically = true,
                ),
            )
            // The service accepted the buffered job. A failure callback cannot prove that no
            // paper was produced, so the durable claim remains until a manual reprint succeeds.
            is ReceiptPrintResult.Failure -> ReceiptRequestResult.Failed(result.message)
            is ReceiptPrintResult.TimedOut -> when (result.stage) {
                ReceiptPrintResult.TimedOut.Stage.SERVICE_CONNECTION -> retryAfterDefiniteNoPrint(
                    automatic = automatic,
                    fingerprint = fingerprint,
                    retryable = ReceiptRequestResult.Unavailable(
                        message = "Timed out connecting to the built-in printer.",
                        retryAutomatically = true,
                    ),
                )
                ReceiptPrintResult.TimedOut.Stage.PRINT_COMPLETION ->
                    ReceiptRequestResult.Failed(
                        "The printer did not confirm receipt completion. " +
                            "Check the paper output before reprinting.",
                    )
            }
        }
    }

    private fun retryAfterDefiniteNoPrint(
        automatic: Boolean,
        fingerprint: String,
        retryable: ReceiptRequestResult,
    ): ReceiptRequestResult {
        if (!automatic) return retryable
        val released = runCatching { automaticClaimStore.release(fingerprint) }.getOrDefault(false)
        return if (released) {
            retryable
        } else {
            ReceiptRequestResult.Failed(
                "The printer did not accept the receipt, but its automatic safety marker " +
                    "could not be cleared. Reprint manually from History.",
            )
        }
    }

    override fun close() = printer.close()
}

internal fun Invoice.toReceiptDocument(): ReceiptDocument {
    require(receiptAutoPrintEligible && receiptNumber > 0) {
        "This historical payment has no verifiable receipt record."
    }
    require(hasSuccessfulPrimaryPayment()) {
        "This payment is not currently confirmed."
    }
    val hash = requireNotNull(paymentTxHash) {
        "Incoming payment transaction details are not available yet."
    }
    val paymentTime = requireNotNull(paidAt) {
        "Payment time is not available yet."
    }
    require(operatorAddress.isNotBlank()) { "Terminal address is unavailable." }
    require(receiptMerchantName.isNotBlank()) { "Receipt merchant name is unavailable." }
    return ReceiptDocument(
        merchantName = receiptMerchantName,
        merchantAbn = receiptMerchantAbn.takeIf(String::isNotBlank),
        displayAmount = formatReceiptAmount(expectedAmount, tokenDecimals),
        tokenSymbol = tokenSymbol,
        networkName = "Base",
        terminalAddress = operatorAddress,
        paymentTxHash = hash,
        receiptNumber = receiptNumber,
        paidAtEpochSeconds = paymentTime,
        explorerUrl = BaseScanExplorer.transactionUrl(chainId, hash),
    )
}

internal fun formatReceiptAmount(raw: String, decimals: Int): String =
    BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()
