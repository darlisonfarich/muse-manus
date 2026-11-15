// Formato do fio da voz do Muse para o passo 5.0 (admissão sem áudio). Port das partes puras de
// musegadget/voice_call.py e musegadget/streaming.py do facebookincubator/muse-gadget-sdk em aa43f0f
// (Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0): o call.start, a oferta de
// end_call_stop, o corpo e o ACK do end-call, a leitura NDJSON e as regras de prontidão.
// Mudanças: Kotlin; o relógio entra como número (ms) para os testes não dependerem da hora real.
package com.darlisonfarich.gadgetlink.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

object VoiceWire {
    const val REALTIME_PATH = "/voice/realtime"
    const val END_CALL_PATH = "/voice/end-call"
    const val NDJSON = "application/x-ndjson; charset=utf-8"
    const val JSON = "application/json"
    const val DEVICE = "mcu-wearable"

    /** A única identidade de app nomeada no SDK; vai na URL do Noise, não no cabeçalho. */
    const val APP_ID = "hatch-web"

    /** Teto de uma linha NDJSON e do corpo de uma resposta bufferizada (MAX_LINE_BYTES / MAX_RESPONSE_BYTES). */
    const val MAX_LINE_BYTES = 1024 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    /** PCM16 mono 24 kHz, no máximo 4 s por delta (MAX_AUDIO_BYTES). */
    const val SAMPLE_RATE = 24_000
    const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 4

