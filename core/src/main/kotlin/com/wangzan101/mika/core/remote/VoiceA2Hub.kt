package com.wangzan101.mika.core.remote

import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonPrimitive

/** Public state carries metadata only. The MP3 is kept separately and consumed once. */
data class VoiceA2State(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val turnId: String? = null,
    val generationId: String? = null,
    val status: String = "Muse desconectado",
    val transcript: String = "",
    val reply: String = "",
    val resultId: String? = null,
    val error: String? = null,
)

fun interface VoiceA2Sender {
    fun send(opcode: Int, payload: ByteArray)
    /** Closes the underlying socket to unblock a writer stuck in send(). */
    fun close() {}
}

/**
 * One authenticated VM socket and one turn at a time.
 * The socket writer never runs while holding [lock]. Cancellation and timeout therefore
 * remain responsive even when the OS/proxy stalls a write indefinitely.
 */
class VoiceA2Hub(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val limits: Timeouts = Timeouts(),
) {
    data class Timeouts(
        val uploadMs: Long = 15_000L,
        val transcriptMs: Long = 25_000L,
        val replyMs: Long = 35_000L,
        val audioMs: Long = 30_000L,
        val maxTurnMs: Long = 120_000L,
    )

    private enum class Phase { UPLOAD, TRANSCRIPT, REPLY, AUDIO }
    private data class Pending(val conversationId: String, val turnId: String, val generationId: String, val wav: ByteArray)

    private val lock = Any()
    private val outgoing = ArrayBlockingQueue<Pending>(1)
    private val mutable = MutableStateFlow(VoiceA2State())
    val state = mutable.asStateFlow()
    private var sender: VoiceA2Sender? = null
    private var current: String? = null
    private var generation: String? = null
    private var phase: Phase? = null
    private var deadlineMs: Long = 0
    private var absoluteDeadlineMs: Long = 0
    private var uploading = false
    private var mp3: VoiceA2Assembler? = null
    private var resultAudio: ByteArray? = null
    private var pendingAudio: ByteArray? = null
    private var pendingAudioId: String? = null
    private var watchdog: Thread? = null

    fun attach(next: VoiceA2Sender): Boolean = synchronized(lock) {
        if (sender != null) return@synchronized false
        sender = next
        clearTurnLocked()
        mutable.value = VoiceA2State(connected = true, status = "Aguardando HELLO da VM")
        Thread({ pump(next) }, "voice-a2-outgoing").apply { isDaemon = true }.start()
        watchdog = Thread({
            while (synchronized(lock) { sender === next }) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                checkTimeout()
            }
        }, "voice-a2-watchdog").apply { isDaemon = true; start() }
        true
    }

    /** An old connection must not reset a newer authenticated socket. */
    fun detach(link: VoiceA2Sender) {
        synchronized(lock) {
            if (sender !== link) return
            sender = null
            clearTurnLocked()
            mutable.value = VoiceA2State()
        }
        watchdog?.interrupt()
    }

    private fun clearTurnLocked() {
        outgoing.clear()
        current = null
        generation = null
        phase = null
        deadlineMs = 0
        absoluteDeadlineMs = 0
        uploading = false
        mp3 = null
        resultAudio = null
        pendingAudio = null
        pendingAudioId = null
    }

    /** No waiting on socket I/O: the bounded queue owns the single pending WAV. */
    fun submit(conversationId: String, turnId: String, generationId: String, wav: ByteArray): Boolean = synchronized(lock) {
        if (sender == null || !mutable.value.ready || current != null || wav.isEmpty() ||
            wav.size > VoiceA2Protocol.MAX_WAV_BYTES) return@synchronized false
        UUID.fromString(conversationId)
        UUID.fromString(turnId)
        UUID.fromString(generationId)
        val now = nowMs()
        current = turnId
        generation = generationId
        phase = Phase.UPLOAD
        deadlineMs = now + limits.uploadMs
        absoluteDeadlineMs = now + limits.maxTurnMs
        mp3 = null
        resultAudio = null
        pendingAudio = null
        pendingAudioId = null
        mutable.value = mutable.value.copy(turnId = turnId, generationId = generationId,
            status = "Áudio na fila de saída", transcript = "", reply = "",
            resultId = null, error = null)
        if (!outgoing.offer(Pending(conversationId, turnId, generationId, wav))) {
            clearTurnLocked()
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
            val task = try { outgoing.poll(250, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { return }
                ?: continue
            if (!active(task, link)) continue
            synchronized(lock) { if (active(task, link)) uploading = true }
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
                    if (active(task, link)) {
                        uploading = false
                        phase = Phase.TRANSCRIPT
                        deadlineMs = nowMs() + limits.transcriptMs
                        mutable.value = mutable.value.copy(status = "Aguardando transcrição")
                    }
                }
            } catch (_: Exception) {
                val close = synchronized(lock) {
                    if (active(task, link)) {
                        sender = null
                        clearTurnLocked()
                        mutable.value = VoiceA2State(status = "Muse desconectado",
                            error = "Falha ao transmitir áudio")
                        true
                    } else false
                }
                if (close) runCatching { link.close() }
            }
        }
    }

    /**
     * Clears the turn before attempting any network cancellation. While a WAV is being
     * uploaded, close the socket to unblock the writer and require a fresh VM connection.
     */
    fun cancel() {
        val (link, message, close) = synchronized(lock) {
            val turn = current ?: return
            val gen = generation ?: return
            val link = sender
            val close = uploading
            val message = VoiceA2Protocol.control("CANCEL", "turn_id" to turn, "generation_id" to gen)
            clearTurnLocked()
            if (close) {
                sender = null
                mutable.value = VoiceA2State(status = "Turno cancelado")
            } else {
                mutable.value = mutable.value.copy(turnId = null, generationId = null,
                    status = "Turno cancelado", resultId = null)
            }
            Triple(link, message, close)
        }
        if (link != null) {
            if (close) runCatching { link.close() }
            else sendBestEffort(link, message)
        }
    }

    /** Testable timeout check; background watchdog calls it each 250 ms while connected. */
    fun checkTimeout(): Boolean {
        val (link, cancelMessage, close) = synchronized(lock) {
            val turn = current ?: return false
            val gen = generation ?: return false
            val now = nowMs()
            if (now < deadlineMs && now < absoluteDeadlineMs) return false
            val link = sender
            val close = uploading
            val msg = VoiceA2Protocol.control("CANCEL", "turn_id" to turn, "generation_id" to gen)
            clearTurnLocked()
            if (close) {
                sender = null
                mutable.value = VoiceA2State(status = "Muse não respondeu a tempo",
                    error = "Muse não respondeu a tempo")
            } else {
                mutable.value = mutable.value.copy(turnId = null, generationId = null,
                    status = "Muse não respondeu a tempo", resultId = null,
                    error = "Muse não respondeu a tempo")
            }
            Triple(link, msg, close)
        }
        if (link != null) {
            if (close) runCatching { link.close() }
            else sendBestEffort(link, cancelMessage)
        }
        return true
    }

    /** Result bytes are removed atomically; recomposition cannot play them twice. */
    fun takeAudio(resultId: String): ByteArray? = synchronized(lock) {
        if (resultId != pendingAudioId) return@synchronized null
        val bytes = pendingAudio
        pendingAudio = null
        pendingAudioId = null
        bytes
    }

    /** Bind inbound frames to the socket that actually read them (prevents stale reconnect frames). */
    fun onTextFrom(link: VoiceA2Sender, value: String) {
        synchronized(lock) { if (sender !== link) return }
        onTextOnLink(link, value)
    }
    fun onBinaryFrom(link: VoiceA2Sender, bytes: ByteArray) {
        synchronized(lock) { if (sender !== link) return }
        onBinaryOnLink(link, bytes)
    }

    /** Test helpers use the current transport; live sockets use the explicitly bound variants. */
    fun onText(value: String) { val link = synchronized(lock) { sender } ?: return; onTextOnLink(link, value) }
    fun onBinary(bytes: ByteArray) { val link = synchronized(lock) { sender } ?: return; onBinaryOnLink(link, bytes) }

    private fun onTextOnLink(link: VoiceA2Sender, value: String) {
        val msg = try { VoiceA2Protocol.parseControl(value) } catch (_: Exception) { return }
        val type = msg["type"]?.jsonPrimitive?.content ?: return
        var response: String? = null
        synchronized(lock) {
            if (sender !== link) return
            when (type) {
                "HELLO" -> {
                    response = if (msg["protocol_version"]?.jsonPrimitive?.content == "1") {
                        mutable.value = mutable.value.copy(ready = true, status = "Muse conectado")
                        VoiceA2Protocol.control("READY", "protocol_version" to "1")
                    } else VoiceA2Protocol.control("ERROR", "code" to "unsupported_version")
                }
                "PING" -> response = VoiceA2Protocol.control("PONG")
                "PONG" -> Unit
                else -> {
                    val turn = msg["turn_id"]?.jsonPrimitive?.content
                    val gen = msg["generation_id"]?.jsonPrimitive?.content
                    if (turn == null || turn != current || gen != generation) return@synchronized
                    try {
                        when (type) {
                            "TRANSCRIPT" -> {
                                mutable.value = mutable.value.copy(
                                    transcript = VoiceA2Protocol.field(msg, "text"),
                                    status = "Transcrição recebida")
                                phase = Phase.REPLY
                                deadlineMs = nowMs() + limits.replyMs
                            }
                            "REPLY" -> {
                                mutable.value = mutable.value.copy(
                                    reply = VoiceA2Protocol.field(msg, "text"),
                                    status = "Resposta recebida")
                                phase = Phase.AUDIO
                                deadlineMs = nowMs() + limits.audioMs
                            }
                            "AUDIO_START" -> {
                                require(VoiceA2Protocol.field(msg, "format") == "mp3")
                                mp3 = VoiceA2Assembler(VoiceA2Protocol.MP3, turn, VoiceA2Protocol.bytes(msg))
                                resultAudio = null
                                phase = Phase.AUDIO
                                deadlineMs = nowMs() + limits.audioMs
                            }
                            "AUDIO_END" -> {
                                require(VoiceA2Protocol.field(msg, "kind") == "mp3")
                                resultAudio = (mp3 ?: error("missing AUDIO_START")).finish()
                                mp3 = null
                                deadlineMs = nowMs() + limits.audioMs
                            }
                            "TURN_END" -> {
                                val bytes = resultAudio ?: error("audio incomplete")
                                require(mutable.value.reply.isNotEmpty()) { "reply missing" }
                                pendingAudio = bytes
                                pendingAudioId = turn
                                mutable.value = mutable.value.copy(resultId = turn, turnId = null,
                                    generationId = null, status = "Resposta pronta para reprodução")
                                current = null
                                generation = null
                                phase = null
                                mp3 = null
                                resultAudio = null
                            }
                            "ERROR", "CANCEL" -> {
                                val errorCode = if (type == "ERROR")
                                    msg["code"]?.jsonPrimitive?.content ?: "Erro no gateway" else null
                                clearTurnLocked()
                                mutable.value = mutable.value.copy(turnId = null,
                                    generationId = null, status = if (errorCode == null)
                                        "Turno cancelado" else "Muse indisponível",
                                    error = errorCode, resultId = null)
                            }
                        }
                    } catch (_: Exception) {
                        clearTurnLocked()
                        mutable.value = mutable.value.copy(turnId = null, generationId = null,
                            status = "Resposta inválida do gateway",
                            error = "Violação do protocolo A2", resultId = null)
                    }
                }
            }
        }
        // Network writes must never hold the state lock, including HELLO and PING replies.
        if (response != null) runCatching { sendText(link, response!!) }
    }

    private fun onBinaryOnLink(link: VoiceA2Sender, data: ByteArray) {
        val frame = try { VoiceA2Protocol.decode(data) } catch (_: Exception) { return }
        synchronized(lock) {
            if (sender !== link || frame.kind != VoiceA2Protocol.MP3 ||
                frame.turnId != current || mp3 == null) return
            try {
                mp3!!.append(frame)
                deadlineMs = nowMs() + limits.audioMs
            } catch (_: Exception) {
                clearTurnLocked()
                mutable.value = mutable.value.copy(turnId = null, generationId = null,
                    status = "Áudio inválido", error = "Sequência de chunks inválida",
                    resultId = null)
            }
        }
    }

    private fun sendBestEffort(link: VoiceA2Sender, json: String) {
        Thread({
            runCatching {
                if (synchronized(lock) { sender === link }) sendText(link, json)
            }
        }, "voice-a2-control").apply { isDaemon = true }.start()
    }

    private fun sendText(link: VoiceA2Sender, text: String) =
        link.send(1, text.toByteArray(Charsets.UTF_8))
}
