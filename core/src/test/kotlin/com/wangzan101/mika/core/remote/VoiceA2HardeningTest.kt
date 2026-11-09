package com.wangzan101.mika.core.remote

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class VoiceA2HardeningTest {
    private val conversation = UUID.randomUUID().toString()
    private fun hello(hub: VoiceA2Hub, sender: VoiceA2Sender) {
        assertTrue(hub.attach(sender))
        hub.onTextFrom(sender, """{"type":"HELLO","protocol_version":1}""")
        assertTrue(hub.state.value.ready)
    }
    private fun turn(hub: VoiceA2Hub): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val gen = UUID.randomUUID().toString()
        assertTrue(hub.submit(conversation, id, gen, byteArrayOf(1, 2, 3)))
        return id to gen
    }
    private fun control(type: String, id: String, gen: String, vararg extra: Pair<String, String>) =
        VoiceA2Protocol.control(type, "turn_id" to id, "generation_id" to gen, *extra)

    @Test fun blockedSocketWriteDoesNotHoldHubLockAndCancelClosesSocket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = object : VoiceA2Sender {
            override fun send(opcode: Int, payload: ByteArray) {
                if (String(payload).contains("TURN_START")) {
                    entered.countDown()
                    release.await(4, TimeUnit.SECONDS)
                }
            }
            override fun close() { release.countDown() }
        }
        val hub = VoiceA2Hub()
        hello(hub, sender)
        val (id, gen) = turn(hub)
        assertTrue("sender was not blocked", entered.await(2, TimeUnit.SECONDS))
        val before = System.nanoTime()
        hub.cancel()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue("cancel blocked for " + elapsedMs + "ms", elapsedMs < 400)
        assertNull(hub.state.value.turnId)
        assertFalse(hub.state.value.connected)
        hub.onTextFrom(sender, control("REPLY", id, gen, "text" to "old"))
        assertEquals("", hub.state.value.reply)
    }

    @Test fun timeoutAfterPartialTranscriptReleasesTurnForNewRequestWithoutReplay() {
        val clock = AtomicLong(1_000)
        val controls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sender = VoiceA2Sender { opcode, data ->
            if (opcode == 1) controls += String(data)
        }
        val hub = VoiceA2Hub(nowMs = clock::get,
            limits = VoiceA2Hub.Timeouts(uploadMs = 5_000, transcriptMs = 10_000,
                replyMs = 20_000, audioMs = 30_000, maxTurnMs = 60_000))
        hello(hub, sender)
        val (id, gen) = turn(hub)
        val waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!controls.any { it.contains("AUDIO_END") } && System.nanoTime() < waitUntil) Thread.sleep(10)
        assertTrue("upload was not completed", controls.any { it.contains("AUDIO_END") })
        hub.onText(control("TRANSCRIPT", id, gen, "text" to "voz parcial"))
        clock.addAndGet(20_001)
        assertTrue(hub.checkTimeout())
        assertNull(hub.state.value.turnId)
        assertTrue(hub.state.value.ready)
        assertEquals("Muse não respondeu a tempo", hub.state.value.error)
        assertEquals("voz parcial", hub.state.value.transcript)
        hub.onText(control("REPLY", id, gen, "text" to "resposta atrasada"))
        assertEquals("", hub.state.value.reply)
        val (next, _) = turn(hub)
        assertNotEquals(id, next)
        assertFalse(hub.checkTimeout())
        hub.detach(sender)
    }

    @Test fun timeoutWhileUploadBlockedClosesSocketAndRejectsLateGeneration() {
        val clock = AtomicLong(1_000)
        val blocked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val sender = object : VoiceA2Sender {
            override fun send(opcode: Int, payload: ByteArray) {
                if (String(payload).contains("TURN_START")) {
                    blocked.countDown()
                    released.await(4, TimeUnit.SECONDS)
                }
            }
            override fun close() { released.countDown() }
        }
        val hub = VoiceA2Hub(nowMs = clock::get,
            limits = VoiceA2Hub.Timeouts(uploadMs = 50, transcriptMs = 10_000))
        hello(hub, sender)
        val (id, gen) = turn(hub)
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        clock.addAndGet(51)
        assertTrue(hub.checkTimeout())
        assertFalse(hub.state.value.connected)
        assertNull(hub.state.value.turnId)
        assertEquals("Muse não respondeu a tempo", hub.state.value.error)
        hub.onTextFrom(sender, control("REPLY", id, gen, "text" to "invalido"))
        assertNull(hub.state.value.resultId)
        assertEquals("", hub.state.value.reply)
    }

    @Test fun reconnectIsCleanAndOldSocketCannotInjectNewSessionEvents() {
        val hub = VoiceA2Hub()
        val old = VoiceA2Sender { _, _ -> }
        hello(hub, old)
        val (id, gen) = turn(hub)
        hub.detach(old)
        assertFalse(hub.state.value.connected)
        assertNull(hub.state.value.turnId)
        assertNull(hub.state.value.resultId)
        assertEquals("", hub.state.value.transcript)
        val next = VoiceA2Sender { _, _ -> }
        hello(hub, next)
        hub.onTextFrom(old, control("TRANSCRIPT", id, gen, "text" to "stale old socket"))
        hub.onBinaryFrom(old, VoiceA2Protocol.encode(VoiceA2Protocol.MP3, id, 0, byteArrayOf(4)))
        assertEquals("", hub.state.value.transcript)
        assertNull(hub.state.value.resultId)
        assertEquals("Muse conectado", hub.state.value.status)
        val (newId, newGen) = turn(hub)
        hub.onTextFrom(old, control("REPLY", newId, newGen, "text" to "forged"))
        assertEquals("", hub.state.value.reply)
        hub.detach(old) // stale close must never reset the new connection
        assertTrue(hub.state.value.connected)
        assertEquals(newId, hub.state.value.turnId)
        hub.detach(next)
    }

    @Test fun audioResultCanBeReadOnlyOnceAndNeverByWrongId() {
        val hub = VoiceA2Hub()
        val sender = VoiceA2Sender { _, _ -> }
        hello(hub, sender)
        val (id, gen) = turn(hub)
        hub.onText(control("TRANSCRIPT", id, gen, "text" to "fala"))
        hub.onText(control("REPLY", id, gen, "text" to "resposta"))
        hub.onText(control("AUDIO_START", id, gen, "format" to "mp3", "total_bytes" to "4"))
        hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, id, 0, byteArrayOf(1, 2, 3, 4)))
        hub.onText(control("AUDIO_END", id, gen, "kind" to "mp3"))
        hub.onText(control("TURN_END", id, gen))
        assertNull(hub.takeAudio(UUID.randomUUID().toString()))
        assertEquals(id, hub.state.value.resultId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), hub.takeAudio(id))
        assertNull(hub.takeAudio(id))
        assertNull(hub.takeAudio(id))
        hub.detach(sender)
    }

    @Test fun reconnectDropsPartialMp3AndOldTurnGeneration() {
        val hub = VoiceA2Hub()
        val first = VoiceA2Sender { _, _ -> }
        hello(hub, first)
        val (id, gen) = turn(hub)
        hub.onText(control("REPLY", id, gen, "text" to "primeira"))
        hub.onText(control("AUDIO_START", id, gen, "format" to "mp3", "total_bytes" to "6"))
        hub.onBinary(VoiceA2Protocol.encode(VoiceA2Protocol.MP3, id, 0, byteArrayOf(1, 2, 3)))
        hub.detach(first)
        val second = VoiceA2Sender { _, _ -> }
        hello(hub, second)
        assertEquals("", hub.state.value.reply)
        assertNull(hub.takeAudio(id))
        hub.onBinaryFrom(first, VoiceA2Protocol.encode(VoiceA2Protocol.MP3, id, 1, byteArrayOf(4, 5, 6)))
        hub.onTextFrom(first, control("AUDIO_END", id, gen, "kind" to "mp3"))
        hub.onTextFrom(first, control("TURN_END", id, gen))
        assertNull(hub.state.value.resultId)
        assertEquals("", hub.state.value.reply)
        hub.detach(second)
    }
}
