package com.wangzan101.mika.mcp

import com.wangzan101.mika.task.GerIntentAttempt
import com.wangzan101.mika.task.GerIntentOutcome
import com.wangzan101.mika.task.GerIntentQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPhoneLaunchPreflightTest {
    @Test fun spotifyOnPhysicalDisplayBlocksEvenThoughVirtualDisplayQueryReturnsMinusOne() {
        // ShellService.displayOfPackage() intentionally only checks Mika-owned
        // displays. Before this fix -1 was falsely interpreted as app free.
        assertEquals(
            "app_in_use_by_user",
            DirectPhoneLaunchPreflight.blockedCause(
                userVisible = true, reservedByOther = false,
                agentDisplay = -1, ourDisplay = null,
            ),
        )
    }

    @Test fun physicalUserTakesPrecedenceOverAppReservations() {
        assertEquals(
            "app_in_use_by_user",
            DirectPhoneLaunchPreflight.blockedCause(true, true, 8, 8),
        )
    }

    @Test fun unknownUserVisibilityFailsClosedBeforePendingJournal() {
        assertEquals(
            "app_presence_unknown",
            DirectPhoneLaunchPreflight.blockedCause(null, false, -1, null),
        )
    }

    @Test fun protectedVirtualAppCannotBeTakenFromAnotherExecutor() {
        assertEquals("app_busy", DirectPhoneLaunchPreflight.blockedCause(false, true, -1, null))
        assertEquals("app_busy", DirectPhoneLaunchPreflight.blockedCause(false, false, 5, 7))
        assertEquals("app_busy", DirectPhoneLaunchPreflight.blockedCause(false, false, 5, null))
    }

    @Test fun freePhysicalAppCanRunAndExistingOwnVirtualAppCanBeReused() {
        assertNull(DirectPhoneLaunchPreflight.blockedCause(false, false, -1, null))
        assertNull(DirectPhoneLaunchPreflight.blockedCause(false, false, 7, 7))
    }

    @Test fun spotifyUsedByPersonWaitsWithoutAuditingThenRunsExactlyOnce() = runTest {
        val q = GerIntentQueue(pollMs = 100L, nowMs = { testScheduler.currentTime })
        var physicalSpotifyVisible = true
        var durablePendingCount = 0
        var executions = 0
        val call = async {
            q.dispatch("mcp:muse", "spotify-request-1", 120_000L) {
                val cause = DirectPhoneLaunchPreflight.blockedCause(
                    userVisible = physicalSpotifyVisible,
                    reservedByOther = false, agentDisplay = -1, ourDisplay = null,
                )
                if (cause != null) GerIntentAttempt.Blocked(cause)
                else GerIntentAttempt.Done("opened".also {
                    // Simulates markOnce(PENDING) and actual launch after preflight.
                    durablePendingCount++
                    executions++
                })
            }
        }
        runCurrent()
        assertEquals(1, q.pendingCount("mcp:muse"))
        assertEquals(0, durablePendingCount)
        assertEquals(0, executions)
        physicalSpotifyVisible = false
        q.notifyChanged()
        runCurrent()
        assertEquals(GerIntentOutcome.Completed("opened"), call.await())
        assertEquals(1, durablePendingCount)
        assertEquals(1, executions)
        assertEquals(0, q.pendingCount("mcp:muse"))
    }

    @Test fun spotifyHeldOnPhysicalScreenTimesOutWithRealCauseAndNeverJournals() = runTest {
        val q = GerIntentQueue(pollMs = 50L, nowMs = { testScheduler.currentTime })
        var visible = true
        var audited = 0
        val waiting = async {
            q.dispatch("mcp:muse", "spotify-request-2", 1_000L) {
                val cause = DirectPhoneLaunchPreflight.blockedCause(visible, false, -1, null)
                if (cause != null) GerIntentAttempt.Blocked(cause)
                else GerIntentAttempt.Done("opened".also { audited++ })
            }
        }
        runCurrent()
        advanceTimeBy(1_010L)
        runCurrent()
        assertEquals(GerIntentOutcome.TimedOut("app_in_use_by_user"), waiting.await())
        visible = false
        q.notifyChanged()
        runCurrent()
        assertEquals(0, audited)
    }

    @Test fun physicalAppWaitCanBeCancelledWithoutLaunching() = runTest {
        val q = GerIntentQueue(pollMs = 50L, nowMs = { testScheduler.currentTime })
        var executed = false
        val waiting = async {
            q.dispatch("mcp:muse", "spotify-request-3", 120_000L) {
                GerIntentAttempt.Blocked("app_in_use_by_user").also { executed = false }
            }
        }
        runCurrent()
        assertEquals(1, q.cancelOwner("mcp:muse"))
        runCurrent()
        assertEquals(GerIntentOutcome.Cancelled, waiting.await())
        assertTrue(!executed)
    }
}
