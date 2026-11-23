package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.shell.HelperWarmup.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class HelperWarmupTest {
    private fun decide(
        helperUp: Boolean = false,
        rootReady: Boolean = false,
        paired: Boolean = true,
        developerOptions: Boolean? = true,
        debuggingReachable: Boolean? = true,
        wifiConnected: Boolean = true,
        sinceLastAskMs: Long? = null,
    ) = HelperWarmup.decide(helperUp, rootReady, paired, developerOptions, debuggingReachable, wifiConnected, sinceLastAskMs)

    @Test fun asksWhenDebuggingIsAlreadyReachableOnAnAlreadyConnectedWifi() {
        assertEquals(Decision.Ask, decide())
    }

    @Test fun doesNothingWhileTheHelperIsUp() {
        assertEquals(Decision.Idle("helper_up"), decide(helperUp = true))
    }

    @Test fun staysDormantWithoutWifi_neverAsksForItToBeTurnedOn() {
        assertEquals(Decision.Dormant("wifi_off"), decide(wifiConnected = false))
        // Even with debugging switch reading "on": no Wi-Fi means dormant, nothing else is attempted.
        assertEquals(Decision.Dormant("wifi_off"), decide(wifiConnected = false, debuggingReachable = true))
    }

    @Test fun staysDormantWithDebuggingOffOrUnreadable_neverAsksForItToBeTurnedOn() {
        assertEquals(Decision.Dormant("debugging_off"), decide(debuggingReachable = false))
        assertEquals(Decision.Dormant("debugging_off"), decide(debuggingReachable = null))
    }

    @Test fun staysDormantWithDeveloperOptionsOffOrNotPaired() {
        assertEquals(Decision.Dormant("developer_options_off"), decide(developerOptions = false))
        assertEquals(Decision.Dormant("not_paired"), decide(paired = false))
    }

    @Test fun doesNotInsistWithinTheMinimumGap_butAsksAgainAfterIt() {
        assertEquals(Decision.Idle("asked_recently"), decide(sinceLastAskMs = 5_000))
        assertEquals(Decision.Idle("asked_recently"), decide(sinceLastAskMs = HelperWarmup.MIN_GAP_MS - 1))
        assertEquals(Decision.Ask, decide(sinceLastAskMs = HelperWarmup.MIN_GAP_MS))
    }

    @Test fun rootStartNeedsNeitherWifiNorDebugging() {
        assertEquals(Decision.Ask, decide(rootReady = true, wifiConnected = false, debuggingReachable = false, paired = false))
    }

    @Test fun nothingInTheDecisionIsAnAction() {
        // The decision is a value, not a side effect: every outcome is Ask/Idle/Dormant, none of which can name a radio.
        val all = listOf(decide(), decide(helperUp = true), decide(wifiConnected = false), decide(debuggingReachable = false))
        assertEquals(setOf("Ask", "Idle", "Dormant"), all.map { it::class.simpleName }.toSet())
    }
}
