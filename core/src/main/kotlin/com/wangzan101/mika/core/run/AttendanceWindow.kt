package com.wangzan101.mika.core.run

/**
 * Live presence evidence. A powered-on, unlocked screen alone is insufficient: we also
 * need a recent unlock or user interaction observed locally (monotonic elapsed time).
 */
object AttendanceWindow {
    const val PRESENT_MS = 5 * 60_000L
    const val REACHABLE_MS = 30 * 60_000L

    fun classify(interactive: Boolean, unlocked: Boolean, lastUserAtMs: Long?, nowMs: Long): Attendance {
        if (!interactive || !unlocked || lastUserAtMs == null || lastUserAtMs > nowMs) return Attendance.Unattended
        return when (nowMs - lastUserAtMs) {
            in 0..PRESENT_MS -> Attendance.Present
            in (PRESENT_MS + 1)..REACHABLE_MS -> Attendance.Reachable
            else -> Attendance.Unattended
        }
    }
}
