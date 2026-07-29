// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import kotlin.test.Test
import kotlin.test.assertFailsWith

class NetworkConfigTest {
    private val factory = EvmAddress.parse("0x1111111111111111111111111111111111111111")
    private val implementation = EvmAddress.parse("0x2222222222222222222222222222222222222222")
    private val vault = EvmAddress.parse("0x3333333333333333333333333333333333333333")

    @Test
    fun `RPC transport is HTTPS except loopback development`() {
        config("https://rpc.example.test")
        config("http://localhost:8545")
        config("http://127.0.0.1:8545")
        config("http://[::1]:8545")

        assertFailsWith<IllegalArgumentException> { config("http://rpc.example.test") }
        assertFailsWith<IllegalArgumentException> { config("ftp://rpc.example.test") }
    }

    @Test
    fun `RPC URL rejects embedded identity and fragments`() {
        assertFailsWith<IllegalArgumentException> { config("https://user:password@rpc.example.test") }
        assertFailsWith<IllegalArgumentException> { config("https://rpc.example.test/#fragment") }
    }

    private fun config(rpcUrl: String) = NetworkConfig(
        chainId = 84532,
        rpcUrl = rpcUrl,
        factory = factory,
        receiverImplementation = implementation,
        vault = vault
    )
}
