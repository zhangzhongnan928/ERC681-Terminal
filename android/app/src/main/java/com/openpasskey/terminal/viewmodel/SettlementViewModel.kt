package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.model.SettlementTransactionStatus
import com.openpasskey.terminal.data.repository.PreparedSettlement
import com.openpasskey.terminal.data.repository.SettlementRepository
import com.openpasskey.terminal.data.repository.settlementHasRequiredConfirmationDepth
import com.openpasskey.terminal.rpc.RpcInteractiveReservation
import com.openpasskey.terminal.rpc.safeReadRpcFailureMessage
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
    val autoSweepEnabled: Boolean = false,
    val preparedAutomatically: Boolean = false,
    val preparedAutoSweepFingerprint: String? = null,
    val autoSweepReviewSequence: Long = 0,
    val autoSweepMessage: Boolean = false,
    val autoSweepSafetyDisableSequence: Long = 0,
)

internal data class AutoSweepCandidate(
    val invoiceId: String,
    val fingerprint: String,
)

private data class AutoSweepConfirmationCursor(
    val thresholdBlock: Long?,
    val thresholdHash: String?,
    val confirmedBlock: Long?,
    val observedAmount: BigInteger,
)

/** Main-thread one-shot ownership for a monotonically increasing UI request sequence. */
internal class OneShotSequenceGate {
    private var lastClaimedSequence = 0L

    fun claim(requestedSequence: Long, currentSequence: Long): Boolean {
        if (requestedSequence <= 0 || requestedSequence != currentSequence) return false
        if (requestedSequence <= lastClaimedSequence) return false
        lastClaimedSequence = requestedSequence
        return true
    }
}

/** Invalidates completions from an auto-preflight cancelled by a preference transition. */
internal class AutoSweepAttemptGate {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(attempt: Long): Boolean = attempt == generation
}

internal fun SettlementUiState.withAutoSweepDisabled(
    cancelledAutomaticPreparation: Boolean,
): SettlementUiState {
    val discardAutomaticReview = preparedAutomatically && !preparingAuthentication && !submitting
    val clearAutoOwnedState = cancelledAutomaticPreparation || discardAutomaticReview ||
        autoSweepMessage
    return copy(
        autoSweepEnabled = false,
        preparing = if (cancelledAutomaticPreparation || discardAutomaticReview) false else preparing,
        prepared = if (discardAutomaticReview) null else prepared,
        preparedAutomatically = if (discardAutomaticReview) false else preparedAutomatically,
        preparedAutoSweepFingerprint = if (clearAutoOwnedState) {
            null
        } else {
            preparedAutoSweepFingerprint
        },
        message = if (clearAutoOwnedState) null else message,
        isError = if (clearAutoOwnedState) false else isError,
        autoSweepMessage = if (clearAutoOwnedState) false else autoSweepMessage,
    )
}

internal fun SettlementUiState.autoSweepFingerprintToSuppressOnDismiss(): String? =
    preparedAutoSweepFingerprint.takeIf { preparedAutomatically }

internal fun SettlementUiState.withAutoSweepDismissalCapacityFailure(): SettlementUiState = copy(
    autoSweepEnabled = false,
    preparing = false,
    prepared = null,
    preparedAutomatically = false,
    preparedAutoSweepFingerprint = null,
    message = "Auto-sweep was turned off because its dismissal history is full. " +
        "Explicitly re-enable it in Settings to start a new review session.",
    isError = true,
    autoSweepMessage = false,
    autoSweepSafetyDisableSequence = autoSweepSafetyDisableSequence + 1,
)

/** Auto-start excludes migrated rows and later payments without their own incoming evidence. */
internal fun selectAutoSweepCandidate(
    invoices: List<Invoice>,
    suppressedFingerprints: Set<String> = emptySet(),
): AutoSweepCandidate? = invoices.asSequence()
    .mapNotNull { invoice ->
        invoice.autoSweepFingerprint()?.let { fingerprint ->
            AutoSweepCandidate(invoice.invoiceId, fingerprint)
        }
    }
    .firstOrNull { it.fingerprint !in suppressedFingerprints }

internal fun deferredAutoSweepFingerprints(
    retryAfterElapsedRealtimeMillis: Map<String, Long>,
    nowElapsedRealtimeMillis: Long,
): Set<String> = retryAfterElapsedRealtimeMillis
    .filterValues { retryAt -> retryAt > nowElapsedRealtimeMillis }
    .keys

