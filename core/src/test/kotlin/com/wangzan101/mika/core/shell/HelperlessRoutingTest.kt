package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.shell.HelperlessRouting.KeyPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperlessRoutingTest {
    private val helperA = Any()
    private val helperB = Any()

    @Test fun newDisplayKeepsTheTrustedHelperDisplayWhileTheHelperLivesAndFallsToTheAppWithout() {
        assertEquals(ShellOwner.Helper, HelperlessRouting.newDisplayOwner(helperUp = true))
        assertEquals(ShellOwner.InApp, HelperlessRouting.newDisplayOwner(helperUp = false))
    }

    @Test fun callsWithoutADisplayGoToTheHelperOnlyWhenItIsAlive() {
        assertEquals(ShellOwner.Helper, HelperlessRouting.globalOwner(true))
        assertEquals(ShellOwner.InApp, HelperlessRouting.globalOwner(false))
    }

    @Test fun aDisplayIsAnsweredByWhoeverMadeIt() {
        val ledger = DisplayLedger()
        ledger.record(7, ShellOwner.Helper, helperA)
        ledger.record(9, ShellOwner.InApp)
        assertEquals(ShellOwner.Helper, HelperlessRouting.displayOwner(ledger, 7, helperUp = true))
        assertEquals(ShellOwner.InApp, HelperlessRouting.displayOwner(ledger, 9, helperUp = true))
        assertEquals(ShellOwner.InApp, HelperlessRouting.displayOwner(ledger, 9, helperUp = false))
    }

    @Test fun aHelperDisplayWhoseHelperDiedIsNotSentToTheDeadHelper() {
        val ledger = DisplayLedger()
        ledger.record(7, ShellOwner.Helper, helperA)
        assertEquals(ShellOwner.InApp, HelperlessRouting.displayOwner(ledger, 7, helperUp = false))
    }

    @Test fun anUnknownDisplayFallsBackToTheGlobalRule() {
        val ledger = DisplayLedger()
        assertEquals(ShellOwner.Helper, HelperlessRouting.displayOwner(ledger, 3, helperUp = true))
        assertEquals(ShellOwner.InApp, HelperlessRouting.displayOwner(ledger, 3, helperUp = false))
    }

    @Test fun orphanedMeansAHelperDisplayOutlivedItsHelper() {
        val ledger = DisplayLedger()
        assertFalse(ledger.orphaned(null))
        ledger.record(7, ShellOwner.Helper, helperA)
        assertFalse(ledger.orphaned(helperA))
        assertTrue("helper gone", ledger.orphaned(null))
        assertTrue("helper replaced by a new process", ledger.orphaned(helperB))
    }

    @Test fun anAppDisplayIsNeverOrphanedByTheHelper() {
        val ledger = DisplayLedger()
        ledger.record(9, ShellOwner.InApp, helperToken = helperA)
        assertFalse(ledger.orphaned(null))
        assertFalse(ledger.orphaned(helperB))
    }

    @Test fun forgetAndClearDropEntries() {
        val ledger = DisplayLedger()
        ledger.record(1, ShellOwner.InApp)
        ledger.record(2, ShellOwner.Helper, helperA)
        ledger.forget(1)
        assertNull(ledger.ownerOf(1))
        assertEquals(setOf(2), ledger.ids())
        ledger.clear()
        assertTrue(ledger.ids().isEmpty())
        assertFalse(ledger.orphaned(null))
    }

    @Test fun withTheHelperAliveEveryKeyKeepsGoingThroughIt() {
        for (code in listOf(4, 3, 24, 85, 66)) assertEquals(KeyPath.Helper, HelperlessRouting.keyPath(code, helperUp = true))
    }

    @Test fun withoutTheHelperVolumeAndMediaUseAudioAndBackUsesTheGuardedGlobalAction() {
        for (code in listOf(24, 25, 164, 85, 86, 87, 88)) assertEquals(KeyPath.AppAudio, HelperlessRouting.keyPath(code, helperUp = false))
        assertEquals(KeyPath.AppGlobalBack, HelperlessRouting.keyPath(4, helperUp = false))
    }

    @Test fun withoutTheHelperEveryOtherKeyIsRefusedAsNeedingIt() {
        for (code in listOf(3, 187, 66, 67, 61, 19, 82, 111)) {
            assertEquals("key $code", KeyPath.NeedsHelper, HelperlessRouting.keyPath(code, helperUp = false))
            assertFalse(HelperlessRouting.keyWorksWithoutHelper(code))
        }
        assertTrue(HelperlessRouting.keyWorksWithoutHelper(24))
        assertTrue(HelperlessRouting.keyWorksWithoutHelper(4))
    }

    @Test fun globalBackIsOnlySafeWhenTheAgentDisplayHoldsTheFocusAndTheMainScreenDoesNot() {
        assertTrue(HelperlessRouting.backIsSafe(agentDisplayActive = true, mainDisplayActive = false))
        assertFalse(HelperlessRouting.backIsSafe(agentDisplayActive = true, mainDisplayActive = true))
        assertFalse(HelperlessRouting.backIsSafe(agentDisplayActive = false, mainDisplayActive = false))
        assertFalse(HelperlessRouting.backIsSafe(agentDisplayActive = false, mainDisplayActive = true))
    }

    @Test fun appDisplayFlagsNeverAskForTrustedOnlyBitsAndEndWithThePlainPublicSet() {
        val trustedOnly = (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 14) or (1 shl 16)
        for (sdk in listOf(30, 33, 34)) {
            val sets = InAppDisplayFlags.candidates(sdk)
            assertTrue(sets.isNotEmpty())
            for (f in sets) {
                assertEquals("trusted-only bits in $f", 0, f and trustedOnly)
                assertTrue("public + own content", f and 1 != 0 && f and (1 shl 3) != 0)
            }
            assertEquals(1 or (1 shl 3), sets.last())
            assertEquals(sets.size, sets.toSet().size)
        }
        assertEquals(3, InAppDisplayFlags.candidates(34).size)
        assertEquals(2, InAppDisplayFlags.candidates(30).size)
        assertTrue("touch feedback only from Android 13", InAppDisplayFlags.candidates(30).none { it and (1 shl 13) != 0 })
    }

    @Test fun readinessNamesTheFirstThingThePersonCanFix() {
        fun b(remote: Boolean = true, link: Boolean = true, a11y: Boolean = true, locked: Boolean = false, on: Boolean = true) =
            SessionReadiness.blocker(remote, link, a11y, locked, on)
        assertNull(b())
        assertEquals("remote_access_disabled", b(remote = false, link = false, a11y = false))
        assertEquals("gadget_link_down", b(link = false, a11y = false))
        assertEquals("accessibility_off", b(a11y = false, locked = true))
        assertEquals("device_locked", b(locked = true, on = false))
        assertEquals("screen_off", b(on = false))
    }
}
