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
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import kotlinx.coroutines.Dispatchers
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
    private val chainConfig: ChainConfig
) {
    suspend fun createInvoice(displayAmount: String, token: PaymentToken): Invoice =
        withContext(Dispatchers.IO) {
            val settings = chainConfig.snapshot()
            val network = settings.toNetworkConfig()
            val tokenAddress = EvmAddress.parse(token.address)
            val amount = TokenAmount.parse(displayAmount, token.decimals)
            val rpc = ReadOnlyRpcClient(network)

            val validation = rpc.validate(tokenAddress, token.decimals)
            require(validation.tokenWhitelisted) { "Token is not whitelisted by the configured vault" }

            val protocolInvoice = PaymentInvoiceFactory.create(
                network = network,
                token = tokenAddress,
                amount = amount,
                terminalIdentifier = EvmAddress.parse(settings.terminalIdentifier)
            )
            val receiver = protocolInvoice.request.receiver
            require(rpc.codeAt(receiver).isEmpty()) {
                "Derived receiver is already deployed; refusing to reuse an invoice receiver"
            }
            require(rpc.tokenBalance(tokenAddress, receiver) == BigInteger.ZERO) {
                "Derived receiver already has a token balance; refusing to reuse an invoice receiver"
            }

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

    fun observePayment(invoiceId: String): Flow<Invoice> = flow {
        var invoice = invoiceDao.getById(invoiceId)
            ?: throw IllegalArgumentException("Invoice not found")
        emit(invoice)
        if (!invoice.canMonitor()) return@flow

        val request = invoice.toPaymentRequest()
        val observer = PaymentObserver(ReadOnlyRpcClient(invoice.toNetworkConfig()))
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
            invoiceDao.updateObservation(
                invoiceId = invoice.invoiceId,
                receivedAmount = observation.observedRawUnits.toString(),
                status = status,
                firstDetectedBlock = observation.fundedAtBlock,
                lastObservedBlock = observation.blockNumber,
                confirmedAtBlock = confirmedBlock
            )
            invoice = invoice.copy(
                receivedAmount = observation.observedRawUnits.toString(),
                status = status,
                firstDetectedBlock = observation.fundedAtBlock,
                lastObservedBlock = observation.blockNumber,
                confirmedAtBlock = confirmedBlock
            )
            previous = observation
            emit(invoice)
            if (!invoice.canMonitor()) break
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun recoverOpenInvoices() = withContext(Dispatchers.IO) {
        val open = invoiceDao.getByStatuses(
            listOf(InvoiceStatus.WAITING, InvoiceStatus.PARTIAL, InvoiceStatus.CONFIRMING)
        )
        open.filter { it.hasCompleteNetworkSnapshot() }.forEach { invoice ->
            // Initial emission is the stored value; the second is the fresh RPC observation.
            withTimeoutOrNull(RECOVERY_TIMEOUT_MILLIS) {
                observePayment(invoice.invoiceId).drop(1).first()
            }
        }
    }

    fun observeRecent(limit: Int): Flow<List<Invoice>> = invoiceDao.observeRecent(limit)
    suspend fun getInvoice(invoiceId: String): Invoice? = invoiceDao.getById(invoiceId)
    suspend fun updateStatus(invoiceId: String, status: InvoiceStatus) =
        invoiceDao.updateStatus(invoiceId, status)

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
            InvoiceStatus.EXPIRED -> return null
        }
        return PaymentObservation(
            token = request.token,
            receiver = request.receiver,
            expectedAmount = request.amount,
            observedRawUnits = BigInteger(receivedAmount),
            blockNumber = lastBlock,
            fundedAtBlock = firstBlock,
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

    private fun Invoice.hasCompleteNetworkSnapshot(): Boolean =
        chainId > 0 && rpcUrl.isNotBlank() && factoryAddress.isNotBlank() &&
            receiverImplementationAddress.isNotBlank() && vaultAddress.isNotBlank()

    companion object {
        private const val POLL_INTERVAL_MILLIS = 2_000L
        private const val RECOVERY_TIMEOUT_MILLIS = 20_000L
    }
}
