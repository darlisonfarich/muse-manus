package com.wangzan101.mika.core.gate

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApprovalTokensTest {
    private var now = 1_000L
    private val used = mutableSetOf<String>()
    private val tokens = ChatApprovalTokens({ SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256") }, ConsumedApprovalIds { used.add(it) }) { now }
    private val args = buildJsonObject { put("text", "hello"); put("x", 2) }

    @Test fun issueAndVerifyOnce() {
        val token = tokens.issue("owner", "phone_tap", args, "direct")
        assertEquals(ChatApprovalTokens.Result.Approved, tokens.consume(token, "owner", "phone_tap", args, "direct"))
        assertEquals(ChatApprovalTokens.Result.Replay, tokens.consume(token, "owner", "phone_tap", args, "direct"))
    }

    @Test fun expiryAndExactActionBinding() {
        val token = tokens.issue("owner", "phone_tap", args, "direct", 10)
        now += 10
        assertEquals(ChatApprovalTokens.Result.Expired, tokens.consume(token, "owner", "phone_tap", args, "direct"))
        now -= 10
        val fresh = tokens.issue("owner", "phone_tap", args, "direct")
        assertEquals(ChatApprovalTokens.Result.Mismatch, tokens.consume(fresh, "owner", "phone_tap", buildJsonObject { put("text", "other") }, "direct"))
    }

    @Test fun signatureTamperRejected() {
        val token = tokens.issue("owner", "phone_tap", args, "direct")
        val bytes = Base64.getUrlDecoder().decode(token.substringAfterLast('.'))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = token.substringBeforeLast('.') + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals(ChatApprovalTokens.Result.Invalid, tokens.consume(tampered, "owner", "phone_tap", args, "direct"))
        assertTrue(used.isEmpty())
    }
}
