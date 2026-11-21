package com.darlisonfarich.gadgetlink.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InvokeAuditLogTest {
    @Test
    fun appendsOneLinePerInvocationAndRotatesOneGeneration() {
        val dir = Files.createTempDirectory("audit").toFile()
        try {
            val log = InvokeAuditLog(java.io.File(dir, "sub/link-invoke-audit.log"), maxBytes = 200)
            val entry = InvokeAuditEntry("inv-1", "linktest.ping", 1_700_000_000_000, 3, "ok", false, 120)
            log.append(entry)
            assertEquals(listOf("1700000000000|inv-1|linktest.ping|ok|3ms|120B"), log.tail(10))
            repeat(10) { log.append(InvokeAuditEntry("inv-$it", "linktest.ping", 1, 1, "timeout", true, 80)) }
            assertTrue(java.io.File(dir, "sub/link-invoke-audit.log.1").isFile)
            assertTrue(log.tail(1).single().endsWith("|timeout|1ms|80B|dedup"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
