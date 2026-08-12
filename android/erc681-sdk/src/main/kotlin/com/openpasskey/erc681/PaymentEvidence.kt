// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import java.math.BigInteger

/** Exact canonical block identity retained by an invoice. */
data class PaymentConfirmationCursor(
    val blockNumber: Long,
    val blockHash: String,
) {
    init {
        require(blockNumber >= 0) { "Canonical block number must not be negative" }
        requireHash(blockHash, "Canonical block hash")
    }
}

/**
 * Read-only inputs needed to attribute the direct transaction that first funded an invoice.
 *
 * [publicationCursor] is the canonical head captured immediately before the payment QR was
 * published. [fundingCursor] is the first canonical payment-observer cursor whose ending receiver
 * balance met the invoice amount. A later merchant sweep transaction is deliberately not part of
 * this model.
 */
data class PaymentEvidenceRequest(
    val chainId: Long,
    val asset: EvmAddress,
    val receiver: EvmAddress,
    val expectedAmount: BigInteger,
    val publicationCursor: PaymentConfirmationCursor,
    val fundingCursor: PaymentConfirmationCursor,
) {
    init {
        require(chainId > 0) { "Payment chain ID must be greater than zero" }
        require(!asset.isZero) { "Payment asset must not be zero" }
        require(!receiver.isZero) { "Payment receiver must not be zero" }
        requireUint256(expectedAmount, "Expected payment amount")
        require(expectedAmount.signum() > 0) { "Expected payment amount must be positive" }
        require(fundingCursor.blockNumber > publicationCursor.blockNumber) {
            "Funding block must follow the invoice publication block"
        }
    }
}

/** Canonical, read-only evidence for the direct consumer transaction that completed a payment. */
data class PaymentTransactionEvidence(
    val txHash: String,
    val payerAddress: String,
    val blockNumber: Long,
    val blockHash: String,
    /** Canonical payment-block timestamp in Unix seconds. */
    val blockTimestamp: Long,
) {
    init {
        requireHash(txHash, "Payment transaction hash")
        require(!EvmAddress.parse(payerAddress).isZero) { "Payment payer must not be zero" }
        require(blockNumber >= 0) { "Payment block number must not be negative" }
        requireHash(blockHash, "Payment block hash")
        require(blockTimestamp >= 0) { "Payment block timestamp must not be negative" }
    }
}

/** One direct native-value transaction decoded from a full canonical block. */
data class DirectNativePaymentTransaction(
    val txHash: String,
    val payer: EvmAddress,
    /** Null only for a contract-creation transaction. */
    val recipient: EvmAddress?,
    val transactionIndex: Long,
    val value: BigInteger,
    val blockNumber: Long,
    val blockHash: String,
) {
    init {
        requireHash(txHash, "Native payment transaction hash")
        require(!payer.isZero) { "Native payment payer must not be zero" }
        require(transactionIndex >= 0) { "Native payment transaction index must not be negative" }
        requireUint256(value, "Native payment transaction value")
        require(blockNumber >= 0) { "Native payment transaction block must not be negative" }
        requireHash(blockHash, "Native payment transaction block hash")
    }
}

/** One canonical block, optionally including its decoded direct native-value transactions. */
class PaymentEvidenceBlock(
    val blockNumber: Long,
    val blockHash: String,
    /** Canonical block timestamp in Unix seconds. */
    val blockTimestamp: Long,
    directNativeTransactions: List<DirectNativePaymentTransaction> = emptyList(),
) {
    private val retainedDirectNativeTransactions = directNativeTransactions.toList()
    val directNativeTransactions: List<DirectNativePaymentTransaction>
        get() = retainedDirectNativeTransactions.toList()

    init {
        require(blockNumber >= 0) { "Payment evidence block number must not be negative" }
        requireHash(blockHash, "Payment evidence block hash")
        require(blockTimestamp >= 0) { "Payment evidence block timestamp must not be negative" }
        require(retainedDirectNativeTransactions.all {
            it.blockNumber == blockNumber && it.blockHash.equals(blockHash, ignoreCase = true)
        }) { "Native payment transactions must belong to their containing block" }
    }
}

