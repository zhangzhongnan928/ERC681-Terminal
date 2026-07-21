package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.data.model.SettlementFeeMode
import com.openpasskey.terminal.settlement.SettlementFeeQuote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SettlementProofFreshnessTest {
    @Test
    fun `elapsed proof TTL is inclusive and rejects clock reset`() {
        assertTrue(isElapsedProofFresh(1_000, 61_000, 60_000))
        assertFalse(isElapsedProofFresh(1_000, 61_001, 60_000))
        assertFalse(isElapsedProofFresh(2_000, 1_999, 60_000))
        assertFalse(isElapsedProofFresh(-1, 1_000, 60_000))
    }

    @Test
    fun `prepared and historical proofs both expire closed`() {
        val fresh = prepared(
            historicalAt = 70_000,
            preparedAt = 100_000,
        )
        requireFreshPreparedSettlementProof(fresh, 120_000)

        assertThrows(IllegalStateException::class.java) {
            requireFreshPreparedSettlementProof(fresh, 160_001)
        }
        assertThrows(IllegalStateException::class.java) {
            requireFreshPreparedSettlementProof(
                prepared(historicalAt = 60_000, preparedAt = 100_000),
                120_001,
            )
        }
    }

    @Test
    fun `device authentication must remain inside Keystore safety margin`() {
        requireFreshDeviceAuthentication(1_000, 26_000)
        assertThrows(IllegalStateException::class.java) {
            requireFreshDeviceAuthentication(1_000, 26_001)
        }
    }

    @Test
    fun `post authentication proof boundary is inclusive then expires closed`() {
        val prepared = prepared(
            historicalAt = 100_000,
            gasEstimateAt = 100_000,
            preparedAt = 100_000,
        )
        requireFreshAuthenticatedPreparedSettlement(
            prepared = prepared,
            authenticatedAtElapsedRealtimeMillis = 140_000,
            nowElapsedRealtimeMillis = 160_000,
        )

        assertThrows(IllegalStateException::class.java) {
            requireFreshAuthenticatedPreparedSettlement(
                prepared = prepared,
                authenticatedAtElapsedRealtimeMillis = 140_000,
                nowElapsedRealtimeMillis = 160_001,
            )
        }
    }

    @Test
    fun `gas estimate reuse requires exact intent and unexpired prepared proof`() {
        val proof = prepared(historicalAt = 70_000, gasEstimateAt = 100_000, preparedAt = 100_000)
        fun reusable(now: Long, callData: String = proof.callData) = proof.canReuseGasEstimateFor(
            chainId = proof.chainId,
            rpcUrl = proof.rpcUrl,
            vaultAddress = proof.vaultAddress,
            tokenAddress = proof.tokenAddress,
            operatorAddress = proof.operatorAddress,
            invoiceIds = proof.invoiceIds,
            confirmedObservedAmounts = proof.confirmedObservedAmounts,
            callData = callData,
            nowElapsedRealtimeMillis = now,
        )

        assertTrue(reusable(160_000))
        assertFalse(reusable(160_001))
        assertFalse(reusable(120_000, callData = "0xdeadbeef"))
    }

    @Test
    fun `chained preflight refresh cannot roll the gas estimate TTL forward`() {
        val original = prepared(
            historicalAt = 100_000,
            gasEstimateAt = 100_000,
            preparedAt = 100_000,
        )
        assertTrue(
            original.canReuseGasEstimateForExactFixture(nowElapsedRealtimeMillis = 150_000),
        )

        // A pre-auth live refresh may issue a new prepared proof while carrying the same estimate.
        val chained = original.copy(preparedAtElapsedRealtimeMillis = 150_000)
        assertTrue(chained.canReuseGasEstimateForExactFixture(nowElapsedRealtimeMillis = 160_000))
        assertFalse(chained.canReuseGasEstimateForExactFixture(nowElapsedRealtimeMillis = 160_001))
        assertThrows(IllegalStateException::class.java) {
            requireFreshPreparedSettlementProof(chained, 160_001)
        }
    }

    private fun PreparedSettlement.canReuseGasEstimateForExactFixture(
        nowElapsedRealtimeMillis: Long,
    ): Boolean = canReuseGasEstimateFor(
        chainId = chainId,
        rpcUrl = rpcUrl,
        vaultAddress = vaultAddress,
        tokenAddress = tokenAddress,
        operatorAddress = operatorAddress,
        invoiceIds = invoiceIds,
        confirmedObservedAmounts = confirmedObservedAmounts,
        callData = callData,
        nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
    )

    private fun prepared(
        historicalAt: Long,
        preparedAt: Long,
        gasEstimateAt: Long = preparedAt,
    ): PreparedSettlement = PreparedSettlement(
        invoiceIds = listOf("0xinvoice"),
        chainId = 84532,
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        vaultAddress = "0x" + "11".repeat(20),
        tokenAddress = "0x" + "22".repeat(20),
        tokenSymbol = "AUD",
        tokenDecimals = 18,
        operatorAddress = "0x" + "33".repeat(20),
        totalExpectedAmount = BigInteger.ONE,
        totalObservedAmount = BigInteger.ONE,
        confirmedObservedAmounts = listOf(BigInteger.ONE),
        callData = "0x682b11b5",
        nonce = BigInteger.ZERO,
        gasLimit = BigInteger.valueOf(100_000),
        feeQuote = SettlementFeeQuote(SettlementFeeMode.LEGACY, gasPrice = BigInteger.ONE),
        maximumGasCost = BigInteger.valueOf(100_000),
        safetyReserve = BigInteger.ONE,
        requiredBalance = BigInteger.valueOf(100_001),
        currentBalance = BigInteger.valueOf(200_000),
        requiredConfirmations = 2,
        confirmedRequiredBalance = BigInteger.valueOf(100_001),
        historicalProofFingerprint = "proof",
        historicalProofAtElapsedRealtimeMillis = historicalAt,
        gasEstimateAtElapsedRealtimeMillis = gasEstimateAt,
        preparedAtElapsedRealtimeMillis = preparedAt,
    )
}
