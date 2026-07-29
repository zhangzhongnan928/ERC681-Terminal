// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger

/** Data-only handoff for an external owner/operator settlement system. */
data class SettlementHandoff(
    val invoiceId: InvoiceId,
    val chainId: Long,
    val vault: EvmAddress,
    val receiver: EvmAddress,
    val token: EvmAddress,
    val expectedAmount: TokenAmount,
    val observedRawUnits: BigInteger,
    val observedAtBlock: Long,
) {
    init {
        require(chainId > 0) { "Chain ID must be greater than zero" }
        require(observedRawUnits.signum() >= 0) { "Observed amount must not be negative" }
        require(observedAtBlock >= 0) { "Observed block must not be negative" }
    }

    companion object {
        @JvmStatic
        fun from(invoice: PaymentInvoice, observation: PaymentObservation): SettlementHandoff {
            require(observation.token == invoice.request.token) { "Observation token does not match invoice" }
            require(observation.receiver == invoice.request.receiver) { "Observation receiver does not match invoice" }
            require(observation.expectedAmount == invoice.request.amount) { "Observation amount does not match invoice" }
            require(observation.status == PaymentStatus.PAID) { "Settlement handoff requires a paid observation" }
            return SettlementHandoff(
                invoiceId = invoice.invoiceId,
                chainId = invoice.request.chainId,
                vault = invoice.vault,
                receiver = invoice.request.receiver,
                token = invoice.request.token,
                expectedAmount = invoice.request.amount,
                observedRawUnits = observation.observedRawUnits,
                observedAtBlock = observation.blockNumber,
            )
        }
    }
}
