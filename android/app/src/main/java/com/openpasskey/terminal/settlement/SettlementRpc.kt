package com.openpasskey.terminal.settlement

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NativeAsset
import com.openpasskey.terminal.data.model.SettlementFeeMode
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class SettlementFeeQuote(
    val mode: SettlementFeeMode,
    val gasPrice: BigInteger? = null,
    val maxPriorityFeePerGas: BigInteger? = null,
    val maxFeePerGas: BigInteger? = null
) {
    init {
        when (mode) {
            SettlementFeeMode.LEGACY -> require(gasPrice?.signum() == 1) { "Legacy gas price must be positive" }
            SettlementFeeMode.EIP1559 -> {
                require(maxPriorityFeePerGas?.signum() == 1) { "Priority fee must be positive" }
                require(maxFeePerGas?.signum() == 1) { "Maximum fee must be positive" }
                require(maxFeePerGas >= maxPriorityFeePerGas) { "Maximum fee must cover the priority fee" }
            }
        }
    }

    val maximumPerGas: BigInteger
        get() = when (mode) {
            SettlementFeeMode.LEGACY -> requireNotNull(gasPrice)
            SettlementFeeMode.EIP1559 -> requireNotNull(maxFeePerGas)
        }

    fun maximumCost(gasLimit: BigInteger): BigInteger {
        require(gasLimit.signum() == 1) { "Gas limit must be positive" }
        return gasLimit.multiply(maximumPerGas)
    }
}

object SettlementFeePolicy {
    private val HUNDRED = BigInteger.valueOf(100)
    private val LEGACY_BUFFER_PERCENT = BigInteger.valueOf(120)
    private val BASE_FEE_MULTIPLIER = BigInteger.valueOf(2)
    private val MIN_PRIORITY_FEE_WEI = BigInteger.valueOf(1_000_000)

    fun quote(baseFeePerGas: BigInteger?, recommendedGasPrice: BigInteger): SettlementFeeQuote {
        require(recommendedGasPrice.signum() == 1) { "RPC gas price must be positive" }
        if (baseFeePerGas == null) {
            return SettlementFeeQuote(
                mode = SettlementFeeMode.LEGACY,
                gasPrice = recommendedGasPrice.multiply(LEGACY_BUFFER_PERCENT).divide(HUNDRED)
            )
        }
        require(baseFeePerGas.signum() >= 0) { "Base fee must not be negative" }
        val priority = recommendedGasPrice.subtract(baseFeePerGas).max(MIN_PRIORITY_FEE_WEI)
        val maxFee = recommendedGasPrice.max(baseFeePerGas.multiply(BASE_FEE_MULTIPLIER).add(priority))
        return SettlementFeeQuote(
            mode = SettlementFeeMode.EIP1559,
            maxPriorityFeePerGas = priority,
            maxFeePerGas = maxFee
        )
    }

    fun exceedsConfirmedCost(confirmedMaximumCost: BigInteger, freshMaximumCost: BigInteger): Boolean {
        require(confirmedMaximumCost.signum() == 1 && freshMaximumCost.signum() == 1)
        return freshMaximumCost > confirmedMaximumCost.multiply(BigInteger.valueOf(120)).divide(HUNDRED)
    }
}

data class SettlementBalanceRequirement(
    val maximumGasCost: BigInteger,
    val safetyReserve: BigInteger,
    val requiredBalance: BigInteger
)

/** Conservative local reserve for fee drift and chains that charge an additional L1 data fee. */
object SettlementBalancePolicy {
    private val MINIMUM_RESERVE_WEI = BigInteger("100000000000000") // 0.0001 native token
    private val RESERVE_PERCENT = BigInteger.valueOf(30)
    private val HUNDRED = BigInteger.valueOf(100)

    fun requirement(gasLimit: BigInteger, quote: SettlementFeeQuote): SettlementBalanceRequirement {
        val maximum = quote.maximumCost(gasLimit)
        val reserve = maximum.multiply(RESERVE_PERCENT).divide(HUNDRED).max(MINIMUM_RESERVE_WEI)
        return SettlementBalanceRequirement(maximum, reserve, maximum.add(reserve))
    }
}

data class SettlementReceipt(
    val successful: Boolean,
    val blockNumber: Long,
    val blockHash: String,
    val transactionHash: String,
    val logs: List<SettlementReceiptLog>
)

/** One transport-bounded recovery read for an already observed receipt block. */
data class SettlementRecoverySnapshot(
    val receipt: SettlementReceipt?,
    val canonicalReceiptBlockHash: String?,
    val latestBlockNumber: Long,
)

data class SettlementReceiverSafetyRead(
    val tokenAddress: String,
    val receiverAddress: String,
    val canonicalBlockNumber: Long,
)

data class SettlementPreflightRequest(
    val operatorAddress: String,
    val vaultAddress: String,
    val callData: String,
    val receivers: List<SettlementReceiverSafetyRead>,
)

data class SettlementPreflightSnapshot(
    val chainId: Long,
    val ownerAddress: String?,
    val operatorListed: Boolean,
    val canonicalBlockHashes: List<String?>,
    val canonicalBlockHashesAfter: List<String?>,
    val receiverBalances: List<BigInteger>,
    val nonce: BigInteger,
    val gasLimit: BigInteger?,
    val feeQuote: SettlementFeeQuote,
    val nativeBalance: BigInteger,
)

