package com.openpasskey.terminal.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.receiptPrintFingerprint
import com.openpasskey.terminal.data.repository.InvoiceRepository
import com.openpasskey.terminal.printing.ReceiptCoordinator
import com.openpasskey.terminal.printing.ReceiptRequestResult
import com.openpasskey.terminal.rpc.safeReadRpcFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

data class ReceiptUiState(
    val printingInvoiceId: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val sequence: Long = 0,
)

class InvoiceViewModel(
    private val repository: InvoiceRepository,
    private val chainConfig: ChainConfig,
    private val receiptCoordinator: ReceiptCoordinator,
) : ViewModel() {
    private val _createState = MutableStateFlow(CreateInvoiceState())
    val createState: StateFlow<CreateInvoiceState> = _createState.asStateFlow()

    private val _paymentState = MutableStateFlow(PaymentUiState())
    val paymentState: StateFlow<PaymentUiState> = _paymentState.asStateFlow()

    private val _recentInvoices = MutableStateFlow<List<Invoice>>(emptyList())
    val recentInvoices: StateFlow<List<Invoice>> = _recentInvoices.asStateFlow()

    private val _receiptState = MutableStateFlow(ReceiptUiState())
    val receiptState: StateFlow<ReceiptUiState> = _receiptState.asStateFlow()

    private val pendingAutoReceipts = MutableStateFlow<List<Invoice>>(emptyList())
    /** Canonical snapshots proven unsupported before any physical print could be submitted. */
    private val unsupportedAutoPrintFingerprints = mutableSetOf<String>()
    private val autoPrintRetryStates = mutableMapOf<String, AutoReceiptRetryState>()
    /** Rechecks suppression/backoff after queued attempts acquire serialization ownership. */
    private val automaticPrintMutex = Mutex()

    private var monitorJob: Job? = null
    private var promptAutoPrintRetryJob: Job? = null

    init {
        refreshConfiguration()
        viewModelScope.launch {
            repository.observeReceiptHistory().collect { _recentInvoices.value = it }
        }
        viewModelScope.launch {
            repository.observePendingAutoReceipts().collect { pending ->
                pendingAutoReceipts.value = pending
                val liveFingerprints = pending.mapTo(mutableSetOf(), Invoice::autoReceiptFingerprint)
                unsupportedAutoPrintFingerprints.retainAll(liveFingerprints)
                autoPrintRetryStates.keys.retainAll(liveFingerprints)
                attemptNextAutomaticReceipt()
            }
        }
        viewModelScope.launch {
            repository.observeUnprintedReceiptSnapshots().collect { snapshots ->
                // Claim liveness is independent of current print eligibility. In particular,
                // transient CONFIRMING must retain the exact claim that can later return to PAID.
                val liveFingerprints = unprintedReceiptFingerprints(snapshots)
                // A failed prune leaves extra markers and therefore fails safe by suppressing.
                runCatching { chainConfig.retainOnly(liveFingerprints) }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(AUTO_PRINT_RETRY_MILLIS)
                attemptNextAutomaticReceipt()
            }
        }
        viewModelScope.launch {
            delay(RECOVERY_STAGGER_MILLIS)
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
        _createState.value = _createState.value.afterConfigurationRefresh(
            configuration = configuration,
            operatorWalletReady = repository.hasReadyOperatorWallet(),
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
                _createState.value = _createState.value.afterProfileSelection(configuration)
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
                    safeReadRpcFailureMessage(error, "Invoice validation failed."),
                )
            }
        }
    }

    internal fun completeReadinessRefresh(result: ReadinessRefreshResult) {
        // A preserved result keeps checkout open through SettingsState, but it is not a fresh
        // proof: only FRESH_READY may clear a live invoice-creation failure.
        _createState.value = _createState.value.afterReadinessRefresh(result.clearsInvoiceFailure())
    }

    internal fun completeProfileSelectionReadinessRefresh(
        sequence: Long,
        profileId: String,
        result: ReadinessRefreshResult,
    ) {
        _createState.value = _createState.value.afterProfileSelectionReadinessRefresh(
            sequence = sequence,
            profileId = profileId,
            ready = result.clearsInvoiceFailure(),
        )
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
                _paymentState.value = PaymentUiState(
                    stored,
                    safeReadRpcFailureMessage(error, "Read-only RPC failed"),
                )
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

    fun reprintReceipt(invoiceId: String) {
        if (_receiptState.value.printingInvoiceId != null) return
        _receiptState.value = _receiptState.value.copy(
            printingInvoiceId = invoiceId,
            message = null,
            isError = false,
        )
        viewModelScope.launch { printReceiptInteractively(invoiceId) }
    }

    fun consumeReceiptMessage(sequence: Long) {
        val current = _receiptState.value
        if (current.sequence == sequence) {
            _receiptState.value = current.copy(message = null)
        }
    }

    private suspend fun attemptNextAutomaticReceipt() {
        val now = SystemClock.elapsedRealtime()
        val invoice = selectAutomaticReceiptCandidate(
            pending = pendingAutoReceipts.value,
            suppressedFingerprints = currentAutoPrintSuppressions(),
            retryStates = autoPrintRetryStates,
            nowElapsedRealtimeMillis = now,
        ) ?: return
        printReceiptAutomatically(invoice)
    }

    private suspend fun printReceiptAutomatically(queuedInvoice: Invoice) {
        automaticPrintMutex.withLock {
            val fingerprint = queuedInvoice.autoReceiptFingerprint()
            val currentFingerprint = pendingAutoReceipts.value
                .firstOrNull { it.invoiceId == queuedInvoice.invoiceId }
                ?.autoReceiptFingerprint()
            val retryAt = autoPrintRetryStates[fingerprint]?.retryAfterElapsedRealtimeMillis ?: 0L
            // This check is intentionally inside serialization. An already-queued duplicate can
            // never reach the printer after an earlier attempt becomes ambiguous or unsupported.
            if (!automaticReceiptAttemptAllowed(
                    queuedFingerprint = fingerprint,
                    currentFingerprint = currentFingerprint,
                    suppressedFingerprints = currentAutoPrintSuppressions(),
                    retryAfterElapsedRealtimeMillis = retryAt,
                    nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                )
            ) return@withLock

            val current = _receiptState.value
            if (current.printingInvoiceId == null) {
                _receiptState.value = current.copy(printingInvoiceId = queuedInvoice.invoiceId)
            }
            val result = requestReceipt(queuedInvoice.invoiceId, automatic = true)
            when (result) {
                is ReceiptRequestResult.Printed,
                ReceiptRequestResult.AlreadyPrinted -> {
                    autoPrintRetryStates.remove(fingerprint)
                    unsupportedAutoPrintFingerprints.remove(fingerprint)
                }
                is ReceiptRequestResult.AutomaticSuppressed ->
                    autoPrintRetryStates.remove(fingerprint)
                is ReceiptRequestResult.Failed -> {
                    if (result.retryAutomatically) {
                        scheduleAutomaticReceiptRetry(fingerprint)
                    } else {
                        autoPrintRetryStates.remove(fingerprint)
                    }
                }
                is ReceiptRequestResult.Unavailable -> {
                    if (result.retryAutomatically) {
                        scheduleAutomaticReceiptRetry(
                            fingerprint = fingerprint,
                            promptly = result.retryPromptly,
                        )
                    } else {
                        autoPrintRetryStates.remove(fingerprint)
                        unsupportedAutoPrintFingerprints += fingerprint
                    }
                }
            }
            publishReceiptResult(queuedInvoice.invoiceId, result, automatic = true)
        }
    }

    private suspend fun printReceiptInteractively(invoiceId: String) {
        val result = requestReceipt(invoiceId, automatic = false)
        if (result is ReceiptRequestResult.Printed) {
            repository.getInvoice(invoiceId)?.autoReceiptFingerprint()?.let { fingerprint ->
                autoPrintRetryStates.remove(fingerprint)
                unsupportedAutoPrintFingerprints.remove(fingerprint)
            }
        }
        publishReceiptResult(invoiceId, result, automatic = false)
    }

    private suspend fun requestReceipt(
        invoiceId: String,
        automatic: Boolean,
    ): ReceiptRequestResult {
        val result = try {
            receiptCoordinator.print(invoiceId, automatic)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The exception boundary cannot prove whether a submitted physical job completed.
            ReceiptRequestResult.Failed(
                message = error.message ?: "Receipt printing failed.",
                retryAutomatically = false,
            )
        }
        return result
    }

    private fun publishReceiptResult(
        invoiceId: String,
        result: ReceiptRequestResult,
        automatic: Boolean,
    ) {
        if (
            automatic &&
            (result == ReceiptRequestResult.AlreadyPrinted ||
                result is ReceiptRequestResult.Unavailable && result.retryPromptly)
        ) {
            val current = _receiptState.value
            if (current.printingInvoiceId == invoiceId) {
                _receiptState.value = current.copy(printingInvoiceId = null)
            }
            return
        }
        val (message, isError) = when (result) {
            is ReceiptRequestResult.Printed ->
                (if (result.wasReprint) "Receipt reprinted." else "Receipt printed.") to false
            ReceiptRequestResult.AlreadyPrinted -> "Receipt was already printed." to false
            is ReceiptRequestResult.AutomaticSuppressed -> result.message to true
            is ReceiptRequestResult.Unavailable -> result.message to true
            is ReceiptRequestResult.Failed -> result.message to true
        }
        val current = _receiptState.value
        _receiptState.value = current.copy(
            printingInvoiceId = null,
            message = message,
            isError = isError,
            sequence = current.sequence + 1,
        )
    }

    private fun scheduleAutomaticReceiptRetry(
        fingerprint: String,
        promptly: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        autoPrintRetryStates[fingerprint] = if (promptly) {
            promptAutoReceiptRetryState(
                nowElapsedRealtimeMillis = now,
                retryDelayMillis = PROMPT_AUTO_PRINT_RETRY_MILLIS,
            )
        } else {
            nextAutoReceiptRetryState(
                previous = autoPrintRetryStates[fingerprint],
                nowElapsedRealtimeMillis = now,
                baseDelayMillis = AUTO_PRINT_RETRY_MILLIS,
                maximumDelayMillis = MAX_AUTO_PRINT_RETRY_MILLIS,
                maximumBackoffSteps = MAX_AUTO_PRINT_BACKOFF_STEPS,
            )
        }
        if (promptly) {
            // A settlement/authentication reservation can end at any moment. Do not wait for the
            // 30-second printer-recovery cadence when no physical print was submitted.
            promptAutoPrintRetryJob?.cancel()
            promptAutoPrintRetryJob = viewModelScope.launch {
                delay(PROMPT_AUTO_PRINT_RETRY_MILLIS)
                attemptNextAutomaticReceipt()
            }
        }
    }

    private fun currentAutoPrintSuppressions(): Set<String> =
        unsupportedAutoPrintFingerprints + runCatching { chainConfig.claims() }.getOrElse {
            // Reading durable safety state failed. Returning every current fingerprint prevents
            // any automatic print until storage is readable again.
            pendingAutoReceipts.value.mapTo(mutableSetOf(), Invoice::autoReceiptFingerprint)
        }

    override fun onCleared() {
        stopPaymentMonitoring()
        promptAutoPrintRetryJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repository: InvoiceRepository,
        private val chainConfig: ChainConfig,
        private val receiptCoordinator: ReceiptCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InvoiceViewModel(repository, chainConfig, receiptCoordinator) as T
    }

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 60_000L
        const val RECOVERY_STAGGER_MILLIS = 30_000L
        const val PROMPT_AUTO_PRINT_RETRY_MILLIS = 1_000L
        const val AUTO_PRINT_RETRY_MILLIS = 30_000L
        const val MAX_AUTO_PRINT_RETRY_MILLIS = 30 * 60_000L
        const val MAX_AUTO_PRINT_BACKOFF_STEPS = 7
    }
}

