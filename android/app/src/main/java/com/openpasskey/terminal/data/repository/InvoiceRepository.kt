package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.Erc681PaymentRequest
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.erc681.PaymentInvoiceFactory
import com.openpasskey.erc681.PaymentInvoice
import com.openpasskey.erc681.PaymentObservation
import com.openpasskey.erc681.PaymentObserver
import com.openpasskey.erc681.PaymentStatus
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.erc681.RpcException
import com.openpasskey.erc681.TokenAmount
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.MerchantReceiptProfile
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.minimumOperatorNativeReserveDisplay
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.hasSameFundingCursor
import com.openpasskey.terminal.data.model.hasSuccessfulPrimaryPayment
import com.openpasskey.terminal.data.model.withoutIncomingPaymentEvidence
import com.openpasskey.terminal.payment.PaymentTransactionEvidence
import com.openpasskey.terminal.payment.PaymentTransactionResolver
import com.openpasskey.terminal.payment.Web3jPaymentTransactionResolver
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger

internal sealed interface VisibleRpcAttemptResult<out T, out S> {
    data class Observed<T>(val value: T) : VisibleRpcAttemptResult<T, Nothing>
    data class Retry<S>(val durableState: S) : VisibleRpcAttemptResult<Nothing, S>
    data class Stop<S>(val durableState: S) : VisibleRpcAttemptResult<Nothing, S>
    data object Deferred : VisibleRpcAttemptResult<Nothing, Nothing>
}

private data class PaymentEvidenceAttempt(val evidence: PaymentTransactionEvidence?)

internal sealed interface AutomaticPaymentEvidenceResult {
    data class Available(val invoice: Invoice?) : AutomaticPaymentEvidenceResult
    /** Interactive work was active or the bounded background unit reached its deadline. */
    data object Deferred : AutomaticPaymentEvidenceResult
    /** The canonical payment cannot be attributed to a printable top-level transaction. */
    data class Unsupported(val invoice: Invoice?) : AutomaticPaymentEvidenceResult
}

private fun Invoice.matches(evidence: PaymentTransactionEvidence): Boolean =
    paymentTxHash?.equals(evidence.txHash, ignoreCase = true) == true &&
        paymentPayerAddress.equals(evidence.payerAddress, ignoreCase = true) &&
        paymentBlockNumber == evidence.blockNumber &&
        paymentBlockHash?.equals(evidence.blockHash, ignoreCase = true) == true &&
        paidAt == evidence.blockTimestamp

/** Retry transport/reorg failures, but never turn a proven wrong chain into an endless spinner. */
internal suspend fun <T : Any, S : Any> runVisibleRpcAttempt(
    boundedRpc: Boolean,
    attempt: suspend () -> T?,
    reloadDurableState: suspend () -> S?,
    shouldContinue: (S) -> Boolean,
    pauseBeforeRetry: suspend () -> Unit,
): VisibleRpcAttemptResult<T, S> = try {
    attempt()?.let { VisibleRpcAttemptResult.Observed(it) }
        ?: VisibleRpcAttemptResult.Deferred
} catch (error: NetworkConfigurationException) {
    throw error
} catch (error: RpcException) {
    if (boundedRpc) throw error
    val durable = reloadDurableState()
        ?: throw IllegalStateException("Invoice disappeared while retrying payment monitoring")
    if (!shouldContinue(durable)) {
        VisibleRpcAttemptResult.Stop(durable)
    } else {
        pauseBeforeRetry()
        // A cashier may close the invoice during the retry cadence. Re-read after the pause so no
        // further RPC is issued from the stale open snapshot.
        val afterPause = reloadDurableState()
            ?: throw IllegalStateException("Invoice disappeared while retrying payment monitoring")
        if (shouldContinue(afterPause)) {
            VisibleRpcAttemptResult.Retry(afterPause)
        } else {
            VisibleRpcAttemptResult.Stop(afterPause)
        }
    }
}