    private val ISO_MS_OFFSET: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)

    /**
     * Como o `datetime.isoformat(timespec="milliseconds")` do SDK com fuso UTC: milissegundos SEMPRE
     * (três dígitos) e offset "+00:00", nunca "Z". O Instant.toString() do Kotlin dá "Z" e precisão
     * variável, por isso não serve.
     */
    fun isoMillisUtc(epochMs: Long): String = ISO_MS_OFFSET.format(Instant.ofEpochMilli(epochMs))

    /** O fim nunca é anterior ao começo + 1 ms (o servidor recusa uma chamada de duração zero). */
    fun endedAtMs(startedAtMs: Long, nowMs: Long): Long = maxOf(nowMs, startedAtMs + 1)

    fun realtimePath(callId: String): String = "$REALTIME_PATH?call_id=$callId"

    /** O call.start do SDK, sem thread_id (chamada nova) e sem saudação proativa. */
    fun callStart(callId: String, deviceId: String): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("hatch.voice.call.start"),
            "call_id" to JsonPrimitive(callId),
            "device" to JsonPrimitive(DEVICE),
            "device_id" to JsonPrimitive(deviceId),
            "call_media_type" to JsonPrimitive("audio"),
            "proactive_greeting_enabled" to JsonPrimitive(false),
        ),
    )

    val END_CALL_STOP_OFFER: JsonObject = JsonObject(
        linkedMapOf("type" to JsonPrimitive("hatch.voice.capability.offer"), "end_call_stop" to JsonPrimitive(1)),
    )

    /** Uma linha NDJSON compacta (sem espaços, como o json.dumps(separators=(",", ":")) do SDK). */
    fun ndjsonLine(event: JsonObject): ByteArray = (event.toString() + "\n").toByteArray(Charsets.UTF_8)

    /** O corpo do POST /voice/end-call. [threadId] só entra se a prontidão trouxe um. */
    fun endCallBody(callId: String, startedAtMs: Long, endedAtMs: Long, threadId: String?): ByteArray {
        val fields = linkedMapOf<String, JsonElement>(
            "call_id" to JsonPrimitive(callId),
            "call_started_at" to JsonPrimitive(isoMillisUtc(startedAtMs)),
            "call_ended_at" to JsonPrimitive(isoMillisUtc(endedAtMs)),
        )
        if (!threadId.isNullOrEmpty()) fields["thread_id"] = JsonPrimitive(threadId)
        return JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * O ACK do end-call só vale aninhado: HTTP 2xx E corpo {"ok": true, "result": {"ok": true}}, com
     * `true` booleano de verdade (o SDK compara com `is True`: 1 ou "true" não valem).
     */
    fun isEndCallAcknowledged(status: Int, body: ByteArray): Boolean {
        if (status !in 200..299) return false
        val root = parseObject(body) ?: return false
        if (!isTrue(root["ok"])) return false
        val result = root["result"] as? JsonObject ?: return false
        return isTrue(result["ok"])
    }

    private fun isTrue(e: JsonElement?): Boolean = e is JsonPrimitive && !e.isString && e.booleanOrNull == true

    private val json = Json { isLenient = false }

    fun parseObject(bytes: ByteArray): JsonObject? = runCatching {
        json.parseToJsonElement(Utf8Strict.decode(bytes)) as? JsonObject
    }.getOrNull()

    /**
     * O PCM de um evento de áudio, ou null se o evento não é de áudio. Lança [IllegalArgumentException]
     * para áudio inválido (formato, base64, tamanho), como o audio_from_event do SDK.
     */
    fun audioFromEvent(event: JsonObject): ByteArray? {
        val kind = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val encoded: JsonElement? = when (kind) {
            "continuous_audio_video.delta" -> {
                val audio = event["audio_delta"]
                if (audio == null || audio is JsonNull) return null
                require(audio is JsonObject) { "invalid audio delta" }
                val fmt = audio["format"]
                if (fmt != null && fmt !is JsonNull) {
                    require(fmt is JsonObject) { "unsupported audio format" }
                    val type = (fmt["type"] as? JsonPrimitive)?.content ?: ""
                    val rate = (fmt["rate"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
                    require(type == "" || type == "audio/pcm") { "unsupported audio format" }
                    require(rate == SAMPLE_RATE.toString()) { "unsupported audio rate" }
                }
                val channels = audio["num_channels"]
                require(channels == null || (channels as? JsonPrimitive)?.takeIf { !it.isString }?.content == "1") { "voice audio must be mono" }
                audio["audio_frames"]
            }
            "response.output_audio.delta", "response.audio.delta" -> event["delta"]
            else -> return null
        }
        require(encoded is JsonPrimitive && encoded.isString && encoded.content.length <= ((MAX_AUDIO_BYTES + 2) / 3) * 4) {
            "invalid or oversized audio delta"
        }
        val pcm = try {
            Base64.getDecoder().decode(encoded.content)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid audio base64")
        }
        require(pcm.size % 2 == 0 && pcm.size <= MAX_AUDIO_BYTES) { "invalid PCM16 audio length" }
        return pcm
    }
}

/** UTF-8 estrito: byte inválido é erro, não "?" (o json.loads do SDK também recusa). */
internal object Utf8Strict {
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}

/**
 * NDJSON em pedaços arbitrários, com teto por linha (read_ndjson do SDK): LF obrigatório, CRLF e
 * linhas em branco aceitos, JSON malformado ou registro que não é objeto é erro. Junta só a linha
 * pendente (não recopia o buffer inteiro a cada pedaço).
 */
class NdjsonReader(private val maxLineBytes: Int = VoiceWire.MAX_LINE_BYTES) {
    private val pending = java.io.ByteArrayOutputStream()

    /** @return os registros completos deste pedaço. Lança [IllegalArgumentException] em registro inválido. */
    fun feed(chunk: ByteArray): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var offset = 0
        while (offset < chunk.size) {
            var newline = -1
            for (i in offset until chunk.size) if (chunk[i] == '\n'.code.toByte()) { newline = i; break }
            val end = if (newline < 0) chunk.size else newline + 1
            require(pending.size() + end - offset <= maxLineBytes) { "NDJSON line too large" }
            pending.write(chunk, offset, end - offset)
            offset = end
            if (newline >= 0) {
                val line = pending.toByteArray()
                pending.reset()
                if (line.any { it != ' '.code.toByte() && it != '\r'.code.toByte() && it != '\n'.code.toByte() && it != '\t'.code.toByte() }) {
                    out += VoiceWire.parseObject(line) ?: throw IllegalArgumentException("invalid NDJSON record")
                }
            }
        }
        return out
    }

    /** Sobrou linha sem LF no fim do stream (o SDK trata como erro). */
    val hasPartialLine: Boolean get() = pending.size() > 0
}

/** O que um evento da voz significa ANTES e durante a espera da prontidão. */
sealed interface ReadinessStep {
    data object Continue : ReadinessStep

    /** Keepalive de PCM zerado (permitido antes da prontidão; não conta como áudio). */
    data object ZeroKeepalive : ReadinessStep

    data class Ready(val sessionId: String, val threadId: String?) : ReadinessStep

    /** [code] é de um vocabulário fixo; nunca o texto do servidor. */
    data class Fatal(val code: String) : ReadinessStep
}

/**
 * As regras de prontidão do VoiceCall do SDK, só a parte que vale até o hatch.voice.session (e a
 * contagem depois dele): erro antes da prontidão é fatal; áudio audível antes da prontidão é violação
 * de protocolo; PCM zerado é keepalive; stop antes da prontidão é fatal; identidade conferida.
 */
class ReadinessTracker(private val callId: String) {
    var ready = false
        private set
    var events = 0
        private set
    var keepalives = 0
        private set
    var advisoryErrors = 0
        private set

    fun onEvent(event: JsonObject): ReadinessStep {
        events++
        val kind = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ReadinessStep.Fatal("no_type")
        when (kind) {
            "error" -> {
                val error = event["error"] as? JsonObject ?: return ReadinessStep.Fatal("invalid_error")
                val code = (error["code"] as? JsonPrimitive)?.content
                if (!ready || code == "session_dead") return ReadinessStep.Fatal(if (ready) "session_dead" else "error_before_ready")
                advisoryErrors++
                return ReadinessStep.Continue
            }
            "hatch.voice.session" -> {
                if (ready || stringOf(event["call_id"]) != callId) return ReadinessStep.Fatal("identity_mismatch")
                val sessionId = stringOf(event["session_id"])
                if (sessionId == null || sessionId.isBlank()) return ReadinessStep.Fatal("no_session_id")
                val thread = event["thread_id"]
                val threadId = when {
                    thread == null || thread is JsonNull -> null
                    else -> stringOf(thread)?.takeIf { it.isNotBlank() } ?: return ReadinessStep.Fatal("bad_thread_id")
                }
                ready = true
                return ReadinessStep.Ready(sessionId, threadId)
            }
            "conversation.item.added" -> {
                val item = event["item"] as? JsonObject
                if (!ready && item != null && stringOf(item["type"]) == "session_control" &&
                    stringOf(item["name"])?.trim()?.lowercase() == "stop"
                ) {
                    return ReadinessStep.Fatal("stop_before_ready")
                }
            }
        }
        val pcm = try {
            VoiceWire.audioFromEvent(event)
        } catch (e: IllegalArgumentException) {
            return ReadinessStep.Fatal("invalid_audio")
        }
        if (pcm != null && pcm.isNotEmpty()) {
            if (pcm.all { it == 0.toByte() }) {
                keepalives++
                return ReadinessStep.ZeroKeepalive
            }
            if (!ready) return ReadinessStep.Fatal("audio_before_ready")
        }
        return ReadinessStep.Continue
    }

    private fun stringOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
