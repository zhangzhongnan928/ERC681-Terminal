package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.repository.InvoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateInvoiceState(
    val amount: String = "",
    val tokens: List<PaymentToken> = emptyList(),
    val selectedToken: PaymentToken? = null,
    val operatorWalletReady: Boolean = false,
    val isCreating: Boolean = false,
    val createdInvoice: Invoice? = null,
    val error: String? = null
)

data class PaymentUiState(val invoice: Invoice? = null, val error: String? = null)

class InvoiceViewModel(
    private val repository: InvoiceRepository,
    private val chainConfig: ChainConfig
) : ViewModel() {
    private val _createState = MutableStateFlow(CreateInvoiceState())
    val createState: StateFlow<CreateInvoiceState> = _createState.asStateFlow()

    private val _paymentState = MutableStateFlow(PaymentUiState())
    val paymentState: StateFlow<PaymentUiState> = _paymentState.asStateFlow()

    private val _recentInvoices = MutableStateFlow<List<Invoice>>(emptyList())
    val recentInvoices: StateFlow<List<Invoice>> = _recentInvoices.asStateFlow()

    private var monitorJob: Job? = null

    init {
        refreshConfiguration()
        viewModelScope.launch {
            repository.observeRecent(100).collect { _recentInvoices.value = it }
        }
        viewModelScope.launch {
            while (true) {
                try {
                    repository.reconcileLateInvoices()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The next bounded pass retries unreachable RPCs and rotates independently
                    // from recovery of open invoices.
                }
                delay(RECOVERY_INTERVAL_MILLIS)
            }
        }
        viewModelScope.launch {
            while (true) {
                try {
                    repository.recoverOpenInvoices()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Open-invoice recovery is best-effort and cannot delay the late-payment loop.
                }
                delay(RECOVERY_INTERVAL_MILLIS)
            }
        }
    }

    fun refreshConfiguration() {
        val tokens = chainConfig.paymentTokens
        val selected = _createState.value.selectedToken?.let { current ->
            tokens.firstOrNull { it.address.equals(current.address, ignoreCase = true) }
        }
        _createState.value = _createState.value.copy(
            tokens = tokens,
            selectedToken = selected ?: tokens.firstOrNull(),
            operatorWalletReady = repository.hasReadyOperatorWallet(),
            error = null
        )
    }

    fun updateAmount(value: String) {
        if (value.isEmpty() || value.matches(Regex("^(0|[1-9][0-9]*)(\\.[0-9]*)?$"))) {
            _createState.value = _createState.value.copy(amount = value, error = null)
        }
    }

    fun selectToken(token: PaymentToken) {
        _createState.value = _createState.value.copy(selectedToken = token, error = null)
    }

    fun createInvoice() {
        val state = _createState.value
        val token = state.selectedToken ?: run {
            _createState.value = state.copy(error = "Select a payment token.")
            return
        }
        if (!chainConfig.isConfigured()) {
            _createState.value = state.copy(error = "Complete Settings before creating a payment.")
            return
        }
        if (!repository.hasReadyOperatorWallet()) {
            _createState.value = state.copy(
                operatorWalletReady = false,
                error = "Create the terminal operator wallet in Settings before creating a payment QR."
            )
            return
        }
        _createState.value = state.copy(isCreating = true, error = null)
        viewModelScope.launch {
            try {
                val invoice = repository.createInvoice(state.amount, token)
                _createState.value = _createState.value.copy(
                    isCreating = false,
                    createdInvoice = invoice
                )
            } catch (error: Exception) {
                _createState.value = _createState.value.copy(
                    isCreating = false,
                    error = error.message ?: "Invoice validation failed."
                )
            }
        }
    }

    fun consumeCreatedInvoice() {
        _createState.value = _createState.value.copy(amount = "", createdInvoice = null)
    }

    fun startPaymentMonitoring(invoiceId: String) {
        stopPaymentMonitoring()
        _paymentState.value = PaymentUiState()
        monitorJob = viewModelScope.launch {
            try {
                repository.observePayment(invoiceId).collect { invoice ->
                    _paymentState.value = PaymentUiState(invoice = invoice)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val stored = repository.getInvoice(invoiceId)
                _paymentState.value = PaymentUiState(stored, error.message ?: "Read-only RPC failed")
            }
        }
    }

    fun stopPaymentMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun cancelInvoice() {
        val invoiceId = _paymentState.value.invoice?.invoiceId ?: return
        viewModelScope.launch { repository.updateStatus(invoiceId, InvoiceStatus.EXPIRED) }
        stopPaymentMonitoring()
    }

    override fun onCleared() {
        stopPaymentMonitoring()
        super.onCleared()
    }

    class Factory(
        private val repository: InvoiceRepository,
        private val chainConfig: ChainConfig
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InvoiceViewModel(repository, chainConfig) as T
    }

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 30_000L
    }
}
