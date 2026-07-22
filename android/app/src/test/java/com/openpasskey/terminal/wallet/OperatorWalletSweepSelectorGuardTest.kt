package com.openpasskey.terminal.wallet

import com.openpasskey.terminal.settlement.SettlementAbi
import com.openpasskey.terminal.settlement.SettlementInvoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.RawTransaction
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * The operator key must sign exactly the calldata produced by [SettlementAbi.encodeSweepSessions]
 * once it has travelled through web3j's [RawTransaction], which strips the 0x prefix from data.
 * Regression: the guard compared the carried data against a 0x-prefixed selector string, so every
 * legitimate settlement was rejected with "Operator key only signs sweepSessions calls".
 */
class OperatorWalletSweepSelectorGuardTest {

    @Test
    fun acceptsSweepCallDataCarriedByLegacyRawTransaction() {
        val raw = RawTransaction.createTransaction(
            NONCE,
            GAS_PRICE,
            GAS_LIMIT,
            VAULT,
            BigInteger.ZERO,
            encodedSweepSessions(),
        )
        requireSweepSessionsCallData(raw.data)
    }

    @Test
    fun acceptsSweepCallDataCarriedByType2RawTransaction() {
        val raw = RawTransaction.createTransaction(
            CHAIN_ID,
            NONCE,
            GAS_LIMIT,
            VAULT,
            BigInteger.ZERO,
            encodedSweepSessions(),
            MAX_PRIORITY_FEE,
            MAX_FEE,
        )
        requireSweepSessionsCallData(raw.data)
    }

    @Test
    fun acceptsEncoderOutputBeforeAnyTransactionWrapping() {
        requireSweepSessionsCallData(encodedSweepSessions())
    }

    @Test
    fun acceptsUppercaseHexDigitsBehindALowercasePrefix() {
        // Uppercase DIGITS are fine — web3j decodes them case-insensitively to the same bytes.
        val upperDigits = "0x" + encodedSweepSessions().removePrefix("0x").uppercase()
        requireSweepSessionsCallData(upperDigits)
        val raw = RawTransaction.createTransaction(
            NONCE,
            GAS_PRICE,
            GAS_LIMIT,
            VAULT,
            BigInteger.ZERO,
            upperDigits,
        )
        requireSweepSessionsCallData(raw.data)
    }

    /**
     * An uppercase "0X" PREFIX is not recognized by web3j's cleanHexPrefix, so it survives into
     * the signed bytes: hexStringToByteArray decodes the "0X" pair to 0xff and the transaction
     * signs selector 0xff682b11 instead of sweepSessions. The guard must reject the exact
     * representation web3j will decode, for both fee modes, as carried by RawTransaction.
     */
    @Test
    fun rejectsUppercase0XPrefixCarriedThroughLegacyRawTransaction() {
        val attack = "0X" + encodedSweepSessions().removePrefix("0x").uppercase()
        val raw = RawTransaction.createTransaction(
            NONCE,
            GAS_PRICE,
            GAS_LIMIT,
            VAULT,
            BigInteger.ZERO,
            attack,
        )
        assertTrue(raw.data.startsWith("0X"))
        assertThrows(IllegalArgumentException::class.java) {
            requireSweepSessionsCallData(raw.data)
        }
    }

    @Test
    fun rejectsUppercase0XPrefixCarriedThroughType2RawTransaction() {
        val attack = "0X" + encodedSweepSessions().removePrefix("0x").uppercase()
        val raw = RawTransaction.createTransaction(
            CHAIN_ID,
            NONCE,
            GAS_LIMIT,
            VAULT,
            BigInteger.ZERO,
            attack,
            MAX_PRIORITY_FEE,
            MAX_FEE,
        )
        assertTrue(raw.data.startsWith("0X"))
        assertThrows(IllegalArgumentException::class.java) {
            requireSweepSessionsCallData(raw.data)
        }
    }

