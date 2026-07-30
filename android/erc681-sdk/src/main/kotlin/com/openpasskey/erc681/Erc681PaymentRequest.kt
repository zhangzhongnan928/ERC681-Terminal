// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/** One OPK QR-rail request: ERC-20 transfer or Protocol 1.6 native value transfer. */
data class Erc681PaymentRequest(
    val token: EvmAddress,
    val chainId: Long,
    val receiver: EvmAddress,
    val amount: TokenAmount,
) {
    init {
        require(chainId > 0) { "Chain ID must be greater than zero" }
        require(!token.isZero) { "Token address must not be zero" }
        require(!receiver.isZero) { "Receiver address must not be zero" }
        require(receiver != NativeAsset.address) { "Receiver must not be the native-asset sentinel" }
    }

    val isNative: Boolean get() = NativeAsset.isNative(token)
}
