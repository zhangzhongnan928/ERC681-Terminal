// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

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
    private val retrySleep: (Long) -> Unit,
    private val retryJitterMillis: () -> Long,
) : ReadOnlyChainClient, PaymentEvidenceChainClient {
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
        retrySleep = { delayMillis -> Thread.sleep(delayMillis) },
        retryJitterMillis = {
            ThreadLocalRandom.current().nextLong(MAX_RETRY_JITTER_MILLIS + 1)
        },
    )

    override fun chainId(): Long = rpcQuantity("eth_chainId").toSupportedLong("Chain ID")

    override fun blockNumber(): Long = rpcQuantity("eth_blockNumber").toSupportedLong("Block number")

    override fun blockHash(blockNumber: Long): String? {
        require(blockNumber >= 0) { "Block number must not be negative" }
        val result = rpcResult("eth_getBlockByNumber", blockByNumberParams(blockNumber))
        return decodeCanonicalBlockHash(result, blockNumber)
    }

    override fun paymentAssetBalance(
        asset: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): BigInteger = tokenBalance(asset, receiver, blockNumber)

    override fun paymentEvidenceBlock(
        blockNumber: Long,
        includeDirectNativeTransactions: Boolean,
    ): PaymentEvidenceBlock? {
        require(blockNumber >= 0) { "Block number must not be negative" }
        val result = rpcResult(
            "eth_getBlockByNumber",
            paymentEvidenceBlockParams(blockNumber, includeDirectNativeTransactions),
        )
        return decodePaymentEvidenceBlockResult(result, blockNumber, includeDirectNativeTransactions)
    }

    private fun paymentEvidenceBlockParams(
        blockNumber: Long,
        includeDirectNativeTransactions: Boolean,
    ): JsonArray = JsonArray().apply {
        add(quantityHex(blockNumber))
        add(includeDirectNativeTransactions)
    }

    private fun decodePaymentEvidenceBlockResult(
        result: JsonElement,
        blockNumber: Long,
        includeDirectNativeTransactions: Boolean,
    ): PaymentEvidenceBlock? {
        if (result.isJsonNull) return null
        if (!result.isJsonObject) {
            throw RpcException("eth_getBlockByNumber result must be an object or null")
        }
        val block = result.asJsonObject
        val identity = decodeCanonicalBlockIdentity(result)
            ?: throw RpcException("Canonical payment block is unavailable")
        if (identity.number != blockNumber) {
            throw RpcException(
                "eth_getBlockByNumber returned block ${identity.number} for requested block $blockNumber",
            )
        }
        val timestamp = objectQuantity(block, "timestamp", "Payment block timestamp")
            .toSupportedLong("Payment block timestamp")
        val transactions = if (includeDirectNativeTransactions) {
            val value = block.get("transactions")
                ?: throw RpcException("Full payment block has no transactions")
            if (!value.isJsonArray) {
                throw RpcException("Full payment block transactions must be an array")
            }
            value.asJsonArray.map { element ->
                decodeDirectNativePaymentTransaction(element, identity)
            }
        } else {
            emptyList()
        }
        return PaymentEvidenceBlock(
            blockNumber = identity.number,
            blockHash = identity.hash,
            blockTimestamp = timestamp,
            directNativeTransactions = transactions,
        )
    }

    override fun incomingErc20Transfers(
        token: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): List<IncomingErc20Transfer> {
        val result = rpcResult(
            "eth_getLogs",
            incomingErc20TransfersParams(token, receiver, blockNumber),
        )
        return decodeIncomingErc20TransfersResult(result, token, receiver, blockNumber)
    }

    private fun incomingErc20TransfersParams(
        token: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): JsonArray {
        require(!token.isZero) { "Token address must not be zero" }
        require(!NativeAsset.isNative(token)) { "Native assets do not emit ERC-20 Transfer logs" }
        require(!receiver.isZero) { "Payment receiver must not be zero" }
        require(blockNumber >= 0) { "Block number must not be negative" }
        val blockTag = quantityHex(blockNumber)
        val filter = JsonObject().apply {
            addProperty("fromBlock", blockTag)
            addProperty("toBlock", blockTag)
            addProperty("address", token.value)
            add("topics", JsonArray().apply {
                add(TRANSFER_EVENT_TOPIC)
                add(com.google.gson.JsonNull.INSTANCE)
                add(addressTopic(receiver))
            })
        }
        return JsonArray().apply { add(filter) }
    }

    private fun decodeIncomingErc20TransfersResult(
        result: JsonElement,
        token: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): List<IncomingErc20Transfer> {
        if (!result.isJsonArray) throw RpcException("eth_getLogs result must be an array")
        return result.asJsonArray.map { element ->
            decodeIncomingErc20Transfer(element, token, receiver, blockNumber)
        }
    }

    /**
     * Wave 1 of a batched evidence resolution: endpoint identity, both saved anchors, and both
     * anchor-height balances in one JSON-RPC batch. Anchor hash verification stays with the
     * resolver; the closing bracket re-reads every anchor fresh via [paymentEvidenceBlockHeaders].
     */
    internal fun openPaymentEvidenceContext(
        asset: EvmAddress,
        receiver: EvmAddress,
        publicationBlockNumber: Long,
        fundingBlockNumber: Long,
    ): PaymentEvidenceOpenContext {
        require(publicationBlockNumber >= 0) { "Block number must not be negative" }
        require(fundingBlockNumber >= 0) { "Block number must not be negative" }
        val isNative = NativeAsset.isNative(asset)
        val results = rpcResults(
            listOf(
                RpcCall("eth_chainId", JsonArray()),
                RpcCall(
                    "eth_getBlockByNumber",
                    paymentEvidenceBlockParams(publicationBlockNumber, false),
                ),
                RpcCall(
                    "eth_getBlockByNumber",
                    paymentEvidenceBlockParams(fundingBlockNumber, false),
                ),
                assetBalanceCall(asset, receiver, quantityHex(publicationBlockNumber)),
                assetBalanceCall(asset, receiver, quantityHex(fundingBlockNumber)),
            ),
        )
        return PaymentEvidenceOpenContext(
            chainId = parseQuantity(resultString(results[0]), "eth_chainId")
                .toSupportedLong("Chain ID"),
            publicationBlock = decodePaymentEvidenceBlockResult(
                results[1],
                publicationBlockNumber,
                false,
            ),
            fundingBlock = decodePaymentEvidenceBlockResult(results[2], fundingBlockNumber, false),
            publicationBalance = decodeAssetBalance(results[3], isNative),
            fundingBalance = decodeAssetBalance(results[4], isNative),
        )
    }

    /** Fresh batched balance reads at exact heights; chunks over ten run concurrently. */
    internal fun paymentAssetBalances(
        asset: EvmAddress,
        receiver: EvmAddress,
        blockNumbers: Collection<Long>,
    ): Map<Long, BigInteger> {
        val ordered = blockNumbers.toCollection(LinkedHashSet())
        require(ordered.isNotEmpty()) { "Balance block set must not be empty" }
        require(ordered.size <= MAX_CONCURRENT_CALL_SET_SIZE) {
            "Balance block set must contain at most $MAX_CONCURRENT_CALL_SET_SIZE heights"
        }
        ordered.forEach { require(it >= 0) { "Block number must not be negative" } }
        val isNative = NativeAsset.isNative(asset)
        val results = rpcResultsChunked(
            ordered.map { blockNumber -> assetBalanceCall(asset, receiver, quantityHex(blockNumber)) },
        )
        return ordered.mapIndexed { index, blockNumber ->
            blockNumber to decodeAssetBalance(results[index], isNative)
        }.toMap(LinkedHashMap())
    }

    /**
     * Crossing-block reads in one batch: the optional prior-height balance, the canonical crossing
     * block (with full transactions only for native assets), and receiver-scoped ERC-20 Transfer
     * logs for token assets.
     */
    internal fun paymentCrossingReads(
        asset: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
        includePriorBalance: Boolean,
    ): PaymentCrossingReads {
        require(blockNumber >= 0) { "Block number must not be negative" }
        val isNative = NativeAsset.isNative(asset)
        val calls = buildList {
            if (includePriorBalance) {
                require(blockNumber > 0) { "Prior balance requires a positive block number" }
                add(assetBalanceCall(asset, receiver, quantityHex(blockNumber - 1L)))
            }
            add(
                RpcCall(
                    "eth_getBlockByNumber",
                    paymentEvidenceBlockParams(blockNumber, isNative),
                ),
            )
            if (!isNative) {
                add(RpcCall("eth_getLogs", incomingErc20TransfersParams(asset, receiver, blockNumber)))
            }
        }
        val results = rpcResults(calls)
        var index = 0
        val priorBalance = if (includePriorBalance) {
            decodeAssetBalance(results[index++], isNative)
        } else {
            null
        }
        val block = decodePaymentEvidenceBlockResult(results[index++], blockNumber, isNative)
        val transfers = if (!isNative) {
            decodeIncomingErc20TransfersResult(results[index], asset, receiver, blockNumber)
        } else {
            null
        }
        return PaymentCrossingReads(
            block = block,
            erc20Transfers = transfers,
            priorBalance = priorBalance,
        )
    }

    /** Fresh batched canonical header reads used to close the evidence bracket. */
    internal fun paymentEvidenceBlockHeaders(
        blockNumbers: Collection<Long>,
    ): Map<Long, PaymentEvidenceBlock?> {
        val ordered = blockNumbers.toCollection(LinkedHashSet())
        require(ordered.isNotEmpty()) { "Header block set must not be empty" }
        require(ordered.size <= MAX_BATCH_SIZE) {
            "Header block set must contain at most $MAX_BATCH_SIZE heights"
        }
        ordered.forEach { require(it >= 0) { "Block number must not be negative" } }
        val results = rpcResults(
            ordered.map { blockNumber ->
                RpcCall("eth_getBlockByNumber", paymentEvidenceBlockParams(blockNumber, false))
            },
        )
        return ordered.mapIndexed { index, blockNumber ->
            blockNumber to decodePaymentEvidenceBlockResult(results[index], blockNumber, false)
        }.toMap(LinkedHashMap())
    }

    private fun decodeDirectNativePaymentTransaction(
        element: JsonElement,
        block: CanonicalBlockIdentity,
    ): DirectNativePaymentTransaction {
        if (!element.isJsonObject) {
            throw RpcException("Full payment block contains a non-transaction result")
        }
        val transaction = element.asJsonObject
        val transactionBlock = objectQuantity(
            transaction,
            "blockNumber",
            "Native payment transaction block number",
        ).toSupportedLong("Native payment transaction block number")
        if (transactionBlock != block.number) {
            throw RpcException("Native payment transaction block number does not match its block")
        }
        val transactionBlockHash = objectString(
            transaction,
            "blockHash",
            "Native payment transaction block hash",
        ).also { requireRpcHash(it, "Native payment transaction block hash") }.lowercase()
        if (transactionBlockHash != block.hash) {
            throw RpcException("Native payment transaction block hash does not match its block")
        }
        val recipientElement = transaction.get("to")
        val recipient = when {
            recipientElement == null || recipientElement.isJsonNull -> null
            recipientElement.isJsonPrimitive && recipientElement.asJsonPrimitive.isString ->
                parseRpcAddress(recipientElement.asString, "Native payment recipient")
            else -> throw RpcException("Native payment recipient must be an address or null")
        }
        return DirectNativePaymentTransaction(
            txHash = objectString(
                transaction,
                "hash",
                "Native payment transaction hash",
            ).also { requireRpcHash(it, "Native payment transaction hash") }.lowercase(),
            payer = parseRpcAddress(
                objectString(transaction, "from", "Native payment payer"),
                "Native payment payer",
            ).also {
                if (it.isZero) throw RpcException("Native payment payer must not be zero")
            },
            recipient = recipient,
            transactionIndex = objectQuantity(
                transaction,
                "transactionIndex",
                "Native payment transaction index",
            ).toSupportedLong("Native payment transaction index"),
            value = objectQuantity(transaction, "value", "Native payment transaction value"),
            blockNumber = block.number,
            blockHash = block.hash,
        )
    }

    private fun decodeIncomingErc20Transfer(
        element: JsonElement,
        token: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): IncomingErc20Transfer {
        if (!element.isJsonObject) throw RpcException("eth_getLogs returned a non-log result")
        val log = element.asJsonObject
        val removed = log.get("removed")?.let { removedValue ->
            if (!removedValue.isJsonPrimitive || !removedValue.asJsonPrimitive.isBoolean) {
                throw RpcException("ERC-20 payment log removed flag is malformed")
            }
            removedValue.asBoolean
        } ?: false
        if (removed) throw RpcException("ERC-20 payment log was removed")
        val logToken = parseRpcAddress(
            objectString(log, "address", "ERC-20 payment log address"),
            "ERC-20 payment log address",
        )
        if (logToken != token) throw RpcException("ERC-20 payment log came from a different token")
        val logBlockNumber = objectQuantity(log, "blockNumber", "ERC-20 payment log block")
            .toSupportedLong("ERC-20 payment log block")
        if (logBlockNumber != blockNumber) {
            throw RpcException("ERC-20 payment log block number does not match the requested block")
        }
        val blockHash = objectString(log, "blockHash", "ERC-20 payment log block hash")
            .also { requireRpcHash(it, "ERC-20 payment log block hash") }
            .lowercase()
        val topicsElement = log.get("topics")
            ?: throw RpcException("ERC-20 payment log has no topics")
        if (!topicsElement.isJsonArray || topicsElement.asJsonArray.size() != 3) {
            throw RpcException("ERC-20 Transfer log topics are malformed")
        }
        val topics = topicsElement.asJsonArray.mapIndexed { index, topic ->
            if (!topic.isJsonPrimitive || !topic.asJsonPrimitive.isString) {
                throw RpcException("ERC-20 Transfer topic $index is malformed")
            }
            topic.asString
        }
        if (!topics[0].equals(TRANSFER_EVENT_TOPIC, ignoreCase = true)) {
            throw RpcException("ERC-20 payment log has the wrong event signature")
        }
        val payer = decodeAddressTopic(topics[1], "ERC-20 Transfer sender topic").also {
            if (it.isZero) throw RpcException("ERC-20 payment payer must not be zero")
        }
        val recipient = decodeAddressTopic(topics[2], "ERC-20 Transfer recipient topic")
        if (recipient != receiver) throw RpcException("ERC-20 payment log has the wrong receiver")
        val data = objectString(log, "data", "ERC-20 Transfer amount")
        val amount = try {
            BigInteger(1, decodeWord(data, "ERC-20 Transfer amount"))
        } catch (error: RpcException) {
            throw error
        } catch (error: Exception) {
            throw RpcException("ERC-20 Transfer amount is malformed", error)
        }
        return IncomingErc20Transfer(
            txHash = objectString(log, "transactionHash", "ERC-20 payment transaction hash")
                .also { requireRpcHash(it, "ERC-20 payment transaction hash") }
                .lowercase(),
            token = logToken,
            payer = payer,
            recipient = recipient,
            logIndex = objectQuantity(log, "logIndex", "ERC-20 payment log index")
                .toSupportedLong("ERC-20 payment log index"),
            value = amount,
            blockNumber = logBlockNumber,
            blockHash = blockHash,
            removed = false,
        )
    }

    private fun objectString(value: JsonObject, name: String, label: String): String {
        val element = value.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw RpcException("$label is missing or malformed")
        }
        return element.asString
    }

    private fun objectQuantity(value: JsonObject, name: String, label: String): BigInteger =
        parseQuantity(objectString(value, name, label), label)

    private fun parseRpcAddress(value: String, label: String): EvmAddress = try {
        EvmAddress.parse(value)
    } catch (error: IllegalArgumentException) {
        throw RpcException("$label is malformed", error)
    }

    private fun decodeAddressTopic(value: String, label: String): EvmAddress {
        val word = try {
            Hex.decode(value, 32)
        } catch (error: IllegalArgumentException) {
            throw RpcException("$label is malformed", error)
        }
        if (word.copyOfRange(0, 12).any { it != 0.toByte() }) {
            throw RpcException("$label has non-zero ABI address padding")
        }
        return EvmAddress.fromBytes(word.copyOfRange(12, 32))
    }

    private fun addressTopic(address: EvmAddress): String =
        "0x" + address.value.substring(2).padStart(64, '0')

    private fun requireRpcHash(value: String, label: String) {
        if (!PAYMENT_HASH_PATTERN.matches(value)) throw RpcException("$label is malformed")
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
        val anchoredHead = decodeCanonicalBlockIdentity(anchor[1])
            ?: throw RpcException("Latest canonical block is unavailable")
        val head = anchoredHead.number

        val cursorToRead = savedCursorBlock?.takeIf { it < head }
        val isNative = NativeAsset.isNative(token)
        val sampleCalls = buildList {
            add(assetBalanceCall(token, holder, quantityHex(head)))
            cursorToRead?.let { cursor ->
                add(RpcCall("eth_getBlockByNumber", blockByNumberParams(cursor)))
            }
        }
        val sampled = rpcResults(sampleCalls)
        val balance = decodeAssetBalance(sampled[0], isNative)
        val cursorHash = if (cursorToRead != null) {
            decodeCanonicalBlockHash(sampled[1], cursorToRead)
        } else {
            null
        }

        val finalHeadHash = decodeCanonicalBlockHash(
            rpcResult("eth_getBlockByNumber", blockByNumberParams(head)),
            head,
        ) ?: throw RpcException(
            "Canonical block $head became unavailable after validating confirmation cursors",
        )
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
        val anchoredHead = decodeCanonicalBlockIdentity(anchor[1])
            ?: throw RpcException("Latest canonical block is unavailable")
        val blockTag = quantityHex(anchoredHead.number)

        // Wave 2: independent reads at one block. Native assets replace token metadata/code
        // reads with the mandatory NATIVE_ASSET() capability probe.
        val validationCalls = networkValidationCalls(token, blockTag)
        val isNative = NativeAsset.isNative(token)
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
            add(assetBalanceCall(token, receiver, blockTag))
        }
        val ownerResultIndex = validationCalls.size + 1
        val state = rpcResultsChunked(
            stateCalls,
            toleratedErrorIndices = setOf(ownerResultIndex),
        )

        // Wave 3: an unchanged head hash also proves every earlier saved ancestor used by this
        // checkout proof remained on the same canonical chain while state was sampled.
        val finalHeadHash = decodeCanonicalBlockHash(
            rpcResult(
                "eth_getBlockByNumber",
                blockByNumberParams(anchoredHead.number),
            ),
            anchoredHead.number,
        ) ?: throw RpcException(
            "Canonical block ${anchoredHead.number} became unavailable during checkout validation",
        )
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
        val receiverBalance = decodeAssetBalance(state[index], isNative)
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
        if (NativeAsset.isNative(token)) return NativeAsset.DECIMALS
        val value = BigInteger(
            1,
            decodeWord(ethCall(token, abiFunction("decimals()")), "decimals result"),
        )
        if (value > UINT8_MAX) throw RpcException("decimals returned a value outside uint8")
        return value.toInt()
    }

    override fun tokenSymbol(token: EvmAddress): String {
        require(!token.isZero) { "Token address must not be zero" }
        if (NativeAsset.isNative(token)) {
            throw RpcException("Native asset symbol must come from the trusted chain profile")
        }
        return decodeTokenSymbol(ethCall(token, abiFunction("symbol()")))
    }

    override fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long?): BigInteger {
        if (NativeAsset.isNative(token)) return nativeBalance(holder, blockNumber)
        require(blockNumber == null || blockNumber >= 0) { "Block number must not be negative" }
        val result = ethCall(
            to = token,
            data = abiFunction("balanceOf(address)", holder),
            blockTag = blockNumber?.let(::quantityHex) ?: LATEST_BLOCK,
        )
        return BigInteger(1, decodeWord(result, "balanceOf result"))
    }

    override fun nativeBalance(holder: EvmAddress, blockNumber: Long?): BigInteger {
        require(!holder.isZero) { "Holder address must not be zero" }
        require(blockNumber == null || blockNumber >= 0) { "Block number must not be negative" }
        return parseQuantity(
            rpcString(
                "eth_getBalance",
                balanceParams(holder, blockNumber?.let(::quantityHex) ?: LATEST_BLOCK),
            ),
            "eth_getBalance",
        )
    }

    /** Fixed, read-only readiness bundle used by terminals after a successful network validation. */
    fun operatorReadiness(operator: EvmAddress): OperatorReadiness =
        operatorReadiness(operator, retryOnThrottle = false)

    /** Fixed readiness bundle with an explicit, bounded throttle-retry policy. */
    fun operatorReadiness(operator: EvmAddress, retryOnThrottle: Boolean): OperatorReadiness {
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
            retryOnThrottle = retryOnThrottle,
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
                assetBalanceCall(token, receiver, LATEST_BLOCK),
            ),
        )
        return ReceiverFreshness(
            deployedCode = decodeData(resultString(results[0]), "eth_getCode result"),
            tokenBalance = decodeAssetBalance(results[1], NativeAsset.isNative(token)),
        )
    }

    /** Fails closed if the configured chain, contracts, factory links, or token whitelist differ. */
    @JvmOverloads
    fun validate(
        token: EvmAddress,
        expectedDecimals: Int? = null,
        expectedSymbol: String? = null,
    ): NetworkValidation = validateWithEvidenceInternal(
        token,
        expectedDecimals,
        expectedSymbol,
        retryOnThrottle = false,
    ).validation

    /** Validation with an explicit, bounded throttle-retry policy. */
    fun validate(
        token: EvmAddress,
        expectedDecimals: Int?,
        expectedSymbol: String?,
        retryOnThrottle: Boolean,
    ): NetworkValidation = validateWithEvidenceInternal(
        token,
        expectedDecimals,
        expectedSymbol,
        retryOnThrottle,
    ).validation

    /**
     * Performs the same validation as [validate] while retaining the vault runtime bytes already
     * returned by its strict batch. This avoids a second eth_getCode proof request.
     */
    @JvmOverloads
    fun validateWithEvidence(
        token: EvmAddress,
        expectedDecimals: Int? = null,
        expectedSymbol: String? = null,
    ): NetworkValidationEvidence = validateWithEvidenceInternal(
        token,
        expectedDecimals,
        expectedSymbol,
        retryOnThrottle = false,
    )

    /** Evidence-producing validation with an explicit, bounded throttle-retry policy. */
    fun validateWithEvidence(
        token: EvmAddress,
        expectedDecimals: Int?,
        expectedSymbol: String?,
        retryOnThrottle: Boolean,
    ): NetworkValidationEvidence = validateWithEvidenceInternal(
        token,
        expectedDecimals,
        expectedSymbol,
        retryOnThrottle,
    )

    private fun validateWithEvidenceInternal(
        token: EvmAddress,
        expectedDecimals: Int?,
        expectedSymbol: String?,
        retryOnThrottle: Boolean,
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
            retryOnThrottle = retryOnThrottle,
        )
        val remoteChainId = parseQuantity(resultString(anchor[0]), "eth_chainId")
            .toSupportedLong("Chain ID")
        if (remoteChainId != config.chainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match configured chain ID ${config.chainId}",
            )
        }
        val anchoredHead = decodeCanonicalBlockIdentity(anchor[1])
            ?: throw RpcException("Latest canonical block is unavailable")

        // Wave 2: every validation read uses the exact anchored height. Native validation swaps
        // token code/metadata reads for the mandatory NATIVE_ASSET() capability proof.
        val results = rpcResults(
            networkValidationCalls(token, quantityHex(anchoredHead.number)),
            retryOnThrottle = retryOnThrottle,
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
        val finalHeadHash = decodeCanonicalBlockHash(
            rpcResult(
                "eth_getBlockByNumber",
                blockByNumberParams(anchoredHead.number),
                retryOnThrottle = retryOnThrottle,
            ),
            anchoredHead.number,
        ) ?: throw RpcException(
            "Canonical block ${anchoredHead.number} became unavailable during network validation",
        )
        if (!finalHeadHash.equals(anchoredHead.hash, ignoreCase = true)) {
            throw RpcException(
                "Canonical block ${anchoredHead.number} changed during network validation",
            )
        }
        return evidence
    }

    private fun networkValidationCalls(token: EvmAddress, blockTag: String): List<RpcCall> {
        val commonPrefix = listOf(
            RpcCall("eth_getCode", codeAtParams(config.factory, blockTag)),
            RpcCall("eth_getCode", codeAtParams(config.receiverImplementation, blockTag)),
            RpcCall("eth_getCode", codeAtParams(config.vault, blockTag)),
        )
        val commonContractReads = listOf(
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
        )
        return if (NativeAsset.isNative(token)) {
            commonPrefix +
                RpcCall("eth_call", ethCallParams(config.vault, abiFunction("NATIVE_ASSET()"), blockTag)) +
                commonContractReads
        } else {
            commonPrefix +
                RpcCall("eth_getCode", codeAtParams(token, blockTag)) +
                commonContractReads +
                listOf(
                    RpcCall("eth_call", ethCallParams(token, abiFunction("decimals()"), blockTag)),
                    RpcCall("eth_call", ethCallParams(token, abiFunction("symbol()"), blockTag)),
                )
        }
    }

    private fun decodeNetworkValidation(
        token: EvmAddress,
        expectedDecimals: Int?,
        expectedSymbol: String?,
        remoteChainId: Long,
        results: List<JsonElement>,
    ): NetworkValidationEvidence {
        val isNative = NativeAsset.isNative(token)
        val expectedResultCount = if (isNative) {
            NATIVE_NETWORK_VALIDATION_CALL_COUNT
        } else {
            ERC20_NETWORK_VALIDATION_CALL_COUNT
        }
        require(results.size == expectedResultCount) {
            "Network validation returned an incomplete result set"
        }
        requireContractResult(results[0], config.factory, "factory")
        requireContractResult(results[1], config.receiverImplementation, "receiver implementation")
        val vaultRuntimeCode = requireContractResult(results[2], config.vault, "vault")
        if (!isNative) requireContractResult(results[3], token, "token")

        var index = 3
        if (isNative) {
            val advertisedNativeAsset = decodeAddress(
                resultString(results[index++]),
                "NATIVE_ASSET result",
            )
            if (advertisedNativeAsset != NativeAsset.address) {
                throw NetworkConfigurationException(
                    "Vault ${config.vault} advertises native asset $advertisedNativeAsset instead of " +
                        NativeAsset.address,
                )
            }
        } else {
            index++
        }
        val actualImplementation = decodeAddress(
            resultString(results[index++]),
            "factory implementation",
        )
        if (actualImplementation != config.receiverImplementation) {
            throw NetworkConfigurationException(
                "Factory implementation $actualImplementation does not match configured receiver implementation " +
                    config.receiverImplementation,
            )
        }
        val actualFactory = decodeAddress(resultString(results[index++]), "vault factory")
        if (actualFactory != config.factory) {
            throw NetworkConfigurationException(
                "Vault factory $actualFactory does not match configured factory ${config.factory}",
            )
        }
        val whitelistedWord = decodeWord(resultString(results[index++]), "isPaymentToken result")
        val whitelisted = when (BigInteger(1, whitelistedWord)) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw RpcException("isPaymentToken returned a non-boolean ABI word")
        }
        if (!whitelisted) {
            throw NetworkConfigurationException(
                "Payment asset $token is not whitelisted by vault ${config.vault}",
            )
        }
        val actualDecimals = if (isNative) {
            NativeAsset.DECIMALS
        } else {
            val decimalsValue = BigInteger(
                1,
                decodeWord(resultString(results[index++]), "decimals result"),
            )
            if (decimalsValue > UINT8_MAX) throw RpcException("decimals returned a value outside uint8")
            decimalsValue.toInt()
        }
        if (expectedDecimals != null && actualDecimals != expectedDecimals) {
            throw NetworkConfigurationException(
                "Payment asset decimals $actualDecimals do not match configured decimals $expectedDecimals",
            )
        }
        val actualSymbol = if (isNative) {
            expectedSymbol?.takeIf { it.isNotBlank() }
                ?: throw NetworkConfigurationException(
                    "Native asset symbol must come from the trusted chain profile",
                )
        } else {
            decodeTokenSymbol(resultString(results[index]))
        }
        if (expectedSymbol != null && actualSymbol != expectedSymbol) {
            throw NetworkConfigurationException(
                "Payment asset symbol $actualSymbol does not match configured symbol $expectedSymbol",
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

    private fun balanceParams(
        address: EvmAddress,
        blockTag: String = LATEST_BLOCK,
    ): JsonArray = JsonArray().apply {
        add(address.value)
        add(blockTag)
    }

    private fun assetBalanceCall(
        asset: EvmAddress,
        holder: EvmAddress,
        blockTag: String,
    ): RpcCall = if (NativeAsset.isNative(asset)) {
        RpcCall("eth_getBalance", balanceParams(holder, blockTag))
    } else {
        RpcCall(
            "eth_call",
            ethCallParams(asset, abiFunction("balanceOf(address)", holder), blockTag),
        )
    }

    private fun decodeAssetBalance(result: JsonElement, isNative: Boolean): BigInteger =
        if (isNative) {
            parseQuantity(resultString(result), "eth_getBalance")
        } else {
            BigInteger(1, decodeWord(resultString(result), "balanceOf result"))
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

    private fun rpcResult(
        method: String,
        params: JsonArray,
        retryOnThrottle: Boolean = false,
    ): JsonElement = rpcResults(
        listOf(RpcCall(method, params)),
        retryOnThrottle = retryOnThrottle,
    ).single()

    private fun rpcResults(
        calls: List<RpcCall>,
        toleratedErrorIndices: Set<Int> = emptySet(),
        retryOnThrottle: Boolean = false,
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

        var retryCount = 0
        while (true) {
            try {
                return executeRpcResults(requests, requestBody, toleratedErrorIndices)
            } catch (error: RpcException) {
                if (
                    !retryOnThrottle ||
                    error !is RpcRateLimit ||
                    retryCount >= MAX_THROTTLE_RETRIES
                ) {
                    throw error
                }
                // Keep InterruptedException outside all broad transport catches so a coroutine
                // runInterruptible caller can cancel validation while it is backing off.
                retrySleep(retryDelayMillis(retryCount, error.retryAfterMillis))
                retryCount += 1
            }
        }
    }

    private fun executeRpcResults(
        requests: List<Pair<Long, JsonObject>>,
        requestBody: String,
        toleratedErrorIndices: Set<Int>,
    ): List<JsonElement> {
        val responseText = try {
            transport.execute(requestBody)
        } catch (error: RpcException) {
            throw error
        } catch (_: Exception) {
            // RPC URLs can carry client credentials. Do not retain the transport exception as a
            // cause because DNS/TLS errors can include the credential-bearing host or full URL.
            throw RpcException("JSON-RPC transport failed")
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
                val (code, message) = decodeRpcError(error.asJsonObject)
                if (isRateLimitRpcError(code, message)) {
                    throw RpcRateLimitResponseException(code, message)
                }
                if (id in toleratedErrorIds) {
                    resultsById[id] = com.google.gson.JsonNull.INSTANCE
                    return@forEach
                }
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

    private fun retryDelayMillis(retryCount: Int, retryAfterMillis: Long?): Long {
        val backoffMillis = if (retryCount == 0) {
            BASE_RETRY_DELAY_MILLIS
        } else {
            BASE_RETRY_DELAY_MILLIS * RETRY_BACKOFF_MULTIPLIER
        }
        val providerDelayMillis = retryAfterMillis
            ?.coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
            ?: 0L
        val jitterMillis = retryJitterMillis().coerceIn(0L, MAX_RETRY_JITTER_MILLIS)
        return max(backoffMillis, providerDelayMillis) + jitterMillis
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
        ): ReadOnlyRpcClient = ReadOnlyRpcClient(
            config = config,
            transport = RpcTransport(execute),
            retrySleep = {},
            retryJitterMillis = { 0L },
        )

        /** Test seam for deterministic retry timing without real sleeps. */
        @JvmSynthetic
        internal fun forTest(
            config: NetworkConfig,
            retrySleep: (Long) -> Unit,
            retryJitterMillis: () -> Long,
            execute: (String) -> String,
        ): ReadOnlyRpcClient = ReadOnlyRpcClient(
            config = config,
            transport = RpcTransport(execute),
            retrySleep = retrySleep,
            retryJitterMillis = retryJitterMillis,
        )

        private fun checkedTimeout(value: Int): Int {
            require(value > 0) { "RPC timeout must be greater than zero" }
            return value
        }

        private fun quantityHex(value: Long): String = "0x" + value.toString(16)

        private fun parseQuantity(value: String, label: String): BigInteger {
            if (!QUANTITY_PATTERN.matches(value)) {
                throw RpcException("$label returned a malformed hex quantity")
            }
            return BigInteger(value.substring(2), 16)
        }

        private fun decodeRpcError(error: JsonObject): Pair<Int, String> {
            val codePrimitive = error.get("code")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isNumber }
                ?: throw RpcException("JSON-RPC error has an invalid code")
            val code = codePrimitive.asString
                .takeIf(RPC_ERROR_CODE_PATTERN::matches)
                ?.toIntOrNull()
                ?: throw RpcException("JSON-RPC error has an invalid code")
            val message = error.get("message")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isString }
                ?.asString
                ?: throw RpcException("JSON-RPC error has an invalid message")
            return code to message
        }

        private fun isRateLimitRpcError(code: Int, message: String): Boolean =
            (code == -32016 || code == -32005) && RATE_LIMIT_MESSAGE_PATTERN.containsMatchIn(message)

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
        private val RPC_ERROR_CODE_PATTERN = Regex("^-?(0|[1-9][0-9]*)$")
        private const val MAX_SYMBOL_UTF8_BYTES = 32
        private const val MAX_BATCH_SIZE = 10
        private const val ERC20_NETWORK_VALIDATION_CALL_COUNT = 9
        private const val NATIVE_NETWORK_VALIDATION_CALL_COUNT = 7
        private const val MAX_CONCURRENT_CALL_SET_SIZE = 20
        private const val MAX_THROTTLE_RETRIES = 2
        private const val BASE_RETRY_DELAY_MILLIS = 1_000L
        private const val RETRY_BACKOFF_MULTIPLIER = 3L
        private const val MAX_RETRY_JITTER_MILLIS = 250L
        private val RATE_LIMIT_MESSAGE_PATTERN = Regex(
            "\\brate[\\s_-]*limit(?:ed|ing)?\\b",
            RegexOption.IGNORE_CASE,
        )
        private val TRANSFER_EVENT_TOPIC = Hex.encode(
            Keccak256.digest("Transfer(address,address,uint256)".toByteArray(StandardCharsets.US_ASCII)),
        )
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
        if (status == 429) {
            stream?.close()
            throw rpcHttpFailure(
                status = status,
                retryAfterHeader = connection.getHeaderField("Retry-After"),
                nowEpochMillis = System.currentTimeMillis(),
            )
        }
        val response = stream?.use(::readLimitedUtf8).orEmpty()
        if (status !in 200..299) {
            throw rpcHttpFailure(
                status = status,
                retryAfterHeader = connection.getHeaderField("Retry-After"),
                nowEpochMillis = System.currentTimeMillis(),
            )
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
        // Full canonical blocks are needed only for direct native-payment attribution and can be
        // larger than the earlier metadata-only RPC ceiling. Keep a finite defensive bound.
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

internal fun rpcHttpFailure(
    status: Int,
    retryAfterHeader: String?,
    nowEpochMillis: Long,
): RpcException = if (status == 429) {
    RpcHttpRateLimitException(
        retryAfterMillis = parseBoundedRetryAfterMillis(retryAfterHeader, nowEpochMillis),
    )
} else {
    RpcException("RPC HTTP request failed with status $status")
}

internal fun parseBoundedRetryAfterMillis(
    header: String?,
    nowEpochMillis: Long,
): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all(Char::isDigit)) {
        val seconds = value.toLongOrNull() ?: return MAX_RETRY_AFTER_MILLIS
        return if (seconds >= MAX_RETRY_AFTER_MILLIS / 1_000L) {
            MAX_RETRY_AFTER_MILLIS
        } else {
            seconds * 1_000L
        }
    }
    val targetEpochMillis = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    } catch (_: Exception) {
        return null
    }
    if (targetEpochMillis <= nowEpochMillis) return 0L
    val difference = targetEpochMillis - nowEpochMillis
    return if (difference < 0L) MAX_RETRY_AFTER_MILLIS else {
        difference.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }
}

private const val MAX_RETRY_AFTER_MILLIS = 5_000L
