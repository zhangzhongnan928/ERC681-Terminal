package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.repository.PreparedSettlement
import com.openpasskey.terminal.data.repository.SettlementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettlementUiState(
    val readyInvoices: List<Invoice> = emptyList(),
    val recentTransactions: List<SettlementTransaction> = emptyList(),
    val preparing: Boolean = false,
    val submitting: Boolean = false,
    val prepared: PreparedSettlement? = null,
    val message: String? = null,
    val isError: Boolean = false
)

class SettlementViewModel(private val repository: SettlementRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettlementUiState())
    val state: StateFlow<SettlementUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeReadyInvoices().collect { invoices ->
                _state.value = _state.value.copy(readyInvoices = invoices)
            }
        }
        viewModelScope.launch {
            repository.observeRecentTransactions().collect { transactions ->
                _state.value = _state.value.copy(recentTransactions = transactions)
            }
        }
        viewModelScope.launch { repository.recoverPending() }
    }

    fun prepare(invoiceIds: List<String>) {
        if (_state.value.preparing || _state.value.submitting) return
        _state.value = _state.value.copy(
            preparing = true,
            prepared = null,
            message = null,
            isError = false
        )
        viewModelScope.launch {
            try {
                val prepared = repository.prepare(invoiceIds)
                _state.value = _state.value.copy(preparing = false, prepared = prepared)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    preparing = false,
                    message = error.message ?: "Settlement preflight failed",
                    isError = true
                )
            }
        }
    }

    fun dismissReview() {
        if (!_state.value.submitting) _state.value = _state.value.copy(prepared = null)
    }

    /** Called only from the OS authentication success callback. */
    fun submitAuthenticated() {
        val prepared = _state.value.prepared ?: return
        if (_state.value.submitting) return
        _state.value = _state.value.copy(submitting = true, message = null, isError = false)
        viewModelScope.launch {
            try {
                val transaction = repository.submit(prepared, userExplicitlyConfirmed = true)
                _state.value = _state.value.copy(
                    submitting = false,
                    prepared = null,
                    message = when (transaction.status.name) {
                        "VERIFIED" -> "Settlement verified on-chain."
                        else -> "Transaction ${transaction.status.name.lowercase().replace('_', ' ')}."
                    }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    message = error.message ?: "Settlement submission failed",
                    isError = true
                )
            }
        }
    }

    fun authenticationFailed(message: String) {
        _state.value = _state.value.copy(message = "Authentication failed: $message", isError = true)
    }

    class Factory(private val repository: SettlementRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettlementViewModel(repository) as T
    }
}
