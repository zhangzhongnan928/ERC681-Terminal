package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.repository.PreparedSettlement
import com.openpasskey.terminal.data.repository.SettlementRepository
import com.openpasskey.terminal.rpc.RpcInteractiveReservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettlementUiState(
    val readyInvoices: List<Invoice> = emptyList(),
    val recentTransactions: List<SettlementTransaction> = emptyList(),
    val preparing: Boolean = false,
    val preparingAuthentication: Boolean = false,
    val submitting: Boolean = false,
    val prepared: PreparedSettlement? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val authenticationRequestSequence: Long = 0,
)

class SettlementViewModel(private val repository: SettlementRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettlementUiState())
    val state: StateFlow<SettlementUiState> = _state.asStateFlow()
    private var recoveryInFlight = false
    private var lastAuthenticationPromptedSequence = 0L
    private var authenticationReservation: RpcInteractiveReservation? = null

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
        viewModelScope.launch {
            while (true) {
                recoverPending(reportError = false)
                delay(RECOVERY_INTERVAL_MILLIS)
            }
        }
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
        if (!_state.value.submitting && !_state.value.preparingAuthentication) {
            releaseAuthenticationReservation()
            _state.value = _state.value.copy(prepared = null)
        }
    }

    /** Runs slow live settlement checks before opening the 30-second Keystore auth window. */
    fun prepareForAuthentication() {
        val reviewed = _state.value.prepared ?: return
        if (_state.value.preparingAuthentication || _state.value.submitting) return
        releaseAuthenticationReservation()
        authenticationReservation = repository.reserveAuthenticationWindow()
        _state.value = _state.value.copy(
            preparingAuthentication = true,
            message = null,
            isError = false,
        )
        viewModelScope.launch {
            try {
                val fresh = repository.prepareForAuthentication(reviewed)
                _state.value = _state.value.copy(
                    preparingAuthentication = false,
                    prepared = fresh,
                    authenticationRequestSequence = _state.value.authenticationRequestSequence + 1,
                )
            } catch (error: CancellationException) {
                releaseAuthenticationReservation()
                throw error
            } catch (error: Exception) {
                releaseAuthenticationReservation()
                _state.value = _state.value.copy(
                    preparingAuthentication = false,
                    message = error.message ?: "Settlement pre-authentication check failed",
                    isError = true,
                )
            }
        }
    }

    /** One-shot ownership prevents rotation/recomposition from presenting the same prompt twice. */
    fun beginAuthenticationPrompt(sequence: Long): Boolean {
        if (sequence <= 0 || sequence != _state.value.authenticationRequestSequence) return false
        if (sequence <= lastAuthenticationPromptedSequence) return false
        lastAuthenticationPromptedSequence = sequence
        return true
    }

    /** Called only from the OS authentication success callback. */
    fun submitAuthenticated() {
        val prepared = _state.value.prepared ?: run {
            releaseAuthenticationReservation()
            return
        }
        if (_state.value.submitting) return
        val authenticatedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(submitting = true, message = null, isError = false)
        viewModelScope.launch {
            try {
                val transaction = repository.submit(
                    reviewed = prepared,
                    userExplicitlyConfirmed = true,
                    authenticatedAtElapsedRealtimeMillis = authenticatedAtElapsedRealtimeMillis,
                )
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
            } finally {
                releaseAuthenticationReservation()
            }
        }
    }

    fun authenticationFailed(message: String) {
        releaseAuthenticationReservation()
        _state.value = _state.value.copy(message = "Authentication failed: $message", isError = true)
    }

    fun refreshPending() {
        viewModelScope.launch { recoverPending(reportError = true) }
    }

    private suspend fun recoverPending(reportError: Boolean) {
        if (recoveryInFlight) return
        recoveryInFlight = true
        try {
            repository.recoverPending()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (reportError) {
                _state.value = _state.value.copy(
                    message = error.message ?: "Settlement recovery failed",
                    isError = true,
                )
            }
        } finally {
            recoveryInFlight = false
        }
    }

    override fun onCleared() {
        releaseAuthenticationReservation()
        super.onCleared()
    }

    private fun releaseAuthenticationReservation() {
        authenticationReservation?.close()
        authenticationReservation = null
    }

    class Factory(private val repository: SettlementRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettlementViewModel(repository) as T
    }

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 60_000L
    }
}
