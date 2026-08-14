package com.openpasskey.terminal.viewmodel

import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.erc681.RpcCallDeadlineException
import com.openpasskey.erc681.RpcCanonicalBlockException
import com.openpasskey.erc681.RpcException
import com.openpasskey.erc681.RpcHttpRateLimitException
import com.openpasskey.erc681.RpcHttpTransientStatusException
import com.openpasskey.erc681.RpcRateLimitResponseException
import com.openpasskey.erc681.RpcResponseException
import com.openpasskey.erc681.RpcTransportException
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPreservationPolicyTest {
    @Test
    fun `only typed transient failures preserve a proven checkout-capable status`() {
        listOf(
            RpcTransportException(),
            RpcCallDeadlineException(),
            RpcHttpTransientStatusException(503),
            RpcHttpRateLimitException(),
            RpcRateLimitResponseException(-32016, "rate limit exceeded"),
            RpcCanonicalBlockException("Canonical block 100 changed during network validation"),
        ).forEach { error ->
            assertTrue(
                error.javaClass.simpleName,
                shouldPreserveProvenReadiness(
                    error,
                    provenConfigurationUnchanged = true,
                    provenStatus = TerminalSetupStatus.READY,
                ),
            )
        }
        assertTrue(
            shouldPreserveProvenReadiness(
                RpcTransportException(),
                provenConfigurationUnchanged = true,
                provenStatus = TerminalSetupStatus.AWAITING_GAS,
            ),
        )
    }

    @Test
    fun `verdicts malformed responses and generic rpc errors are never preserved over`() {
        listOf(
            NetworkConfigurationException("Payment asset 0x0 is not whitelisted by vault"),
            NetworkConfigurationException("RPC chain ID 1 does not match configured chain ID 8453"),
            RpcException("JSON-RPC response is not valid JSON"),
            RpcException("JSON-RPC batch response is not an array"),
            RpcException("RPC HTTP response body is empty"),
            RpcException("RPC HTTP request failed with status 404"),
            RpcResponseException(-32000, "execution reverted"),
            RpcResponseException(-32601, "method not found"),
            IllegalStateException("Factory pin mismatch"),
        ).forEach { error ->
            assertFalse(
                error.javaClass.simpleName + ": " + error.message,
                shouldPreserveProvenReadiness(
                    error,
                    provenConfigurationUnchanged = true,
                    provenStatus = TerminalSetupStatus.READY,
                ),
            )
        }
    }

    @Test
    fun `nothing proven for this configuration means nothing to preserve`() {
        assertFalse(
            shouldPreserveProvenReadiness(
                RpcTransportException(),
                provenConfigurationUnchanged = false,
                provenStatus = TerminalSetupStatus.READY,
            ),
        )
        listOf(
            TerminalSetupStatus.AWAITING_AUTHORIZATION,
            TerminalSetupStatus.PROVISIONING,
            TerminalSetupStatus.ERROR,
        ).forEach { status ->
            assertFalse(
                shouldPreserveProvenReadiness(
                    RpcTransportException(),
                    provenConfigurationUnchanged = true,
                    provenStatus = status,
                ),
            )
        }
    }

    @Test
    fun `deferred automatic refresh reports a preserved result never a fresh one`() {
        assertEquals(
            ReadinessRefreshResult.PRESERVED,
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.READY,
            ),
        )
        assertEquals(
            ReadinessRefreshResult.PRESERVED,
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.AWAITING_GAS,
            ),
        )
        assertEquals(
            ReadinessRefreshResult.NOT_READY,
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = false,
                setupStatus = TerminalSetupStatus.READY,
            ),
        )
        assertEquals(
            ReadinessRefreshResult.NOT_READY,
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.AWAITING_AUTHORIZATION,
            ),
        )
    }

    @Test
    fun `only a fresh proof clears an invoice failure`() {
        assertTrue(ReadinessRefreshResult.FRESH_READY.clearsInvoiceFailure())
        assertFalse(ReadinessRefreshResult.PRESERVED.clearsInvoiceFailure())
        assertFalse(ReadinessRefreshResult.NOT_READY.clearsInvoiceFailure())
    }

    @Test
    fun `a preserved result keeps a failed invoice state closed`() {
        val failed = CreateInvoiceState().withRepositoryFailure("Token is not whitelisted")

        val afterPreserved = failed.afterReadinessRefresh(
            ReadinessRefreshResult.PRESERVED.clearsInvoiceFailure(),
        )
        assertTrue(afterPreserved.readinessInvalidated)
        assertEquals("Token is not whitelisted", afterPreserved.repositoryFailure)

        val afterFresh = failed.afterReadinessRefresh(
            ReadinessRefreshResult.FRESH_READY.clearsInvoiceFailure(),
        )
        assertFalse(afterFresh.readinessInvalidated)
    }

    @Test
    fun `checkout-capable statuses are READY and AWAITING_GAS only`() {
        TerminalSetupStatus.entries.forEach { status ->
            assertEquals(
                status == TerminalSetupStatus.READY || status == TerminalSetupStatus.AWAITING_GAS,
                statusAllowsCheckout(status),
            )
        }
    }

    @Test
    fun `preserved notice keeps the proven summary and names the unreachable provider`() {
        val policy = KnownChainPolicy.requireProfile(84_532)

        val ready = preservedReadinessNoticeMessage(TerminalSetupStatus.READY, policy)
        assertTrue(ready.startsWith("Terminal is ready to create payments."))
        assertTrue(ready.contains("could not reach the RPC provider"))

        val lowGas = preservedReadinessNoticeMessage(TerminalSetupStatus.AWAITING_GAS, policy)
        assertTrue(lowGas.contains("0.0001 ETH"))
        assertFalse(lowGas.contains("wei"))
        assertTrue(lowGas.contains("could not reach the RPC provider"))
    }
}
