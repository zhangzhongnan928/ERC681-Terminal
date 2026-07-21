package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.settlement.SettlementChainClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FreshSettlementBroadcastTest {
    @Test
    fun `fresh authenticated submit broadcasts exactly once without receipt reads`() {
        val expectedHash = "0x" + "12".repeat(32)
        val calls = mutableListOf<String>()
        val client = settlementClient(calls) { expectedHash }

        val outcome = broadcastFreshSignedTransaction(client, "0xsigned", expectedHash)

        assertTrue(outcome.accepted)
        assertNull(outcome.error)
        assertEquals(listOf("sendRawTransaction"), calls)
    }

    @Test
    fun `fresh broadcast mismatch returns signed recovery state after one call`() {
        val calls = mutableListOf<String>()
        val client = settlementClient(calls) { "0x" + "34".repeat(32) }

        val outcome = broadcastFreshSignedTransaction(
            client,
            "0xsigned",
            "0x" + "12".repeat(32),
        )

        assertFalse(outcome.accepted)
        assertTrue(outcome.error.orEmpty().contains("different transaction hash"))
        assertEquals(listOf("sendRawTransaction"), calls)
    }

    private fun settlementClient(
        calls: MutableList<String>,
        broadcast: () -> String,
    ): SettlementChainClient = Proxy.newProxyInstance(
        SettlementChainClient::class.java.classLoader,
        arrayOf(SettlementChainClient::class.java),
    ) { _, method, _ ->
        calls += method.name
        when (method.name) {
            "sendRawTransaction" -> broadcast()
            else -> error("Fresh broadcast unexpectedly called ${method.name}")
        }
    } as SettlementChainClient
}
