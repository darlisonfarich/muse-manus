package com.wangzan101.mika.mcp

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import kotlinx.coroutines.delay

/**
 * Bounded internal retry for `stale_snapshot`: a busy screen (playing video, ticking seek bar) usually settles in
 * a fraction of a second, so the executor waits and reads again instead of handing the failure back to the caller,
 * where every repetition costs a full trip over the link.
 *
 * Only a refusal that happened before anything was executed may go through here: the callers wrap the passive
 * reads and the pre-action surface check, never the action itself.
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

    /**
     * Runs [block]; while it throws a retryable `stale_snapshot`, waits [settleMs] and runs it again, at most
     * [maxAttempts] times and while the waiting stays within [budgetMs]. Any other error propagates at once.
     * When the screen is still unstable at the end, [finalize] builds the error the caller gets (by default the
     * same `stale_snapshot`, its detail carrying the number of internal retries). [settled] is told how many
     * retries were needed whenever there was at least one, for the audit.
     */
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
    ): T {
        val started = now()
        var retries = 0
        while (true) {
            try {
                return block().also { if (retries > 0) settled(retries, true) }
            } catch (e: CommandException) {
                val retryable = e.code == ErrorCode.STALE_SNAPSHOT && e.detail != SUPERSEDED
                val canWait = retries + 1 < maxAttempts && now() - started + settleMs <= budgetMs
                if (!retryable || !canWait) {
                    if (retries > 0) settled(retries, false)
                    throw if (retryable) finalize(e, retries) else e
                }
                retries++
                pause(settleMs)
            }
        }
    }
}
