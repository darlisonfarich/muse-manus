package com.wangzan101.mika.core.voice

/**
 * Turns the hub's growing segment list into "what is new to play". The player calls [update] on every
 * state emission; it gets back only the segments it has not seen, in order, and a reset flag when the
 * stream was cancelled or replaced (the player must then stop and drop its queue).
 */
class SegmentCursor {
    data class Update(val reset: Boolean, val fresh: List<ByteArray>)

    private var turn: String? = null
    private var seen = 0

    fun update(audioTurnId: String?, segments: List<ByteArray>): Update {
        if (audioTurnId == null) {
            val had = turn != null
            turn = null
            seen = 0
            return Update(reset = had, fresh = emptyList())
        }
        var reset = false
        if (turn != audioTurnId || segments.size < seen) {
            // A different turn (or a list that shrank) means the earlier stream is stale.
            reset = turn != null
            turn = audioTurnId
            seen = 0
        }
        val fresh = if (segments.size > seen) segments.subList(seen, segments.size).toList() else emptyList()
        seen = segments.size
        return Update(reset, fresh)
    }
}
