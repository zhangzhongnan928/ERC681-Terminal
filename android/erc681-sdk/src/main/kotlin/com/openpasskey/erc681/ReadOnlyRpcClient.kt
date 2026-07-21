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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class OperatorReadiness(
    val listedOperator: Boolean,
    /**
     * The decoded owner, or zero when `owner()` was unavailable after `isOperator` independently
     * proved this operator is listed. A vault that has actually renounced ownership also returns
     * zero, so callers must not use zero alone to distinguish those two cases.
    */
    val vaultOwner: EvmAddress,
    /** Conservative readiness balance: minimum of anchored latest and pending when both are read. */
    val nativeBalance: BigInteger,
) {
    /** True only when [vaultOwner] is a usable non-zero authorization fallback. */
    val hasNonZeroVaultOwner: Boolean
        get() = !vaultOwner.isZero
}

data class ReceiverFreshness(
    val deployedCode: ByteArray,
    val tokenBalance: BigInteger,
)

/** Narrow proof material returned from the same strict validation batch, with defensive bytes. */
class NetworkValidationEvidence internal constructor(
    val validation: NetworkValidation,
    vaultRuntimeCode: ByteArray,
) {
    private val retainedVaultRuntimeCode = vaultRuntimeCode.copyOf()
    val vaultRuntimeCode: ByteArray
        get() = retainedVaultRuntimeCode.copyOf()
}

/**
 * One canonical checkout proof. Contract/receiver state and the latest operator balance were read
 * at [blockNumber], then bracketed by the unchanged [blockHash]. Gas readiness additionally folds
 * in the pending operator balance and exposes the conservative minimum.
 */
