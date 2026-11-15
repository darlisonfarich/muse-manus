package com.darlisonfarich.gadgetlink.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** As partes puras do 5.0: datas do end-call, ACK aninhado, linhas do fio, NDJSON e regras de prontidão. */
class VoiceWireTest {
    // ---- datas do end-call ------------------------------------------------------------------------
    @Test
    fun timestampsAreIsoWithMillisecondsAndPlusZeroOffsetNeverZ() {
        // 2026-11-15T19:04:05.007Z em ms: o .007 tem que sair com 3 dígitos e o fuso como +00:00.
        assertEquals("2026-11-15T19:04:05.007+00:00", VoiceWire.isoMillisUtc(1_794_769_445_007L))
        assertEquals("1970-01-01T00:00:00.000+00:00", VoiceWire.isoMillisUtc(0L))
        assertEquals("2026-11-15T19:04:05.120+00:00", VoiceWire.isoMillisUtc(1_794_769_445_120L))
        // É o mesmo texto do datetime.isoformat(timespec="milliseconds") do SDK (conferido no Python 3.12):
        // datetime.fromtimestamp(1794769445.999, timezone.utc).isoformat(timespec="milliseconds")
        assertEquals("2026-11-15T19:04:05.999+00:00", VoiceWire.isoMillisUtc(1_794_769_445_999L))
        for (ms in listOf(0L, 1L, 1_794_769_445_007L, 4_102_444_800_000L)) {
            val s = VoiceWire.isoMillisUtc(ms)
            assertFalse(s, s.endsWith("Z"))
            assertTrue(s, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00""").matches(s))
        }
    }

    @Test
    fun theEndIsNeverBeforeTheStartPlusOneMillisecond() {
        assertEquals(1001L, VoiceWire.endedAtMs(startedAtMs = 1000, nowMs = 1000))
        assertEquals(1001L, VoiceWire.endedAtMs(startedAtMs = 1000, nowMs = 900)) // relógio de parede voltou
        assertEquals(3000L, VoiceWire.endedAtMs(startedAtMs = 1000, nowMs = 3000))
    }

    @Test
    fun theEndCallBodyHasExactlyTheSdkFields() {
        val body = String(VoiceWire.endCallBody("c-1", 1_794_769_445_007L, 1_794_769_447_100L, threadId = null))
        assertEquals(
            """{"call_id":"c-1","call_started_at":"2026-11-15T19:04:05.007+00:00","call_ended_at":"2026-11-15T19:04:07.100+00:00"}""",
            body,
        )
        val withThread = String(VoiceWire.endCallBody("c-1", 0, 1, threadId = "t-9"))
        assertTrue(withThread, withThread.endsWith(""","thread_id":"t-9"}"""))
    }

    // ---- ACK aninhado -----------------------------------------------------------------------------
    @Test
    fun onlyTheNestedBooleanTrueAckCounts() {
        fun ack(status: Int, body: String) = VoiceWire.isEndCallAcknowledged(status, body.toByteArray())
        assertTrue(ack(200, """{"ok":true,"result":{"ok":true}}"""))
        assertTrue(ack(204, """{"ok":true,"result":{"ok":true,"extra":1},"x":null}"""))
        assertFalse("status não-2xx", ack(500, """{"ok":true,"result":{"ok":true}}"""))
        assertFalse("ok de fora só", ack(200, """{"ok":true}"""))
        assertFalse("result não é objeto", ack(200, """{"ok":true,"result":true}"""))
        assertFalse("ok de dentro falso", ack(200, """{"ok":true,"result":{"ok":false}}"""))
        assertFalse("\"true\" em texto não é true", ack(200, """{"ok":"true","result":{"ok":true}}"""))
        assertFalse("1 não é true (o SDK usa `is True`)", ack(200, """{"ok":true,"result":{"ok":1}}"""))
        assertFalse("corpo vazio", ack(200, ""))
        assertFalse("JSON quebrado", ack(200, """{"ok":true,"""))
        assertFalse("não é objeto", ack(200, """[{"ok":true}]"""))
        assertFalse("UTF-8 inválido", VoiceWire.isEndCallAcknowledged(200, byteArrayOf(0x7b, 0xff.toByte(), 0x7d)))
    }

    // ---- linhas do fio ----------------------------------------------------------------------------
    @Test
    fun theAdmissionLinesAreCompactNdjsonLikeTheSdk() {
        assertEquals(
            """{"type":"hatch.voice.call.start","call_id":"c-1","device":"mcu-wearable","device_id":"homelink-abc","call_media_type":"audio","proactive_greeting_enabled":false}""" + "\n",
            String(VoiceWire.ndjsonLine(VoiceWire.callStart("c-1", "homelink-abc"))),
        )
        assertEquals(
            """{"type":"hatch.voice.capability.offer","end_call_stop":1}""" + "\n",
            String(VoiceWire.ndjsonLine(VoiceWire.END_CALL_STOP_OFFER)),
        )
        assertEquals("/voice/realtime?call_id=c-1", VoiceWire.realtimePath("c-1"))
        assertEquals("hatch-web", VoiceWire.APP_ID)
    }

    // ---- NDJSON -----------------------------------------------------------------------------------
    @Test
    fun ndjsonSurvivesArbitraryFragmentationCrlfAndBlankLines() {
        val text = "{\"a\":1}\r\n\n  \n{\"b\":\"x\\ny\"}\n{\"c\":3}"
        val r = NdjsonReader()
        val got = ArrayList<JsonObject>()
        for (b in text.toByteArray()) got += r.feed(byteArrayOf(b))
        assertEquals(listOf("""{"a":1}""", """{"b":"x\ny"}"""), got.map { it.toString() })
        assertTrue("a última linha não tem LF ainda", r.hasPartialLine)
        assertEquals(listOf("""{"c":3}"""), r.feed("\n".toByteArray()).map { it.toString() })
        assertFalse(r.hasPartialLine)
    }

    @Test
    fun ndjsonRefusesBadRecordsAndOversizedLines() {
        for (bad in listOf("{nope}\n", "[1,2]\n", "\"s\"\n")) {
            try {
                NdjsonReader().feed(bad.toByteArray())
                fail("aceitou $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
        val small = NdjsonReader(maxLineBytes = 8)
        small.feed("{\"a\":".toByteArray())
        try {
            small.feed("12345}\n".toByteArray())
            fail("linha acima do teto")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- prontidão --------------------------------------------------------------------------------
    private fun ev(json: String): JsonObject = VoiceWire.parseObject(json.toByteArray())!!
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun readinessNeedsOurCallIdAndASessionId() {
        val t = ReadinessTracker("c-1")
        assertEquals(ReadinessStep.Continue, t.onEvent(ev("""{"type":"session.created"}""")))
        assertEquals(
            ReadinessStep.Ready("s-1", "t-1"),
            t.onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"s-1","thread_id":"t-1"}""")),
        )
        assertTrue(t.ready)
        assertEquals("duplicada", ReadinessStep.Fatal("identity_mismatch"), t.onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"s-1"}""")))

        assertEquals(ReadinessStep.Fatal("identity_mismatch"), ReadinessTracker("c-1").onEvent(ev("""{"type":"hatch.voice.session","call_id":"other","session_id":"s"}""")))
        assertEquals(ReadinessStep.Fatal("no_session_id"), ReadinessTracker("c-1").onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"  "}""")))
        assertEquals(ReadinessStep.Fatal("bad_thread_id"), ReadinessTracker("c-1").onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"s","thread_id":5}""")))
        assertEquals(ReadinessStep.Ready("s", null), ReadinessTracker("c-1").onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"s","thread_id":null}""")))
        assertEquals(ReadinessStep.Fatal("no_type"), ReadinessTracker("c-1").onEvent(ev("""{"call_id":"c-1"}""")))
    }

    @Test
    fun beforeReadinessZeroPcmIsAKeepaliveButAudibleAudioErrorAndStopAreFatal() {
        val zero = b64(ByteArray(960))
        val loud = b64(ByteArray(960) { if (it == 10) 1 else 0 })
        val t = ReadinessTracker("c-1")
        assertEquals(ReadinessStep.ZeroKeepalive, t.onEvent(ev("""{"type":"response.output_audio.delta","delta":"$zero"}""")))
        assertEquals(
            ReadinessStep.ZeroKeepalive,
            t.onEvent(ev("""{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$zero","format":{"type":"audio/pcm","rate":24000}}}""")),
        )
        assertEquals(2, t.keepalives)
        assertEquals(ReadinessStep.Fatal("audio_before_ready"), ReadinessTracker("c").onEvent(ev("""{"type":"response.audio.delta","delta":"$loud"}""")))
        assertEquals(ReadinessStep.Fatal("error_before_ready"), ReadinessTracker("c").onEvent(ev("""{"type":"error","error":{"code":"x"}}""")))
        assertEquals(
            ReadinessStep.Fatal("stop_before_ready"),
            ReadinessTracker("c").onEvent(ev("""{"type":"conversation.item.added","item":{"type":"session_control","name":" Stop ","call_id":"k"}}""")),
        )
        assertEquals(
            "taxa errada",
            ReadinessStep.Fatal("invalid_audio"),
            ReadinessTracker("c").onEvent(ev("""{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$zero","format":{"rate":16000}}}""")),
        )
        assertEquals("base64 inválido", ReadinessStep.Fatal("invalid_audio"), ReadinessTracker("c").onEvent(ev("""{"type":"response.audio.delta","delta":"@@@"}""")))
        assertEquals("PCM ímpar", ReadinessStep.Fatal("invalid_audio"), ReadinessTracker("c").onEvent(ev("""{"type":"response.audio.delta","delta":"${b64(ByteArray(3))}"}""")))
    }

    @Test
    fun afterReadinessAnErrorIsAdvisoryUnlessTheSessionIsDead() {
        val t = ReadinessTracker("c-1")
        t.onEvent(ev("""{"type":"hatch.voice.session","call_id":"c-1","session_id":"s"}"""))
        assertEquals(ReadinessStep.Continue, t.onEvent(ev("""{"type":"error","error":{"code":"server_error"}}""")))
        assertEquals(1, t.advisoryErrors)
        assertEquals(ReadinessStep.Fatal("session_dead"), t.onEvent(ev("""{"type":"error","error":{"code":"session_dead"}}""")))
        assertNull(VoiceWire.audioFromEvent(JsonObject(mapOf("type" to JsonPrimitive("other")))))
    }
}
