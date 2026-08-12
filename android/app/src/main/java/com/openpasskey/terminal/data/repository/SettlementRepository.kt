package com.openpasskey.terminal.data.repository

import android.os.SystemClock
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
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import com.openpasskey.terminal.rpc.RpcInteractiveReservation
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
import com.openpasskey.terminal.settlement.SettlementPreflightRequest
import com.openpasskey.terminal.settlement.SettlementReceiverSafetyRead
import com.openpasskey.terminal.settlement.SettlementReceipt
import com.openpasskey.terminal.settlement.SettlementRpcException
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
    /** Credential-neutral process revision of the endpoint that produced this proof. */
    val rpcEndpointGeneration: Long,
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
    val requiredConfirmations: Int,
    val confirmedRequiredBalance: BigInteger,
    val historicalProofFingerprint: String,
    val historicalProofAtElapsedRealtimeMillis: Long,
    /** Original issuance time of gasLimit; never refreshed when that estimate is reused. */
    val gasEstimateAtElapsedRealtimeMillis: Long,
    val preparedAtElapsedRealtimeMillis: Long,
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

/** Narrow repository boundary that keeps wallet key use replaceable in local regression tests. */
internal interface SettlementWalletAccess {
    fun snapshot(): OperatorWalletSnapshot

    fun activateAndSignSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
    ): ByteArray

    fun activateAndSignAutomaticSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
        invoiceIds: List<String>,
        expectedAmounts: List<BigInteger>,
        tokenAddress: String,
        maximumGasCost: BigInteger,
        requiredBalance: BigInteger,
    ): ByteArray = error("Unattended auto-sweep signing is unavailable")
}

private class StoredSettlementWalletAccess(
    private val walletStore: OperatorWalletStore,
) : SettlementWalletAccess {
    override fun snapshot(): OperatorWalletSnapshot = walletStore.snapshot()

    override fun activateAndSignSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
    ): ByteArray = walletStore.activateAndSignSettlementTransaction(
        transaction = transaction,
        chainId = chainId,
        vaultAddress = vaultAddress,
        operatorAddress = operatorAddress,
        eip1559 = eip1559,
    )

    override fun activateAndSignAutomaticSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
        invoiceIds: List<String>,
        expectedAmounts: List<BigInteger>,
        tokenAddress: String,
        maximumGasCost: BigInteger,
        requiredBalance: BigInteger,
    ): ByteArray = walletStore.activateAndSignAutomaticSettlementTransaction(
        transaction = transaction,
        chainId = chainId,
        vaultAddress = vaultAddress,
        operatorAddress = operatorAddress,
        eip1559 = eip1559,
        invoiceIds = invoiceIds,
        expectedAmounts = expectedAmounts,
        tokenAddress = tokenAddress,
        maximumGasCost = maximumGasCost,
        requiredBalance = requiredBalance,
    )
}

/**
 * App-layer write path. The reusable ERC-681 SDK remains read-only. A single process-wide mutex,
 * pending nonces, and durable pre-broadcast records prevent concurrent nonce reuse.
 */
