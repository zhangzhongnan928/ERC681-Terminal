// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/** An immutable 32-byte invoice identifier. */
class InvoiceId private constructor(bytes: ByteArray) {
    private val contents = bytes.copyOf()

    val hex: String get() = Hex.encode(contents)

    fun toByteArray(): ByteArray = contents.copyOf()

    override fun toString(): String = hex

    override fun equals(other: Any?): Boolean = other is InvoiceId && contents.contentEquals(other.contents)

    override fun hashCode(): Int = contents.contentHashCode()

    companion object {
        @JvmStatic
        fun parse(value: String): InvoiceId = InvoiceId(Hex.decode(value, 32))

        internal fun fromBytes(bytes: ByteArray): InvoiceId {
            require(bytes.size == 32) { "Invoice ID must contain 32 bytes" }
            return InvoiceId(bytes)
        }
    }
}
