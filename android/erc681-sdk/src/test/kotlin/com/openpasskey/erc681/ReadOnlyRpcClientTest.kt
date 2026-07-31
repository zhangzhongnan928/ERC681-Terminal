// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlyRpcClientTest {
    private val factory = EvmAddress.parse("0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f")
    private val implementation = EvmAddress.parse("0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18")
    private val vault = EvmAddress.parse("0x1111111111111111111111111111111111111111")
    private val token = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
    private val holder = EvmAddress.parse("0xbbd352de4428d535ac79849abefa8d69bb51c671")
    private val config = NetworkConfig(84532, "https://rpc.example.invalid", factory, implementation, vault)

    @Test
    fun `client exposes only decoded read methods`() {
        val seen = mutableListOf<JsonObject>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject.also(seen::add)
            val method = request.get("method").asString
            if (method == "eth_getBlockByNumber") {
                val params = request.getAsJsonArray("params")
                assertEquals("0x64", params[0].asString)
                assertEquals(false, params[1].asBoolean)
                success(request, blockResult(100, BLOCK_HASH))
            } else {
                val result = when (method) {
                "eth_chainId" -> "0x14a34"
                "eth_blockNumber" -> "0x64"
                "eth_getCode" -> "0x60016000"
                "eth_call" -> when (request.getAsJsonArray("params")[0].asJsonObject.get("data").asString.take(10)) {
                    "0x5c60da1b" -> abiAddress(implementation)
                    "0xc45a0155" -> abiAddress(factory)
                    "0x930eaddc" -> abiUint(BigInteger.ONE)
                    "0x313ce567" -> abiUint(BigInteger.valueOf(18))
                    "0x95d89b41" -> abiString("AUD")
                    "0x70a08231" -> abiUint(BigInteger("12340000"))
                    else -> error("Unexpected eth_call selector")
                }
                    else -> error("Unexpected method $method")
                }
                success(request, result)
            }
        }

        assertEquals(84532, client.chainId())
        assertEquals(100, client.blockNumber())
        assertEquals(BLOCK_HASH, client.blockHash(100))
        assertContentEquals(Hex.decode("0x60016000"), client.codeAt(factory))
        assertEquals(implementation, client.factoryImplementation())
        assertEquals(factory, client.vaultFactory())
        assertTrue(client.isPaymentToken(token))
        assertEquals(18, client.tokenDecimals(token))
        assertEquals("AUD", client.tokenSymbol(token))
        assertEquals(BigInteger("12340000"), client.tokenBalance(token, holder, 100))
        val balanceRequest = seen.last().getAsJsonArray("params")
        assertEquals("0x64", balanceRequest[1].asString)
        assertEquals(token.value, balanceRequest[0].asJsonObject.get("to").asString)
    }

    @Test
    fun `validation rejects an RPC chain mismatch before contract checks`() {
        val requestBodies = mutableListOf<String>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.forEach { element ->
                    val request = element.asJsonObject
                    add(
                        if (request.get("method").asString == "eth_chainId") {
                            rpcSuccess(request, "0x1")
                        } else {
                            rpcSuccess(request, blockResult(100, BLOCK_HASH))
                        },
                    )
                }
            }.toString()
        }

        val error = assertFailsWith<NetworkConfigurationException> { client.validate(token) }
        assertTrue(error.message.orEmpty().contains("does not match"))
        assertEquals(1, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies.single()),
        )
    }

    @Test
    fun `validation uses anchored three waves and retains vault code from state batch`() {
        val requestBodies = mutableListOf<String>()
        var vaultCodeReads = 0
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val methods = requestMethods(body)
            when {
                "eth_chainId" in methods -> {
                    val requests = root.asJsonArray
                    JsonArray().apply {
                        requests.forEach { element ->
                            val request = element.asJsonObject
                            add(
                                if (request.get("method").asString == "eth_chainId") {
                                    rpcSuccess(request, "0x14a34")
                                } else {
                                    rpcSuccess(request, blockResult(100, BLOCK_HASH))
                                },
                            )
                        }
                    }.toString()
                }
                methods == listOf("eth_getBlockByNumber") ->
                    success(root.asJsonObject, blockResult(100, BLOCK_HASH))
                else -> {
                val requests = root.asJsonArray
                assertEquals(9, requests.size())
                // Reverse the responses to prove ID matching does not depend on response order.
                JsonArray().apply {
                    requests.reversed().forEach { element ->
                        val request = element.asJsonObject
                        val method = request.get("method").asString
                        if (
                            method == "eth_getCode" &&
                            request.getAsJsonArray("params")[0].asString == vault.value
                        ) {
                            vaultCodeReads += 1
                        }
                        val result = when (method) {
                            "eth_getCode" -> "0x60016000"
                            "eth_call" -> when (
                                request.getAsJsonArray("params")[0].asJsonObject
                                    .get("data").asString.take(10)
                            ) {
                                "0x5c60da1b" -> abiAddress(implementation)
                                "0xc45a0155" -> abiAddress(factory)
                                "0x930eaddc" -> abiUint(BigInteger.ONE)
                                "0x313ce567" -> abiUint(BigInteger.valueOf(18))
                                "0x95d89b41" -> abiString("AUD")
                                else -> error("Unexpected eth_call selector")
                            }
                            else -> error("Unexpected method $method")
                        }
                        add(JsonParser.parseString(success(request, result)))
                    }
                }.toString()
                }
            }
        }

        val evidence = client.validateWithEvidence(token, 18, "AUD")
        val validation = evidence.validation

        assertEquals(3, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies.first()),
        )
        JsonParser.parseString(requestBodies[1]).asJsonArray.forEach { element ->
            val request = element.asJsonObject
            assertEquals("0x64", request.getAsJsonArray("params")[1].asString)
        }
        assertEquals(listOf("eth_getBlockByNumber"), requestMethods(requestBodies.last()))
        assertEquals("0x64", checkoutBlockTag(requestBodies.last()))
        assertEquals(1, vaultCodeReads)
        assertEquals(token, validation.token)
        assertEquals("AUD", validation.tokenSymbol)
        assertContentEquals(Hex.decode("0x60016000"), evidence.vaultRuntimeCode)
        val mutableCopy = evidence.vaultRuntimeCode
        mutableCopy.fill(0)
        assertContentEquals(Hex.decode("0x60016000"), evidence.vaultRuntimeCode)
    }

    @Test
    fun `native validation requires NATIVE_ASSET capability and vault whitelist`() {
        fun makeClient(
            advertisedNativeAsset: EvmAddress,
            whitelisted: Boolean,
            requestBodies: MutableList<String> = mutableListOf(),
        ): ReadOnlyRpcClient = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                val result: Any = when (request.get("method").asString) {
                    "eth_chainId" -> "0x14a34"
                    "eth_getBlockByNumber" -> blockResult(100, BLOCK_HASH)
                    "eth_getCode" -> "0x60016000"
                    "eth_call" -> when (
                        request.getAsJsonArray("params")[0].asJsonObject
                            .get("data").asString.take(10)
                    ) {
                        "0xbf53253b" -> abiAddress(advertisedNativeAsset)
                        "0x5c60da1b" -> abiAddress(implementation)
                        "0xc45a0155" -> abiAddress(factory)
                        "0x930eaddc" -> abiUint(if (whitelisted) BigInteger.ONE else BigInteger.ZERO)
                        else -> error("Unexpected native validation selector")
                    }
                    else -> error("Unexpected native validation method")
                }
                when (result) {
                    is String -> JsonParser.parseString(success(request, result))
                    is JsonObject -> JsonParser.parseString(success(request, result))
                    else -> error("Unexpected native validation result")
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }

        val successBodies = mutableListOf<String>()
        val validation = makeClient(NativeAsset.address, true, successBodies)
            .validate(NativeAsset.address, NativeAsset.DECIMALS, "ETH")
        assertEquals(NativeAsset.address, validation.token)
        assertEquals(NativeAsset.DECIMALS, validation.tokenDecimals)
        assertEquals("ETH", validation.tokenSymbol)
        val proofRequests = JsonParser.parseString(successBodies[1]).asJsonArray
        assertEquals(7, proofRequests.size())
        assertTrue(proofRequests.none { element ->
            val request = element.asJsonObject
            request.get("method").asString == "eth_getCode" &&
                request.getAsJsonArray("params")[0].asString.equals(
                    NativeAsset.address.value,
                    ignoreCase = true,
                )
        })

        assertFailsWith<NetworkConfigurationException> {
            makeClient(token, true).validate(
                NativeAsset.address,
                NativeAsset.DECIMALS,
                "ETH",
            )
        }
        assertFailsWith<NetworkConfigurationException> {
            makeClient(NativeAsset.address, false).validate(
                NativeAsset.address,
                NativeAsset.DECIMALS,
                "ETH",
            )
        }
    }

    @Test
    fun `validation fails closed when anchored head changes after state reads`() {
        var wave = 0
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            wave += 1
            val root = JsonParser.parseString(body)
            val methods = requestMethods(body)
            when (wave) {
                1 -> JsonArray().apply {
                    root.asJsonArray.forEach { element ->
                        val request = element.asJsonObject
                        add(
                            if (request.get("method").asString == "eth_chainId") {
                                rpcSuccess(request, "0x14a34")
                            } else {
                                rpcSuccess(request, blockResult(100, BLOCK_HASH))
                            },
                        )
                    }
                }.toString()
                2 -> JsonArray().apply {
                    root.asJsonArray.forEach { element ->
                        val request = element.asJsonObject
                        add(rpcSuccess(request, checkoutStateResult(request)))
                    }
                }.toString()
                3 -> {
                    assertEquals(listOf("eth_getBlockByNumber"), methods)
                    success(root.asJsonObject, blockResult(100, OTHER_BLOCK_HASH))
                }
                else -> error("Unexpected validation wave")
            }
        }

        assertFailsWith<RpcException> { client.validateWithEvidence(token, 18, "AUD") }
        assertEquals(3, wave)
    }

    @Test
    fun `checkout proof uses fixed-head waves and conservative pending gas balance`() {
        val requestBodies = Collections.synchronizedList(mutableListOf<String>())
        val middleStarted = CountDownLatch(2)
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val methods = requests.map { it.asJsonObject.get("method").asString }
            val responses = when {
                "eth_chainId" in methods -> requests.map { element ->
                    val request = element.asJsonObject
                    if (request.get("method").asString == "eth_chainId") {
                        rpcSuccess(request, "0x14a34")
                    } else {
                        rpcSuccess(request, blockResult(100, BLOCK_HASH))
                    }
                }
                methods == listOf("eth_getBlockByNumber") -> requests.map { element ->
                    val request = element.asJsonObject
                    rpcSuccess(request, blockResult(100, BLOCK_HASH))
                }
                else -> {
                    middleStarted.countDown()
                    assertTrue(
                        middleStarted.await(5, TimeUnit.SECONDS),
                        "Both bounded state batches must be in flight together",
                    )
                    requests.reversed().map { element ->
                        val request = element.asJsonObject
                        rpcSuccess(request, checkoutStateResult(request))
                    }
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }

        val proof = client.validateCheckout(token, 18, "AUD", holder, holder)

        assertEquals(4, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies.first()),
        )
        assertEquals(
            listOf(5, 10),
            requestBodies.subList(1, 3).map { JsonParser.parseString(it).asJsonArray.size() }.sorted(),
        )
        assertEquals(listOf("eth_getBlockByNumber"), requestMethods(requestBodies.last()))
        requestBodies.subList(1, 3).forEach { body ->
            JsonParser.parseString(body).asJsonArray.forEach { element ->
                val request = element.asJsonObject
                val params = request.getAsJsonArray("params")
                val blockTagIndex = when (request.get("method").asString) {
                    "eth_call" -> 1
                    "eth_getCode" -> 1
                    "eth_getBalance" -> 1
                    else -> error("Unexpected state method")
                }
                val expectedTag = if (
                    request.get("method").asString == "eth_getBalance" &&
                    params[blockTagIndex].asString == "pending"
                ) {
                    "pending"
                } else {
                    "0x64"
                }
                assertEquals(expectedTag, params[blockTagIndex].asString)
            }
        }
        assertEquals("latest", checkoutBlockTag(requestBodies.first()))
        assertEquals("0x64", checkoutBlockTag(requestBodies.last()))
        assertEquals(100, proof.blockNumber)
        assertEquals(BLOCK_HASH, proof.blockHash)
        assertTrue(proof.operatorReadiness.listedOperator)
        assertEquals(holder, proof.operatorReadiness.vaultOwner)
        assertEquals(BigInteger.ONE, proof.operatorReadiness.nativeBalance)
        assertEquals(BigInteger.ZERO, proof.receiverFreshness.tokenBalance)
        assertTrue(proof.receiverFreshness.deployedCode.isEmpty())
    }

    @Test
    fun `checkout proof rejects wrong chain before requesting state`() {
        val requestBodies = mutableListOf<String>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.forEach { element ->
                    val request = element.asJsonObject
                    val response = if (request.get("method").asString == "eth_chainId") {
                        rpcSuccess(request, "0x1")
                    } else {
                        rpcSuccess(request, blockResult(100, BLOCK_HASH))
                    }
                    add(response)
                }
            }.toString()
        }

        assertFailsWith<NetworkConfigurationException> {
            client.validateCheckout(token, 18, "AUD", holder, holder)
        }

        assertEquals(1, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies.single()),
        )
    }

    @Test
    fun `checkout proof fails closed when anchored head is reorganized`() {
        val requestBodies = Collections.synchronizedList(mutableListOf<String>())
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val methods = requests.map { it.asJsonObject.get("method").asString }
            val responses = when {
                "eth_chainId" in methods -> requests.map { element ->
                    val request = element.asJsonObject
                    if (request.get("method").asString == "eth_chainId") {
                        rpcSuccess(request, "0x14a34")
                    } else {
                        rpcSuccess(request, blockResult(100, BLOCK_HASH))
                    }
                }
                methods == listOf("eth_getBlockByNumber") -> requests.map { element ->
                    rpcSuccess(element.asJsonObject, blockResult(100, OTHER_BLOCK_HASH))
                }
                else -> requests.map { element ->
                    val request = element.asJsonObject
                    rpcSuccess(request, checkoutStateResult(request))
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }

        assertFailsWith<RpcException> {
            client.validateCheckout(token, 18, "AUD", holder, holder)
        }

        assertEquals(4, requestBodies.size)
    }

    @Test
    fun `listed operator readiness tolerates owner RPC failure`() {
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                val selector = request.getAsJsonArray("params").firstOrNull()
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("data")
                    ?.asString
                    ?.take(10)
                if (selector == "0x8da5cb5b") {
                    return@map JsonParser.parseString(rpcError(request, -32000, "owner unavailable"))
                }
                val result = when (request.get("method").asString) {
                    "eth_getBalance" -> "0x123"
                    "eth_call" -> abiUint(BigInteger.ONE)
                    else -> error("Unexpected method")
                }
                JsonParser.parseString(success(request, result))
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }

        val readiness = client.operatorReadiness(holder)

        assertTrue(readiness.listedOperator)
        assertTrue(readiness.vaultOwner.isZero)
        assertEquals(BigInteger("123", 16), readiness.nativeBalance)
    }

    @Test
    fun `unlisted operator readiness fetches owner as a fail closed fallback`() {
        var requestCount = 0
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestCount += 1
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                val result = when (request.get("method").asString) {
                    "eth_getBalance" -> "0x123"
                    "eth_call" -> when (
                        request.getAsJsonArray("params")[0].asJsonObject.get("data").asString.take(10)
                    ) {
                        "0x8da5cb5b" -> abiAddress(holder)
                        else -> abiUint(BigInteger.ZERO)
                    }
                    else -> error("Unexpected method")
                }
                JsonParser.parseString(success(request, result))
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }

        val readiness = client.operatorReadiness(holder)

        assertEquals(false, readiness.listedOperator)
        assertEquals(holder, readiness.vaultOwner)
        assertEquals(1, requestCount)
    }

    @Test
    fun `unlisted operator readiness fails when owner is unavailable`() {
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.forEach { element ->
                    val request = element.asJsonObject
                    val selector = request.getAsJsonArray("params").firstOrNull()
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("data")
                        ?.asString
                        ?.take(10)
                    if (selector == "0x8da5cb5b") {
                        add(JsonParser.parseString(rpcError(request, -32000, "owner unavailable")))
                    } else {
                        val result = when (request.get("method").asString) {
                            "eth_getBalance" -> "0x123"
                            "eth_call" -> abiUint(BigInteger.ZERO)
                            else -> error("Unexpected method")
                        }
                        add(JsonParser.parseString(success(request, result)))
                    }
                }
            }.toString()
        }

        assertFailsWith<RpcException> { client.operatorReadiness(holder) }
    }

    @Test
    fun `batch validation rejects duplicate and missing response IDs`() {
        fun malformedBatch(removeLast: Boolean): ReadOnlyRpcClient {
            var call = 0
            return ReadOnlyRpcClient.forTest(config) { body ->
                call += 1
                val root = JsonParser.parseString(body)
                if (call == 1) {
                    return@forTest JsonArray().apply {
                        root.asJsonArray.forEach { element ->
                            val request = element.asJsonObject
                            add(
                                if (request.get("method").asString == "eth_chainId") {
                                    rpcSuccess(request, "0x14a34")
                                } else {
                                    rpcSuccess(request, blockResult(100, BLOCK_HASH))
                                },
                            )
                        }
                    }.toString()
                }
                val requests = root.asJsonArray
                val responses = requests.map { element ->
                    val request = element.asJsonObject
                    success(request, "0x60016000")
                }.toMutableList()
                if (removeLast) {
                    responses.removeLast()
                } else {
                    responses[responses.lastIndex] = responses.first()
                }
                "[${responses.joinToString(",")}]"
            }
        }

        assertFailsWith<RpcException> { malformedBatch(removeLast = false).validate(token) }
        assertFailsWith<RpcException> { malformedBatch(removeLast = true).validate(token) }
    }

    @Test
    fun `RPC response IDs must retain the exact numeric request identity`() {
        val stringIdClient = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            """{"jsonrpc":"2.0","id":"${request.get("id").asLong}","result":"0x14a34"}"""
        }
        val fractionalIdClient = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            """{"jsonrpc":"2.0","id":${request.get("id").asLong}.0,"result":"0x14a34"}"""
        }

        assertFailsWith<RpcException> { stringIdClient.chainId() }
        assertFailsWith<RpcException> { fractionalIdClient.chainId() }
    }

    @Test
    fun `RPC errors and malformed quantities fail closed`() {
        val errorClient = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            """{"jsonrpc":"2.0","id":${request.get("id").asLong},"error":{"code":-32000,"message":"reverted"}}"""
        }
        assertFailsWith<RpcResponseException> { errorClient.chainId() }

        val malformedClient = ReadOnlyRpcClient.forTest(config) { body ->
            success(JsonParser.parseString(body).asJsonObject, "0x00")
        }
        assertFailsWith<RpcException> { malformedClient.chainId() }
    }

    @Test
    fun `malformed RPC error fields fail as transport errors`() {
        val malformedErrors = listOf(
            """{"code":"-32000","message":"reverted"}""",
            """{"code":-32000.5,"message":"reverted"}""",
            """{"code":2147483648,"message":"reverted"}""",
            """{"message":"reverted"}""",
            """{"code":-32000}""",
            """{"code":-32000,"message":7}""",
        )

        malformedErrors.forEach { malformedError ->
            val client = ReadOnlyRpcClient.forTest(config) { body ->
                val request = JsonParser.parseString(body).asJsonObject
                """{"jsonrpc":"2.0","id":${request.get("id").asLong},"error":$malformedError}"""
            }

            val error = assertFailsWith<RpcException>(malformedError) { client.chainId() }
            assertTrue(error !is RpcResponseException)
        }
    }

    @Test
    fun `local argument preconditions remain argument errors`() {
        val client = ReadOnlyRpcClient.forTest(config) {
            error("RPC transport must not be reached for an invalid local argument")
        }

        assertFailsWith<IllegalArgumentException> { client.blockHash(-1) }
    }

    @Test
    fun `validation maps unavailable canonical heads to RPC failures`() {
        val unavailableAnchor = ReadOnlyRpcClient.forTest(config) { body ->
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.forEach { element ->
                    val request = element.asJsonObject
                    add(
                        if (request.get("method").asString == "eth_chainId") {
                            rpcSuccess(request, "0x14a34")
                        } else {
                            rpcNullSuccess(request)
                        },
                    )
                }
            }.toString()
        }
        assertFailsWith<RpcException> {
            unavailableAnchor.validateWithEvidence(token, 18, "AUD")
        }

        var wave = 0
        val unavailableClosingHead = ReadOnlyRpcClient.forTest(config) { body ->
            wave += 1
            val root = JsonParser.parseString(body)
            when (wave) {
                1 -> JsonArray().apply {
                    root.asJsonArray.forEach { element ->
                        val request = element.asJsonObject
                        add(
                            if (request.get("method").asString == "eth_chainId") {
                                rpcSuccess(request, "0x14a34")
                            } else {
                                rpcSuccess(request, blockResult(100, BLOCK_HASH))
                            },
                        )
                    }
                }.toString()
                2 -> JsonArray().apply {
                    root.asJsonArray.forEach { element ->
                        val request = element.asJsonObject
                        add(rpcSuccess(request, checkoutStateResult(request)))
                    }
                }.toString()
                3 -> rpcNullSuccess(root.asJsonObject).toString()
                else -> error("Unexpected validation wave")
            }
        }

        assertFailsWith<RpcException> {
            unavailableClosingHead.validateWithEvidence(token, 18, "AUD")
        }
        assertEquals(3, wave)
    }

    @Test
    fun `canonical block hash lookup rejects wrong or malformed block identity`() {
        fun client(result: JsonObject) = ReadOnlyRpcClient.forTest(config) { body ->
            success(JsonParser.parseString(body).asJsonObject, result)
        }

        assertFailsWith<RpcException> { client(blockResult(99, BLOCK_HASH)).blockHash(100) }
        assertFailsWith<RpcException> {
            client(blockResult(100, "0x1234")).blockHash(100)
        }
        val unavailable = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", request.get("id"))
                add("result", com.google.gson.JsonNull.INSTANCE)
            }.toString()
        }
        assertEquals(null, unavailable.blockHash(100))
    }

    @Test
    fun `payment observation uses three canonical waves and maps reversed batch responses by ID`() {
        val requestBodies = mutableListOf<String>()
        val head = 100L
        val cursor = 98L
        val cursorHash = blockHash(cursor)
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.reversed().map { element ->
                val rpcRequest = element.asJsonObject
                val result = when (rpcRequest.get("method").asString) {
                    "eth_chainId" -> "0x14a34"
                    "eth_call" -> abiUint(BigInteger("12340000"))
                    "eth_getBlockByNumber" -> {
                        val requested = requestedBlockNumber(rpcRequest, head)
                        blockResult(requested, blockHash(requested))
                    }
                    else -> error("Unexpected payment RPC method")
                }
                when (result) {
                    is String -> JsonParser.parseString(success(rpcRequest, result))
                    is JsonObject -> JsonParser.parseString(success(rpcRequest, result))
                    else -> error("Unexpected payment RPC result")
                }
            }
            if (root.isJsonArray) {
                JsonArray().apply { responses.forEach(::add) }.toString()
            } else {
                responses.single().toString()
            }
        }
        val payment = Erc681PaymentRequest(
            token = token,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(BigInteger("12340000"), 6),
        )
        val previous = PaymentObservation(
            token = token,
            receiver = holder,
            expectedAmount = payment.amount,
            observedRawUnits = payment.amount.rawUnits,
            blockNumber = 99,
            fundedAtBlock = cursor,
            fundedAtBlockHash = cursorHash,
            confirmations = 2,
            requiredConfirmations = 3,
            status = PaymentStatus.CONFIRMING,
        )

        val observation = PaymentObserver(client).observe(payment, previous, 3)

        assertEquals(3, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies[0]),
        )
        assertEquals(
            listOf("eth_call", "eth_getBlockByNumber"),
            requestMethods(requestBodies[1]),
        )
        assertEquals(listOf("eth_getBlockByNumber"), requestMethods(requestBodies[2]))
        val anchorRequests = JsonParser.parseString(requestBodies[0]).asJsonArray
        assertEquals("latest", anchorRequests[1].asJsonObject.getAsJsonArray("params")[0].asString)
        val sampleRequests = JsonParser.parseString(requestBodies[1]).asJsonArray
        assertEquals("0x64", sampleRequests[0].asJsonObject.getAsJsonArray("params")[1].asString)
        assertEquals("0x62", sampleRequests[1].asJsonObject.getAsJsonArray("params")[0].asString)
        assertEquals(cursor, observation.fundedAtBlock)
        assertEquals(cursorHash, observation.fundedAtBlockHash)
        assertEquals(3, observation.confirmations)
        assertEquals(PaymentStatus.PAID, observation.status)
    }

    @Test
    fun `native payment observation reads receiver ETH balance at the anchored block`() {
        val requestBodies = mutableListOf<String>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                val result: Any = when (request.get("method").asString) {
                    "eth_chainId" -> "0x14a34"
                    "eth_getBalance" -> "0x3e8"
                    "eth_getBlockByNumber" -> {
                        val requested = requestedBlockNumber(request, 100)
                        blockResult(requested, blockHash(requested))
                    }
                    else -> error("Unexpected native payment RPC method")
                }
                when (result) {
                    is String -> JsonParser.parseString(success(request, result))
                    is JsonObject -> JsonParser.parseString(success(request, result))
                    else -> error("Unexpected native payment result")
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }
        val payment = Erc681PaymentRequest(
            token = NativeAsset.address,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(BigInteger("1000"), NativeAsset.DECIMALS),
        )

        val observation = PaymentObserver(client).observe(payment)

        assertEquals(PaymentStatus.PAID, observation.status)
        assertEquals(listOf("eth_getBalance"), requestMethods(requestBodies[1]))
        val balanceParams = JsonParser.parseString(requestBodies[1])
            .asJsonObject.getAsJsonArray("params")
        assertEquals(holder.value, balanceParams[0].asString)
        assertEquals("0x64", balanceParams[1].asString)
    }

    @Test
    fun `payment observation rejects the wrong chain before any balance request`() {
        val requestBodies = mutableListOf<String>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestBodies += body
            val requests = JsonParser.parseString(body).asJsonArray
            JsonArray().apply {
                requests.reversed().forEach { element ->
                    val request = element.asJsonObject
                    val response = if (request.get("method").asString == "eth_chainId") {
                        success(request, "0x1")
                    } else {
                        success(request, blockResult(100, BLOCK_HASH))
                    }
                    add(JsonParser.parseString(response))
                }
            }.toString()
        }
        val payment = Erc681PaymentRequest(
            token = token,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(BigInteger.ONE, 6),
        )

        assertFailsWith<NetworkConfigurationException> {
            PaymentObserver(client).observe(payment)
        }

        assertEquals(1, requestBodies.size)
        assertEquals(
            listOf("eth_chainId", "eth_getBlockByNumber"),
            requestMethods(requestBodies.single()),
        )
    }

    @Test
    fun `payment observation fails closed when the anchored head changes`() {
        var requestCount = 0
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestCount += 1
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                when (request.get("method").asString) {
                    "eth_chainId" -> JsonParser.parseString(success(request, "0x14a34"))
                    "eth_call" -> JsonParser.parseString(success(request, abiUint(BigInteger.ONE)))
                    "eth_getBlockByNumber" -> JsonParser.parseString(
                        success(
                            request,
                            blockResult(100, if (requestCount == 3) OTHER_BLOCK_HASH else BLOCK_HASH),
                        ),
                    )
                    else -> error("Unexpected payment RPC method")
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }
        val payment = Erc681PaymentRequest(
            token = token,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(BigInteger.ONE, 6),
        )

        assertFailsWith<RpcException> { PaymentObserver(client).observe(payment) }
        assertEquals(3, requestCount)
    }

    @Test
    fun `payment observation invalidates a reorged saved cursor`() {
        val head = 100L
        val cursor = 98L
        val amount = BigInteger.TEN
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val root = JsonParser.parseString(body)
            val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            val responses = requests.map { element ->
                val request = element.asJsonObject
                val result = when (request.get("method").asString) {
                    "eth_chainId" -> "0x14a34"
                    "eth_call" -> abiUint(amount)
                    "eth_getBlockByNumber" -> {
                        val requested = requestedBlockNumber(request, head)
                        blockResult(
                            requested,
                            if (requested == cursor) OTHER_BLOCK_HASH else blockHash(requested),
                        )
                    }
                    else -> error("Unexpected payment RPC method")
                }
                when (result) {
                    is String -> JsonParser.parseString(success(request, result))
                    is JsonObject -> JsonParser.parseString(success(request, result))
                    else -> error("Unexpected payment RPC result")
                }
            }
            if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
            else responses.single().toString()
        }
        val payment = Erc681PaymentRequest(
            token = token,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(amount, 6),
        )
        val previous = PaymentObservation(
            token = token,
            receiver = holder,
            expectedAmount = payment.amount,
            observedRawUnits = amount,
            blockNumber = 99,
            fundedAtBlock = cursor,
            fundedAtBlockHash = blockHash(cursor),
            confirmations = 2,
            requiredConfirmations = 3,
            status = PaymentStatus.CONFIRMING,
        )

        val observation = PaymentObserver(client).observe(payment, previous, 3)

        assertEquals(head, observation.fundedAtBlock)
        assertEquals(blockHash(head), observation.fundedAtBlockHash)
        assertEquals(1, observation.confirmations)
        assertEquals(PaymentStatus.CONFIRMING, observation.status)
    }

    @Test
    fun `payment observation rejects the wrong block identity inside a batch`() {
        var requestCount = 0
        val cursor = 98L
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            requestCount += 1
            val root = JsonParser.parseString(body)
            val requests = root.asJsonArray
            if (requestCount == 1) {
                JsonArray().apply {
                    requests.forEach { element ->
                        val request = element.asJsonObject
                        val response = if (request.get("method").asString == "eth_chainId") {
                            success(request, "0x14a34")
                        } else {
                            success(request, blockResult(100, BLOCK_HASH))
                        }
                        add(JsonParser.parseString(response))
                    }
                }.toString()
            } else {
                JsonArray().apply {
                    requests.forEach { element ->
                        val request = element.asJsonObject
                        val result = if (request.get("method").asString == "eth_call") {
                            abiUint(BigInteger.ONE)
                        } else {
                            blockResult(99, BLOCK_HASH)
                        }
                        add(
                            when (result) {
                                is String -> JsonParser.parseString(success(request, result))
                                is JsonObject -> JsonParser.parseString(success(request, result))
                                else -> error("Unexpected payment RPC result")
                            },
                        )
                    }
                }.toString()
            }
        }
        val payment = Erc681PaymentRequest(
            token = token,
            chainId = config.chainId,
            receiver = holder,
            amount = TokenAmount.ofRaw(BigInteger.ONE, 6),
        )
        val previous = PaymentObservation(
            token = token,
            receiver = holder,
            expectedAmount = payment.amount,
            observedRawUnits = BigInteger.ONE,
            blockNumber = 99,
            fundedAtBlock = cursor,
            fundedAtBlockHash = blockHash(cursor),
            confirmations = 2,
            requiredConfirmations = 3,
            status = PaymentStatus.CONFIRMING,
        )

        assertFailsWith<RpcException> { PaymentObserver(client).observe(payment, previous, 3) }
        assertEquals(2, requestCount)
    }

    @Test
    fun `token decimals uses strict uint8 ABI decoding`() {
        val tooLarge = ReadOnlyRpcClient.forTest(config) { body ->
            success(JsonParser.parseString(body).asJsonObject, abiUint(BigInteger.valueOf(256)))
        }
        assertFailsWith<RpcException> { tooLarge.tokenDecimals(token) }

        val shortWord = ReadOnlyRpcClient.forTest(config) { body ->
            success(JsonParser.parseString(body).asJsonObject, "0x12")
        }
        assertFailsWith<RpcException> { shortWord.tokenDecimals(token) }
    }

    @Test
    fun `token symbol uses strict canonical dynamic string decoding`() {
        listOf("AUD", "OPK2", "₿").forEach { expected ->
            val client = ReadOnlyRpcClient.forTest(config) { body ->
                success(JsonParser.parseString(body).asJsonObject, abiString(expected))
            }
            assertEquals(expected, client.tokenSymbol(token))
        }

        val rejected = listOf(
            "0x" + "00".repeat(96),
            abiString(""),
            abiString(" "),
            abiString(" AUD"),
            abiString("\u00a0AUD"),
            abiString("AUD\u00a0"),
            abiString("A\nD"),
            abiString("A\u202eD"),
            abiString("A\u200bD"),
            abiString("A\u2028D"),
            abiString("A\u2029D"),
            abiString("A".repeat(33)),
            abiString("AUD").replaceRange(2, 66, "00".repeat(31) + "40"),
            abiString("AUD") + "00".repeat(32),
            abiString("AUD").dropLast(2) + "01",
            "0x" + abiUint(BigInteger.valueOf(32)).substring(2) +
                abiUint(BigInteger.ONE).substring(2) + "ff" + "00".repeat(31),
        )
        rejected.forEach { result ->
            val client = ReadOnlyRpcClient.forTest(config) { body ->
                success(JsonParser.parseString(body).asJsonObject, result)
            }
            assertFailsWith<RpcException>(result.take(24)) { client.tokenSymbol(token) }
        }
    }

    private fun success(request: JsonObject, result: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        addProperty("result", result)
    }.toString()

    private fun success(request: JsonObject, result: JsonObject): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        add("result", result)
    }.toString()

    private fun rpcSuccess(request: JsonObject, result: String) =
        JsonParser.parseString(success(request, result))

    private fun rpcSuccess(request: JsonObject, result: JsonObject) =
        JsonParser.parseString(success(request, result))

    private fun rpcNullSuccess(request: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        add("result", com.google.gson.JsonNull.INSTANCE)
    }

    private fun checkoutStateResult(request: JsonObject): String =
        when (request.get("method").asString) {
            "eth_getCode" -> {
                val address = request.getAsJsonArray("params")[0].asString
                if (address == holder.value) "0x" else "0x60016000"
            }
            "eth_getBalance" -> if (
                request.getAsJsonArray("params")[1].asString == "pending"
            ) {
                "0x1"
            } else {
                "0x100000000000000"
            }
            "eth_call" -> when (
                request.getAsJsonArray("params")[0].asJsonObject
                    .get("data").asString.take(10)
            ) {
                "0x5c60da1b" -> abiAddress(implementation)
                "0xc45a0155" -> abiAddress(factory)
                "0x313ce567" -> abiUint(BigInteger.valueOf(18))
                "0x95d89b41" -> abiString("AUD")
                "0x8da5cb5b" -> abiAddress(holder)
                "0x70a08231" -> abiUint(BigInteger.ZERO)
                else -> abiUint(BigInteger.ONE)
            }
            else -> error("Unexpected checkout state method")
        }

    private fun checkoutBlockTag(body: String): String {
        val root = JsonParser.parseString(body)
        val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        return requests.single { it.asJsonObject.get("method").asString == "eth_getBlockByNumber" }
            .asJsonObject.getAsJsonArray("params")[0].asString
    }

    private fun rpcError(request: JsonObject, code: Int, message: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }.toString()

    private fun blockResult(number: Long, hash: String): JsonObject = JsonObject().apply {
        addProperty("number", "0x" + number.toString(16))
        addProperty("hash", hash)
    }

    private fun requestMethods(body: String): List<String> {
        val root = JsonParser.parseString(body)
        val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        return requests.map { it.asJsonObject.get("method").asString }
    }

    private fun requestedBlockNumber(request: JsonObject, latestBlock: Long): Long {
        val tag = request.getAsJsonArray("params")[0].asString
        return if (tag == "latest") latestBlock else tag.removePrefix("0x").toLong(16)
    }

    private fun blockHash(block: Long): String =
        "0x" + block.toString(16).padStart(64, '0')

    private fun abiAddress(address: EvmAddress): String =
        "0x" + "00".repeat(12) + address.value.substring(2)

    private fun abiUint(value: BigInteger): String =
        "0x" + value.toString(16).padStart(64, '0')

    private fun abiString(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val padding = (32 - bytes.size % 32) % 32
        return abiUint(BigInteger.valueOf(32)) +
            abiUint(BigInteger.valueOf(bytes.size.toLong())).substring(2) +
            Hex.encode(bytes).substring(2) + "00".repeat(padding)
    }

    private companion object {
        const val BLOCK_HASH =
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_BLOCK_HASH =
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}
