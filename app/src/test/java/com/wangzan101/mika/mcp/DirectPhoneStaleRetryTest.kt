package com.wangzan101.mika.mcp

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DirectPhoneStaleRetryTest {
    private fun stale(detail: String? = null) = CommandException(ErrorCode.STALE_SNAPSHOT, detail)

    @Test fun aScreenThatSettlesIsReadAgainWithoutTheCallerSeeingTheStaleError() = runTest {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val seen = mutableListOf<Pair<Int, Boolean>>()
        val value = DirectPhoneStaleRetry.run(
            pause = { pauses += it },
            settled = { retries, ok -> seen += retries to ok },
        ) {
            if (++calls < 3) throw stale()
            "tree"
        }
        assertEquals("tree", value)
        assertEquals(3, calls)
        assertEquals(listOf(DirectPhoneStaleRetry.SETTLE_MS, DirectPhoneStaleRetry.SETTLE_MS), pauses)
        assertEquals(listOf(2 to true), seen)
    }

    @Test fun aFirstTrySuccessLeavesNoRetryTrace() = runTest {
        val seen = mutableListOf<Pair<Int, Boolean>>()
        assertEquals(1, DirectPhoneStaleRetry.run(pause = { fail("no wait expected") }, settled = { r, ok -> seen += r to ok }) { 1 })
        assertTrue(seen.isEmpty())
    }

    @Test fun anInstabilityThatNeverEndsIsReportedAfterTheLimitWithTheRetryCount() = runTest {
        var calls = 0
        val seen = mutableListOf<Pair<Int, Boolean>>()
        try {
            DirectPhoneStaleRetry.run(maxAttempts = 4, pause = {}, settled = { r, ok -> seen += r to ok }) { calls++; throw stale() }
            fail("expected stale_snapshot")
        } catch (e: CommandException) {
            assertEquals(ErrorCode.STALE_SNAPSHOT, e.code)
            assertEquals("retries_3", e.detail)
        }
        assertEquals(4, calls)
        assertEquals(listOf(3 to false), seen)
    }

    @Test fun theWaitingBudgetCutsTheRetriesShort() = runTest {
        var clock = 0L
        var calls = 0
        try {
            DirectPhoneStaleRetry.run(
                maxAttempts = 50, settleMs = 1_000, budgetMs = 2_500,
                now = { clock }, pause = { clock += it },
            ) { calls++; throw stale() }
            fail("expected stale_snapshot")
        } catch (e: CommandException) {
            assertEquals(ErrorCode.STALE_SNAPSHOT, e.code)
        }
        // t=0 try, wait to 1000 try, wait to 2000 try, a third wait would end at 3000 > 2500.
        assertEquals(3, calls)
    }

    @Test fun otherErrorsAndSupersededSnapshotsAreNeverRetried() = runTest {
        var calls = 0
        for (e in listOf(CommandException(ErrorCode.LOCAL_GATE_DENIED, "needs_user"), stale(DirectPhoneStaleRetry.SUPERSEDED))) {
            calls = 0
            try {
                DirectPhoneStaleRetry.run(pause = { fail("must not wait") }) { calls++; throw e }
                fail("expected the error")
            } catch (got: CommandException) {
                assertEquals(e.code, got.code)
                assertEquals(e.detail, got.detail)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun theCallerCanTurnAnExhaustedRetryIntoAMoreSpecificError() = runTest {
        try {
            DirectPhoneStaleRetry.run(
                maxAttempts = 2, pause = {},
                finalize = { e, _ -> if (e.detail == "window_unreadable") CommandException(ErrorCode.EXECUTOR_ERROR, "window_unreadable") else e },
            ) { throw stale("window_unreadable") }
            fail("expected an error")
        } catch (e: CommandException) {
            assertEquals(ErrorCode.EXECUTOR_ERROR, e.code)
            assertEquals("window_unreadable", e.detail)
        }
    }
}
