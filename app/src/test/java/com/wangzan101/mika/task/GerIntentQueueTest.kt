package com.wangzan101.mika.task

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GerIntentQueueTest {
    @Test fun blockedTaskFinishesAndIntentionRunsOnce() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime })
        val ger = GlobalExecutionRegistry()
        ger.taskStarted("task-a")
        var executed = 0
        val call = async {
            queue.dispatch("mcp:a", "req-0001", 120_000) {
                if (ger.hasRunningTasks()) GerIntentAttempt.Blocked("task_running")
                else GerIntentAttempt.Done(++executed)
            }
        }
        runCurrent()
        assertEquals(1, queue.pendingCount("mcp:a"))
        assertEquals(0, executed)
        ger.taskEnded("task-a")
        queue.notifyChanged()
        runCurrent()
        assertEquals(GerIntentOutcome.Completed(1), call.await())
        assertEquals(1, executed)
    }

    @Test fun waitTimeoutHasLastCauseAndNeverExecutesLater() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime }, pollMs = 10)
        var busy = true
        var count = 0
        val call = async {
            queue.dispatch("mcp:a", "req-0002", 1_000) {
                if (busy) GerIntentAttempt.Blocked("app_in_use_by_user")
                else GerIntentAttempt.Done(++count)
            }
        }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GerIntentOutcome.TimedOut("app_in_use_by_user"), call.await())
        busy = false
        queue.notifyChanged()
        runCurrent()
        assertEquals(0, count)
        assertEquals(0, queue.pendingCount("mcp:a"))
    }

    @Test fun ownerCancellationDropsPendingBeforeUnlockingAndroidHost() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime })
        var executed = 0
        val call = async {
            queue.dispatch("mcp:a", "req-0003", 120_000) {
                GerIntentAttempt.Blocked("direct_session_busy").also { executed++ }
            }
        }
        runCurrent()
        assertEquals(1, queue.cancelOwner("mcp:a"))
        assertEquals(0, queue.pendingCount("mcp:a"))
        runCurrent()
        assertEquals(GerIntentOutcome.Cancelled, call.await())
        assertEquals(1, executed) // Only the non-executing preflight ran.
    }

    @Test fun duplicateRequestIdWhileWaitingCannotExecuteTwice() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime })
        var ready = false
        var actions = 0
        val first = async {
            queue.dispatch("mcp:a", "same-id-001", 120_000) {
                if (!ready) GerIntentAttempt.Blocked("task_running")
                else GerIntentAttempt.Done(++actions)
            }
        }
        runCurrent()
        val duplicate = queue.dispatch("mcp:a", "same-id-001", 120_000) {
            GerIntentAttempt.Done(++actions)
        }
        assertEquals(GerIntentOutcome.Duplicate, duplicate)
        ready = true
        queue.notifyChanged()
        runCurrent()
        assertEquals(GerIntentOutcome.Completed(1), first.await())
        assertEquals(1, actions)
        // After execution the host's durable journal, tested independently,
        // rejects this ID; the queue intentionally does not invent a second journal.
    }

    @Test fun killSwitchDisabledDuringWaitReturnsRefusalWithoutAction() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime })
        var blocked = true
        var enabled = true
        var actions = 0
        val call = async {
            queue.dispatch("mcp:a", "req-0004", 120_000) {
                when {
                    !enabled -> GerIntentAttempt.Done("remote_access_disabled")
                    blocked -> GerIntentAttempt.Blocked("task_running")
                    else -> GerIntentAttempt.Done("ok".also { actions++ })
                }
            }
        }
        runCurrent()
        blocked = false
        enabled = false
        queue.notifyChanged()
        runCurrent()
        assertEquals(GerIntentOutcome.Completed("remote_access_disabled"), call.await())
        assertEquals(0, actions)
    }

    @Test fun fifoByOwnerAndPerOwnerLimit() = runTest {
        val queue = GerIntentQueue(maxPerOwner = 2, nowMs = { testScheduler.currentTime })
        val log = mutableListOf<String>()
        var open = false
        fun attempt(id: String): GerIntentAttempt<String> {
            if (!open) return GerIntentAttempt.Blocked("app_busy")
            log += id
            return GerIntentAttempt.Done(id)
        }
        val a = async { queue.dispatch("mcp:a", "one", 120_000) { attempt("one") } }
        val b = async { queue.dispatch("mcp:a", "two", 120_000) { attempt("two") } }
        runCurrent()
        assertEquals(2, queue.pendingCount("mcp:a"))
        assertEquals(GerIntentOutcome.QueueFull, queue.dispatch("mcp:a", "three", 120_000) { attempt("three") })
        val other = async { queue.dispatch("mcp:b", "own-one", 120_000) { attempt("own-one") } }
        runCurrent()
        open = true
        queue.notifyChanged()
        runCurrent()
        assertEquals(GerIntentOutcome.Completed("one"), a.await())
        assertEquals(GerIntentOutcome.Completed("two"), b.await())
        assertEquals(GerIntentOutcome.Completed("own-one"), other.await())
        assertTrue(log.indexOf("one") < log.indexOf("two"))
        assertFalse(log.contains("three"))
    }

    @Test fun aLongAttemptDoesNotConsumeTheWaitingTimeout() = runTest {
        val queue = GerIntentQueue(nowMs = { testScheduler.currentTime }, pollMs = 10L)
        var calls = 0
        val answer = async {
            queue.dispatch("mcp:a", "slow-batch", 1_000L) {
                calls++
                if (calls == 1) {
                    // Simulates a lengthy completed command followed by an
                    // unexecuted next command waiting for a resource.
                    kotlinx.coroutines.delay(5_000L)
                    GerIntentAttempt.Blocked("app_busy")
                } else {
                    GerIntentAttempt.Done("finished")
                }
            }
        }
        runCurrent()
        advanceTimeBy(5_200L)
        runCurrent()
        assertEquals(GerIntentOutcome.Completed("finished"), answer.await())
        assertEquals(2, calls)
    }

    @Test fun parkedSessionAndPendingCallAreDifferentStates() = runTest {
        val ger = GlobalExecutionRegistry()
        val queue = ger.intentions
        assertTrue(ger.claimDirect("mcp:a"))
        ger.taskStarted("task-a")
        assertFalse(ger.canUseDirect("mcp:a"))
        assertTrue(ger.isDirectOwner("mcp:a")) // Still parked, not released.
        var actions = 0
        val waiting = async {
            queue.dispatch("mcp:a", "req-0005", 1_000) {
                if (!ger.canUseDirect("mcp:a")) GerIntentAttempt.Blocked("task_running")
                else GerIntentAttempt.Done(++actions)
            }
        }
        runCurrent()
        assertEquals(1, queue.pendingCount("mcp:a"))
        ger.taskEnded("task-a")
        runCurrent()
        assertEquals(GerIntentOutcome.Completed(1), waiting.await())
        assertTrue(ger.canUseDirect("mcp:a"))
        assertEquals(0, queue.pendingCount("mcp:a"))
    }
}
