package dev.rboza.android.opensyncforproton

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `ACCOUNT_REMOVED` broadcast stays open until its reconciliation ends, but at most [BROADCAST_HOLD] (50 s, under
 * Android's limit for a background broadcast); the reconciliation itself goes on.
 */
class BroadcastHoldTest {
    @Test
    fun `the broadcast is held for at most 50 seconds`() {
        assertEquals(50.seconds, BROADCAST_HOLD)
    }

    @Test
    fun `the broadcast ends as soon as the reconciliation does`() = runTest {
        val reconciliation = Job()
        var finished = false
        finishWhenDone(backgroundScope, reconciliation, 50.seconds) { finished = true }
        advanceTimeBy(10.seconds)
        runCurrent()
        assertFalse(finished)

        reconciliation.complete()
        runCurrent()
        assertTrue(finished)
    }

    @Test
    fun `a reconciliation still running at the limit keeps running, and the broadcast ends`() = runTest {
        val reconciliation = Job()
        val finished = CompletableDeferred<Long>()
        finishWhenDone(backgroundScope, reconciliation, 50.seconds) { finished.complete(testScheduler.currentTime) }

        advanceTimeBy(50.seconds - 1.seconds)
        runCurrent()
        assertFalse(finished.isCompleted)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(50_000L, finished.await())
        assertTrue(reconciliation.isActive)
    }
}
