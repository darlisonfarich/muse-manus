package com.wangzan101.mika.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The attempt has not performed any action when it reports [Blocked]. */
sealed interface GerIntentAttempt<out T> {
    data class Done<T>(val result: T) : GerIntentAttempt<T>
    data class Blocked(val cause: String) : GerIntentAttempt<Nothing>
}

sealed interface GerIntentOutcome<out T> {
    data class Completed<T>(val result: T) : GerIntentOutcome<T>
    data class TimedOut(val cause: String) : GerIntentOutcome<Nothing>
    data object Cancelled : GerIntentOutcome<Nothing>
    data object Duplicate : GerIntentOutcome<Nothing>
    data object QueueFull : GerIntentOutcome<Nothing>
}

/**
 * Small in-memory intention queue for direct MCP calls ONLY. TaskCenter owns the
 * persistent task queue, while [GlobalExecutionRegistry] owns this queue.
 *
 * Entries stay at the head of their owner's FIFO until their attempt FINISHES;
 * no two calls from the same owner can overtake or overlap. Each blocked head
 * sleeps OUTSIDE AndroidDirectPhoneHost's mutex. No action is journaled until the
 * host rechecks its gates and begins the real execution.
 *
 * A parked direct session is not a queued intention: sessions keep their display,
 * app leases and 15-minute TTL; intentions are only pending *calls*.
 *
 * No cross-process recovery here (phase 3). The Android host's durable PENDING
 * journal still handles actions that actually began.
 */
class GerIntentQueue(
    private val maxPerOwner: Int = 8,
    private val pollMs: Long = 500L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(maxPerOwner > 0)
        require(pollMs > 0L)
    }

    private class Ticket(val owner: String, val id: String) {
        @Volatile var cancelled: Boolean = false
        @Volatile var cause: String = "owner_fifo"
    }

    private val pending = HashMap<String, MutableList<Ticket>>()
    private val inFlight = HashSet<Pair<String, String>>()
    private val revision = MutableStateFlow(0L)

    @Synchronized fun notifyChanged() { revision.value += 1L }

    @Synchronized fun pendingCount(owner: String): Int = pending[owner]?.size ?: 0

    /** Revocation is immediate even if a different call currently owns the host mutex. */
    @Synchronized fun cancelOwner(owner: String): Int {
        val tickets = pending.remove(owner) ?: return 0
        tickets.forEach { it.cancelled = true }
        notifyChanged()
        return tickets.size
    }

    @Synchronized fun cancelAll(): Int {
        val count = pending.values.sumOf { it.size }
        pending.values.flatten().forEach { it.cancelled = true }
        pending.clear()
        notifyChanged()
        return count
    }

    /**
     * [id] is the original request_id for writes, or a fresh synthetic ID for reads.
     * Repeating the same (owner,id) while queued OR executing reports Duplicate.
     * Once execution finishes, the host's fsync'd audit journal continues to
     * reject repeated write IDs (including after process restart).
     *
     * [attempt] may return Blocked only before performing ANY action or writing
     * its durable PENDING marker. Timeout applies to *waiting*, not to an action
     * already under way. [isCancelled] must be rechecked at the execution gate.
     */
    suspend fun <T> dispatch(
        owner: String, id: String, timeoutMs: Long,
        attempt: suspend (isCancelled: () -> Boolean) -> GerIntentAttempt<T>,
    ): GerIntentOutcome<T> {
        require(owner.isNotBlank() && id.isNotBlank())
        require(timeoutMs > 0L)
        val ticket = synchronized(this) {
            val key = owner to id
            if (key in inFlight) return GerIntentOutcome.Duplicate
            val own = pending.getOrPut(owner) { mutableListOf() }
            if (own.size >= maxPerOwner) return GerIntentOutcome.QueueFull
            Ticket(owner, id).also {
                own.add(it)
                inFlight.add(key)
                notifyChanged()
            }
        }
        var deadline = nowMs() + timeoutMs
        try {
            while (true) {
                if (ticket.cancelled) return GerIntentOutcome.Cancelled
                if (nowMs() >= deadline) return GerIntentOutcome.TimedOut(ticket.cause)
                val head = synchronized(this) { pending[owner]?.firstOrNull() === ticket }
                if (head) {
                    // Do not hold this queue's monitor or the Android host mutex
                    // while parked between attempts.
                    val started = nowMs()
                    try {
                        when (val result = attempt { ticket.cancelled }) {
                            is GerIntentAttempt.Done -> return GerIntentOutcome.Completed(result.result)
                            is GerIntentAttempt.Blocked -> ticket.cause = result.cause
                        }
                    } finally {
                        // Count only time genuinely waiting for a resource, not
                        // seconds spent checking gates or executing a long batch.
                        deadline += (nowMs() - started).coerceAtLeast(0L)
                    }
                }
                if (ticket.cancelled) return GerIntentOutcome.Cancelled
                val remaining = deadline - nowMs()
                if (remaining <= 0) return GerIntentOutcome.TimedOut(ticket.cause)
                val before = revision.value
                withTimeoutOrNull(minOf(pollMs, remaining)) { revision.first { it != before } }
            }
        } catch (e: CancellationException) {
            throw e // Disconnected MCP caller removes its unexecuted intention.
        } finally {
            synchronized(this) {
                pending[owner]?.let {
                    it.remove(ticket)
                    if (it.isEmpty()) pending.remove(owner)
                }
                inFlight.remove(owner to id)
                notifyChanged()
            }
        }
    }
}