/** One decoded ERC-20 Transfer log whose recipient is the requested receiver. */
data class IncomingErc20Transfer(
    val txHash: String,
    val token: EvmAddress,
    val payer: EvmAddress,
    val recipient: EvmAddress,
    val logIndex: Long,
    val value: BigInteger,
    val blockNumber: Long,
    val blockHash: String,
    val removed: Boolean = false,
) {
    init {
        requireHash(txHash, "ERC-20 payment transaction hash")
        require(!token.isZero && !NativeAsset.isNative(token)) {
            "ERC-20 payment token must be a non-native non-zero address"
        }
        require(!payer.isZero) { "ERC-20 payment payer must not be zero" }
        require(!recipient.isZero) { "ERC-20 payment recipient must not be zero" }
        require(logIndex >= 0) { "ERC-20 payment log index must not be negative" }
        requireUint256(value, "ERC-20 payment value")
        require(blockNumber >= 0) { "ERC-20 payment block must not be negative" }
        requireHash(blockHash, "ERC-20 payment block hash")
    }
}

/**
 * Narrow read-only chain capability used by [PaymentEvidenceResolver]. It exposes only exact asset
 * balances, canonical blocks, and receiver-scoped ERC-20 Transfer logs, never arbitrary calls,
 * signing, transaction construction, or broadcast.
 */
interface PaymentEvidenceChainClient {
    fun chainId(): Long

    fun paymentAssetBalance(
        asset: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): BigInteger

    fun paymentEvidenceBlock(
        blockNumber: Long,
        includeDirectNativeTransactions: Boolean = false,
    ): PaymentEvidenceBlock?

    fun incomingErc20Transfers(
        token: EvmAddress,
        receiver: EvmAddress,
        blockNumber: Long,
    ): List<IncomingErc20Transfer>
}

/** Wave-1 snapshot of a batched evidence resolution. */
internal class PaymentEvidenceOpenContext(
    val chainId: Long,
    val publicationBlock: PaymentEvidenceBlock?,
    val fundingBlock: PaymentEvidenceBlock?,
    val publicationBalance: BigInteger,
    val fundingBalance: BigInteger,
)

/** Crossing-block reads of a batched evidence resolution. */
internal class PaymentCrossingReads(
    val block: PaymentEvidenceBlock?,
    /** Null for native assets, which have no Transfer logs. */
    val erc20Transfers: List<IncomingErc20Transfer>?,
    /** Null when the prior-height balance was not requested. */
    val priorBalance: BigInteger?,
)

/**
 * Deterministically attributes the first direct incoming transaction that crosses the invoice
 * threshold. Payment status remains owned by the canonical balance observer; a null result means
 * that the balance crossing cannot be attributed to a supported direct transaction.
 *
 * Production [ReadOnlyRpcClient] instances resolve through grouped JSON-RPC batches — the same
 * reads in the same trust order, with the closing bracket still re-reading every anchor fresh.
 * Custom [PaymentEvidenceChainClient] implementations use the sequential compatibility path.
 */