class SettlementRpcException(
    message: String,
    val rpcCode: Int? = null,
    val knownTransactionResponse: Boolean = false,
) : RuntimeException(message)

interface SettlementChainClient : Closeable {
    fun chainId(): Long
    /** Pending balance is used for fee readiness and accounts for accepted withdrawals. */
    fun nativeBalance(address: String): BigInteger
    /** Latest canonical balance is required by destructive key-reset safety. */
    fun latestNativeBalance(address: String): BigInteger
    fun tokenBalance(tokenAddress: String, accountAddress: String): BigInteger
    fun isOperator(vaultAddress: String, operatorAddress: String): Boolean
    fun owner(vaultAddress: String): String
    fun simulate(fromAddress: String, toAddress: String, callData: String)
    fun pendingNonce(address: String): BigInteger
    fun estimateGas(fromAddress: String, toAddress: String, callData: String, nonce: BigInteger): BigInteger
    fun feeQuote(): SettlementFeeQuote
    fun sendRawTransaction(signedTransaction: String): String
    fun transactionReceipt(txHash: String): SettlementReceipt?
    fun blockNumber(): Long
    /** Canonical block hash for an exact height, or null when that height is unavailable. */
    fun canonicalBlockHash(blockNumber: Long): String?
    fun settlementRecoverySnapshot(
        txHash: String,
        expectedReceiptBlock: Long,
    ): SettlementRecoverySnapshot

    fun canonicalBlockHashes(blockNumbers: List<Long>): List<String?> =
        blockNumbers.map(::canonicalBlockHash)

    /**
     * Bounded live safety snapshot. Production sends the independent reads as one JSON-RPC batch;
     * the optional estimate is the only dependent second round trip because it requires the nonce.
     */
    fun settlementPreflight(
        request: SettlementPreflightRequest,
        includeGasEstimate: Boolean,
    ): SettlementPreflightSnapshot {
        val hashes = request.receivers.map { canonicalBlockHash(it.canonicalBlockNumber) }
        val remoteChain = chainId()
        val listed = isOperator(request.vaultAddress, request.operatorAddress)
        // owner() is a compatibility fallback for vaults that authorize their owner without
        // listing it as an operator. A listed operator must not be rejected merely because an
        // optional owner() implementation is absent or temporarily unavailable.
        val owner = if (listed) null else owner(request.vaultAddress)
        val balances = request.receivers.map { tokenBalance(it.tokenAddress, it.receiverAddress) }
        simulate(request.operatorAddress, request.vaultAddress, request.callData)
        val nonce = pendingNonce(request.operatorAddress)
        val gasLimit = if (includeGasEstimate) {
            estimateGas(
                request.operatorAddress,
                request.vaultAddress,
                request.callData,
                nonce,
            )
        } else {
            null
        }
        val hashesAfter = request.receivers.map { canonicalBlockHash(it.canonicalBlockNumber) }
        return SettlementPreflightSnapshot(
            chainId = remoteChain,
            ownerAddress = owner,
            operatorListed = listed,
            canonicalBlockHashes = hashes,
            canonicalBlockHashesAfter = hashesAfter,
            receiverBalances = balances,
            nonce = nonce,
            gasLimit = gasLimit,
            feeQuote = feeQuote(),
            nativeBalance = nativeBalance(request.operatorAddress),
        )
    }
}

class Web3jSettlementChainClient(rpcUrl: String) : SettlementChainClient {
    private val lifecycleLock = ReentrantReadWriteLock()
    private var endpoint: OwnedRpcEndpoint? = createOwnedRpcEndpoint(rpcUrl)

