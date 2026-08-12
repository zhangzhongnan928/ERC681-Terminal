// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The batched fast path used with production [ReadOnlyRpcClient] instances must produce the same
 * attribution as the sequential compatibility path while grouping its reads into few round trips,
 * and its closing bracket must remain a fresh network read rather than a cache hit.
 */
class PaymentEvidenceBatchedRpcTest {
    @Test
    fun `erc20 resolution completes in four grouped round trips`() {
        val rpc = ScriptedRpc()
        val client = ReadOnlyRpcClient.forTest(CONFIG, rpc::execute)

        val evidence = requireNotNull(PaymentEvidenceResolver(client).resolve(request()))

        assertEquals(TX_HASH, evidence.txHash)
        assertEquals(PAYER.value, evidence.payerAddress)
        assertEquals(CROSSING_BLOCK, evidence.blockNumber)
        assertEquals(CROSSING_HASH, evidence.blockHash)
        assertEquals(CROSSING_TIMESTAMP, evidence.blockTimestamp)
        assertEquals(4, rpc.httpPosts)
    }

    @Test
    fun `closing bracket re-reads anchors fresh and rejects a rotated funding hash`() {
        val rpc = ScriptedRpc(fundingHashOnSecondRead = ROTATED_HASH)
        val client = ReadOnlyRpcClient.forTest(CONFIG, rpc::execute)

        val error = assertFailsWith<RpcException> {
            PaymentEvidenceResolver(client).resolve(request())
        }

        assertEquals(
            "Canonical funding block hash does not match the saved invoice evidence",
            error.message,
        )
        // All four grouped round trips ran: the bracket failure came from a fresh closing read.
        assertEquals(4, rpc.httpPosts)
    }

    @Test
    fun `midpoint prefetch covers every height the bounded search visits`() {
        for (last in 2L..16L) {
            val midpoints = balanceCrossingMidpoints(1L, last, 4)
            for (crossing in 1L..last) {
                val visited = mutableSetOf<Long>()
                val found = findFirstBalanceCrossing(1L, last, BigInteger.ONE) { block ->
                    visited += block
                    if (block >= crossing) BigInteger.ONE else BigInteger.ZERO
                }
                assertEquals(crossing, found)
                assertTrue(
                    (visited - last).all { it in midpoints },
                    "range 1..$last crossing $crossing visited $visited beyond $midpoints",
                )
            }
        }
    }

    private fun request() = PaymentEvidenceRequest(
        chainId = CONFIG.chainId,
        asset = TOKEN,
        receiver = RECEIVER,
        expectedAmount = EXPECTED_AMOUNT,
        publicationCursor = PaymentConfirmationCursor(PUBLICATION_BLOCK, PUBLICATION_HASH),
        fundingCursor = PaymentConfirmationCursor(FUNDING_BLOCK, FUNDING_HASH),
    )

