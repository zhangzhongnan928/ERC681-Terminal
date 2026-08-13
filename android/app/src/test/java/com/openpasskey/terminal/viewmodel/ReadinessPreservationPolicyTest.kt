package com.openpasskey.terminal.viewmodel

import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.erc681.RpcException
import com.openpasskey.erc681.RpcHttpRateLimitException
import com.openpasskey.erc681.RpcRateLimitResponseException
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPreservationPolicyTest {
    @Test
    fun `transient rpc failures preserve a proven checkout-capable status`() {
        listOf(
            RpcException("JSON-RPC transport failed"),
            RpcException("Canonical block 100 changed during network validation"),
            RpcHttpRateLimitException(),
            RpcRateLimitResponseException(-32016, "rate limit exceeded"),
        ).forEach { error ->
            assertTrue(
                shouldPreserveProvenReadiness(
                    error,
                    provenConfigurationUnchanged = true,
                    provenStatus = TerminalSetupStatus.READY,
                ),
            )
        }
        assertTrue(
            shouldPreserveProvenReadiness(
                RpcException("JSON-RPC transport failed"),
                provenConfigurationUnchanged = true,
                provenStatus = TerminalSetupStatus.AWAITING_GAS,
            ),
        )
    }

    @Test
    fun `an explicit on-chain verdict or local invariant failure is never preserved over`() {
        listOf(
            NetworkConfigurationException("Payment asset 0x0 is not whitelisted by vault"),
            NetworkConfigurationException("RPC chain ID 1 does not match configured chain ID 8453"),
            IllegalStateException("Factory pin mismatch"),
        ).forEach { error ->
            assertFalse(
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
                RpcException("JSON-RPC transport failed"),
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
                    RpcException("JSON-RPC transport failed"),
                    provenConfigurationUnchanged = true,
                    provenStatus = status,
                ),
            )
        }
    }

    @Test
    fun `deferred automatic refresh releases both checkout-capable proven states`() {
        assertTrue(
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.AWAITING_GAS,
            ),
        )
        assertFalse(
            readinessResultWhenAutomaticRefreshDefers(
                configurationStillValidated = true,
                setupStatus = TerminalSetupStatus.AWAITING_AUTHORIZATION,
            ),
        )
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
