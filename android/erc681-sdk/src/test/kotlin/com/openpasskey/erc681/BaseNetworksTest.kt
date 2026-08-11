// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BaseNetworksTest {
    private val fixture by lazy {
        val json = checkNotNull(javaClass.getResourceAsStream("/opk-base-networks-v1.json")) {
            "Missing conformance/opk-base-networks-v1.json test resource"
        }.bufferedReader().use { it.readText() }
        JsonParser.parseString(json).asJsonObject
    }

    @Test
    fun `known Base descriptors match the shared fixture`() {
        val expected = fixture.getAsJsonArray("networks").map { element ->
            val network = element.asJsonObject
            BaseNetworkDescriptor(
                chainId = network.get("chainId").asLong,
                networkName = network.get("networkName").asString,
                isTestnet = network.get("isTestnet").asBoolean,
                nativeCurrencySymbol = network.get("nativeCurrencySymbol").asString,
                nativeCurrencyDecimals = network.get("nativeCurrencyDecimals").asInt,
                baseScanUrl = network.get("baseScanUrl").asString,
            )
        }

        assertEquals(expected, BaseNetworks.all)
        assertEquals(setOf(8_453L, 84_532L), BaseNetworks.all.mapTo(mutableSetOf()) { it.chainId })
        assertSame(BaseNetworks.mainnet, BaseNetworks.forChainId(8_453L))
        assertSame(BaseNetworks.sepolia, BaseNetworks.forChainId(84_532L))
        assertNull(BaseNetworks.forChainId(1L))
    }

    @Test
    fun `mainnet and Sepolia identities are not interchangeable`() {
        assertFalse(BaseNetworks.mainnet.isTestnet)
        assertTrue(BaseNetworks.sepolia.isTestnet)
        assertEquals("https://basescan.org", BaseNetworks.mainnet.baseScanUrl)
        assertEquals("https://sepolia.basescan.org", BaseNetworks.sepolia.baseScanUrl)
    }
}