internal data class AutoReceiptRetryState(
    val failureCount: Int,
    val retryAfterElapsedRealtimeMillis: Long,
)

internal fun promptAutoReceiptRetryState(
    nowElapsedRealtimeMillis: Long,
    retryDelayMillis: Long,
): AutoReceiptRetryState {
    require(nowElapsedRealtimeMillis >= 0)
    require(retryDelayMillis > 0)
    return AutoReceiptRetryState(
        failureCount = 0,
        retryAfterElapsedRealtimeMillis = Math.addExact(
            nowElapsedRealtimeMillis,
            retryDelayMillis,
        ),
    )
}

internal fun selectAutomaticReceiptCandidate(
    pending: List<Invoice>,
    suppressedFingerprints: Set<String>,
    retryStates: Map<String, AutoReceiptRetryState>,
    nowElapsedRealtimeMillis: Long,
): Invoice? = pending.firstOrNull { candidate ->
    val fingerprint = candidate.autoReceiptFingerprint()
    automaticReceiptAttemptAllowed(
        queuedFingerprint = fingerprint,
        currentFingerprint = fingerprint,
        suppressedFingerprints = suppressedFingerprints,
        retryAfterElapsedRealtimeMillis = retryStates[fingerprint]
            ?.retryAfterElapsedRealtimeMillis
            ?: 0L,
        nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
    )
}