    override fun chainId(): Long = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethChainId().send()
        response.throwIfError("eth_chainId")
        response.chainId.toLongExactCompat("chain ID")
    }

    override fun nativeBalance(address: String): BigInteger = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethGetBalance(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("eth_getBalance")
        response.balance
    }

    override fun latestNativeBalance(address: String): BigInteger = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethGetBalance(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.LATEST,
        ).send()
        response.throwIfError("eth_getBalance")
        response.balance
    }

    override fun tokenBalance(tokenAddress: String, accountAddress: String): BigInteger {
        if (NativeAsset.isNative(EvmAddress.parse(tokenAddress))) {
            return nativeBalance(accountAddress)
        }
        return withEndpoint { endpoint ->
            val response = endpoint.web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    EvmAddress.parse(tokenAddress).value,
                    SettlementAbi.encodeBalanceOf(accountAddress)
                ),
                DefaultBlockParameterName.PENDING
            ).send()
            response.throwIfError("balanceOf eth_call")
            SettlementAbi.decodeUint256Word(response.value)
        }
    }

    override fun isOperator(vaultAddress: String, operatorAddress: String): Boolean =
        withEndpoint { endpoint ->
            val response = endpoint.web3j.ethCall(
                Transaction.createEthCallTransaction(
                    EvmAddress.parse(operatorAddress).value,
                    EvmAddress.parse(vaultAddress).value,
                    SettlementAbi.encodeIsOperator(operatorAddress)
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            response.throwIfError("isOperator eth_call")
            SettlementAbi.decodeIsOperator(response.value)
        }

    override fun owner(vaultAddress: String): String = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethCall(
            Transaction.createEthCallTransaction(
                null,
                EvmAddress.parse(vaultAddress).value,
                SettlementAbi.encodeOwner()
            ),
            DefaultBlockParameterName.LATEST
        ).send()
        response.throwIfError("owner eth_call")
        SettlementAbi.decodeOwner(response.value)
    }

    override fun simulate(fromAddress: String, toAddress: String, callData: String) =
        withEndpoint { endpoint ->
            val response = endpoint.web3j.ethCall(
                Transaction.createEthCallTransaction(
                    EvmAddress.parse(fromAddress).value,
                    EvmAddress.parse(toAddress).value,
                    callData
                ),
                DefaultBlockParameterName.PENDING
            ).send()
            response.throwIfError("sweepSessions simulation")
        }

    override fun pendingNonce(address: String): BigInteger = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethGetTransactionCount(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("eth_getTransactionCount")
        response.transactionCount
    }

    override fun estimateGas(
        fromAddress: String,
        toAddress: String,
        callData: String,
        nonce: BigInteger
    ): BigInteger = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethEstimateGas(
            Transaction.createFunctionCallTransaction(
                EvmAddress.parse(fromAddress).value,
                nonce,
                null,
                null,
                EvmAddress.parse(toAddress).value,
                callData
            )
        ).send()
        response.throwIfError("eth_estimateGas")
        require(response.amountUsed.signum() == 1) { "eth_estimateGas returned zero" }
        // A percentage buffer plus a fixed margin handles modest state changes between review/signing.
        response.amountUsed.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(100))
            .add(BigInteger.valueOf(15_000))
    }

    override fun feeQuote(): SettlementFeeQuote = withEndpoint { endpoint ->
        val priceResponse = endpoint.web3j.ethGasPrice().send()
        priceResponse.throwIfError("eth_gasPrice")
        val blockResponse = endpoint.web3j
            .ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
            .send()
        blockResponse.throwIfError("eth_getBlockByNumber")
        val baseFee = blockResponse.block.baseFeePerGas?.let(Numeric::decodeQuantity)
        SettlementFeePolicy.quote(baseFee, priceResponse.gasPrice)
    }

    override fun sendRawTransaction(signedTransaction: String): String = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethSendRawTransaction(signedTransaction).send()
        response.throwIfError("eth_sendRawTransaction")
        response.transactionHash
            ?: throw SettlementRpcException("eth_sendRawTransaction returned no transaction hash")
    }

    override fun transactionReceipt(txHash: String): SettlementReceipt? = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethGetTransactionReceipt(txHash).send()
        response.throwIfError("eth_getTransactionReceipt")
        val receipt = response.transactionReceipt.orElse(null) ?: return@withEndpoint null
        val status = receipt.status ?: throw SettlementRpcException("Receipt has no execution status")
        val successful = when (status.lowercase()) {
            "0x1", "1" -> true
            "0x0", "0" -> false
            else -> throw SettlementRpcException("Receipt has invalid execution status")
        }
        SettlementReceipt(
            successful = successful,
            blockNumber = receipt.blockNumber.toLongExactCompat("receipt block number"),
            blockHash = requireNotNull(receipt.blockHash) { "Receipt has no block hash" },
            transactionHash = requireNotNull(receipt.transactionHash) { "Receipt has no transaction hash" },
            logs = receipt.logs.map { log ->
                SettlementReceiptLog(
                    address = log.address,
                    topics = log.topics ?: emptyList(),
                    data = log.data ?: "0x",
                    transactionHash = log.transactionHash ?: receipt.transactionHash,
                    blockHash = log.blockHash ?: receipt.blockHash,
                    logIndex = log.logIndex?.toLongExactCompat("receipt log index"),
                    removed = log.isRemoved
                )
            }
        )
    }

    override fun blockNumber(): Long = withEndpoint { endpoint ->
        val response = endpoint.web3j.ethBlockNumber().send()
        response.throwIfError("eth_blockNumber")
        response.blockNumber.toLongExactCompat("block number")
    }

    override fun canonicalBlockHash(blockNumber: Long): String? = withEndpoint { endpoint ->
        require(blockNumber >= 0) { "Block number cannot be negative" }
        val response = endpoint.web3j.ethGetBlockByNumber(
            DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
            false,
        ).send()
        response.throwIfError("eth_getBlockByNumber")
        val block = response.block ?: return@withEndpoint null
        val returnedNumber = block.number
            ?: throw SettlementRpcException("eth_getBlockByNumber result has no block number")
        if (returnedNumber != BigInteger.valueOf(blockNumber)) {
            throw SettlementRpcException(
                "eth_getBlockByNumber returned block $returnedNumber for requested block $blockNumber",
            )
        }
        val hash = block.hash
            ?: throw SettlementRpcException("eth_getBlockByNumber result has no block hash")
        if (!BLOCK_HASH_PATTERN.matches(hash)) {
            throw SettlementRpcException("eth_getBlockByNumber returned a malformed block hash")
        }
        hash.lowercase()
    }

    override fun canonicalBlockHashes(blockNumbers: List<Long>): List<String?> =
        withEndpoint { endpoint ->
            require(blockNumbers.size <= MAX_BATCH_RECEIVERS) {
                "Canonical hash batch supports at most $MAX_BATCH_RECEIVERS blocks"
            }
            if (blockNumbers.isEmpty()) return@withEndpoint emptyList()
            val results = endpoint.batch.executeChunked(blockNumbers.map { blockNumber ->
                require(blockNumber >= 0) { "Block number cannot be negative" }
                SettlementRpcCall(
                    "eth_getBlockByNumber",
                    JsonArray().apply {
                        add(quantityHex(blockNumber))
                        add(false)
                    },
                )
            })
            results.mapIndexed { index, result ->
                decodeBlockHash(result, blockNumbers[index])
            }
        }

    override fun settlementRecoverySnapshot(
        txHash: String,
        expectedReceiptBlock: Long,
    ): SettlementRecoverySnapshot = withEndpoint { endpoint ->
        executeSettlementRecoverySnapshot(
            batch = endpoint.batch,
            txHash = txHash,
            expectedReceiptBlock = expectedReceiptBlock,
        )
    }

    override fun settlementPreflight(
        request: SettlementPreflightRequest,
        includeGasEstimate: Boolean,
    ): SettlementPreflightSnapshot = withEndpoint { endpoint ->
        executeSettlementPreflight(
            batch = endpoint.batch,
            request = request,
            includeGasEstimate = includeGasEstimate,
        )
    }

    override fun close() {
        val closing = lifecycleLock.write {
            val current = endpoint
            endpoint = null
            current
        }
        closing?.close()
    }

    private inline fun <T> withEndpoint(block: (OwnedRpcEndpoint) -> T): T = try {
        lifecycleLock.read {
            val current = endpoint
                ?: throw SettlementRpcException("Settlement RPC client is closed")
            block(current)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SettlementRpcException) {
        throw error
    } catch (_: Exception) {
        // Web3j embeds provider response bodies and request details in several exception types.
        // Replace them at this boundary without retaining a credential-bearing cause.
        throw SettlementRpcException("RPC transport failed")
    }

    private fun org.web3j.protocol.core.Response<*>.throwIfError(operation: String) {
        if (hasError()) {
            val code = error.code
            throw SettlementRpcException(
                "$operation failed with JSON-RPC error code $code",
                rpcCode = code,
                knownTransactionResponse = operation == "eth_sendRawTransaction" &&
                    isKnownTransactionProviderResponse(error.message),
            )
        }
    }

    private companion object {
        const val MAX_BATCH_RECEIVERS = SettlementAbi.MAX_BATCH_SIZE
    }
}

