package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.Erc681PaymentRequest
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.PaymentInvoiceFactory
import com.openpasskey.erc681.PaymentObservation
import com.openpasskey.erc681.PaymentObserver
import com.openpasskey.erc681.PaymentStatus
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.erc681.TokenAmount
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
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

/** Application persistence around the SDK's keyless, read-only payment API. */
class InvoiceRepository(
    private val invoiceDao: InvoiceDao,
    settlementEventDao: SettlementEventDao,
    private val chainConfig: ChainConfig,
    private val operatorWalletStore: OperatorWalletStore,
    private val lifecycleGate: TerminalLifecycleGate,
    private val settlementClientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
) {
    private val lateInvoiceReconciler = LateInvoiceReconciler(
        invoiceDao,
        settlementEventDao,
        lifecycleGate,
    )

    suspend fun createInvoice(displayAmount: String, token: PaymentToken): Invoice =
        withContext(Dispatchers.IO) {
            lifecycleGate.withExclusiveMutation {
            val settings = chainConfig.snapshot()
            require(settings.provisioned) { "Provision this terminal from the merchant portal first" }
            val profile = KnownChainPolicy.requireProfile(settings.chainId)
            profile.requireValidCreate2Fixture()
            require(settings.factoryAddress.equals(profile.factory.value, true)) { "Factory pin mismatch" }
            require(
                settings.receiverImplementationAddress.equals(
                    profile.receiverImplementation.value,
                    true,
                ),
            ) { "Receiver implementation pin mismatch" }
            val configuredToken = settings.paymentTokens.single()
            require(configuredToken.address.equals(token.address, true)) {
                "Selected token does not match the provisioned terminal token"
            }
            require(configuredToken.symbol == token.symbol && configuredToken.decimals == token.decimals) {
                "Selected token metadata does not match the provisioned configuration"
            }
            val network = settings.toNetworkConfig()
            val tokenAddress = EvmAddress.parse(token.address)
            val amount = TokenAmount.parse(displayAmount, token.decimals)
            val rpc = ReadOnlyRpcClient(network)
            val wallet = operatorWalletStore.snapshot()
            val operatorIdentifier = requireOperatorInvoiceIdentifier(wallet)
            require(settings.provisionedOperatorAddress?.equals(operatorIdentifier.value, true) == true) {
                "Provisioned operator does not match the local terminal wallet"
            }

            val validation = rpc.validate(tokenAddress, token.decimals, token.symbol)
            require(validation.tokenWhitelisted) { "Token is not whitelisted by the configured vault" }
            val readiness = settlementClientFactory(settings.rpcUrl).use { client ->
                require(client.chainId() == settings.chainId) { "RPC chain ID mismatch" }
                val listed = client.isOperator(settings.vaultAddress, operatorIdentifier.value)
                val ownerMatches = if (listed) false else {
                    client.owner(settings.vaultAddress).equals(operatorIdentifier.value, true)
                }
                InvoiceReadiness(
                    authorized = listed || ownerMatches,
                    nativeBalance = client.nativeBalance(operatorIdentifier.value),
                )
            }
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

            val protocolInvoice = PaymentInvoiceFactory.create(
                network = network,
                token = tokenAddress,
                amount = amount,
                // The protocol calls this namespace a terminal identifier. For every new invoice,
                // the app uses the public address of the device's real settlement operator EOA.
                // Historical invoices retain their already-persisted invoice IDs and receivers.
                terminalIdentifier = operatorIdentifier
            )
            val receiver = protocolInvoice.request.receiver
            require(rpc.codeAt(receiver).isEmpty()) {
                "Derived receiver is already deployed; refusing to reuse an invoice receiver"
            }
            require(rpc.tokenBalance(tokenAddress, receiver) == BigInteger.ZERO) {
                "Derived receiver already has a token balance; refusing to reuse an invoice receiver"
            }
            requireInvoiceStateUnchanged(
                expectedSettings = settings,
                currentSettings = chainConfig.snapshot(),
                expectedWallet = wallet,
                currentWallet = operatorWalletStore.snapshot(),
            )

            val createdAt = System.currentTimeMillis() / 1_000
            val invoice = Invoice(
                invoiceId = protocolInvoice.invoiceId.hex,
                receiver = receiver.value,
                token = tokenAddress.value,
                tokenSymbol = token.symbol,
                tokenDecimals = token.decimals,
                expectedAmount = amount.rawUnits.toString(),
                status = InvoiceStatus.WAITING,
                createdAt = createdAt,
                chainId = settings.chainId,
                networkName = settings.networkName,
                rpcUrl = settings.rpcUrl,
                factoryAddress = network.factory.value,
                receiverImplementationAddress = network.receiverImplementation.value,
                vaultAddress = network.vault.value,
                confirmationBlocks = settings.confirmationBlocks,
                erc681Uri = protocolInvoice.erc681Uri
            )
            // Persist the complete request before the UI is allowed to display its QR.
            invoiceDao.insert(invoice)
            invoice
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
            if (boundedRpc) {
                ReadOnlyRpcClient(
                    invoice.toNetworkConfig(),
                    connectTimeoutMillis = RECOVERY_RPC_CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = RECOVERY_RPC_READ_TIMEOUT_MILLIS,
                )
            } else {
                ReadOnlyRpcClient(invoice.toNetworkConfig())
            },
        )
        var previous = invoice.toPreviousObservation(request)
        while (invoice.canMonitor()) {
            val observation = observer.observe(
                request = request,
                previous = previous,
                requiredConfirmations = invoice.confirmationBlocks
            )
            val status = observation.toInvoiceStatus()
            val confirmedBlock = if (status == InvoiceStatus.PAID || status == InvoiceStatus.OVERPAID) {
                observation.blockNumber
            } else {
                null
            }
            val proposed = invoice.copy(
                receivedAmount = observation.observedRawUnits.toString(),
                status = status,
                firstDetectedBlock = observation.fundedAtBlock,
                firstDetectedBlockHash = observation.fundedAtBlockHash,
                lastObservedBlock = observation.blockNumber,
                confirmedAtBlock = confirmedBlock,
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
        lateInvoiceReconciler.reconcileOnce()
    }

    suspend fun recoverOpenInvoices() = withContext(Dispatchers.IO) {
        recoverOpenInvoiceBatch(invoiceDao) { invoice ->
            // Initial emission is the stored value; the second is the fresh RPC observation. The
            // durable attempt timestamp was committed before this potentially slow RPC begins.
            withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
                observePayment(invoice.invoiceId, boundedRpc = true).drop(1).first()
            }
        }
    }

    fun observeRecent(limit: Int): Flow<List<Invoice>> = invoiceDao.observeRecent(limit)
    suspend fun getInvoice(invoiceId: String): Invoice? = invoiceDao.getById(invoiceId)
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
        private const val POLL_INTERVAL_MILLIS = 2_000L
        private const val RECOVERY_TIMEOUT_MILLIS = 15_000L
        private const val RECOVERY_RPC_CONNECT_TIMEOUT_MILLIS = 2_500
        private const val RECOVERY_RPC_READ_TIMEOUT_MILLIS = 4_000
        val MINIMUM_GAS_RESERVE_WEI: BigInteger = BigInteger("100000000000000")
    }
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
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The durable least-recently-attempted ordering moves this endpoint behind rows that
            // have not yet had a chance, including after a process restart.
        }
    }
    return attempted
}

internal const val MAX_OPEN_RECOVERY_CANDIDATES_PER_PASS = 2

internal data class InvoiceReadiness(
    val authorized: Boolean,
    val nativeBalance: BigInteger,
)

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
    check(readiness.nativeBalance >= InvoiceRepository.MINIMUM_GAS_RESERVE_WEI) {
        "Fund the terminal operator with at least 0.0001 native currency before creating a payment QR"
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
