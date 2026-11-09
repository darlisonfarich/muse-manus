package com.wangzan101.mika.mcp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPhoneBatchCheckpointsTest {
    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val signature = "c".repeat(64)
    private fun ledgerFile(): File = File.createTempFile("muse-manus-batch-", ".jsonl").also { it.delete() }

    @Test fun survivingRestartRecoversOnlyDurablySuccessfulPrefix() {
        val file = ledgerFile()
        try {
            val first = DirectPhoneBatchCheckpoints(file)
            assertEquals(
                DirectPhoneBatchCheckpoints.OpenResult.Ready(
                    DirectPhoneBatchCheckpoints.Snapshot(0, 3, fresh = true)
                ), first.open(key, signature, 3)
            )
            assertTrue(first.completed(key, 0))
            assertTrue(first.completed(key, 1))
            assertEquals(
                DirectPhoneBatchCheckpoints.OpenResult.Ready(
                    DirectPhoneBatchCheckpoints.Snapshot(2, 3, fresh = false)
                ), DirectPhoneBatchCheckpoints(file).open(key, signature, 3)
            )
            assertTrue(DirectPhoneBatchCheckpoints(file).completed(key, 2))
            assertEquals(3, (DirectPhoneBatchCheckpoints(file).open(key, signature, 3)
                as DirectPhoneBatchCheckpoints.OpenResult.Ready).snapshot.completed)
        } finally { file.delete() }
    }

    @Test fun repeatedOrSkippedStepNeverCreatesFalseCheckpoint() {
        val file = ledgerFile()
        try {
            val ledger = DirectPhoneBatchCheckpoints(file)
            ledger.open(key, signature, 3)
            assertFalse(ledger.completed(key, 1))
            assertTrue(ledger.completed(key, 0))
            assertFalse(ledger.completed(key, 0))
            assertFalse(ledger.completed(key, 3))
            assertEquals(1, (DirectPhoneBatchCheckpoints(file).open(key, signature, 3)
                as DirectPhoneBatchCheckpoints.OpenResult.Ready).snapshot.completed)
        } finally { file.delete() }
    }

    @Test fun changedBatchWithSameRequestIdIsRejectedAndOwnersRemainIsolated() {
        val file = ledgerFile()
        try {
            val ledger = DirectPhoneBatchCheckpoints(file)
            ledger.open(key, signature, 2)
            assertTrue(ledger.completed(key, 0))
            assertEquals(DirectPhoneBatchCheckpoints.OpenResult.Conflict,
                DirectPhoneBatchCheckpoints(file).open(key, "d".repeat(64), 2))
            assertEquals(DirectPhoneBatchCheckpoints.OpenResult.Conflict,
                DirectPhoneBatchCheckpoints(file).open(key, signature, 3))
            assertEquals(0, (DirectPhoneBatchCheckpoints(file).open(otherKey, signature, 2)
                as DirectPhoneBatchCheckpoints.OpenResult.Ready).snapshot.completed)
        } finally { file.delete() }
    }

    @Test fun tornOrMalformedLedgerFailsClosed() {
        val file = ledgerFile()
        try {
            file.writeText("OPEN|$key|$signature|2\nOK|$key|0\nOK|$key|wrong\n")
            assertEquals(DirectPhoneBatchCheckpoints.OpenResult.Corrupt,
                DirectPhoneBatchCheckpoints(file).open(key, signature, 2))
            // A corrupt record with an identifiable key must not poison other owners.
            assertTrue(DirectPhoneBatchCheckpoints(file).open(otherKey, signature, 2)
                is DirectPhoneBatchCheckpoints.OpenResult.Ready)
        } finally { file.delete() }
    }

    @Test fun ledgerNeverContainsUserInputOrRawRequestId() {
        val file = ledgerFile()
        try {
            val ledger = DirectPhoneBatchCheckpoints(file)
            ledger.open(key, signature, 1)
            ledger.completed(key, 0)
            val text = file.readText()
            assertFalse(text.contains("secret-password"))
            assertFalse(text.contains("raw-request-id"))
            assertEquals(2, text.trim().lines().size)
            assertTrue(text.lines().first().startsWith("OPEN|$key|$signature|"))
        } finally { file.delete() }
    }
}
