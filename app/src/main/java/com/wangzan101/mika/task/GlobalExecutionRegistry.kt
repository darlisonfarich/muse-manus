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
    data class Budgets(val maxTasks: Int = 3, val maxTasksPerOwner: Int = 1, val maxDisplaySlots: Int = 3,
        val sessionTtlMs: Long = 15 * 60_000L, val taskTtlMs: Long = 60 * 60_000L)
    enum class Refusal { task_budget_exhausted, owner_budget_exhausted, task_ttl_exhausted, slot_budget_exhausted }
    data class Admission(val slot: Int? = null, val refusal: Refusal? = null) { val admitted get() = refusal == null }
    var budgets: Budgets = Budgets()

    /** Phase 2: waiting MCP calls; not part of TaskCenter's persistent task queue. */
    val intentions = GerIntentQueue()

    private val taskOwners = LinkedHashSet<String>()
    private val taskGroups = HashMap<String, String>()
    private val taskStartedAt = HashMap<String, Long>()
    private var directOwner: String? = null
    private val slots = LinkedHashMap<Int, String>()

    /** Register only tasks actually admitted to TaskCenter.running, never queued tasks. */
    @Synchronized fun taskStarted(owner: String, concurrencyKey: String = owner) {
        require(owner.isNotBlank())
        if (owner in taskOwners) return
        require(taskOwners.size < budgets.maxTasks) { Refusal.task_budget_exhausted.name }
        require(taskGroups.values.count { it == concurrencyKey } < budgets.maxTasksPerOwner) { Refusal.owner_budget_exhausted.name }
        require(taskStartedAt.values.none { System.currentTimeMillis() - it >= budgets.taskTtlMs }) { Refusal.task_ttl_exhausted.name }
        if (taskOwners.add(owner)) {
            taskGroups[owner] = concurrencyKey
            taskStartedAt[owner] = System.currentTimeMillis()
            intentions.notifyChanged()
        }
    }

    @Synchronized fun canAdmitTask(concurrencyKey: String): Boolean =
        taskOwners.size < budgets.maxTasks &&
            taskGroups.values.count { it == concurrencyKey } < budgets.maxTasksPerOwner &&
            taskStartedAt.values.none { System.currentTimeMillis() - it >= budgets.taskTtlMs }

    @Synchronized fun taskEnded(owner: String): Boolean = taskOwners.remove(owner).also {
        if (it) { taskGroups.remove(owner); taskStartedAt.remove(owner); intentions.notifyChanged() }
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

    /** Atomically lease the first free GER display slot. The physical user display is never represented here. */
    @Synchronized fun claimSlot(owner: String): Admission {
        require(owner.isNotBlank())
        slots.entries.firstOrNull { it.value == owner }?.let { return Admission(it.key) }
        val slot = (0 until budgets.maxDisplaySlots).firstOrNull { it !in slots }
            ?: return Admission(refusal = Refusal.slot_budget_exhausted)
        slots[slot] = owner
        intentions.notifyChanged()
        return Admission(slot)
    }

    @Synchronized fun releaseSlot(owner: String): Boolean {
        val removed = slots.entries.firstOrNull { it.value == owner }?.let { slots.remove(it.key) } != null
        if (removed) intentions.notifyChanged()
        return removed
    }
    @Synchronized fun slotOf(owner: String): Int? = slots.entries.firstOrNull { it.value == owner }?.key
    @Synchronized fun slotOwners(): Map<Int, String> = slots.toMap()

    /** If a task started after the session was claimed, the host must stand aside. */
    @Synchronized fun canUseDirect(owner: String): Boolean =
        directOwner == owner && taskOwners.isEmpty()

    @Synchronized fun canUseSlot(owner: String): Boolean = owner in slots.values && taskOwners.isEmpty()

    @Synchronized fun releaseDirect(owner: String): Boolean {
        if (directOwner != owner) return false
        directOwner = null
        intentions.notifyChanged()
        return true
    }

    /** Used by ResourceScheduler to distinguish a live MCP holder from a stale run. */
    @Synchronized fun isDirectOwner(owner: String): Boolean = directOwner == owner || owner in slots.values
    @Synchronized fun isActiveOwner(owner: String): Boolean =
        owner in taskOwners || directOwner == owner || owner in slots.values

    @Synchronized fun hasRunningTasks(): Boolean = taskOwners.isNotEmpty()
    @Synchronized fun snapshot(): Snapshot = Snapshot(taskOwners.size, directOwner != null || slots.isNotEmpty())
}
