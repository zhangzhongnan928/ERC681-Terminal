// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger

/** Strict codec for the canonical ERC-20 transfer form of ERC-681. */
object Erc681Codec {
    private val canonicalPattern = Regex(
        "^ethereum:(0x[0-9a-fA-F]{40})@([1-9][0-9]*)/transfer\\?" +
            "address=(0x[0-9a-fA-F]{40})&uint256=([1-9][0-9]*)$",
    )

    @JvmStatic
    fun encode(request: Erc681PaymentRequest): String = buildString(154) {
        append("ethereum:")
        append(request.token.value)
        append('@')
        append(request.chainId)
        append("/transfer?address=")
        append(request.receiver.value)
        append("&uint256=")
        append(request.amount.rawUnits)
    }

    /**
     * Parses only canonical ERC-20 transfer URIs. Native value requests, alternate methods,
     * reordered/extra parameters, zero values, signs, exponents, and percent-encoding fail closed.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(uri: String, expectedChainId: Long? = null, tokenDecimals: Int = 0): Erc681PaymentRequest {
        val match = canonicalPattern.matchEntire(uri)
            ?: throw IllegalArgumentException("Not a canonical ERC-20 ERC-681 transfer URI")

        val chainId = match.groupValues[2].toLongOrNull()
            ?: throw IllegalArgumentException("Chain ID is outside the supported range")
        require(chainId > 0) { "Chain ID must be greater than zero" }
        if (expectedChainId != null) {
            require(expectedChainId > 0) { "Expected chain ID must be greater than zero" }
            require(chainId == expectedChainId) {
                "ERC-681 chain ID $chainId does not match expected chain ID $expectedChainId"
            }
        }

        val rawAmount = try {
            BigInteger(match.groupValues[4])
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("ERC-681 amount is not a valid integer", error)
        }

        return Erc681PaymentRequest(
            token = EvmAddress.parse(match.groupValues[1]),
            chainId = chainId,
            receiver = EvmAddress.parse(match.groupValues[3]),
            amount = TokenAmount.ofRaw(rawAmount, tokenDecimals),
        )
    }
}
