package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalExecutionRegistryTest {
    @Test fun slotsAllocateIndependentlyAndReleaseForReuse() {
        val ger = GlobalExecutionRegistry()
        val a = ger.claimSlot("mcp-a")
        val b = ger.claimSlot("mcp-b")
        assertTrue(a.admitted); assertTrue(b.admitted)
        assertTrue(a.slot != b.slot)
        assertTrue(ger.isActiveOwner("mcp-a"))
        assertTrue(ger.releaseSlot("mcp-a"))
        assertEquals(a.slot, ger.claimSlot("mcp-c").slot)
    }

    @Test fun slotExhaustionReturnsStableBudgetCodeAndDoesNotSteal() {
        val ger = GlobalExecutionRegistry()
        ger.budgets = GlobalExecutionRegistry.Budgets(maxDisplaySlots = 2)
        assertTrue(ger.claimSlot("a").admitted)
        assertTrue(ger.claimSlot("b").admitted)
        val refused = ger.claimSlot("c")
        assertEquals(GlobalExecutionRegistry.Refusal.slot_budget_exhausted, refused.refusal)
        assertEquals(setOf("a", "b"), ger.slotOwners().values.toSet())
    }

    @Test fun taskGovernorRefusesBeyondConfiguredBudgetButAllowsUserDisplayOutsideSlots() {
        val ger = GlobalExecutionRegistry()
        ger.budgets = GlobalExecutionRegistry.Budgets(maxTasks = 1)
        ger.taskStarted("task-a")
        try {
            ger.taskStarted("task-b")
            throw AssertionError("expected stable budget refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals(GlobalExecutionRegistry.Refusal.task_budget_exhausted.name, e.message)
        }
        assertTrue(ger.claimSlot("mcp-a").admitted)
        assertFalse(ger.canUseSlot("mcp-a")) // admitted tasks take priority over Muse slots
    }

    @Test fun taskGovernorEnforcesPerOwnerConcurrencyAndTaskTtl() {
        val ger = GlobalExecutionRegistry()
        ger.budgets = GlobalExecutionRegistry.Budgets(maxTasks = 3, maxTasksPerOwner = 1, taskTtlMs = 0)
        ger.taskStarted("run-a", "conversation-a")
        try {
            ger.taskStarted("run-b", "conversation-a")
            throw AssertionError("expected owner budget refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals(GlobalExecutionRegistry.Refusal.owner_budget_exhausted.name, e.message)
        }
        ger.taskEnded("run-a")
        ger.budgets = ger.budgets.copy(taskTtlMs = 60_000)
        ger.taskStarted("run-b", "conversation-a")
        ger.budgets = ger.budgets.copy(taskTtlMs = 0)
        try {
            ger.taskStarted("run-c", "conversation-b")
            throw AssertionError("expected task TTL admission refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals(GlobalExecutionRegistry.Refusal.task_ttl_exhausted.name, e.message)
        }
    }

    @Test fun existingTaskBlocksDirectHostWithoutChangingTaskCapacity() {
        val ger = GlobalExecutionRegistry()
        ger.taskStarted("task-a")
        ger.taskStarted("task-b")
        assertEquals(2, ger.snapshot().admittedTasks)
        assertFalse(ger.claimDirect("mcp-a"))
        assertTrue(ger.taskEnded("task-a"))
        assertFalse(ger.claimDirect("mcp-a"))
        ger.taskEnded("task-b")
        assertTrue(ger.claimDirect("mcp-a"))
        assertTrue(ger.canUseDirect("mcp-a"))
    }

    @Test fun anotherOwnerCannotBorrowOrReleaseDirectSession() {
        val ger = GlobalExecutionRegistry()
        assertTrue(ger.claimDirect("mcp-a"))
        assertTrue(ger.claimDirect("mcp-a"))
        assertFalse(ger.claimDirect("mcp-b"))
        assertFalse(ger.canUseDirect("mcp-b"))
        assertFalse(ger.releaseDirect("mcp-b"))
        assertTrue(ger.canUseDirect("mcp-a"))
        assertTrue(ger.releaseDirect("mcp-a"))
        assertFalse(ger.releaseDirect("mcp-a"))
        assertTrue(ger.claimDirect("mcp-b"))
    }

    @Test fun newlyAdmittedTaskSuspendsExistingDirectSession() {
        val ger = GlobalExecutionRegistry()
        assertTrue(ger.claimDirect("mcp-a"))
        ger.taskStarted("task-a")
        assertTrue(ger.isDirectOwner("mcp-a"))
        assertFalse(ger.canUseDirect("mcp-a"))
        assertFalse(ger.claimDirect("mcp-a"))
        assertTrue(ger.isActiveOwner("task-a"))
        ger.taskEnded("task-a")
        assertTrue(ger.canUseDirect("mcp-a"))
        ger.releaseDirect("mcp-a")
        assertFalse(ger.isActiveOwner("mcp-a"))
    }

    @Test fun duplicateTaskRegistrationAndTerminationAreIdempotent() {
        val ger = GlobalExecutionRegistry()
        ger.taskStarted("task-a")
        ger.taskStarted("task-a")
        assertEquals(1, ger.snapshot().admittedTasks)
        assertTrue(ger.taskEnded("task-a"))
        assertFalse(ger.taskEnded("task-a"))
        assertEquals(GlobalExecutionRegistry.Snapshot(0, false), ger.snapshot())
    }

    @Test fun concurrentDirectClaimsHaveExactlyOneWinner() {
        val ger = GlobalExecutionRegistry()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val wins = AtomicInteger(0)
        repeat(2) { i ->
            Thread {
                try {
                    start.await()
                    if (ger.claimDirect("mcp-$i")) wins.incrementAndGet()
                } finally { finished.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        assertTrue(ger.snapshot().directSession)
    }
}
