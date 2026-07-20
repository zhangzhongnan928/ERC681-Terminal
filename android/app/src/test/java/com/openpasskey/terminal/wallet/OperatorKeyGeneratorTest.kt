package com.openpasskey.terminal.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Security

class OperatorKeyGeneratorTest {
    @Test
    fun `generates key without a JCA secp256k1 provider and rejects invalid scalars`() {
        val curveOrder = BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            16
        )
        val random = SequenceSecureRandom(
            ByteArray(32),
            Numeric.toBytesPadded(curveOrder, 32),
            Numeric.toBytesPadded(BigInteger.ONE, 32)
        )

        val keyPair = OperatorKeyGenerator.create(random)

        assertEquals(BigInteger.ONE, keyPair.privateKey)
        assertEquals(
            "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
            Keys.toChecksumAddress("0x${Keys.getAddress(keyPair)}")
        )
        assertEquals(3, random.calls)
    }

    @Test
    fun `generation and signing work when BC provider has no ECDSA algorithm`() {
        val original = Security.getProvider("BC")
        val originalPosition = Security.getProviders()
            .indexOfFirst { it.name == "BC" }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: Security.getProviders().size + 1
        Security.removeProvider("BC")
        @Suppress("DEPRECATION")
        val strippedProvider = object : Provider("BC", 1.0, "Stripped Android-like BC provider") {}
        Security.insertProviderAt(strippedProvider, originalPosition)

        try {
            assertThrows(java.security.NoSuchAlgorithmException::class.java) {
                KeyPairGenerator.getInstance("ECDSA", "BC")
            }
            val keyPair = OperatorKeyGenerator.create(
                SequenceSecureRandom(Numeric.toBytesPadded(BigInteger.ONE, 32))
            )

            val signature = keyPair.sign(ByteArray(32) { 0x42 })

            assertTrue(signature.r.signum() > 0)
            assertTrue(signature.s.signum() > 0)
            assertTrue(signature.s <= SECP256K1_CURVE_ORDER.shiftRight(1))
        } finally {
            Security.removeProvider("BC")
            if (original != null) Security.insertProviderAt(original, originalPosition)
        }
    }

    private class SequenceSecureRandom(
        private vararg val values: ByteArray
    ) : SecureRandom() {
        var calls: Int = 0
            private set

        override fun nextBytes(bytes: ByteArray) {
            values[calls++].copyInto(bytes)
        }
    }

    private companion object {
        val SECP256K1_CURVE_ORDER = BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            16
        )
    }
}
