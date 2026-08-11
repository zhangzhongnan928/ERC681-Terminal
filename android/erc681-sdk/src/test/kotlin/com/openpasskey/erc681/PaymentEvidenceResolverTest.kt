// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaymentEvidenceResolverTest {
    @Test
    fun `binary search returns first canonical balance crossing`() {
        val balances = mapOf(
            101L to BigInteger.ZERO,
            102L to BigInteger("4"),
            103L to BigInteger("9"),
            104L to BigInteger.TEN,
            105L to BigInteger("12"),
        )

        val block = findFirstBalanceCrossing(101, 105, BigInteger.TEN) {
            balances.getValue(it)
        }

        assertEquals(104L, block)
    }

    @Test
    fun `ERC20 resolver orders cumulative transfers and selects threshold transaction`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances.putAll(
                mapOf(
                    100L to BigInteger.ZERO,
                    101L to BigInteger("2"),
                    102L to BigInteger("4"),
                    103L to BigInteger.TEN,
                    104L to BigInteger.TEN,
                ),
            )
            transfers[103] = listOf(
                erc20Transfer(index = 5, amount = 4, marker = "22"),
                erc20Transfer(index = 2, amount = 2, marker = "11"),
            )
        }

        val evidence = PaymentEvidenceResolver(chain).resolve(request())

        assertEquals(hash("22"), evidence?.txHash)
        assertEquals(PAYER.value, evidence?.payerAddress)
        assertEquals(103L, evidence?.blockNumber)
        assertEquals(hashForBlock(103), evidence?.blockHash)
        assertEquals(1_700_000_103L, evidence?.blockTimestamp)
        assertEquals(listOf(100L, 104L, 102L, 103L), chain.balanceReads)
    }

    @Test
    fun `native resolver attributes only ordered direct value transactions`() {
        val first = nativeTransaction(index = 3, amount = 3, marker = "44")
        val crossing = nativeTransaction(index = 6, amount = 5, marker = "55")
        val chain = FakeEvidenceChain(asset = NativeAsset.address).apply {
            balances.putAll(
                mapOf(
                    100L to BigInteger.ZERO,
                    101L to BigInteger("2"),
                    102L to BigInteger.TEN,
                    104L to BigInteger.TEN,
                ),
            )
            fullTransactions[102] = listOf(
                crossing,
                nativeTransaction(index = 1, amount = 100, marker = "66", recipient = OTHER),
                first,
            )
        }

        val evidence = PaymentEvidenceResolver(chain).resolve(
            request(asset = NativeAsset.address),
        )

        assertEquals(crossing.txHash, evidence?.txHash)
        assertEquals(PAYER.value, evidence?.payerAddress)
        assertEquals(102L, evidence?.blockNumber)
    }

    @Test
    fun `native internal or indirect balance crossing remains unattributed`() {
        val chain = FakeEvidenceChain(asset = NativeAsset.address).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.ZERO
            balances[102] = BigInteger.TEN
            balances[104] = BigInteger.TEN
            fullTransactions[102] = listOf(
                nativeTransaction(index = 0, amount = 50, marker = "77", recipient = OTHER),
            )
        }

        val evidence = PaymentEvidenceResolver(chain).resolve(
            request(asset = NativeAsset.address),
        )

        assertNull(evidence)
    }

    @Test
    fun `ERC20 balance crossing without sufficient matching logs remains unattributed`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.ZERO
            balances[102] = BigInteger.TEN
            balances[104] = BigInteger.TEN
            transfers[102] = listOf(
                erc20Transfer(index = 1, amount = 3, marker = "88", block = 102),
            )
        }

        assertNull(PaymentEvidenceResolver(chain).resolve(request()))
    }

    @Test
    fun `wrong chain fails before any block balance or log read`() {
        val chain = FakeEvidenceChain(chainId = 1L, asset = TOKEN)

        assertFailsWith<NetworkConfigurationException> {
            PaymentEvidenceResolver(chain).resolve(request())
        }

        assertEquals(0, chain.blockReads)
        assertEquals(emptyList(), chain.balanceReads)
        assertEquals(0, chain.logReads)
    }

    @Test
    fun `saved funding anchor mismatch fails before attribution`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            blockOverride = { block, _ ->
                val blockHash = if (block == 104L) hash("aa") else hashForBlock(block)
                PaymentEvidenceBlock(block, blockHash, 1_700_000_000L + block)
            }
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(request())
        }
    }

    @Test
    fun `canonical reorg during resolution invalidates selected evidence`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.ZERO
            balances[102] = BigInteger.TEN
            balances[104] = BigInteger.TEN
            transfers[102] = listOf(
                erc20Transfer(index = 1, amount = 10, marker = "99", block = 102),
            )
            blockOverride = { block, read ->
                val blockHash = if (block == 100L && read > 1) hash("ff") else hashForBlock(block)
                PaymentEvidenceBlock(block, blockHash, 1_700_000_000L + block)
            }
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(request())
        }
    }

    @Test
    fun `payment block timestamp change invalidates selected evidence`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.ZERO
            balances[102] = BigInteger.TEN
            balances[104] = BigInteger.TEN
            transfers[102] = listOf(
                erc20Transfer(index = 1, amount = 10, marker = "9a", block = 102),
            )
            blockOverride = { block, read ->
                val changedTimestamp = if (block == 102L && read > 1) 1 else 0
                PaymentEvidenceBlock(
                    block,
                    hashForBlock(block),
                    1_700_000_000L + block + changedTimestamp,
                )
            }
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(request())
        }
    }

    @Test
    fun `alternate client cannot substitute an ERC20 token`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.TEN
            balances[102] = BigInteger.TEN
            balances[103] = BigInteger.TEN
            balances[104] = BigInteger.TEN
            transfers[101] = listOf(
                erc20Transfer(
                    index = 1,
                    amount = 10,
                    marker = "9b",
                    block = 101,
                    token = OTHER,
                ),
            )
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(request())
        }
    }

    @Test
    fun `alternate client cannot return a removed ERC20 log`() {
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = BigInteger.ZERO
            balances[101] = BigInteger.TEN
            transfers[101] = listOf(
                erc20Transfer(
                    index = 1,
                    amount = 10,
                    marker = "9d",
                    block = 101,
                    removed = true,
                ),
            )
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(
                request(funding = PaymentConfirmationCursor(101, hashForBlock(101))),
            )
        }
    }

    @Test
    fun `cumulative ERC20 attribution rejects uint256 overflow`() {
        val almostMaximum = UINT256_MAX.subtract(BigInteger.ONE)
        val chain = FakeEvidenceChain(asset = TOKEN).apply {
            balances[100] = almostMaximum
            balances[101] = UINT256_MAX
            transfers[101] = listOf(
                erc20Transfer(index = 1, amount = 2, marker = "9c", block = 101),
            )
        }

        assertFailsWith<RpcException> {
            PaymentEvidenceResolver(chain).resolve(
                request(
                    expectedAmount = UINT256_MAX,
                    funding = PaymentConfirmationCursor(101, hashForBlock(101)),
                ),
            )
        }
    }

    @Test
    fun `request rejects noncanonical anchors and impossible cursor order`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentConfirmationCursor(100, "0x1234")
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                publication = PaymentConfirmationCursor(104, hashForBlock(104)),
                funding = PaymentConfirmationCursor(104, hashForBlock(104)),
            )
        }
    }

    @Test
    fun `evidence rejects a zero payer identity`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentTransactionEvidence(
                txHash = hash("ab"),
                payerAddress = "0x" + "00".repeat(20),
                blockNumber = 102,
                blockHash = hashForBlock(102),
                blockTimestamp = 1_700_000_102,
            )
        }
    }

    private class FakeEvidenceChain(
        private val chainId: Long = CHAIN_ID,
        private val asset: EvmAddress,
    ) : PaymentEvidenceChainClient {
        val balances = mutableMapOf<Long, BigInteger>()
        val transfers = mutableMapOf<Long, List<IncomingErc20Transfer>>()
        val fullTransactions = mutableMapOf<Long, List<DirectNativePaymentTransaction>>()
        val balanceReads = mutableListOf<Long>()
        var blockReads = 0
        var logReads = 0
        var blockOverride: ((Long, Int) -> PaymentEvidenceBlock?)? = null
        private val blockReadCounts = mutableMapOf<Long, Int>()

        override fun chainId(): Long = chainId

        override fun paymentAssetBalance(
            asset: EvmAddress,
            receiver: EvmAddress,
            blockNumber: Long,
        ): BigInteger {
            assertEquals(this.asset, asset)
            assertEquals(RECEIVER, receiver)
            balanceReads += blockNumber
            return balances[blockNumber] ?: BigInteger.ZERO
        }

        override fun paymentEvidenceBlock(
            blockNumber: Long,
            includeDirectNativeTransactions: Boolean,
        ): PaymentEvidenceBlock? {
            blockReads += 1
            val read = blockReadCounts.getOrDefault(blockNumber, 0) + 1
            blockReadCounts[blockNumber] = read
            blockOverride?.let { return it(blockNumber, read) }
            return PaymentEvidenceBlock(
                blockNumber = blockNumber,
                blockHash = hashForBlock(blockNumber),
                blockTimestamp = 1_700_000_000L + blockNumber,
                directNativeTransactions = if (includeDirectNativeTransactions) {
                    fullTransactions[blockNumber].orEmpty()
                } else {
                    emptyList()
                },
            )
        }

        override fun incomingErc20Transfers(
            token: EvmAddress,
            receiver: EvmAddress,
            blockNumber: Long,
        ): List<IncomingErc20Transfer> {
            assertEquals(asset, token)
            assertEquals(RECEIVER, receiver)
            logReads += 1
            return transfers[blockNumber].orEmpty()
        }
    }

    private fun request(
        asset: EvmAddress = TOKEN,
        expectedAmount: BigInteger = BigInteger.TEN,
        publication: PaymentConfirmationCursor =
            PaymentConfirmationCursor(100, hashForBlock(100)),
        funding: PaymentConfirmationCursor = PaymentConfirmationCursor(104, hashForBlock(104)),
    ) = PaymentEvidenceRequest(
        chainId = CHAIN_ID,
        asset = asset,
        receiver = RECEIVER,
        expectedAmount = expectedAmount,
        publicationCursor = publication,
        fundingCursor = funding,
    )

    private fun erc20Transfer(
        index: Long,
        amount: Long,
        marker: String,
        recipient: EvmAddress = RECEIVER,
        block: Long = 103,
        token: EvmAddress = TOKEN,
        removed: Boolean = false,
    ) = IncomingErc20Transfer(
        txHash = hash(marker),
        token = token,
        payer = PAYER,
        recipient = recipient,
        logIndex = index,
        value = BigInteger.valueOf(amount),
        blockNumber = block,
        blockHash = hashForBlock(block),
        removed = removed,
    )

    private fun nativeTransaction(
        index: Long,
        amount: Long,
        marker: String,
        recipient: EvmAddress = RECEIVER,
        block: Long = 102,
    ) = DirectNativePaymentTransaction(
        txHash = hash(marker),
        payer = PAYER,
        recipient = recipient,
        transactionIndex = index,
        value = BigInteger.valueOf(amount),
        blockNumber = block,
        blockHash = hashForBlock(block),
    )

    private companion object {
        const val CHAIN_ID = 84_532L
        val TOKEN = EvmAddress.parse("0x1111111111111111111111111111111111111111")
        val RECEIVER = EvmAddress.parse("0x2222222222222222222222222222222222222222")
        val OTHER = EvmAddress.parse("0x3333333333333333333333333333333333333333")
        val PAYER = EvmAddress.parse("0x4444444444444444444444444444444444444444")

        fun hash(marker: String): String = "0x" + marker.repeat(32)
        fun hashForBlock(block: Long): String = "0x" + block.toString(16).padStart(64, '0')
    }
}
