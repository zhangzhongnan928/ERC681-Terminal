package com.openpasskey.terminal.provisioning

import java.math.BigDecimal
import java.math.BigInteger

/** Formats an EVM native-currency amount exactly, without floating-point rounding. */
internal fun formatNativeCurrencyAmount(
    rawUnits: BigInteger,
    decimals: Int,
    symbol: String,
): String {
    require(rawUnits.signum() >= 0) { "Native-currency amount cannot be negative" }
    require(decimals >= 0) { "Native-currency decimals cannot be negative" }
    require(symbol.isNotBlank()) { "Native-currency symbol cannot be blank" }
    val displayAmount = BigDecimal(rawUnits, decimals).stripTrailingZeros().toPlainString()
    return "$displayAmount ${symbol.trim()}"
}

internal fun formatNativeCurrencyAmount(
    rawUnits: String,
    decimals: Int,
    symbol: String,
): String = runCatching {
    formatNativeCurrencyAmount(rawUnits.toBigInteger(), decimals, symbol)
}.getOrDefault("Unknown")

internal fun KnownChainProfile.minimumOperatorNativeReserveDisplay(): String =
    formatNativeCurrencyAmount(
        minimumOperatorNativeReserve,
        nativeCurrencyDecimals,
        nativeCurrencySymbol,
    )
