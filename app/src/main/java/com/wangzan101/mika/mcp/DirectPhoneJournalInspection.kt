package com.wangzan101.mika.mcp

import java.io.File

/**
 * Read-only, owner-scoped inspection of the existing fsync audit journal.
 * The caller supplies only a SHA-256 owner/request digest, never a raw ID.
 *
 * "completed_ok" means the action executor reported success, NOT that an
 * external client has verified the current UI or may retry the command.
 * "completed_unconfirmed" is deliberately not equivalent to "not executed".
 */
internal object DirectPhoneJournalInspection {
    enum class State(val wire: String) {
        NOT_SEEN("not_seen"),
        PENDING_UNKNOWN("pending_unknown"),
        COMPLETED_OK("completed_ok"),
        COMPLETED_UNCONFIRMED("completed_unconfirmed"),
        CORRUPT("journal_corrupt"),
    }

    fun lookup(file: File, key: String): State {
        if (key.length != 64 || key.any { it !in '0'..'9' && it !in 'a'..'f' }) return State.CORRUPT
        if (!file.exists()) return State.NOT_SEEN
        if (!file.isFile) return State.CORRUPT
        var pending = false
        var finished: State? = null
        var corrupt = false
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.substringBefore('|') != key) return@forEachLine
                val parts = line.split('|', limit = 4)
                if (parts.size != 4 || parts[1].toLongOrNull() == null || parts[2].isBlank()) {
                    corrupt = true
                    return@forEachLine
                }
                when (parts[2]) {
                    "completed" -> {
                        if (!pending || finished != null) {
                            corrupt = true
                        } else {
                            finished = when (parts[3]) {
                                "ok" -> State.COMPLETED_OK
                                "not_applied", "unknown" -> State.COMPLETED_UNCONFIRMED
                                else -> State.CORRUPT
                            }
                            if (finished == State.CORRUPT) corrupt = true
                        }
                    }
                    else -> {
                        // A request_id must have exactly one pre-execution marker.
                        if (pending || finished != null) corrupt = true
                        else pending = true
                    }
                }
            }
        } catch (_: Exception) {
            return State.CORRUPT
        }
        return when {
            corrupt -> State.CORRUPT
            finished != null -> finished!!
            pending -> State.PENDING_UNKNOWN
            else -> State.NOT_SEEN
        }
    }
}
