package com.openpasskey.terminal.rpc

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RpcInteractiveReservation internal constructor(
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean()

    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Preserves the public endpoint's rate-limit budget for cashier-initiated work. Background loops
 * pause before their next RPC sample while an invoice or settlement operation is active.
 */
class RpcWorkCoordinator(
    private val backgroundRetryMillis: Long = DEFAULT_BACKGROUND_RETRY_MILLIS,
    private val backgroundOperationTimeoutMillis: Long = DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS,
) {
    init {
        require(backgroundRetryMillis > 0) { "Background retry delay must be positive" }
        require(backgroundOperationTimeoutMillis > 0) {
            "Background RPC operation timeout must be positive"
        }
    }

    private val interactiveOperations = AtomicInteger()
    private val interactiveMutex = Mutex()
    private val backgroundMutex = Mutex()

    val interactive: Boolean
        get() = interactiveOperations.get() > 0

    suspend fun <T> withInteractiveOperation(block: suspend () -> T): T {
        interactiveOperations.incrementAndGet()
        return try {
            // Cashier actions, settlement checks, and Settings readiness share one public RPC
            // budget. Serialize them so a readiness refresh cannot compete with a payment tap.
            interactiveMutex.withLock { block() }
        } finally {
            releaseInteractiveOperation()
        }
    }

    /** Keeps background work deferred across UI gaps such as the system authentication prompt. */
    fun reserveInteractiveWindow(): RpcInteractiveReservation {
        interactiveOperations.incrementAndGet()
        return RpcInteractiveReservation(::releaseInteractiveOperation)
    }

    /**
     * Runs at most one bounded background RPC unit. Interactive work uses a separate mutex: one
     * already-started blocking HttpURLConnection sample may overlap, but it can never hold the
     * cashier queue. Once interactive intent is published, no later background unit may start.
     * Null means interactive work was queued or this cooperative unit reached its deadline.
     */
    suspend fun <T : Any> withBackgroundOperation(block: suspend () -> T): T? {
        if (interactive) return null
        return backgroundMutex.withLock {
            if (interactive) {
                null
            } else {
                // Settlement units are also constrained to one shorter-deadline OkHttp call;
                // legacy read-only samples use a total socket budget below this deadline. The
                // separate mutex means non-cancellable legacy I/O still cannot hold cashier work.
                withTimeoutOrNull(backgroundOperationTimeoutMillis) { block() }
            }
        }
    }

    suspend fun awaitBackgroundWindow() {
        while (interactive) delay(backgroundRetryMillis)
    }

    companion object {
        private const val DEFAULT_BACKGROUND_RETRY_MILLIS = 250L
        internal const val DEFAULT_BACKGROUND_OPERATION_TIMEOUT_MILLIS = 5_000L
    }

    private fun releaseInteractiveOperation() {
        check(interactiveOperations.decrementAndGet() >= 0) {
            "Interactive RPC operation accounting underflow"
        }
    }
}
