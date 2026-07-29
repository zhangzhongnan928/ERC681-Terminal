// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigDecimal
import java.math.BigInteger

/** An exact, positive ERC-20 amount represented in raw on-chain units. */
data class TokenAmount(
    val rawUnits: BigInteger,
    val decimals: Int,
) {
    init {
        require(decimals in 0..255) { "Token decimals must be between 0 and 255" }
        require(rawUnits.signum() > 0) { "Token amount must be greater than zero" }
        require(rawUnits <= UINT256_MAX) { "Token amount exceeds uint256" }
    }

    val display: String
        get() = BigDecimal(rawUnits, decimals).stripTrailingZeros().toPlainString()

    companion object {
        private val UINT256_MAX: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
        private val displayPattern = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")

        @JvmStatic
        fun parse(display: String, decimals: Int): TokenAmount {
            require(decimals in 0..255) { "Token decimals must be between 0 and 255" }
            require(displayPattern.matches(display)) {
                "Amount must be an unsigned plain decimal without whitespace or exponent notation"
            }
            val fractionalDigits = display.substringAfter('.', missingDelimiterValue = "").length
            require(fractionalDigits <= decimals) {
                "Amount has more fractional digits than the token supports"
            }
            val rawUnits = try {
                BigDecimal(display).movePointRight(decimals).toBigIntegerExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Amount has more precision than the token supports", error)
            }
            return TokenAmount(rawUnits, decimals)
        }

        @JvmStatic
        fun ofRaw(rawUnits: BigInteger, decimals: Int): TokenAmount = TokenAmount(rawUnits, decimals)
    }
}
