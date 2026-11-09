package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Approval
import com.wangzan101.mika.core.action.ApprovalMode
import com.wangzan101.mika.core.action.ApprovalPolicy
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.uistate.PressKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDirectPhoneSafetyTest {
    @Test fun bridgeProvenanceStaysStrictEvenIfUiIsAutonomous() {
        val bridge = RunProvenance(RunOrigin.Bridge, Attendance.Unattended)
        assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(ActionRisk.External),
            bridge, ApprovalMode.Autonomous))
        assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(ActionRisk.Irreversible),
            bridge, ApprovalMode.Autonomous))
    }

    @Test fun remoteOriginCannotConsumeAnApproval() {
        val remote = Origin.McpTool(1).policy
        assertFalse(remote.usesGrant)
        assertTrue(remote.judgeWithoutGate)
        assertTrue(remote.refuseUnresolved)
        assertTrue(remote.stopOnGuardWarning)
    }

    @Test fun scopedKeysAreVirtualOnlyAndPowerRemainsBlocked() {
        for (key in listOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_VOLUME_UP",
                "KEYCODE_VOLUME_DOWN", "KEYCODE_MEDIA_NEXT")) {
            assertEquals(key, null, PressKeys.resolve(key, PressKeys.Scope.AnyDisplay))
            assertTrue(key, PressKeys.resolve(key, PressKeys.Scope.VirtualDisplay) != null)
        }
        assertEquals(null, PressKeys.resolve("KEYCODE_POWER", PressKeys.Scope.VirtualDisplay))
        assertEquals(4, PressKeys.resolve("KEYCODE_BACK"))
    }
}