    /**
     * Routes every JSON-RPC request of a canonical ERC-20 attribution: publication at block 100
     * with balance 0, the 150-unit crossing at block 103, and the saved funding cursor at 104.
     */
    private class ScriptedRpc(
        private val fundingHashOnSecondRead: String = FUNDING_HASH,
    ) {
        var httpPosts = 0
        private var fundingHeaderReads = 0

        fun execute(body: String): String {
            httpPosts += 1
            val root = JsonParser.parseString(body)
            return if (root.isJsonArray) {
                JsonArray().apply {
                    root.asJsonArray.forEach { add(respond(it.asJsonObject)) }
                }.toString()
            } else {
                respond(root.asJsonObject).toString()
            }
        }

        private fun respond(request: JsonObject): JsonObject {
            val params = request.getAsJsonArray("params")
            val result = when (val method = request.get("method").asString) {
                "eth_chainId" -> primitive("0x14a34")
                "eth_getBlockByNumber" -> when (val tag = params[0].asString) {
                    quantity(PUBLICATION_BLOCK) -> header(
                        PUBLICATION_BLOCK,
                        PUBLICATION_HASH,
                        PUBLICATION_TIMESTAMP,
                    )
                    quantity(CROSSING_BLOCK) -> header(
                        CROSSING_BLOCK,
                        CROSSING_HASH,
                        CROSSING_TIMESTAMP,
                    )
                    quantity(FUNDING_BLOCK) -> {
                        fundingHeaderReads += 1
                        header(
                            FUNDING_BLOCK,
                            if (fundingHeaderReads >= 2) fundingHashOnSecondRead else FUNDING_HASH,
                            FUNDING_TIMESTAMP,
                        )
                    }
                    else -> error("Unexpected block header read at $tag")
                }
                "eth_call" -> primitive(
                    when (val tag = params[1].asString) {
                        quantity(PUBLICATION_BLOCK) -> abiWord(BigInteger.ZERO)
                        quantity(101L) -> abiWord(BigInteger.ZERO)
                        quantity(102L) -> abiWord(BigInteger.ZERO)
                        quantity(CROSSING_BLOCK) -> abiWord(PAID_AMOUNT)
                        quantity(FUNDING_BLOCK) -> abiWord(PAID_AMOUNT)
                        else -> error("Unexpected balance read at $tag")
                    },
                )
                "eth_getLogs" -> JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("removed", false)
                        addProperty("address", TOKEN.value)
                        addProperty("blockNumber", quantity(CROSSING_BLOCK))
                        addProperty("blockHash", CROSSING_HASH)
                        addProperty("transactionHash", TX_HASH)
                        addProperty("logIndex", "0x0")
                        addProperty("data", abiWord(PAID_AMOUNT))
                        add("topics", JsonArray().apply {
                            add(TRANSFER_TOPIC)
                            add(addressTopic(PAYER))
                            add(addressTopic(RECEIVER))
                        })
                    })
                }
                else -> error("Unexpected JSON-RPC method $method")
            }
            return JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", request.get("id"))
                add("result", result)
            }
        }

        private fun primitive(value: String) = com.google.gson.JsonPrimitive(value)

        private fun header(number: Long, hash: String, timestamp: Long) = JsonObject().apply {
            addProperty("number", quantity(number))
            addProperty("hash", hash)
            addProperty("timestamp", "0x" + timestamp.toString(16))
        }
    }

    private companion object {
        val FACTORY = EvmAddress.parse("0x1111111111111111111111111111111111111111")
        val IMPLEMENTATION = EvmAddress.parse("0x2222222222222222222222222222222222222222")
        val VAULT = EvmAddress.parse("0x3333333333333333333333333333333333333333")
        val TOKEN = EvmAddress.parse("0x4444444444444444444444444444444444444444")
        val RECEIVER = EvmAddress.parse("0x5555555555555555555555555555555555555555")
        val PAYER = EvmAddress.parse("0x6666666666666666666666666666666666666666")
        val CONFIG = NetworkConfig(
            chainId = 84_532,
            rpcUrl = "https://rpc.example",
            factory = FACTORY,
            receiverImplementation = IMPLEMENTATION,
            vault = VAULT,
        )
        const val PUBLICATION_BLOCK = 100L
        const val CROSSING_BLOCK = 103L
        const val FUNDING_BLOCK = 104L
        const val PUBLICATION_TIMESTAMP = 1_700_000_100L
        const val CROSSING_TIMESTAMP = 1_700_000_103L
        const val FUNDING_TIMESTAMP = 1_700_000_104L
        val EXPECTED_AMOUNT: BigInteger = BigInteger.valueOf(100)
        val PAID_AMOUNT: BigInteger = BigInteger.valueOf(150)
        const val PUBLICATION_HASH =
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CROSSING_HASH =
            "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val FUNDING_HASH =
            "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val ROTATED_HASH =
            "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val TX_HASH =
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        fun quantity(value: Long): String = "0x" + value.toString(16)

        fun addressTopic(address: EvmAddress): String =
            "0x" + address.value.substring(2).padStart(64, '0')

        fun abiWord(value: BigInteger): String = "0x" + value.toString(16).padStart(64, '0')
    }
}
