// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

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

    /** Native balance at an optional fixed block. Alternate clients fail closed until implemented. */
    fun nativeBalance(holder: EvmAddress, blockNumber: Long? = null): BigInteger =
        throw RpcException("Native-asset balance reads are not supported by this client")

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

open class RpcResponseException(
    val rpcCode: Int,
    @Suppress("UNUSED_PARAMETER") rpcMessage: String,
) : RpcException("JSON-RPC error $rpcCode")

/**
 * Marker shared by the distinct HTTP and JSON-RPC throttle failures. Callers can classify a
 * provider throttle without losing the existing [RpcResponseException] JSON-RPC hierarchy.
 */
interface RpcRateLimit {
    /** Provider-requested delay after defensive bounding, when supplied over HTTP. */
    val retryAfterMillis: Long?
}

/**
 * Marker for failures where repeating the read may succeed without any configuration or trust
 * change: transport interruptions, call deadlines, transient HTTP statuses, provider throttles,
 * and canonical-block movement during a bracketed read. Malformed or protocol-violating
 * responses and explicit on-chain verdicts never carry it.
 */
interface RpcTransientFailure

/** A provider JSON-RPC throttle that remains catch-compatible with [RpcResponseException]. */
class RpcRateLimitResponseException(
    rpcCode: Int,
    rpcMessage: String,
    override val retryAfterMillis: Long? = null,
) : RpcResponseException(rpcCode, rpcMessage), RpcRateLimit, RpcTransientFailure

/** An HTTP 429 provider throttle. */
class RpcHttpRateLimitException(
    override val retryAfterMillis: Long? = null,
) : RpcException("RPC HTTP request failed with status 429"), RpcRateLimit, RpcTransientFailure {
    val httpStatus: Int = 429
}

/** The connection-level transport failed; the sanitized message never carries the endpoint. */
class RpcTransportException : RpcException("JSON-RPC transport failed"), RpcTransientFailure

/** The whole-call transport deadline elapsed before a response completed. */
class RpcCallDeadlineException :
    RpcException("RPC HTTP call exceeded its deadline"), RpcTransientFailure

/** A retryable HTTP status (408, 425, or 5xx) that is not a throttle. */
class RpcHttpTransientStatusException(status: Int) :
    RpcException("RPC HTTP request failed with status $status"), RpcTransientFailure

/** The canonical block anchor moved or vanished while a bracketed read was sampling. */
class RpcCanonicalBlockException(message: String) : RpcException(message), RpcTransientFailure

class NetworkConfigurationException(message: String) : RpcException(message)
