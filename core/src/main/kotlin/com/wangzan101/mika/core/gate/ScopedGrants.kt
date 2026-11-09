package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk

import java.util.UUID

/**
 * Short-lived local consent for *routine* actions. It is NOT a replacement for
 * the one-use ConfirmationGate, the kill switch or ApprovalPolicy. In particular
 * send, delete, order, payment and unclassified actions NEVER match.
 *
 * Account is an exact opaque identity supplied by the trusted local UI; the
 * Android host must not infer it from untrusted screen text or MCP arguments.
 * All grants are process-local, deliberately lost after process death.
 */
enum class ScopedActionClass { Navigate, Edit }

data class ScopedGrant(
    val id: String,
    val app: String,
    val account: String,
    val actionClass: ScopedActionClass,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
)

class ScopedGrantRegistry(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onAudit: (String, ScopedGrant) -> Unit = { _, _ -> },
) {
    companion object {
        const val MAX_TTL_MS = 15 * 60_000L
        private val APP = Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }

    private val grants = LinkedHashMap<String, ScopedGrant>()
    private fun prune(now: Long) {
        val expired = grants.values.filter { now >= it.expiresAtMs || now < it.issuedAtMs }
        expired.forEach {
            grants.remove(it.id)
            // Expiration/revocation must fail closed even when storage is full.
            runCatching { onAudit("expired", it) }
        }
    }

    /** Only the local, user-operated management screen may call this. */
    @Synchronized fun issueFromLocalUi(
        app: String, account: String, actionClass: ScopedActionClass, ttlMs: Long,
    ): ScopedGrant? {
        val now = clockMs()
        prune(now)
        val a = app.trim()
        val accountId = account.trim()
        if (!APP.matches(a) || accountId.isBlank() || accountId.length > 128 ||
            ttlMs !in 1L..MAX_TTL_MS || now > Long.MAX_VALUE - ttlMs) return null
        val grant = ScopedGrant(UUID.randomUUID().toString(), a, accountId,
            actionClass, now, now + ttlMs)
        // Durable audit must succeed before permission enters the registry.
        onAudit("issued", grant)
        grants[grant.id] = grant
        return grant
    }

    /** Matching one grant never implies that a higher-priority gate can be bypassed. */
    @Synchronized fun matches(app: String?, account: String?, actionClass: ScopedActionClass?): Boolean {
        val now = clockMs()
        prune(now)
        if (app.isNullOrBlank() || account.isNullOrBlank() || actionClass == null) return false
        return grants.values.any { it.app == app && it.account == account && it.actionClass == actionClass }
    }

    /**
     * Strict risk binding: unknown/outward/irreversible/declared approvals
     * are NEVER covered by scoped grants, even if package/account match.
     */
    @Synchronized fun coversRoutineRisk(app: String?, account: String?, risk: Risk): Boolean {
        if (risk.payment || risk.ask != null || risk.action != null) return false
        val actionClass = when (risk.level) {
            ActionRisk.Read -> ScopedActionClass.Navigate
            ActionRisk.Change -> ScopedActionClass.Edit
            else -> return false
        }
        return matches(app, account, actionClass)
    }

    @Synchronized fun active(): List<ScopedGrant> {
        prune(clockMs())
        return grants.values.toList()
    }

    @Synchronized fun revoke(id: String): Boolean {
        prune(clockMs())
        val grant = grants.remove(id) ?: return false
        runCatching { onAudit("revoked", grant) }
        return true
    }

    @Synchronized fun revokeAll() {
        val old = grants.values.toList()
        grants.clear()
        old.forEach { runCatching { onAudit("revoked", it) } }
    }
}
