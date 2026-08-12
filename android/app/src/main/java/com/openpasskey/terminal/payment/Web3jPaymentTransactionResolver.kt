package com.openpasskey.terminal.payment

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.PaymentEvidenceRequest
import com.openpasskey.erc681.PaymentEvidenceResolver
import com.openpasskey.erc681.PaymentConfirmationCursor
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.terminal.data.model.Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin Android persistence adapter around the reusable SDK's read-only evidence resolver.
 *
 * The historical class name is retained for dependency compatibility. Web3j is no longer used for
 * payment attribution, and the reusable SDK remains keyless and incapable of broadcasting.
 */
class Web3jPaymentTransactionResolver : PaymentTransactionResolver {
    override suspend fun resolve(
        invoice: Invoice,
        resolvedRpcUrl: String,
    ): PaymentTransactionEvidence? =
        withContext(Dispatchers.IO) {
            val request = invoice.toPaymentEvidenceRequestOrNull() ?: return@withContext null
            val client = ReadOnlyRpcClient(
                invoice.toEvidenceNetworkConfig(resolvedRpcUrl),
                connectTimeoutMillis = EVIDENCE_RPC_CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = EVIDENCE_RPC_READ_TIMEOUT_MILLIS,
                callTimeoutMillis = EVIDENCE_RPC_CALL_TIMEOUT_MILLIS,
            )
            PaymentEvidenceResolver(
                chain = client,
                totalBudgetMillis = EVIDENCE_TOTAL_BUDGET_MILLIS,
            ).resolve(request)
        }

    internal companion object {
        // Three nested bounds produce a hard end-to-end envelope with strict headroom below the
        // five-second background coordinator lease. Socket timeouts bound each idle wait; the
        // whole-call watchdog deadline disconnects a call the moment it expires, aborting even a
        // header- or body-dribbling peer within milliseconds; and the resolver's budget, checked
        // before every network operation, bounds the sequential sum. Worst complete pass:
        // 2_800 + 1_250 = 4_050 ms plus millisecond-scale watchdog teardown — well under
        // 5_000 ms, leaving margin for request writes, response parsing, and coroutine
        // resumption. BackgroundRpcBudgetTest pins the strict inequality against the lease.
        internal const val EVIDENCE_RPC_CONNECT_TIMEOUT_MILLIS = 500
        internal const val EVIDENCE_RPC_READ_TIMEOUT_MILLIS = 750
        internal const val EVIDENCE_RPC_CALL_TIMEOUT_MILLIS = 1_250
        internal const val EVIDENCE_TOTAL_BUDGET_MILLIS = 2_800L
    }
}

internal fun Invoice.toPaymentEvidenceRequestOrNull(): PaymentEvidenceRequest? {
    require(chainId in SUPPORTED_BASE_CHAIN_IDS) {
        "Payment transaction evidence is supported only on Base chains"
    }
    val publicationBlock = publishedAtBlock ?: return null
    val publicationHash = publishedAtBlockHash ?: return null
    val fundingBlock = firstDetectedBlock ?: return null
    val fundingHash = firstDetectedBlockHash ?: return null
    val amount = expectedAmount.toBigIntegerOrNull()
        ?: throw IllegalArgumentException("Invoice expected amount is malformed")
    return PaymentEvidenceRequest(
        chainId = chainId,
        asset = EvmAddress.parse(token),
        receiver = EvmAddress.parse(receiver),
        expectedAmount = amount,
        publicationCursor = PaymentConfirmationCursor(publicationBlock, publicationHash),
        fundingCursor = PaymentConfirmationCursor(fundingBlock, fundingHash),
    )
}

private fun Invoice.toEvidenceNetworkConfig(resolvedRpcUrl: String): NetworkConfig = NetworkConfig(
    chainId = chainId,
    rpcUrl = resolvedRpcUrl,
    factory = EvmAddress.parse(factoryAddress),
    receiverImplementation = EvmAddress.parse(receiverImplementationAddress),
    vault = EvmAddress.parse(vaultAddress),
)

private val SUPPORTED_BASE_CHAIN_IDS = setOf(8_453L, 84_532L)
