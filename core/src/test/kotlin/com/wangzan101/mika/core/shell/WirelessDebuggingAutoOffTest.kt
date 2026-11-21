package com.wangzan101.mika.core.shell

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A gadget-owned launch opens wireless debugging only for itself and leaves the user's setting off afterwards. */
class WirelessDebuggingAutoOffTest {
    private var on: Boolean? = false
    private var restore = true
    private var refuseOff = false
    private var stopsHelper = false
    private var learned = 0
    private var offWrites = 0
    private var helperAlive = true

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { true }, canWrite = { true }, read = { on }, write = { on = true; true },
        now = { testScheduler.currentTime },
        restoreAfterLaunch = { restore },
        writeOff = { offWrites++; if (refuseOff) false else { on = false; true } },
        offStopsHelper = { stopsHelper },
        learnOffStopsHelper = { stopsHelper = true; learned++ },
    )

    @Test
    fun closesWhatItOpenedAndKeepsTheHelper() = runTest {
        val a = autoOn()
        assertTrue(a.turnOn())
        assertEquals(AutoOffOutcome.Restored, a.closeAfterLaunch { helperAlive })
        assertEquals(false, on)
        assertEquals(1, offWrites)
        assertFalse(a.writing)
        assertEquals(AutoOffOutcome.Restored, a.lastOff.value)
        // Only once per opening.
        assertEquals(AutoOffOutcome.NotOurs, a.closeAfterLaunch { helperAlive })
        assertEquals(1, offWrites)
    }

    @Test
    fun neverClosesASwitchTheUserLeftOn() = runTest {
        on = true
        val a = autoOn()
        assertEquals(AutoOffOutcome.NotOurs, a.closeAfterLaunch { helperAlive })
        assertEquals(true, on)
        assertEquals(0, offWrites)
    }

    @Test
    fun withoutTheGadgetPolicyTheOriginalBehaviourStays() = runTest {
        restore = false
        val a = autoOn()
        assertTrue(a.turnOn())
        assertEquals(AutoOffOutcome.KeptOn, a.closeAfterLaunch { helperAlive })
        assertEquals(true, on)
        assertEquals(0, offWrites)
    }

    @Test
    fun learnsWhenClosingStopsTheHelperAndThenLeavesItOn() = runTest {
        val a = autoOn()
        assertTrue(a.turnOn())
        helperAlive = false
        assertEquals(AutoOffOutcome.HelperStopped, a.closeAfterLaunch { helperAlive })
        assertEquals(1, learned)
        // The relaunch after that loss keeps the switch on instead of looping.
        helperAlive = true
        assertTrue(a.turnOn())
        assertEquals(AutoOffOutcome.KeptOnStopsHelper, a.closeAfterLaunch { helperAlive })
        assertEquals(true, on)
        assertEquals(1, offWrites)
    }

    @Test
    fun aLateHelperLossShortlyAfterClosingIsAlsoLearned() = runTest {
        val a = autoOn()
        assertTrue(a.turnOn())
        assertEquals(AutoOffOutcome.Restored, a.closeAfterLaunch { true })
        testScheduler.advanceTimeBy(5_000)
        a.helperStopped()
        assertEquals(1, learned)
        assertEquals(AutoOffOutcome.HelperStopped, a.lastOff.value)
        // Counted once.
        a.helperStopped()
        assertEquals(1, learned)
    }

    @Test
    fun aHelperLossLongAfterClosingIsNotBlamedOnIt() = runTest {
        val a = autoOn()
        assertTrue(a.turnOn())
        a.closeAfterLaunch { true }
        testScheduler.advanceTimeBy(WirelessDebuggingAutoOn.OFF_BLAME_MS + 1_000)
        a.helperStopped()
        assertEquals(0, learned)
        assertEquals(AutoOffOutcome.Restored, a.lastOff.value)
    }

    @Test
    fun aRefusedCloseIsReported() = runTest {
        refuseOff = true
        val a = autoOn()
        assertTrue(a.turnOn())
        assertEquals(AutoOffOutcome.Refused, a.closeAfterLaunch { true })
        assertEquals(0, learned)
    }

    @Test
    fun aSwitchOpenedByTheBootRestoreIsClosedLikeOurs() = runTest {
        on = true // written on by the boot restore, not by turnOn
        val a = autoOn()
        a.adoptOpened()
        assertEquals(AutoOffOutcome.Restored, a.closeAfterLaunch { helperAlive })
        assertEquals(false, on)
    }
}
