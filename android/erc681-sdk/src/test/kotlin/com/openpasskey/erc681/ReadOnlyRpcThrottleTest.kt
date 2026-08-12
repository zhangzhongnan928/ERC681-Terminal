// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadOnlyRpcThrottleTest {
    private val factory = EvmAddress.parse("0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f")
    private val implementation = EvmAddress.parse("0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18")
    private val vault = EvmAddress.parse("0x1111111111111111111111111111111111111111")
    private val token = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
    private val operator = EvmAddress.parse("0xbbd352de4428d535ac79849abefa8d69bb51c671")
    private val config = NetworkConfig(84532, "https://rpc.example.invalid", factory, implementation, vault)

    @Test
    fun `transport failure does not retain credential-bearing exception text or cause`() {
        val secret = "terminal-client-key"
        val client = ReadOnlyRpcClient.forTest(config) {
            throw java.net.UnknownHostException("$secret.rpc-provider.example")
        }

        val error = assertFailsWith<RpcException> { client.chainId() }

        assertEquals("JSON-RPC transport failed", error.message)
        assertNull(error.cause)
        assertFalse(error.toString().contains(secret))
    }

    @Test
    fun `validation retries a transient HTTP throttle and honors bounded Retry-After`() {
        val requestBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        var throttled = false
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            requestBodies += body
            if (!throttled) {
                throttled = true
                throw RpcHttpRateLimitException(retryAfterMillis = 2_000L)
            }
            successfulValidationResponse(body)
        }

        val validation = client.validate(token, 18, "AUD", retryOnThrottle = true)

        assertEquals(token, validation.token)
        assertEquals(listOf(2_000L), delays)
        assertEquals(requestBodies[0], requestBodies[1])
        assertEquals(4, requestBodies.size)
    }

    @Test
    fun `validation retries the same body twice then exposes persistent throttle`() {
        val requestBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val jitter = ArrayDeque(listOf(13L, 999L))
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { jitter.removeFirst() },
        ) { body ->
            requestBodies += body
            partialThrottleResponse(body, -32016, "over rate limit")
        }

        val error = assertFailsWith<RpcRateLimitResponseException> {
            client.validateWithEvidence(token, 18, "AUD", retryOnThrottle = true)
        }

        assertEquals(-32016, error.rpcCode)
        assertEquals(listOf(1_013L, 3_250L), delays)
        assertEquals(3, requestBodies.size)
        assertTrue(requestBodies.all { it == requestBodies.first() })
    }

    @Test
    fun `non-throttle JSON-RPC errors preserve response type and are not retried`() {
        val requestBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            requestBodies += body
            partialThrottleResponse(body, -32016, "execution failed")
        }

        val error = assertFailsWith<RpcResponseException> {
            client.validate(token, 18, "AUD", retryOnThrottle = true)
        }

        assertEquals(-32016, error.rpcCode)
        assertEquals("JSON-RPC error -32016", error.message)
        assertEquals(1, requestBodies.size)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `JSON-RPC exceptions never retain provider controlled credential text`() {
        val secret = "https://terminal-client-key.rpc-provider.example/base"
        val error = RpcResponseException(-32000, secret)

        assertEquals("JSON-RPC error -32000", error.message)
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
    }

    @Test
    fun `validation does not retry a throttle unless explicitly enabled`() {
        var calls = 0
        val delays = mutableListOf<Long>()
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            calls += 1
            partialThrottleResponse(body, -32016, "over rate limit")
        }

        assertFailsWith<RpcRateLimitResponseException> {
            client.validateWithEvidence(token, 18, "AUD")
        }

        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `partial validation batch throttle retries the complete fixed batch`() {
        val stateBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        var throttledState = false
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            val methods = requestMethods(body)
            if (methods.size == 9) {
                stateBodies += body
                if (!throttledState) {
                    throttledState = true
                    return@forTest partialThrottleResponse(body, -32016, "over rate limit", 4)
                }
            }
            successfulValidationResponse(body)
        }

        val validation = client.validate(token, 18, "AUD", retryOnThrottle = true)

        assertEquals(token, validation.token)
        assertEquals(listOf(1_000L), delays)
        assertEquals(2, stateBodies.size)
        assertEquals(stateBodies[0], stateBodies[1])
    }

    @Test
    fun `rate-limit classification is narrow and does not retry ordinary reads`() {
        var calls = 0
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            calls += 1
            val request = JsonParser.parseString(body).asJsonObject
            rpcError(request, -32005, "project rate limit exceeded").toString()
        }

        val error = assertFailsWith<RpcResponseException> { client.chainId() }

        assertEquals(-32005, error.rpcCode)
        assertTrue(error is RpcRateLimit)
        assertEquals(1, calls)

        val ordinary = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            rpcError(request, -32005, "request rejected").toString()
        }
        assertFailsWith<RpcResponseException> { ordinary.chainId() }
    }

    @Test
    fun `operator readiness retries one transient throttle when enabled`() {
        val requestBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        var throttled = false
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            requestBodies += body
            if (!throttled) {
                throttled = true
                readinessResponse(body, throttleIndex = 1)
            } else {
                readinessResponse(body)
            }
        }

        val readiness = client.operatorReadiness(operator, retryOnThrottle = true)

        assertTrue(readiness.listedOperator)
        assertEquals(operator, readiness.vaultOwner)
        assertEquals(BigInteger.valueOf(100), readiness.nativeBalance)
        assertEquals(listOf(1_000L), delays)
        assertEquals(2, requestBodies.size)
        assertEquals(requestBodies[0], requestBodies[1])
    }

    @Test
    fun `operator readiness exhausts two retries with the same body`() {
        val requestBodies = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            requestBodies += body
            readinessResponse(body, throttleIndex = 0)
        }

        assertFailsWith<RpcRateLimitResponseException> {
            client.operatorReadiness(operator, retryOnThrottle = true)
        }

        assertEquals(listOf(1_000L, 3_000L), delays)
        assertEquals(3, requestBodies.size)
        assertTrue(requestBodies.all { it == requestBodies.first() })
    }

    @Test
    fun `operator readiness defaults to no throttle retry`() {
        var calls = 0
        val delays = mutableListOf<Long>()
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = delays::add,
            retryJitterMillis = { 0L },
        ) { body ->
            calls += 1
            readinessResponse(body, throttleIndex = 2)
        }

        assertFailsWith<RpcRateLimitResponseException> {
            client.operatorReadiness(operator)
        }
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `HTTP 429 is typed and Retry-After parsing is bounded`() {
        val now = 1_445_412_478_000L
        val seconds = rpcHttpFailure(429, "2", now)
        assertTrue(seconds is RpcRateLimit)
        assertTrue(seconds is RpcHttpRateLimitException)
        assertEquals(429, seconds.httpStatus)
        assertEquals(2_000L, seconds.retryAfterMillis)

        val date = rpcHttpFailure(429, "Wed, 21 Oct 2015 07:28:00 GMT", now)
        assertTrue(date is RpcHttpRateLimitException)
        assertEquals(2_000L, date.retryAfterMillis)

        val bounded = rpcHttpFailure(429, "120", now)
        assertTrue(bounded is RpcHttpRateLimitException)
        assertEquals(5_000L, bounded.retryAfterMillis)

        val invalid = rpcHttpFailure(429, "soon", now)
        assertTrue(invalid is RpcHttpRateLimitException)
        assertEquals(null, invalid.retryAfterMillis)

        assertTrue(rpcHttpFailure(503, "2", now) !is RpcRateLimit)
    }

    @Test
    fun `interruption escapes validation retry without another request`() {
        var calls = 0
        val client = ReadOnlyRpcClient.forTest(
            config = config,
            retrySleep = { throw InterruptedException("cancelled") },
            retryJitterMillis = { 0L },
        ) { body ->
            calls += 1
            partialThrottleResponse(body, -32016, "over rate limit")
        }

        assertFailsWith<InterruptedException> {
            client.validateWithEvidence(token, 18, "AUD", retryOnThrottle = true)
        }
        assertEquals(1, calls)
    }

    private fun successfulValidationResponse(body: String): String {
        val root = JsonParser.parseString(body)
        val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        val responses = requests.map { element ->
            val request = element.asJsonObject
            when (request.get("method").asString) {
                "eth_chainId" -> rpcSuccess(request, "0x14a34")
                "eth_getBlockByNumber" -> rpcSuccess(request, blockResult(100, BLOCK_HASH))
                "eth_getCode" -> rpcSuccess(request, "0x60016000")
                "eth_call" -> rpcSuccess(request, validationCallResult(request))
                else -> error("Unexpected validation method ${request.get("method").asString}")
            }
        }
        return if (root.isJsonArray) {
            JsonArray().apply { responses.forEach(::add) }.toString()
        } else {
            responses.single().toString()
        }
    }

    private fun readinessResponse(body: String, throttleIndex: Int? = null): String {
        val requests = JsonParser.parseString(body).asJsonArray
        return JsonArray().apply {
            requests.forEachIndexed { index, element ->
                val request = element.asJsonObject
                add(
                    if (index == throttleIndex) {
                        rpcError(request, -32016, "over rate limit")
                    } else {
                        readinessSuccess(request)
                    },
                )
            }
        }.toString()
    }

    private fun readinessSuccess(request: JsonObject): JsonObject = when (
        request.get("method").asString
    ) {
        "eth_getBalance" -> rpcSuccess(request, "0x64")
        "eth_call" -> {
            val selector = request.getAsJsonArray("params")[0].asJsonObject
                .get("data").asString.take(10)
            rpcSuccess(
                request,
                if (selector == "0x8da5cb5b") abiAddress(operator) else abiUint(BigInteger.ONE),
            )
        }
        else -> error("Unexpected readiness method ${request.get("method").asString}")
    }

    private fun partialThrottleResponse(
        body: String,
        code: Int,
        message: String,
        errorIndex: Int = 1,
    ): String {
        val root = JsonParser.parseString(body)
        val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        return if (root.isJsonArray) {
            JsonArray().apply {
                requests.forEachIndexed { index, element ->
                    val request = element.asJsonObject
                    add(
                        if (index == errorIndex.coerceAtMost(requests.lastIndex)) {
                            rpcError(request, code, message)
                        } else {
                            successfulElement(request)
                        },
                    )
                }
            }.toString()
        } else {
            rpcError(requests.single().asJsonObject, code, message).toString()
        }
    }

    private fun successfulElement(request: JsonObject): JsonObject = when (
        request.get("method").asString
    ) {
        "eth_chainId" -> rpcSuccess(request, "0x14a34")
        "eth_getBlockByNumber" -> rpcSuccess(request, blockResult(100, BLOCK_HASH))
        "eth_getCode" -> rpcSuccess(request, "0x60016000")
        "eth_call" -> rpcSuccess(request, validationCallResult(request))
        else -> error("Unexpected validation method ${request.get("method").asString}")
    }

    private fun validationCallResult(request: JsonObject): String = when (
        request.getAsJsonArray("params")[0].asJsonObject.get("data").asString.take(10)
    ) {
        "0x5c60da1b" -> abiAddress(implementation)
        "0xc45a0155" -> abiAddress(factory)
        "0x930eaddc" -> abiUint(BigInteger.ONE)
        "0x313ce567" -> abiUint(BigInteger.valueOf(18))
        "0x95d89b41" -> abiString("AUD")
        else -> error("Unexpected validation selector")
    }

    private fun rpcSuccess(request: JsonObject, result: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        addProperty("result", result)
    }

    private fun rpcSuccess(request: JsonObject, result: JsonObject): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        add("result", result)
    }

    private fun rpcError(request: JsonObject, code: Int, message: String): JsonObject =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", request.get("id"))
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }

    private fun blockResult(number: Long, hash: String): JsonObject = JsonObject().apply {
        addProperty("number", "0x" + number.toString(16))
        addProperty("hash", hash)
    }

    private fun requestMethods(body: String): List<String> {
        val root = JsonParser.parseString(body)
        val requests: List<JsonElement> = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        return requests.map { it.asJsonObject.get("method").asString }
    }

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
    }
}
