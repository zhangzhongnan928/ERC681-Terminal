package com.openpasskey.erc681

import java.math.BigInteger

enum class PaymentStatus {
    AWAITING_PAYMENT,
    PARTIALLY_FUNDED,
    CONFIRMING,
    PAID,
}

data class PaymentObservation(
    val token: EvmAddress,
    val receiver: EvmAddress,
    val expectedAmount: TokenAmount,
    val observedRawUnits: BigInteger,
    val blockNumber: Long,
    val fundedAtBlock: Long?,
    val fundedAtBlockHash: String?,
    val confirmations: Int,
    val requiredConfirmations: Int,
    val status: PaymentStatus,
) {
    init {
        require(observedRawUnits.signum() >= 0) { "Observed amount must not be negative" }
        require(blockNumber >= 0) { "Block number must not be negative" }
        require(fundedAtBlock == null || fundedAtBlock in 0..blockNumber) { "Funding block is invalid" }
        require((fundedAtBlock == null) == (fundedAtBlockHash == null)) {
            "Funding block and canonical hash must either both be present or both be absent"
        }
        require(confirmations >= 0) { "Confirmations must not be negative" }
        require(requiredConfirmations > 0) { "Required confirmations must be greater than zero" }
    }

    val isOverpaid: Boolean get() = observedRawUnits > expectedAmount.rawUnits
}

/** Produces one reorg-aware payment observation per poll; callers retain the previous result. */
class PaymentObserver(private val chain: ReadOnlyChainClient) {
    @JvmOverloads
    fun observe(
        request: Erc681PaymentRequest,
        previous: PaymentObservation? = null,
        requiredConfirmations: Int = 1,
    ): PaymentObservation {
        require(requiredConfirmations > 0) { "Required confirmations must be greater than zero" }
        val remoteChainId = chain.chainId()
        if (remoteChainId != request.chainId) {
            throw NetworkConfigurationException(
                "RPC chain ID $remoteChainId does not match payment chain ID ${request.chainId}",
            )
        }

        val block = chain.blockNumber()
        val blockHashBefore = requireNotNull(chain.blockHash(block)) {
            "Canonical block $block is unavailable"
        }
        val balance = chain.tokenBalance(request.token, request.receiver, block)
        val blockHash = requireNotNull(chain.blockHash(block)) {
            "Canonical block $block became unavailable while sampling payment balance"
        }
        if (!blockHash.equals(blockHashBefore, ignoreCase = true)) {
            throw RpcException("Canonical block $block changed while sampling payment balance")
        }
        val expected = request.amount.rawUnits
        val identityMatches = previous != null &&
            previous.token == request.token &&
            previous.receiver == request.receiver &&
            previous.expectedAmount == request.amount &&
            previous.requiredConfirmations == requiredConfirmations

        val preservedCursor = if (balance >= expected) {
            previous?.fundedAtBlock?.takeIf { cursor ->
                val savedHash = previous.fundedAtBlockHash
                val canonicalHash = if (cursor == block) blockHash else {
                    runCatching { chain.blockHash(cursor) }.getOrNull()
                }
                identityMatches && previous.observedRawUnits == balance && cursor <= block &&
                    savedHash != null && canonicalHash?.equals(savedHash, ignoreCase = true) == true
            }
        } else {
            null
        }
        val finalBlockHash = requireNotNull(chain.blockHash(block)) {
            "Canonical block $block became unavailable after validating confirmation cursors"
        }
        if (!finalBlockHash.equals(blockHash, ignoreCase = true)) {
            throw RpcException("Canonical block $block changed while validating confirmation cursors")
        }
        val fundedAtBlock = if (balance >= expected) preservedCursor ?: block else null
        val fundedAtBlockHash = if (fundedAtBlock == null) null else {
            if (preservedCursor != null) previous?.fundedAtBlockHash else finalBlockHash
        }
        val confirmations = fundedAtBlock?.let { fundingBlock ->
            (block - fundingBlock + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } ?: 0
        val status = when {
            balance.signum() == 0 -> PaymentStatus.AWAITING_PAYMENT
            balance < expected -> PaymentStatus.PARTIALLY_FUNDED
            confirmations < requiredConfirmations -> PaymentStatus.CONFIRMING
            else -> PaymentStatus.PAID
        }

        return PaymentObservation(
            token = request.token,
            receiver = request.receiver,
            expectedAmount = request.amount,
            observedRawUnits = balance,
            blockNumber = block,
            fundedAtBlock = fundedAtBlock,
            fundedAtBlockHash = fundedAtBlockHash,
            confirmations = confirmations,
            requiredConfirmations = requiredConfirmations,
            status = status,
        )
    }
}
