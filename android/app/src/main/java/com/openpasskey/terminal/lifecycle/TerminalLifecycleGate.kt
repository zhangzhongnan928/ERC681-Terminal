package com.openpasskey.terminal.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-lifetime critical section shared by configuration, invoice, settlement, and reset
 * mutations. Callers must never acquire this gate while holding a lock that a reset path also
 * needs; database and wallet operations happen inside the gate, not around a second gate acquire.
 */
class TerminalLifecycleGate {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveMutation(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
