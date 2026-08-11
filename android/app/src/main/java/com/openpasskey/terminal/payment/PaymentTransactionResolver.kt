package com.openpasskey.terminal.payment

import com.openpasskey.terminal.data.model.Invoice

/** App-layer adapter that binds SDK evidence resolution to one durable invoice snapshot. */
fun interface PaymentTransactionResolver {
    suspend fun resolve(invoice: Invoice): PaymentTransactionEvidence?
}
