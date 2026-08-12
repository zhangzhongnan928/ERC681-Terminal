// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.net.URI

/** Immutable chain and OPK contract configuration used by the read-only SDK. */
data class NetworkConfig(
    val chainId: Long,
    val rpcUrl: String,
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
    val vault: EvmAddress,
) {
    init {
        require(chainId > 0) { "Chain ID must be greater than zero" }
        require(factory != vault) { "Factory and vault addresses must differ" }
        require(!factory.isZero) { "Factory address must not be zero" }
        require(!receiverImplementation.isZero) { "Receiver implementation address must not be zero" }
        require(!vault.isZero) { "Vault address must not be zero" }

        val uri = try {
            URI(rpcUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("RPC URL is invalid")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank()) { "RPC URL must include a host" }
        val isLoopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(scheme == "https" || (scheme == "http" && isLoopback)) {
            "RPC URL must use HTTPS; HTTP is allowed only for loopback development"
        }
        require(uri.userInfo == null) { "RPC URL must not embed credentials" }
        require(uri.fragment == null) { "RPC URL must not include a fragment" }
    }

    val receiverResolver: Create2ReceiverResolver
        get() = Create2ReceiverResolver(factory, receiverImplementation)

    override fun toString(): String = "NetworkConfig(" +
        "chainId=$chainId, " +
        "rpcUrl=<redacted>, " +
        "factory=$factory, " +
        "receiverImplementation=$receiverImplementation, " +
        "vault=$vault)"
}
