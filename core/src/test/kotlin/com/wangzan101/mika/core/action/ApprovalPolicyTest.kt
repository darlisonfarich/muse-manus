package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun askRetainsLegacyOnlyMoneyAndExplicitAsk() {
        for (level in ActionRisk.entries) {
            assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun strictAsksOnlyForOutwardAndUnknownOperations() {
        for (level in ActionRisk.entries) {
            val expected = if (level.outward) Approval.Ask else Approval.Allow
            assertEquals(level.name, expected,
                ApprovalPolicy.decide(Risk(level), configured = ApprovalMode.Strict))
        }
    }

    @Test fun bridgeHasStrictFloorRegardlessOfGlobalAutonomous() {
        for (mode in ApprovalMode.entries) {
            for (attendance in Attendance.entries) {
                val run = RunProvenance(RunOrigin.Bridge, attendance)
                assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(ActionRisk.External), run, mode, attendance))
                assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(ActionRisk.Irreversible), run, mode, attendance))
                assertEquals(Approval.Ask, ApprovalPolicy.decide(Risk(ActionRisk.Unknown), run, mode, attendance))
                assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.Change), run, mode, attendance))
            }
        }
    }

    @Test fun moneyPaymentNeverGoesThroughApprovalGateForBridge() {
        for (mode in ApprovalMode.entries) for (attendance in Attendance.entries) {
            val run = RunProvenance(RunOrigin.Bridge, attendance)
            assertEquals(Approval.Deny, ApprovalPolicy.decide(
                Risk(ActionRisk.Irreversible, payment = true, action = IrrevAction.pay), run, mode, attendance))
        }
        assertEquals(Approval.Deny, ApprovalPolicy.decide(Risk(ActionRisk.Irreversible,
            payment = true, action = IrrevAction.pay), configured = ApprovalMode.Strict))
        assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.Irreversible,
            payment = true, action = IrrevAction.pay), configured = ApprovalMode.Autonomous))
    }

    @Test fun genericRemoteCannotInheritBridgeExceptionOrAutonomy() {
        for (origin in listOf(RunOrigin.Mcp, RunOrigin.Channel, RunOrigin.Trigger, RunOrigin.Child)) {
            for (mode in ApprovalMode.entries) {
                // Generic remote keeps legacy Ask semantics for harmless direct actions;
                // a direct tool that explicitly needs confirmation must still be denied.
                assertEquals("$origin $mode", Approval.Allow, ApprovalPolicy.decide(
                    Risk(ActionRisk.External), RunProvenance(origin, Attendance.Present), mode))
                assertEquals("$origin $mode needs approval", Approval.Deny, ApprovalPolicy.decide(
                    Risk(ActionRisk.External, ask = com.wangzan101.mika.core.gate.ConfirmationRequest(
                        IrrevAction.send, "peer", "message", null, "com.example")),
                    RunProvenance(origin, Attendance.Present), mode))
                assertEquals("$origin $mode", Approval.Deny, ApprovalPolicy.decide(
                    Risk(ActionRisk.Irreversible, payment = true), RunProvenance(origin, Attendance.Reachable), mode))
            }
        }
    }

    @Test fun localUnattendedCannotConfirmButMayReadOrChange() {
        val run = RunProvenance(RunOrigin.Ui, Attendance.Unattended)
        assertEquals(Approval.Deny, ApprovalPolicy.decide(Risk(ActionRisk.External), run, ApprovalMode.Strict))
        assertEquals(Approval.Allow, ApprovalPolicy.decide(Risk(ActionRisk.Change), run, ApprovalMode.Strict))
    }

    @Test fun irreversibleDeleteAndSendActionsSurviveRiskClassification() {
        val delete = ClassifiedRisk.assess(null, com.wangzan101.mika.core.gate.Irreversible(IrrevAction.delete, "delete")) { null }
        val send = ClassifiedRisk.assess(null, com.wangzan101.mika.core.gate.Irreversible(IrrevAction.send, "send")) { null }
        assertEquals(IrrevAction.delete, delete.action)
        assertEquals(IrrevAction.send, send.action)
        assertEquals(Approval.Ask, ApprovalPolicy.decide(delete, configured = ApprovalMode.Strict))
    }
}
