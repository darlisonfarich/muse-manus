package com.wangzan101.mika.core.gate

import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApprovalGateTest {
    private var now = 1_000L
    private val used = mutableSetOf<String>()
    private val tokens = ChatApprovalTokens(
        { SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256") },
        ConsumedApprovalIds { used.add(it) },
    ) { now }
    private val args = buildJsonObject { put("text", "hello"); put("x", 2) }

    private fun redeem(token: String?) = ChatApprovalGate.redeem(
        tokens, token, "owner", "phone_tap", args, "direct")

    @Test fun notPresentedForMissingToken() {
        assertEquals(ChatApprovalGate.Outcome.NotPresented, redeem(null))
        assertEquals(ChatApprovalGate.Outcome.NotPresented, redeem("  "))
        assertTrue(used.isEmpty())
    }

    @Test fun approvedOnceThenReplayRejected() {
        val token = tokens.issue("owner", "phone_tap", args, "direct")
        assertEquals(ChatApprovalGate.Outcome.Approved, redeem(token))
        val second = redeem(token)
        assertTrue(second is ChatApprovalGate.Outcome.Rejected)
        assertEquals(
            ChatApprovalGate.Failure.Replay,
            (second as ChatApprovalGate.Outcome.Rejected).failure,
        )
        assertEquals("approval_token_replay", second.failure.code)
    }

    @Test fun expiredAndMismatchAndInvalidMapToStableCodes() {
        val expiring = tokens.issue("owner", "phone_tap", args, "direct", 10)
        now += 10
        val expired = redeem(expiring)
        assertEquals("approval_token_expired",
            (expired as ChatApprovalGate.Outcome.Rejected).failure.code)
        now -= 10

        val other = buildJsonObject { put("text", "other") }
        val mismatched = ChatApprovalGate.redeem(
            tokens, tokens.issue("owner", "phone_tap", args, "direct"),
            "owner", "phone_tap", other, "direct")
        assertEquals("approval_token_mismatch",
            (mismatched as ChatApprovalGate.Outcome.Rejected).failure.code)

        val invalid = redeem("not-a-token")
        assertEquals("approval_token_invalid",
            (invalid as ChatApprovalGate.Outcome.Rejected).failure.code)
    }
}
