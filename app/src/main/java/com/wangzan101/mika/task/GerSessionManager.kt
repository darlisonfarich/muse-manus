package com.wangzan101.mika.task

import java.util.UUID

/**
 * GER phase 1: one leased direct-phone session, with app claims shared with TaskCenter.
 *
 * This does not increase the number of virtual displays or change the remote access
 * policy. Later phases can extend the slot policy without giving up ownership checks.
 */
class GerSessionManager(private val resources: ResourceScheduler) {
    private var directOwner: String? = null

    /** A direct session holds one slot until explicit close, TTL expiry or failed creation. */
    @Synchronized
    fun acquireDirect(): DirectLease? {
        if (directOwner != null) return null
        val owner = "ger-direct:${UUID.randomUUID()}"
        directOwner = owner
        resources.registerExternalOwner(owner)
        return DirectLease(owner, this)
    }

    /** The lease is idempotently releasable; an expired lease cannot claim an app. */
    class DirectLease internal constructor(
        val ownerKey: String,
        private val manager: GerSessionManager,
    ) {
        fun reserveApp(packageName: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean =
            manager.reserveApp(ownerKey, packageName, holderActive, stillUsed)

        fun appFree(packageName: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean =
            manager.appFree(ownerKey, packageName, holderActive, stillUsed)

        fun release() = manager.releaseDirect(ownerKey)
    }

    @Synchronized
    private fun reserveApp(owner: String, packageName: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (directOwner != owner) return false
        return resources.reserveApp(packageName, owner, holderActive, stillUsed)
    }

    @Synchronized
    private fun appFree(owner: String, packageName: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (directOwner != owner) return false
        return resources.appFree(packageName, owner, holderActive, stillUsed)
    }

    @Synchronized
    private fun releaseDirect(owner: String) {
        if (directOwner != owner) return
        resources.releaseOwner(owner)
        directOwner = null
    }
}
