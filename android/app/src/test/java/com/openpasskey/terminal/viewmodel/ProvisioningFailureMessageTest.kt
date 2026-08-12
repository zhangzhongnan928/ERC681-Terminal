package com.openpasskey.terminal.viewmodel

import com.openpasskey.erc681.RpcHttpRateLimitException
import com.openpasskey.erc681.RpcRateLimitResponseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningFailureMessageTest {
    @Test
    fun `exhausted SDK rate limits get generic actionable Base RPC guidance`() {
        val jsonRateLimit = RpcRateLimitResponseException(
            rpcCode = -32016,
            rpcMessage = "over rate limit",
        )
        val httpRateLimit = RpcHttpRateLimitException(retryAfterMillis = 1_000)

        listOf(jsonRateLimit, httpRateLimit).forEach { rateLimit ->
            val message = terminalRpcFailureMessage(rateLimit, "Provisioning failed")
            assertEquals(BASE_RPC_BUSY_MESSAGE, message)
            assertEquals(
                BASE_RPC_BUSY_MESSAGE,
                terminalRpcFailureMessage(rateLimit, "Unable to validate terminal readiness"),
            )
            assertEquals(
                "The selected Base RPC provider is busy. Wait a moment and try again. If this " +
                    "continues, review the configured endpoint credentials and provider quota.",
                message,
            )
            assertFalse(message.contains("-32016"))
            assertFalse(message.contains("429"))
        }
    }

    @Test
    fun `non-rate provisioning and readiness failures keep their own fallback`() {
        assertEquals(
            "Vault runtime bytecode mismatch",
            terminalRpcFailureMessage(
                IllegalArgumentException("Vault runtime bytecode mismatch"),
                "Provisioning failed",
            ),
        )
        assertEquals(
            "Provisioning failed",
            terminalRpcFailureMessage(IllegalStateException(), "Provisioning failed"),
        )
        assertEquals(
            "Unable to validate terminal readiness",
            terminalRpcFailureMessage(
                IllegalStateException(),
                "Unable to validate terminal readiness",
            ),
        )
    }

    @Test
    fun `interactive readiness retries throttle while automatic readiness stays no retry`() {
        assertTrue(retryReadinessOnThrottle(ReadinessRpcPriority.INTERACTIVE))
        assertFalse(retryReadinessOnThrottle(ReadinessRpcPriority.AUTOMATIC))
    }
}
