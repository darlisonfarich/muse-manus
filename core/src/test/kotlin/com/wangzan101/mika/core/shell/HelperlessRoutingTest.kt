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
        for (code in listOf(24, 25, 164, 79, 85, 86, 87, 88, 89, 90, 126, 127)) assertEquals(KeyPath.AppAudio, HelperlessRouting.keyPath(code, helperUp = false))
        assertEquals(KeyPath.AppGlobalBack, HelperlessRouting.keyPath(4, helperUp = false))
    }

    @Test fun theDpadKeysUseTheAccessibilityGlobalActionFromAndroid13Only() {
        for (code in 19..23) {
            assertEquals("key $code on Android 12", KeyPath.NeedsHelper, HelperlessRouting.keyPath(code, helperUp = false, sdk = 32))
            assertEquals("key $code on Android 13", KeyPath.AppGlobalDpad, HelperlessRouting.keyPath(code, helperUp = false, sdk = 33))
            assertEquals("key $code with the helper", KeyPath.Helper, HelperlessRouting.keyPath(code, helperUp = true, sdk = 34))
        }
        assertTrue(HelperlessRouting.keyWorksWithoutHelper(23, sdk = 34))
        assertFalse(HelperlessRouting.keyWorksWithoutHelper(23, sdk = 30))
    }

    @Test fun theOwnerOfTheDisplayDecidesWhetherAKeyNeedsTheMissingHelperNotWhetherAHelperIsUpNow() {
        // M1: a display the app made keeps being answered by the app even after a helper appears.
        val ledger = DisplayLedger()
        ledger.record(5, ShellOwner.InApp)
        ledger.record(6, ShellOwner.Helper, helperA)
        val appOwner = HelperlessRouting.displayOwner(ledger, 5, helperUp = true)
        val helperOwner = HelperlessRouting.displayOwner(ledger, 6, helperUp = true)
        assertEquals(ShellOwner.InApp, appOwner)
        assertEquals(ShellOwner.Helper, helperOwner)
        for (home in listOf(3, 66, 67, 61)) {
            assertTrue("key $home on the app's display, helper up", HelperlessRouting.keyNeedsMissingHelper(appOwner, home))
            assertFalse("key $home on the helper's display", HelperlessRouting.keyNeedsMissingHelper(helperOwner, home))
        }
        // Keys the app can deliver are never refused on its own display.
        for (ok in listOf(24, 85, 4)) assertFalse(HelperlessRouting.keyNeedsMissingHelper(appOwner, ok))
        assertFalse(HelperlessRouting.keyNeedsMissingHelper(appOwner, 23, sdk = 34))
        assertTrue(HelperlessRouting.keyNeedsMissingHelper(appOwner, 23, sdk = 30))
        // A helper display whose helper died falls to the app, and then asks for the helper like any app display.
        val orphan = HelperlessRouting.displayOwner(ledger, 6, helperUp = false)
        assertEquals(ShellOwner.InApp, orphan)
        assertTrue(HelperlessRouting.keyNeedsMissingHelper(orphan, 66))
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
            assertEquals(1 or (1 shl 3) or (1 shl 8), sets.last())
            // M2: every set closes the display's tasks with it (DESTROY_CONTENT_ON_REMOVAL).
            for (f in sets) assertTrue("destroy-on-removal in $f", f and (1 shl 8) != 0)
            assertEquals(sets.size, sets.toSet().size)
        }
        assertEquals(3, InAppDisplayFlags.candidates(34).size)
        assertEquals(2, InAppDisplayFlags.candidates(30).size)
        assertTrue("touch feedback only from Android 13", InAppDisplayFlags.candidates(30).none { it and (1 shl 13) != 0 })
    }

    @Test fun readinessNamesTheFirstThingThePersonCanFix() {
        fun b(remote: Boolean = true, link: Boolean = true, a11y: Boolean = true) = SessionReadiness.blocker(remote, link, a11y)
        assertNull(b())
        assertEquals("remote_access_disabled", b(remote = false, link = false, a11y = false))
        assertEquals("gadget_link_down", b(link = false, a11y = false))
        assertEquals("accessibility_off", b(a11y = false))
    }

    @Test fun aLockedOrDarkPhoneBlocksActionsByName_notTheSession() {
        // M4: reads on the virtual display work with the screen off, so only the physical-phone actions ask the person.
        assertNull(PhoneState.cause(accessibilityUp = true, deviceLocked = false, screenInteractive = true))
        assertEquals("accessibility_off", PhoneState.cause(accessibilityUp = false, deviceLocked = true, screenInteractive = false))
        assertEquals("device_locked", PhoneState.cause(accessibilityUp = true, deviceLocked = true, screenInteractive = false))
        assertEquals("screen_off", PhoneState.cause(accessibilityUp = true, deviceLocked = false, screenInteractive = false))
    }

    @Test fun withoutTheHelperTheWindowVerdictComesFromWhatTheAppSees() {
        assertEquals(HelperlessRouting.VERDICT_CLEAR, HelperlessRouting.appWindowVerdict(accessibilityUp = true, windowCount = 2))
        assertEquals(HelperlessRouting.VERDICT_EMPTY, HelperlessRouting.appWindowVerdict(accessibilityUp = true, windowCount = 0))
        // Cannot see anything at all: unknown, which the reads treat as fail-closed.
        assertEquals(HelperlessRouting.VERDICT_UNKNOWN, HelperlessRouting.appWindowVerdict(accessibilityUp = false, windowCount = 3))
        // The codes are the ones `VirtualWindowProtection` decodes: 0 unknown, 1 empty, 2 clear.
        assertEquals(listOf(0, 1, 2), listOf(HelperlessRouting.VERDICT_UNKNOWN, HelperlessRouting.VERDICT_EMPTY, HelperlessRouting.VERDICT_CLEAR))
    }

    @Test fun anApplicationWindowWithAnUnreadableRootCountsAsTheUsersAppInUse() {
        // A2: fail-closed. Opening the user's own app elsewhere would pull its task onto the virtual display.
        assertTrue(HelperlessRouting.inUseOnMain("a.b", setOf("a.b"), 0))
        assertFalse(HelperlessRouting.inUseOnMain("a.b", setOf("c.d"), 0))
        assertTrue(HelperlessRouting.inUseOnMain("a.b", setOf("c.d"), 1))
        assertTrue(HelperlessRouting.inUseOnMain("a.b", emptySet(), 1))
        assertFalse(HelperlessRouting.inUseOnMain("a.b", emptySet(), 0))
    }
}