class PaymentEvidenceResolver(
    private val chain: PaymentEvidenceChainClient,
    /**
     * Optional end-to-end wall-clock budget for one [resolve] pass. Socket timeouts bound each
     * network operation; this bounds their sequential sum, so a caller running inside a
     * cooperative deadline (such as the terminal's background RPC lease) can size the two
     * together: budget plus one worst-case socket never exceeds the lease.
     */
    private val totalBudgetMillis: Long? = null,
    private val elapsedMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    init {
        require(totalBudgetMillis == null || totalBudgetMillis > 0) {
            "Evidence resolution budget must be positive"
        }
    }

    fun resolve(request: PaymentEvidenceRequest): PaymentTransactionEvidence? {
        val deadlineMillis = totalBudgetMillis?.let { Math.addExact(elapsedMillis(), it) }
        fun requireBudget() {
            if (deadlineMillis != null && elapsedMillis() > deadlineMillis) {
                throw RpcException("Payment evidence resolution exceeded its time budget")
            }
        }
        val batched = chain as? ReadOnlyRpcClient
        val balanceCache = mutableMapOf<Long, BigInteger>()
        fun recordBalance(blockNumber: Long, value: BigInteger): BigInteger {
            if (value.signum() < 0 || value > UINT256_MAX) {
                throw RpcException("Payment receiver balance is outside uint256")
            }
            balanceCache[blockNumber] = value
            return value
        }
        fun balanceAt(blockNumber: Long): BigInteger = balanceCache[blockNumber] ?: run {
            requireBudget()
            recordBalance(
                blockNumber,
                chain.paymentAssetBalance(request.asset, request.receiver, blockNumber),
            )
        }
        fun budgetedAnchor(anchor: PaymentConfirmationCursor, label: String): PaymentEvidenceBlock {
            requireBudget()
            return requireAnchor(anchor, label)
        }

        if (batched != null) {
            val context = batched.openPaymentEvidenceContext(
                asset = request.asset,
                receiver = request.receiver,
                publicationBlockNumber = request.publicationCursor.blockNumber,
                fundingBlockNumber = request.fundingCursor.blockNumber,
            )
            if (context.chainId != request.chainId) {
                throw NetworkConfigurationException(
                    "RPC chain ID ${context.chainId} does not match payment chain ID " +
                        "${request.chainId}",
                )
            }
            verifyAnchor(context.publicationBlock, request.publicationCursor, "publication")
            verifyAnchor(context.fundingBlock, request.fundingCursor, "funding")
            recordBalance(request.publicationCursor.blockNumber, context.publicationBalance)
            recordBalance(request.fundingCursor.blockNumber, context.fundingBalance)
        } else {
            requireBudget()
            val remoteChainId = chain.chainId()
            if (remoteChainId != request.chainId) {
                throw NetworkConfigurationException(
                    "RPC chain ID $remoteChainId does not match payment chain ID ${request.chainId}",
                )
            }
            budgetedAnchor(request.publicationCursor, "publication")
            budgetedAnchor(request.fundingCursor, "funding")
        }

        val publicationBalance = balanceAt(request.publicationCursor.blockNumber)
        if (publicationBalance >= request.expectedAmount) {
            throw RpcException("Invoice receiver already satisfied the payment at publication")
        }
        val firstCandidateBlock = try {
            Math.addExact(request.publicationCursor.blockNumber, 1L)
        } catch (error: ArithmeticException) {
            throw RpcException("Invoice publication block is outside the supported range", error)
        }
        if (batched != null && firstCandidateBlock < request.fundingCursor.blockNumber) {
            // Warm the exact heights the unchanged binary search can visit; deeper levels of a
            // wide range degrade to single reads instead of changing the search semantics.
            val prefetch = balanceCrossingMidpoints(
                firstCandidateBlock = firstCandidateBlock,
                lastCandidateBlock = request.fundingCursor.blockNumber,
                levels = BALANCE_PREFETCH_LEVELS,
            ).filterNot(balanceCache::containsKey)
            if (prefetch.isNotEmpty()) {
                requireBudget()
                batched.paymentAssetBalances(request.asset, request.receiver, prefetch)
                    .forEach { (blockNumber, value) -> recordBalance(blockNumber, value) }
            }
        }
        val crossingBlockNumber = findFirstBalanceCrossing(
            firstCandidateBlock = firstCandidateBlock,
            lastCandidateBlock = request.fundingCursor.blockNumber,
            expectedAmount = request.expectedAmount,
            balanceAt = ::balanceAt,
        ) ?: return null
        val isNative = NativeAsset.isNative(request.asset)
        val priorBalance: BigInteger
        val crossingBlock: PaymentEvidenceBlock
        val erc20Transfers: List<IncomingErc20Transfer>
        if (batched != null) {
            requireBudget()
            val reads = batched.paymentCrossingReads(
                asset = request.asset,
                receiver = request.receiver,
                blockNumber = crossingBlockNumber,
                includePriorBalance = (crossingBlockNumber - 1L) !in balanceCache,
            )
            reads.priorBalance?.let { recordBalance(crossingBlockNumber - 1L, it) }
            priorBalance = balanceAt(crossingBlockNumber - 1L)
            crossingBlock = reads.block
                ?: throw RpcException("Canonical payment block $crossingBlockNumber is unavailable")
            erc20Transfers = reads.erc20Transfers.orEmpty()
        } else {
            priorBalance = balanceAt(crossingBlockNumber - 1L)
            requireBudget()
            crossingBlock = chain.paymentEvidenceBlock(
                crossingBlockNumber,
                includeDirectNativeTransactions = isNative,
            ) ?: throw RpcException("Canonical payment block $crossingBlockNumber is unavailable")
            erc20Transfers = emptyList()
        }
        if (crossingBlock.blockNumber != crossingBlockNumber) {
            throw RpcException(
                "RPC returned payment block ${crossingBlock.blockNumber} for requested block " +
                    crossingBlockNumber,
            )
        }
        if (
            crossingBlockNumber == request.fundingCursor.blockNumber &&
            !crossingBlock.blockHash.equals(request.fundingCursor.blockHash, ignoreCase = true)
        ) {
            throw RpcException("Canonical funding block hash changed before payment attribution")
        }

        val selected = if (isNative) {
            selectDirectNativeTransaction(
                block = crossingBlock,
                receiver = request.receiver,
                priorBalance = priorBalance,
                expectedAmount = request.expectedAmount,
            )
        } else {
            val transfers = if (batched != null) {
                erc20Transfers
            } else {
                requireBudget()
                chain.incomingErc20Transfers(
                    token = request.asset,
                    receiver = request.receiver,
                    blockNumber = crossingBlockNumber,
                )
            }
            selectErc20Transfer(
                block = crossingBlock,
                requestAsset = request.asset,
                receiver = request.receiver,
                priorBalance = priorBalance,
                expectedAmount = request.expectedAmount,
                candidates = transfers,
            )
        } ?: return null

        // Close the canonical bracket only after all balance, block, and log reads complete.
        requireBudget()
        if (batched != null) {
            val closingNumbers = linkedSetOf(
                crossingBlock.blockNumber,
                request.publicationCursor.blockNumber,
                request.fundingCursor.blockNumber,
            )
            val closing = batched.paymentEvidenceBlockHeaders(closingNumbers)
            val finalPaymentBlock = verifyAnchor(
                closing[crossingBlock.blockNumber],
                PaymentConfirmationCursor(crossingBlock.blockNumber, crossingBlock.blockHash),
                "payment",
            )
            if (finalPaymentBlock.blockTimestamp != crossingBlock.blockTimestamp) {
                throw RpcException("Canonical payment block timestamp changed during attribution")
            }
            verifyAnchor(
                closing[request.publicationCursor.blockNumber],
                request.publicationCursor,
                "publication",
            )
            verifyAnchor(
                closing[request.fundingCursor.blockNumber],
                request.fundingCursor,
                "funding",
            )
        } else {
            val finalPaymentBlock = budgetedAnchor(
                PaymentConfirmationCursor(crossingBlock.blockNumber, crossingBlock.blockHash),
                "payment",
            )
            if (finalPaymentBlock.blockTimestamp != crossingBlock.blockTimestamp) {
                throw RpcException("Canonical payment block timestamp changed during attribution")
            }
            budgetedAnchor(request.publicationCursor, "publication")
            budgetedAnchor(request.fundingCursor, "funding")
        }
        return selected
    }

    private fun requireAnchor(
        anchor: PaymentConfirmationCursor,
        label: String,
    ): PaymentEvidenceBlock = verifyAnchor(chain.paymentEvidenceBlock(anchor.blockNumber), anchor, label)

    private fun verifyAnchor(
        canonical: PaymentEvidenceBlock?,
        anchor: PaymentConfirmationCursor,
        label: String,
    ): PaymentEvidenceBlock {
        if (canonical == null) {
            throw RpcException("Canonical $label block ${anchor.blockNumber} is unavailable")
        }
        if (canonical.blockNumber != anchor.blockNumber) {
            throw RpcException(
                "RPC returned canonical $label block ${canonical.blockNumber} for requested block " +
                    anchor.blockNumber,
            )
        }
        if (!canonical.blockHash.equals(anchor.blockHash, ignoreCase = true)) {
            throw RpcException("Canonical $label block hash does not match the saved invoice evidence")
        }
        return canonical
    }

    private companion object {
        /** Four warmed levels cover crossings inside a 16-block window with one batch. */
        private const val BALANCE_PREFETCH_LEVELS = 4
        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun selectErc20Transfer(
        block: PaymentEvidenceBlock,
        requestAsset: EvmAddress,
        receiver: EvmAddress,
        priorBalance: BigInteger,
        expectedAmount: BigInteger,
        candidates: List<IncomingErc20Transfer>,
    ): PaymentTransactionEvidence? {
        val ordered = candidates.sortedBy(IncomingErc20Transfer::logIndex)
        if (ordered.map(IncomingErc20Transfer::logIndex).distinct().size != ordered.size) {
            throw RpcException("ERC-20 payment logs contain a duplicate log index")
        }
        var cumulative = priorBalance
        ordered.forEach { candidate ->
            if (candidate.removed) {
                throw RpcException("ERC-20 payment log was removed")
            }
            if (candidate.recipient != receiver) {
                throw RpcException("ERC-20 payment log has the wrong receiver")
            }
            if (candidate.token != requestAsset) {
                throw RpcException("ERC-20 payment log has the wrong token")
            }
            if (
                candidate.blockNumber != block.blockNumber ||
                !candidate.blockHash.equals(block.blockHash, ignoreCase = true)
            ) {
                throw RpcException("ERC-20 payment log does not belong to its canonical block")
            }
            cumulative += candidate.value
            if (cumulative > UINT256_MAX) {
                throw RpcException("Cumulative ERC-20 payment value exceeds uint256")
            }
            if (cumulative >= expectedAmount) {
                return PaymentTransactionEvidence(
                    txHash = candidate.txHash.lowercase(),
                    payerAddress = candidate.payer.value,
                    blockNumber = block.blockNumber,
                    blockHash = block.blockHash.lowercase(),
                    blockTimestamp = block.blockTimestamp,
                )
            }
        }
        return null
    }

    private fun selectDirectNativeTransaction(
        block: PaymentEvidenceBlock,
        receiver: EvmAddress,
        priorBalance: BigInteger,
        expectedAmount: BigInteger,
    ): PaymentTransactionEvidence? {
        val ordered = block.directNativeTransactions
            .filter { it.recipient == receiver }
            .sortedBy(DirectNativePaymentTransaction::transactionIndex)
        if (ordered.map(DirectNativePaymentTransaction::transactionIndex).distinct().size != ordered.size) {
            throw RpcException("Native payment transactions contain a duplicate transaction index")
        }
        var cumulative = priorBalance
        ordered.forEach { candidate ->
            cumulative += candidate.value
            if (cumulative > UINT256_MAX) {
                throw RpcException("Cumulative native payment value exceeds uint256")
            }
            if (cumulative >= expectedAmount) {
                return PaymentTransactionEvidence(
                    txHash = candidate.txHash.lowercase(),
                    payerAddress = candidate.payer.value,
                    blockNumber = block.blockNumber,
                    blockHash = block.blockHash.lowercase(),
                    blockTimestamp = block.blockTimestamp,
                )
            }
        }
        // A canonical native balance may cross through an internal transfer, self-destruct, or
        // another indirect mechanism. Those paths have no attributable top-level customer tx here.
        return null
    }
}

/**
 * Every height [findFirstBalanceCrossing] can visit within its first [levels] bisection steps,
 * derived by simulating both comparison outcomes. Prefetching these heights cannot change which
 * blocks the unchanged search reads — only whether each read is served from a warmed cache.
 */
internal fun balanceCrossingMidpoints(
    firstCandidateBlock: Long,
    lastCandidateBlock: Long,
    levels: Int,
): Set<Long> {
    require(firstCandidateBlock >= 0) { "First candidate block must not be negative" }
    require(lastCandidateBlock >= firstCandidateBlock) { "Payment block range is invalid" }
    require(levels in 1..16) { "Prefetch levels are out of range" }
    val midpoints = linkedSetOf<Long>()
    fun visit(low: Long, high: Long, depth: Int) {
        if (depth <= 0 || low >= high) return
        val middle = low + (high - low) / 2
        midpoints.add(middle)
        visit(low, middle, depth - 1)
        visit(middle + 1, high, depth - 1)
    }
    visit(firstCandidateBlock, lastCandidateBlock, levels)
    return midpoints
}

internal fun findFirstBalanceCrossing(
    firstCandidateBlock: Long,
    lastCandidateBlock: Long,
    expectedAmount: BigInteger,
    balanceAt: (Long) -> BigInteger,
): Long? {
    require(firstCandidateBlock >= 0) { "First candidate block must not be negative" }
    require(lastCandidateBlock >= firstCandidateBlock) { "Payment block range is invalid" }
    require(expectedAmount.signum() > 0) { "Expected payment amount must be positive" }

    if (balanceAt(lastCandidateBlock) < expectedAmount) return null
    var low = firstCandidateBlock
    var high = lastCandidateBlock
    while (low < high) {
        val middle = low + (high - low) / 2
        if (balanceAt(middle) >= expectedAmount) high = middle else low = middle + 1
    }
    return low
}

internal val PAYMENT_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
internal val UINT256_MAX: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

internal fun requireHash(value: String, label: String): String {
    require(PAYMENT_HASH_PATTERN.matches(value)) { "$label is malformed" }
    return value.lowercase()
}

internal fun requireUint256(value: BigInteger, label: String): BigInteger {
    require(value.signum() >= 0 && value <= UINT256_MAX) { "$label is outside uint256" }
    return value
}