internal fun Invoice.autoSweepFingerprint(): String? {
    if (settlementId != null || !hasPersistedAutoSweepPaymentEvidence()) return null
    if (confirmationBlocks !in 1..64) return null
    val cursor = when (status) {
        InvoiceStatus.PAID,
        InvoiceStatus.OVERPAID -> {
            if (settledTxHash != null) return null
            val observed = positiveBigInteger(receivedAmount) ?: return null
            val expected = positiveBigInteger(expectedAmount) ?: return null
            if (observed < expected) return null
            AutoSweepConfirmationCursor(
                thresholdBlock = firstDetectedBlock,
                thresholdHash = firstDetectedBlockHash,
                confirmedBlock = confirmedAtBlock,
                observedAmount = observed,
            )
        }
        // The current durable evidence identifies only the original consumer payment. A late
        // payment stays on the exact manual settlement path until it has its own transaction proof.
        InvoiceStatus.LATE_PAYMENT_READY -> return null
        else -> return null
    }
    val thresholdBlock = cursor.thresholdBlock ?: return null
    val thresholdHash = cursor.thresholdHash ?: return null
    val confirmationHead = cursor.confirmedBlock ?: return null
    if (!CANONICAL_HASH.matches(thresholdHash)) return null
    if (!settlementHasRequiredConfirmationDepth(
            receiptBlock = thresholdBlock,
            requiredConfirmations = confirmationBlocks,
            canonicalHead = confirmationHead,
        )
    ) return null
    return listOf(
        invoiceId.lowercase(),
        status.name,
        thresholdBlock.toString(),
        thresholdHash.lowercase(),
        cursor.observedAmount.toString(),
        requireNotNull(paymentTxHash).lowercase(),
        requireNotNull(paymentPayerAddress).lowercase(),
        requireNotNull(paymentBlockNumber).toString(),
        requireNotNull(paymentBlockHash).lowercase(),
    ).joinToString("|")
}

private fun Invoice.hasPersistedAutoSweepPaymentEvidence(): Boolean {
    if (!receiptAutoPrintEligible || receiptNumber <= 0 || paidAt == null || paidAt <= 0) {
        return false
    }
    if (chainId !in SUPPORTED_BASE_CHAIN_IDS) return false
    val publishedBlock = publishedAtBlock ?: return false
    val primaryThresholdBlock = firstDetectedBlock ?: return false
    val paymentBlock = paymentBlockNumber ?: return false
    val payer = paymentPayerAddress ?: return false
    if (publishedBlock < 0 || primaryThresholdBlock <= publishedBlock ||
        paymentBlock <= publishedBlock || paymentBlock > primaryThresholdBlock
    ) return false
    return CANONICAL_HASH.matches(publishedAtBlockHash.orEmpty()) &&
        CANONICAL_HASH.matches(firstDetectedBlockHash.orEmpty()) &&
        CANONICAL_HASH.matches(paymentTxHash.orEmpty()) &&
        CANONICAL_HASH.matches(paymentBlockHash.orEmpty()) &&
        CANONICAL_ADDRESS.matches(payer) && !payer.equals(ZERO_ADDRESS, ignoreCase = true)
}

private fun positiveBigInteger(raw: String): BigInteger? = runCatching { BigInteger(raw) }
    .getOrNull()
    ?.takeIf { it.signum() > 0 }

private val CANONICAL_HASH = Regex("^0x[0-9a-fA-F]{64}$")
private val CANONICAL_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
private val SUPPORTED_BASE_CHAIN_IDS = setOf(8_453L, 84_532L)