internal fun automaticReceiptAttemptAllowed(
    queuedFingerprint: String,
    currentFingerprint: String?,
    suppressedFingerprints: Set<String>,
    retryAfterElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
): Boolean = currentFingerprint == queuedFingerprint &&
    queuedFingerprint !in suppressedFingerprints &&
    retryAfterElapsedRealtimeMillis <= nowElapsedRealtimeMillis

internal fun nextAutoReceiptRetryState(
    previous: AutoReceiptRetryState?,
    nowElapsedRealtimeMillis: Long,
    baseDelayMillis: Long,
    maximumDelayMillis: Long,
    maximumBackoffSteps: Int,
): AutoReceiptRetryState {
    require(nowElapsedRealtimeMillis >= 0)
    require(baseDelayMillis > 0 && maximumDelayMillis >= baseDelayMillis)
    require(maximumBackoffSteps in 1..62)
    val failureCount = ((previous?.failureCount ?: 0) + 1).coerceAtMost(maximumBackoffSteps)
    val shift = (failureCount - 1).coerceAtMost(62)
    val delay = runCatching { Math.multiplyExact(baseDelayMillis, 1L shl shift) }
        .getOrDefault(maximumDelayMillis)
        .coerceAtMost(maximumDelayMillis)
    return AutoReceiptRetryState(
        failureCount = failureCount,
        retryAfterElapsedRealtimeMillis = Math.addExact(nowElapsedRealtimeMillis, delay),
    )
}

