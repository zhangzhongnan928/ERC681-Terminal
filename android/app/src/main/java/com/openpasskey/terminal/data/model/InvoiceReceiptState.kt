package com.openpasskey.terminal.data.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** States in which the invoice's primary payment has been successfully confirmed. */
internal fun InvoiceStatus.hasSuccessfulPrimaryPayment(): Boolean = when (this) {
    InvoiceStatus.PAID,
    InvoiceStatus.OVERPAID,
    InvoiceStatus.PARTIALLY_SETTLED,
    InvoiceStatus.LATE_PAYMENT_CONFIRMING,
    InvoiceStatus.LATE_PAYMENT_READY,
    InvoiceStatus.SETTLED,
    InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED -> true

    InvoiceStatus.WAITING,
    InvoiceStatus.PARTIAL,
    InvoiceStatus.CONFIRMING,
    InvoiceStatus.EXPIRED -> false
}

internal fun Invoice.hasSuccessfulPrimaryPayment(): Boolean =
    status.hasSuccessfulPrimaryPayment()

internal fun Invoice.hasSameFundingCursor(other: Invoice): Boolean =
    firstDetectedBlock == other.firstDetectedBlock &&
        firstDetectedBlockHash.equals(other.firstDetectedBlockHash, ignoreCase = true)

internal fun Invoice.withoutIncomingPaymentEvidence(): Invoice = copy(
    paymentTxHash = null,
    paymentPayerAddress = null,
    paymentBlockNumber = null,
    paymentBlockHash = null,
    paidAt = null,
)

/** Exact, fixed-size identity for the immutable receipt snapshot submitted to the printer. */
internal fun Invoice.receiptPrintFingerprint(): String {
    val fields = listOf(
        invoiceId,
        receiptNumber.toString(),
        receiptMerchantName,
        receiptMerchantAbn,
        chainId.toString(),
        operatorAddress,
        token,
        tokenSymbol,
        tokenDecimals.toString(),
        expectedAmount,
        firstDetectedBlock?.toString().orEmpty(),
        firstDetectedBlockHash.orEmpty(),
        paymentTxHash.orEmpty(),
        paymentPayerAddress.orEmpty(),
        paymentBlockNumber?.toString().orEmpty(),
        paymentBlockHash.orEmpty(),
        paidAt?.toString().orEmpty(),
    )
    val canonical = fields.joinToString(separator = "") { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        "${bytes.size}:$value"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "receipt-v1:$digest"
}
