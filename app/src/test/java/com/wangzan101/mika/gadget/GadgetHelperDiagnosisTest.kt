package com.wangzan101.mika.gadget

import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GadgetHelperDiagnosisTest {
    private fun label(status: BuiltinShellStatus, rejection: String? = null) = GadgetHelperDiagnosis.helperStatus(status, rejection)

    @Test
    fun aRefusedStaleHelperIsReportedAsUpgradingWhileTheWatchdogWaits() {
        assertEquals("helper_upgrading", label(BuiltinShellStatus.Connecting, "StaleApk"))
        assertEquals("stale_helper", label(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), "StaleApk"))
    }

    @Test
    fun namesWhatTheRelaunchWaitsOn() {
        assertEquals("helper_starting", label(BuiltinShellStatus.Connecting))
        assertEquals("helper_needs_wifi", label(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi)))
        assertEquals("helper_wifi_not_allowed", label(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)))
        assertEquals("helper_needs_pairing", label(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)))
        assertEquals("helper_needs_wireless_debugging", label(BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals("helper_not_set_up", label(BuiltinShellStatus.NotPaired))
        assertEquals("helper_unavailable", label(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
    }

    @Test
    fun developerOptionsOffIsNamed() {
        assertEquals("helper_needs_developer_options",
            GadgetHelperDiagnosis.helperStatus(BuiltinShellStatus.WirelessDebuggingOff, null, developerOptions = false))
        // A self-upgrade in progress needs no developer options at all.
        assertEquals("helper_upgrading",
            GadgetHelperDiagnosis.helperStatus(BuiltinShellStatus.Connecting, "StaleApk", developerOptions = false))
    }
}
