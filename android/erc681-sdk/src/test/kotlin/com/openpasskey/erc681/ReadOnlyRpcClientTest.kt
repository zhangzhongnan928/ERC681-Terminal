package com.openpasskey.erc681

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlyRpcClientTest {
    private val factory = EvmAddress.parse("0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5")
    private val implementation = EvmAddress.parse("0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc")
    private val vault = EvmAddress.parse("0x1ed67E540E6AB92dC3537A7bba3BcAb6FdD69Da1")
    private val token = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
    private val holder = EvmAddress.parse("0x9107decd2cb06c57c40a663648e19cde1d52f606")
    private val config = NetworkConfig(84532, "https://rpc.example.invalid", factory, implementation, vault)

    @Test
    fun `client exposes only decoded read methods`() {
        val seen = mutableListOf<JsonObject>()
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject.also(seen::add)
            val method = request.get("method").asString
            val result = when (method) {
                "eth_chainId" -> "0x14a34"
                "eth_blockNumber" -> "0x64"
                "eth_getCode" -> "0x60016000"
                "eth_call" -> when (request.getAsJsonArray("params")[0].asJsonObject.get("data").asString.take(10)) {
                    "0x5c60da1b" -> abiAddress(implementation)
                    "0xc45a0155" -> abiAddress(factory)
                    "0x930eaddc" -> abiUint(BigInteger.ONE)
                    "0x313ce567" -> abiUint(BigInteger.valueOf(18))
                    "0x70a08231" -> abiUint(BigInteger("12340000"))
                    else -> error("Unexpected eth_call selector")
                }
                else -> error("Unexpected method $method")
            }
            success(request, result)
        }

        assertEquals(84532, client.chainId())
        assertEquals(100, client.blockNumber())
        assertContentEquals(Hex.decode("0x60016000"), client.codeAt(factory))
        assertEquals(implementation, client.factoryImplementation())
        assertEquals(factory, client.vaultFactory())
        assertTrue(client.isPaymentToken(token))
        assertEquals(18, client.tokenDecimals(token))
        assertEquals(BigInteger("12340000"), client.tokenBalance(token, holder, 100))
        val balanceRequest = seen.last().getAsJsonArray("params")
        assertEquals("0x64", balanceRequest[1].asString)
        assertEquals(token.value, balanceRequest[0].asJsonObject.get("to").asString)
    }

    @Test
    fun `validation rejects an RPC chain mismatch before contract checks`() {
        val client = ReadOnlyRpcClient.forTest(config) { body ->
            val request = JsonParser.parseString(body).asJsonObject
            success(request, "0x1")
        }

        val error = assertFailsWith<NetworkConfigurationException> { client.validate(token) }
        assertTrue(error.message.orEmpty().contains("does not match"))
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
        assertFailsWith<IllegalArgumentException> { malformedClient.chainId() }
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

    private fun success(request: JsonObject, result: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        addProperty("result", result)
    }.toString()

    private fun abiAddress(address: EvmAddress): String =
        "0x" + "00".repeat(12) + address.value.substring(2)

    private fun abiUint(value: BigInteger): String =
        "0x" + value.toString(16).padStart(64, '0')
}
