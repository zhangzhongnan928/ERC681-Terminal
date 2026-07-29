// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.security.SecureRandom

/** Creates protocol-compatible invoice IDs without a wallet or signing key. */
object InvoiceIdGenerator {
    /**
     * Generates an invoice ID using a persistent, non-secret 20-byte terminal namespace.
     * The protocol treats this as a generic address-shaped value; an integrating app may apply a
     * stricter policy, such as always supplying its device operator EOA public address.
     */
    @JvmStatic
    @JvmOverloads
    fun generate(
        terminalIdentifier: EvmAddress,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000,
        random: SecureRandom = SecureRandom(),
    ): InvoiceId {
        val nonce = ByteArray(32).also(random::nextBytes)
        return generate(terminalIdentifier, timestampSeconds, nonce)
    }

    /** Deterministic overload for conformance tests and restored invoice creation state. */
    @JvmStatic
    fun generate(
        terminalIdentifier: EvmAddress,
        timestampSeconds: Long,
        nonce: ByteArray,
    ): InvoiceId {
        require(timestampSeconds >= 0) { "Timestamp must not be negative" }
        require(nonce.size == 32) { "Nonce must contain exactly 32 bytes" }

        val abiEncoded = ByteArray(96)
        terminalIdentifier.toByteArray().copyInto(abiEncoded, destinationOffset = 12)
        writeUnsignedLong(timestampSeconds, abiEncoded, destinationOffset = 56)
        nonce.copyInto(abiEncoded, destinationOffset = 64)
        return InvoiceId.fromBytes(Keccak256.digest(abiEncoded))
    }

    /**
     * Low-level SDK convenience when no namespace is supplied. Production terminal apps should
     * persist and explicitly pass the namespace required by their identity policy.
     */
    @JvmStatic
    @JvmOverloads
    fun generate(random: SecureRandom = SecureRandom()): InvoiceId {
        val namespace = ByteArray(20).also(random::nextBytes)
        return generate(EvmAddress.fromBytes(namespace), System.currentTimeMillis() / 1_000, random)
    }

    private fun writeUnsignedLong(value: Long, destination: ByteArray, destinationOffset: Int) {
        for (index in 0 until Long.SIZE_BYTES) {
            destination[destinationOffset + Long.SIZE_BYTES - 1 - index] = (value ushr (index * 8)).toByte()
        }
    }
}
