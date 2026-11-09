package com.wangzan101.mika.core.remote

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VoiceA2ProtocolTest {
    private val turn = "12345678-1234-4234-9234-123456789abc"
    private val generation = "12345678-1234-4234-9234-123456789abd"
    private val conversation = "12345678-1234-4234-9234-123456789abe"

    @Test fun binaryFrameRoundTrip() {
        val bytes = ByteArray(65_536) { (it * 3).toByte() }
        val wire = VoiceA2Protocol.encode(VoiceA2Protocol.WAV, turn, 5, bytes)
        assertEquals(25 + bytes.size, wire.size)
        assertEquals("MV01", String(wire.copyOfRange(0, 4), Charsets.US_ASCII))
        val decoded = VoiceA2Protocol.decode(wire)
        assertEquals(VoiceA2Protocol.WAV, decoded.kind)
        assertEquals(turn, decoded.turnId)
        assertEquals(5, decoded.seq)
        assertArrayEquals(bytes, decoded.payload)
    }

    @Test fun wavAssemblerHandlesMegabyteAndRejectsReplays() {
        val wav = ByteArray(960_044) { it.toByte() }
        val assembler = VoiceA2Assembler(VoiceA2Protocol.WAV, turn, wav.size)
        var seq = 0
        for (start in wav.indices step VoiceA2Protocol.CHUNK_BYTES) {
            assembler.append(VoiceA2Protocol.decode(VoiceA2Protocol.encode(
                VoiceA2Protocol.WAV, turn, seq++,
                wav.copyOfRange(start, minOf(start + VoiceA2Protocol.CHUNK_BYTES, wav.size)),
            )))
        }
        assertArrayEquals(wav, assembler.finish())
        try {
            assembler.append(VoiceA2Protocol.decode(VoiceA2Protocol.encode(
                VoiceA2Protocol.WAV, turn, 0, byteArrayOf(1))))
            fail("duplicate frame accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun assemblerRejectsWrongTurnAndIncompleteAudio() {
        val assembler = VoiceA2Assembler(VoiceA2Protocol.MP3, turn, 50)
        try {
            assembler.append(VoiceA2Protocol.decode(VoiceA2Protocol.encode(
                VoiceA2Protocol.MP3, UUID.randomUUID().toString(), 0, byteArrayOf(1))))
            fail("wrong turn accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            assembler.finish()
            fail("incomplete audio accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun stateMachineRejectsOldTurnAndCompletesExactCurrentGeneration() {
        val hub = VoiceA2Hub()
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val sender = VoiceA2Sender { op, data -> sent += op to data }
        assertTrue(hub.attach(sender))
        assertFalse(hub.state.value.ready)
        hub.onText("""{"type":"HELLO","protocol_version":1}""")
        assertTrue(hub.state.value.ready)
        assertTrue(hub.submit(conversation, turn, generation, byteArrayOf(1, 2, 3)))
        assertFalse(hub.submit(conversation, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), byteArrayOf(9)))
        assertEquals("TURN_START", VoiceA2Protocol.field(VoiceA2Protocol.parseControl(String(sent[1].second)), "type"))
        val oldId = UUID.randomUUID().toString()
        hub.onText(VoiceA2Protocol.control("TRANSCRIPT", "turn_id" to oldId,
            "generation_id" to generation, "text" to "stale"))
        assertEquals("", hub.state.value.transcript)
        hub.onText(VoiceA2Protocol.control("TRANSCRIPT", "turn_id" to turn,
            "generation_id" to UUID.randomUUID().toString(), "text" to "wrong generation"))
        assertEquals("", hub.state.value.transcript)
        hub.onText(VoiceA2Protocol.control("TRANSCRIPT", "turn_id" to turn,
            "generation_id" to generation, "text" to "pedido correto"))
        hub.onText(VoiceA2Protocol.control("REPLY", "turn_id" to turn,
            "generation_id" to generation, "text" to "resposta real"))
        hub.onText(VoiceA2Protocol.control("AUDIO_START", "turn_id" to turn,
            "generation_id" to generation, "format" to "mp3", "total_bytes" to "5"))
        hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, oldId, 0, byteArrayOf(0)))
        hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, turn, 0, byteArrayOf(1, 2, 3, 4, 5)))
        hub.onText(VoiceA2Protocol.control("AUDIO_END", "turn_id" to turn,
            "generation_id" to generation, "kind" to "mp3"))
        hub.onText(VoiceA2Protocol.control("TURN_END", "turn_id" to turn,
            "generation_id" to generation))
        assertEquals(turn, hub.state.value.resultId)
        assertEquals("pedido correto", hub.state.value.transcript)
        assertEquals("resposta real", hub.state.value.reply)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), hub.state.value.audio)
        assertNull(hub.state.value.turnId)
        hub.onText(VoiceA2Protocol.control("REPLY", "turn_id" to turn,
            "generation_id" to generation, "text" to "later duplicate"))
        assertEquals("resposta real", hub.state.value.reply)
        hub.detach(sender)
        assertFalse(hub.state.value.connected)
    }

    @Test fun cancelDiscardsLateAudioAndPermitsNewTurn() {
        val hub = VoiceA2Hub()
        val sender = VoiceA2Sender { _, _ -> }
        hub.attach(sender)
        hub.onText("""{"type":"HELLO","protocol_version":1}""")
        assertTrue(hub.submit(conversation, turn, generation, byteArrayOf(1)))
        hub.cancel()
        assertNull(hub.state.value.turnId)
        hub.onText(VoiceA2Protocol.control("REPLY", "turn_id" to turn,
            "generation_id" to generation, "text" to "late"))
        assertEquals("", hub.state.value.reply)
        assertTrue(hub.submit(conversation, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), byteArrayOf(1)))
    }

    @Test fun oversizedAndMalformedFramesRejected() {
        try {
            VoiceA2Protocol.decode(byteArrayOf(1, 2))
            fail("accepted short frame")
        } catch (_: IllegalArgumentException) {}
        try {
            VoiceA2Assembler(VoiceA2Protocol.MP3, turn, VoiceA2Protocol.MAX_MP3_BYTES + 1)
            fail("accepted oversized MP3")
        } catch (_: IllegalArgumentException) {}
    }
}
