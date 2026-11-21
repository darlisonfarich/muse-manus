package com.wangzan101.mika.mcp

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import kotlinx.coroutines.delay

/**
 * Bounded internal retry for `stale_snapshot`: a busy screen (playing video, ticking seek bar) usually settles in
 * a fraction of a second, so the executor waits and reads again instead of handing the failure back to the caller,
 * where every repetition costs a full trip over the link.
 */
internal object DirectPhoneStaleRetry {
    /** Total tries, the first one included. */
    const val MAX_ATTEMPTS = 5
    const val SETTLE_MS = 250L
    /** Upper bound for all the waiting together; far below the 30 s every phone command declares. */
    const val BUDGET_MS = 8_000L

    /** A stale verdict that waiting cannot fix (the caller's snapshot was already replaced). */
    const val SUPERSEDED = "snapshot_superseded"

    fun retriesDetail(retries: Int) = "retries_$retries"

    suspend fun <T> run(
        maxAttempts: Int = MAX_ATTEMPTS,
        settleMs: Long = SETTLE_MS,
        budgetMs: Long = BUDGET_MS,
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) },
        finalize: (CommandException, Int) -> CommandException = { e, retries ->
            CommandException(ErrorCode.STALE_SNAPSHOT, if (retries > 0) retriesDetail(retries) else e.detail)
        },
        settled: (retries: Int, succeeded: Boolean) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ): T = block()
}
