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

class PaymentEvidenceRpcTest {
    @Test
    fun `full canonical block decodes native transactions and timestamp`() {
        val client = ReadOnlyRpcClient.forTest(CONFIG) { requestBody ->
            val request = JsonParser.parseString(requestBody).asJsonObject
            assertEquals("eth_getBlockByNumber", request.get("method").asString)
            assertEquals("0x66", request.getAsJsonArray("params")[0].asString)
            assertEquals(true, request.getAsJsonArray("params")[1].asBoolean)
            success(
                request,
                JsonObject().apply {
                    addProperty("number", "0x66")
                    addProperty("hash", BLOCK_HASH)
                    addProperty("timestamp", "0x6553f166")
                    add("transactions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("hash", TX_HASH)
                            addProperty("from", PAYER.value)
                            addProperty("to", RECEIVER.value)
                            addProperty("transactionIndex", "0x3")
                            addProperty("value", "0x2a")
                            addProperty("blockNumber", "0x66")
                            addProperty("blockHash", BLOCK_HASH)
                        })
                    })
                },
            )
        }

        val block = requireNotNull(client.paymentEvidenceBlock(102, true))

        assertEquals(102L, block.blockNumber)
        assertEquals(BLOCK_HASH, block.blockHash)
        assertEquals(1_700_000_102L, block.blockTimestamp)
        assertEquals(1, block.directNativeTransactions.size)
        with(block.directNativeTransactions.single()) {
            assertEquals(TX_HASH, txHash)
            assertEquals(PAYER, payer)
            assertEquals(RECEIVER, recipient)
            assertEquals(3L, transactionIndex)
            assertEquals(BigInteger.valueOf(42), value)
        }
    }

    @Test
    fun `receiver scoped ERC20 logs decode ordered attribution fields`() {
        val client = ReadOnlyRpcClient.forTest(CONFIG) { requestBody ->
            val request = JsonParser.parseString(requestBody).asJsonObject
            assertEquals("eth_getLogs", request.get("method").asString)
            val filter = request.getAsJsonArray("params")[0].asJsonObject
            assertEquals("0x66", filter.get("fromBlock").asString)
            assertEquals("0x66", filter.get("toBlock").asString)
            assertEquals(TOKEN.value, filter.get("address").asString)
            val topics = filter.getAsJsonArray("topics")
            assertEquals(TRANSFER_TOPIC, topics[0].asString)
            assertEquals(true, topics[1].isJsonNull)
            assertEquals(addressTopic(RECEIVER), topics[2].asString)
            success(
                request,
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("removed", false)
                        addProperty("address", TOKEN.value)
                        addProperty("blockNumber", "0x66")
                        addProperty("blockHash", BLOCK_HASH)
                        addProperty("transactionHash", TX_HASH)
                        addProperty("logIndex", "0x9")
                        add("topics", JsonArray().apply {
                            add(TRANSFER_TOPIC)
                            add(addressTopic(PAYER))
                            add(addressTopic(RECEIVER))
                        })
                        addProperty("data", abiWord(BigInteger.valueOf(123)))
                    })
                },
            )
        }

        val transfer = client.incomingErc20Transfers(TOKEN, RECEIVER, 102).single()

        assertEquals(TX_HASH, transfer.txHash)
        assertEquals(TOKEN, transfer.token)
        assertEquals(PAYER, transfer.payer)
        assertEquals(RECEIVER, transfer.recipient)
        assertEquals(9L, transfer.logIndex)
        assertEquals(BigInteger.valueOf(123), transfer.value)
        assertEquals(102L, transfer.blockNumber)
        assertEquals(BLOCK_HASH, transfer.blockHash)
    }

    @Test
    fun `full block rejects transaction from a different canonical block`() {
        val client = ReadOnlyRpcClient.forTest(CONFIG) { requestBody ->
            val request = JsonParser.parseString(requestBody).asJsonObject
            success(
                request,
                JsonObject().apply {
                    addProperty("number", "0x66")
                    addProperty("hash", BLOCK_HASH)
                    addProperty("timestamp", "0x1")
                    add("transactions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("hash", TX_HASH)
                            addProperty("from", PAYER.value)
                            addProperty("to", RECEIVER.value)
                            addProperty("transactionIndex", "0x0")
                            addProperty("value", "0x1")
                            addProperty("blockNumber", "0x65")
                            addProperty("blockHash", BLOCK_HASH)
                        })
                    })
                },
            )
        }

        assertFailsWith<RpcException> { client.paymentEvidenceBlock(102, true) }
    }

    @Test
    fun `full block rejects a native transaction without payer identity`() {
        val client = ReadOnlyRpcClient.forTest(CONFIG) { requestBody ->
            val request = JsonParser.parseString(requestBody).asJsonObject
            success(
                request,
                JsonObject().apply {
                    addProperty("number", "0x66")
                    addProperty("hash", BLOCK_HASH)
                    addProperty("timestamp", "0x1")
                    add("transactions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("hash", TX_HASH)
                            addProperty("to", RECEIVER.value)
                            addProperty("transactionIndex", "0x0")
                            addProperty("value", "0x1")
                            addProperty("blockNumber", "0x66")
                            addProperty("blockHash", BLOCK_HASH)
                        })
                    })
                },
            )
        }

        assertFailsWith<RpcException> { client.paymentEvidenceBlock(102, true) }
    }

    private fun success(request: JsonObject, result: com.google.gson.JsonElement): String =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", request.get("id"))
            add("result", result)
        }.toString()

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
        const val BLOCK_HASH =
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TX_HASH =
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        fun addressTopic(address: EvmAddress): String =
            "0x" + address.value.substring(2).padStart(64, '0')

        fun abiWord(value: BigInteger): String = "0x" + value.toString(16).padStart(64, '0')
    }
}
