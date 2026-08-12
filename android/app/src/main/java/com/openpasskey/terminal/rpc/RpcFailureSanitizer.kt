package com.openpasskey.terminal.rpc

import com.openpasskey.erc681.RpcException
import com.openpasskey.terminal.settlement.SettlementRpcException

/** Provider and transport messages are untrusted and can echo credential-bearing endpoint data. */
fun safeReadRpcFailureMessage(error: Throwable, fallback: String): String =
    if (error is RpcException || error is SettlementRpcException) {
        fallback
    } else {
        error.message ?: fallback
    }
