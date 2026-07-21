package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.erc681.RpcException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePaymentRpcRetryTest {
    @Test
    fun `transient RPC failure reloads durable open state and waits one cadence`() = runBlocking {
        var reloaded = false
        var paused = false

        val result = runVisibleRpcAttempt<String, Boolean>(
            boundedRpc = false,
            attempt = { throw RpcException("temporary reorg") },
            reloadDurableState = { reloaded = true; true },
            shouldContinue = { it },
            pauseBeforeRetry = { paused = true },
        )

        assertTrue(result is VisibleRpcAttemptResult.Retry)
        assertTrue(reloaded)
        assertTrue(paused)
        assertEquals(5_000L, InvoiceRepository.POLL_INTERVAL_MILLIS)
    }

    @Test
    fun `transient RPC failure stops immediately after durable cancellation`() = runBlocking {
        var paused = false

        val result = runVisibleRpcAttempt<String, Boolean>(
            boundedRpc = false,
            attempt = { throw RpcException("offline") },
            reloadDurableState = { false },
            shouldContinue = { it },
            pauseBeforeRetry = { paused = true },
        )

        assertTrue(result is VisibleRpcAttemptResult.Stop)
        assertFalse(paused)
    }

    @Test
    fun `cancellation during retry cadence prevents the next RPC attempt`() = runBlocking {
        var open = true

        val result = runVisibleRpcAttempt<String, Boolean>(
            boundedRpc = false,
            attempt = { throw RpcException("offline") },
            reloadDurableState = { open },
            shouldContinue = { it },
            pauseBeforeRetry = { open = false },
        )

        assertTrue(result is VisibleRpcAttemptResult.Stop)
    }

    @Test
    fun `wrong chain remains terminal and is never retried`() {
        var reloaded = false
        assertThrows(NetworkConfigurationException::class.java) {
            runBlocking {
                runVisibleRpcAttempt<String, Boolean>(
                    boundedRpc = false,
                    attempt = { throw NetworkConfigurationException("wrong chain") },
                    reloadDurableState = { reloaded = true; true },
                    shouldContinue = { it },
                    pauseBeforeRetry = {},
                )
            }
        }
        assertFalse(reloaded)
    }

    @Test
    fun `bounded recovery leaves transient failure to durable scheduler`() {
        assertThrows(RpcException::class.java) {
            runBlocking {
                runVisibleRpcAttempt<String, Boolean>(
                    boundedRpc = true,
                    attempt = { throw RpcException("offline") },
                    reloadDurableState = { true },
                    shouldContinue = { it },
                    pauseBeforeRetry = {},
                )
            }
        }
    }
}
