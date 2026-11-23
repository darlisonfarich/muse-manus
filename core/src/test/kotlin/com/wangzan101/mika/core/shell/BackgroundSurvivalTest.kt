package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.shell.BackgroundSurvival.SurvivalSetting
import com.wangzan101.mika.core.shell.BackgroundSurvival.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSurvivalTest {
    // ---- restart decision ----

    @Test fun anExplicitDisconnectAlwaysStops() {
        assertEquals(StartDecision.Stop, BackgroundSurvival.onStart(stopRequested = true, explicitStart = true, linkEnabled = true, hasPairing = true))
    }

    @Test fun anExplicitStartRunsEvenBeforePairing() {
        assertEquals(StartDecision.Run, BackgroundSurvival.onStart(false, explicitStart = true, linkEnabled = true, hasPairing = false))
    }

    @Test fun aSystemRestartWithNullIntentRunsOnlyWhenTheUserLeftTheLinkOnAndItIsPaired() {
        assertEquals(StartDecision.Run, BackgroundSurvival.onStart(false, explicitStart = false, linkEnabled = true, hasPairing = true))
        assertEquals(StartDecision.Stop, BackgroundSurvival.onStart(false, explicitStart = false, linkEnabled = false, hasPairing = true))
        assertEquals(StartDecision.Stop, BackgroundSurvival.onStart(false, explicitStart = false, linkEnabled = true, hasPairing = false))
    }

    // ---- background launch attribution ----

    @Test fun aRecentBackgroundLaunchIsNamedInTheFailureHint() {
        val hint = BackgroundSurvival.backgroundLaunchSuffix(launchedFromBackgroundAtMs = 1_000, nowMs = 6_000)
        assertTrue(hint, hint.contains(BackgroundSurvival.BACKGROUND_LAUNCH_TOKEN))
    }

    @Test fun noBackgroundLaunchOrAnOldOneAddsNothing() {
        assertEquals("", BackgroundSurvival.backgroundLaunchSuffix(null, 6_000))
        assertEquals("", BackgroundSurvival.backgroundLaunchSuffix(1_000, 1_000 + BackgroundSurvival.BACKGROUND_LAUNCH_WINDOW_MS + 1))
        assertEquals("", BackgroundSurvival.backgroundLaunchSuffix(10_000, 5_000)) // clock went backwards: never claim
    }

    @Test fun theWindowEdgeStillCounts() {
        assertTrue(BackgroundSurvival.backgroundLaunchSuffix(0, BackgroundSurvival.BACKGROUND_LAUNCH_WINDOW_MS).isNotEmpty())
    }

    // ---- lifecycle log ----

    @Test fun theLogKeepsOnlyTheNewestLines() {
        var lines = emptyList<String>()
        repeat(40) { lines = BackgroundSurvival.appendEvent(lines, "l$it", max = 30) }
        assertEquals(30, lines.size)
        assertEquals("l10", lines.first())
        assertEquals("l39", lines.last())
    }

    @Test fun eventLinesUseOnlyFixedVocabulary() {
        assertEquals("21:00:01 boot_resume", BackgroundSurvival.eventLine("21:00:01", "boot_resume"))
        assertEquals("21:00:01 foreground_refused SecurityException",
            BackgroundSurvival.eventLine("21:00:01", "foreground_refused", "SecurityException"))
        // free text, addresses and spaces never get in
        assertEquals("21:00:01 unknown_event", BackgroundSurvival.eventLine("21:00:01", "Has Spaces!"))
        assertEquals("21:00:01 sticky_restart", BackgroundSurvival.eventLine("21:00:01", "sticky_restart", "token abc 192.168.0.1"))
    }

    // ---- settings deep links ----

    @Test fun hyperOsScreensComeFirstAndTheAppDetailsPageIsTheLastResort() {
        for (setting in listOf(SurvivalSetting.Autostart, SurvivalSetting.BatteryUnrestricted)) {
            val c = BackgroundSurvival.settingsCandidates(setting, "com.example.app", "Muse Manus")
            assertTrue(c.first().packageName?.startsWith("com.miui.") == true)
            assertEquals(BackgroundSurvival.APP_DETAILS, c.last().action)
        }
        assertEquals(listOf(BackgroundSurvival.APP_DETAILS),
            BackgroundSurvival.settingsCandidates(SurvivalSetting.AppDetails, "p", "l").map { it.action })
    }

    @Test fun theBatteryScreenOfHyperOsCarriesThePackageAndLabel() {
        val first = BackgroundSurvival.settingsCandidates(SurvivalSetting.BatteryUnrestricted, "com.example.app", "Muse Manus").first()
        assertEquals("com.example.app", first.extras["package_name"])
        assertEquals("Muse Manus", first.extras["package_label"])
    }

    @Test fun theFirstResolvableCandidateWinsAndNoneGivesNull() {
        val c = BackgroundSurvival.settingsCandidates(SurvivalSetting.BatteryUnrestricted, "p", "l")
        // a stock phone: the MIUI components do not exist
        val stock = BackgroundSurvival.firstResolvable(c) { it.packageName == null }
        assertNotNull(stock)
        assertEquals(BackgroundSurvival.IGNORE_BATTERY_REQUEST, stock!!.action)
        // a HyperOS phone resolves everything: the first one is used
        assertEquals(c.first(), BackgroundSurvival.firstResolvable(c) { true })
        assertNull(BackgroundSurvival.firstResolvable(c) { false })
        assertFalse(c.isEmpty())
    }
}
