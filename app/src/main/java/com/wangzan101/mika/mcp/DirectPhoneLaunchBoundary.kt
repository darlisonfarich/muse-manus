package com.wangzan101.mika.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DirectLaunchOutcome<out T> {
    data class Completed<T>(val value: T) : DirectLaunchOutcome<T>
    data object TimedOut : DirectLaunchOutcome<Nothing>
    data object InFlight : DirectLaunchOutcome<Nothing>
}

/**
 * Bounds the MCP response time, NOT Android's Binder call. A blocked Binder
 * invocation cannot be safely interrupted: it may still finish after timeout.
 * Keep it running once in an independent supervisor scope, deny overlapping
 * launches and require observation/reconciliation before issuing a fresh ID.
 *
 * This class never retries a launch or changes the caller's durable journal.
 */
internal class DirectPhoneLaunchBoundary(
    private val workerScope: CoroutineScope,
    private val timeoutMs: Long = 10_000L,
    private val onSettled: () -> Unit = {},
) {
    init { require(timeoutMs > 0L) }

    @Volatile private var active: Deferred<*>? = null

    fun isInFlight(): Boolean = active?.isCompleted == false

    suspend fun <T : Any> execute(action: suspend () -> T): DirectLaunchOutcome<T> {
        // LAZY avoids executing anything when an earlier launch is unresolved.
        val task = workerScope.async(start = CoroutineStart.LAZY) { action() }
        synchronized(this) {
            if (isInFlight()) {
                task.cancel()
                return DirectLaunchOutcome.InFlight
            }
            active = task
        }
        task.invokeOnCompletion {
            synchronized(this) {
                if (active === task) active = null
            }
            onSettled()
        }
        task.start()
        // Only the wait is cancelled. The independent, already-committed
        // Android action must never be blindly retried or cancelled on timeout.
        val result = withTimeoutOrNull(timeoutMs) { task.await() }
        return if (result == null) DirectLaunchOutcome.TimedOut
        else DirectLaunchOutcome.Completed(result)
    }
}
