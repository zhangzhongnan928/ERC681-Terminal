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

    fun tokenBalance(token: EvmAddress, holder: EvmAddress, blockNumber: Long? = null): BigInteger

    fun blockNumber(): Long
}

data class NetworkValidation(
    val chainId: Long,
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
    val vault: EvmAddress,
    val token: EvmAddress,
    val tokenWhitelisted: Boolean,
    val tokenDecimals: Int,
)

open class RpcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RpcResponseException(
    val rpcCode: Int,
    rpcMessage: String,
) : RpcException("JSON-RPC error $rpcCode: $rpcMessage")

class NetworkConfigurationException(message: String) : RpcException(message)