/** Reads receipt identity, its canonical block hash, and the current head in one HTTP batch. */
internal fun executeSettlementRecoverySnapshot(
    batch: StrictSettlementRpcBatchClient,
    txHash: String,
    expectedReceiptBlock: Long,
): SettlementRecoverySnapshot {
    require(TRANSACTION_HASH_PATTERN.matches(txHash)) { "Transaction hash is malformed" }
    require(expectedReceiptBlock >= 0) { "Receipt block cannot be negative" }
    val results = batch.execute(
        listOf(
            SettlementRpcCall(
                "eth_getTransactionReceipt",
                JsonArray().apply { add(txHash) },
            ),
            settlementBlockHashCall(expectedReceiptBlock),
            SettlementRpcCall("eth_blockNumber", JsonArray()),
        ),
    )
    return SettlementRecoverySnapshot(
        receipt = decodeSettlementReceipt(results[0]),
        canonicalReceiptBlockHash = decodeBlockHash(results[1], expectedReceiptBlock),
        latestBlockNumber = parseQuantity(
            resultString(results[2]),
            "eth_blockNumber",
        ).toLongExactCompat("block number"),
    )
}

/**
 * Three ordered network waves preserve the canonical cursor bracket while remaining constant at
 * the protocol maximum: cursor-before, mutable reads, then cursor-after plus dependent reads.
 */
