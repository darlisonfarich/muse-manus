package com.wangzan101.mika.mcp

import java.io.File
import java.io.FileOutputStream

/**
 * Durable, append-only checkpoints for direct MCP phone_run batches.
 *
 * The existing direct-phone-audit.jsonl remains the authority for whether an
 * Android action MAY be attempted. This ledger only records a safe, contiguous
 * prefix of actions whose executor returned success after that audit marker.
 *
 * No command arguments, UI text, credentials, screenshots, or results go to disk.
 * Caller must hold AndroidDirectPhoneHost's mutex for all accesses.
 */
internal class DirectPhoneBatchCheckpoints(private val file: File) {
    data class Snapshot(val completed: Int, val total: Int, val fresh: Boolean)

    sealed interface OpenResult {
        data class Ready(val snapshot: Snapshot) : OpenResult
        data object Conflict : OpenResult
        data object Corrupt : OpenResult
    }

    private data class Batch(val fingerprint: String, val total: Int, var completed: Int = 0)
    private val batches = HashMap<String, Batch>()
    private val damaged = HashSet<String>()
    private var loaded = false
    private var broken = false

    /** key and fingerprint are SHA-256 hex digests, never raw owner/ID/arguments. */
    fun open(key: String, fingerprint: String, total: Int): OpenResult {
        if (!isDigest(key) || !isDigest(fingerprint) || total !in 1..30) return OpenResult.Corrupt
        load()
        if (broken || key in damaged) return OpenResult.Corrupt
        batches[key]?.let { existing ->
            if (existing.fingerprint != fingerprint || existing.total != total) return OpenResult.Conflict
            return OpenResult.Ready(Snapshot(existing.completed, existing.total, fresh = false))
        }
        append("OPEN|$key|$fingerprint|$total")
        batches[key] = Batch(fingerprint, total)
        return OpenResult.Ready(Snapshot(0, total, fresh = true))
    }

    /**
     * Called only after the underlying action's successful outcome was audited.
     * An absent/noncontiguous batch must never be silently reconstructed.
     */
    fun completed(key: String, index: Int): Boolean {
        load()
        if (broken || key in damaged) return false
        val batch = batches[key] ?: return false
        if (index != batch.completed || index !in 0 until batch.total) return false
        append("OK|$key|$index")
        batch.completed++
        return true
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val fields = line.split('|')
            val key = fields.getOrNull(1)
            fun corrupt() {
                if (key != null && isDigest(key)) damaged.add(key) else broken = true
            }
            when (fields.firstOrNull()) {
                "OPEN" -> {
                    val total = fields.getOrNull(3)?.toIntOrNull()
                    val fingerprint = fields.getOrNull(2)
                    if (fields.size != 4 || key == null || !isDigest(key) ||
                        fingerprint == null || !isDigest(fingerprint) ||
                        total == null || total !in 1..30 || batches.containsKey(key)
                    ) corrupt()
                    else batches[key] = Batch(fingerprint, total)
                }
                "OK" -> {
                    val index = fields.getOrNull(2)?.toIntOrNull()
                    val batch = key?.let(batches::get)
                    if (fields.size != 3 || batch == null || index != batch.completed ||
                        batch.completed >= batch.total
                    ) corrupt()
                    else batch.completed++
                }
                else -> corrupt()
            }
        }
    }

    private fun append(event: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((event + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun isDigest(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
