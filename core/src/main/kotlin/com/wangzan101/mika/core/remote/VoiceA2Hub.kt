package com.wangzan101.mika.core.remote

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A2 has one connected VM and at most one in-flight request, independent of phone tasks. */
data class VoiceA2State(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val turnId: String? = null,
    val generationId: String? = null,
    /** Turn that owns [segments]; survives TURN_END so the player can finish the queue. Null after cancel/error. */
    val audioTurnId: String? = null,
    val status: String = "Muse desconectado",
    val transcript: String = "",
    val reply: String = "",
    val audio: ByteArray? = null,
    /** MP3 segments of the current turn, in arrival order; each one is complete and decodable on its own. */
    val segments: List<ByteArray> = emptyList(),
    val resultId: String? = null,
    val error: String? = null,
)

fun interface VoiceA2Sender { fun send(opcode: Int, payload: ByteArray) }

class VoiceA2Hub {
    private val lock = Any()
    private data class Pending(val conversationId: String, val turnId: String, val generationId: String, val wav: ByteArray)
    private val outgoing = ArrayBlockingQueue<Pending>(1)
    private val mutable = MutableStateFlow(VoiceA2State())
    val state = mutable.asStateFlow()
    private var sender: VoiceA2Sender? = null
    private var current: String? = null
    private var generation: String? = null
    private var mp3: VoiceA2Assembler? = null
    private val segmentList = ArrayList<ByteArray>()
    private var segmentBytes = 0
    /** CANCEL must never be written from the caller (UI) thread: sockets reject that on Android. */
    private val cancelWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-a2-cancel").apply { isDaemon = true }
    }

    private fun clearAudio() {
        mp3 = null
        segmentList.clear()
        segmentBytes = 0
    }

    fun attach(next: VoiceA2Sender): Boolean = synchronized(lock) {
        if (sender != null) return@synchronized false
        sender = next
        current = null
        generation = null
        clearAudio()
        outgoing.clear()
        mutable.value = VoiceA2State(connected = true, status = "Aguardando HELLO da VM")
        Thread({ pump(next) }, "voice-a2-outgoing").apply { isDaemon = true }.start()
        true
    }

    fun detach(connectedSender: VoiceA2Sender) = synchronized(lock) {
        if (sender !== connectedSender) return@synchronized
        sender = null
        outgoing.clear()
        current = null
        generation = null
        clearAudio()
        mutable.value = VoiceA2State()
    }

    /** Exactly one queued outbound WAV at a time, drained by a connection-owned worker. */
    fun submit(conversationId: String, turnId: String, generationId: String, wav: ByteArray): Boolean =
        synchronized(lock) {
            if (sender == null || !mutable.value.ready || current != null || wav.isEmpty() ||
                wav.size > VoiceA2Protocol.MAX_WAV_BYTES) return@synchronized false
            UUID.fromString(conversationId)
            UUID.fromString(turnId)
            UUID.fromString(generationId)
            current = turnId
            generation = generationId
            clearAudio()
            mutable.value = mutable.value.copy(turnId = turnId, generationId = generationId,
                status = "Áudio na fila de saída", transcript = "", reply = "", audio = null,
                segments = emptyList(), audioTurnId = turnId, resultId = null, error = null)
            if (!outgoing.offer(Pending(conversationId, turnId, generationId, wav))) {
                current = null
                generation = null
                mutable.value = mutable.value.copy(turnId = null, generationId = null,
                    status = "Fila de voz ocupada", error = "Fila de voz ocupada")
                return@synchronized false
            }
            true
        }

    private fun active(task: Pending, link: VoiceA2Sender): Boolean = synchronized(lock) {
        sender === link && current == task.turnId && generation == task.generationId
    }

    private fun pump(link: VoiceA2Sender) {
        while (synchronized(lock) { sender === link }) {
            val task = outgoing.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (!active(task, link)) continue
            try {
                sendText(link, VoiceA2Protocol.control("TURN_START",
                    "conversation_id" to task.conversationId, "turn_id" to task.turnId,
                    "generation_id" to task.generationId, "format" to "wav_pcm16_16000_mono",
                    "total_bytes" to task.wav.size.toString()))
                var seq = 0
                for (start in task.wav.indices step VoiceA2Protocol.CHUNK_BYTES) {
                    if (!active(task, link)) break
                    val block = task.wav.copyOfRange(start, minOf(start + VoiceA2Protocol.CHUNK_BYTES, task.wav.size))
                    link.send(2, VoiceA2Protocol.encode(VoiceA2Protocol.WAV, task.turnId, seq++, block))
                }
                if (!active(task, link)) continue
                sendText(link, VoiceA2Protocol.control("AUDIO_END",
                    "turn_id" to task.turnId, "generation_id" to task.generationId, "kind" to "wav"))
                synchronized(lock) {
                    if (active(task, link)) mutable.value = mutable.value.copy(status = "Aguardando resposta do Muse")
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (active(task, link)) {
                        current = null
                        generation = null
                        mutable.value = mutable.value.copy(turnId = null, generationId = null,
                            status = "Falha ao transmitir WAV", error = "Conexão interrompida")
                    }
                }
            }
        }
    }

    /**
     * Barge-in: drops local state at once and sends CANCEL from a worker thread (never the caller's, which is
     * the UI thread). Frames of this turn that are still in flight are ignored because [current] is cleared.
     * With no turn in flight (reply already finished, player still draining) it only empties the segment queue.
     */
    fun cancel(): Unit = synchronized(lock) {
        val turn = current
        val gen = generation
        val out = sender
        if (turn != null && gen != null && out != null) {
            outgoing.clear()
            val message = VoiceA2Protocol.control("CANCEL", "turn_id" to turn, "generation_id" to gen)
            cancelWriter.execute { runCatching { sendText(out, message) } }
        }
        current = null
        generation = null
        clearAudio()
        mutable.value = mutable.value.copy(turnId = null, generationId = null, audioTurnId = null,
            status = if (turn != null) "Turno cancelado" else mutable.value.status,
            audio = null, segments = emptyList(), resultId = null)
    }

    /** Unsolicited or stale generations are ignored rather than executed or played. */
    fun onText(value: String): Unit = synchronized(lock) {
        val msg = try { VoiceA2Protocol.parseControl(value) } catch (_: Exception) { return@synchronized }
        val type = msg["type"]?.jsonPrimitive?.content ?: return@synchronized
        val out = sender ?: return@synchronized
        when (type) {
            "HELLO" -> {
                if (msg["protocol_version"]?.jsonPrimitive?.content != "1") {
                    sendText(out, VoiceA2Protocol.control("ERROR", "code" to "unsupported_version"))
                    return@synchronized
                }
                mutable.value = mutable.value.copy(ready = true, status = "Muse conectado")
                // segments=1: this hub queues MP3 segments as they arrive, so the gateway may stream one per sentence.
                sendText(out, VoiceA2Protocol.control("READY", "protocol_version" to "1", "segments" to "1"))
            }
            "PING" -> sendText(out, VoiceA2Protocol.control("PONG"))
            "PONG" -> Unit
            else -> {
                val turn = msg["turn_id"]?.jsonPrimitive?.content
                val gen = msg["generation_id"]?.jsonPrimitive?.content
                if (turn != current || gen != generation || turn == null) return@synchronized
                try {
                    when (type) {
                        "TRANSCRIPT" -> mutable.value = mutable.value.copy(
                            transcript = VoiceA2Protocol.field(msg, "text"), status = "Transcrição recebida")
                        "REPLY" -> mutable.value = mutable.value.copy(
                            reply = VoiceA2Protocol.field(msg, "text"), status = "Resposta recebida")
                        "AUDIO_START" -> {
                            require(VoiceA2Protocol.field(msg, "format") == "mp3")
                            require(mp3 == null) { "segment started before the previous one ended" }
                            require(segmentList.size < VoiceA2Protocol.MAX_SEGMENTS) { "too many segments" }
                            mp3 = VoiceA2Assembler(VoiceA2Protocol.MP3, turn, VoiceA2Protocol.bytes(msg))
                        }
                        "AUDIO_END" -> {
                            require(VoiceA2Protocol.field(msg, "kind") == "mp3")
                            val segment = (mp3 ?: error("no audio start")).finish()
                            mp3 = null
                            require(segmentBytes + segment.size <= VoiceA2Protocol.MAX_TURN_MP3_BYTES) { "turn audio too large" }
                            segmentBytes += segment.size
                            segmentList.add(segment)
                            // Publish each completed segment now: the player starts on the first one, not on TURN_END.
                            mutable.value = mutable.value.copy(segments = segmentList.toList(),
                                audioTurnId = turn, status = "Reproduzindo resposta")
                        }
                        "TURN_END" -> {
                            require(mp3 == null) { "turn ended inside a segment" }
                            require(segmentList.isNotEmpty()) { "audio missing" }
                            require(mutable.value.reply.isNotEmpty()) { "reply missing" }
                            // `audio` keeps the single-blob contract (wake service); MP3 frames concatenate cleanly.
                            val whole = ByteArray(segmentBytes)
                            var at = 0
                            for (part in segmentList) { part.copyInto(whole, at); at += part.size }
                            mutable.value = mutable.value.copy(audio = whole, resultId = turn,
                                turnId = null, generationId = null,
                                status = "Resposta pronta para reprodução")
                            current = null
                            generation = null
                            clearAudio()
                        }
                        "ERROR" -> {
                            mutable.value = mutable.value.copy(
                                status = "Muse indisponível", turnId = null, generationId = null,
                                audioTurnId = null, segments = emptyList(),
                                error = msg["code"]?.jsonPrimitive?.content ?: "Erro no gateway")
                            current = null
                            generation = null
                            clearAudio()
                        }
                        "CANCEL" -> {
                            current = null
                            generation = null
                            clearAudio()
                            mutable.value = mutable.value.copy(status = "Turno cancelado", turnId = null,
                                generationId = null, audioTurnId = null, segments = emptyList())
                        }
                    }
                } catch (_: Exception) {
                    current = null
                    generation = null
                    clearAudio()
                    mutable.value = mutable.value.copy(status = "Resposta inválida do gateway",
                        turnId = null, generationId = null, audioTurnId = null, segments = emptyList(),
                        error = "Violação do protocolo A2")
                }
            }
        }
    }

    fun onBinary(data: ByteArray): Unit = synchronized(lock) {
        val frame = try { VoiceA2Protocol.decode(data) } catch (_: Exception) { return@synchronized }
        if (frame.kind != VoiceA2Protocol.MP3 || frame.turnId != current || mp3 == null) return@synchronized
        try { mp3!!.append(frame) } catch (_: Exception) {
            clearAudio()
            current = null
            generation = null
            mutable.value = mutable.value.copy(status = "Áudio inválido",
                turnId = null, generationId = null, audioTurnId = null, segments = emptyList(),
                error = "Sequência de chunks inválida")
        }
    }

    private fun sendText(s: VoiceA2Sender, value: String) = s.send(1, value.toByteArray(Charsets.UTF_8))
}
