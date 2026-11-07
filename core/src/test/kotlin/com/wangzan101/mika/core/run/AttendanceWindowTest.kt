package com.wangzan101.mika.core.run

import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceWindowTest {
    @Test fun presentRequiresInteractiveUnlockWithRecentEvidence() {
        assertEquals(Attendance.Present, AttendanceWindow.classify(true, true, 0, 5 * 60_000L))
        assertEquals(Attendance.Reachable, AttendanceWindow.classify(true, true, 0, 5 * 60_000L + 1))
        assertEquals(Attendance.Reachable, AttendanceWindow.classify(true, true, 0, 30 * 60_000L))
        assertEquals(Attendance.Unattended, AttendanceWindow.classify(true, true, 0, 30 * 60_000L + 1))
        assertEquals(Attendance.Unattended, AttendanceWindow.classify(true, true, null, 3))
        assertEquals(Attendance.Unattended, AttendanceWindow.classify(true, true, 600, 300))
        assertEquals(Attendance.Unattended, AttendanceWindow.classify(false, true, 200, 300))
        assertEquals(Attendance.Unattended, AttendanceWindow.classify(true, false, 200, 300))
    }
}