/** Application persistence around the SDK's keyless, read-only payment API. */
class InvoiceRepository(
    private val invoiceDao: InvoiceDao,
    settlementEventDao: SettlementEventDao,
    private val chainConfig: ChainConfig,
    private val operatorWalletStore: OperatorWalletStore,
    private val lifecycleGate: TerminalLifecycleGate,
    private val rpcWorkCoordinator: RpcWorkCoordinator = RpcWorkCoordinator(),
    private val paymentTransactionResolver: PaymentTransactionResolver =
        Web3jPaymentTransactionResolver(),
) {
    private val lateInvoiceReconciler = LateInvoiceReconciler(
        invoiceDao,
        settlementEventDao,
        lifecycleGate,
    )

    /** Serializes cashier profile changes with invoice publication and settlement mutations. */
    suspend fun selectPaymentProfile(profileId: String): TerminalConfigSnapshot =
        withContext(Dispatchers.IO) {
            rpcWorkCoordinator.withInteractiveOperation {
                selectPaymentProfileExclusively(
                    lifecycleGate = lifecycleGate,
                    profileId = profileId,
                    selectProfile = chainConfig::selectProfile,
                    snapshot = chainConfig::snapshot,
                )
            }
        }

    suspend fun createInvoice(displayAmount: String, profileId: String): Invoice =
        withContext(Dispatchers.IO) {
            rpcWorkCoordinator.withInteractiveOperation {
                lifecycleGate.withExclusiveMutation {
                    val settings = chainConfig.snapshot()
                    require(settings.provisioned) {
                        "Provision this terminal from the merchant portal first"
                    }
                    val selectedProfile = requireSelectedPaymentProfile(settings, profileId)
                    val token = selectedProfile.token
                    val profile = KnownChainPolicy.requireProfile(settings.chainId)
                    profile.requireValidCreate2Fixture()
                    require(settings.factoryAddress.equals(profile.factory.value, true)) {
                        "Factory pin mismatch"
                    }
                    require(
                        settings.receiverImplementationAddress.equals(
                            profile.receiverImplementation.value,
                            true,
                        ),
                    ) { "Receiver implementation pin mismatch" }
                    val network = settings.toNetworkConfig()
                    val tokenAddress = EvmAddress.parse(token.address)
                    val amount = TokenAmount.parse(displayAmount, token.decimals)
                    val rpc = ReadOnlyRpcClient(network)
                    val wallet = operatorWalletStore.snapshot()
                    val operatorIdentifier = requireOperatorInvoiceIdentifier(wallet)
                    require(
                        settings.provisionedOperatorAddress?.equals(
                            operatorIdentifier.value,
                            true,
                        ) == true,
                    ) { "Provisioned operator does not match the local terminal wallet" }

                    // Derive the receiver locally before touching RPC so configuration, operator
                    // readiness, and receiver freshness can be proven at one canonical block in
                    // three bounded network waves.
                    val protocolInvoice = PaymentInvoiceFactory.create(
                        network = network,
                        token = tokenAddress,
                        amount = amount,
                        // The protocol calls this namespace a terminal identifier. New invoices
                        // use the device settlement EOA; historical invoice IDs remain unchanged.
                        terminalIdentifier = operatorIdentifier,
                    )
                    val receiver = protocolInvoice.request.receiver
                    val checkoutProof = rpc.validateCheckout(
                        token = tokenAddress,
                        expectedDecimals = token.decimals,
                        expectedSymbol = token.symbol,
                        operator = operatorIdentifier,
                        receiver = receiver,
                    )
                    val validation = checkoutProof.validation
                    require(validation.tokenWhitelisted) {
                        "Token is not whitelisted by the configured vault"
                    }
                    val liveReadiness = checkoutProof.operatorReadiness
                    val readiness = InvoiceReadiness(
                        authorized = liveReadiness.listedOperator ||
                            liveReadiness.vaultOwner == operatorIdentifier,
                        nativeBalance = liveReadiness.nativeBalance,
                    )
                    requireTerminalReadiness(settings, wallet, readiness)
                    requireInvoiceStateUnchanged(
                        expectedSettings = settings,
                        currentSettings = chainConfig.snapshot(),
                        expectedWallet = wallet,
                        currentWallet = operatorWalletStore.snapshot(),
                    )
                    operatorWalletStore.recordVerifiedSettlementTarget(
                        settings.chainId,
                        settings.vaultAddress,
                        requireNotNull(settings.provisionedOperatorAddress),
                    )

                    val receiverFreshness = checkoutProof.receiverFreshness
                    require(receiverFreshness.deployedCode.isEmpty()) {
                        "Derived receiver is already deployed; refusing to reuse an invoice receiver"
                    }
                    require(receiverFreshness.tokenBalance == BigInteger.ZERO) {
                        "Derived receiver already has a token balance; refusing to reuse an invoice receiver"
                    }
                    val invoice = buildPublishedInvoiceSnapshot(
                        protocolInvoice = protocolInvoice,
                        selectedProfile = selectedProfile,
                        operatorAddress = operatorIdentifier,
                        createdAt = System.currentTimeMillis() / 1_000,
                        publishedAtBlock = checkoutProof.blockNumber,
                        publishedAtBlockHash = checkoutProof.blockHash,
                        receiptNumber = invoiceDao.countIssuedInvoices().toLong() + 1,
                        merchantReceiptProfile = chainConfig.merchantReceiptProfile(),
                    )
                    // Persist the complete request before the UI can display its QR.
                    invoiceDao.insert(invoice)
                    invoice
                }
            }
        }

    fun observePayment(invoiceId: String): Flow<Invoice> = observePayment(invoiceId, boundedRpc = false)

    private fun observePayment(invoiceId: String, boundedRpc: Boolean): Flow<Invoice> = flow {
        var invoice = invoiceDao.getById(invoiceId)
            ?: throw IllegalArgumentException("Invoice not found")
        emit(invoice)
        if (!invoice.canMonitor()) return@flow

        val request = invoice.toPaymentRequest()
        val observer = PaymentObserver(
            ReadOnlyRpcClient(
                invoice.toNetworkConfig(),
                connectTimeoutMillis = if (boundedRpc) {
                    RECOVERY_RPC_CONNECT_TIMEOUT_MILLIS
                } else {
                    MONITOR_RPC_CONNECT_TIMEOUT_MILLIS
                },
                readTimeoutMillis = if (boundedRpc) {
                    RECOVERY_RPC_READ_TIMEOUT_MILLIS
                } else {
                    MONITOR_RPC_READ_TIMEOUT_MILLIS
                },
            ),
        )
        var previous = invoice.toPreviousObservation(request)
        while (invoice.canMonitor()) {
            val attempt = runVisibleRpcAttempt(
                boundedRpc = boundedRpc,
                attempt = {
                    rpcWorkCoordinator.withBackgroundOperation {
                        observer.observe(
                            request = request,
                            previous = previous,
                            requiredConfirmations = invoice.confirmationBlocks,
                        )
                    }
                },
                reloadDurableState = { invoiceDao.getById(invoice.invoiceId) },
                shouldContinue = { candidate -> candidate.canMonitor() },
                pauseBeforeRetry = { delay(POLL_INTERVAL_MILLIS) },
            )
            val observation = when (attempt) {
                is VisibleRpcAttemptResult.Observed -> attempt.value
                is VisibleRpcAttemptResult.Retry -> {
                    invoice = attempt.durableState
                    previous = invoice.toPreviousObservation(request)
                    emit(invoice)
                    continue
                }
                is VisibleRpcAttemptResult.Stop -> {
                    invoice = attempt.durableState
                    emit(invoice)
                    break
                }
                VisibleRpcAttemptResult.Deferred -> {
                    // A bounded automatic recovery pass must not linger behind cashier work. The
                    // durable scheduler retries later. Visible monitoring also rechecks durable
                    // cancellation before waiting for the cashier window.
                    if (boundedRpc) throw BackgroundRpcDeferredException()
                    val durable = invoiceDao.getById(invoice.invoiceId)
                        ?: throw IllegalStateException("Invoice disappeared while monitoring")
                    invoice = durable
                    previous = invoice.toPreviousObservation(request)
                    emit(invoice)
                    if (!invoice.canMonitor()) break
                    rpcWorkCoordinator.awaitBackgroundWindow()
                    invoice = invoiceDao.getById(invoice.invoiceId)
                        ?: throw IllegalStateException("Invoice disappeared while monitoring")
                    previous = invoice.toPreviousObservation(request)
                    if (!invoice.canMonitor()) {
                        emit(invoice)
                        break
                    }
                    continue
                }
            }
            val status = observation.toInvoiceStatus()
            val confirmedBlock = if (status == InvoiceStatus.PAID || status == InvoiceStatus.OVERPAID) {
                observation.blockNumber
            } else {
                null
            }
            val fundingCursorUnchanged =
                observation.fundedAtBlock != null &&
                    observation.fundedAtBlock == invoice.firstDetectedBlock &&
                    observation.fundedAtBlockHash.equals(
                        invoice.firstDetectedBlockHash,
                        ignoreCase = true,
                    )
            val evidence = if (
                observation.fundedAtBlock != null &&
                !fundingCursorUnchanged &&
                invoice.receiptAutoPrintEligible
            ) {
                val candidate = invoice.copy(
                    receivedAmount = observation.observedRawUnits.toString(),
                    status = status,
                    firstDetectedBlock = observation.fundedAtBlock,
                    firstDetectedBlockHash = observation.fundedAtBlockHash,
                    lastObservedBlock = observation.blockNumber,
                    confirmedAtBlock = confirmedBlock,
                    paymentTxHash = null,
                    paymentPayerAddress = null,
                    paymentBlockNumber = null,
                    paymentBlockHash = null,
                    paidAt = null,
                )
                try {
                    rpcWorkCoordinator.withBackgroundOperation {
                        PaymentEvidenceAttempt(paymentTransactionResolver.resolve(candidate))
                    }?.evidence
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val retainEvidence = observation.fundedAtBlock != null && fundingCursorUnchanged
            val proposed = invoice.copy(
                receivedAmount = observation.observedRawUnits.toString(),
                status = status,
                firstDetectedBlock = observation.fundedAtBlock,
                firstDetectedBlockHash = observation.fundedAtBlockHash,
                lastObservedBlock = observation.blockNumber,
                confirmedAtBlock = confirmedBlock,
                paymentTxHash = if (retainEvidence) invoice.paymentTxHash else evidence?.txHash,
                paymentPayerAddress = if (retainEvidence) {
                    invoice.paymentPayerAddress
                } else {
                    evidence?.payerAddress
                },
                paymentBlockNumber = if (retainEvidence) {
                    invoice.paymentBlockNumber
                } else {
                    evidence?.blockNumber
                },
                paymentBlockHash = if (retainEvidence) {
                    invoice.paymentBlockHash
                } else {
                    evidence?.blockHash
                },
                paidAt = if (retainEvidence) invoice.paidAt else evidence?.blockTimestamp,
            )
            val (latest, observationPersisted) = lifecycleGate.withExclusiveMutation {
                val current = invoiceDao.getById(invoice.invoiceId)
                    ?: throw IllegalStateException("Invoice disappeared while monitoring")
                if (current != invoice) {
                    current to false
                } else {
                    check(
                        invoiceDao.updateObservation(
                            invoiceId = invoice.invoiceId,
                            receivedAmount = observation.observedRawUnits.toString(),
                            status = status,
                            firstDetectedBlock = observation.fundedAtBlock,
                            firstDetectedBlockHash = observation.fundedAtBlockHash,
                            lastObservedBlock = observation.blockNumber,
                            confirmedAtBlock = confirmedBlock,
                            paymentTxHash = proposed.paymentTxHash,
                            paymentPayerAddress = proposed.paymentPayerAddress,
                            paymentBlockNumber = proposed.paymentBlockNumber,
                            paymentBlockHash = proposed.paymentBlockHash,
                            paidAt = proposed.paidAt,
                        ) == 1,
                    ) { "Invoice observation changed during its lifecycle mutation" }
                    proposed to true
                }
            }
            invoice = latest
            // A concurrent monitor or lifecycle transition wins over a sample made from stale
            // state. Resume from its durable observation instead of regressing block/confirmation.
            previous = if (observationPersisted) observation else invoice.toPreviousObservation(request)
            emit(invoice)
            if (!invoice.canMonitor()) break
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun reconcileLateInvoices() = withContext(Dispatchers.IO) {
        // One record is one bounded background unit. This avoids both check-then-run overlap and
        // a multi-record pass monopolizing the public endpoint ahead of a cashier tap.
        rpcWorkCoordinator.withBackgroundOperation {
            lateInvoiceReconciler.reconcileOnce(limit = 1)
        }
    }

    suspend fun recoverOpenInvoices() = withContext(Dispatchers.IO) {
        if (rpcWorkCoordinator.interactive) return@withContext
        recoverOpenInvoiceBatch(invoiceDao) { invoice ->
            // Initial emission is the stored value; the second is the fresh RPC observation. The
            // durable attempt timestamp was committed before this potentially slow RPC begins.
            withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
                observePayment(invoice.invoiceId, boundedRpc = true).drop(1).first()
            }
        }
    }

    fun observeRecent(limit: Int): Flow<List<Invoice>> = invoiceDao.observeRecent(limit)
    fun observeReceiptHistory(): Flow<List<Invoice>> = invoiceDao.observeReceiptHistory()
    fun observePendingAutoReceipts(): Flow<List<Invoice>> = invoiceDao.observePendingAutoReceipts()
    fun observeUnprintedReceiptSnapshots(): Flow<List<Invoice>> =
        invoiceDao.observeUnprintedReceiptSnapshots()
    suspend fun getInvoice(invoiceId: String): Invoice? = invoiceDao.getById(invoiceId)

    /** Revalidate incoming evidence against the current canonical publication and funding anchors. */
    suspend fun ensurePaymentEvidence(invoiceId: String): Invoice? = withContext(Dispatchers.IO) {
        val invoice = invoiceDao.getById(invoiceId) ?: return@withContext null
        if (!invoice.needsPaymentEvidenceValidation()) {
            return@withContext invoice
        }
        val evidence = rpcWorkCoordinator.withInteractiveOperation {
            paymentTransactionResolver.resolve(invoice)
        } ?: return@withContext invoice.withoutIncomingPaymentEvidence()
        persistValidatedPaymentEvidence(invoice, evidence)
    }

    /**
     * Automatic printing is cooperative background work. A cashier action always wins, and the
     * whole resolver unit is capped by [RpcWorkCoordinator]'s background deadline.
     */
    internal suspend fun ensurePaymentEvidenceAutomatically(
        invoiceId: String,
    ): AutomaticPaymentEvidenceResult = withContext(Dispatchers.IO) {
        val invoice = invoiceDao.getById(invoiceId)
            ?: return@withContext AutomaticPaymentEvidenceResult.Available(null)
        if (!invoice.needsPaymentEvidenceValidation()) {
            return@withContext AutomaticPaymentEvidenceResult.Available(invoice)
        }
        val attempt = try {
            rpcWorkCoordinator.withBackgroundOperation {
                PaymentEvidenceAttempt(paymentTransactionResolver.resolve(invoice))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkConfigurationException) {
            return@withContext AutomaticPaymentEvidenceResult.Unsupported(
                invoice.withoutIncomingPaymentEvidence(),
            )
        } catch (_: Exception) {
            return@withContext AutomaticPaymentEvidenceResult.Deferred
        } ?: return@withContext AutomaticPaymentEvidenceResult.Deferred

        val evidence = attempt.evidence
            ?: return@withContext AutomaticPaymentEvidenceResult.Unsupported(
                invoice.withoutIncomingPaymentEvidence(),
            )
        AutomaticPaymentEvidenceResult.Available(
            persistValidatedPaymentEvidence(invoice, evidence),
        )
    }

    private suspend fun persistValidatedPaymentEvidence(
        invoice: Invoice,
        evidence: PaymentTransactionEvidence,
    ): Invoice? {
        val current = lifecycleGate.withExclusiveMutation {
            val durable = invoiceDao.getById(invoice.invoiceId)
                ?: return@withExclusiveMutation null
            if (!durable.receiptAutoPrintEligible || !durable.hasSuccessfulPrimaryPayment() ||
                !durable.hasSameFundingCursor(invoice)
            ) {
                return@withExclusiveMutation durable.withoutIncomingPaymentEvidence()
            }
            if (!durable.matches(evidence)) {
                invoiceDao.persistPaymentEvidence(
                    invoiceId = durable.invoiceId,
                    fundingCursorBlock = requireNotNull(durable.firstDetectedBlock),
                    fundingCursorHash = requireNotNull(durable.firstDetectedBlockHash),
                    expectedPaymentTxHash = durable.paymentTxHash,
                    paymentTxHash = evidence.txHash,
                    paymentPayerAddress = evidence.payerAddress,
                    paymentBlockNumber = evidence.blockNumber,
                    paymentBlockHash = evidence.blockHash,
                    paidAt = evidence.blockTimestamp,
                )
            }
            invoiceDao.getById(invoice.invoiceId)
        }
        return current?.takeIf {
            it.receiptAutoPrintEligible && it.hasSuccessfulPrimaryPayment() &&
                it.hasSameFundingCursor(invoice) && it.matches(evidence)
        } ?: current?.withoutIncomingPaymentEvidence()
    }

    private fun Invoice.needsPaymentEvidenceValidation(): Boolean =
        receiptAutoPrintEligible && hasSuccessfulPrimaryPayment() &&
            firstDetectedBlock != null && firstDetectedBlockHash != null

    suspend fun markReceiptPrinted(
        invoiceId: String,
        expectedPaymentTxHash: String,
        expectedFundingCursorBlock: Long,
        expectedFundingCursorHash: String,
        printedAt: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleGate.withExclusiveMutation {
                invoiceDao.markReceiptPrinted(
                    invoiceId = invoiceId,
                    expectedPaymentTxHash = expectedPaymentTxHash,
                    expectedFundingCursorBlock = expectedFundingCursorBlock,
                    expectedFundingCursorHash = expectedFundingCursorHash,
                    printedAt = printedAt,
                ) == 1
            }
        }
    suspend fun updateStatus(invoiceId: String, status: InvoiceStatus) =
        withContext(Dispatchers.IO) {
            require(status == InvoiceStatus.EXPIRED) { "Only an open invoice can be closed manually" }
            lifecycleGate.withExclusiveMutation {
                invoiceDao.updateStatus(invoiceId, status)
            }
        }

    fun hasReadyOperatorWallet(): Boolean {
        val settings = chainConfig.snapshot()
        val wallet = operatorWalletStore.snapshot()
        return settings.provisioned && wallet.availability == OperatorWalletAvailability.READY &&
            wallet.address != null &&
            settings.provisionedOperatorAddress?.equals(wallet.address, true) == true
    }

    private fun TerminalConfigSnapshot.toNetworkConfig() = NetworkConfig(
        chainId = chainId,
        rpcUrl = rpcUrl,
        factory = EvmAddress.parse(factoryAddress),
        receiverImplementation = EvmAddress.parse(receiverImplementationAddress),
        vault = EvmAddress.parse(vaultAddress)
    )

    private fun Invoice.toNetworkConfig() = NetworkConfig(
        chainId = chainId,
        rpcUrl = rpcUrl,
        factory = EvmAddress.parse(factoryAddress),
        receiverImplementation = EvmAddress.parse(receiverImplementationAddress),
        vault = EvmAddress.parse(vaultAddress)
    )

    private fun Invoice.toPaymentRequest() = Erc681PaymentRequest(
        token = EvmAddress.parse(token),
        chainId = chainId,
        receiver = EvmAddress.parse(receiver),
        amount = TokenAmount.ofRaw(BigInteger(expectedAmount), tokenDecimals)
    )

    private fun Invoice.toPreviousObservation(request: Erc681PaymentRequest): PaymentObservation? {
        val firstBlock = firstDetectedBlock ?: return null
        val firstBlockHash = firstDetectedBlockHash ?: return null
        val lastBlock = lastObservedBlock ?: return null
        if (firstBlock > lastBlock) return null
        val required = confirmationBlocks.coerceAtLeast(1)
        val confirmations = (lastBlock - firstBlock + 1)
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val sdkStatus = when (status) {
            InvoiceStatus.WAITING -> PaymentStatus.AWAITING_PAYMENT
            InvoiceStatus.PARTIAL -> PaymentStatus.PARTIALLY_FUNDED
            InvoiceStatus.CONFIRMING -> PaymentStatus.CONFIRMING
            InvoiceStatus.PAID, InvoiceStatus.OVERPAID -> PaymentStatus.PAID
            InvoiceStatus.PARTIALLY_SETTLED,
            InvoiceStatus.LATE_PAYMENT_CONFIRMING,
            InvoiceStatus.LATE_PAYMENT_READY,
            InvoiceStatus.SETTLED,
            InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED,
            InvoiceStatus.EXPIRED -> return null
        }
        return PaymentObservation(
            token = request.token,
            receiver = request.receiver,
            expectedAmount = request.amount,
            observedRawUnits = BigInteger(receivedAmount),
            blockNumber = lastBlock,
            fundedAtBlock = firstBlock,
            fundedAtBlockHash = firstBlockHash,
            confirmations = confirmations,
            requiredConfirmations = required,
            status = sdkStatus
        )
    }

    private fun PaymentObservation.toInvoiceStatus(): InvoiceStatus = when (status) {
        PaymentStatus.AWAITING_PAYMENT -> InvoiceStatus.WAITING
        PaymentStatus.PARTIALLY_FUNDED -> InvoiceStatus.PARTIAL
        PaymentStatus.CONFIRMING -> InvoiceStatus.CONFIRMING
        PaymentStatus.PAID -> if (isOverpaid) InvoiceStatus.OVERPAID else InvoiceStatus.PAID
    }

    private fun Invoice.canMonitor(): Boolean =
        status in listOf(InvoiceStatus.WAITING, InvoiceStatus.PARTIAL, InvoiceStatus.CONFIRMING)

    companion object {
        // Base-family blocks are approximately two seconds and readiness requires at least two
        // blocks, so a five-second cadence avoids self-throttling without increasing finality time.
        internal const val POLL_INTERVAL_MILLIS = 5_000L
        private const val RECOVERY_TIMEOUT_MILLIS = 5_000L
        // One SDK observation is exactly three HTTP waves. Even though an already-started sample
        // no longer owns the cashier mutex, cap its worst socket budget below the five-second
        // coordinator lease so background overlap remains brief and bounded.
        internal const val MONITOR_RPC_WAVES = 3
        internal const val MONITOR_RPC_CONNECT_TIMEOUT_MILLIS = 500
        internal const val MONITOR_RPC_READ_TIMEOUT_MILLIS = 1_000
        internal const val RECOVERY_RPC_CONNECT_TIMEOUT_MILLIS = 500
        internal const val RECOVERY_RPC_READ_TIMEOUT_MILLIS = 1_000
    }
}

/**
 * Detaches every payment-routing value needed by monitoring, settlement, and audit from the
 * mutable profile catalog before the QR is published. The returned entity is safe to persist even
 * if a cashier selects another profile immediately afterwards.
 */
internal fun buildPublishedInvoiceSnapshot(
    protocolInvoice: PaymentInvoice,
    selectedProfile: TerminalPaymentProfile,
    operatorAddress: EvmAddress,
    createdAt: Long,
    publishedAtBlock: Long,
    publishedAtBlockHash: String,
    receiptNumber: Long,
    merchantReceiptProfile: MerchantReceiptProfile,
): Invoice {
    val request = protocolInvoice.request
    val token = selectedProfile.token
    require(request.chainId == selectedProfile.chainId) {
        "Invoice chain does not match the selected payment profile"
    }
    require(protocolInvoice.vault.value.equals(selectedProfile.vaultAddress, true)) {
        "Invoice vault does not match the selected payment profile"
    }
    require(request.token.value.equals(token.address, true)) {
        "Invoice token does not match the selected payment profile"
    }
    require(request.amount.decimals == token.decimals) {
        "Invoice amount decimals do not match the selected payment profile"
    }
    require(!operatorAddress.isZero) { "Invoice operator address must not be zero" }
    require(publishedAtBlock >= 0) { "Published block must not be negative" }
    require(BLOCK_HASH_PATTERN.matches(publishedAtBlockHash)) {
        "Published block hash must be canonical"
    }
    require(receiptNumber > 0) { "Receipt number must be positive" }

    return Invoice(
        invoiceId = protocolInvoice.invoiceId.hex,
        receiver = request.receiver.value,
        operatorAddress = operatorAddress.value,
        token = request.token.value,
        tokenSymbol = token.symbol,
        tokenDecimals = token.decimals,
        expectedAmount = request.amount.rawUnits.toString(),
        status = InvoiceStatus.WAITING,
        createdAt = createdAt,
        chainId = selectedProfile.chainId,
        networkName = selectedProfile.networkName,
        rpcUrl = selectedProfile.rpcUrl,
        factoryAddress = EvmAddress.parse(selectedProfile.factoryAddress).value,
        receiverImplementationAddress = EvmAddress.parse(
            selectedProfile.receiverImplementationAddress,
        ).value,
        vaultAddress = EvmAddress.parse(selectedProfile.vaultAddress).value,
        confirmationBlocks = selectedProfile.confirmationBlocks,
        erc681Uri = protocolInvoice.erc681Uri,
        publishedAtBlock = publishedAtBlock,
        publishedAtBlockHash = publishedAtBlockHash.lowercase(),
        receiptNumber = receiptNumber,
        receiptMerchantName = merchantReceiptProfile.name,
        receiptMerchantAbn = merchantReceiptProfile.abn,
        receiptAutoPrintEligible = true,
    )
}

private val BLOCK_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")

internal suspend fun selectPaymentProfileExclusively(
    lifecycleGate: TerminalLifecycleGate,
    profileId: String,
    selectProfile: (String) -> Boolean,
    snapshot: () -> TerminalConfigSnapshot,
): TerminalConfigSnapshot = lifecycleGate.withExclusiveMutation {
    check(selectProfile(profileId)) { "Unable to save the selected payment profile" }
    snapshot()
}

internal suspend fun recoverOpenInvoiceBatch(
    invoiceDao: InvoiceDao,
    limit: Int = MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS,
    attemptedAt: () -> Long = System::currentTimeMillis,
    recover: suspend (Invoice) -> Unit,
): Int {
    require(limit in 1..MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS) {
        "Open-invoice recovery limit must be between 1 and $MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS"
    }
    var attempted = 0
    invoiceDao.getOpenRecoveryCandidates(limit).forEach { invoice ->
        if (invoiceDao.markOpenRecoveryAttempt(invoice.invoiceId, attemptedAt()) != 1) {
            return@forEach
        }
        attempted += 1
        try {
            recover(invoice)
        } catch (_: BackgroundRpcDeferredException) {
            // Interactive work arrived after this durable attempt was claimed. Stop the pass now;
            // later rows retain priority and the claimed row rotates on the next scheduled pass.
            return attempted
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The durable least-recently-attempted ordering moves this endpoint behind rows that
            // have not yet had a chance, including after a process restart.
        }
    }
    return attempted
}

private class BackgroundRpcDeferredException : RuntimeException()

internal const val MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS = 2

internal data class InvoiceReadiness(
    val authorized: Boolean,
    val nativeBalance: BigInteger,
)

/** Final UI-to-repository guard before a sale amount is interpreted and a QR is published. */
internal fun requireSelectedPaymentProfile(
    settings: TerminalConfigSnapshot,
    requestedProfileId: String,
): TerminalPaymentProfile {
    val selectedProfile = requireNotNull(settings.selectedPaymentProfile()) {
        "Select a configured payment profile"
    }
    require(selectedProfile.id == requestedProfileId) {
        "Selected payment profile changed; review the currency and try again"
    }
    return selectedProfile
}

internal fun requireTerminalReadiness(
    settings: TerminalConfigSnapshot,
    wallet: OperatorWalletSnapshot,
    readiness: InvoiceReadiness,
) {
    check(settings.provisioned) { "Provision this terminal from the merchant portal first" }
    val operator = requireOperatorInvoiceIdentifier(wallet)
    check(settings.provisionedOperatorAddress?.equals(operator.value, true) == true) {
        "Provisioned operator does not match the local terminal wallet"
    }
    check(readiness.authorized) {
        "Authorize this terminal operator on the provisioned vault before creating a payment QR"
    }
    val networkPolicy = KnownChainPolicy.requireProfile(settings.chainId)
    check(readiness.nativeBalance >= networkPolicy.minimumOperatorNativeReserve) {
        "Fund the terminal operator with at least " +
            "${networkPolicy.minimumOperatorNativeReserveDisplay()} before creating a payment QR"
    }
}

internal fun requireInvoiceStateUnchanged(
    expectedSettings: TerminalConfigSnapshot,
    currentSettings: TerminalConfigSnapshot,
    expectedWallet: OperatorWalletSnapshot,
    currentWallet: OperatorWalletSnapshot,
) {
    check(currentSettings == expectedSettings) {
        "Terminal configuration changed during invoice validation; retry the payment"
    }
    check(
        currentWallet.availability == OperatorWalletAvailability.READY &&
            currentWallet.address != null &&
            currentWallet.address.equals(expectedWallet.address, true),
    ) { "Terminal operator wallet changed during invoice validation; retry the payment" }
}

internal fun requireOperatorInvoiceIdentifier(snapshot: OperatorWalletSnapshot): EvmAddress {
    check(snapshot.availability == OperatorWalletAvailability.READY && snapshot.address != null) {
        snapshot.error
            ?: "Create the terminal operator wallet in Settings before creating a payment QR. " +
                "Historical invoices remain available."
    }
    return EvmAddress.parse(requireNotNull(snapshot.address))
}
