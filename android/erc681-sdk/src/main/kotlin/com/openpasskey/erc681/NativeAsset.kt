// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/** OPK Protocol 1.6's EIP-7528 identifier for the current chain's native asset. */
object NativeAsset {
    const val SENTINEL = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
    const val DECIMALS = 18
    const val PROTOCOL_VERSION = "1.6"

    @JvmField
    val address: EvmAddress = EvmAddress.parse(SENTINEL)

    @JvmStatic
    fun isNative(address: EvmAddress): Boolean = address == this.address
}
