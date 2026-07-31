// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentObserverTest {
    private val token = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
    private val receiver = EvmAddress.parse("0xbbd352de4428d535ac79849abefa8d69bb51c671")
    private val request = Erc681PaymentRequest(token, 84532, receiver, TokenAmount.parse("12.34", 6))

    @Test
    fun `default is one canonical block confirmation`() {
        val chain = FakeChain(block = 100, balance = request.amount.rawUnits)

        val result = PaymentObserver(chain).observe(request)

        assertEquals(1, result.requiredConfirmations)
        assertEquals(1, result.confirmations)
        assertEquals(PaymentStatus.PAID, result.status)
        assertEquals(canonicalHash(100), result.fundedAtBlockHash)
    }

    @Test
    fun `payment progresses through partial confirmations and paid`() {
        val chain = FakeChain()
        val observer = PaymentObserver(chain)

        var result = observer.observe(request, requiredConfirmations = 3)
        assertEquals(PaymentStatus.AWAITING_PAYMENT, result.status)
        assertNull(result.fundedAtBlock)

        chain.block = 101
        chain.balance = BigInteger("12000000")
        result = observer.observe(request, result, 3)
        assertEquals(PaymentStatus.PARTIALLY_FUNDED, result.status)

        chain.block = 102
        chain.balance = request.amount.rawUnits
        result = observer.observe(request, result, 3)
        assertEquals(PaymentStatus.CONFIRMING, result.status)
        assertEquals(102, result.fundedAtBlock)
        assertEquals(canonicalHash(102), result.fundedAtBlockHash)
        assertEquals(1, result.confirmations)

        chain.block = 103
        result = observer.observe(request, result, 3)
        assertEquals(2, result.confirmations)
        assertEquals(PaymentStatus.CONFIRMING, result.status)

        chain.block = 104
        result = observer.observe(request, result, 3)
        assertEquals(3, result.confirmations)
        assertEquals(PaymentStatus.PAID, result.status)
        assertFalse(result.isOverpaid)
    }

    @Test
    fun `balance loss resets confirmations and later overpayment is detected`() {
        val chain = FakeChain(block = 10, balance = request.amount.rawUnits)
        val observer = PaymentObserver(chain)
        var result = observer.observe(request, requiredConfirmations = 2)

        chain.block = 11
        chain.balance = request.amount.rawUnits.subtract(BigInteger.ONE)
        result = observer.observe(request, result, 2)
        assertEquals(PaymentStatus.PARTIALLY_FUNDED, result.status)
        assertNull(result.fundedAtBlock)
        assertEquals(0, result.confirmations)

        chain.block = 12
        chain.balance = request.amount.rawUnits.add(BigInteger.ONE)
        result = observer.observe(request, result, 2)
        assertEquals(PaymentStatus.CONFIRMING, result.status)
        assertEquals(12, result.fundedAtBlock)
        assertTrue(result.isOverpaid)
    }

    @Test
    fun `changed fully funded balance starts a new confirmation window`() {
        val chain = FakeChain(block = 100, balance = BigInteger.TEN)
        val observer = PaymentObserver(chain)
        val smallRequest = request.copy(amount = TokenAmount.ofRaw(BigInteger.TEN, 6))

        var result = observer.observe(smallRequest, requiredConfirmations = 2)
        assertEquals(PaymentStatus.CONFIRMING, result.status)
        assertEquals(100L, result.fundedAtBlock)

        chain.block = 101
        chain.balance = BigInteger("100")
        result = observer.observe(smallRequest, result, 2)
        assertEquals(PaymentStatus.CONFIRMING, result.status)
        assertEquals(101L, result.fundedAtBlock)
        assertEquals(1, result.confirmations)

        chain.block = 102
        result = observer.observe(smallRequest, result, 2)
        assertEquals(PaymentStatus.PAID, result.status)
        assertEquals(2, result.confirmations)
    }

    @Test
    fun `canonical funding block reorg restarts confirmation window`() {
        val chain = FakeChain(block = 100, balance = request.amount.rawUnits)
        val observer = PaymentObserver(chain)

        var result = observer.observe(request, requiredConfirmations = 3)
        assertEquals(canonicalHash(100), result.fundedAtBlockHash)
        chain.block = 101
        result = observer.observe(request, result, 3)
        assertEquals(2, result.confirmations)

        chain.hashes[100] = OTHER_BLOCK_HASH
        chain.block = 102
        result = observer.observe(request, result, 3)
        assertEquals(PaymentStatus.CONFIRMING, result.status)
        assertEquals(102L, result.fundedAtBlock)
        assertEquals(canonicalHash(102), result.fundedAtBlockHash)
        assertEquals(1, result.confirmations)
    }

    @Test
    fun `head reorg during saved cursor lookup rejects the entire sample`() {
        val chain = FakeChain(block = 100, balance = request.amount.rawUnits)
        val observer = PaymentObserver(chain)
        val previous = observer.observe(request, requiredConfirmations = 2)

        chain.block = 101
        var currentHeadReads = 0
        chain.blockHashOverride = { blockNumber ->
            if (blockNumber == 101L) {
                currentHeadReads += 1
                if (currentHeadReads < 3) canonicalHash(101) else OTHER_BLOCK_HASH
            } else {
                canonicalHash(blockNumber)
            }
        }

        assertFailsWith<RpcException> {
            observer.observe(request, previous, requiredConfirmations = 2)
        }
        assertEquals(3, currentHeadReads)
    }

    private class FakeChain(
        var block: Long = 100,
        var balance: BigInteger = BigInteger.ZERO,
    ) : ReadOnlyChainClient {
        val hashes = mutableMapOf<Long, String>()
        var blockHashOverride: ((Long) -> String?)? = null

        override fun chainId(): Long = 84532
        override fun codeAt(address: EvmAddress): ByteArray = byteArrayOf(1)
        override fun factoryImplementation(): EvmAddress = EvmAddress.parse("0x" + "11".repeat(20))
        override fun vaultFactory(): EvmAddress = EvmAddress.parse("0x" + "22".repeat(20))
        override fun isPaymentToken(token: EvmAddress): Boolean = true
        override fun tokenDecimals(token: EvmAddress): Int = 6
        override fun tokenSymbol(token: EvmAddress): String = "TEST"
        override fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long?): BigInteger {
            assertEquals(block, blockNumber)
            return balance
        }
        override fun blockNumber(): Long = block
        override fun blockHash(blockNumber: Long): String? =
            blockHashOverride?.invoke(blockNumber)
                ?: hashes[blockNumber]
                ?: canonicalHash(blockNumber)
    }


    companion object {
        private const val OTHER_BLOCK_HASH =
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        private fun canonicalHash(block: Long): String =
            "0x" + block.toString(16).padStart(64, '0')
    }
}
