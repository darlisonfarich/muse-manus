package com.wangzan101.mika.mcp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectPhoneJournalInspectionTest {
    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)
    private fun temp(): File = File.createTempFile("muse-direct-audit-", ".jsonl").also { it.delete() }
    private fun mark(key: String, command: String, detail: String = "fields") =
        "$key|1794180000000|$command|$detail\n"

    @Test fun missingRecordIsNotProofOfNonExecution() {
        val f = temp()
        try {
            assertEquals(DirectPhoneJournalInspection.State.NOT_SEEN,
                DirectPhoneJournalInspection.lookup(f, ownerA))
        } finally { f.delete() }
    }

    @Test fun pendingAndCompletedAreReadWithoutWritingAnything() {
        val f = temp()
        try {
            f.writeText(mark(ownerA, "phone_launch_app", "package,cold"))
            assertEquals(DirectPhoneJournalInspection.State.PENDING_UNKNOWN,
                DirectPhoneJournalInspection.lookup(f, ownerA))
            val pendingSize = f.length()
            assertEquals(DirectPhoneJournalInspection.State.NOT_SEEN,
                DirectPhoneJournalInspection.lookup(f, ownerB))
            assertEquals(pendingSize, f.length())
            f.appendText(mark(ownerA, "completed", "ok"))
            assertEquals(DirectPhoneJournalInspection.State.COMPLETED_OK,
                DirectPhoneJournalInspection.lookup(f, ownerA))
        } finally { f.delete() }
    }

    @Test fun failureAfterPendingIsNotTreatedAsSafeRetry() {
        val f = temp()
        try {
            f.writeText(mark(ownerA, "phone_launch_app") +
                mark(ownerA, "completed", "not_applied"))
            assertEquals(DirectPhoneJournalInspection.State.COMPLETED_UNCONFIRMED,
                DirectPhoneJournalInspection.lookup(f, ownerA))
            f.writeText(mark(ownerA, "phone_launch_app") +
                mark(ownerA, "completed", "unknown"))
            assertEquals(DirectPhoneJournalInspection.State.COMPLETED_UNCONFIRMED,
                DirectPhoneJournalInspection.lookup(f, ownerA))
        } finally { f.delete() }
    }

    @Test fun duplicatedOrTruncatedOwnerRecordFailsClosed() {
        val f = temp()
        try {
            f.writeText(mark(ownerA, "phone_launch_app") +
                mark(ownerA, "phone_launch_app"))
            assertEquals(DirectPhoneJournalInspection.State.CORRUPT,
                DirectPhoneJournalInspection.lookup(f, ownerA))
            f.writeText(ownerA + "|1794180000000|completed\n")
            assertEquals(DirectPhoneJournalInspection.State.CORRUPT,
                DirectPhoneJournalInspection.lookup(f, ownerA))
            assertEquals(DirectPhoneJournalInspection.State.NOT_SEEN,
                DirectPhoneJournalInspection.lookup(f, ownerB))
        } finally { f.delete() }
    }

    @Test fun malformedDigestAndCompletedWithoutPendingFailClosed() {
        val f = temp()
        try {
            assertEquals(DirectPhoneJournalInspection.State.CORRUPT,
                DirectPhoneJournalInspection.lookup(f, "raw-request-id"))
            f.writeText(mark(ownerA, "completed", "ok"))
            assertEquals(DirectPhoneJournalInspection.State.CORRUPT,
                DirectPhoneJournalInspection.lookup(f, ownerA))
        } finally { f.delete() }
    }
}
