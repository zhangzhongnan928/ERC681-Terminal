package com.openpasskey.terminal.rpc

import com.openpasskey.erc681.RpcResponseException
import com.openpasskey.terminal.settlement.SettlementRpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RpcFailureSanitizerTest {
    @Test
    fun providerControlledRpcMessageCannotReachUiText() {
        val secret = "terminal-client-key"
        val error = RpcResponseException(
            rpcCode = -32000,
            rpcMessage = "request failed at https://$secret.rpc-provider.example/base",
        )

        val message = safeReadRpcFailureMessage(error, "Read-only RPC failed")

        assertEquals("Read-only RPC failed", message)
        assertFalse(message.contains(secret))
    }

    @Test
    fun providerControlledSettlementMessageCannotReachUiText() {
        val secret = "terminal-settlement-client-key"

        val message = safeReadRpcFailureMessage(
            SettlementRpcException("provider echoed $secret"),
            "Settlement RPC failed",
        )

        assertEquals("Settlement RPC failed", message)
        assertFalse(message.contains(secret))
    }
}
