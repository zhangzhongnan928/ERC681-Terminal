package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.selectedPaymentProfile
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
    val profiles: List<TerminalPaymentProfile> = emptyList(),
    val selectedProfile: TerminalPaymentProfile? = null,
    val operatorWalletReady: Boolean = false,
    val isCreating: Boolean = false,
    val createdInvoice: Invoice? = null,
    val error: String? = null,
    val repositoryFailure: String? = null,
    val readinessInvalidated: Boolean = false,
    val readinessFailureSequence: Long = 0,
    val profileSelectionSequence: Long = 0,
    val profileSelectionPending: Boolean = false,
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
        val configuration = chainConfig.snapshot()
        val profiles = configuration.resolvedPaymentProfiles()
        val selected = configuration.selectedPaymentProfile()
        val current = _createState.value
        _createState.value = current.copy(
            profiles = profiles,
            selectedProfile = selected,
            operatorWalletReady = repository.hasReadyOperatorWallet(),
            error = current.repositoryFailure,
        )
    }

    fun updateAmount(value: String) {
        val decimals = _createState.value.selectedProfile?.token?.decimals ?: return
        if (isPotentialCheckoutAmount(value, decimals)) {
            _createState.value = _createState.value.withEditedAmount(value)
        }
    }

    fun selectProfile(profile: TerminalPaymentProfile) {
        val current = _createState.value
        if (current.selectedProfile?.id == profile.id) return
        if (current.isCreating || current.profileSelectionPending) return
        _createState.value = current.copy(profileSelectionPending = true, error = null)
        viewModelScope.launch {
            try {
                val configuration = repository.selectPaymentProfile(profile.id)
                val selected = requireNotNull(configuration.selectedPaymentProfile())
                val refreshed = _createState.value
                _createState.value = refreshed.copy(
                    amount = refreshed.amount.takeIf {
                        isPotentialCheckoutAmount(it, selected.token.decimals)
                    }.orEmpty(),
                    profiles = configuration.resolvedPaymentProfiles(),
                    selectedProfile = selected,
                    error = null,
                    repositoryFailure = null,
                    profileSelectionPending = true,
                    profileSelectionSequence = refreshed.profileSelectionSequence + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _createState.value = _createState.value.copy(
                    error = error.message ?: "Unable to select payment profile",
                    profileSelectionPending = false,
                )
            }
        }
    }

    fun createInvoice() {
        val state = _createState.value
        val selectedProfile = state.selectedProfile ?: run {
            _createState.value = state.copy(error = "Select a payment profile.")
            return
        }
        if (state.profileSelectionPending) {
            _createState.value = state.copy(error = "Wait for currency validation to finish.")
            return
        }
        val token = selectedProfile.token
        if (!isSubmittableCheckoutAmount(state.amount, token.decimals)) {
            _createState.value = state.copy(
                error = "Enter an amount greater than zero with no more than ${token.decimals} decimal places.",
            )
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
        _createState.value = state.copy(
            isCreating = true,
            error = null,
            repositoryFailure = null,
        )
        viewModelScope.launch {
            try {
                val invoice = repository.createInvoice(state.amount, selectedProfile.id)
                _createState.value = _createState.value.copy(
                    isCreating = false,
                    createdInvoice = invoice
                )
            } catch (error: Exception) {
                _createState.value = _createState.value.withRepositoryFailure(
                    error.message ?: "Invoice validation failed.",
                )
            }
        }
    }

    fun completeReadinessRefresh(ready: Boolean) {
        _createState.value = _createState.value.copy(
            profileSelectionPending = false,
        ).afterReadinessRefresh(ready)
    }

    fun consumeCreatedInvoice() {
        _createState.value = _createState.value.copy(
            amount = "",
            createdInvoice = null,
            repositoryFailure = null,
            readinessInvalidated = false,
        )
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

internal fun CreateInvoiceState.withEditedAmount(value: String): CreateInvoiceState = copy(
    amount = value,
    error = repositoryFailure,
)

internal fun CreateInvoiceState.withRepositoryFailure(message: String): CreateInvoiceState = copy(
    isCreating = false,
    error = message,
    repositoryFailure = message,
    readinessInvalidated = true,
    readinessFailureSequence = readinessFailureSequence + 1,
)

internal fun CreateInvoiceState.afterReadinessRefresh(ready: Boolean): CreateInvoiceState =
    if (readinessInvalidated && ready) {
        copy(
            error = null,
            repositoryFailure = null,
            readinessInvalidated = false,
        )
    } else {
        this
    }
