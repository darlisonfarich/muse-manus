package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalExecutionRegistryTest {
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
