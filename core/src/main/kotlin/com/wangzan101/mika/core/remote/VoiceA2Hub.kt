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
    val status: String = "Muse desconectado",
    val transcript: String = "",
    val reply: String = "",
    val audio: ByteArray? = null,
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
    private var resultAudio: ByteArray? = null

    fun attach(next: VoiceA2Sender): Boolean = synchronized(lock) {
        if (sender != null) return@synchronized false
        sender = next
        current = null
        generation = null
        mp3 = null
        resultAudio = null
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
        mp3 = null
        resultAudio = null
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
            mp3 = null
            resultAudio = null
            mutable.value = mutable.value.copy(turnId = turnId, generationId = generationId,
                status = "Áudio na fila de saída", transcript = "", reply = "", audio = null,
                resultId = null, error = null)
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

    fun cancel(): Unit = synchronized(lock) {
        val turn = current ?: return@synchronized
        val gen = generation ?: return@synchronized
        outgoing.clear()
        runCatching { sender?.let { sendText(it, VoiceA2Protocol.control("CANCEL",
            "turn_id" to turn, "generation_id" to gen)) } }
        current = null
        generation = null
        mp3 = null
        resultAudio = null
        mutable.value = mutable.value.copy(turnId = null, generationId = null,
            status = "Turno cancelado", audio = null, resultId = null)
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
                sendText(out, VoiceA2Protocol.control("READY", "protocol_version" to "1"))
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
                            mp3 = VoiceA2Assembler(VoiceA2Protocol.MP3, turn, VoiceA2Protocol.bytes(msg))
                            resultAudio = null
                        }
                        "AUDIO_END" -> {
                            require(VoiceA2Protocol.field(msg, "kind") == "mp3")
                            resultAudio = (mp3 ?: error("no audio start")).finish()
                            mp3 = null
                        }
                        "TURN_END" -> {
                            val audio = resultAudio ?: error("audio missing")
                            require(mutable.value.reply.isNotEmpty()) { "reply missing" }
                            mutable.value = mutable.value.copy(audio = audio, resultId = turn,
                                turnId = null, generationId = null,
                                status = "Resposta pronta para reprodução")
                            current = null
                            generation = null
                            resultAudio = null
                            mp3 = null
                        }
                        "ERROR" -> {
                            mutable.value = mutable.value.copy(
                                status = "Muse indisponível", turnId = null, generationId = null,
                                error = msg["code"]?.jsonPrimitive?.content ?: "Erro no gateway")
                            current = null
                            generation = null
                            mp3 = null
                            resultAudio = null
                        }
                        "CANCEL" -> {
                            current = null
                            generation = null
                            mp3 = null
                            resultAudio = null
                            mutable.value = mutable.value.copy(status = "Turno cancelado", turnId = null, generationId = null)
                        }
                    }
                } catch (_: Exception) {
                    current = null
                    generation = null
                    mp3 = null
                    resultAudio = null
                    mutable.value = mutable.value.copy(status = "Resposta inválida do gateway",
                        turnId = null, generationId = null,
                        error = "Violação do protocolo A2")
                }
            }
        }
    }

    fun onBinary(data: ByteArray): Unit = synchronized(lock) {
        val frame = try { VoiceA2Protocol.decode(data) } catch (_: Exception) { return@synchronized }
        if (frame.kind != VoiceA2Protocol.MP3 || frame.turnId != current || mp3 == null) return@synchronized
        try { mp3!!.append(frame) } catch (_: Exception) {
            mp3 = null
            resultAudio = null
            current = null
            generation = null
            mutable.value = mutable.value.copy(status = "Áudio inválido",
                turnId = null, generationId = null, error = "Sequência de chunks inválida")
        }
    }

    private fun sendText(s: VoiceA2Sender, value: String) = s.send(1, value.toByteArray(Charsets.UTF_8))
}
