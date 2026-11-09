package com.wangzan101.mika.core.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordLogicTest {
    @Test fun enableWithoutPermissionIsHonest() {
        val machine = WakeStateMachine()
        assertEquals(WakePhase.PermissionDenied, machine.enable(false))
        assertFalse(machine.prepared())
        machine.disable()
        assertEquals(WakePhase.Off, machine.phase)
    }

    @Test fun happyTurnMovesThroughEveryStage() {
        val machine = WakeStateMachine()
        assertEquals(WakePhase.Preparing, machine.enable(true))
        assertTrue(machine.prepared())
        assertTrue(machine.detected())
        assertTrue(machine.sent())
        assertTrue(machine.answer())
        assertTrue(machine.finished())
        assertEquals(WakePhase.Listening, machine.phase)
        machine.disable()
        assertEquals(WakePhase.Off, machine.phase)
    }

    @Test fun wakeDetectorIsPausedForWholeTtsPlaybackAndResumesAfterCompletion() {
        val machine = WakeStateMachine()
        machine.enable(true)
        assertTrue(machine.prepared())
        assertTrue(machine.detectorListening)

        assertTrue(machine.detected())
        assertTrue(machine.sent())
        assertTrue(machine.answer())
        assertEquals(WakePhase.Playing, machine.phase)
        assertFalse(machine.detectorListening)
        assertFalse(machine.resumeDetectorAfterPlayback())

        // Playback begins: the detector is explicitly gated off, not merely expected
        // to ignore the TTS hypothesis.
        assertTrue(machine.pauseDetectorForPlayback())
        assertEquals(WakePhase.Paused, machine.phase)
        assertFalse(machine.detectorListening)
        assertFalse(machine.detected())
        assertFalse(machine.pauseDetectorForPlayback())

        // MediaPlayer completion/error/timeout-stop is the only point that reopens listening.
        assertTrue(machine.resumeDetectorAfterPlayback())
        assertEquals(WakePhase.Listening, machine.phase)
        assertTrue(machine.detectorListening)
        assertTrue(machine.detected())
    }

    @Test fun rejectedTransitionsDoNotAdvance() {
        val machine = WakeStateMachine()
        assertFalse(machine.detected())
        machine.enable(true)
        assertFalse(machine.sent())
        assertFalse(machine.answer())
        assertEquals(WakePhase.Preparing, machine.phase)
    }

    @Test fun errorPauseResumeAndPermissionLoss() {
        val machine = WakeStateMachine()
        machine.enable(true)
        machine.error()
        assertEquals(WakePhase.Error, machine.phase)
        assertTrue(machine.pause())
        assertTrue(machine.resume())
        assertTrue(machine.prepared())
        machine.permissionLost()
        assertEquals(WakePhase.PermissionDenied, machine.phase)
        assertFalse(machine.pause())
        machine.disable()
        assertEquals(WakePhase.Off, machine.phase)
    }

    @Test fun matchRequiresPartialThenFinal() {
        val gate = WakePhraseGate()
        assertFalse(gate.accept("oi muse", false, 1000))
        assertTrue(gate.accept("oi muse", true, 1300))
        assertFalse(gate.accept("oi muse", true, 1400))
    }

    @Test fun repeatedPartialsNeverCountAsTwoMatches() {
        val gate = WakePhraseGate()
        assertFalse(gate.accept("oi muse", false, 100))
        assertFalse(gate.accept("oi muse", false, 150))
        assertFalse(gate.accept("", true, 160))
        assertFalse(gate.accept("oi muse", true, 200))
    }

    @Test fun finalOnlyCannotWake() {
        assertFalse(WakePhraseGate().accept("oi muse", true, 100))
    }

    @Test fun expiredAndReversedClockCannotWake() {
        val gate = WakePhraseGate(500)
        gate.accept("oi muse", false, 100)
        assertFalse(gate.accept("oi muse", true, 601))
        gate.accept("oi muse", false, 3000)
        assertFalse(gate.accept("oi muse", true, 2000))
    }

    @Test fun mismatchedFinalClearsCandidate() {
        val gate = WakePhraseGate()
        gate.accept("oi muse", false, 50)
        assertFalse(gate.accept("outro", true, 100))
        assertFalse(gate.accept("oi muse", true, 120))
    }

    @Test fun normalizePortugueseAndRejectSubstrings() {
        assertEquals("oi muse", WakePhraseGate.normalize(" OI, MUSE! "))
        assertEquals("oi muse", WakePhraseGate.normalize("Ói Múse"))
        val gate = WakePhraseGate()
        assertFalse(gate.accept("oi muse por favor", false, 0))
        assertFalse(gate.accept("oi muse por favor", true, 1))
    }

    @Test fun circularRingPreservesMostRecentSamples() {
        val ring = ShortPcmRing(4)
        ring.add(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
        ring.add(shortArrayOf(4, 5))
        assertArrayEquals(shortArrayOf(2, 3, 4, 5), ring.snapshot())
        assertEquals(4, ring.size)
    }

    @Test fun ringAcceptsPartialChunksAndClears() {
        val ring = ShortPcmRing(3)
        ring.add(shortArrayOf(1, 2, 3), 2)
        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot())
        ring.add(shortArrayOf(9, 8, 7, 6))
        assertArrayEquals(shortArrayOf(8, 7, 6), ring.snapshot())
        ring.clear()
        assertArrayEquals(shortArrayOf(), ring.snapshot())
        assertEquals(0, ring.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ringRejectsOutOfBoundsLength() {
        ShortPcmRing(2).add(shortArrayOf(1), 2)
    }
}