/** Changes when the canonical payment snapshot changes, allowing a corrected proof to retry. */
internal fun Invoice.autoReceiptFingerprint(): String = receiptPrintFingerprint()

internal fun unprintedReceiptFingerprints(snapshots: List<Invoice>): Set<String> = snapshots
    .asSequence()
    .filter { invoice ->
        invoice.receiptAutoPrintEligible && invoice.receiptNumber > 0 &&
            invoice.receiptPrintedAt == null
    }
    .mapTo(mutableSetOf(), Invoice::autoReceiptFingerprint)

internal fun CreateInvoiceState.withEditedAmount(value: String): CreateInvoiceState = copy(
    amount = value,
    error = repositoryFailure,
)

/**
 * Synchronizes checkout with the authoritative catalog. A pending selection remains owned by its
 * readiness callback only while that exact profile is still selected. Removal or an admin-side
 * reselection supersedes the operation, so it is safe to release pending without trusting any
 * generic or stale readiness result.
 */
internal fun CreateInvoiceState.afterConfigurationRefresh(
    configuration: TerminalConfigSnapshot,
    operatorWalletReady: Boolean,
): CreateInvoiceState {
    val profiles = configuration.resolvedPaymentProfiles()
    val selected = configuration.selectedPaymentProfile()
    val selectionChanged = selectedProfile?.id != selected?.id
    return copy(
        amount = if (selectionChanged) "" else amount,
        profiles = profiles,
        selectedProfile = selected,
        operatorWalletReady = operatorWalletReady,
        error = repositoryFailure,
        profileSelectionPending = profileSelectionPending && !selectionChanged,
    )
}

/**
 * A profile change always changes the sale's currency context, even when the two tokens happen to
 * use the same decimals. Clear the entered amount so a cashier must deliberately enter it again.
 */
internal fun CreateInvoiceState.afterProfileSelection(
    configuration: TerminalConfigSnapshot,
): CreateInvoiceState = copy(
    amount = "",
    profiles = configuration.resolvedPaymentProfiles(),
    selectedProfile = requireNotNull(configuration.selectedPaymentProfile()),
    error = null,
    repositoryFailure = null,
    profileSelectionPending = true,
    profileSelectionSequence = profileSelectionSequence + 1,
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

/** Only the readiness pass started for this exact selection may release checkout. */
internal fun CreateInvoiceState.afterProfileSelectionReadinessRefresh(
    sequence: Long,
    profileId: String,
    ready: Boolean,
): CreateInvoiceState {
    if (!profileSelectionPending ||
        profileSelectionSequence != sequence ||
        selectedProfile?.id != profileId
    ) {
        return this
    }
    return copy(profileSelectionPending = false).afterReadinessRefresh(ready)
}
