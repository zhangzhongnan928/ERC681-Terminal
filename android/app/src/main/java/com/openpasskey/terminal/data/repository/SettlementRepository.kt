package com.openpasskey.terminal.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.SettlementEvent
import com.openpasskey.terminal.data.model.SettlementFeeMode
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.model.SettlementTransactionStatus
import com.openpasskey.terminal.settlement.SettlementAbi
import com.openpasskey.terminal.settlement.SettlementBalancePolicy
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.SettlementFeePolicy
import com.openpasskey.terminal.settlement.SettlementFeeQuote
import com.openpasskey.terminal.settlement.SettlementInvoiceIntent
import com.openpasskey.terminal.settlement.SettlementReceipt
import com.openpasskey.terminal.settlement.SweepProofClassification
import com.openpasskey.terminal.settlement.VerifiedSweep
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.UUID

data class PreparedSettlement(
    val invoiceIds: List<String>,
    val chainId: Long,
    val networkName: String,
    val rpcUrl: String,
    val vaultAddress: String,
    val tokenAddress: String,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val operatorAddress: String,
    val totalExpectedAmount: BigInteger,
    val totalObservedAmount: BigInteger,
    val callData: String,
    val nonce: BigInteger,
    val gasLimit: BigInteger,
    val feeQuote: SettlementFeeQuote,
    val maximumGasCost: BigInteger,
    val safetyReserve: BigInteger,
    val requiredBalance: BigInteger,
    val currentBalance: BigInteger,
    val requiredConfirmations: Int
)

data class PersistedSweepEvidence(
    val invoiceId: String,
    val sweptAmount: String,
    val expectedAmount: String,
    val feeAmount: String,
    val transactionHash: String,
    val blockHash: String,
    val logIndex: Long
)

/**
 * App-layer write path. The reusable ERC-681 SDK remains read-only. A single process-wide mutex,
 * pending nonces, and durable pre-broadcast records prevent concurrent nonce reuse.
 */
