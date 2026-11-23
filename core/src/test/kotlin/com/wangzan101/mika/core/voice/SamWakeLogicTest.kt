package com.wangzan101.mika.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SamWakeLogicTest {
    @Test fun gateAcceptsConfiguredPhrasesAndRejectsOtherSpeech() {
        val phrases = listOf("Samantha", "Oi Sam", "Ei Sam", "Acorda Sam", "Sam vem cá")
        phrases.forEach { phrase ->
            val gate = WakePhraseGate(phrases)
            assertFalse(gate.accept(phrase, false, 100))
            assertTrue(gate.accept(phrase, true, 200), phrase)
        }
        val gate = WakePhraseGate(phrases)
        assertFalse(gate.accept("Samantha", false, 100))
        assertFalse(gate.accept("abre a janela", true, 200))
    }

    @Test fun summonUsesAccessibilityDriverAndReportsNamedOutcomes() = runTest {
        class Driver(var live: Boolean = false, var result: SamCallTap = SamCallTap.Tapped) : SamSummonDriver {
            override suspend fun liveCallOpen() = live
            override suspend fun wakeScreen() = true
            override suspend fun launchAssistant() = true
            override suspend fun tapCallButton(timeoutMs: Long): SamCallTap { assertEquals(3_000L, timeoutMs); return result }
        }
        val happy = SamSummonStateMachine()
        assertEquals(SamSummonResult.Opened, happy.execute(Driver()))
        assertEquals(SamSummonPhase.Complete, happy.phase)

        val missing = SamSummonStateMachine()
        assertEquals(SamSummonResult.CallButtonNotFound, missing.execute(Driver(result = SamCallTap.NotFound)))
        assertEquals(SamSummonPhase.Failed, missing.phase)

        val live = SamSummonStateMachine()
        assertEquals(SamSummonResult.AlreadyLive, live.execute(Driver(live = true)))
        assertEquals(SamSummonPhase.NoOp, live.phase)
    }
}
