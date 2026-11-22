package com.wangzan101.mika.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceA2HubSegmentsTest {
    private val conversation = UUID.randomUUID().toString()
    private val turn = UUID.randomUUID().toString()
    private val gen = UUID.randomUUID().toString()

    private class Wire : VoiceA2Sender {
        val texts = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()
        @Volatile var cancelSeen = CountDownLatch(1)
        override fun send(opcode: Int, payload: ByteArray) {
            if (opcode == 1) {
                val t = String(payload, Charsets.UTF_8)
                texts.add(t)
                if (t.contains("\"CANCEL\"")) { threads.add(Thread.currentThread().name); cancelSeen.countDown() }
            }
        }
    }

    private fun ready(): Pair<VoiceA2Hub, Wire> {
        val hub = VoiceA2Hub()
        val wire = Wire()
        assertTrue(hub.attach(wire))
        hub.onText("""{"type":"HELLO","protocol_version":"1"}""")
        return hub to wire
    }

    private fun startTurn(hub: VoiceA2Hub) {
        assertTrue(hub.submit(conversation, turn, gen, ByteArray(100) { 1 }))
        hub.onText("""{"type":"TRANSCRIPT","turn_id":"$turn","generation_id":"$gen","text":"oi"}""")
        hub.onText("""{"type":"REPLY","turn_id":"$turn","generation_id":"$gen","text":"Olá. Tudo bem?"}""")
    }

    private fun segment(hub: VoiceA2Hub, bytes: ByteArray, chunk: Int = 40) {
        hub.onText("""{"type":"AUDIO_START","turn_id":"$turn","generation_id":"$gen","format":"mp3","total_bytes":"${bytes.size}"}""".replace("\"${bytes.size}\"", "${bytes.size}"))
        var seq = 0
        for (s in bytes.indices step chunk) {
            val block = bytes.copyOfRange(s, minOf(s + chunk, bytes.size))
            hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, turn, seq++, block))
        }
        hub.onText("""{"type":"AUDIO_END","turn_id":"$turn","generation_id":"$gen","kind":"mp3"}""")
    }

    @Test fun readyAnnouncesSegments() {
        val (_, wire) = ready()
        val ready = wire.texts.single { it.contains("\"READY\"") }
        assertTrue(ready, ready.contains("\"segments\":\"1\"") && ready.contains("\"protocol_version\":\"1\""))
    }

    @Test fun firstSegmentIsPublishedBeforeTurnEnd() {
        val (hub, _) = ready()
        startTurn(hub)
        val a = ByteArray(100) { 7 }
        segment(hub, a)
        val s = hub.state.value
        assertEquals("1 segmento publicado sem TURN_END", 1, s.segments.size)
        assertTrue(s.segments[0].contentEquals(a))
        assertEquals(turn, s.audioTurnId)
        assertNotNull("turno ainda em curso", s.turnId)
        assertNull("sem resultId antes do TURN_END", s.resultId)
    }

    @Test fun segmentsAccumulateInOrderAndTurnEndKeepsThem() {
        val (hub, _) = ready()
        startTurn(hub)
        val a = ByteArray(100) { 1 }; val b = ByteArray(55) { 2 }; val c = ByteArray(9) { 3 }
        segment(hub, a); segment(hub, b); segment(hub, c)
        assertEquals(3, hub.state.value.segments.size)
        hub.onText("""{"type":"TURN_END","turn_id":"$turn","generation_id":"$gen"}""")
        val s = hub.state.value
        assertEquals(listOf(100, 55, 9), s.segments.map { it.size })
        assertEquals(turn, s.resultId)
        assertNull(s.turnId)
        // compat: o blob único é a concatenação, na ordem
        assertTrue(s.audio!!.contentEquals(a + b + c))
        assertEquals(turn, s.audioTurnId)
    }

    @Test fun segmentStartedBeforePreviousEndedIsViolation() {
        val (hub, _) = ready()
        startTurn(hub)
        hub.onText("""{"type":"AUDIO_START","turn_id":"$turn","generation_id":"$gen","format":"mp3","total_bytes":10}""")
        hub.onText("""{"type":"AUDIO_START","turn_id":"$turn","generation_id":"$gen","format":"mp3","total_bytes":10}""")
        val s = hub.state.value
        assertEquals("Violação do protocolo A2", s.error)
        assertNull(s.turnId); assertTrue(s.segments.isEmpty())
    }

    @Test fun incompleteSegmentIsViolation() {
        val (hub, _) = ready()
        startTurn(hub)
        hub.onText("""{"type":"AUDIO_START","turn_id":"$turn","generation_id":"$gen","format":"mp3","total_bytes":100}""")
        hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, turn, 0, ByteArray(40)))
        hub.onText("""{"type":"AUDIO_END","turn_id":"$turn","generation_id":"$gen","kind":"mp3"}""")
        assertEquals("Violação do protocolo A2", hub.state.value.error)
    }

    @Test fun turnEndWithoutAudioIsViolation() {
        val (hub, _) = ready()
        startTurn(hub)
        hub.onText("""{"type":"TURN_END","turn_id":"$turn","generation_id":"$gen"}""")
        assertEquals("Violação do protocolo A2", hub.state.value.error)
    }

    @Test fun errorWithSegmentsClearsQueue() {
        val (hub, _) = ready()
        startTurn(hub)
        segment(hub, ByteArray(100) { 1 })
        hub.onText("""{"type":"ERROR","turn_id":"$turn","generation_id":"$gen","code":"tts_failed"}""")
        val s = hub.state.value
        assertEquals("tts_failed", s.error)
        assertTrue(s.segments.isEmpty()); assertNull(s.audioTurnId)
    }

    @Test fun cancelSendsCancelFromWorkerThreadAndDropsLateFrames() {
        val (hub, wire) = ready()
        startTurn(hub)
        segment(hub, ByteArray(100) { 1 })
        val callerThread = Thread.currentThread().name
        hub.cancel()
        assertTrue("CANCEL saiu em até 2 s", wire.cancelSeen.await(2, TimeUnit.SECONDS))
        val cancel = wire.texts.single { it.contains("\"CANCEL\"") }
        assertTrue(cancel, cancel.contains(turn) && cancel.contains(gen))
        assertEquals("voice-a2-cancel", wire.threads.single())
        assertTrue(wire.threads.single() != callerThread)
        val s = hub.state.value
        assertTrue(s.segments.isEmpty()); assertNull(s.turnId); assertNull(s.audioTurnId)
        // frames/segmentos em trânsito depois do CANCEL são descartados
        segment(hub, ByteArray(100) { 9 })
        hub.onText("""{"type":"TURN_END","turn_id":"$turn","generation_id":"$gen"}""")
        assertTrue(hub.state.value.segments.isEmpty())
        assertNull(hub.state.value.resultId)
    }

    @Test fun newTurnRightAfterCancelIsAccepted() {
        val (hub, _) = ready()
        startTurn(hub)
        hub.cancel()
        val t2 = UUID.randomUUID().toString(); val g2 = UUID.randomUUID().toString()
        assertTrue(hub.submit(conversation, t2, g2, ByteArray(10) { 1 }))
        assertEquals(t2, hub.state.value.audioTurnId)
    }

    @Test fun cancelAfterTurnEndOnlyEmptiesQueueAndSendsNothing() {
        val (hub, wire) = ready()
        startTurn(hub)
        segment(hub, ByteArray(100) { 1 })
        hub.onText("""{"type":"TURN_END","turn_id":"$turn","generation_id":"$gen"}""")
        hub.cancel()
        Thread.sleep(150)
        assertFalse(wire.texts.any { it.contains("\"CANCEL\"") })
        assertTrue(hub.state.value.segments.isEmpty()); assertNull(hub.state.value.audioTurnId)
    }

    @Test fun newSubmitClearsPreviousSegments() {
        val (hub, _) = ready()
        startTurn(hub)
        segment(hub, ByteArray(100) { 1 })
        hub.onText("""{"type":"TURN_END","turn_id":"$turn","generation_id":"$gen"}""")
        assertTrue(hub.submit(conversation, UUID.randomUUID().toString(), UUID.randomUUID().toString(), ByteArray(10) { 1 }))
        assertTrue(hub.state.value.segments.isEmpty())
    }
}