class SettlementViewModel(
    private val repository: SettlementRepository,
    private val chainConfig: ChainConfig? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SettlementUiState())
    val state: StateFlow<SettlementUiState> = _state.asStateFlow()
    private var recoveryInFlight = false
    private var lastAuthenticationPromptedSequence = 0L
    private var authenticationReservation: RpcInteractiveReservation? = null
    private var autoSweepJob: Job? = null
    private val autoSweepAttemptGate = AutoSweepAttemptGate()
    private var autoSweepEnabled = false
    private var autoSweepPreferenceInitialized = false
    private val autoSweepReviewNavigationGate = OneShotSequenceGate()
    private val suppressedAutoSweepFingerprints = chainConfig
        ?.autoSweepDismissedFingerprints()
        ?.toMutableSet()
        ?: mutableSetOf()
    private val autoSweepRetryAfter = mutableMapOf<String, Long>()
    private var readyInvoicesLoaded = false
    private var recentTransactionsLoaded = false

    init {
        viewModelScope.launch {
            repository.observeReadyInvoices().collect { invoices ->
                val liveFingerprints = invoices.mapNotNull(Invoice::autoSweepFingerprint).toSet()
                autoSweepRetryAfter.keys.retainAll(liveFingerprints)
                readyInvoicesLoaded = true
                _state.value = _state.value.copy(readyInvoices = invoices)
                maybeStartAutoSweep()
            }
        }
        viewModelScope.launch {
            repository.observeRecentTransactions().collect { transactions ->
                recentTransactionsLoaded = true
                _state.value = _state.value.copy(recentTransactions = transactions)
                maybeStartAutoSweep()
            }
        }
        viewModelScope.launch {
            while (true) {
                recoverPending(reportError = false)
                maybeStartAutoSweep()
                delay(RECOVERY_INTERVAL_MILLIS)
            }
        }
    }

    fun prepare(invoiceIds: List<String>) {
        if (_state.value.preparing || _state.value.submitting) return
        _state.value = _state.value.copy(
            preparing = true,
            prepared = null,
            preparedAutomatically = false,
            preparedAutoSweepFingerprint = null,
            message = null,
            isError = false,
            autoSweepMessage = false,
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
                    message = safeReadRpcFailureMessage(error, "Settlement preflight failed"),
                    isError = true
                )
            }
        }
    }

    fun dismissReview() {
        if (!_state.value.submitting && !_state.value.preparingAuthentication) {
            var dismissalPersisted = true
            _state.value.autoSweepFingerprintToSuppressOnDismiss()
                ?.let { fingerprint ->
                    if (suppressedAutoSweepFingerprints.add(fingerprint)) {
                        dismissalPersisted = persistAutoSweepDismissals(
                            retainFingerprint = fingerprint,
                        )
                    }
                }
            releaseAuthenticationReservation()
            if (!dismissalPersisted) {
                autoSweepEnabled = false
                autoSweepAttemptGate.invalidate()
                autoSweepJob?.cancel()
                autoSweepJob = null
                chainConfig?.updateAutoSweepEnabled(false)
                _state.value = _state.value.withAutoSweepDismissalCapacityFailure()
                return
            }
            val clearAutoMessage = _state.value.autoSweepMessage
            _state.value = _state.value.copy(
                prepared = null,
                preparedAutomatically = false,
                preparedAutoSweepFingerprint = null,
                message = if (clearAutoMessage) null else _state.value.message,
                isError = if (clearAutoMessage) false else _state.value.isError,
                autoSweepMessage = false,
            )
        }
    }

    /** Settings propagation only. Enabling never signs or bypasses the existing review/auth UI. */
    fun setAutoSweepEnabled(enabled: Boolean) {
        if (!autoSweepPreferenceInitialized) {
            autoSweepPreferenceInitialized = true
            autoSweepEnabled = enabled
            _state.value = _state.value.copy(autoSweepEnabled = enabled)
            if (enabled) maybeStartAutoSweep()
            return
        }
        if (autoSweepEnabled == enabled) return
        autoSweepEnabled = enabled
        if (enabled) {
            suppressedAutoSweepFingerprints.clear()
            persistAutoSweepDismissals()
            autoSweepRetryAfter.clear()
            _state.value = _state.value.copy(autoSweepEnabled = true)
            maybeStartAutoSweep()
        } else {
            val cancelledJob = autoSweepJob
            val cancelledAutomaticPreparation = cancelledJob?.isActive == true
            autoSweepAttemptGate.invalidate()
            autoSweepJob = null
            cancelledJob?.cancel()
            _state.value = _state.value.withAutoSweepDisabled(
                cancelledAutomaticPreparation = cancelledAutomaticPreparation,
            )
        }
    }

    /** One-shot navigation ownership prevents recomposition or cancellation from reopening review. */
    fun beginAutoSweepReviewNavigation(sequence: Long): Boolean {
        val current = _state.value
        if (!current.autoSweepEnabled || !current.preparedAutomatically || current.prepared == null) {
            return false
        }
        return autoSweepReviewNavigationGate.claim(sequence, current.autoSweepReviewSequence)
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
            autoSweepMessage = false,
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
                    message = safeReadRpcFailureMessage(
                        error,
                        "Settlement pre-authentication check failed",
                    ),
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
        _state.value = _state.value.copy(
            submitting = true,
            message = null,
            isError = false,
            autoSweepMessage = false,
        )
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
                    preparedAutomatically = false,
                    preparedAutoSweepFingerprint = null,
                    message = when (transaction.status.name) {
                        "VERIFIED" -> "Settlement verified on-chain."
                        else -> "Transaction ${transaction.status.name.lowercase().replace('_', ' ')}."
                    },
                    autoSweepMessage = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    message = safeReadRpcFailureMessage(error, "Settlement submission failed"),
                    isError = true,
                    autoSweepMessage = false,
                )
            } finally {
                releaseAuthenticationReservation()
            }
        }
    }

    fun authenticationFailed(message: String) {
        releaseAuthenticationReservation()
        _state.value = _state.value.copy(
            message = "Authentication failed: $message",
            isError = true,
            autoSweepMessage = false,
        )
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
                    message = safeReadRpcFailureMessage(error, "Settlement recovery failed"),
                    isError = true,
                    autoSweepMessage = false,
                )
            }
        } finally {
            recoveryInFlight = false
        }
    }

    private fun maybeStartAutoSweep() {
        if (!autoSweepEnabled || !readyInvoicesLoaded || !recentTransactionsLoaded ||
            autoSweepJob?.isActive == true
        ) return
        val current = _state.value
        if (current.preparing || current.preparingAuthentication || current.submitting ||
            current.prepared != null || current.hasActiveSettlementTransaction()
        ) return
        val candidate = selectAutoSweepCandidate(
            invoices = current.readyInvoices,
            suppressedFingerprints = suppressedAutoSweepFingerprints +
                deferredAutoSweepFingerprints(
                    retryAfterElapsedRealtimeMillis = autoSweepRetryAfter,
                    nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                ),
        ) ?: return

        _state.value = current.copy(
            preparing = true,
            preparedAutomatically = false,
            preparedAutoSweepFingerprint = null,
            message = null,
            isError = false,
            autoSweepMessage = false,
        )
        val attemptGeneration = autoSweepAttemptGate.begin()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var tryNextCandidate = false
            try {
                val prepared = repository.prepare(listOf(candidate.invoiceId))
                val stillCanonical = _state.value.readyInvoices.any { invoice ->
                    invoice.autoSweepFingerprint() == candidate.fingerprint
                }
                if (!autoSweepAttemptGate.isCurrent(attemptGeneration)) return@launch
                if (!autoSweepEnabled || !stillCanonical ||
                    _state.value.hasActiveSettlementTransaction()
                ) {
                    _state.value = _state.value.copy(preparing = false)
                    return@launch
                }
                _state.value = _state.value.copy(
                    preparing = false,
                    prepared = prepared,
                    preparedAutomatically = true,
                    preparedAutoSweepFingerprint = candidate.fingerprint,
                    autoSweepReviewSequence = _state.value.autoSweepReviewSequence + 1,
                    message = "Auto-sweep is ready for review. Device authentication is required.",
                    isError = false,
                    autoSweepMessage = true,
                )
                autoSweepRetryAfter.remove(candidate.fingerprint)
            } catch (error: CancellationException) {
                if (autoSweepAttemptGate.isCurrent(attemptGeneration)) {
                    _state.value = _state.value.copy(preparing = false)
                }
                throw error
            } catch (error: Exception) {
                if (autoSweepAttemptGate.isCurrent(attemptGeneration) && autoSweepEnabled) {
                    autoSweepRetryAfter[candidate.fingerprint] = Math.addExact(
                        SystemClock.elapsedRealtime(),
                        AUTO_SWEEP_RETRY_DELAY_MILLIS,
                    )
                    tryNextCandidate = true
                    _state.value = _state.value.copy(
                        preparing = false,
                        message = "Auto-sweep preparation deferred: " +
                            safeReadRpcFailureMessage(error, "settlement preflight failed"),
                        isError = true,
                        autoSweepMessage = true,
                    )
                }
            } finally {
                if (autoSweepAttemptGate.isCurrent(attemptGeneration)) {
                    autoSweepJob = null
                    if (tryNextCandidate && autoSweepEnabled) maybeStartAutoSweep()
                }
            }
        }
        autoSweepJob = job
        job.start()
    }

    private fun SettlementUiState.hasActiveSettlementTransaction(): Boolean =
        recentTransactions.any { transaction ->
            transaction.status in setOf(
                SettlementTransactionStatus.SIGNED,
                SettlementTransactionStatus.SUBMITTED,
                SettlementTransactionStatus.CONFIRMING,
            )
        }

    override fun onCleared() {
        releaseAuthenticationReservation()
        super.onCleared()
    }

    private fun releaseAuthenticationReservation() {
        authenticationReservation?.close()
        authenticationReservation = null
    }

    private fun persistAutoSweepDismissals(retainFingerprint: String? = null): Boolean {
        val config = chainConfig ?: return true
        if (config.updateAutoSweepDismissedFingerprints(
                fingerprints = suppressedAutoSweepFingerprints,
                retainFingerprint = retainFingerprint,
            )
        ) {
            // Keep process state exactly aligned with the safely bounded durable set.
            suppressedAutoSweepFingerprints.clear()
            suppressedAutoSweepFingerprints += config.autoSweepDismissedFingerprints()
            return true
        }
        return false
    }

    class Factory(
        private val repository: SettlementRepository,
        private val chainConfig: ChainConfig? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettlementViewModel(repository, chainConfig) as T
    }

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 60_000L
        const val AUTO_SWEEP_RETRY_DELAY_MILLIS = 60_000L
    }
}
