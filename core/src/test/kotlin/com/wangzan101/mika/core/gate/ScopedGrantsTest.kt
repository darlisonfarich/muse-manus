package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Approval
import com.wangzan101.mika.core.action.ApprovalMode
import com.wangzan101.mika.core.action.ApprovalPolicy
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedGrantsTest {
    @Test fun exactAppAccountAndClassRequired() {
        var now = 100_000L
        val events = mutableListOf<String>()
        val grants = ScopedGrantRegistry({ now }) { event, _ -> events.add(event) }
        assertNotNull(grants.issueFromLocalUi("com.spotify.music", "user-a", ScopedActionClass.Navigate, 1000L))
        assertTrue(grants.matches("com.spotify.music", "user-a", ScopedActionClass.Navigate))
        assertFalse(grants.matches("com.spotify.music", "user-b", ScopedActionClass.Navigate))
        assertFalse(grants.matches("com.example.music", "user-a", ScopedActionClass.Navigate))
        assertFalse(grants.matches("com.spotify.music", "user-a", ScopedActionClass.Edit))
        assertFalse(grants.matches(null, "user-a", ScopedActionClass.Navigate))
        assertEquals(listOf("issued"), events)
    }

    @Test fun expiresAtDeadlineAndNeverSurvivesClockRollback() {
        var now = 1_000L
        val grants = ScopedGrantRegistry { now }
        grants.issueFromLocalUi("com.example.app", "personal", ScopedActionClass.Edit, 900L)
        now = 1_899L
        assertTrue(grants.matches("com.example.app", "personal", ScopedActionClass.Edit))
        now = 1_900L
        assertFalse(grants.matches("com.example.app", "personal", ScopedActionClass.Edit))
        now = 2_000L
        grants.issueFromLocalUi("com.example.app", "personal", ScopedActionClass.Edit, 400L)
        now = 1_990L
        assertFalse(grants.matches("com.example.app", "personal", ScopedActionClass.Edit))
    }

    @Test fun durationCannotExceedFifteenMinutesOrContainInvalidIdentity() {
        val grants = ScopedGrantRegistry { 50_000L }
        assertEquals(null, grants.issueFromLocalUi("com.app.test", "acct", ScopedActionClass.Edit, 0))
        assertEquals(null, grants.issueFromLocalUi("com.app.test", "acct", ScopedActionClass.Edit,
            ScopedGrantRegistry.MAX_TTL_MS + 1))
        assertEquals(null, grants.issueFromLocalUi("com.app.test", "", ScopedActionClass.Edit, 1000))
        assertEquals(null, grants.issueFromLocalUi("com.app.test;rm", "acct", ScopedActionClass.Edit, 1000))
        assertEquals(0, grants.active().size)
    }

    @Test fun explicitRevocationIsImmediateAndRegistryIsProcessLocal() {
        val grants = ScopedGrantRegistry { 100L }
        val one = grants.issueFromLocalUi("com.example.app", "a", ScopedActionClass.Edit, 1000)!!
        val two = grants.issueFromLocalUi("com.example.app", "b", ScopedActionClass.Edit, 1000)!!
        assertTrue(grants.revoke(one.id))
        assertFalse(grants.revoke(one.id))
        assertFalse(grants.matches("com.example.app", "a", ScopedActionClass.Edit))
        assertTrue(grants.matches("com.example.app", "b", ScopedActionClass.Edit))
        grants.revokeAll()
        assertTrue(grants.active().isEmpty())
        assertFalse(ScopedGrantRegistry { 101L }.matches("com.example.app", "b", ScopedActionClass.Edit))
        assertEquals(two.id, two.id)
    }

    @Test fun grantsDoNotWeakenRemotePaymentDenial() {
        val grants = ScopedGrantRegistry { 100L }
        grants.issueFromLocalUi("com.bank.app", "personal", ScopedActionClass.Navigate, 10_000L)
        val risk = Risk(ActionRisk.Irreversible, payment = true, action = IrrevAction.pay)
        assertEquals(Approval.Deny, ApprovalPolicy.decide(
            risk, RunProvenance(origin = RunOrigin.Mcp), ApprovalMode.Autonomous))
    }
}
