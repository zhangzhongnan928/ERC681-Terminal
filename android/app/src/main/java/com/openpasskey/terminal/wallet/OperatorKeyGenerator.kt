package com.openpasskey.terminal.wallet

import org.web3j.crypto.ECKeyPair
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Generates an Ethereum secp256k1 scalar without using a JCA EC provider.
 *
 * web3j 4.8.9-android's Keys.createEcKeyPair() asks the platform's provider named `BC` for the
 * `ECDSA` algorithm. Android ships a deliberately reduced provider with that name, so the call can
 * fail on a real device even though web3j's bundled lightweight curve arithmetic works normally.
 */
internal object OperatorKeyGenerator {
    private const val PRIVATE_KEY_BYTES = 32
    private val curveOrder = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16
    )

    fun create(secureRandom: SecureRandom = SecureRandom()): ECKeyPair {
        val candidateBytes = ByteArray(PRIVATE_KEY_BYTES)
        try {
            while (true) {
                secureRandom.nextBytes(candidateBytes)
                val candidate = BigInteger(1, candidateBytes)
                if (candidate.signum() > 0 && candidate < curveOrder) {
                    return ECKeyPair.create(candidate)
                }
            }
        } finally {
            candidateBytes.fill(0)
        }
    }
}
