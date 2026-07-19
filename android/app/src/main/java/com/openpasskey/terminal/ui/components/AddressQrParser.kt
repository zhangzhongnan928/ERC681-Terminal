package com.openpasskey.terminal.ui.components

import com.openpasskey.erc681.EvmAddress

/** Extracts one non-zero configuration address from deliberately narrow QR payloads. */
internal object AddressQrParser {
    private const val ETHEREUM_PREFIX = "ethereum:"

    fun parse(content: String): String {
        require(content.isNotEmpty()) { "QR code is empty" }
        require(content.none { it.isWhitespace() || it.isISOControl() }) {
            "QR code must contain only an address"
        }

        val candidate = when {
            content.startsWith("0x") -> content
            content.regionMatches(0, ETHEREUM_PREFIX, 0, ETHEREUM_PREFIX.length, true) -> {
                content.substring(ETHEREUM_PREFIX.length).removePrefix("//")
            }
            else -> throw IllegalArgumentException(
                "QR must contain a raw 0x address or an address-only ethereum: URI",
            )
        }

        val address = EvmAddress.parse(candidate)
        require(!address.isZero) { "Zero address is not valid configuration" }
        return address.value
    }
}