class CheckoutValidationEvidence internal constructor(
    val validation: NetworkValidation,
    val operatorReadiness: OperatorReadiness,
    val receiverFreshness: ReceiverFreshness,
    val blockNumber: Long,
    val blockHash: String,
)

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
        val result = rpcResult("eth_getBlockByNumber", blockByNumberParams(blockNumber))
        return decodeCanonicalBlockHash(result, blockNumber)
    }

    /**
     * Narrow payment-only sampler. It intentionally does not expose arbitrary batch RPC calls.
     *
     * Wave 1 anchors the expected chain and latest block identity. Wave 2 reads the balance at that
     * exact height plus an optional saved confirmation cursor. Wave 3 brackets the sample with the
     * same canonical block identity so a reorg cannot produce a mixed observation.
     */
    internal fun samplePaymentObservation(
        expectedChainId: Long,
        token: EvmAddress,
        holder: EvmAddress,
        savedCursorBlock: Long?,
    ): PaymentReadSample {
        require(expectedChainId >= 0) { "Expected chain ID must not be negative" }
        require(!token.isZero) { "Token address must not be zero" }
        require(!holder.isZero) { "Holder address must not be zero" }
        require(savedCursorBlock == null || savedCursorBlock >= 0) {
            "Saved cursor block must not be negative"
        }

        val anchor = rpcResults(
            listOf(
                RpcCall("eth_chainId", JsonArray()),
                RpcCall("eth_getBlockByNumber", blockByTagParams(LATEST_BLOCK)),
            ),
        )
        val remoteChainId = parseQuantity(resultString(anchor[0]), "eth_chainId")
            .toSupportedLong("Chain ID")
        if (remoteChainId != expectedChainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match payment chain ID $expectedChainId",
            )
        }
        val anchoredHead = requireNotNull(decodeCanonicalBlockIdentity(anchor[1])) {
            "Latest canonical block is unavailable"
        }
        val head = anchoredHead.number

        val cursorToRead = savedCursorBlock?.takeIf { it < head }
        val sampleCalls = buildList {
            add(
                RpcCall(
                    "eth_call",
                    ethCallParams(
                        token,
                        abiFunction("balanceOf(address)", holder),
                        quantityHex(head),
                    ),
                ),
            )
            cursorToRead?.let { cursor ->
                add(RpcCall("eth_getBlockByNumber", blockByNumberParams(cursor)))
            }
        }
        val sampled = rpcResults(sampleCalls)
        val balance = BigInteger(
            1,
            decodeWord(resultString(sampled[0]), "balanceOf result"),
        )
        val cursorHash = if (cursorToRead != null) {
            decodeCanonicalBlockHash(sampled[1], cursorToRead)
        } else {
            null
        }

        val finalHeadHash = requireNotNull(
            decodeCanonicalBlockHash(
                rpcResult("eth_getBlockByNumber", blockByNumberParams(head)),
                head,
            ),
        ) {
            "Canonical block $head became unavailable after validating confirmation cursors"
        }
        if (!finalHeadHash.equals(anchoredHead.hash, ignoreCase = true)) {
            throw RpcException("Canonical block $head changed while sampling payment balance")
        }
        return PaymentReadSample(
            blockNumber = head,
            blockHash = finalHeadHash,
            balance = balance,
            savedCursorHash = if (savedCursorBlock == head) finalHeadHash else cursorHash,
        )
    }

    private fun decodeCanonicalBlockIdentity(result: JsonElement): CanonicalBlockIdentity? {
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
        val hash = block.get("hash")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: throw RpcException("eth_getBlockByNumber result has no block hash")
        if (!BLOCK_HASH_PATTERN.matches(hash)) {
            throw RpcException("eth_getBlockByNumber returned a malformed block hash")
        }
        return CanonicalBlockIdentity(decodedNumber, hash.lowercase())
    }

    private fun decodeCanonicalBlockHash(result: JsonElement, requestedBlockNumber: Long): String? {
        val identity = decodeCanonicalBlockIdentity(result) ?: return null
        if (identity.number != requestedBlockNumber) {
            throw RpcException(
                "eth_getBlockByNumber returned block ${identity.number} for requested block $requestedBlockNumber",
            )
        }
        return identity.hash
    }

    private data class CanonicalBlockIdentity(val number: Long, val hash: String)

    /**
     * Validates every checkout prerequisite against one canonical block in three network waves.
     * The middle wave is split into at most two concurrent strict max-10 JSON-RPC batches.
     */
    fun validateCheckout(
        token: EvmAddress,
        expectedDecimals: Int,
        expectedSymbol: String,
        operator: EvmAddress,
        receiver: EvmAddress,
    ): CheckoutValidationEvidence {
        require(!token.isZero) { "Token address must not be zero" }
        require(expectedDecimals in 0..255) { "Expected token decimals must be between 0 and 255" }
        require(!operator.isZero) { "Operator address must not be zero" }
        require(!receiver.isZero) { "Receiver address must not be zero" }

        // Wave 1: fail a wrong endpoint before requesting any contract or account state, while
        // anchoring the exact canonical head that every following state read must use.
        val anchor = rpcResults(
            listOf(
                RpcCall("eth_chainId", JsonArray()),
                RpcCall("eth_getBlockByNumber", blockByTagParams(LATEST_BLOCK)),
            ),
        )
        val remoteChainId = parseQuantity(resultString(anchor[0]), "eth_chainId")
            .toSupportedLong("Chain ID")
        if (remoteChainId != config.chainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match configured chain ID ${config.chainId}",
            )
        }
        val anchoredHead = requireNotNull(decodeCanonicalBlockIdentity(anchor[1])) {
            "Latest canonical block is unavailable"
        }
        val blockTag = quantityHex(anchoredHead.number)

        // Wave 2: 15 independent reads. Strict physical batches remain <=10 and only the two
        // chunks for this single logical proof may run concurrently.
        val validationCalls = networkValidationCalls(token, blockTag)
        val stateCalls = buildList {
            addAll(validationCalls)
            add(
                RpcCall(
                    "eth_call",
                    ethCallParams(
                        config.vault,
                        abiFunction("isOperator(address)", operator),
                        blockTag,
                    ),
                ),
            )
            add(RpcCall("eth_call", ethCallParams(config.vault, abiFunction("owner()"), blockTag)))
            add(RpcCall("eth_getBalance", JsonArray().apply {
                add(operator.value)
                add(blockTag)
            }))
            // Pending accounts for locally accepted spends that are absent from anchored latest.
            // Use the minimum below so neither optimistic view can make gas readiness pass.
            add(RpcCall("eth_getBalance", JsonArray().apply {
                add(operator.value)
                add(PENDING_BLOCK)
            }))
            add(RpcCall("eth_getCode", codeAtParams(receiver, blockTag)))
            add(
                RpcCall(
                    "eth_call",
                    ethCallParams(token, abiFunction("balanceOf(address)", receiver), blockTag),
                ),
            )
        }
        val ownerResultIndex = validationCalls.size + 1
        val state = rpcResultsChunked(
            stateCalls,
            toleratedErrorIndices = setOf(ownerResultIndex),
        )

        // Wave 3: an unchanged head hash also proves every earlier saved ancestor used by this
        // checkout proof remained on the same canonical chain while state was sampled.
        val finalHeadHash = requireNotNull(
            decodeCanonicalBlockHash(
                rpcResult(
                    "eth_getBlockByNumber",
                    blockByNumberParams(anchoredHead.number),
                ),
                anchoredHead.number,
            ),
        ) { "Canonical block ${anchoredHead.number} became unavailable during checkout validation" }
        if (!finalHeadHash.equals(anchoredHead.hash, ignoreCase = true)) {
            throw RpcException(
                "Canonical block ${anchoredHead.number} changed during checkout validation",
            )
        }

        val validation = decodeNetworkValidation(
            token = token,
            expectedDecimals = expectedDecimals,
            expectedSymbol = expectedSymbol,
            remoteChainId = remoteChainId,
            results = state.subList(0, validationCalls.size),
        )
        var index = validationCalls.size
        val listed = decodeBooleanWord(resultString(state[index++]), "isOperator result")
        val ownerResult = state[index++]
        val owner = if (ownerResult.isJsonNull) {
            if (!listed) throw RpcException("owner() failed for an unlisted operator")
            ZERO_ADDRESS
        } else {
            decodeAddress(resultString(ownerResult), "vault owner")
        }
        val latestNativeBalance = parseQuantity(
            resultString(state[index++]),
            "eth_getBalance latest",
        )
        val pendingNativeBalance = parseQuantity(
            resultString(state[index++]),
            "eth_getBalance pending",
        )
        val nativeBalance = minOf(latestNativeBalance, pendingNativeBalance)
        val receiverCode = decodeData(resultString(state[index++]), "eth_getCode result")
        val receiverBalance = BigInteger(
            1,
            decodeWord(resultString(state[index]), "balanceOf result"),
        )
        return CheckoutValidationEvidence(
            validation = validation.validation,
            operatorReadiness = OperatorReadiness(listed, owner, nativeBalance),
            receiverFreshness = ReceiverFreshness(receiverCode, receiverBalance),
            blockNumber = anchoredHead.number,
            blockHash = finalHeadHash,
        )
    }

    override fun codeAt(address: EvmAddress): ByteArray {
        return decodeData(rpcString("eth_getCode", codeAtParams(address)), "eth_getCode result")
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

    /** Fixed, read-only readiness bundle used by terminals after a successful network validation. */
    fun operatorReadiness(operator: EvmAddress): OperatorReadiness {
        require(!operator.isZero) { "Operator address must not be zero" }
        val results = rpcResults(
            listOf(
                RpcCall(
                    "eth_call",
                    ethCallParams(config.vault, abiFunction("isOperator(address)", operator)),
                ),
                RpcCall("eth_call", ethCallParams(config.vault, abiFunction("owner()"))),
                RpcCall("eth_getBalance", JsonArray().apply {
                    add(operator.value)
                    add(PENDING_BLOCK)
                }),
            ),
            toleratedErrorIndices = setOf(1),
        )
        val listed = decodeBooleanWord(resultString(results[0]), "isOperator result")
        return OperatorReadiness(
            listedOperator = listed,
            // Preserve the public non-null type. The zero-address sentinel is safe only after a
            // positive isOperator result; an unlisted operator still fails closed without owner().
            vaultOwner = if (results[1].isJsonNull) {
                if (!listed) throw RpcException("owner() failed for an unlisted operator")
                ZERO_ADDRESS
            } else {
                decodeAddress(resultString(results[1]), "vault owner")
            },
            nativeBalance = parseQuantity(resultString(results[2]), "eth_getBalance"),
        )
    }

    /** One-round-trip freshness check before publishing a newly derived receiver. */
    fun receiverFreshness(token: EvmAddress, receiver: EvmAddress): ReceiverFreshness {
        require(!token.isZero) { "Token address must not be zero" }
        require(!receiver.isZero) { "Receiver address must not be zero" }
        val results = rpcResults(
            listOf(
                RpcCall("eth_getCode", codeAtParams(receiver)),
                RpcCall(
                    "eth_call",
                    ethCallParams(token, abiFunction("balanceOf(address)", receiver)),
                ),
            ),
        )
        return ReceiverFreshness(
            deployedCode = decodeData(resultString(results[0]), "eth_getCode result"),
            tokenBalance = BigInteger(
                1,
                decodeWord(resultString(results[1]), "balanceOf result"),
            ),
        )
    }

    /** Fails closed if the configured chain, contracts, factory links, or token whitelist differ. */
    @JvmOverloads
    fun validate(
        token: EvmAddress,
        expectedDecimals: Int? = null,
        expectedSymbol: String? = null,
    ): NetworkValidation = validateWithEvidence(token, expectedDecimals, expectedSymbol).validation

    /**
     * Performs the same validation as [validate] while retaining the vault runtime bytes already
     * returned by its strict batch. This avoids a second eth_getCode proof request.
     */
    @JvmOverloads
    fun validateWithEvidence(
        token: EvmAddress,
        expectedDecimals: Int? = null,
        expectedSymbol: String? = null,
    ): NetworkValidationEvidence {
        require(!token.isZero) { "Token address must not be zero" }
        require(expectedDecimals == null || expectedDecimals in 0..255) {
            "Expected token decimals must be between 0 and 255"
        }
        // Wave 1: bind both endpoint identity and the latest canonical head before any contract
        // state is trusted. A wrong chain fails before the nine validation reads are issued.
        val anchor = rpcResults(
            listOf(
                RpcCall("eth_chainId", JsonArray()),
                RpcCall("eth_getBlockByNumber", blockByTagParams(LATEST_BLOCK)),
            ),
        )
        val remoteChainId = parseQuantity(resultString(anchor[0]), "eth_chainId")
            .toSupportedLong("Chain ID")
        if (remoteChainId != config.chainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match configured chain ID ${config.chainId}",
            )
        }
        val anchoredHead = requireNotNull(decodeCanonicalBlockIdentity(anchor[1])) {
            "Latest canonical block is unavailable"
        }

        // Wave 2: all nine validation reads use the exact anchored height. Vault runtime bytes
        // retained in the evidence therefore come from this same strict batch and proof block.
        val results = rpcResults(
            networkValidationCalls(token, quantityHex(anchoredHead.number)),
        )
        val evidence = decodeNetworkValidation(
            token = token,
            expectedDecimals = expectedDecimals,
            expectedSymbol = expectedSymbol,
            remoteChainId = remoteChainId,
            results = results,
        )

        // Wave 3: close the canonical bracket at the same height; advancing `latest` is harmless,
        // but a changed or unavailable anchored hash invalidates every Wave-2 result.
        val finalHeadHash = requireNotNull(
            decodeCanonicalBlockHash(
                rpcResult(
                    "eth_getBlockByNumber",
                    blockByNumberParams(anchoredHead.number),
                ),
                anchoredHead.number,
            ),
        ) { "Canonical block ${anchoredHead.number} became unavailable during network validation" }
        if (!finalHeadHash.equals(anchoredHead.hash, ignoreCase = true)) {
            throw RpcException(
                "Canonical block ${anchoredHead.number} changed during network validation",
            )
        }
        return evidence
    }

    private fun networkValidationCalls(token: EvmAddress, blockTag: String): List<RpcCall> = listOf(
        RpcCall("eth_getCode", codeAtParams(config.factory, blockTag)),
        RpcCall("eth_getCode", codeAtParams(config.receiverImplementation, blockTag)),
        RpcCall("eth_getCode", codeAtParams(config.vault, blockTag)),
        RpcCall("eth_getCode", codeAtParams(token, blockTag)),
        RpcCall(
            "eth_call",
            ethCallParams(config.factory, abiFunction("implementation()"), blockTag),
        ),
        RpcCall("eth_call", ethCallParams(config.vault, abiFunction("factory()"), blockTag)),
        RpcCall(
            "eth_call",
            ethCallParams(
                config.vault,
                abiFunction("isPaymentToken(address)", token),
                blockTag,
            ),
        ),
        RpcCall("eth_call", ethCallParams(token, abiFunction("decimals()"), blockTag)),
        RpcCall("eth_call", ethCallParams(token, abiFunction("symbol()"), blockTag)),
    )

    private fun decodeNetworkValidation(
        token: EvmAddress,
        expectedDecimals: Int?,
        expectedSymbol: String?,
        remoteChainId: Long,
        results: List<JsonElement>,
    ): NetworkValidationEvidence {
        require(results.size == NETWORK_VALIDATION_CALL_COUNT) {
            "Network validation returned an incomplete result set"
        }
        requireContractResult(results[0], config.factory, "factory")
        requireContractResult(results[1], config.receiverImplementation, "receiver implementation")
        val vaultRuntimeCode = requireContractResult(results[2], config.vault, "vault")
        requireContractResult(results[3], token, "token")

        val actualImplementation = decodeAddress(
            resultString(results[4]),
            "factory implementation",
        )
        if (actualImplementation != config.receiverImplementation) {
            throw NetworkConfigurationException(
                "Factory implementation $actualImplementation does not match configured receiver implementation " +
                    config.receiverImplementation,
            )
        }
        val actualFactory = decodeAddress(resultString(results[5]), "vault factory")
        if (actualFactory != config.factory) {
            throw NetworkConfigurationException(
                "Vault factory $actualFactory does not match configured factory ${config.factory}",
            )
        }
        val whitelistedWord = decodeWord(resultString(results[6]), "isPaymentToken result")
        val whitelisted = when (BigInteger(1, whitelistedWord)) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw RpcException("isPaymentToken returned a non-boolean ABI word")
        }
        if (!whitelisted) {
            throw NetworkConfigurationException("Token $token is not whitelisted by vault ${config.vault}")
        }
        val decimalsValue = BigInteger(
            1,
            decodeWord(resultString(results[7]), "decimals result"),
        )
        if (decimalsValue > UINT8_MAX) throw RpcException("decimals returned a value outside uint8")
        val actualDecimals = decimalsValue.toInt()
        if (expectedDecimals != null && actualDecimals != expectedDecimals) {
            throw NetworkConfigurationException(
                "Token decimals $actualDecimals do not match configured decimals $expectedDecimals",
            )
        }
        val actualSymbol = decodeTokenSymbol(resultString(results[8]))
        if (expectedSymbol != null && actualSymbol != expectedSymbol) {
            throw NetworkConfigurationException(
                "Token symbol $actualSymbol does not match configured symbol $expectedSymbol",
            )
        }

        val validation = NetworkValidation(
            chainId = remoteChainId,
            factory = config.factory,
            receiverImplementation = actualImplementation,
            vault = config.vault,
            token = token,
            tokenWhitelisted = true,
            tokenDecimals = actualDecimals,
            tokenSymbol = actualSymbol,
        )
        return NetworkValidationEvidence(validation, vaultRuntimeCode)
    }

    private fun requireContract(address: EvmAddress, label: String) {
        if (codeAt(address).isEmpty()) {
            throw NetworkConfigurationException("Configured $label $address has no deployed code")
        }
    }

    private fun requireContractResult(
        result: JsonElement,
        address: EvmAddress,
        label: String,
    ): ByteArray {
        val code = decodeData(resultString(result), "eth_getCode result")
        if (code.isEmpty()) {
            throw NetworkConfigurationException("Configured $label $address has no deployed code")
        }
        return code
    }

    private fun ethCall(to: EvmAddress, data: String, blockTag: String = LATEST_BLOCK): String {
        return rpcString("eth_call", ethCallParams(to, data, blockTag))
    }

    private fun codeAtParams(
        address: EvmAddress,
        blockTag: String = LATEST_BLOCK,
    ): JsonArray = JsonArray().apply {
        add(address.value)
        add(blockTag)
    }

    private fun blockByNumberParams(blockNumber: Long): JsonArray = JsonArray().apply {
        add(quantityHex(blockNumber))
        add(false)
    }

    private fun blockByTagParams(blockTag: String): JsonArray = JsonArray().apply {
        add(blockTag)
        add(false)
    }

    private fun ethCallParams(
        to: EvmAddress,
        data: String,
        blockTag: String = LATEST_BLOCK,
    ): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("to", to.value)
            addProperty("data", data)
        })
        add(blockTag)
    }

    private fun rpcQuantity(method: String): BigInteger = parseQuantity(rpcString(method, JsonArray()), method)

    private fun rpcString(method: String, params: JsonArray): String {
        val result = rpcResult(method, params)
        return resultString(result)
    }

    private fun resultString(result: JsonElement): String {
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isString) {
            throw RpcException("JSON-RPC result must be a string")
        }
        return result.asString
    }

    private fun rpcResult(method: String, params: JsonArray): JsonElement =
        rpcResults(listOf(RpcCall(method, params))).single()

    private fun rpcResults(
        calls: List<RpcCall>,
        toleratedErrorIndices: Set<Int> = emptySet(),
    ): List<JsonElement> {
        require(calls.isNotEmpty()) { "JSON-RPC batch must not be empty" }
        require(calls.size <= MAX_BATCH_SIZE) {
            "JSON-RPC batch must contain at most $MAX_BATCH_SIZE requests"
        }
        require(toleratedErrorIndices.all { it in calls.indices }) {
            "Tolerated JSON-RPC error index is outside the request batch"
        }
        val requests = calls.map { call ->
            val id = requestIds.incrementAndGet()
            id to JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", call.method)
                add("params", call.params)
            }
        }
        val requestBody = if (requests.size == 1) {
            requests.single().second.toString()
        } else {
            JsonArray().apply { requests.forEach { add(it.second) } }.toString()
        }
        val responseText = try {
            transport.execute(requestBody)
        } catch (error: RpcException) {
            throw error
        } catch (error: Exception) {
            throw RpcException("JSON-RPC transport failed", error)
        }

        val responseRoot = try {
            JsonParser.parseString(responseText)
        } catch (error: Exception) {
            throw RpcException("JSON-RPC response is not valid JSON", error)
        }
        val responses = if (requests.size == 1) {
            if (!responseRoot.isJsonObject) {
                throw RpcException("JSON-RPC response is not a JSON object")
            }
            listOf(responseRoot.asJsonObject)
        } else {
            if (!responseRoot.isJsonArray) {
                throw RpcException("JSON-RPC batch response is not an array")
            }
            responseRoot.asJsonArray.map { element ->
                if (!element.isJsonObject) {
                    throw RpcException("JSON-RPC batch response contains a non-object")
                }
                element.asJsonObject
            }
        }
        if (responses.size != requests.size) {
            throw RpcException("JSON-RPC response count does not match the request count")
        }
        val expectedIds = requests.mapTo(linkedSetOf()) { it.first }
        val toleratedErrorIds = toleratedErrorIndices.mapTo(hashSetOf()) { requests[it].first }
        val resultsById = linkedMapOf<Long, JsonElement>()
        responses.forEach { response ->
            val version = response.get("jsonrpc")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isString }
                ?.asString
            if (version != "2.0") {
                throw RpcException("JSON-RPC response has an invalid version")
            }
            val idPrimitive = response.get("id")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isNumber }
                ?: throw RpcException("JSON-RPC response has an invalid ID")
            val id = idPrimitive.asString
                .takeIf(RESPONSE_ID_PATTERN::matches)
                ?.toLongOrNull()
                ?: throw RpcException("JSON-RPC response has an invalid ID")
            if (id !in expectedIds) {
                throw RpcException("JSON-RPC response ID does not match any request")
            }
            if (resultsById.containsKey(id)) {
                throw RpcException("JSON-RPC response contains a duplicate ID")
            }
            val hasResult = response.has("result")
            val error = response.get("error")?.takeUnless { it.isJsonNull }
            if (hasResult == (error != null)) {
                throw RpcException("JSON-RPC response must contain exactly one of result or error")
            }
            if (error != null) {
                if (!error.isJsonObject) throw RpcException("JSON-RPC error is not an object")
                if (id in toleratedErrorIds) {
                    resultsById[id] = com.google.gson.JsonNull.INSTANCE
                    return@forEach
                }
                val code = error.asJsonObject.get("code")?.asInt ?: 0
                val message = error.asJsonObject.get("message")?.asString ?: "Unknown RPC error"
                throw RpcResponseException(code, message)
            }
            val result = response.get("result")
                ?: throw RpcException("JSON-RPC response is missing result")
            resultsById[id] = result
        }
        return requests.map { (id, _) ->
            resultsById[id] ?: throw RpcException("JSON-RPC batch response is missing request ID $id")
        }
    }

    private fun rpcResultsChunked(
        calls: List<RpcCall>,
        toleratedErrorIndices: Set<Int> = emptySet(),
    ): List<JsonElement> {
        require(calls.isNotEmpty()) { "JSON-RPC call set must not be empty" }
        require(calls.size <= MAX_CONCURRENT_CALL_SET_SIZE) {
            "Concurrent JSON-RPC call set must contain at most $MAX_CONCURRENT_CALL_SET_SIZE requests"
        }
        require(toleratedErrorIndices.all { it in calls.indices }) {
            "Tolerated JSON-RPC error index is outside the request call set"
        }
        val chunks = calls.withIndex().toList().chunked(MAX_BATCH_SIZE)
        if (chunks.size == 1) {
            return rpcResults(
                chunks.single().map { it.value },
                toleratedErrorIndices,
            )
        }
        val futures = chunks.map { chunk ->
            CompletableFuture.supplyAsync(
                {
                    val localTolerated = chunk.mapIndexedNotNull { localIndex, indexedValue ->
                        localIndex.takeIf { indexedValue.index in toleratedErrorIndices }
                    }.toSet()
                    rpcResults(chunk.map { it.value }, localTolerated)
                },
                CHECKOUT_BATCH_EXECUTOR,
            )
        }
        return try {
            futures.flatMap(CompletableFuture<List<JsonElement>>::get)
        } catch (error: ExecutionException) {
            futures.forEach { it.cancel(true) }
            val cause = error.cause
            if (cause is RpcException) throw cause
            throw RpcException("Concurrent JSON-RPC batch failed", cause)
        } catch (error: InterruptedException) {
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw RpcException("Concurrent JSON-RPC batch was interrupted", error)
        }
    }

    private data class RpcCall(val method: String, val params: JsonArray)

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
        private const val PENDING_BLOCK = "pending"
        private val UINT8_MAX = BigInteger.valueOf(255)
        private val ZERO_ADDRESS = EvmAddress.parse("0x" + "00".repeat(20))
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
        private val RESPONSE_ID_PATTERN = Regex("^(0|[1-9][0-9]*)$")
        private const val MAX_SYMBOL_UTF8_BYTES = 32
        private const val MAX_BATCH_SIZE = 10
        private const val NETWORK_VALIDATION_CALL_COUNT = 9
        private const val MAX_CONCURRENT_CALL_SET_SIZE = 20
        private val CHECKOUT_BATCH_EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "opk-checkout-rpc").apply { isDaemon = true }
        }
        private val UNSAFE_UNICODE_CATEGORIES = setOf(
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
        )

        private fun decodeBooleanWord(value: String, label: String): Boolean = when (
            BigInteger(1, decodeWord(value, label))
        ) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw RpcException("$label returned a non-boolean ABI word")
        }
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

        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.use(::readLimitedUtf8).orEmpty()
        if (status !in 200..299) {
            throw RpcException("RPC HTTP request failed with status $status")
        }
        if (response.isEmpty()) throw RpcException("RPC HTTP response body is empty")
        // Closing request/response streams returns this connection to Android's process-wide
        // keep-alive pool. Calling disconnect() here prevented reuse across short RPC phases.
        return response
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
