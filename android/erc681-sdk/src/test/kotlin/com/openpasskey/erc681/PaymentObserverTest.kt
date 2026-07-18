package com.openpasskey.erc681

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentObserverTest {
    private val token = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
    private val receiver = EvmAddress.parse("0x9107decd2cb06c57c40a663648e19cde1d52f606")
    private val request = Erc681PaymentRequest(token, 84532, receiver, TokenAmount.parse("12.34", 6))

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

    private class FakeChain(
        var block: Long = 100,
        var balance: BigInteger = BigInteger.ZERO,
    ) : ReadOnlyChainClient {
        override fun chainId(): Long = 84532
        override fun codeAt(address: EvmAddress): ByteArray = byteArrayOf(1)
        override fun factoryImplementation(): EvmAddress = EvmAddress.parse("0x" + "11".repeat(20))
        override fun vaultFactory(): EvmAddress = EvmAddress.parse("0x" + "22".repeat(20))
        override fun isPaymentToken(token: EvmAddress): Boolean = true
        override fun tokenDecimals(token: EvmAddress): Int = 6
        override fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long?): BigInteger {
            assertEquals(block, blockNumber)
            return balance
        }
        override fun blockNumber(): Long = block
    }
}