internal fun executeSettlementPreflight(
    batch: StrictSettlementRpcBatchClient,
    request: SettlementPreflightRequest,
    includeGasEstimate: Boolean,
): SettlementPreflightSnapshot {
    require(request.receivers.isNotEmpty()) { "Settlement safety batch must not be empty" }
    require(request.receivers.size <= SettlementAbi.MAX_BATCH_SIZE) {
        "Settlement safety batch supports at most ${SettlementAbi.MAX_BATCH_SIZE} receivers"
    }
    val operator = EvmAddress.parse(request.operatorAddress).value
    val vault = EvmAddress.parse(request.vaultAddress).value

    // Wave 1: establish every saved canonical cursor before any balance is sampled.
    val requestedCursorBlocks = request.receivers.map { it.canonicalBlockNumber }
    val uniqueCursorBlocks = requestedCursorBlocks.distinct()
    val hashCalls = uniqueCursorBlocks.map(::settlementBlockHashCall)
    val hashesByBlock = batch.executeChunked(hashCalls).mapIndexed { index, result ->
        val block = uniqueCursorBlocks[index]
        block to decodeBlockHash(result, block)
    }.toMap()
    val hashes = requestedCursorBlocks.map(hashesByBlock::getValue)

    // Wave 2: all mutually independent mutable reads. At 20 invoices this is 27 calls split into
    // three concurrent strict batches; invoice count therefore does not add latency waves.
    val primaryCalls = buildList {
        add(SettlementRpcCall("eth_chainId", JsonArray()))
        add(settlementEthCall(vault, SettlementAbi.encodeIsOperator(operator), RPC_LATEST_BLOCK, operator))
        request.receivers.forEach { receiver -> add(settlementBalanceCall(receiver)) }
        add(settlementEthCall(vault, request.callData, RPC_PENDING_BLOCK, operator))
        add(
            SettlementRpcCall(
                "eth_getTransactionCount",
                JsonArray().apply {
                    add(operator)
                    add(RPC_PENDING_BLOCK)
                },
            ),
        )
        add(SettlementRpcCall("eth_gasPrice", JsonArray()))
        add(
            SettlementRpcCall(
                "eth_getBlockByNumber",
                JsonArray().apply {
                    add(RPC_LATEST_BLOCK)
                    add(false)
                },
            ),
        )
        add(
            SettlementRpcCall(
                "eth_getBalance",
                JsonArray().apply {
                    add(operator)
                    add(RPC_PENDING_BLOCK)
                },
            ),
        )
    }
    val primary = batch.executeChunked(primaryCalls)
    var primaryIndex = 0
    val chain = parseQuantity(resultString(primary[primaryIndex++]), "eth_chainId")
        .toLongExactCompat("chain ID")
    val listed = SettlementAbi.decodeIsOperator(resultString(primary[primaryIndex++]))
    val balances = request.receivers.map { receiver ->
        val result = resultString(primary[primaryIndex++])
        if (NativeAsset.isNative(EvmAddress.parse(receiver.tokenAddress))) {
            parseQuantity(result, "eth_getBalance")
        } else {
            SettlementAbi.decodeUint256Word(result)
        }
    }
    resultString(primary[primaryIndex++]) // A reverted simulation is represented as an RPC error.
    val nonce = parseQuantity(resultString(primary[primaryIndex++]), "eth_getTransactionCount")
    val gasPrice = parseQuantity(resultString(primary[primaryIndex++]), "eth_gasPrice")
    val feeBlock = primary[primaryIndex++].takeIf { it.isJsonObject }?.asJsonObject
        ?: throw SettlementRpcException("eth_getBlockByNumber returned no latest block")
    val baseFee = feeBlock.get("baseFeePerGas")
        ?.takeUnless { it.isJsonNull }
        ?.let { element -> parseQuantity(resultString(element), "baseFeePerGas") }
    val nativeBalance = parseQuantity(resultString(primary[primaryIndex]), "eth_getBalance")

    // Wave 3: close the cursor bracket. owner() is fetched only for an unlisted operator, and gas
    // estimation is included only when the prior <=60-second estimate cannot be reused.
    val finalCalls = buildList {
        addAll(hashCalls)
        if (!listed) add(settlementEthCall(vault, SettlementAbi.encodeOwner(), RPC_LATEST_BLOCK))
        if (includeGasEstimate) {
            add(
                SettlementRpcCall(
                    "eth_estimateGas",
                    JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("from", operator)
                            addProperty("to", vault)
                            addProperty("data", request.callData)
                            addProperty("nonce", quantityHex(nonce))
                        })
                    },
                ),
            )
        }
    }
    val final = batch.executeChunked(finalCalls)
    var finalIndex = 0
    val hashesAfterByBlock = uniqueCursorBlocks.associateWith { block ->
        decodeBlockHash(final[finalIndex++], block)
    }
    val hashesAfter = requestedCursorBlocks.map(hashesAfterByBlock::getValue)
    val owner = if (listed) null else {
        SettlementAbi.decodeOwner(resultString(final[finalIndex++]))
    }
    val gasLimit = if (includeGasEstimate) {
        val estimate = parseQuantity(resultString(final[finalIndex]), "eth_estimateGas")
        require(estimate.signum() == 1) { "eth_estimateGas returned zero" }
        estimate.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(100))
            .add(BigInteger.valueOf(15_000))
    } else null

    return SettlementPreflightSnapshot(
        chainId = chain,
        ownerAddress = owner,
        operatorListed = listed,
        canonicalBlockHashes = hashes,
        canonicalBlockHashesAfter = hashesAfter,
        receiverBalances = balances,
        nonce = nonce,
        gasLimit = gasLimit,
        feeQuote = SettlementFeePolicy.quote(baseFee, gasPrice),
        nativeBalance = nativeBalance,
    )
}

private fun settlementBlockHashCall(blockNumber: Long): SettlementRpcCall {
    require(blockNumber >= 0) { "Block number cannot be negative" }
    return SettlementRpcCall(
        "eth_getBlockByNumber",
        JsonArray().apply {
            add(quantityHex(blockNumber))
            add(false)
        },
    )
}

private fun settlementEthCall(
    to: String,
    data: String,
    blockTag: String,
    from: String? = null,
): SettlementRpcCall = SettlementRpcCall(
    "eth_call",
    JsonArray().apply {
        add(JsonObject().apply {
            from?.let { addProperty("from", it) }
            addProperty("to", to)
            addProperty("data", data)
        })
        add(blockTag)
    },
)

private fun settlementBalanceCall(receiver: SettlementReceiverSafetyRead): SettlementRpcCall {
    val asset = EvmAddress.parse(receiver.tokenAddress)
    val holder = EvmAddress.parse(receiver.receiverAddress)
    return if (NativeAsset.isNative(asset)) {
        SettlementRpcCall(
            "eth_getBalance",
            JsonArray().apply {
                add(holder.value)
                add(RPC_PENDING_BLOCK)
            },
        )
    } else {
        settlementEthCall(
            asset.value,
            SettlementAbi.encodeBalanceOf(holder.value),
            RPC_PENDING_BLOCK,
        )
    }
}

