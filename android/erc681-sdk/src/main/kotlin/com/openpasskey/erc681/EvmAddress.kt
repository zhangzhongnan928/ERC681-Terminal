// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/** A validated 20-byte EVM address in canonical lower-case hexadecimal form. */
class EvmAddress private constructor(val value: String) {
    val isZero: Boolean get() = value.substring(2).all { it == '0' }

    fun toByteArray(): ByteArray = Hex.decode(value, ADDRESS_BYTES)

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean =
        other is EvmAddress && value.equals(other.value, ignoreCase = true)

    override fun hashCode(): Int = value.lowercase().hashCode()

    companion object {
        private const val ADDRESS_BYTES = 20
        private val pattern = Regex("^0x[0-9a-fA-F]{40}$")

        @JvmStatic
        fun parse(value: String): EvmAddress {
            require(pattern.matches(value)) { "EVM address must be 0x followed by exactly 40 hex digits" }
            return EvmAddress(value.lowercase())
        }

        internal fun fromBytes(bytes: ByteArray): EvmAddress {
            require(bytes.size == ADDRESS_BYTES) { "EVM address must contain 20 bytes" }
            return EvmAddress(Hex.encode(bytes))
        }
    }
}
