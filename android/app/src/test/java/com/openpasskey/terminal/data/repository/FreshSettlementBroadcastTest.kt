package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.SettlementRpcException
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

    @Test
    fun `credential-bearing transport failure is replaced before reaching durable state`() {
        val secret = "terminal-client-key"
        val client = settlementClient(mutableListOf()) {
            throw RuntimeException("Failed https://$secret.rpc-provider.example/base")
        }

        val outcome = broadcastFreshSignedTransaction(
            client,
            "0xsigned",
            "0x" + "12".repeat(32),
        )

        assertFalse(outcome.accepted)
        assertEquals("Broadcast result unknown", outcome.error)
        assertFalse(outcome.error.orEmpty().contains(secret))
    }

    @Test
    fun `redacted known-transaction classification remains accepted without provider text`() {
        val client = settlementClient(mutableListOf()) {
            throw SettlementRpcException(
                message = "redacted provider response",
                rpcCode = -32000,
                knownTransactionResponse = true,
            )
        }

        val outcome = broadcastFreshSignedTransaction(
            client,
            "0xsigned",
            "0x" + "12".repeat(32),
        )

        assertTrue(outcome.accepted)
        assertNull(outcome.error)
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
