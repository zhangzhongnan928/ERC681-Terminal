package com.openpasskey.terminal.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcWorkCoordinatorTest {
    @Test
    fun `background waits while interactive RPC work owns the endpoint budget`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val interactiveStarted = CompletableDeferred<Unit>()
        val releaseInteractive = CompletableDeferred<Unit>()
        var backgroundPassed = false

        val interactive = launch {
            coordinator.withInteractiveOperation {
                interactiveStarted.complete(Unit)
                releaseInteractive.await()
            }
        }
        interactiveStarted.await()
        val background = launch {
            coordinator.awaitBackgroundWindow()
            backgroundPassed = true
        }

        delay(15)
        assertTrue(coordinator.interactive)
        assertFalse(backgroundPassed)
        releaseInteractive.complete(Unit)
        joinAll(interactive, background)
        assertFalse(coordinator.interactive)
        assertTrue(backgroundPassed)
    }

    @Test
    fun `cancelled interactive work always releases the background gate`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val started = CompletableDeferred<Unit>()
        val operation = launch {
            coordinator.withInteractiveOperation {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        operation.cancel()
        operation.join()

        coordinator.awaitBackgroundWindow()
        assertFalse(coordinator.interactive)
    }

    @Test
    fun `interactive operations are serialized and background waits for the full queue`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var activeBlocks = 0
        var overlapObserved = false
        var backgroundPassed = false

        val first = launch {
            coordinator.withInteractiveOperation {
                activeBlocks += 1
                overlapObserved = overlapObserved || activeBlocks > 1
                firstEntered.complete(Unit)
                releaseFirst.await()
                activeBlocks -= 1
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withInteractiveOperation {
                activeBlocks += 1
                overlapObserved = overlapObserved || activeBlocks > 1
                secondEntered.complete(Unit)
                releaseSecond.await()
                activeBlocks -= 1
            }
        }
        val background = launch {
            coordinator.awaitBackgroundWindow()
            backgroundPassed = true
        }

        delay(15)
        assertFalse(secondEntered.isCompleted)
        assertFalse(backgroundPassed)
        releaseFirst.complete(Unit)
        secondEntered.await()
        assertFalse(overlapObserved)
        assertFalse(backgroundPassed)
        releaseSecond.complete(Unit)
        joinAll(first, second, background)

        assertFalse(overlapObserved)
        assertFalse(coordinator.interactive)
        assertTrue(backgroundPassed)
    }

    @Test
    fun `already started blocking background unit cannot delay cashier work`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val backgroundEntered = CompletableDeferred<Unit>()
        val releaseBackground = CompletableDeferred<Unit>()
        val interactiveEntered = CompletableDeferred<Unit>()

        val background = launch {
            coordinator.withBackgroundOperation {
                backgroundEntered.complete(Unit)
                releaseBackground.await()
                Unit
            }
        }
        backgroundEntered.await()
        val interactive = launch {
            coordinator.withInteractiveOperation {
                interactiveEntered.complete(Unit)
            }
        }

        interactiveEntered.await()
        assertFalse(background.isCompleted)
        releaseBackground.complete(Unit)
        joinAll(background, interactive)
        assertFalse(coordinator.interactive)
    }

    @Test
    fun `exclusive interactive mutation drains old background work before trust-source commit`() =
        runBlocking {
            val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
            val backgroundEntered = CompletableDeferred<Unit>()
            val releaseBackground = CompletableDeferred<Unit>()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val background = launch {
                coordinator.withBackgroundOperation {
                    events += "old-provider-read"
                    backgroundEntered.complete(Unit)
                    releaseBackground.await()
                    events += "old-provider-commit"
                    Unit
                }
            }
            backgroundEntered.await()
            val exclusive = launch {
                coordinator.withExclusiveInteractiveOperation {
                    events += "new-provider-commit"
                    exclusiveEntered.complete(Unit)
                }
            }

            delay(15)
            assertFalse(exclusiveEntered.isCompleted)
            assertEquals(null, coordinator.withBackgroundOperation { Unit })

            releaseBackground.complete(Unit)
            joinAll(background, exclusive)

            assertEquals(
                listOf("old-provider-read", "old-provider-commit", "new-provider-commit"),
                events,
            )
            assertFalse(coordinator.interactive)
        }

    @Test
    fun `queued interactive work causes a later background unit to defer`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val interactiveEntered = CompletableDeferred<Unit>()
        val releaseInteractive = CompletableDeferred<Unit>()
        var backgroundRan = false
        val interactive = launch {
            coordinator.withInteractiveOperation {
                interactiveEntered.complete(Unit)
                releaseInteractive.await()
            }
        }
        interactiveEntered.await()

        val result = coordinator.withBackgroundOperation {
            backgroundRan = true
            Unit
        }

        assertEquals(null, result)
        assertFalse(backgroundRan)
        releaseInteractive.complete(Unit)
        interactive.join()
    }

    @Test
    fun `authentication reservation blocks background between preflight and submit`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val reservation = coordinator.reserveInteractiveWindow()

        coordinator.withInteractiveOperation { /* pre-authentication live proof */ }
        var backgroundRan = false
        assertEquals(
            null,
            coordinator.withBackgroundOperation {
                backgroundRan = true
                Unit
            },
        )
        assertFalse(backgroundRan)

        var submitted = false
        coordinator.withInteractiveOperation { submitted = true }
        reservation.close()
        assertTrue(submitted)
        assertEquals(Unit, coordinator.withBackgroundOperation { Unit })
        assertFalse(coordinator.interactive)
    }

    @Test
    fun `cancelled authentication reservation releases background exactly once`() = runBlocking {
        val coordinator = RpcWorkCoordinator(backgroundRetryMillis = 1)
        val reservation = coordinator.reserveInteractiveWindow()
        assertTrue(coordinator.interactive)

        reservation.close()
        reservation.close()

        assertFalse(coordinator.interactive)
        assertEquals(Unit, coordinator.withBackgroundOperation { Unit })
    }

    @Test
    fun `timed out background unit releases endpoint lock for cashier work`() = runBlocking {
        val coordinator = RpcWorkCoordinator(
            backgroundRetryMillis = 1,
            backgroundOperationTimeoutMillis = 25,
        )
        val backgroundEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val elapsed = measureTimeMillis {
            val background = launch {
                val result = coordinator.withBackgroundOperation {
                    backgroundEntered.complete(Unit)
                    neverCompletes.await()
                    Unit
                }
                assertEquals(null, result)
            }
            backgroundEntered.await()
            background.join()
        }

        assertTrue("background deadline took $elapsed ms", elapsed < 1_000)
        var cashierEntered = false
        coordinator.withInteractiveOperation { cashierEntered = true }
        assertTrue(cashierEntered)
    }
}
