package com.openpasskey.erc681

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal JSON-RPC client for OPK terminal reads. It intentionally has no transaction,
 * signing, nonce, gas, raw-call, or arbitrary-method API.
 */
class ReadOnlyRpcClient private constructor(
    val config: NetworkConfig,
    private val transport: RpcTransport,
) : ReadOnlyChainClient {
    @JvmOverloads
    constructor(
        config: NetworkConfig,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ) : this(
        config = config,
        transport = HttpUrlConnectionRpcTransport(
            config.rpcUrl,
            checkedTimeout(connectTimeoutMillis),
            checkedTimeout(readTimeoutMillis),
        ),
    )

    override fun chainId(): Long = rpcQuantity("eth_chainId").toSupportedLong("Chain ID")

    override fun blockNumber(): Long = rpcQuantity("eth_blockNumber").toSupportedLong("Block number")

    override fun blockHash(blockNumber: Long): String? {
        require(blockNumber >= 0) { "Block number must not be negative" }
        val params = JsonArray().apply {
            add(quantityHex(blockNumber))
            add(false)
        }
        val result = rpcResult("eth_getBlockByNumber", params)
        if (result.isJsonNull) return null
        if (!result.isJsonObject) throw RpcException("eth_getBlockByNumber result must be an object or null")
        val block = result.asJsonObject
        val returnedNumber = block.get("number")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: throw RpcException("eth_getBlockByNumber result has no block number")
        val decodedNumber = try {
            parseQuantity(returnedNumber, "Block number").toSupportedLong("Block number")
        } catch (error: IllegalArgumentException) {
            throw RpcException("eth_getBlockByNumber returned a malformed block number", error)
        }
        if (decodedNumber != blockNumber) {
            throw RpcException("eth_getBlockByNumber returned block $decodedNumber for requested block $blockNumber")
        }
        val hash = block.get("hash")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: throw RpcException("eth_getBlockByNumber result has no block hash")
        if (!BLOCK_HASH_PATTERN.matches(hash)) {
            throw RpcException("eth_getBlockByNumber returned a malformed block hash")
        }
        return hash.lowercase()
    }

    override fun codeAt(address: EvmAddress): ByteArray {
        val params = JsonArray().apply {
            add(address.value)
            add(LATEST_BLOCK)
        }
        return decodeData(rpcString("eth_getCode", params), "eth_getCode result")
    }

    override fun factoryImplementation(): EvmAddress = factoryImplementation(config.factory)

    /** Fixed-selector bootstrap read for a policy-pinned factory. */
    fun factoryImplementation(factory: EvmAddress): EvmAddress {
        require(!factory.isZero) { "Factory address must not be zero" }
        return decodeAddress(ethCall(factory, abiFunction("implementation()")), "factory implementation")
    }

    override fun vaultFactory(): EvmAddress = vaultFactory(config.vault)

    /** Fixed-selector bootstrap read for the vault supplied by a provisioning payload. */
    fun vaultFactory(vault: EvmAddress): EvmAddress {
        require(!vault.isZero) { "Vault address must not be zero" }
        return decodeAddress(ethCall(vault, abiFunction("factory()")), "vault factory")
    }

    override fun isPaymentToken(token: EvmAddress): Boolean = isPaymentToken(config.vault, token)

    /** Fixed-selector bootstrap read scoped to a supplied vault and token. */
    fun isPaymentToken(vault: EvmAddress, token: EvmAddress): Boolean {
        require(!vault.isZero) { "Vault address must not be zero" }
        require(!token.isZero) { "Token address must not be zero" }
        val result = decodeWord(
            ethCall(vault, abiFunction("isPaymentToken(address)", token)),
            "isPaymentToken result",
        )
        return when (BigInteger(1, result)) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw RpcException("isPaymentToken returned a non-boolean ABI word")
        }
    }

    override fun tokenDecimals(token: EvmAddress): Int {
        val value = BigInteger(
            1,
            decodeWord(ethCall(token, abiFunction("decimals()")), "decimals result"),
        )
        if (value > UINT8_MAX) throw RpcException("decimals returned a value outside uint8")
        return value.toInt()
    }

    override fun tokenSymbol(token: EvmAddress): String {
        require(!token.isZero) { "Token address must not be zero" }
        return decodeTokenSymbol(ethCall(token, abiFunction("symbol()")))
    }

    override fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long?): BigInteger {
        require(blockNumber == null || blockNumber >= 0) { "Block number must not be negative" }
        val result = ethCall(
            to = token,
            data = abiFunction("balanceOf(address)", holder),
            blockTag = blockNumber?.let(::quantityHex) ?: LATEST_BLOCK,
        )
        return BigInteger(1, decodeWord(result, "balanceOf result"))
    }

    /** Fails closed if the configured chain, contracts, factory links, or token whitelist differ. */
    @JvmOverloads
    fun validate(
        token: EvmAddress,
        expectedDecimals: Int? = null,
        expectedSymbol: String? = null,
    ): NetworkValidation {
        require(!token.isZero) { "Token address must not be zero" }
        require(expectedDecimals == null || expectedDecimals in 0..255) {
            "Expected token decimals must be between 0 and 255"
        }
        val remoteChainId = chainId()
        if (remoteChainId != config.chainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match configured chain ID ${config.chainId}",
            )
        }
        requireContract(config.factory, "factory")
        requireContract(config.receiverImplementation, "receiver implementation")
        requireContract(config.vault, "vault")
        requireContract(token, "token")

        val actualImplementation = factoryImplementation()
        if (actualImplementation != config.receiverImplementation) {
            throw NetworkConfigurationException(
                "Factory implementation $actualImplementation does not match configured receiver implementation " +
                    config.receiverImplementation,
            )
        }
        val actualFactory = vaultFactory()
        if (actualFactory != config.factory) {
            throw NetworkConfigurationException(
                "Vault factory $actualFactory does not match configured factory ${config.factory}",
            )
        }
        if (!isPaymentToken(token)) {
            throw NetworkConfigurationException("Token $token is not whitelisted by vault ${config.vault}")
        }
        val actualDecimals = tokenDecimals(token)
        if (expectedDecimals != null && actualDecimals != expectedDecimals) {
            throw NetworkConfigurationException(
                "Token decimals $actualDecimals do not match configured decimals $expectedDecimals",
            )
        }
        val actualSymbol = tokenSymbol(token)
        if (expectedSymbol != null && actualSymbol != expectedSymbol) {
            throw NetworkConfigurationException(
                "Token symbol $actualSymbol does not match configured symbol $expectedSymbol",
            )
        }

        return NetworkValidation(
            chainId = remoteChainId,
            factory = config.factory,
            receiverImplementation = actualImplementation,
            vault = config.vault,
            token = token,
            tokenWhitelisted = true,
            tokenDecimals = actualDecimals,
            tokenSymbol = actualSymbol,
        )
    }

    private fun requireContract(address: EvmAddress, label: String) {
        if (codeAt(address).isEmpty()) {
            throw NetworkConfigurationException("Configured $label $address has no deployed code")
        }
    }

    private fun ethCall(to: EvmAddress, data: String, blockTag: String = LATEST_BLOCK): String {
        val call = JsonObject().apply {
            addProperty("to", to.value)
            addProperty("data", data)
        }
        val params = JsonArray().apply {
            add(call)
            add(blockTag)
        }
        return rpcString("eth_call", params)
    }

    private fun rpcQuantity(method: String): BigInteger = parseQuantity(rpcString(method, JsonArray()), method)

    private fun rpcString(method: String, params: JsonArray): String {
        val result = rpcResult(method, params)
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isString) {
            throw RpcException("JSON-RPC result must be a string")
        }
        return result.asString
    }

    private fun rpcResult(method: String, params: JsonArray): JsonElement {
        val id = requestIds.incrementAndGet()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val responseText = try {
            transport.execute(request.toString())
        } catch (error: RpcException) {
            throw error
        } catch (error: Exception) {
            throw RpcException("JSON-RPC transport failed", error)
        }

        val response = try {
            JsonParser.parseString(responseText).asJsonObject
        } catch (error: Exception) {
            throw RpcException("JSON-RPC response is not a JSON object", error)
        }
        if (response.get("jsonrpc")?.asString != "2.0") {
            throw RpcException("JSON-RPC response has an invalid version")
        }
        if (response.get("id")?.asLong != id) {
            throw RpcException("JSON-RPC response ID does not match the request")
        }

        val error = response.getAsJsonObject("error")
        if (error != null) {
            val code = error.get("code")?.asInt ?: 0
            val message = error.get("message")?.asString ?: "Unknown RPC error"
            throw RpcResponseException(code, message)
        }
        val result = response.get("result")
            ?: throw RpcException("JSON-RPC response is missing result")
        return result
    }

    private fun abiFunction(signature: String, address: EvmAddress? = null): String {
        val selector = Keccak256.digest(signature.toByteArray(StandardCharsets.US_ASCII)).copyOfRange(0, 4)
        if (address == null) return Hex.encode(selector)
        val encoded = ByteArray(36)
        selector.copyInto(encoded)
        address.toByteArray().copyInto(encoded, destinationOffset = 16)
        return Hex.encode(encoded)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 15_000
        private const val LATEST_BLOCK = "latest"
        private val UINT8_MAX = BigInteger.valueOf(255)
        private val requestIds = AtomicLong()

        /** Test seam kept out of the published Java and Kotlin API. */
        @JvmSynthetic
        internal fun forTest(
            config: NetworkConfig,
            execute: (String) -> String,
        ): ReadOnlyRpcClient = ReadOnlyRpcClient(config, RpcTransport(execute))

        private fun checkedTimeout(value: Int): Int {
            require(value > 0) { "RPC timeout must be greater than zero" }
            return value
        }

        private fun quantityHex(value: Long): String = "0x" + value.toString(16)

        private fun parseQuantity(value: String, label: String): BigInteger {
            require(QUANTITY_PATTERN.matches(value)) { "$label returned a malformed hex quantity" }
            return BigInteger(value.substring(2), 16)
        }

        private fun BigInteger.toSupportedLong(label: String): Long {
            if (signum() < 0 || bitLength() > 63) {
                throw RpcException("$label is outside the supported signed 64-bit range")
            }
            return toLong()
        }

        private fun decodeData(value: String, label: String): ByteArray = try {
            Hex.decode(value)
        } catch (error: IllegalArgumentException) {
            throw RpcException("$label is malformed", error)
        }

        private fun decodeWord(value: String, label: String): ByteArray {
            val bytes = decodeData(value, label)
            if (bytes.size != 32) throw RpcException("$label must contain exactly one 32-byte ABI word")
            return bytes
        }

        private fun decodeAddress(value: String, label: String): EvmAddress {
            val word = decodeWord(value, label)
            if (word.copyOfRange(0, 12).any { it != 0.toByte() }) {
                throw RpcException("$label has non-zero ABI address padding")
            }
            return EvmAddress.fromBytes(word.copyOfRange(12, 32))
        }

        private fun decodeTokenSymbol(value: String): String {
            val bytes = decodeData(value, "symbol result")
            if (bytes.size < 96 || bytes.size % 32 != 0) {
                throw RpcException("symbol result is not canonical dynamic-string ABI data")
            }
            val offset = BigInteger(1, bytes.copyOfRange(0, 32))
            if (offset != BigInteger.valueOf(32)) {
                throw RpcException("symbol result has a non-canonical ABI offset")
            }
            val lengthValue = BigInteger(1, bytes.copyOfRange(32, 64))
            if (lengthValue.signum() <= 0 || lengthValue > BigInteger.valueOf(MAX_SYMBOL_UTF8_BYTES.toLong())) {
                throw RpcException("symbol must contain 1 to $MAX_SYMBOL_UTF8_BYTES UTF-8 bytes")
            }
            val length = lengthValue.toInt()
            val paddedLength = ((length + 31) / 32) * 32
            if (bytes.size != 64 + paddedLength) {
                throw RpcException("symbol result has a non-canonical ABI length")
            }
            if (bytes.copyOfRange(64 + length, bytes.size).any { it != 0.toByte() }) {
                throw RpcException("symbol result has non-zero ABI padding")
            }
            val symbol = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 64, length))
                    .toString()
            } catch (error: Exception) {
                throw RpcException("symbol is not strict UTF-8", error)
            }
            val codePoints = symbol.codePoints().toArray()
            val isDisplayWhitespace: (Int) -> Boolean = { codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            }
            if (
                codePoints.all(isDisplayWhitespace) ||
                isDisplayWhitespace(codePoints.first()) ||
                isDisplayWhitespace(codePoints.last())
            ) {
                throw RpcException("symbol must not be blank or have surrounding whitespace")
            }
            val unsafe = symbol.codePoints().anyMatch { codePoint ->
                Character.getType(codePoint) in UNSAFE_UNICODE_CATEGORIES
            }
            if (unsafe) {
                throw RpcException(
                    "symbol contains a Unicode control, format, line-separator, or paragraph-separator character",
                )
            }
            return symbol
        }

        private val QUANTITY_PATTERN = Regex("^0x(0|[1-9a-fA-F][0-9a-fA-F]*)$")
        private val BLOCK_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
        private const val MAX_SYMBOL_UTF8_BYTES = 32
        private val UNSAFE_UNICODE_CATEGORIES = setOf(
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
        )
    }
}

private fun interface RpcTransport {
    fun execute(requestBody: String): String
}

private class HttpUrlConnectionRpcTransport(
    private val rpcUrl: String,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) : RpcTransport {
    override fun execute(requestBody: String): String {
        val body = requestBody.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(body.size)
        }

        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use(::readLimitedUtf8).orEmpty()
            if (status !in 200..299) {
                throw RpcException("RPC HTTP request failed with status $status")
            }
            if (response.isEmpty()) throw RpcException("RPC HTTP response body is empty")
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw RpcException("RPC HTTP response exceeds size limit")
            output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
