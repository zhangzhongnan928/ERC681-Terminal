package com.openpasskey.erc681

import java.math.BigInteger

/** The complete chain surface exposed by the SDK. Every operation is read-only. */
interface ReadOnlyChainClient {
    fun chainId(): Long

    fun codeAt(address: EvmAddress): ByteArray

    fun factoryImplementation(): EvmAddress

    fun vaultFactory(): EvmAddress

    fun isPaymentToken(token: EvmAddress): Boolean

    fun tokenDecimals(token: EvmAddress): Int

    /** Strictly decoded ERC-20 display symbol. Implementations must fail closed on unsafe text. */
    fun tokenSymbol(token: EvmAddress): String

    fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long? = null): BigInteger

    fun blockNumber(): Long

    /** Canonical hash for an exact block height, or null when that height is unavailable. */
    fun blockHash(blockNumber: Long): String?
}

data class NetworkValidation(
    val chainId: Long,
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
    val vault: EvmAddress,
    val token: EvmAddress,
    val tokenWhitelisted: Boolean,
    val tokenDecimals: Int,
    val tokenSymbol: String,
)

open class RpcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RpcResponseException(
    val rpcCode: Int,
    rpcMessage: String,
) : RpcException("JSON-RPC error $rpcCode: $rpcMessage")

class NetworkConfigurationException(message: String) : RpcException(message)