private const val RPC_LATEST_BLOCK = "latest"
private const val RPC_PENDING_BLOCK = "pending"

internal data class SettlementRpcCall(val method: String, val params: JsonArray)

internal class StrictSettlementRpcBatchClient private constructor(
    private val executeBody: (String) -> String,
) {
    constructor(rpcUrl: String, httpClient: OkHttpClient) : this(
        executeBody = { requestBody ->
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                    ?: throw SettlementRpcException("RPC HTTP response body is empty")
                val responseText = body.byteStream().use(::readLimitedUtf8)
                if (!response.isSuccessful) {
                    throw SettlementRpcException("RPC HTTP request failed with status ${response.code}")
                }
                if (responseText.isEmpty()) {
                    throw SettlementRpcException("RPC HTTP response body is empty")
                }
                responseText
            }
        },
    )

    fun execute(calls: List<SettlementRpcCall>): List<JsonElement> {
        require(calls.isNotEmpty()) { "Settlement JSON-RPC batch must not be empty" }
        require(calls.size <= MAX_BATCH_ITEMS) {
            "Settlement JSON-RPC batch must contain at most $MAX_BATCH_ITEMS requests"
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
        val requestBody = JsonArray().apply { requests.forEach { add(it.second) } }.toString()
        val responseRoot = try {
            JsonParser.parseString(executeBody(requestBody))
        } catch (error: SettlementRpcException) {
            throw error
        } catch (error: Exception) {
            throw SettlementRpcException("JSON-RPC batch response is not valid JSON")
        }
        if (!responseRoot.isJsonArray) {
            throw SettlementRpcException("JSON-RPC batch response is not an array")
        }
        val responses = responseRoot.asJsonArray
        if (responses.size() != requests.size) {
            throw SettlementRpcException("JSON-RPC response count does not match request count")
        }
        val expectedIds = requests.mapTo(linkedSetOf()) { it.first }
        val resultsById = linkedMapOf<Long, JsonElement>()
        responses.forEach { element ->
            if (!element.isJsonObject) {
                throw SettlementRpcException("JSON-RPC batch response contains a non-object")
            }
            val response = element.asJsonObject
            val version = response.get("jsonrpc")
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isString }
                ?.asString
            if (version != "2.0") {
                throw SettlementRpcException("JSON-RPC response has an invalid version")
            }
            val idElement = response.get("id")?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber
            } ?: throw SettlementRpcException("JSON-RPC response has an invalid ID")
            val idText = idElement.asString
            if (!STRICT_NUMERIC_ID_PATTERN.matches(idText)) {
                throw SettlementRpcException("JSON-RPC response has an invalid ID")
            }
            val id = idText.toLongOrNull()
                ?: throw SettlementRpcException("JSON-RPC response has an invalid ID")
            if (id !in expectedIds) {
                throw SettlementRpcException("JSON-RPC response ID does not match any request")
            }
            if (resultsById.containsKey(id)) {
                throw SettlementRpcException("JSON-RPC response contains a duplicate ID")
            }
            val rpcError = response.get("error")?.takeUnless { it.isJsonNull }
            if (rpcError != null) {
                if (!rpcError.isJsonObject) {
                    throw SettlementRpcException("JSON-RPC error is not an object")
                }
                val code = rpcError.asJsonObject.get("code")?.asInt
                throw SettlementRpcException(
                    "JSON-RPC request failed with error code ${code ?: 0}",
                    code,
                )
            }
            resultsById[id] = response.get("result")
                ?: throw SettlementRpcException("JSON-RPC response is missing result")
        }
        return requests.map { (id, _) ->
            resultsById[id]
                ?: throw SettlementRpcException("JSON-RPC batch response is missing request ID $id")
        }
    }

    /** Executes max-10 batches concurrently through a bounded process-wide pool, preserving order. */
    fun executeChunked(calls: List<SettlementRpcCall>): List<JsonElement> {
        require(calls.isNotEmpty()) { "Settlement JSON-RPC call set must not be empty" }
        val chunks = calls.chunked(MAX_BATCH_ITEMS)
        if (chunks.size == 1) return execute(chunks.single())
        val futures = chunks.map { chunk ->
            CompletableFuture.supplyAsync({ execute(chunk) }, BATCH_EXECUTOR)
        }
        return try {
            futures.flatMap(CompletableFuture<List<JsonElement>>::get)
        } catch (error: ExecutionException) {
            futures.forEach { it.cancel(true) }
            val cause = error.cause
            if (cause is SettlementRpcException) throw cause
            throw SettlementRpcException("Settlement JSON-RPC batch failed")
        } catch (error: InterruptedException) {
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw SettlementRpcException("Settlement JSON-RPC batch was interrupted")
        }
    }

    companion object {
        private val requestIds = AtomicLong()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal const val MAX_BATCH_ITEMS = 10
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val STRICT_NUMERIC_ID_PATTERN = Regex("^(0|[1-9][0-9]*)$")
        private val BATCH_EXECUTOR = Executors.newFixedThreadPool(5) { runnable ->
            Thread(runnable, "opk-rpc-batch").apply { isDaemon = true }
        }

        @JvmSynthetic
        internal fun forTest(execute: (String) -> String): StrictSettlementRpcBatchClient =
            StrictSettlementRpcBatchClient(execute)

        private fun readLimitedUtf8(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw SettlementRpcException("RPC HTTP response exceeds size limit")
                }
                output.write(buffer, 0, count)
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }
}

