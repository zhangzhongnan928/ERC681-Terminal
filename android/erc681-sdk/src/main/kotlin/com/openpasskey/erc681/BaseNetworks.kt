// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/**
 * Public Base chain identity metadata.
 *
 * This deliberately excludes RPC endpoints and OPK deployment addresses. A descriptor does not
 * enable a chain, validate a deployment, or select an application default. Applications must pair
 * an explicitly selected descriptor with their own reviewed [NetworkConfig].
 */
@ConsistentCopyVisibility
data class BaseNetworkDescriptor internal constructor(
    val chainId: Long,
    val networkName: String,
    val isTestnet: Boolean,
    val nativeCurrencySymbol: String,
    val nativeCurrencyDecimals: Int,
    val baseScanUrl: String,
)

/** Stable identities for Base mainnet and Base Sepolia. */
object BaseNetworks {
    val mainnet = BaseNetworkDescriptor(
        chainId = 8_453L,
        networkName = "Base Mainnet",
        isTestnet = false,
        nativeCurrencySymbol = "ETH",
        nativeCurrencyDecimals = 18,
        baseScanUrl = "https://basescan.org",
    )

    val sepolia = BaseNetworkDescriptor(
        chainId = 84_532L,
        networkName = "Base Sepolia",
        isTestnet = true,
        nativeCurrencySymbol = "ETH",
        nativeCurrencyDecimals = 18,
        baseScanUrl = "https://sepolia.basescan.org",
    )

    val all: List<BaseNetworkDescriptor> = listOf(mainnet, sepolia)

    fun forChainId(chainId: Long): BaseNetworkDescriptor? = all.firstOrNull {
        it.chainId == chainId
    }
}
