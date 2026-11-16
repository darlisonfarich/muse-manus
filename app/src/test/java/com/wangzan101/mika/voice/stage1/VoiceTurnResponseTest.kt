package com.wangzan101.mika.voice.stage1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnResponseTest {
    private val sample = """
        {
          "conversation_id":"conversation-123",
          "turn_id":"turn-456",
          "transcript":"Oi Muse, tudo bem?",
          "reply_text":"Tudo certo.",
          "fallback":false,
          "audio_base64":"SUQzAA==",
          "timings_ms":{"asr":890,"tts":1440}
        }
    """.trimIndent()

    @Test fun parsesGatewayContract() {
        val result = VoiceTurnResponse.parse(sample, "conversation-123", "turn-456")
        assertEquals("Oi Muse, tudo bem?", result.transcript)
        assertEquals("Tudo certo.", result.replyText)
        assertFalse(result.fallback)
        assertEquals("SUQzAA==", result.audioBase64)
        assertEquals("890", result.timingsMs?.get("asr").toString())
    }

    @Test fun explicitFallbackIsPreserved() {
        val result = VoiceTurnResponse.parse(sample.replace("\"fallback\":false", "\"fallback\":true"),
            "conversation-123", "turn-456")
        assertTrue(result.fallback)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedTurnRejected() {
        VoiceTurnResponse.parse(sample, "conversation-123", "different")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingAudioRejected() {
        VoiceTurnResponse.parse(sample.replace("\"SUQzAA==\"", "\"\""), "conversation-123", "turn-456")
    }
}