class SettlementRepository internal constructor(
    private val database: InvoiceDatabase,
    private val walletAccess: SettlementWalletAccess,
    private val chainConfigSnapshot: () -> TerminalConfigSnapshot,
    private val lifecycleGate: TerminalLifecycleGate,
    private val rpcWorkCoordinator: RpcWorkCoordinator = RpcWorkCoordinator(),
    private val rpcEndpointResolver: RpcEndpointResolver = RpcEndpointResolver.PASSTHROUGH,
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
    private val gson: Gson = Gson(),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val historicalSnapshotValidationOverride: ((Invoice, String) -> Unit)? = null,
) {
    constructor(
        database: InvoiceDatabase,
        walletStore: OperatorWalletStore,
        chainConfig: ChainConfig,
        lifecycleGate: TerminalLifecycleGate,
        rpcWorkCoordinator: RpcWorkCoordinator = RpcWorkCoordinator(),
        rpcEndpointResolver: RpcEndpointResolver = RpcEndpointResolver.PASSTHROUGH,
        clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
        gson: Gson = Gson(),
        elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    ) : this(
        database = database,
        walletAccess = StoredSettlementWalletAccess(walletStore),
        chainConfigSnapshot = chainConfig::snapshot,
        lifecycleGate = lifecycleGate,
        rpcWorkCoordinator = rpcWorkCoordinator,
        rpcEndpointResolver = rpcEndpointResolver,
        clientFactory = clientFactory,
        gson = gson,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private val invoiceDao = database.invoiceDao()
    private val settlementDao = database.settlementDao()
    private val eventDao = database.settlementEventDao()

    fun observeReadyInvoices(): Flow<List<Invoice>> = invoiceDao.observeReadyForSettlement()
    fun observeRecentTransactions(limit: Int = 50): Flow<List<SettlementTransaction>> =
        settlementDao.observeRecent(limit)

    /** Reserves background priority from pre-auth revalidation through prompt completion. */
    fun reserveAuthenticationWindow(): RpcInteractiveReservation =
        rpcWorkCoordinator.reserveInteractiveWindow()

    suspend fun prepare(invoiceIds: List<String>): PreparedSettlement = withContext(Dispatchers.IO) {
        rpcWorkCoordinator.withInteractiveOperation { prepareInternal(invoiceIds) }
    }

    /** Completes slow live revalidation before the UI opens the system authentication prompt. */
    suspend fun prepareForAuthentication(reviewed: PreparedSettlement): PreparedSettlement =
        withContext(Dispatchers.IO) {
            rpcWorkCoordinator.withInteractiveOperation {
                submissionMutex.withLock {
                    val fresh = prepareInternal(
                        invoiceIds = reviewed.invoiceIds,
                        reusableHistoricalProof = reviewed,
                        reusableGasEstimate = reviewed,
                    )
                    requireSameReviewedSettlement(reviewed, fresh)
                    require(
                        !SettlementFeePolicy.exceedsConfirmedCost(
                            reviewed.confirmedRequiredBalance,
                            fresh.requiredBalance,
                        ),
                    ) { "Network fees increased by more than 20%; review the new maximum before signing" }
                    requireNoActiveOperatorTransaction(fresh)
                    fresh.copy(confirmedRequiredBalance = reviewed.confirmedRequiredBalance)
                }
            }
        }

    /** Must only be invoked after a fresh system biometric/device-credential prompt. */
    suspend fun submit(
        reviewed: PreparedSettlement,
        userExplicitlyConfirmed: Boolean,
        authenticatedAtElapsedRealtimeMillis: Long,
    ): SettlementTransaction = withContext(Dispatchers.IO) {
        require(userExplicitlyConfirmed) { "Settlement requires explicit operator confirmation" }
        rpcWorkCoordinator.withInteractiveOperation {
            requireFreshAuthenticatedPreparedSettlement(
                prepared = reviewed,
                authenticatedAtElapsedRealtimeMillis = authenticatedAtElapsedRealtimeMillis,
                nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            )
            submissionMutex.withLock {
                requireCurrentRpcEndpointGeneration(reviewed)
                requireNoActiveOperatorTransaction(reviewed)
                // Post-authentication work is intentionally only the live, mutable safety core.
                // The pre-prompt proof supplied provenance and fees moments ago.
                val fresh = requirePreparedBalancesStillExact(reviewed)
                requireFreshAuthenticatedPreparedSettlement(
                    prepared = fresh,
                    authenticatedAtElapsedRealtimeMillis = authenticatedAtElapsedRealtimeMillis,
                    nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
                )
                signPersistAndBroadcast(
                    fresh = fresh,
                    authenticatedAtElapsedRealtimeMillis = authenticatedAtElapsedRealtimeMillis,
                    automatic = false,
                )
            }
        }
    }

    /**
     * Full unattended path used only after one-time administrator enrollment. It repeats every
     * live canonical/configuration/fee check, persists SIGNED bytes before the sole broadcast,
     * and never weakens the separate manual authentication path above.
     *
     * Unlike the manual path there is no authentication prompt between revalidation and signing:
     * the submission mutex is held from the [prepareInternal] preflight through key use, so that
     * one preflight is the live safety core and a second identical pass would re-prove state that
     * cannot have changed.
     */
    suspend fun submitAutomatically(reviewed: PreparedSettlement): SettlementTransaction =
        withContext(Dispatchers.IO) {
            rpcWorkCoordinator.withInteractiveOperation {
                submissionMutex.withLock {
                    requireCurrentRpcEndpointGeneration(reviewed)
                    requireNoActiveOperatorTransaction(reviewed)
                    val revalidated = prepareInternal(
                        invoiceIds = reviewed.invoiceIds,
                        reusableHistoricalProof = reviewed,
                        reusableGasEstimate = reviewed,
                    )
                    requireSameReviewedSettlement(reviewed, revalidated)
                    require(
                        !SettlementFeePolicy.exceedsConfirmedCost(
                            reviewed.requiredBalance,
                            revalidated.requiredBalance,
                        ),
                    ) { "Automatic settlement fee increased by more than 20%; retrying later" }
                    requireNoActiveOperatorTransaction(revalidated)
                    signPersistAndBroadcast(
                        fresh = revalidated,
                        authenticatedAtElapsedRealtimeMillis = null,
                        automatic = true,
                    )
                }
            }
        }

    private suspend fun signPersistAndBroadcast(
        fresh: PreparedSettlement,
        authenticatedAtElapsedRealtimeMillis: Long?,
        automatic: Boolean,
    ): SettlementTransaction {
        val raw = when (fresh.feeQuote.mode) {
            SettlementFeeMode.LEGACY -> RawTransaction.createTransaction(
                fresh.nonce,
                requireNotNull(fresh.feeQuote.gasPrice),
                fresh.gasLimit,
                fresh.vaultAddress,
                BigInteger.ZERO,
                fresh.callData,
            )
            SettlementFeeMode.EIP1559 -> RawTransaction.createTransaction(
                fresh.chainId,
                fresh.nonce,
                fresh.gasLimit,
                fresh.vaultAddress,
                BigInteger.ZERO,
                fresh.callData,
                requireNotNull(fresh.feeQuote.maxPriorityFeePerGas),
                requireNotNull(fresh.feeQuote.maxFeePerGas),
            )
        }
        val transaction = lifecycleGate.withExclusiveMutation {
            requireCurrentOperatorBinding(fresh.operatorAddress)
            val invoices = requireEligibleInvoices(fresh.invoiceIds)
            require(invoices.map(Invoice::settlementObservedAmount) == fresh.confirmedObservedAmounts) {
                "Confirmed invoice observations changed immediately before signing"
            }
            if (!automatic) {
                requireFreshAuthenticatedPreparedSettlement(
                    prepared = fresh,
                    authenticatedAtElapsedRealtimeMillis = requireNotNull(
                        authenticatedAtElapsedRealtimeMillis,
                    ),
                    nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
                )
            } else {
                requireFreshPreparedSettlementProof(fresh, elapsedRealtimeMillis())
            }
            requireCurrentRpcEndpointGeneration(fresh)
            val expectedAmounts = invoices.map { BigInteger(it.expectedAmount) }
            val signedBytes = if (automatic) {
                walletAccess.activateAndSignAutomaticSettlementTransaction(
                    transaction = raw,
                    chainId = fresh.chainId,
                    vaultAddress = fresh.vaultAddress,
                    operatorAddress = fresh.operatorAddress,
                    eip1559 = fresh.feeQuote.mode == SettlementFeeMode.EIP1559,
                    invoiceIds = invoices.map(Invoice::invoiceId),
                    expectedAmounts = expectedAmounts,
                    tokenAddress = fresh.tokenAddress,
                    maximumGasCost = fresh.maximumGasCost,
                    requiredBalance = fresh.requiredBalance,
                )
            } else {
                walletAccess.activateAndSignSettlementTransaction(
                    transaction = raw,
                    chainId = fresh.chainId,
                    vaultAddress = fresh.vaultAddress,
                    operatorAddress = fresh.operatorAddress,
                    eip1559 = fresh.feeQuote.mode == SettlementFeeMode.EIP1559,
                )
            }
            val signedHex = Numeric.toHexString(signedBytes)
            signedBytes.fill(0)
            val localHash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(signedHex)))
            val now = nowSeconds()
            val persisted = SettlementTransaction(
                id = UUID.randomUUID().toString(), chainId = fresh.chainId,
                networkName = fresh.networkName, rpcUrl = fresh.rpcUrl,
                vaultAddress = fresh.vaultAddress, tokenAddress = fresh.tokenAddress,
                tokenSymbol = fresh.tokenSymbol, operatorAddress = fresh.operatorAddress,
                invoiceIdsJson = gson.toJson(invoices.map(Invoice::invoiceId)),
                expectedAmountsJson = gson.toJson(invoices.map(Invoice::expectedAmount)),
                receiverAddressesJson = gson.toJson(invoices.map(Invoice::receiver)),
                requiredConfirmations = fresh.requiredConfirmations, callData = fresh.callData,
                nonce = fresh.nonce.toString(), gasLimit = fresh.gasLimit.toString(),
                feeMode = fresh.feeQuote.mode, gasPrice = fresh.feeQuote.gasPrice?.toString(),
                maxPriorityFeePerGas = fresh.feeQuote.maxPriorityFeePerGas?.toString(),
                maxFeePerGas = fresh.feeQuote.maxFeePerGas?.toString(),
                maxGasCostWei = fresh.maximumGasCost.toString(),
                feeReserveWei = fresh.safetyReserve.toString(),
                requiredBalanceWei = fresh.requiredBalance.toString(), txHash = localHash,
                signedRawTransaction = signedHex, status = SettlementTransactionStatus.SIGNED,
                createdAt = now, updatedAt = now,
            )
            database.withTransaction {
                settlementDao.insert(persisted)
                check(invoiceDao.attachSettlement(fresh.invoiceIds, persisted.id) == fresh.invoiceIds.size) {
                    "One or more invoices became unavailable before signing was persisted"
                }
            }
            persisted
        }
        return broadcastSignedTransaction(transaction.id)
    }

    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        if (rpcWorkCoordinator.interactive) return@withContext
        settlementDao.getByStatuses(ACTIVE_STATUSES).forEach { transaction ->
            // Lock ordering matches every interactive settlement path: endpoint budget first,
            // then nonce/submission serialization. Each automatic record is non-polling.
            val completed = rpcWorkCoordinator.withBackgroundOperation {
                try {
                    submissionMutex.withLock {
                        recoverOneBackgroundStep(transaction.id)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordRecoverableError(transaction, error)
                }
            }
            if (completed == null) return@withContext
        }
    }

    suspend fun refreshTransaction(id: String): SettlementTransaction = withContext(Dispatchers.IO) {
        rpcWorkCoordinator.withInteractiveOperation {
            submissionMutex.withLock { broadcastAndRecover(id) }
        }
    }

    /**
     * Drives one transaction's durable recovery to a terminal state on a fast bounded cadence.
     * Each step is the same cooperative background unit as [recoverPending] — endpoint budget
     * first, then submission serialization — so cashier work always wins and no lock is held
     * across a poll delay. Returns the latest durable state once the transaction leaves
     * [ACTIVE_STATUSES] or the attempt budget is exhausted; the scheduled recovery loop remains
     * the fallback for anything still active afterwards.
     */
    suspend fun driveTransactionRecovery(
        id: String,
        maxAttempts: Int = FAST_RECOVERY_MAX_ATTEMPTS,
        intervalMillis: Long = FAST_RECOVERY_INTERVAL_MILLIS,
    ): SettlementTransaction? = withContext(Dispatchers.IO) {
        require(maxAttempts > 0) { "Recovery attempt budget must be positive" }
        require(intervalMillis > 0) { "Recovery interval must be positive" }
        var latest = settlementDao.getById(id) ?: return@withContext null
        repeat(maxAttempts) { attempt ->
            if (latest.status !in ACTIVE_STATUSES) return@withContext latest
            val stepped = rpcWorkCoordinator.withBackgroundOperation {
                try {
                    submissionMutex.withLock { recoverOneBackgroundStep(id) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordRecoverableError(latest, error)
                    settlementDao.getById(id) ?: latest
                }
            }
            if (stepped != null) {
                latest = stepped
                if (latest.status !in ACTIVE_STATUSES) return@withContext latest
            }
            if (attempt < maxAttempts - 1) delay(intervalMillis)
        }
        latest
    }

    private suspend fun prepareInternal(
        invoiceIds: List<String>,
        reusableHistoricalProof: PreparedSettlement? = null,
        reusableGasEstimate: PreparedSettlement? = null,
    ): PreparedSettlement {
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
        // One resolution binds historical validation, live preflight, and all reusable evidence
        // in this prepare. The credential-bearing endpoint never enters PreparedSettlement.
        val endpointResolution = rpcEndpointResolver.resolveCurrent(first.chainId, first.rpcUrl)
        val proofFingerprint = historicalSettlementProofFingerprint(first)
        val proofNow = elapsedRealtimeMillis()
        val reusableProofIsFresh = reusableHistoricalProof?.let { proof ->
            proof.rpcEndpointGeneration == endpointResolution.generation &&
                proof.historicalProofFingerprint == proofFingerprint &&
                isElapsedProofFresh(
                    proof.historicalProofAtElapsedRealtimeMillis,
                    proofNow,
                    HISTORICAL_PROOF_TTL_MILLIS,
                )
        } == true
        val historicalProofAt = if (reusableProofIsFresh) {
            requireNotNull(reusableHistoricalProof).historicalProofAtElapsedRealtimeMillis
        } else {
            historicalSnapshotValidationOverride?.invoke(first, endpointResolution.endpoint)
                ?: validateHistoricalSettlementSnapshot(first, endpointResolution.endpoint)
            proofNow
        }
        val wallet = walletAccess.snapshot()
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

        clientFactory(endpointResolution.endpoint).use { client ->
            val cursors = invoices.map(Invoice::settlementConfirmationCursor)
            val request = SettlementPreflightRequest(
                operatorAddress = operator,
                vaultAddress = first.vaultAddress,
                callData = callData,
                receivers = invoices.zip(cursors).map { (invoice, cursor) ->
                    SettlementReceiverSafetyRead(
                        tokenAddress = invoice.token,
                        receiverAddress = invoice.receiver,
                        canonicalBlockNumber = cursor.first,
                    )
                },
            )
            val canReuseGasEstimate = reusableGasEstimate.canReuseGasEstimateFor(
                chainId = first.chainId,
                rpcUrl = first.rpcUrl,
                rpcEndpointGeneration = endpointResolution.generation,
                vaultAddress = first.vaultAddress,
                tokenAddress = first.token,
                operatorAddress = operator,
                invoiceIds = invoices.map(Invoice::invoiceId),
                confirmedObservedAmounts = confirmedObservedAmounts,
                callData = callData,
                // Historical revalidation can itself take several seconds; check the gas-proof
                // TTL at the actual reuse decision rather than at the start of prepare().
                nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            )
            val live = client.settlementPreflight(
                request = request,
                includeGasEstimate = !canReuseGasEstimate,
            )
            require(live.chainId == first.chainId) {
                "RPC chain ID does not match the invoice snapshot"
            }
            requireLiveOperatorAuthorization(live.ownerAddress, live.operatorListed, operator)
            require(live.canonicalBlockHashes.size == invoices.size &&
                live.canonicalBlockHashesAfter.size == invoices.size &&
                live.receiverBalances.size == invoices.size
            ) { "Settlement safety batch returned incomplete receiver results" }
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(
                    invoice,
                    live.canonicalBlockHashes[index],
                )
                val liveBalance = live.receiverBalances[index]
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
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(invoice, live.canonicalBlockHashesAfter[index])
            }
            val nonce = live.nonce
            val reusableGasStillFresh = canReuseGasEstimate && isElapsedProofFresh(
                requireNotNull(reusableGasEstimate).gasEstimateAtElapsedRealtimeMillis,
                elapsedRealtimeMillis(),
                PREPARED_PROOF_TTL_MILLIS,
            )
            val gasLimit = if (reusableGasStillFresh) {
                val reusable = requireNotNull(reusableGasEstimate)
                require(nonce == reusable.nonce) {
                    "Operator nonce changed after settlement review; review it again"
                }
                reusable.gasLimit
            } else if (canReuseGasEstimate) {
                // The three-wave live proof crossed the TTL boundary. Refresh only the dependent
                // estimate instead of returning a newly timestamped wrapper around stale gas.
                client.estimateGas(operator, first.vaultAddress, callData, nonce)
            } else {
                requireNotNull(live.gasLimit) { "Settlement gas estimate is unavailable" }
            }
            val gasEstimateAt = if (reusableGasStillFresh) {
                requireNotNull(reusableGasEstimate).gasEstimateAtElapsedRealtimeMillis
            } else {
                elapsedRealtimeMillis()
            }
            val quote = live.feeQuote
            val requirement = SettlementBalancePolicy.requirement(gasLimit, quote)
            val balance = live.nativeBalance
            require(balance >= requirement.requiredBalance) {
                "Low gas balance: requires ${requirement.requiredBalance} wei including reserve; has $balance wei"
            }
            check(rpcEndpointResolver.isCurrent(endpointResolution)) {
                "RPC endpoint changed during settlement review; review it again"
            }
            return PreparedSettlement(
                invoiceIds = invoices.map(Invoice::invoiceId),
                chainId = first.chainId,
                networkName = first.networkName,
                rpcUrl = first.rpcUrl,
                rpcEndpointGeneration = endpointResolution.generation,
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
                requiredConfirmations = invoices.maxOf { it.confirmationBlocks.coerceAtLeast(1) },
                confirmedRequiredBalance = reusableGasEstimate?.confirmedRequiredBalance
                    ?: requirement.requiredBalance,
                historicalProofFingerprint = proofFingerprint,
                historicalProofAtElapsedRealtimeMillis = historicalProofAt,
                gasEstimateAtElapsedRealtimeMillis = gasEstimateAt,
                preparedAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            )
        }
    }

    private suspend fun broadcastSignedTransaction(id: String): SettlementTransaction {
        var transaction = requireNotNull(settlementDao.getById(id)) {
            "Settlement transaction not found"
        }
        require(transaction.status == SettlementTransactionStatus.SIGNED) {
            "Fresh settlement transaction is not ready for broadcast"
        }
        val raw = requireNotNull(transaction.signedRawTransaction) {
            "Signed transaction bytes are unavailable"
        }
        clientFor(transaction.chainId, transaction.rpcUrl).use { client ->
            val outcome = broadcastFreshSignedTransaction(client, raw, transaction.txHash)
            transaction = transaction.copy(
                status = if (outcome.accepted) SettlementTransactionStatus.SUBMITTED
                else SettlementTransactionStatus.SIGNED,
                error = outcome.error,
                updatedAt = nowSeconds(),
            )
            settlementDao.update(transaction)
            return transaction
        }
    }

    /**
     * Advances one durable recovery state without polling. Most states require one transport
     * call; a receipt that appears final in the bounded snapshot requires one ordered head read
     * before terminalization. Each call retains the shared OkHttp deadline, and background work
     * uses a separate coordinator queue so it cannot hold the cashier's interactive queue.
     */
    private suspend fun recoverOneBackgroundStep(id: String): SettlementTransaction {
        var transaction = requireNotNull(settlementDao.getById(id)) {
            "Settlement transaction not found"
        }
        if (transaction.status !in ACTIVE_STATUSES) return transaction
        clientFor(transaction.chainId, transaction.rpcUrl).use { client ->
            when (transaction.status) {
                SettlementTransactionStatus.SIGNED -> {
                    val raw = requireNotNull(transaction.signedRawTransaction) {
                        "Signed transaction bytes are unavailable"
                    }
                    val outcome = broadcastFreshSignedTransaction(client, raw, transaction.txHash)
                    transaction = transaction.copy(
                        status = if (outcome.accepted) SettlementTransactionStatus.SUBMITTED
                        else SettlementTransactionStatus.SIGNED,
                        error = outcome.error,
                        updatedAt = nowSeconds(),
                    )
                    settlementDao.update(transaction)
                    return transaction
                }
                SettlementTransactionStatus.SUBMITTED -> {
                    return observeBackgroundReceiptOnce(transaction, client)
                }
                SettlementTransactionStatus.CONFIRMING -> {
                    val expectedBlock = transaction.receiptBlock
                        ?: return observeBackgroundReceiptOnce(transaction, client)
                    val snapshot = client.settlementRecoverySnapshot(
                        transaction.txHash,
                        expectedBlock,
                    )
                    val receipt = snapshot.receipt
                    if (receipt == null ||
                        !receipt.transactionHash.equals(transaction.txHash, ignoreCase = true) ||
                        receipt.blockNumber != expectedBlock
                    ) {
                        transaction = transaction.copy(
                            receiptBlock = null,
                            error = "Receipt identity changed during confirmation; waiting for " +
                                "canonical inclusion",
                            updatedAt = nowSeconds(),
                        )
                        settlementDao.update(transaction)
                        return transaction
                    }
                    val target = settlementConfirmationTarget(
                        receipt.blockNumber,
                        transaction.requiredConfirmations,
                    )
                    if (snapshot.latestBlockNumber < target) {
                        transaction = transaction.copy(
                            error = "Waiting for the canonical head to reach block $target",
                            updatedAt = nowSeconds(),
                        )
                        settlementDao.update(transaction)
                        return transaction
                    }
                    if (!receiptMatchesCanonicalBlock(
                            receipt,
                            snapshot.canonicalReceiptBlockHash,
                        )
                    ) {
                        transaction = transaction.copy(
                            receiptBlock = null,
                            error = "Receipt block is missing or orphaned; waiting for canonical " +
                                "inclusion",
                            updatedAt = nowSeconds(),
                        )
                        settlementDao.update(transaction)
                        return transaction
                    }
                    // A JSON-RPC batch is not an atomic snapshot. The head response in the batch
                    // can be produced before the receipt and canonical-hash reads by a different
                    // backend. Re-read the head after those identity checks and require finality
                    // to still hold before performing an irreversible terminal transition.
                    val finalHeadResult = try {
                        Result.success(client.blockNumber())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    val finalHead = finalHeadResult.getOrNull()
                    if (finalHead == null || finalHead < target) {
                        transaction = transaction.copy(
                            status = SettlementTransactionStatus.CONFIRMING,
                            receiptBlock = receipt.blockNumber,
                            error = finalHeadResult.exceptionOrNull()?.let { error ->
                                "Unable to recheck final confirmation depth: " +
                                    safeSettlementRpcError(error, "RPC read failed")
                            } ?: "Confirmation depth changed during finality verification; " +
                                "waiting for the canonical head to reach block $target",
                            updatedAt = nowSeconds(),
                        )
                        settlementDao.update(transaction)
                        return transaction
                    }
                    return if (receipt.successful) {
                        verifyAndFinalize(transaction, receipt)
                    } else {
                        finalizeReverted(transaction, receipt)
                    }
                }
                else -> return transaction
            }
        }
    }

    private suspend fun observeBackgroundReceiptOnce(
        transaction: SettlementTransaction,
        client: SettlementChainClient,
    ): SettlementTransaction {
        val receipt = client.transactionReceipt(transaction.txHash)
        val updated = if (receipt == null) {
            transaction.copy(
                error = "Transaction is pending; recovery will continue automatically",
                updatedAt = nowSeconds(),
            )
        } else if (!receipt.transactionHash.equals(transaction.txHash, ignoreCase = true)) {
            transaction.copy(
                status = SettlementTransactionStatus.CONFIRMING,
                receiptBlock = null,
                error = "RPC returned a receipt for a different transaction",
                updatedAt = nowSeconds(),
            )
        } else {
            transaction.copy(
                status = SettlementTransactionStatus.CONFIRMING,
                receiptBlock = receipt.blockNumber,
                error = null,
                updatedAt = nowSeconds(),
            )
        }
        settlementDao.update(updated)
        return updated
    }

    private suspend fun broadcastAndRecover(
        id: String,
        pollForProgress: Boolean = true,
    ): SettlementTransaction {
        var transaction = requireNotNull(settlementDao.getById(id)) { "Settlement transaction not found" }
        if (transaction.status !in ACTIVE_STATUSES) return transaction
        clientFor(transaction.chainId, transaction.rpcUrl).use { client ->
            require(client.chainId() == transaction.chainId) { "RPC chain changed during recovery" }
            var receipt: SettlementReceipt? = null
            var broadcastAttemptedThisPass = false
            if (transaction.status == SettlementTransactionStatus.SIGNED) {
                val raw = requireNotNull(transaction.signedRawTransaction) {
                    "Signed transaction bytes are unavailable"
                }
                receipt = client.transactionReceipt(transaction.txHash)
                if (receipt == null) {
                    try {
                        broadcastAttemptedThisPass = true
                        val returnedHash = client.sendRawTransaction(raw)
                        require(returnedHash.equals(transaction.txHash, true)) {
                            "RPC returned a different transaction hash"
                        }
                    } catch (error: Exception) {
                        if (!pollForProgress) {
                            transaction = transaction.copy(
                                status = SettlementTransactionStatus.SIGNED,
                                error = safeSettlementRpcError(error, "Broadcast result unknown"),
                                updatedAt = nowSeconds(),
                            )
                            settlementDao.update(transaction)
                            return transaction
                        }
                        // A response can be lost after acceptance. Always query the deterministic
                        // local hash, and treat an exact "already known" response as submitted.
                        receipt = client.transactionReceipt(transaction.txHash)
                        val alreadyKnown = isKnownTransactionResponse(error)
                        if (receipt == null && !alreadyKnown) {
                            transaction = transaction.copy(
                                status = SettlementTransactionStatus.SIGNED,
                                error = safeSettlementRpcError(error, "Broadcast result unknown"),
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

            var receiptAttempt = 0
            val receiptPollAttempts = if (pollForProgress) RECEIPT_POLL_ATTEMPTS else 1
            while (receipt == null && receiptAttempt < receiptPollAttempts) {
                receipt = client.transactionReceipt(transaction.txHash)
                receiptAttempt += 1
                if (receipt == null && receiptAttempt < receiptPollAttempts) {
                    delay(RECEIPT_POLL_MILLIS)
                }
            }
            val firstReceipt = receipt ?: run {
                var rebroadcastError: String? = null
                val retainedRaw = transaction.signedRawTransaction
                if (retainedRaw != null && (pollForProgress || !broadcastAttemptedThisPass)) {
                    broadcastAttemptedThisPass = true
                    try {
                        val returnedHash = client.sendRawTransaction(retainedRaw)
                        require(returnedHash.equals(transaction.txHash, true)) {
                            "RPC returned a different transaction hash while rebroadcasting"
                        }
                    } catch (error: Exception) {
                        if (!isKnownTransactionResponse(error)) {
                            rebroadcastError = safeSettlementRpcError(
                                error,
                                "Identical transaction rebroadcast failed",
                            )
                        }
                    }
                } else if (retainedRaw == null) {
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
            val confirmationPollAttempts = if (pollForProgress) CONFIRMATION_POLL_ATTEMPTS else 0
            while (latestBlock < target && confirmationAttempt < confirmationPollAttempts) {
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
                        "Unable to verify the canonical receipt block: " +
                            safeSettlementRpcError(error, "RPC read failed")
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
                                safeSettlementRpcError(error, "RPC read failed")
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
            error = safeSettlementRpcError(error, "Settlement recovery failed"),
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

    private suspend fun requirePreparedBalancesStillExact(
        prepared: PreparedSettlement,
    ): PreparedSettlement {
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
        require(
            historicalSettlementProofFingerprint(invoices.first()) ==
                prepared.historicalProofFingerprint,
        ) { "Historical settlement proof no longer matches the invoice snapshot" }
        val endpointResolution = rpcEndpointResolver.resolveCurrent(
            prepared.chainId,
            prepared.rpcUrl,
        )
        check(endpointResolution.generation == prepared.rpcEndpointGeneration) {
            "RPC endpoint changed after settlement review; review it again"
        }
        clientFactory(endpointResolution.endpoint).use { client ->
            val cursors = invoices.map(Invoice::settlementConfirmationCursor)
            val live = client.settlementPreflight(
                request = SettlementPreflightRequest(
                    operatorAddress = prepared.operatorAddress,
                    vaultAddress = prepared.vaultAddress,
                    callData = prepared.callData,
                    receivers = invoices.zip(cursors).map { (invoice, cursor) ->
                        SettlementReceiverSafetyRead(
                            tokenAddress = invoice.token,
                            receiverAddress = invoice.receiver,
                            canonicalBlockNumber = cursor.first,
                        )
                    },
                ),
                includeGasEstimate = false,
            )
            require(live.chainId == prepared.chainId) {
                "RPC chain changed before settlement signing"
            }
            requireLiveOperatorAuthorization(
                ownerAddress = live.ownerAddress,
                operatorListed = live.operatorListed,
                operatorAddress = prepared.operatorAddress,
            )
            require(live.nonce == prepared.nonce) {
                "Operator nonce changed after authentication; review settlement again"
            }
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(invoice, live.canonicalBlockHashes[index])
                require(live.receiverBalances[index] == prepared.confirmedObservedAmounts[index]) {
                    "Receiver ${invoice.receiver} changed after review; refresh and wait for confirmation"
                }
            }
            invoices.forEachIndexed { index, invoice ->
                requireCanonicalSettlementCursor(invoice, live.canonicalBlockHashesAfter[index])
            }
            val requirement = SettlementBalancePolicy.requirement(prepared.gasLimit, live.feeQuote)
            require(
                !SettlementFeePolicy.exceedsConfirmedCost(
                    prepared.confirmedRequiredBalance,
                    requirement.requiredBalance,
                ),
            ) { "Network fees increased by more than 20%; review the new maximum before signing" }
            require(live.nativeBalance >= requirement.requiredBalance) {
                "Low gas balance: requires ${requirement.requiredBalance} wei including reserve; " +
                    "has ${live.nativeBalance} wei"
            }
            check(rpcEndpointResolver.isCurrent(endpointResolution)) {
                "RPC endpoint changed during settlement signing checks; review it again"
            }
            return prepared.copy(
                feeQuote = live.feeQuote,
                maximumGasCost = requirement.maximumGasCost,
                safetyReserve = requirement.safetyReserve,
                requiredBalance = requirement.requiredBalance,
                currentBalance = live.nativeBalance,
            )
        }
    }

    /**
     * Re-proves every security-sensitive historical snapshot field through the currently approved
     * per-chain endpoint while retaining immutable shipped deployment pins. Credential-bearing
     * endpoint material is resolved only for this client and never copied into historical rows.
     */
    private fun validateHistoricalSettlementSnapshot(
        invoice: Invoice,
        resolvedRpcUrl: String,
    ) {
        val profile = KnownChainPolicy.requireProfile(invoice.chainId)
        profile.requireValidCreate2Fixture()
        val pinnedNetwork = requirePinnedHistoricalInvoiceSnapshot(invoice)
        val vault = EvmAddress.parse(invoice.vaultAddress)
        val token = EvmAddress.parse(invoice.token)

        val rpc = ReadOnlyRpcClient(
            pinnedNetwork.copy(
                rpcUrl = resolvedRpcUrl,
            ),
            connectTimeoutMillis = HISTORICAL_RPC_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis = HISTORICAL_RPC_READ_TIMEOUT_MILLIS,
        )
        val evidence = rpc.validateWithEvidence(token, invoice.tokenDecimals, invoice.tokenSymbol)
        val validation = evidence.validation
        val vaultRuntimeHash = Numeric.toHexString(Hash.sha3(evidence.vaultRuntimeCode))
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

    private fun requireLiveOperatorAuthorization(
        ownerAddress: String?,
        operatorListed: Boolean,
        operatorAddress: String,
    ) {
        require(operatorListed || ownerAddress?.equals(operatorAddress, true) == true) {
            "Operator is not authorized by the historical vault"
        }
    }

    /** Compares only the process-local revision. The resolved credential is discarded immediately. */
    private fun requireCurrentRpcEndpointGeneration(prepared: PreparedSettlement) {
        val current = rpcEndpointResolver.resolveCurrent(prepared.chainId, prepared.rpcUrl)
        check(
            current.generation == prepared.rpcEndpointGeneration &&
                rpcEndpointResolver.isCurrent(current),
        ) { "RPC endpoint changed after settlement review; review it again" }
    }

    /** Resolves credential-bearing material only for the lifetime of this client construction. */
    private fun clientFor(chainId: Long, fallbackRpcUrl: String): SettlementChainClient =
        clientFactory(rpcEndpointResolver.resolve(chainId, fallbackRpcUrl))

    private suspend fun requireNoActiveOperatorTransaction(prepared: PreparedSettlement) {
        val active = settlementDao.getActiveForOperator(
            prepared.chainId,
            prepared.operatorAddress,
            ACTIVE_STATUSES,
        )
        require(active.isEmpty()) {
            "Another operator transaction is pending; recover it before assigning a new nonce"
        }
    }

    private fun requireSameReviewedSettlement(
        reviewed: PreparedSettlement,
        fresh: PreparedSettlement,
    ) {
        require(
            fresh.chainId == reviewed.chainId &&
                fresh.networkName == reviewed.networkName &&
                fresh.rpcUrl == reviewed.rpcUrl &&
                fresh.vaultAddress.equals(reviewed.vaultAddress, ignoreCase = true) &&
                fresh.tokenAddress.equals(reviewed.tokenAddress, ignoreCase = true) &&
                fresh.tokenSymbol == reviewed.tokenSymbol &&
                fresh.tokenDecimals == reviewed.tokenDecimals &&
                fresh.operatorAddress.equals(reviewed.operatorAddress, ignoreCase = true) &&
                fresh.invoiceIds == reviewed.invoiceIds &&
                fresh.totalExpectedAmount == reviewed.totalExpectedAmount &&
                fresh.confirmedObservedAmounts == reviewed.confirmedObservedAmounts &&
                fresh.callData == reviewed.callData &&
                fresh.requiredConfirmations == reviewed.requiredConfirmations,
        ) { "Settlement configuration changed; review it again" }
    }

    private fun historicalSettlementProofFingerprint(invoice: Invoice): String {
        val profile = KnownChainPolicy.requireProfile(invoice.chainId)
        return listOf(
            invoice.chainId.toString(),
            profile.rpcUrl,
            profile.factory.value.lowercase(),
            profile.receiverImplementation.value.lowercase(),
            profile.vaultRuntimeCodeHash.lowercase(),
            invoice.vaultAddress.lowercase(),
            invoice.token.lowercase(),
            invoice.tokenDecimals.toString(),
            invoice.tokenSymbol,
        ).joinToString("|")
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
            config = chainConfigSnapshot(),
            wallet = walletAccess.snapshot(),
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
        // Post-broadcast drive: one cooperative background step per interval keeps the terminal
        // transition near chain finality (~two Base blocks) without holding any interactive lock.
        internal const val FAST_RECOVERY_MAX_ATTEMPTS = 20
        internal const val FAST_RECOVERY_INTERVAL_MILLIS = 3_000L
        private const val HISTORICAL_RPC_CONNECT_TIMEOUT_MILLIS = 2_500
        private const val HISTORICAL_RPC_READ_TIMEOUT_MILLIS = 4_000
        internal const val HISTORICAL_PROOF_TTL_MILLIS = 60_000L
        internal const val PREPARED_PROOF_TTL_MILLIS = 60_000L
        internal const val DEVICE_AUTH_MAX_AGE_MILLIS = 25_000L
    }
}

internal fun isElapsedProofFresh(
    issuedAtElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
    ttlMillis: Long,
): Boolean = issuedAtElapsedRealtimeMillis >= 0 &&
    nowElapsedRealtimeMillis >= issuedAtElapsedRealtimeMillis &&
    nowElapsedRealtimeMillis - issuedAtElapsedRealtimeMillis <= ttlMillis

internal fun PreparedSettlement?.canReuseGasEstimateFor(
    chainId: Long,
    rpcUrl: String,
    rpcEndpointGeneration: Long,
    vaultAddress: String,
    tokenAddress: String,
    operatorAddress: String,
    invoiceIds: List<String>,
    confirmedObservedAmounts: List<BigInteger>,
    callData: String,
    nowElapsedRealtimeMillis: Long,
): Boolean = this != null &&
    this.chainId == chainId &&
    this.rpcUrl == rpcUrl &&
    this.rpcEndpointGeneration == rpcEndpointGeneration &&
    this.vaultAddress.equals(vaultAddress, true) &&
    this.tokenAddress.equals(tokenAddress, true) &&
    this.operatorAddress.equals(operatorAddress, true) &&
    this.invoiceIds == invoiceIds &&
    this.confirmedObservedAmounts == confirmedObservedAmounts &&
    this.callData == callData &&
    isElapsedProofFresh(
        this.gasEstimateAtElapsedRealtimeMillis,
        nowElapsedRealtimeMillis,
        SettlementRepository.PREPARED_PROOF_TTL_MILLIS,
    )

internal fun requireFreshPreparedSettlementProof(
    prepared: PreparedSettlement,
    nowElapsedRealtimeMillis: Long,
) {
    check(
        isElapsedProofFresh(
            prepared.preparedAtElapsedRealtimeMillis,
            nowElapsedRealtimeMillis,
            SettlementRepository.PREPARED_PROOF_TTL_MILLIS,
        ),
    ) { "Settlement preflight expired; review and authenticate again" }
    check(
        isElapsedProofFresh(
            prepared.gasEstimateAtElapsedRealtimeMillis,
            nowElapsedRealtimeMillis,
            SettlementRepository.PREPARED_PROOF_TTL_MILLIS,
        ),
    ) { "Settlement gas estimate expired; review and authenticate again" }
    check(
        isElapsedProofFresh(
            prepared.historicalProofAtElapsedRealtimeMillis,
            nowElapsedRealtimeMillis,
            SettlementRepository.HISTORICAL_PROOF_TTL_MILLIS,
        ),
    ) { "Historical settlement proof expired; review and authenticate again" }
}

internal fun requireFreshDeviceAuthentication(
    authenticatedAtElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
) {
    check(
        isElapsedProofFresh(
            authenticatedAtElapsedRealtimeMillis,
            nowElapsedRealtimeMillis,
            SettlementRepository.DEVICE_AUTH_MAX_AGE_MILLIS,
        ),
    ) { "Device authentication expired before signing; authenticate again" }
}

/** One clock sample closes every proof TTL at each post-authentication safety boundary. */
internal fun requireFreshAuthenticatedPreparedSettlement(
    prepared: PreparedSettlement,
    authenticatedAtElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
) {
    requireFreshPreparedSettlementProof(prepared, nowElapsedRealtimeMillis)
    requireFreshDeviceAuthentication(
        authenticatedAtElapsedRealtimeMillis,
        nowElapsedRealtimeMillis,
    )
}

internal data class FreshSettlementBroadcastOutcome(
    val accepted: Boolean,
    val error: String?,
)

/** Exactly one side-effecting RPC. Receipt and finality reads are recovery-only. */
internal fun broadcastFreshSignedTransaction(
    client: SettlementChainClient,
    signedRawTransaction: String,
    expectedTransactionHash: String,
): FreshSettlementBroadcastOutcome = try {
    val returnedHash = client.sendRawTransaction(signedRawTransaction)
    require(returnedHash.equals(expectedTransactionHash, ignoreCase = true)) {
        "RPC returned a different transaction hash"
    }
    FreshSettlementBroadcastOutcome(accepted = true, error = null)
} catch (error: Exception) {
    val accepted = isKnownTransactionResponse(error)
    FreshSettlementBroadcastOutcome(
        accepted = accepted,
        error = if (accepted) null else safeSettlementRpcError(error, "Broadcast result unknown"),
    )
}

private fun isKnownTransactionResponse(error: Throwable): Boolean =
    (error as? SettlementRpcException)?.knownTransactionResponse == true ||
        error.message.orEmpty().lowercase().let { value ->
        "already known" in value ||
            "known transaction" in value ||
            "already imported" in value ||
            "nonce too low" in value
    }

/** Only transport-boundary messages from our redacting exception type may enter durable state. */
internal fun safeSettlementRpcError(error: Throwable, fallback: String): String =
    when {
        error is SettlementRpcException && error.rpcCode != null ->
            "RPC request failed with JSON-RPC error code ${error.rpcCode}"
        error is IllegalArgumentException && error.message in SAFE_LOCAL_SETTLEMENT_ERRORS ->
            requireNotNull(error.message)
        else -> fallback
    }

private val SAFE_LOCAL_SETTLEMENT_ERRORS = setOf(
    "RPC returned a different transaction hash",
    "RPC returned a different transaction hash while rebroadcasting",
)

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
    val (block, _) = invoice.settlementConfirmationCursor()
    requireCanonicalSettlementCursor(invoice, canonicalBlockHash(block))
}

internal fun requireCanonicalSettlementCursor(
    invoice: Invoice,
    canonicalBlockHash: String?,
) {
    val (_, savedHash) = invoice.settlementConfirmationCursor()
    require(canonicalBlockHash?.equals(savedHash, ignoreCase = true) == true) {
        "Invoice ${invoice.invoiceId} confirmation threshold was reorganized; refresh confirmations"
    }
}

private fun Invoice.settlementConfirmationCursor(): Pair<Long, String> {
    val (block, savedHash) = if (status == InvoiceStatus.LATE_PAYMENT_READY) {
        lateFirstDetectedBlock to lateFirstDetectedBlockHash
    } else {
        firstDetectedBlock to firstDetectedBlockHash
    }
    val thresholdBlock = requireNotNull(block) {
        "Invoice $invoiceId has no confirmation threshold block"
    }
    val thresholdHash = requireNotNull(savedHash) {
        "Invoice $invoiceId has no canonical confirmation cursor; refresh confirmations"
    }
    return thresholdBlock to thresholdHash
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
