package com.openpasskey.terminal.settlement

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.data.model.SettlementFeeMode
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.Closeable
import java.math.BigInteger

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

class SettlementRpcException(message: String, val rpcCode: Int? = null) : RuntimeException(message)

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
}

class Web3jSettlementChainClient(rpcUrl: String) : SettlementChainClient {
    private val web3j = Web3j.build(HttpService(rpcUrl))

    override fun chainId(): Long {
        val response = web3j.ethChainId().send()
        response.throwIfError("eth_chainId")
        return response.chainId.toLongExactCompat("chain ID")
    }

    override fun nativeBalance(address: String): BigInteger {
        val response = web3j.ethGetBalance(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("eth_getBalance")
        return response.balance
    }

    override fun latestNativeBalance(address: String): BigInteger {
        val response = web3j.ethGetBalance(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.LATEST,
        ).send()
        response.throwIfError("eth_getBalance")
        return response.balance
    }

    override fun tokenBalance(tokenAddress: String, accountAddress: String): BigInteger {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                null,
                EvmAddress.parse(tokenAddress).value,
                SettlementAbi.encodeBalanceOf(accountAddress)
            ),
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("balanceOf eth_call")
        return SettlementAbi.decodeUint256Word(response.value)
    }

    override fun isOperator(vaultAddress: String, operatorAddress: String): Boolean {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                EvmAddress.parse(operatorAddress).value,
                EvmAddress.parse(vaultAddress).value,
                SettlementAbi.encodeIsOperator(operatorAddress)
            ),
            DefaultBlockParameterName.LATEST
        ).send()
        response.throwIfError("isOperator eth_call")
        return SettlementAbi.decodeIsOperator(response.value)
    }

    override fun owner(vaultAddress: String): String {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                null,
                EvmAddress.parse(vaultAddress).value,
                SettlementAbi.encodeOwner()
            ),
            DefaultBlockParameterName.LATEST
        ).send()
        response.throwIfError("owner eth_call")
        return SettlementAbi.decodeOwner(response.value)
    }

    override fun simulate(fromAddress: String, toAddress: String, callData: String) {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                EvmAddress.parse(fromAddress).value,
                EvmAddress.parse(toAddress).value,
                callData
            ),
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("sweepSessions simulation")
    }

    override fun pendingNonce(address: String): BigInteger {
        val response = web3j.ethGetTransactionCount(
            EvmAddress.parse(address).value,
            DefaultBlockParameterName.PENDING
        ).send()
        response.throwIfError("eth_getTransactionCount")
        return response.transactionCount
    }

    override fun estimateGas(
        fromAddress: String,
        toAddress: String,
        callData: String,
        nonce: BigInteger
    ): BigInteger {
        val response = web3j.ethEstimateGas(
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
        return response.amountUsed.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(100))
            .add(BigInteger.valueOf(15_000))
    }

    override fun feeQuote(): SettlementFeeQuote {
        val priceResponse = web3j.ethGasPrice().send()
        priceResponse.throwIfError("eth_gasPrice")
        val blockResponse = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send()
        blockResponse.throwIfError("eth_getBlockByNumber")
        val baseFee = blockResponse.block.baseFeePerGas?.let(Numeric::decodeQuantity)
        return SettlementFeePolicy.quote(baseFee, priceResponse.gasPrice)
    }

    override fun sendRawTransaction(signedTransaction: String): String {
        val response = web3j.ethSendRawTransaction(signedTransaction).send()
        response.throwIfError("eth_sendRawTransaction")
        return response.transactionHash
            ?: throw SettlementRpcException("eth_sendRawTransaction returned no transaction hash")
    }

    override fun transactionReceipt(txHash: String): SettlementReceipt? {
        val response = web3j.ethGetTransactionReceipt(txHash).send()
        response.throwIfError("eth_getTransactionReceipt")
        val receipt = response.transactionReceipt.orElse(null) ?: return null
        val status = receipt.status ?: throw SettlementRpcException("Receipt has no execution status")
        val successful = when (status.lowercase()) {
            "0x1", "1" -> true
            "0x0", "0" -> false
            else -> throw SettlementRpcException("Receipt has invalid execution status $status")
        }
        return SettlementReceipt(
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

    override fun blockNumber(): Long {
        val response = web3j.ethBlockNumber().send()
        response.throwIfError("eth_blockNumber")
        return response.blockNumber.toLongExactCompat("block number")
    }

    override fun canonicalBlockHash(blockNumber: Long): String? {
        require(blockNumber >= 0) { "Block number cannot be negative" }
        val response = web3j.ethGetBlockByNumber(
            DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
            false,
        ).send()
        response.throwIfError("eth_getBlockByNumber")
        return response.block?.hash
    }

    override fun close() {
        web3j.shutdown()
    }

    private fun org.web3j.protocol.core.Response<*>.throwIfError(operation: String) {
        if (hasError()) {
            throw SettlementRpcException(
                "$operation failed: ${error.message ?: "unknown RPC error"}",
                error.code
            )
        }
    }
}

private fun BigInteger.toLongExactCompat(label: String): Long {
    require(signum() >= 0 && bitLength() <= 63) { "$label exceeds signed 64-bit storage" }
    return toLong()
}
