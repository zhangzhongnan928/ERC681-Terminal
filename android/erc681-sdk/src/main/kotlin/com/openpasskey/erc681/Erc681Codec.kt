// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger

/** Strict codec for OPK's canonical ERC-20 and native-value ERC-681 forms. */
object Erc681Codec {
    private val erc20Pattern = Regex(
        "^ethereum:(0x[0-9a-fA-F]{40})@([1-9][0-9]*)/transfer\\?" +
            "address=(0x[0-9a-fA-F]{40})&uint256=([1-9][0-9]*)$",
    )
    private val nativePattern = Regex(
        "^ethereum:(0x[0-9a-fA-F]{40})@([1-9][0-9]*)\\?value=([1-9][0-9]*)$",
    )

    @JvmStatic
    fun encode(request: Erc681PaymentRequest): String =
        if (request.isNative) {
            "ethereum:${request.receiver.value}@${request.chainId}?value=${request.amount.rawUnits}"
        } else {
            buildString(154) {
                append("ethereum:")
                append(request.token.value)
                append('@')
                append(request.chainId)
                append("/transfer?address=")
                append(request.receiver.value)
                append("&uint256=")
                append(request.amount.rawUnits)
            }
        }

    /**
     * Parses only the two canonical OPK forms. Alternate methods, reordered/extra parameters,
     * zero values, signs, exponents, percent-encoding, and sentinel-as-target all fail closed.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(uri: String, expectedChainId: Long? = null, tokenDecimals: Int = 0): Erc681PaymentRequest {
        nativePattern.matchEntire(uri)?.let { match ->
            val chainId = parseChainId(match.groupValues[2], expectedChainId)
            val receiver = EvmAddress.parse(match.groupValues[1])
            require(receiver != NativeAsset.address) {
                "Native-asset sentinel must never appear as an ERC-681 target"
            }
            return Erc681PaymentRequest(
                token = NativeAsset.address,
                chainId = chainId,
                receiver = receiver,
                amount = TokenAmount.ofRaw(
                    parseRawAmount(match.groupValues[3]),
                    NativeAsset.DECIMALS,
                ),
            )
        }

        val match = erc20Pattern.matchEntire(uri)
            ?: throw IllegalArgumentException("Not a canonical OPK ERC-681 payment URI")

        val chainId = parseChainId(match.groupValues[2], expectedChainId)
        val token = EvmAddress.parse(match.groupValues[1])
        require(!NativeAsset.isNative(token)) {
            "Native-asset sentinel must never appear as an ERC-681 target"
        }

        return Erc681PaymentRequest(
            token = token,
            chainId = chainId,
            receiver = EvmAddress.parse(match.groupValues[3]),
            amount = TokenAmount.ofRaw(parseRawAmount(match.groupValues[4]), tokenDecimals),
        )
    }

    private fun parseChainId(value: String, expectedChainId: Long?): Long {
        val chainId = value.toLongOrNull()
            ?: throw IllegalArgumentException("Chain ID is outside the supported range")
        require(chainId > 0) { "Chain ID must be greater than zero" }
        if (expectedChainId != null) {
            require(expectedChainId > 0) { "Expected chain ID must be greater than zero" }
            require(chainId == expectedChainId) {
                "ERC-681 chain ID $chainId does not match expected chain ID $expectedChainId"
            }
        }
        return chainId
    }

    private fun parseRawAmount(value: String): BigInteger = try {
        BigInteger(value)
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException("ERC-681 amount is not a valid integer", error)
    }
}
