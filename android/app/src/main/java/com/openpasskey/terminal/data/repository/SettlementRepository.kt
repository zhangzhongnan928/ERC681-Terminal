package com.openpasskey.terminal.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.InvoiceId
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.provisioning.KnownChainPolicy
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
import com.openpasskey.terminal.settlement.requireSameSettlementBatchSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val confirmedObservedAmounts: List<BigInteger>,
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
    private val chainConfig: ChainConfig,
    private val lifecycleGate: TerminalLifecycleGate,
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
                fresh.tokenAddress.equals(reviewed.tokenAddress, ignoreCase = true) &&
                fresh.invoiceIds == reviewed.invoiceIds &&
                fresh.confirmedObservedAmounts == reviewed.confirmedObservedAmounts
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
            // The contract sweeps each receiver's entire live balance. Re-read the exact amounts
            // immediately before signing so value that arrived after confirmation/review cannot be
            // swept under an older approval.
            requirePreparedBalancesStillExact(fresh)
            val transaction = lifecycleGate.withExclusiveMutation {
                requireCurrentOperatorBinding(fresh.operatorAddress)
                val invoices = requireEligibleInvoices(fresh.invoiceIds)
                require(
                    invoices.map(Invoice::settlementObservedAmount) ==
                        fresh.confirmedObservedAmounts,
                ) { "Confirmed invoice observations changed immediately before signing" }
                // Activate only the exact historical target just revalidated above, then invoke
                // the constrained signer in the same local mutation critical section.
                val signedBytes = walletStore.activateAndSignSettlementTransaction(
                    transaction = raw,
                    chainId = fresh.chainId,
                    vaultAddress = fresh.vaultAddress,
                    operatorAddress = fresh.operatorAddress,
                    eip1559 = fresh.feeQuote.mode == SettlementFeeMode.EIP1559,
                )
                val signedHex = Numeric.toHexString(signedBytes)
                signedBytes.fill(0)
                val localHash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(signedHex)))
                val now = nowSeconds()
                val persisted = SettlementTransaction(
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
                    settlementDao.insert(persisted)
                    val attached = invoiceDao.attachSettlement(fresh.invoiceIds, persisted.id)
                    check(attached == fresh.invoiceIds.size) {
                        "One or more invoices became unavailable before signing was persisted"
                    }
                }
                persisted
            }
            broadcastAndRecover(transaction.id)
        }
    }

    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        submissionMutex.withLock {
            settlementDao.getByStatuses(ACTIVE_STATUSES).forEach { transaction ->
                try {
                    broadcastAndRecover(transaction.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordRecoverableError(transaction, error)
                }
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
        val previouslyProvenByInvoice = invoices.associate { invoice ->
            invoice.invoiceId.lowercase() to previouslyProvenSwept(invoice)
        }
        require(first.hasSettlementSnapshot()) { "Invoice is missing its immutable network snapshot" }
        requireSameSettlementBatchSnapshot(invoices)
        invoices.forEach { invoice ->
            require(invoice.hasSettlementSnapshot()) { "Invoice ${invoice.invoiceId} has no network snapshot" }
            val previouslyProven = previouslyProvenByInvoice.getValue(invoice.invoiceId.lowercase())
            requireSettlementObservation(invoice, previouslyProven)
            require(!EvmAddress.parse(invoice.receiver).isZero) { "Invoice receiver must not be zero" }
            require(!EvmAddress.parse(invoice.token).isZero) { "Settlement token must not be zero" }
            require(!EvmAddress.parse(invoice.vaultAddress).isZero) { "Settlement vault must not be zero" }
            requirePinnedHistoricalInvoiceSnapshot(invoice)
        }
        validateHistoricalSettlementSnapshot(first)
        val wallet = walletStore.snapshot()
        require(wallet.availability == OperatorWalletAvailability.READY) {
            wallet.error ?: "Create the terminal operator wallet first"
        }
        val operator = EvmAddress.parse(requireNotNull(wallet.address)).value
        requireCurrentOperatorBinding(operator)
        requireInvoiceOperatorSnapshots(invoices, operator)
        val intents = invoices.map {
            SettlementInvoiceIntent(it.invoiceId, it.receiver, BigInteger(it.expectedAmount))
        }
        val callData = SettlementAbi.encodeSweepSessions(intents, first.token)
        val confirmedObservedAmounts = invoices.map(Invoice::settlementObservedAmount)

        clientFactory(first.rpcUrl).use { client ->
            require(client.chainId() == first.chainId) { "RPC chain ID does not match the invoice snapshot" }
            requireOperatorAuthorization(client, first.vaultAddress, operator)
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(invoice, client::canonicalBlockHash)
                val liveBalance = client.tokenBalance(invoice.token, invoice.receiver)
                val previouslyProven = previouslyProvenByInvoice.getValue(invoice.invoiceId.lowercase())
                if (invoice.status == InvoiceStatus.LATE_PAYMENT_READY) {
                    require(previouslyProven.signum() > 0 || invoice.settlementAmbiguous) {
                        "Late-payment invoice has no durable prior sweep evidence"
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
                require(liveBalance == confirmedObservedAmounts[index]) {
                    "Receiver ${invoice.receiver} balance changed after confirmation; refresh and " +
                        "wait for the exact current balance to confirm before settlement"
                }
            }
            client.simulate(operator, first.vaultAddress, callData)
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
                totalObservedAmount = confirmedObservedAmounts.fold(BigInteger.ZERO, BigInteger::add),
                confirmedObservedAmounts = confirmedObservedAmounts,
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
                        val alreadyKnown = isKnownTransactionResponse(error.message)
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
                var rebroadcastError: String? = null
                val retainedRaw = transaction.signedRawTransaction
                if (retainedRaw != null) {
                    try {
                        val returnedHash = client.sendRawTransaction(retainedRaw)
                        require(returnedHash.equals(transaction.txHash, true)) {
                            "RPC returned a different transaction hash while rebroadcasting"
                        }
                    } catch (error: Exception) {
                        if (!isKnownTransactionResponse(error.message)) {
                            rebroadcastError = error.message ?: "Identical transaction rebroadcast failed"
                        }
                    }
                } else {
                    rebroadcastError = "Signed transaction bytes are unavailable for recovery"
                }
                val wasConfirming = transaction.status == SettlementTransactionStatus.CONFIRMING
                val pending = transaction.copy(
                    status = if (wasConfirming) SettlementTransactionStatus.CONFIRMING
                    else SettlementTransactionStatus.SUBMITTED,
                    receiptBlock = if (wasConfirming) null else transaction.receiptBlock,
                    error = rebroadcastError ?: if (wasConfirming) {
                        "Canonical receipt temporarily unavailable; recovery will keep checking"
                    } else {
                        "Transaction is pending; recovery will continue automatically"
                    },
                    updatedAt = nowSeconds()
                )
                settlementDao.update(pending)
                return pending
            }
            // Success, revert, and receipt log classification are all provisional until this
            // exact canonical receipt survives the configured confirmation window.
            transaction = transaction.copy(
                status = SettlementTransactionStatus.CONFIRMING,
                receiptBlock = firstReceipt.blockNumber,
                error = null,
                updatedAt = nowSeconds()
            )
            settlementDao.update(transaction)
            val target = settlementConfirmationTarget(
                firstReceipt.blockNumber,
                transaction.requiredConfirmations,
            )
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
                !confirmedReceipt.transactionHash.equals(transaction.txHash, true) ||
                confirmedReceipt.blockNumber != firstReceipt.blockNumber ||
                !confirmedReceipt.blockHash.equals(firstReceipt.blockHash, true) ||
                confirmedReceipt.successful != firstReceipt.successful
            ) {
                transaction = transaction.copy(
                    status = SettlementTransactionStatus.CONFIRMING,
                    receiptBlock = null,
                    error = "Receipt identity changed during confirmation; waiting for canonical inclusion",
                    updatedAt = nowSeconds()
                )
                settlementDao.update(transaction)
                return transaction
            }
            val canonicalHashResult = runCatching {
                client.canonicalBlockHash(confirmedReceipt.blockNumber)
            }
            val canonicalHash = canonicalHashResult.getOrNull()
            if (!receiptMatchesCanonicalBlock(confirmedReceipt, canonicalHash)) {
                transaction = transaction.copy(
                    status = SettlementTransactionStatus.CONFIRMING,
                    receiptBlock = null,
                    error = canonicalHashResult.exceptionOrNull()?.let { error ->
                        "Unable to verify the canonical receipt block: ${error.message ?: "RPC read failed"}"
                    } ?: "Receipt block is missing or orphaned; waiting for canonical inclusion",
                    updatedAt = nowSeconds(),
                )
                settlementDao.update(transaction)
                return transaction
            }
            // The head used to enter finality can regress or come from a different backend than
            // the receipt/hash reads above. Re-read it after those identity checks and require the
            // configured depth to still hold before terminalizing either success or revert.
            val finalHeadResult = runCatching { client.blockNumber() }
            val finalHead = finalHeadResult.getOrNull()
            if (finalHead == null || finalHead < target) {
                transaction = transaction.copy(
                    status = SettlementTransactionStatus.CONFIRMING,
                    receiptBlock = confirmedReceipt.blockNumber,
                    error = finalHeadResult.exceptionOrNull()?.let { error ->
                        "Unable to recheck final confirmation depth: " +
                            (error.message ?: "RPC read failed")
                    } ?: "Confirmation depth changed during finality verification; waiting for " +
                        "the canonical head to reach block $target",
                    updatedAt = nowSeconds(),
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
        val invoicesById = invoiceDao.getByIds(intents.map { it.invoiceId })
            .associateBy { it.invoiceId.lowercase() }
        check(invoicesById.size == intents.size) {
            "One or more settlement invoices disappeared before receipt verification"
        }
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
        val reviewIds = mutableListOf<String>()
        intents.forEach { intent ->
            val invoice = checkNotNull(invoicesById[intent.invoiceId.lowercase()]) {
                "Invoice ${intent.invoiceId} disappeared before receipt verification"
            }
            val proof = usableProofs[intent.invoiceId.lowercase()]
            val previouslyProven = previouslyProvenSwept(
                transaction.chainId,
                transaction.vaultAddress,
                intent.invoiceId,
                transaction.tokenAddress
            )
            val classification = proof?.let { SettlementAbi.classify(it, previouslyProven) }
            if (shouldPersistSettlementReview(invoice.settlementAmbiguous, classification)) {
                reviewIds += intent.invoiceId
            }
            when (classification) {
                null -> {
                    missingIds += intent.invoiceId
                }
                SweepProofClassification.ZERO -> {
                    zeroIds += intent.invoiceId
                }
                SweepProofClassification.PARTIAL -> {
                    partialIds += intent.invoiceId
                }
                SweepProofClassification.FULL -> fullIds += intent.invoiceId
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
            val ordinaryPartialIds = partialIds - reviewIds.toSet()
            val ordinaryZeroIds = zeroIds - reviewIds.toSet()
            if (ordinaryPartialIds.isNotEmpty()) invoiceDao.markPartiallySettled(
                ordinaryPartialIds,
                transaction.txHash,
                receipt.blockNumber
            )
            if (ordinaryZeroIds.isNotEmpty()) {
                invoiceDao.releaseSettlement(ordinaryZeroIds, transaction.id)
            }
            if (reviewIds.isNotEmpty()) invoiceDao.markSettlementReviewRequired(
                reviewIds.distinct(),
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
            val provenLatePayment = invoice.status == InvoiceStatus.LATE_PAYMENT_READY &&
                (invoice.settledTxHash != null || invoice.settlementAmbiguous)
            require((firstSweep || provenLatePayment) && invoice.settlementId == null) {
                "Invoice ${invoice.invoiceId} is not eligible for settlement"
            }
        }
        return ordered
    }

    private suspend fun requirePreparedBalancesStillExact(prepared: PreparedSettlement) {
        val invoices = requireEligibleInvoices(prepared.invoiceIds)
        require(invoices.map(Invoice::settlementObservedAmount) == prepared.confirmedObservedAmounts) {
            "Confirmed invoice observations changed; review settlement again"
        }
        invoices.forEach { invoice ->
            require(invoice.chainId == prepared.chainId &&
                invoice.rpcUrl == prepared.rpcUrl &&
                invoice.networkName == prepared.networkName &&
                invoice.vaultAddress.equals(prepared.vaultAddress, true) &&
                invoice.token.equals(prepared.tokenAddress, true) &&
                invoice.tokenSymbol == prepared.tokenSymbol &&
                invoice.tokenDecimals == prepared.tokenDecimals &&
                invoice.confirmationBlocks == prepared.requiredConfirmations
            ) { "Historical invoice snapshot changed after settlement review" }
            requirePinnedHistoricalInvoiceSnapshot(invoice)
        }
        validateHistoricalSettlementSnapshot(invoices.first())
        clientFactory(prepared.rpcUrl).use { client ->
            require(client.chainId() == prepared.chainId) {
                "RPC chain changed before settlement signing"
            }
            requireOperatorAuthorization(client, prepared.vaultAddress, prepared.operatorAddress)
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(invoice, client::canonicalBlockHash)
                val live = client.tokenBalance(invoice.token, invoice.receiver)
                require(live == prepared.confirmedObservedAmounts[index]) {
                    "Receiver ${invoice.receiver} changed after review; refresh and wait for confirmation"
                }
            }
            client.simulate(prepared.operatorAddress, prepared.vaultAddress, prepared.callData)
        }
    }

    /**
     * Re-proves every security-sensitive historical snapshot field through the immutable shipped
     * RPC endpoint. The stored endpoint is used later only for operational reads/broadcast after
     * its chain ID and this provenance have independently passed.
     */
    private fun validateHistoricalSettlementSnapshot(invoice: Invoice) {
        val profile = KnownChainPolicy.requireProfile(invoice.chainId)
        profile.requireValidCreate2Fixture()
        val pinnedNetwork = requirePinnedHistoricalInvoiceSnapshot(invoice)
        val vault = EvmAddress.parse(invoice.vaultAddress)
        val token = EvmAddress.parse(invoice.token)

        val rpc = ReadOnlyRpcClient(
            pinnedNetwork,
            connectTimeoutMillis = HISTORICAL_RPC_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis = HISTORICAL_RPC_READ_TIMEOUT_MILLIS,
        )
        val validation = rpc.validate(token, invoice.tokenDecimals, invoice.tokenSymbol)
        val vaultRuntimeHash = Numeric.toHexString(Hash.sha3(rpc.codeAt(vault)))
        require(vaultRuntimeHash.equals(profile.vaultRuntimeCodeHash, true)) {
            "Historical vault runtime bytecode does not match the trusted chain pin"
        }
        require(validation.chainId == profile.chainId &&
            validation.factory == profile.factory &&
            validation.receiverImplementation == profile.receiverImplementation &&
            validation.vault == vault &&
            validation.token == token &&
            validation.tokenWhitelisted &&
            validation.tokenDecimals == invoice.tokenDecimals &&
            validation.tokenSymbol == invoice.tokenSymbol
        ) { "Historical settlement snapshot failed full network validation" }
    }

    private fun requireOperatorAuthorization(
        client: SettlementChainClient,
        vaultAddress: String,
        operatorAddress: String,
    ) {
        val ownerResult = runCatching { client.owner(vaultAddress) }
        val operatorResult = runCatching { client.isOperator(vaultAddress, operatorAddress) }
        val authorized = ownerResult.getOrNull()?.equals(operatorAddress, true) == true ||
            operatorResult.getOrNull() == true
        require(authorized) {
            val failures = listOfNotNull(
                ownerResult.exceptionOrNull()?.message,
                operatorResult.exceptionOrNull()?.message,
            ).joinToString("; ")
            if (failures.isBlank()) "Operator is not authorized by the historical vault"
            else "Unable to prove historical vault authorization: $failures"
        }
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

    private fun requireCurrentOperatorBinding(operatorAddress: String) {
        requireSettlementOperatorBinding(
            config = chainConfig.snapshot(),
            wallet = walletStore.snapshot(),
            operatorAddress = operatorAddress,
        )
    }

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

    private fun isKnownTransactionResponse(message: String?): Boolean =
        message.orEmpty().lowercase().let { value ->
            "already known" in value ||
                "known transaction" in value ||
                "already imported" in value ||
                "nonce too low" in value
        }

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
        private const val HISTORICAL_RPC_CONNECT_TIMEOUT_MILLIS = 2_500
        private const val HISTORICAL_RPC_READ_TIMEOUT_MILLIS = 4_000
    }
}

/**
 * Historical invoice snapshots and fresh on-chain authorization are the settlement authority.
 * Removing the final future-checkout profile must not strand already confirmed receiver funds.
 */
internal fun requireSettlementOperatorBinding(
    config: TerminalConfigSnapshot,
    wallet: OperatorWalletSnapshot,
    operatorAddress: String,
) {
    if (config.provisioned) {
        check(config.provisionedOperatorAddress?.equals(operatorAddress, true) == true) {
            "Provisioned operator does not match the settlement operator"
        }
    }
    check(
        wallet.availability == OperatorWalletAvailability.READY &&
            wallet.address?.equals(operatorAddress, true) == true,
    ) { "Local settlement wallet does not match the settlement operator" }
}

/**
 * New invoices retain the terminal EOA that derived their invoice ID. A blank value is accepted
 * only for pre-v6 Room rows, which still pass the existing local-wallet and fresh on-chain
 * authorization checks. Every available snapshot must identify the same current device EOA.
 */
internal fun requireInvoiceOperatorSnapshots(
    invoices: List<Invoice>,
    currentOperatorAddress: String,
) {
    val current = EvmAddress.parse(currentOperatorAddress).value
    val snapshotted = invoices.mapNotNull { invoice ->
        invoice.operatorAddress.takeIf(String::isNotBlank)?.let { EvmAddress.parse(it).value }
    }
    require(snapshotted.distinct().size <= 1) {
        "Batch invoices do not share the same operator snapshot"
    }
    require(snapshotted.all { it.equals(current, true) }) {
        "Invoice operator snapshot does not match the local settlement wallet"
    }
}

internal fun requireSettlementObservation(invoice: Invoice, previouslyProven: BigInteger) {
    require(previouslyProven.signum() >= 0) { "Prior swept evidence cannot be negative" }
    when (invoice.status) {
        InvoiceStatus.LATE_PAYMENT_READY -> {
            require(invoice.lateConfirmedAtBlock != null) {
                "Late payment for invoice ${invoice.invoiceId} is not confirmed"
            }
            require(previouslyProven.signum() > 0 || invoice.settlementAmbiguous) {
                "Late-payment invoice has no durable prior sweep evidence"
            }
            require(BigInteger(invoice.pendingLateAmount).signum() > 0) {
                "Late-payment invoice has no confirmed unswept value"
            }
        }

        InvoiceStatus.PAID,
        InvoiceStatus.OVERPAID -> {
            require(invoice.confirmedAtBlock != null) { "Invoice ${invoice.invoiceId} is not confirmed" }
            require(BigInteger(invoice.receivedAmount) >= BigInteger(invoice.expectedAmount)) {
                "Invoice ${invoice.invoiceId} is not fully funded"
            }
        }

        else -> error("Invoice ${invoice.invoiceId} is not eligible for settlement")
    }
}

internal fun requireCanonicalSettlementCursor(
    invoice: Invoice,
    canonicalBlockHash: (Long) -> String?,
) {
    val (block, savedHash) = if (invoice.status == InvoiceStatus.LATE_PAYMENT_READY) {
        invoice.lateFirstDetectedBlock to invoice.lateFirstDetectedBlockHash
    } else {
        invoice.firstDetectedBlock to invoice.firstDetectedBlockHash
    }
    val thresholdBlock = requireNotNull(block) {
        "Invoice ${invoice.invoiceId} has no confirmation threshold block"
    }
    val thresholdHash = requireNotNull(savedHash) {
        "Invoice ${invoice.invoiceId} has no canonical confirmation cursor; refresh confirmations"
    }
    val canonical = canonicalBlockHash(thresholdBlock)
    require(canonical?.equals(thresholdHash, ignoreCase = true) == true) {
        "Invoice ${invoice.invoiceId} confirmation threshold was reorganized; refresh confirmations"
    }
}

private fun Invoice.settlementObservedAmount(): BigInteger =
    if (status == InvoiceStatus.LATE_PAYMENT_READY) BigInteger(pendingLateAmount)
    else BigInteger(receivedAmount)

internal fun receiptMatchesCanonicalBlock(
    receipt: SettlementReceipt,
    canonicalBlockHash: String?,
): Boolean = canonicalBlockHash?.equals(receipt.blockHash, ignoreCase = true) == true

internal fun settlementConfirmationTarget(
    receiptBlock: Long,
    requiredConfirmations: Int,
): Long {
    require(receiptBlock >= 0) { "Receipt block cannot be negative" }
    val required = requiredConfirmations.coerceAtLeast(1)
    return Math.addExact(receiptBlock, required.toLong() - 1L)
}

internal fun settlementHasRequiredConfirmationDepth(
    receiptBlock: Long,
    requiredConfirmations: Int,
    canonicalHead: Long,
): Boolean = canonicalHead >= settlementConfirmationTarget(receiptBlock, requiredConfirmations)

internal fun shouldPersistSettlementReview(
    settlementAmbiguous: Boolean,
    classification: SweepProofClassification?,
): Boolean = classification == null ||
    (settlementAmbiguous && classification != SweepProofClassification.FULL)

internal fun requirePinnedHistoricalInvoiceSnapshot(invoice: Invoice): NetworkConfig {
    val profile = KnownChainPolicy.requireProfile(invoice.chainId)
    profile.requireValidCreate2Fixture()
    val factory = EvmAddress.parse(invoice.factoryAddress)
    val implementation = EvmAddress.parse(invoice.receiverImplementationAddress)
    val vault = EvmAddress.parse(invoice.vaultAddress)
    require(invoice.networkName == profile.networkName) {
        "Historical invoice network label does not match the trusted chain profile"
    }
    require(factory == profile.factory) { "Historical invoice factory does not match the chain pin" }
    require(implementation == profile.receiverImplementation) {
        "Historical invoice receiver implementation does not match the chain pin"
    }
    val pinnedNetwork = NetworkConfig(
        chainId = profile.chainId,
        rpcUrl = profile.rpcUrl,
        factory = profile.factory,
        receiverImplementation = profile.receiverImplementation,
        vault = vault,
    )
    require(
        pinnedNetwork.receiverResolver.resolve(vault, InvoiceId.parse(invoice.invoiceId)) ==
            EvmAddress.parse(invoice.receiver),
    ) { "Historical invoice receiver does not match its pinned CREATE2 derivation" }
    return pinnedNetwork
}
