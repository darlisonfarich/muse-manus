package com.wangzan101.mika.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GerSessionManagerTest {
    private data class Run(val conversationId: String, val goal: String) {
        val key = "$conversationId/$goal"
    }

    @Test fun directSlotIsExclusiveAndReleasedIdempotently() {
        val resources = ResourceScheduler()
        val ger = GerSessionManager(resources)
        val first = ger.acquireDirect()!!
        assertNull(ger.acquireDirect())
        assertTrue(resources.isExternalOwnerActive(first.ownerKey))
        first.release()
        first.release()
        assertFalse(resources.isExternalOwnerActive(first.ownerKey))
        val second = ger.acquireDirect()
        assertNotNull(second)
        second!!.release()
    }

    @Test fun directAndLocalRunsUseSamePackageClaims() {
        val resources = ResourceScheduler()
        val ger = GerSessionManager(resources)
        val center = TaskCenter(
            limit = { ParallelLimit(2, null) },
            conversationOf = { run: Run -> run.conversationId },
            keyOf = { run: Run -> run.key },
            resources = resources,
            create = { c, g -> Run(c, g) },
        )
        val local = (center.tryStart("local", "chat") as StartResult.Started).run
        val lease = ger.acquireDirect()!!
        val active: (String) -> Boolean = { it == local.key || resources.isExternalOwnerActive(it) }

        assertTrue(lease.reserveApp("com.chat", active) { true })
        assertFalse(center.reserve("com.chat", local.key) { true })
        assertTrue(lease.appFree("com.mail", active) { false })
        // A released direct lease cannot reacquire a package, and local work can take over.
        lease.release()
        assertFalse(lease.reserveApp("com.chat", active) { false })
        assertTrue(center.reserve("com.chat", local.key) { true })
        center.onEnded(local)
    }

    @Test fun localClaimsBlockDirectUntilLocalReleases() {
        val resources = ResourceScheduler()
        val ger = GerSessionManager(resources)
        val center = TaskCenter(
            limit = { ParallelLimit(2, null) },
            conversationOf = { run: Run -> run.conversationId },
            keyOf = { run: Run -> run.key },
            resources = resources,
            create = { c, g -> Run(c, g) },
        )
        val local = (center.tryStart("a", "one") as StartResult.Started).run
        assertTrue(center.reserve("com.chat", local.key) { true })
        val lease = ger.acquireDirect()!!
        val active: (String) -> Boolean = { it == local.key || resources.isExternalOwnerActive(it) }
        assertFalse(lease.reserveApp("com.chat", active) { true })
        center.onEnded(local)
        assertTrue(lease.reserveApp("com.chat", active) { true })
        lease.release()
    }
}