class SettlementRepository(
    private val database: InvoiceDatabase,
    private val walletStore: OperatorWalletStore,
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
    private val gson: Gson = Gson()
) {
    private val invoiceDao = database.invoiceDao()
    private val settlementDao = database.settlementDao()
    private val eventDao = database.settlementEventDao()

    fun observeReadyInvoices(): Flow<List<Invoice>> = invoiceDao.observeReadyForSettlement()
    fun observeRecentTransactions(limit: Int = 50): Flow<List<SettlementTransaction>> =
        settlementDao.observeRecent(limit)

    suspend fun prepare(invoiceIds: List<String>): PreparedSettlement = withContext(Dispatchers.IO) {
        prepareInternal(invoiceIds)
    }

    /** Must only be invoked after a fresh system biometric/device-credential prompt. */
    suspend fun submit(
        reviewed: PreparedSettlement,
        userExplicitlyConfirmed: Boolean
    ): SettlementTransaction = withContext(Dispatchers.IO) {
        require(userExplicitlyConfirmed) { "Settlement requires explicit operator confirmation" }
        submissionMutex.withLock {
            val fresh = prepareInternal(reviewed.invoiceIds)
            require(fresh.chainId == reviewed.chainId &&
                fresh.vaultAddress.equals(reviewed.vaultAddress, ignoreCase = true) &&
                fresh.tokenAddress.equals(reviewed.tokenAddress, ignoreCase = true)
            ) { "Settlement configuration changed; review it again" }
            require(!SettlementFeePolicy.exceedsConfirmedCost(
                reviewed.requiredBalance,
                fresh.requiredBalance
            )) { "Network fees increased by more than 20%; review the new maximum before signing" }

            val active = settlementDao.getActiveForOperator(
                fresh.chainId,
                fresh.operatorAddress,
                ACTIVE_STATUSES
            )
            require(active.isEmpty()) {
                "Another operator transaction is pending; recover it before assigning a new nonce"
            }

            val raw = when (fresh.feeQuote.mode) {
                SettlementFeeMode.LEGACY -> RawTransaction.createTransaction(
                    fresh.nonce,
                    requireNotNull(fresh.feeQuote.gasPrice),
                    fresh.gasLimit,
                    fresh.vaultAddress,
                    BigInteger.ZERO,
                    fresh.callData
                )
                SettlementFeeMode.EIP1559 -> RawTransaction.createTransaction(
                    fresh.chainId,
                    fresh.nonce,
                    fresh.gasLimit,
                    fresh.vaultAddress,
                    BigInteger.ZERO,
                    fresh.callData,
                    requireNotNull(fresh.feeQuote.maxPriorityFeePerGas),
                    requireNotNull(fresh.feeQuote.maxFeePerGas)
                )
            }
            val signedBytes = walletStore.signSettlementTransaction(
                raw,
                fresh.chainId,
                fresh.feeQuote.mode == SettlementFeeMode.EIP1559
            )
            val signedHex = Numeric.toHexString(signedBytes)
            signedBytes.fill(0)
            val localHash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(signedHex)))
            val now = nowSeconds()
            val invoices = requireEligibleInvoices(fresh.invoiceIds)
            val transaction = SettlementTransaction(
                id = UUID.randomUUID().toString(),
                chainId = fresh.chainId,
                networkName = fresh.networkName,
                rpcUrl = fresh.rpcUrl,
                vaultAddress = fresh.vaultAddress,
                tokenAddress = fresh.tokenAddress,
                tokenSymbol = fresh.tokenSymbol,
                operatorAddress = fresh.operatorAddress,
                invoiceIdsJson = gson.toJson(invoices.map(Invoice::invoiceId)),
                expectedAmountsJson = gson.toJson(invoices.map(Invoice::expectedAmount)),
                receiverAddressesJson = gson.toJson(invoices.map(Invoice::receiver)),
                requiredConfirmations = fresh.requiredConfirmations,
                callData = fresh.callData,
                nonce = fresh.nonce.toString(),
                gasLimit = fresh.gasLimit.toString(),
                feeMode = fresh.feeQuote.mode,
                gasPrice = fresh.feeQuote.gasPrice?.toString(),
                maxPriorityFeePerGas = fresh.feeQuote.maxPriorityFeePerGas?.toString(),
                maxFeePerGas = fresh.feeQuote.maxFeePerGas?.toString(),
                maxGasCostWei = fresh.maximumGasCost.toString(),
                feeReserveWei = fresh.safetyReserve.toString(),
                requiredBalanceWei = fresh.requiredBalance.toString(),
                txHash = localHash,
                signedRawTransaction = signedHex,
                status = SettlementTransactionStatus.SIGNED,
                createdAt = now,
                updatedAt = now
            )
            database.withTransaction {
                settlementDao.insert(transaction)
                val attached = invoiceDao.attachSettlement(fresh.invoiceIds, transaction.id)
                check(attached == fresh.invoiceIds.size) {
                    "One or more invoices became unavailable before signing was persisted"
                }
            }
            broadcastAndRecover(transaction.id)
        }
    }

    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        submissionMutex.withLock {
            settlementDao.getByStatuses(ACTIVE_STATUSES).forEach { transaction ->
                runCatching { broadcastAndRecover(transaction.id) }
                    .onFailure { error -> recordRecoverableError(transaction, error) }
            }
        }
    }

    suspend fun refreshTransaction(id: String): SettlementTransaction = withContext(Dispatchers.IO) {
        submissionMutex.withLock { broadcastAndRecover(id) }
    }

    private suspend fun prepareInternal(invoiceIds: List<String>): PreparedSettlement {
        require(invoiceIds.isNotEmpty()) { "Choose at least one invoice" }
        require(invoiceIds.size <= SettlementAbi.MAX_BATCH_SIZE) {
            "Choose at most ${SettlementAbi.MAX_BATCH_SIZE} invoices"
        }
        val invoices = requireEligibleInvoices(invoiceIds)
        val first = invoices.first()
        require(first.hasSettlementSnapshot()) { "Invoice is missing its immutable network snapshot" }
        invoices.forEach { invoice ->
            require(invoice.hasSettlementSnapshot()) { "Invoice ${invoice.invoiceId} has no network snapshot" }
            require(invoice.chainId == first.chainId && invoice.rpcUrl == first.rpcUrl &&
                invoice.vaultAddress.equals(first.vaultAddress, true) &&
                invoice.token.equals(first.token, true)
            ) { "Batch invoices must use the same network, vault, and token snapshot" }
            require(BigInteger(invoice.receivedAmount) >= BigInteger(invoice.expectedAmount)) {
                "Invoice ${invoice.invoiceId} is not fully funded"
            }
            require(invoice.confirmedAtBlock != null) { "Invoice ${invoice.invoiceId} is not confirmed" }
            require(!EvmAddress.parse(invoice.receiver).isZero) { "Invoice receiver must not be zero" }
            require(!EvmAddress.parse(invoice.token).isZero) { "Settlement token must not be zero" }
            require(!EvmAddress.parse(invoice.vaultAddress).isZero) { "Settlement vault must not be zero" }
        }
        val wallet = walletStore.snapshot()
        require(wallet.availability == OperatorWalletAvailability.READY) {
            wallet.error ?: "Create the terminal operator wallet first"
        }
        val operator = EvmAddress.parse(requireNotNull(wallet.address)).value
        val intents = invoices.map {
            SettlementInvoiceIntent(it.invoiceId, it.receiver, BigInteger(it.expectedAmount))
        }
        val callData = SettlementAbi.encodeSweepSessions(intents, first.token)

        clientFactory(first.rpcUrl).use { client ->
            require(client.chainId() == first.chainId) { "RPC chain ID does not match the invoice snapshot" }
            val ownerResult = runCatching { client.owner(first.vaultAddress) }
            val operatorResult = runCatching { client.isOperator(first.vaultAddress, operator) }
            val authorized = ownerResult.getOrNull()?.equals(operator, true) == true ||
                operatorResult.getOrNull() == true
            require(authorized) {
                val failures = listOfNotNull(
                    ownerResult.exceptionOrNull()?.message,
                    operatorResult.exceptionOrNull()?.message
                ).joinToString("; ")
                if (failures.isBlank()) "Operator is not authorized by the vault"
                else "Unable to prove vault authorization: $failures"
            }
            invoices.forEach { invoice ->
                val liveBalance = client.tokenBalance(invoice.token, invoice.receiver)
                val previouslyProven = previouslyProvenSwept(invoice)
                if (invoice.status == InvoiceStatus.PARTIALLY_SETTLED) {
                    require(previouslyProven.signum() > 0) {
                        "Partial invoice has no durable prior sweep evidence"
                    }
                    require(previouslyProven < BigInteger(invoice.expectedAmount)) {
                        "Cumulative proof already covers this invoice; settlement status requires repair"
                    }
                    require(liveBalance.signum() > 0) {
                        "Receiver ${invoice.receiver} has zero pending balance; nothing new can be swept"
                    }
                } else {
                    require(liveBalance >= BigInteger(invoice.expectedAmount)) {
                        "Receiver ${invoice.receiver} now holds $liveBalance, below the expected " +
                            "${invoice.expectedAmount}; it may already have been swept"
                    }
                }
            }
            client.simulate(operator, first.vaultAddress, callData)
            // Cache only the exact chain/vault target proven above. This gates the constrained
            // signer; invoice identity always comes from the operator's public address.
            walletStore.recordVerifiedSettlementTarget(first.chainId, first.vaultAddress)
            val nonce = client.pendingNonce(operator)
            val gasLimit = client.estimateGas(operator, first.vaultAddress, callData, nonce)
            val quote = client.feeQuote()
            val requirement = SettlementBalancePolicy.requirement(gasLimit, quote)
            val balance = client.nativeBalance(operator)
            require(balance >= requirement.requiredBalance) {
                "Low gas balance: requires ${requirement.requiredBalance} wei including reserve; has $balance wei"
            }
            return PreparedSettlement(
                invoiceIds = invoices.map(Invoice::invoiceId),
                chainId = first.chainId,
                networkName = first.networkName,
                rpcUrl = first.rpcUrl,
                vaultAddress = EvmAddress.parse(first.vaultAddress).value,
                tokenAddress = EvmAddress.parse(first.token).value,
                tokenSymbol = first.tokenSymbol,
                tokenDecimals = first.tokenDecimals,
                operatorAddress = operator,
                totalExpectedAmount = invoices.sumOfBigInteger { BigInteger(it.expectedAmount) },
                totalObservedAmount = invoices.sumOfBigInteger { BigInteger(it.receivedAmount) },
                callData = callData,
                nonce = nonce,
                gasLimit = gasLimit,
                feeQuote = quote,
                maximumGasCost = requirement.maximumGasCost,
                safetyReserve = requirement.safetyReserve,
                requiredBalance = requirement.requiredBalance,
                currentBalance = balance,
                requiredConfirmations = invoices.maxOf { it.confirmationBlocks.coerceAtLeast(1) }
            )
        }
    }

    private suspend fun broadcastAndRecover(id: String): SettlementTransaction {
        var transaction = requireNotNull(settlementDao.getById(id)) { "Settlement transaction not found" }
        if (transaction.status !in ACTIVE_STATUSES) return transaction
        clientFactory(transaction.rpcUrl).use { client ->
            require(client.chainId() == transaction.chainId) { "RPC chain changed during recovery" }
            if (transaction.status == SettlementTransactionStatus.SIGNED) {
                val raw = requireNotNull(transaction.signedRawTransaction) {
                    "Signed transaction bytes are unavailable"
                }
                var alreadyMinedReceipt = client.transactionReceipt(transaction.txHash)
                if (alreadyMinedReceipt == null) {
                    try {
                        val returnedHash = client.sendRawTransaction(raw)
                        require(returnedHash.equals(transaction.txHash, true)) {
                            "RPC returned a different transaction hash"
                        }
                    } catch (error: Exception) {
                        // A response can be lost after acceptance. Always query the deterministic
                        // local hash, and treat an exact "already known" response as submitted.
                        alreadyMinedReceipt = client.transactionReceipt(transaction.txHash)
                        val alreadyKnown = error.message.orEmpty().lowercase().let { message ->
                            "already known" in message || "known transaction" in message
                        }
                        if (alreadyMinedReceipt == null && !alreadyKnown) {
                            transaction = transaction.copy(
                                status = SettlementTransactionStatus.SIGNED,
                                error = error.message ?: "Broadcast result unknown",
                                updatedAt = nowSeconds()
                            )
                            settlementDao.update(transaction)
                            return transaction
                        }
                    }
                    transaction = transaction.copy(
                        status = SettlementTransactionStatus.SUBMITTED,
                        error = null,
                        updatedAt = nowSeconds()
                    )
                    settlementDao.update(transaction)
                } else {
                    transaction = transaction.copy(
                        status = SettlementTransactionStatus.SUBMITTED,
                        error = null,
                        updatedAt = nowSeconds()
                    )
                    settlementDao.update(transaction)
                }
            }

            var receipt: SettlementReceipt? = null
            var receiptAttempt = 0
            while (receipt == null && receiptAttempt < RECEIPT_POLL_ATTEMPTS) {
                receipt = client.transactionReceipt(transaction.txHash)
                receiptAttempt += 1
                if (receipt == null && receiptAttempt < RECEIPT_POLL_ATTEMPTS) {
                    delay(RECEIPT_POLL_MILLIS)
                }
            }
            val firstReceipt = receipt ?: run {
                val wasConfirming = transaction.status == SettlementTransactionStatus.CONFIRMING
                val pending = transaction.copy(
                    status = if (wasConfirming) SettlementTransactionStatus.CONFIRMING
                    else SettlementTransactionStatus.SUBMITTED,
                    receiptBlock = if (wasConfirming) null else transaction.receiptBlock,
                    error = if (wasConfirming) {
                        "Canonical receipt temporarily unavailable; recovery will keep checking"
                    } else {
                        "Transaction is pending; recovery will continue automatically"
                    },
                    updatedAt = nowSeconds()
                )
                settlementDao.update(pending)
                return pending
            }
            if (!firstReceipt.successful) return finalizeReverted(transaction, firstReceipt)

            transaction = transaction.copy(
                status = SettlementTransactionStatus.CONFIRMING,
                receiptBlock = firstReceipt.blockNumber,
                error = null,
                updatedAt = nowSeconds()
            )
            settlementDao.update(transaction)
            val target = firstReceipt.blockNumber + transaction.requiredConfirmations.coerceAtLeast(1) - 1
            var latestBlock = client.blockNumber()
            var confirmationAttempt = 0
            while (latestBlock < target && confirmationAttempt < CONFIRMATION_POLL_ATTEMPTS) {
                delay(RECEIPT_POLL_MILLIS)
                latestBlock = client.blockNumber()
                confirmationAttempt += 1
            }
            if (latestBlock < target) return transaction

            // Re-fetch after the confirmation window. A missing/moved receipt is a reorg, not proof.
            val confirmedReceipt = client.transactionReceipt(transaction.txHash)
            if (confirmedReceipt == null ||
                confirmedReceipt.blockNumber != firstReceipt.blockNumber ||
                !confirmedReceipt.blockHash.equals(firstReceipt.blockHash, true)
            ) {
                transaction = transaction.copy(
                    status = SettlementTransactionStatus.CONFIRMING,
                    receiptBlock = null,
                    error = "Receipt changed during confirmation; waiting for canonical inclusion",
                    updatedAt = nowSeconds()
                )
                settlementDao.update(transaction)
                return transaction
            }
            if (!confirmedReceipt.successful) return finalizeReverted(transaction, confirmedReceipt)
            return verifyAndFinalize(transaction, confirmedReceipt)
        }
    }

    private suspend fun verifyAndFinalize(
        transaction: SettlementTransaction,
        receipt: SettlementReceipt
    ): SettlementTransaction {
        require(receipt.transactionHash.equals(transaction.txHash, true)) {
            "Receipt transaction hash does not match the signed transaction"
        }
        val intents = transaction.toIntents()
        val proofs = SettlementAbi.verifySweptEvents(
            receipt.logs,
            transaction.vaultAddress,
            transaction.tokenAddress,
            intents
        )
        val usableProofs = proofs.filterValues { proof ->
            proof.transactionHash?.equals(transaction.txHash, true) == true &&
                proof.blockHash?.equals(receipt.blockHash, true) == true &&
                proof.logIndex != null
        }
        val fullIds = mutableListOf<String>()
        val partialIds = mutableListOf<String>()
        val zeroIds = mutableListOf<String>()
        val missingIds = mutableListOf<String>()
        intents.forEach { intent ->
            val proof = usableProofs[intent.invoiceId.lowercase()]
            val previouslyProven = previouslyProvenSwept(
                transaction.chainId,
                transaction.vaultAddress,
                intent.invoiceId,
                transaction.tokenAddress
            )
            when {
                proof == null -> missingIds += intent.invoiceId
                SettlementAbi.classify(proof, previouslyProven) == SweepProofClassification.ZERO ->
                    zeroIds += intent.invoiceId
                SettlementAbi.classify(proof, previouslyProven) == SweepProofClassification.PARTIAL ->
                    partialIds += intent.invoiceId
                else -> fullIds += intent.invoiceId
            }
        }
        val evidence = usableProofs.values.map { it.toEvidence(transaction, receipt) }
        val persistedJson = gson.toJson(evidence.map {
            PersistedSweepEvidence(
                it.invoiceId,
                it.sweptAmount,
                it.expectedAmount,
                it.feeAmount,
                it.transactionHash,
                it.blockHash,
                it.logIndex
            )
        })
        val verifiedIds = fullIds + partialIds
        val finalStatus = when {
            fullIds.size == intents.size -> SettlementTransactionStatus.VERIFIED
            verifiedIds.isNotEmpty() -> SettlementTransactionStatus.PARTIALLY_VERIFIED
            else -> SettlementTransactionStatus.VERIFICATION_FAILED
        }
        val messageParts = buildList {
            if (partialIds.isNotEmpty()) add("${partialIds.size} partial sweep(s) require review")
            if (zeroIds.isNotEmpty()) add("${zeroIds.size} zero sweep(s) remain unsettled")
            if (missingIds.isNotEmpty()) add("${missingIds.size} invoice(s) lack unique receipt proof")
        }
        val updated = transaction.copy(
            status = finalStatus,
            signedRawTransaction = null,
            receiptBlock = receipt.blockNumber,
            verifiedInvoiceIdsJson = gson.toJson(verifiedIds),
            verifiedEventsJson = persistedJson,
            error = messageParts.joinToString("; ").ifBlank { null },
            updatedAt = nowSeconds()
        )
        database.withTransaction {
            if (evidence.isNotEmpty()) eventDao.insertAll(evidence)
            if (fullIds.isNotEmpty()) invoiceDao.markSettled(
                fullIds,
                transaction.id,
                transaction.txHash,
                receipt.blockNumber
            )
            if (partialIds.isNotEmpty()) invoiceDao.markPartiallySettled(
                partialIds,
                transaction.txHash,
                receipt.blockNumber
            )
            if (zeroIds.isNotEmpty()) invoiceDao.releaseSettlement(zeroIds, transaction.id)
            if (missingIds.isNotEmpty()) invoiceDao.markSettlementReviewRequired(
                missingIds,
                transaction.id,
                transaction.txHash,
                receipt.blockNumber
            )
            settlementDao.update(updated)
        }
        return updated
    }

    private suspend fun finalizeReverted(
        transaction: SettlementTransaction,
        receipt: SettlementReceipt
    ): SettlementTransaction {
        val updated = transaction.copy(
            status = SettlementTransactionStatus.REVERTED,
            signedRawTransaction = null,
            receiptBlock = receipt.blockNumber,
            error = "Settlement transaction reverted on-chain",
            updatedAt = nowSeconds()
        )
        database.withTransaction {
            invoiceDao.releaseSettlement(transaction.invoiceIds(), transaction.id)
            settlementDao.update(updated)
        }
        return updated
    }

    private suspend fun recordRecoverableError(transaction: SettlementTransaction, error: Throwable) {
        val latest = settlementDao.getById(transaction.id) ?: return
        if (latest.status !in ACTIVE_STATUSES) return
        settlementDao.update(latest.copy(
            error = error.message ?: "Settlement recovery failed",
            updatedAt = nowSeconds()
        ))
    }

    private suspend fun requireEligibleInvoices(ids: List<String>): List<Invoice> {
        require(ids.distinctBy { it.lowercase() }.size == ids.size) { "Duplicate invoice IDs" }
        val byId = invoiceDao.getByIds(ids).associateBy { it.invoiceId.lowercase() }
        val ordered = ids.map { id ->
            byId[id.lowercase()] ?: throw IllegalArgumentException("Invoice $id was not found")
        }
        ordered.forEach { invoice ->
            val firstSweep = invoice.status in setOf(InvoiceStatus.PAID, InvoiceStatus.OVERPAID) &&
                invoice.settledTxHash == null
            val provenPartial = invoice.status == InvoiceStatus.PARTIALLY_SETTLED &&
                invoice.settledTxHash != null
            require((firstSweep || provenPartial) && invoice.settlementId == null) {
                "Invoice ${invoice.invoiceId} is not eligible for settlement"
            }
        }
        return ordered
    }

    private fun SettlementTransaction.invoiceIds(): List<String> =
        gson.fromJson(invoiceIdsJson, STRING_LIST_TYPE)

    private fun SettlementTransaction.toIntents(): List<SettlementInvoiceIntent> {
        val ids: List<String> = gson.fromJson(invoiceIdsJson, STRING_LIST_TYPE)
        val amounts: List<String> = gson.fromJson(expectedAmountsJson, STRING_LIST_TYPE)
        val receivers: List<String> = gson.fromJson(receiverAddressesJson, STRING_LIST_TYPE)
        require(ids.size == amounts.size && ids.size == receivers.size) {
            "Persisted settlement intent arrays have different lengths"
        }
        return ids.indices.map { index ->
            SettlementInvoiceIntent(ids[index], receivers[index], BigInteger(amounts[index]))
        }
    }

    private fun VerifiedSweep.toEvidence(
        transaction: SettlementTransaction,
        receipt: SettlementReceipt
    ): SettlementEvent {
        val index = requireNotNull(logIndex)
        val txHash = requireNotNull(transactionHash)
        val canonicalBlockHash = requireNotNull(blockHash)
        return SettlementEvent(
            eventId = "${transaction.chainId}:${txHash.lowercase()}:$index",
            settlementId = transaction.id,
            invoiceId = invoiceId,
            chainId = transaction.chainId,
            transactionHash = txHash,
            blockHash = canonicalBlockHash,
            blockNumber = receipt.blockNumber,
            logIndex = index,
            receiverAddress = receiver,
            vaultAddress = vault,
            tokenAddress = token,
            sweptAmount = sweptAmount.toString(),
            expectedAmount = expectedAmount.toString(),
            feeAmount = fee.toString(),
            recordedAt = nowSeconds()
        )
    }

    private fun Invoice.hasSettlementSnapshot(): Boolean =
        chainId > 0 && rpcUrl.isNotBlank() && vaultAddress.isNotBlank() && token.isNotBlank()

    private suspend fun previouslyProvenSwept(invoice: Invoice): BigInteger =
        previouslyProvenSwept(
            invoice.chainId,
            invoice.vaultAddress,
            invoice.invoiceId,
            invoice.token
        )

    private suspend fun previouslyProvenSwept(
        chainId: Long,
        vaultAddress: String,
        invoiceId: String,
        tokenAddress: String
    ): BigInteger = eventDao.getByInvoiceScope(
        chainId,
        vaultAddress,
        invoiceId,
        tokenAddress
    ).fold(BigInteger.ZERO) { total, event -> total + BigInteger(event.sweptAmount) }

    private inline fun <T> Iterable<T>.sumOfBigInteger(selector: (T) -> BigInteger): BigInteger =
        fold(BigInteger.ZERO) { total, item -> total.add(selector(item)) }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000

    companion object {
        private val submissionMutex = Mutex()
        private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type
        private val ACTIVE_STATUSES = listOf(
            SettlementTransactionStatus.SIGNED,
            SettlementTransactionStatus.SUBMITTED,
            SettlementTransactionStatus.CONFIRMING
        )
        private const val RECEIPT_POLL_MILLIS = 3_000L
        private const val RECEIPT_POLL_ATTEMPTS = 8
        private const val CONFIRMATION_POLL_ATTEMPTS = 20
    }
}
