package com.wangzan101.mika.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPhoneLaunchBoundaryTest {
    @Test fun stalledMediaAppLaunchReturnsAtDeadlineWithoutStartingItTwice() = runTest {
        val release = CompletableDeferred<Unit>()
        var starts = 0
        var settled = 0
        val bound = DirectPhoneLaunchBoundary(backgroundScope, timeoutMs = 10_000L) { settled++ }

        val first = async {
            bound.execute {
                starts++
                release.await() // Binder/HyperOS remains blocked while media plays.
                "opened"
            }
        }
        runCurrent()
        assertEquals(1, starts)
        assertTrue(bound.isInFlight())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(DirectLaunchOutcome.TimedOut, first.await())
        assertTrue(bound.isInFlight())

        val overlap = bound.execute {
            starts++
            "duplicate"
        }
        assertEquals(DirectLaunchOutcome.InFlight, overlap)
        assertEquals(1, starts)

        release.complete(Unit) // A late result may arrive; no replay is scheduled.
        runCurrent()
        assertFalse(bound.isInFlight())
        assertEquals(1, settled)
        assertEquals(1, starts)
    }

    @Test fun clientDisconnectCannotCancelAlreadyIssuedAndroidLaunch() = runTest {
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val bound = DirectPhoneLaunchBoundary(backgroundScope, 10_000L)
        val caller = async {
            bound.execute {
                starts++
                release.await()
                "opened"
            }
        }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(bound.isInFlight())
        assertEquals(1, starts)
        release.complete(Unit)
        runCurrent()
        assertFalse(bound.isInFlight())
        assertEquals(1, starts)
    }

    @Test fun fastIdleSpotifyLaunchPassesThroughWithoutTimeout() = runTest {
        val bound = DirectPhoneLaunchBoundary(backgroundScope, 10_000L)
        val result = bound.execute { "opened-on-virtual-display" }
        assertEquals(DirectLaunchOutcome.Completed("opened-on-virtual-display"), result)
        assertFalse(bound.isInFlight())
    }
}