private class OwnedRpcEndpoint(
    val web3j: Web3j,
    val batch: StrictSettlementRpcBatchClient,
    private val httpClient: OkHttpClient,
    private val web3jScheduler: ScheduledExecutorService,
) : Closeable {
    override fun close() {
        // No call can still hold this endpoint because the owning client closes it under its
        // write lock. Tear down every URL-bearing wrapper and transport resource before
        // returning. Pooled sockets are keyed by host only — request URLs, and therefore any
        // embedded credentials, never outlive the request — so the shared connection pool is
        // deliberately left warm for the next settlement client.
        httpClient.dispatcher.cancelAll()
        runCatching { web3j.shutdown() }
        web3jScheduler.shutdownNow()
        runCatching { httpClient.cache?.close() }
        httpClient.dispatcher.executorService.shutdownNow()
    }
}

private fun createOwnedRpcEndpoint(rpcUrl: String): OwnedRpcEndpoint {
    val httpClient = settlementHttpClientBuilder()
        .build()
    val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(
            runnable,
            "opk-settlement-web3j-${SETTLEMENT_TRANSPORT_IDS.incrementAndGet()}",
        ).apply { isDaemon = true }
    }
    return try {
        OwnedRpcEndpoint(
            web3j = Web3j.build(
                HttpService(rpcUrl, httpClient),
                WEB3J_BLOCK_TIME_MILLIS,
                scheduler,
            ),
            batch = StrictSettlementRpcBatchClient(rpcUrl, httpClient),
            httpClient = httpClient,
            web3jScheduler = scheduler,
        )
    } catch (error: Throwable) {
        scheduler.shutdownNow()
        httpClient.dispatcher.cancelAll()
        runCatching { httpClient.cache?.close() }
        httpClient.dispatcher.executorService.shutdownNow()
        throw error
    }
}

