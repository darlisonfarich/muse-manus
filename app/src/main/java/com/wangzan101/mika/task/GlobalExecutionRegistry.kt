package com.wangzan101.mika.task

/**
 * GER: one process-wide admission ledger shared by TaskCenter and the direct MCP host.
 *
 * Phase 1 deliberately preserves TaskCenter's existing 1..3 task capacity and queue:
 * task admission is still decided there. The direct host has one exclusive virtual
 * screen session and may only operate while no TaskCenter run is admitted.
 *
 * This is not a second queue, a model, or a source of user approval. A task that
 * starts while an MCP session is parked takes priority: the MCP host must check
 * [canUseDirect] before every action, and must not reclaim the session's screen.
 * Multi-slot ownership belongs to later phases; phase 2 adds a separate
 * nonpersistent per-owner queue for direct calls only.
 */
class GlobalExecutionRegistry {
    data class Snapshot(val admittedTasks: Int, val directSession: Boolean)

    /** Phase 2: waiting MCP calls; not part of TaskCenter's persistent task queue. */
    val intentions = GerIntentQueue()

    private val taskOwners = LinkedHashSet<String>()
    private var directOwner: String? = null

    /** Register only tasks actually admitted to TaskCenter.running, never queued tasks. */
    @Synchronized fun taskStarted(owner: String) {
        require(owner.isNotBlank())
        if (taskOwners.add(owner)) intentions.notifyChanged()
    }

    @Synchronized fun taskEnded(owner: String): Boolean = taskOwners.remove(owner).also {
        if (it) intentions.notifyChanged()
    }

    /**
     * Claim the sole direct-host session. Returns false if any task is running or
     * another authenticated MCP owner already holds the session.
     * Repeated calls by the same owner are idempotent, not nested leases.
     */
    @Synchronized fun claimDirect(owner: String): Boolean {
        require(owner.isNotBlank())
        if (taskOwners.isNotEmpty()) return false
        if (directOwner == owner) return true // No change; do not wake a blocked queue head in a tight loop.
        if (directOwner != null) return false
        directOwner = owner
        intentions.notifyChanged()
        return true
    }

    /** If a task started after the session was claimed, the host must stand aside. */
    @Synchronized fun canUseDirect(owner: String): Boolean =
        directOwner == owner && taskOwners.isEmpty()

    @Synchronized fun releaseDirect(owner: String): Boolean {
        if (directOwner != owner) return false
        directOwner = null
        intentions.notifyChanged()
        return true
    }

    /** Used by ResourceScheduler to distinguish a live MCP holder from a stale run. */
    @Synchronized fun isDirectOwner(owner: String): Boolean = directOwner == owner
    @Synchronized fun isActiveOwner(owner: String): Boolean =
        owner in taskOwners || directOwner == owner

    @Synchronized fun hasRunningTasks(): Boolean = taskOwners.isNotEmpty()
    @Synchronized fun snapshot(): Snapshot = Snapshot(taskOwners.size, directOwner != null)
}
