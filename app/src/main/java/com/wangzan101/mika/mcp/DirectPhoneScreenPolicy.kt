package com.wangzan101.mika.mcp

/**
 * Read-only tools inspect the agent's virtual display. They must not require
 * the user's physical screen to be on/unlocked merely to observe that display.
 * Both paths still require an authenticated owner, a GER lease and the existing
 * sensitive-screen checks; writes keep ready() and STRICT approvals.
 */
internal object DirectPhoneScreenPolicy {
    val reads = setOf("phone_observe", "phone_list_apps", "phone_screenshot", "phone_reconcile")

    fun requiresInteractivePhysicalScreen(name: String): Boolean = name !in reads
}