    @Test
    fun uppercasePrefixCandidateDocumentsTheSignedByteDivergence() {
        // Pins the web3j semantics this guard mirrors. If either assertion fails after a web3j
        // upgrade, the guard's decode rationale must be re-examined.
        assertEquals("0X682B11B5", Numeric.cleanHexPrefix("0X682B11B5"))
        val signedHead = Numeric.toHexString(
            Numeric.hexStringToByteArray("0X682B11B5").copyOfRange(0, 5),
        )
        assertEquals("0xff682b11b5", signedHead)
        assertThrows(IllegalArgumentException::class.java) {
            requireSweepSessionsCallData("0X682B11B5" + "00".repeat(30))
        }
    }

    @Test
    fun encoderEmitsTheCanonicalSweepSessionsSelector() {
        assertTrue(encodedSweepSessions().startsWith("0x682b11b5"))
    }

    @Test
    fun rejectsForeignSelectorsWithAndWithoutPrefix() {
        val transferCallData = "a9059cbb" +
            "000000000000000000000000" + RECEIVER.removePrefix("0x") +
            "00000000000000000000000000000000000000000000000000000000000f4240"
        listOf("0x$transferCallData", transferCallData).forEach { callData ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSweepSessionsCallData(callData)
            }
        }
    }

    @Test
    fun rejectsEmptyMissingOrMisplacedSelectorData() {
        listOf(null, "", "0x", "0x00682b11b5", "00682b11b5").forEach { callData ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSweepSessionsCallData(callData)
            }
        }
    }

    /**
     * Odd-length hex passes a naive "682b11b5" string-prefix check, but web3j left-pads it with a
     * leading nibble before signing, so the bytes actually authorized begin 0x0682b11b. The guard
     * validates the decoded bytes it will sign, so every odd-length variant must be rejected.
     */
    @Test
    fun rejectsOddLengthHexThatShiftsTheSignedSelector() {
        listOf(
            "682b11b5f",
            "0x682b11b5f",
            "682B11B5F",
            "682b11b5deadbee",
        ).forEach { callData ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSweepSessionsCallData(callData)
            }
        }
    }

    @Test
    fun oddLengthCandidateWouldHavePassedANaiveStringPrefixCheck() {
        // Documents the bypass this guard closes: the string starts with the selector text, yet the
        // signed bytes do not. If this assertion ever fails the padding behaviour changed and the
        // guard's byte-level rationale must be re-examined.
        val bypass = "682b11b5f"
        assertTrue(bypass.startsWith("682b11b5"))
        val signedSelector = Numeric.toHexString(
            Numeric.hexStringToByteArray(bypass).copyOfRange(0, 4),
        )
        assertEquals("0x0682b11b", signedSelector)
        assertThrows(IllegalArgumentException::class.java) {
            requireSweepSessionsCallData(bypass)
        }
    }

    @Test
    fun rejectsNonHexCharactersInSelectorPosition() {
        listOf("682b11g5", "0x682b11z500", "  682b11b5").forEach { callData ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSweepSessionsCallData(callData)
            }
        }
    }

    private fun encodedSweepSessions(): String = SettlementAbi.encodeSweepSessions(
        listOf(
            SettlementInvoiceIntent(
                invoiceId = "0x" + "11".repeat(32),
                receiver = RECEIVER,
                expectedAmount = BigInteger.valueOf(1_000_000),
            ),
        ),
        TOKEN,
    )

    private companion object {
        const val CHAIN_ID = 84532L
        const val VAULT = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"
        const val RECEIVER = "0x3333333333333333333333333333333333333333"
        const val TOKEN = "0x4444444444444444444444444444444444444444"
        val NONCE: BigInteger = BigInteger.ONE
        val GAS_PRICE: BigInteger = BigInteger.valueOf(1_000_000_000)
        val GAS_LIMIT: BigInteger = BigInteger.valueOf(300_000)
        val MAX_PRIORITY_FEE: BigInteger = BigInteger.valueOf(1_000_000_000)
        val MAX_FEE: BigInteger = BigInteger.valueOf(2_000_000_000)
    }
}