@JvmSynthetic
internal fun settlementHttpClientBuilder(): OkHttpClient.Builder =
    // Build directly so Web3j cannot install its optional BODY logger, which would include a
    // credential-bearing request URL when an application's SLF4J debug level is enabled.
    OkHttpClient.Builder()
        // Settlement clients are constructed per operation so credential-bearing URLs never
        // outlive an endpoint rotation, but TCP+TLS sessions carry no request material and are
        // shared across constructions to avoid a fresh handshake on every preflight and
        // recovery step.
        .connectionPool(SETTLEMENT_CONNECTION_POOL)
        .followRedirects(false)
        .followSslRedirects(false)
        // Provider URLs can contain client credentials. Replace transport exception text at the
        // connection boundary so host/path/query data cannot reach UI or Room errors.
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (_: IOException) {
                throw IOException("RPC transport failed")
            }
        }
        // Hard-cap every synchronous RPC used by both interactive and background settlement.
        // Each scheduled recovery unit is exactly one call/batch, leaving one second beneath the
        // coordinator's five-second cashier-priority lease.
        .callTimeout(SETTLEMENT_RPC_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

private const val SETTLEMENT_RPC_CALL_TIMEOUT_MILLIS = 4_000L
private const val WEB3J_BLOCK_TIME_MILLIS = 15_000L
private val SETTLEMENT_TRANSPORT_IDS = AtomicLong()
private val SETTLEMENT_CONNECTION_POOL = ConnectionPool(
    5,
    5,
    TimeUnit.MINUTES,
)

private fun isKnownTransactionProviderResponse(message: String?): Boolean =
    message.orEmpty().lowercase().let { value ->
        "already known" in value ||
            "known transaction" in value ||
            "already imported" in value ||
            "nonce too low" in value
    }

private fun quantityHex(value: Long): String {
    require(value >= 0) { "RPC quantity cannot be negative" }
    return "0x" + value.toString(16)
}

private fun quantityHex(value: BigInteger): String {
    require(value.signum() >= 0) { "RPC quantity cannot be negative" }
    return "0x" + value.toString(16)
}

private fun parseQuantity(value: String, operation: String): BigInteger {
    if (!RPC_QUANTITY_PATTERN.matches(value)) {
        throw SettlementRpcException("$operation returned a malformed hex quantity")
    }
    return BigInteger(value.substring(2), 16)
}

private fun resultString(result: JsonElement): String {
    if (!result.isJsonPrimitive || !result.asJsonPrimitive.isString) {
        throw SettlementRpcException("JSON-RPC result must be a string")
    }
    return result.asString
}

private fun decodeBlockHash(result: JsonElement, expectedBlockNumber: Long): String? {
    if (result.isJsonNull) return null
    if (!result.isJsonObject) {
        throw SettlementRpcException("eth_getBlockByNumber result must be an object or null")
    }
    val block = result.asJsonObject
    val number = block.get("number")?.let(::resultString)?.let {
        parseQuantity(it, "eth_getBlockByNumber block number")
    } ?: throw SettlementRpcException("eth_getBlockByNumber result has no block number")
    if (number != BigInteger.valueOf(expectedBlockNumber)) {
        throw SettlementRpcException(
            "eth_getBlockByNumber returned block $number for requested block $expectedBlockNumber",
        )
    }
    val hash = block.get("hash")?.let(::resultString)
        ?: throw SettlementRpcException("eth_getBlockByNumber result has no block hash")
    if (!BLOCK_HASH_PATTERN.matches(hash)) {
        throw SettlementRpcException("eth_getBlockByNumber returned a malformed block hash")
    }
    return hash.lowercase()
}

private fun decodeSettlementReceipt(result: JsonElement): SettlementReceipt? {
    if (result.isJsonNull) return null
    if (!result.isJsonObject) {
        throw SettlementRpcException("eth_getTransactionReceipt result must be an object or null")
    }
    val receipt = result.asJsonObject
    fun requiredString(name: String): String = receipt.get(name)?.let(::resultString)
        ?: throw SettlementRpcException("Receipt has no $name")

    val status = parseQuantity(requiredString("status"), "receipt status")
    if (status != BigInteger.ZERO && status != BigInteger.ONE) {
        throw SettlementRpcException("Receipt has invalid execution status $status")
    }
    val blockNumber = parseQuantity(
        requiredString("blockNumber"),
        "receipt block number",
    ).toLongExactCompat("receipt block number")
    val blockHash = requiredString("blockHash").also { hash ->
        if (!BLOCK_HASH_PATTERN.matches(hash)) {
            throw SettlementRpcException("Receipt has malformed block hash")
        }
    }.lowercase()
    val transactionHash = requiredString("transactionHash").also { hash ->
        if (!TRANSACTION_HASH_PATTERN.matches(hash)) {
            throw SettlementRpcException("Receipt has malformed transaction hash")
        }
    }.lowercase()
    val logsElement = receipt.get("logs")
        ?: throw SettlementRpcException("Receipt has no logs")
    if (!logsElement.isJsonArray) throw SettlementRpcException("Receipt logs must be an array")
    val logs = logsElement.asJsonArray.map { element ->
        if (!element.isJsonObject) throw SettlementRpcException("Receipt log must be an object")
        val log = element.asJsonObject
        fun logString(name: String): String = log.get(name)?.let(::resultString)
            ?: throw SettlementRpcException("Receipt log has no $name")
        val address = try {
            EvmAddress.parse(logString("address")).value
        } catch (_: IllegalArgumentException) {
            throw SettlementRpcException("Receipt log has malformed address")
        }
        val topicsElement = log.get("topics")
            ?: throw SettlementRpcException("Receipt log has no topics")
        if (!topicsElement.isJsonArray) {
            throw SettlementRpcException("Receipt log topics must be an array")
        }
        val topics = topicsElement.asJsonArray.map { topicElement ->
            resultString(topicElement).also { topic ->
                if (!BLOCK_HASH_PATTERN.matches(topic)) {
                    throw SettlementRpcException("Receipt log has malformed topic")
                }
            }.lowercase()
        }
        val data = logString("data").also { value ->
            if (!LOG_DATA_PATTERN.matches(value)) {
                throw SettlementRpcException("Receipt log has malformed data")
            }
        }.lowercase()
        val logTransactionHash = log.get("transactionHash")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let(::resultString)
            ?.also { hash ->
                if (!TRANSACTION_HASH_PATTERN.matches(hash)) {
                    throw SettlementRpcException("Receipt log has malformed transaction hash")
                }
            }
            ?.lowercase()
            ?: transactionHash
        val logBlockHash = log.get("blockHash")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let(::resultString)
            ?.also { hash ->
                if (!BLOCK_HASH_PATTERN.matches(hash)) {
                    throw SettlementRpcException("Receipt log has malformed block hash")
                }
            }
            ?.lowercase()
            ?: blockHash
        val logIndex = log.get("logIndex")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let(::resultString)
            ?.let { parseQuantity(it, "receipt log index").toLongExactCompat("receipt log index") }
        val removedElement = log.get("removed")
        val removed = when {
            removedElement == null || removedElement.isJsonNull -> false
            removedElement.isJsonPrimitive && removedElement.asJsonPrimitive.isBoolean ->
                removedElement.asBoolean
            else -> throw SettlementRpcException("Receipt log removed flag must be boolean")
        }
        SettlementReceiptLog(
            address = address,
            topics = topics,
            data = data,
            transactionHash = logTransactionHash,
            blockHash = logBlockHash,
            logIndex = logIndex,
            removed = removed,
        )
    }
    return SettlementReceipt(
        successful = status == BigInteger.ONE,
        blockNumber = blockNumber,
        blockHash = blockHash,
        transactionHash = transactionHash,
        logs = logs,
    )
}

private val RPC_QUANTITY_PATTERN = Regex("^0x(0|[1-9a-fA-F][0-9a-fA-F]*)$")
private val BLOCK_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
private val TRANSACTION_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
private val LOG_DATA_PATTERN = Regex("^0x(?:[0-9a-fA-F]{2})*$")

private fun BigInteger.toLongExactCompat(label: String): Long {
    require(signum() >= 0 && bitLength() <= 63) { "$label exceeds signed 64-bit storage" }
    return toLong()
}
