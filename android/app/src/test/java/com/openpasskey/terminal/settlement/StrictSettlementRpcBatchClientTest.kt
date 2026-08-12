package com.openpasskey.terminal.settlement

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.openpasskey.erc681.NativeAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.math.BigInteger

class StrictSettlementRpcBatchClientTest {
    @Test
    fun `batch matches out of order responses by exact request ID`() {
        var requestCount = 0
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            val requests = JsonParser.parseString(body).asJsonArray
            requestCount += requests.size()
            JsonArray().apply {
                requests.reversed().forEach { element ->
                    val request = element.asJsonObject
                    add(success(request, request.get("method").asString))
                }
            }.toString()
        }

        val results = client.execute(
            listOf(
                SettlementRpcCall("first", JsonArray()),
                SettlementRpcCall("second", JsonArray()),
                SettlementRpcCall("third", JsonArray()),
            ),
        )

        assertEquals(3, requestCount)
        assertEquals(listOf("first", "second", "third"), results.map { it.asString })
    }

    @Test
    fun `batch rejects duplicate missing and RPC error responses`() {
        fun malformed(transform: (JsonArray) -> JsonArray) =
            StrictSettlementRpcBatchClient.forTest { body ->
                val requests = JsonParser.parseString(body).asJsonArray
                transform(JsonArray().apply {
                    requests.forEach { element ->
                        val request = element.asJsonObject
                        add(success(request, "0x1"))
                    }
                }).toString()
            }

        val calls = listOf(
            SettlementRpcCall("one", JsonArray()),
            SettlementRpcCall("two", JsonArray()),
        )
        assertThrows(SettlementRpcException::class.java) {
            malformed { responses ->
                responses.set(1, responses[0])
                responses
            }.execute(calls)
        }
        assertThrows(SettlementRpcException::class.java) {
            malformed { responses ->
                responses.remove(responses.size() - 1)
                responses
            }.execute(calls)
        }

        val rpcError = StrictSettlementRpcBatchClient.forTest { body ->
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.forEach { element ->
                    val request = element.asJsonObject
                    add(JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        add("id", request.get("id"))
                        add("error", JsonObject().apply {
                            addProperty("code", -32000)
                            addProperty("message", "reverted")
                        })
                    })
                }
            }.toString()
        }
        val error = assertThrows(SettlementRpcException::class.java) {
            rpcError.execute(listOf(calls.first()))
        }
        assertEquals(-32000, error.rpcCode)
    }

    @Test
    fun `protocol maximum primary wave is split into strict max ten chunks without reordering`() {
        // A 20-invoice primary settlement wave contains 27 independent reads. The two cursor
        // waves are separately ordered around it so mixed concurrent chunks cannot hide a reorg.
        assertEquals(20, SettlementAbi.MAX_BATCH_SIZE)
        val observedChunkSizes = Collections.synchronizedList(mutableListOf<Int>())
        val allChunksStarted = CountDownLatch(3)
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            val requests = JsonParser.parseString(body).asJsonArray
            observedChunkSizes += requests.size()
            allChunksStarted.countDown()
            check(allChunksStarted.await(5, TimeUnit.SECONDS)) {
                "Protocol-max chunks were not dispatched concurrently"
            }
            JsonArray().apply {
                requests.reversed().forEach { element ->
                    val request = element.asJsonObject
                    add(success(request, request.get("method").asString))
                }
            }.toString()
        }
        val calls = List(27) { index -> SettlementRpcCall("call-$index", JsonArray()) }

        val results = client.executeChunked(calls)

        assertEquals((0 until 27).map { "call-$it" }, results.map { it.asString })
        assertEquals(3, observedChunkSizes.size)
        assertEquals(27, observedChunkSizes.sum())
        assertTrue(observedChunkSizes.all { it in 1..StrictSettlementRpcBatchClient.MAX_BATCH_ITEMS })
    }

    @Test
    fun `chunk failure never retains provider controlled exception text`() {
        val secret = "https://provider.example/base/terminal-client-key"
        val client = StrictSettlementRpcBatchClient.forTest {
            throw IllegalStateException(secret)
        }
        val calls = List(11) { index -> SettlementRpcCall("call-$index", JsonArray()) }

        val error = assertThrows(SettlementRpcException::class.java) {
            client.executeChunked(calls)
        }

        assertEquals("JSON-RPC batch response is not valid JSON", error.message)
        assertFalse(error.toString().contains(secret))
        assertEquals(null, error.cause)
    }

    @Test
    fun `protocol maximum settlement preflight preserves three ordered waves`() {
        val operator = "0x" + "11".repeat(20)
        val vault = "0x" + "22".repeat(20)
        val token = "0x" + "33".repeat(20)
        val callData = "0xdeadbeef"
        val receivers = List(SettlementAbi.MAX_BATCH_SIZE) { index ->
            SettlementReceiverSafetyRead(
                tokenAddress = token,
                receiverAddress = "0x" + (index + 1).toString(16).padStart(40, '0'),
                canonicalBlockNumber = 100L + (index % 2),
            )
        }
        val batches = Collections.synchronizedList(mutableListOf<List<JsonObject>>())
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            val requests = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }
            batches += requests
            JsonArray().apply {
                requests.reversed().forEach { request ->
                    val method = request.get("method").asString
                    val result = when (method) {
                        "eth_chainId" -> JsonParser.parseString("\"0x14a34\"")
                        "eth_getTransactionCount" -> JsonParser.parseString("\"0x1\"")
                        "eth_gasPrice" -> JsonParser.parseString("\"0x2\"")
                        "eth_getBalance" -> JsonParser.parseString("\"0xffffffffffffffff\"")
                        "eth_estimateGas" -> JsonParser.parseString("\"0x5208\"")
                        "eth_getBlockByNumber" -> {
                            val tag = request.getAsJsonArray("params")[0].asString
                            if (tag == "latest") {
                                JsonObject().apply { addProperty("baseFeePerGas", "0x1") }
                            } else {
                                val number = BigInteger(tag.substring(2), 16).toLong()
                                JsonObject().apply {
                                    addProperty("number", tag)
                                    addProperty("hash", blockHash(number))
                                }
                            }
                        }
                        "eth_call" -> {
                            val data = request.getAsJsonArray("params")[0].asJsonObject
                                .get("data").asString
                            val value = when {
                                data == callData -> "0x"
                                data.startsWith(SettlementAbi.encodeIsOperator(operator).take(10)) ->
                                    abiUint(BigInteger.ONE)
                                else -> abiUint(BigInteger.valueOf(5))
                            }
                            JsonParser.parseString("\"$value\"")
                        }
                        else -> error("Unexpected method $method")
                    }
                    add(success(request, result))
                }
            }.toString()
        }

        val snapshot = executeSettlementPreflight(
            batch = client,
            request = SettlementPreflightRequest(operator, vault, callData, receivers),
            includeGasEstimate = true,
        )

        assertEquals(5, batches.size)
        val isCursorBatch: (List<JsonObject>) -> Boolean = { requests ->
            requests.all { request ->
                request.get("method").asString == "eth_getBlockByNumber" &&
                    request.getAsJsonArray("params")[0].asString != "latest"
            }
        }
        assertTrue(isCursorBatch(batches.first()))
        assertTrue(batches.subList(1, 4).none(isCursorBatch))
        assertTrue(batches.last().any { it.get("method").asString == "eth_estimateGas" })
        val exactCursorReads = batches.flatten().count { request ->
            request.get("method").asString == "eth_getBlockByNumber" &&
                request.getAsJsonArray("params")[0].asString != "latest"
        }
        assertEquals(4, exactCursorReads) // two unique heights, once before and once after
        assertTrue(batches.all { it.size <= StrictSettlementRpcBatchClient.MAX_BATCH_ITEMS })
        assertTrue(snapshot.operatorListed)
        assertFalse(
            batches.flatten().any { rpcRequest ->
                rpcRequest.get("method").asString == "eth_call" &&
                    rpcRequest.getAsJsonArray("params")[0].asJsonObject
                        .get("data").asString == SettlementAbi.encodeOwner()
            },
        )
        assertEquals(20, snapshot.canonicalBlockHashes.size)
        assertEquals(snapshot.canonicalBlockHashes, snapshot.canonicalBlockHashesAfter)
        assertEquals(List(20) { BigInteger.valueOf(5) }, snapshot.receiverBalances)
    }

    @Test
    fun `native settlement preflight reads each receiver with eth_getBalance`() {
        val operator = "0x" + "11".repeat(20)
        val vault = "0x" + "22".repeat(20)
        val receiver = "0x" + "33".repeat(20)
        val callData = "0xdeadbeef"
        val requestsSeen = Collections.synchronizedList(mutableListOf<JsonObject>())
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            val requests = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }
            requestsSeen += requests
            JsonArray().apply {
                requests.reversed().forEach { request ->
                    val result = when (request.get("method").asString) {
                        "eth_chainId" -> JsonParser.parseString("\"0x14a34\"")
                        "eth_getTransactionCount" -> JsonParser.parseString("\"0x1\"")
                        "eth_gasPrice" -> JsonParser.parseString("\"0x2\"")
                        "eth_getBalance" -> {
                            val account = request.getAsJsonArray("params")[0].asString
                            JsonParser.parseString(if (account == receiver) "\"0x5\"" else "\"0xa\"")
                        }
                        "eth_getBlockByNumber" -> {
                            val tag = request.getAsJsonArray("params")[0].asString
                            if (tag == "latest") {
                                JsonObject().apply { addProperty("baseFeePerGas", "0x1") }
                            } else {
                                val number = BigInteger(tag.substring(2), 16).toLong()
                                JsonObject().apply {
                                    addProperty("number", tag)
                                    addProperty("hash", blockHash(number))
                                }
                            }
                        }
                        "eth_call" -> {
                            val data = request.getAsJsonArray("params")[0].asJsonObject
                                .get("data").asString
                            JsonParser.parseString(
                                if (data == callData) "\"0x\"" else "\"${abiUint(BigInteger.ONE)}\"",
                            )
                        }
                        else -> error("Unexpected method")
                    }
                    add(success(request, result))
                }
            }.toString()
        }

        val snapshot = executeSettlementPreflight(
            batch = client,
            request = SettlementPreflightRequest(
                operator,
                vault,
                callData,
                listOf(
                    SettlementReceiverSafetyRead(
                        NativeAsset.address.value,
                        receiver,
                        100,
                    ),
                ),
            ),
            includeGasEstimate = false,
        )

        assertEquals(listOf(BigInteger.valueOf(5)), snapshot.receiverBalances)
        assertTrue(requestsSeen.any { request ->
            request.get("method").asString == "eth_getBalance" &&
                request.getAsJsonArray("params")[0].asString == receiver
        })
        assertFalse(requestsSeen.any { request ->
            request.get("method").asString == "eth_call" &&
                request.getAsJsonArray("params")[0].asJsonObject.get("to").asString
                    .equals(NativeAsset.address.value, ignoreCase = true)
        })
    }

    @Test
    fun `scheduled recovery receipt proof is one ordered transport batch`() {
        val transactionHash = "0x" + "12".repeat(32)
        val receiptBlock = 100L
        val receiptBlockHash = blockHash(receiptBlock)
        var transportCalls = 0
        var methods = emptyList<String>()
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            transportCalls += 1
            val requests = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }
            methods = requests.map { it.get("method").asString }
            JsonArray().apply {
                requests.reversed().forEach { request ->
                    val result = when (request.get("method").asString) {
                        "eth_getTransactionReceipt" -> JsonObject().apply {
                            addProperty("status", "0x1")
                            addProperty("blockNumber", "0x64")
                            addProperty("blockHash", receiptBlockHash)
                            addProperty("transactionHash", transactionHash)
                            add("logs", JsonArray())
                        }
                        "eth_getBlockByNumber" -> JsonObject().apply {
                            addProperty("number", "0x64")
                            addProperty("hash", receiptBlockHash)
                        }
                        "eth_blockNumber" -> JsonParser.parseString("\"0x65\"")
                        else -> error("Unexpected recovery method")
                    }
                    add(success(request, result))
                }
            }.toString()
        }

        val snapshot = executeSettlementRecoverySnapshot(
            batch = client,
            txHash = transactionHash,
            expectedReceiptBlock = receiptBlock,
        )

        assertEquals(1, transportCalls)
        assertEquals(
            listOf(
                "eth_getTransactionReceipt",
                "eth_getBlockByNumber",
                "eth_blockNumber",
            ),
            methods,
        )
        assertEquals(transactionHash, snapshot.receipt?.transactionHash)
        assertEquals(receiptBlockHash, snapshot.canonicalReceiptBlockHash)
        assertEquals(101L, snapshot.latestBlockNumber)
    }

    @Test
    fun `batch rejects string fractional negative and foreign IDs`() {
        fun rejectsId(replacement: (JsonObject) -> Unit) {
            val client = StrictSettlementRpcBatchClient.forTest { body ->
                val request = JsonParser.parseString(body).asJsonArray.single().asJsonObject
                JsonArray().apply {
                    add(success(request, "0x1").apply(replacement))
                }.toString()
            }
            assertThrows(SettlementRpcException::class.java) {
                client.execute(listOf(SettlementRpcCall("one", JsonArray())))
            }
        }

        rejectsId { response -> response.addProperty("id", response.get("id").asString) }
        rejectsId { response -> response.addProperty("id", 1.5) }
        rejectsId { response -> response.addProperty("id", -1) }
        rejectsId { response -> response.addProperty("id", response.get("id").asLong + 1_000_000) }
    }

    @Test
    fun `batch requires JSON-RPC version to be the exact string`() {
        val client = StrictSettlementRpcBatchClient.forTest { body ->
            val request = JsonParser.parseString(body).asJsonArray.single().asJsonObject
            JsonArray().apply {
                add(success(request, "0x1").apply { addProperty("jsonrpc", 2.0) })
            }.toString()
        }

        assertThrows(SettlementRpcException::class.java) {
            client.execute(listOf(SettlementRpcCall("one", JsonArray())))
        }
    }

    private fun success(request: JsonObject, result: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        addProperty("result", result)
    }

    private fun success(request: JsonObject, result: com.google.gson.JsonElement): JsonObject =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", request.get("id"))
            add("result", result)
        }

    private fun blockHash(blockNumber: Long): String =
        "0x" + blockNumber.toString(16).padStart(64, '0')

    private fun abiUint(value: BigInteger): String =
        "0x" + value.toString(16).padStart(64, '0')
}
