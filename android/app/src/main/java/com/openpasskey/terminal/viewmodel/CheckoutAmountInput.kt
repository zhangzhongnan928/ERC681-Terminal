package com.openpasskey.terminal.viewmodel

import com.openpasskey.erc681.TokenAmount
import java.math.BigDecimal
import java.math.BigInteger

internal enum class CheckoutKey(val label: String) {
    ONE("1"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    DECIMAL("."),
    ZERO("0"),
    BACKSPACE("Backspace"),
}

internal fun CheckoutKey.accessibilityLabel(): String? = when (this) {
    CheckoutKey.DECIMAL -> "Decimal point"
    CheckoutKey.BACKSPACE -> "Delete last digit"
    else -> null
}

internal const val CLEAR_AMOUNT_ACCESSIBILITY_LABEL = "Clear amount"

internal fun applyCheckoutKey(
    current: String,
    key: CheckoutKey,
    tokenDecimals: Int,
): String {
    val candidate = when (key) {
        CheckoutKey.BACKSPACE -> current.dropLast(1)
        CheckoutKey.DECIMAL -> when {
            tokenDecimals == 0 || current.contains('.') -> current
            current.isEmpty() -> "0."
            else -> "$current."
        }
        CheckoutKey.ZERO -> when (current) {
            "0" -> current
            else -> current + key.label
        }
        else -> when (current) {
            "0" -> key.label
            else -> current + key.label
        }
    }
    return candidate.takeIf { isPotentialCheckoutAmount(it, tokenDecimals) } ?: current
}

internal fun isPotentialCheckoutAmount(value: String, tokenDecimals: Int): Boolean {
    if (tokenDecimals !in 0..255) return false
    if (value.isEmpty()) return true
    if (!POTENTIAL_AMOUNT_PATTERN.matches(value)) return false
    if (tokenDecimals == 0 && value.contains('.')) return false
    if (value.substringAfter('.', missingDelimiterValue = "").length > tokenDecimals) return false

    val completeValue = value.removeSuffix(".")
    val rawUnits = runCatching {
        BigDecimal(completeValue).movePointRight(tokenDecimals).toBigIntegerExact()
    }.getOrNull() ?: return false
    return rawUnits.signum() >= 0 && rawUnits <= UINT256_MAX
}

internal fun isSubmittableCheckoutAmount(value: String, tokenDecimals: Int): Boolean =
    runCatching { TokenAmount.parse(value, tokenDecimals) }.isSuccess

internal fun checkoutAmountPlaceholder(tokenDecimals: Int): String = when {
    tokenDecimals <= 0 -> "0"
    tokenDecimals == 1 -> "0.0"
    else -> "0.00"
}

internal fun checkoutAmountDisplay(value: String, tokenDecimals: Int): String =
    value.ifEmpty { checkoutAmountPlaceholder(tokenDecimals) }

internal data class CheckoutActionCopy(
    val visibleLabel: String,
    val accessibilityLabel: String,
    val amountIsCondensed: Boolean,
)

internal fun checkoutActionCopy(amount: String, tokenSymbol: String): CheckoutActionCopy {
    val amountIsCondensed = amount.length > MAX_INLINE_CTA_AMOUNT_LENGTH
    return CheckoutActionCopy(
        visibleLabel = if (amountIsCondensed) {
            "Show payment QR · Amount shown above"
        } else {
            "Show payment QR · $amount $tokenSymbol"
        },
        accessibilityLabel = "Show payment QR for $amount $tokenSymbol",
        amountIsCondensed = amountIsCondensed,
    )
}

internal fun isCheckoutReady(
    terminalStatus: TerminalSetupStatus,
    configurationValidated: Boolean,
    refreshing: Boolean,
    readinessInvalidated: Boolean,
    operatorWalletReady: Boolean,
    hasSelectedToken: Boolean,
): Boolean = terminalStatus == TerminalSetupStatus.READY &&
    configurationValidated && !refreshing && !readinessInvalidated &&
    operatorWalletReady && hasSelectedToken

private val POTENTIAL_AMOUNT_PATTERN = Regex("^(0|[1-9][0-9]*)(\\.[0-9]*)?$")
private val UINT256_MAX: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
private const val MAX_INLINE_CTA_AMOUNT_LENGTH = 20
