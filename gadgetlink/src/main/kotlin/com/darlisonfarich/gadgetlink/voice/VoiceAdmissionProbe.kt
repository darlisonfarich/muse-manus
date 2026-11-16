// Passo 5.0 da voz: a admissão SEM áudio (receita de 11 quadros do debate de 2026-11-15). Abre uma
// segunda sessão Noise sem registro (app_id=hatch-web na URL), POST /voice/realtime com o call.start e
// a oferta de end_call_stop, espera o hatch.voice.session, fica ~2 s sem mandar áudio e encerra com
// POST /voice/end-call (ACK aninhado), half-close e dreno. Port do fluxo de VoiceCall._start/_receive/
// _cleanup em musegadget/voice_call.py do facebookincubator/muse-gadget-sdk em aa43f0f (Copyright (c)
// Meta Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: máquina de estados num único
// agendador (como o LinkSupervisor), relógio injetado, sem microfone nem reprodução, e o end-call
// repetido uma vez para medir a idempotência.
package com.darlisonfarich.gadgetlink.voice

import com.darlisonfarich.gadgetlink.MuseApi
import com.darlisonfarich.gadgetlink.Vm
import com.darlisonfarich.gadgetlink.noise.Header
import com.darlisonfarich.gadgetlink.noise.LinkListener
import com.darlisonfarich.gadgetlink.noise.LinkOutcome
import com.darlisonfarich.gadgetlink.noise.LinkScheduler
import com.darlisonfarich.gadgetlink.noise.LinkSession
import com.darlisonfarich.gadgetlink.noise.MuseApiPort
import com.darlisonfarich.gadgetlink.noise.PairingLoad
import com.darlisonfarich.gadgetlink.noise.PairingRecord
import com.darlisonfarich.gadgetlink.noise.PairingStore
import com.darlisonfarich.gadgetlink.noise.ResetCode
import com.darlisonfarich.gadgetlink.noise.StreamSend
import com.darlisonfarich.gadgetlink.noise.StreamSink
import java.util.UUID

/**
 * A parte da nota do FETCH_VMS com os nomes dos campos da entrada escolhida: `fields=[a,b,c]` (nomes já
 * filtrados por MuseApi.parseVmEntryFieldNames, em ordem alfabética) ou `fields=unknown` quando a porta não
 * sabe os nomes. Só nomes, nunca valores; a ordem é a recebida.
 */
fun fieldsNote(names: List<String>?): String =
    if (names == null) "fields=unknown"
    // Filtra de novo (o "+N" do teto passa): a nota não confia em quem implementou a porta.
    else names.map { if (Regex("\\+[0-9]{1,6}").matches(it)) it else MuseApi.safeFieldName(it) }.joinToString(",", "fields=[", "]")

/** Os estágios do 5.0, na ordem. Cada um gera exatamente uma linha de [VoiceStageEntry] quando roda. */
enum class VoiceStage { PAIRING, FETCH_VMS, UPGRADE, HANDSHAKE, ADMISSION, READINESS, HOLD, END_CALL, END_CALL_REPEAT, CLOSE }

/**
 * Uma linha do registro do 5.0: estágio, status HTTP, TAMANHO do corpo, ms. [note] vem de um vocabulário
 * fixo deste arquivo; nunca é texto do servidor, token, id de chamada nem conteúdo. Única exceção: os
 * NOMES dos campos do fetch_vms no FETCH_VMS ([fieldsNote]), filtrados por MuseApi.safeFieldName.
 */
data class VoiceStageEntry(val stage: VoiceStage, val ok: Boolean, val status: Int?, val bytes: Long?, val ms: Long, val note: String) {
    fun line(): String =
        "${stage.name} ${if (ok) "ok" else "FAIL"} http=${status ?: "-"} bytes=${bytes ?: "-"} ms=$ms" +
            if (note.isEmpty()) "" else " $note"
}

/** O resumo do 5.0 (o que o debate quer saber: B4 admitiu? ficou pronto? o end-call fechou?). */
data class VoiceAdmissionResult(
    val admissionSent: Boolean,
    val admissionStatus: Int?,
    val ready: Boolean,
    val endCallAcknowledged: Boolean,
    val repeatStatus: Int?,
    val repeatAcknowledged: Boolean,
    /** O primeiro estágio que falhou (null = tudo certo). */
    val failedStage: VoiceStage?,
    val failedNote: String,
)

/** Relógios do 5.0: monotônico para durações, parede para as datas do end-call. */
interface VoiceClock {
    fun monoMs(): Long
    fun wallMs(): Long
}

/** Abre a sessão Noise SEM registro (a real é OkHttpLinkLauncher.open com app_id=hatch-web). */
fun interface VoiceConnector {
    /** [onUpgrade]: 101 abriu, outro código = recusado, null = sem resposta HTTP. null = nem tentou. */
    fun connect(vm: Vm, pairing: PairingRecord, listener: LinkListener, onUpgrade: (Int?) -> Unit): LinkSession?
}

interface VoiceProbeListener {
    fun onStage(entry: VoiceStageEntry) {}
    fun onFinished(result: VoiceAdmissionResult) {}

    /** Linhas técnicas da sessão Noise (já sem token nem conteúdo). */
    fun log(line: String) {}
}

/**
 * O 5.0 de uma vez só (não se reaproveita). Tudo roda na thread do [scheduler]: os callbacks da sessão
 * (que chegam na thread do WebSocket, sob o monitor da sessão) só repassam o evento ao agendador, então
 * nenhum callback chama a sessão de volta e nenhum estado daqui é tocado por duas threads.
 *
 * Regra do SDK que este fluxo mantém: depois que a admissão foi enviada, o end-call roda em TODO
 * caminho (resposta recusada, prontidão que não vem, EOF, reset, prazo), com a limpeza inteira em ≤10 s;
 * um ACK validado não é desfeito por um EOF atrasado da mídia.
 */
class VoiceAdmissionProbe(
    private val deviceId: String,
    private val store: PairingStore,
    private val api: MuseApiPort,
    private val connector: VoiceConnector,
    private val scheduler: LinkScheduler,
    private val clock: VoiceClock,
    private val listener: VoiceProbeListener,
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    private val timing: Timing = Timing(),
) {
    /** Prazos do SDK (handshake 20 s, prontidão 35 s, limpeza 10 s, fechamento 1 s) e a espera sem áudio. */
    data class Timing(
        val handshakeMs: Long = 20_000,
        val readyMs: Long = 35_000,
        val holdMs: Long = 2_000,
        val cleanupMs: Long = 10_000,
        val closeMs: Long = 1_000,
    )

    private val entries = ArrayList<VoiceStageEntry>()
    private var started = false
    private var finished = false

    /** Cada espera agendada leva o número da fase; se a fase mudou, ela não faz nada. */
    private var phase = 0
    private var stage: VoiceStage = VoiceStage.PAIRING
    private var stageStartMs = 0L

    private var session: LinkSession? = null
    private var sessionClosed = false
    private var upgradeStatus: Int? = null

    private var callId = ""
    private var startedWallMs = 0L
    private var admissionSent = false
    private var admissionStatus: Int? = null
    private var mediaStream: Long? = null
    private var mediaEnded = false
    private var mediaBytes = 0L
    private val ndjson = NdjsonReader()
    private var tracker = ReadinessTracker("")
    private var threadId: String? = null
    private var holdBytesStart = 0L
    private var holdEventsStart = 0

    private var cleaning = false
    private var cleanupDeadlineMs = 0L
    private var endCallAck = false
    private var repeatStatus: Int? = null
    private var repeatAck = false

    fun start() {
        scheduler.schedule(0) {
            if (started) return@schedule
            started = true
            pairing()
        }
    }

    /** O registro até agora (cópia). */
    fun entries(): List<VoiceStageEntry> = ArrayList(entries)

    // ---- estágios -------------------------------------------------------------------------------
    private fun begin(next: VoiceStage) {
        stage = next
        stageStartMs = clock.monoMs()
        phase++
    }

    private fun record(ok: Boolean, status: Int?, bytes: Long?, note: String = "") {
        val entry = VoiceStageEntry(stage, ok, status, bytes, clock.monoMs() - stageStartMs, note)
        entries += entry
        listener.onStage(entry)
    }

    private fun pairing() {
        begin(VoiceStage.PAIRING)
        val record = when (val loaded = runCatching { store.load() }.getOrElse { PairingLoad.Invalid("unreadable") }) {
            PairingLoad.NotPaired -> return stop(ok = false, note = "not_paired")
            is PairingLoad.Invalid -> return stop(ok = false, note = "invalid_pairing")
            is PairingLoad.Paired -> loaded.record
        }
        if (record.accessToken.isEmpty()) return stop(ok = false, note = "no_access_token")
        record(true, null, null)
        fetchVms(record, rereadAllowed = true)
    }

    private fun fetchVms(pairing: PairingRecord, rereadAllowed: Boolean) {
        begin(VoiceStage.FETCH_VMS)
        val fetched = api.fetchVmListWithFields(pairing.accessToken, MuseApi.apiRoot(pairing.apiUrlV2))
        val list = fetched.list
        val count = "vms=${list.vms.size}"
        if (list.status == 401 && rereadAllowed) {
            // Token vencido/girado pelo serviço do link: relê o store UMA vez. Nunca renova daqui (a voz só lê).
            val again = (runCatching { store.load() }.getOrNull() as? PairingLoad.Paired)?.record
            if (again != null && again.accessToken != pairing.accessToken) {
                record(false, 401, null, "$count reread_store")
                return fetchVms(again, rereadAllowed = false)
            }
            return stop(ok = false, status = 401, note = "$count token_rejected")
        }
        if (list.status == null) return stop(ok = false, note = "no_response")
        if (list.status !in 200..299) return stop(ok = false, status = list.status, note = count)
        val index = list.vms.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        val vm = list.vms.getOrNull(index) ?: return stop(ok = false, status = list.status, note = "$count no_vm")
        // Os nomes dos campos da entrada escolhida (nunca os valores): a VM anuncia alguma capacidade de voz?
        val names = fetched.fieldNames?.takeIf { it.size == list.vms.size }?.get(index)
        record(true, list.status, null, count + (if (list.vms.size != 1) " not_exactly_one" else "") + " " + fieldsNote(names))
        upgrade(vm, pairing)
    }

    private fun upgrade(vm: Vm, pairing: PairingRecord) {
        begin(VoiceStage.UPGRADE)
        val opened = connector.connect(vm, pairing, object : LinkListener {
            override fun onTransportReady() = post { transportReady() }
            override fun onOutcome(outcome: LinkOutcome) = post { sessionEnded(outcome) }
            override fun log(line: String) = listener.log(line)
        }) { status -> post { upgraded(status) } }
        if (opened == null) {
            // O conector já entregou CLOSED; o desfecho chega pelo agendador e encontra a sonda parada.
            return stop(ok = false, note = "not_connected")
        }
        session = opened
        // O prazo de 20 s cobre upgrade + handshake; a sonda é de uso único e os estágios só avançam.
        scheduler.schedule(timing.handshakeMs) { handshakeTimeout() }
    }

    private fun upgraded(status: Int?) {
        if (finished || stage != VoiceStage.UPGRADE) return
        upgradeStatus = status
        if (status != 101) {
            record(false, status, null, if (status == null) "no_http_response" else "refused")
            closeSessionAndFinish()
            return
        }
        record(true, status, null)
        begin(VoiceStage.HANDSHAKE)
    }

    private fun handshakeTimeout() {
        if (finished || (stage != VoiceStage.UPGRADE && stage != VoiceStage.HANDSHAKE)) return
        record(false, upgradeStatus, null, "timeout")
        closeSessionAndFinish()
    }

    private fun transportReady() {
        if (finished || stage != VoiceStage.HANDSHAKE) return
        record(true, null, null)
        admission()
    }

    private fun admission() {
        begin(VoiceStage.ADMISSION)
        val s = session ?: return
        callId = newCallId()
        tracker = ReadinessTracker(callId)
        startedWallMs = clock.wallMs()
        // Como o SDK: marcado ANTES de escrever. Um envio ambíguo pode ter tido efeito no servidor, então
        // daqui em diante o end-call (com o NOSSO call_id) roda em todo caminho.
        admissionSent = true
        val id = s.openStream(VoiceWire.realtimePath(callId), listOf(Header("Content-Type", VoiceWire.NDJSON)), mediaSink())
        if (id == null) return failThenCleanup(null, "send_failed")
        mediaStream = id
        for (event in listOf(VoiceWire.callStart(callId, deviceId), VoiceWire.END_CALL_STOP_OFFER)) {
            if (s.sendStreamBody(id, VoiceWire.ndjsonLine(event)) != StreamSend.SENT) return failThenCleanup(null, "send_failed")
        }
        // O prazo da prontidão (35 s) conta desde a admissão, como o ready_timeout do SDK.
        scheduler.schedule(timing.readyMs) { readyTimeout() }
    }

    private fun readyTimeout() {
        if (cleaning || finished || tracker.ready) return
        if (stage == VoiceStage.ADMISSION || stage == VoiceStage.READINESS) failThenCleanup(admissionStatus, "timeout")
    }

    private fun mediaSink() = object : StreamSink {
        override fun onResponse(status: Int, body: ByteArray, end: Boolean) = post { mediaResponse(status, body, end) }
        override fun onBody(data: ByteArray, end: Boolean) = post { mediaBody(data, end) }
        override fun onReset(code: ResetCode, reason: String) = post { mediaReset() }
    }

    private fun mediaResponse(status: Int, body: ByteArray, end: Boolean) {
        if (finished) return
        mediaBytes += body.size
        if (end) mediaEnded = true
        if (stage != VoiceStage.ADMISSION || admissionStatus != null) return
        admissionStatus = status
        if (status !in 200..299) {
            // B4 negativo (4xx), rota inexistente (404) ou servidor (5xx): o tamanho do erro, nunca o texto.
            return failThenCleanup(status, "rejected", bytes = body.size.toLong())
        }
        record(true, status, body.size.toLong())
        begin(VoiceStage.READINESS)
        mediaData(body, end)
    }

    private fun mediaBody(data: ByteArray, end: Boolean) {
        if (finished) return
        mediaBytes += data.size
        if (end) mediaEnded = true
        if (cleaning) {
            if (end && stage == VoiceStage.CLOSE) closed("eof")
            return
        }
        if (stage == VoiceStage.ADMISSION) return failThenCleanup(null, "body_before_head")
        mediaData(data, end)
    }

    private fun mediaData(data: ByteArray, end: Boolean) {
        if (cleaning) return
        val events = try {
            ndjson.feed(data)
        } catch (e: IllegalArgumentException) {
            return failThenCleanup(null, "bad_ndjson")
        }
        for (event in events) {
            when (val step = tracker.onEvent(event)) {
                is ReadinessStep.Ready -> {
                    threadId = step.threadId
                    record(true, admissionStatus, mediaBytes, "events=${tracker.events} keepalives=${tracker.keepalives}")
                    hold()
                }
                is ReadinessStep.Fatal -> return failThenCleanup(null, step.code)
                else -> Unit
            }
            if (cleaning) return
        }
        if (end) {
            mediaEnded = true
            when (stage) {
                VoiceStage.READINESS -> failThenCleanup(null, if (ndjson.hasPartialLine) "truncated_line" else "ended_before_ready")
                VoiceStage.HOLD -> holdDone("remote_end")
                else -> Unit
            }
        }
    }

    private fun mediaReset() {
        if (finished) return
        mediaEnded = true
        mediaStream = null // o servidor já fechou o stream: nada de half-close nele
        when {
            cleaning -> if (stage == VoiceStage.CLOSE) closed("reset")
            stage == VoiceStage.HOLD -> holdDone("remote_reset")
            else -> failThenCleanup(admissionStatus, "reset")
        }
    }

    private fun hold() {
        begin(VoiceStage.HOLD)
        holdBytesStart = mediaBytes
        holdEventsStart = tracker.events
        val p = phase
        // 5.0 NÃO manda áudio: só espera, contando o que chega.
        scheduler.schedule(timing.holdMs) { if (p == phase) holdDone("") }
    }

    private fun holdDone(note: String) {
        if (cleaning || finished || stage != VoiceStage.HOLD) return
        val counts = "events=${tracker.events - holdEventsStart} keepalives=${tracker.keepalives}" +
            if (tracker.advisoryErrors > 0) " advisory_errors=${tracker.advisoryErrors}" else ""
        record(true, null, mediaBytes - holdBytesStart, if (note.isEmpty()) counts else "$counts $note")
        cleanup()
    }

    private fun sessionEnded(outcome: LinkOutcome) {
        sessionClosed = true
        if (finished) return
        val note = "session_" + outcome.name.lowercase()
        when {
            stage == VoiceStage.UPGRADE -> if (upgradeStatus == null) {
                // Recusa sem passar por upgraded() (o conector não viu status): fecha aqui.
                record(false, null, null, note)
                finish()
            }
            stage == VoiceStage.HANDSHAKE -> {
                record(false, upgradeStatus, null, note)
                finish()
            }
            cleaning -> when (stage) {
                VoiceStage.END_CALL, VoiceStage.END_CALL_REPEAT -> endCallFailed(note)
                VoiceStage.CLOSE -> closed(note)
                else -> Unit
            }
            stage == VoiceStage.HOLD -> holdDone(note)
            else -> failThenCleanup(admissionStatus, note)
        }
    }

    // ---- limpeza ---------------------------------------------------------------------------------
    private fun failThenCleanup(status: Int?, note: String, bytes: Long? = null) {
        if (cleaning || finished) return
        record(false, status, bytes ?: mediaBytes.takeIf { stage != VoiceStage.ADMISSION }, note)
        cleanup()
    }

    private fun cleanup() {
        if (cleaning) return
        cleaning = true
        cleanupDeadlineMs = clock.monoMs() + timing.cleanupMs
        phase++
        scheduler.schedule(timing.cleanupMs) { cleanupTimeout() }
        if (admissionSent) endCall(repeat = false) else close()
    }

    private fun cleanupTimeout() {
        if (finished) return
        when (stage) {
            VoiceStage.END_CALL, VoiceStage.END_CALL_REPEAT -> endCallFailed("cleanup_timeout")
            VoiceStage.CLOSE -> Unit // o fechamento tem o próprio prazo de 1 s
            else -> close()
        }
    }

    private var endCallStream: Long? = null
    private var endCallStatus: Int? = null
    private val endCallBody = java.io.ByteArrayOutputStream()

    private fun endCall(repeat: Boolean) {
        begin(if (repeat) VoiceStage.END_CALL_REPEAT else VoiceStage.END_CALL)
        endCallStatus = null
        endCallBody.reset()
        val s = session
        if (s == null || sessionClosed) return endCallFailed("session_closed")
        val ended = VoiceWire.endedAtMs(startedWallMs, clock.wallMs())
        val body = VoiceWire.endCallBody(callId, startedWallMs, ended, threadId)
        val myPhase = phase
        val id = s.openRequest(
            "POST", VoiceWire.END_CALL_PATH, listOf(Header("Content-Type", VoiceWire.JSON)), body,
            object : StreamSink {
                override fun onResponse(status: Int, body: ByteArray, end: Boolean) = post { endCallResponse(myPhase, status, body, end) }
                override fun onBody(data: ByteArray, end: Boolean) = post { endCallData(myPhase, data, end) }
                override fun onReset(code: ResetCode, reason: String) = post { if (myPhase == phase) endCallFailed("reset") }
            },
        )
        if (id == null) return endCallFailed("send_failed")
        endCallStream = id
    }

    private fun endCallResponse(p: Int, status: Int, body: ByteArray, end: Boolean) {
        if (p != phase || finished) return
        if (endCallStatus != null || status !in 100..599) return endCallFailed("invalid_head")
        endCallStatus = status
        endCallData(p, body, end)
    }

    private fun endCallData(p: Int, data: ByteArray, end: Boolean) {
        if (p != phase || finished) return
        if (endCallStatus == null) return endCallFailed("body_before_head")
        if (endCallBody.size() + data.size > VoiceWire.MAX_RESPONSE_BYTES) {
            endCallStream?.let { session?.resetStream(it, "response too large") }
            return endCallFailed("too_large")
        }
        endCallBody.write(data)
        if (!end) return
        endCallStream = null
        val status = endCallStatus!!
        val ack = VoiceWire.isEndCallAcknowledged(status, endCallBody.toByteArray())
        record(ack, status, endCallBody.size().toLong(), if (ack) "ack" else "not_acknowledged")
        if (stage == VoiceStage.END_CALL) {
            endCallAck = ack
            endCall(repeat = true)
        } else {
            repeatStatus = status
            repeatAck = ack
            close()
        }
    }

    private fun endCallFailed(note: String) {
        if (finished || (stage != VoiceStage.END_CALL && stage != VoiceStage.END_CALL_REPEAT)) return
        endCallStream?.let { id -> if (!sessionClosed) session?.resetStream(id, "request ended") }
        endCallStream = null
        record(false, endCallStatus, endCallBody.size().toLong().takeIf { endCallStatus != null }, note)
        if (stage == VoiceStage.END_CALL_REPEAT) repeatStatus = endCallStatus
        // O repetido só faz sentido se ainda há sessão e tempo de limpeza.
        if (stage == VoiceStage.END_CALL && !sessionClosed && clock.monoMs() < cleanupDeadlineMs) endCall(repeat = true) else close()
    }

    private fun close() {
        begin(VoiceStage.CLOSE)
        val s = session
        val id = mediaStream
        if (s == null || sessionClosed) return closed(if (s == null) "no_session" else "session_closed")
        if (id == null) return closed("no_stream") // nunca abriu, ou o servidor resetou
        // Half-close do nosso lado (corpo vazio com end_body), mesmo que o servidor já tenha terminado o dele;
        // depois espera o EOF da mídia por até 1 s.
        if (s.sendStreamBody(id, ByteArray(0), end = true) != StreamSend.SENT) return closed("half_close_failed")
        if (mediaEnded) return closed("eof")
        val p = phase
        scheduler.schedule(timing.closeMs) {
            if (p != phase || finished) return@schedule
            mediaStream?.let { session?.resetStream(it, "call ended") }
            closed("reset_after_drain_timeout")
        }
    }

    private fun closed(note: String) {
        if (finished || stage != VoiceStage.CLOSE) return
        record(note == "eof" || note == "no_stream" || note == "reset_after_drain_timeout" || note == "reset", null, mediaBytes, note)
        closeSessionAndFinish()
    }

    private fun stop(ok: Boolean, status: Int? = null, note: String) {
        record(ok, status, null, note)
        finish()
    }

    private fun closeSessionAndFinish() {
        session?.let { runCatching { it.stop() } }
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        phase++
        session?.let { runCatching { it.stop() } }
        val failed = entries.firstOrNull { !it.ok }
        listener.onFinished(
            VoiceAdmissionResult(
                admissionSent = admissionSent,
                admissionStatus = admissionStatus,
                ready = tracker.ready,
                endCallAcknowledged = endCallAck,
                repeatStatus = repeatStatus,
                repeatAcknowledged = repeatAck,
                failedStage = failed?.stage,
                failedNote = failed?.note ?: "",
            ),
        )
    }

    private fun post(task: () -> Unit) = scheduler.schedule(0) { task() }
}

/**
 * Leitura humana da tabela estágio × sintoma do debate (o que cada falha quer dizer). Não lê corpo,
 * só estágio, status e a nota fixa.
 */
object VoiceAdmissionDiagnosis {
    fun explain(result: VoiceAdmissionResult, entries: List<VoiceStageEntry>): String {
        val failed = entries.firstOrNull { !it.ok }
        if (failed == null) {
            return "admitido, pronto e encerrado com ACK" +
                (result.repeatStatus?.let { ", end-call repetido: HTTP $it" + if (result.repeatAcknowledged) " com ACK" else " sem ACK" } ?: "")
        }
        val s = failed.status
        return when (failed.stage) {
            VoiceStage.PAIRING -> "não pareado (nada saiu)"
            VoiceStage.FETCH_VMS -> when {
                s == 401 -> "token do device vencido/girado (o store foi relido 1x)"
                failed.note.contains("no_vm") -> "nenhuma VM utilizável"
                else -> "fetch_vms falhou (HTTP ${s ?: "-"})"
            }
            VoiceStage.UPGRADE -> when (s) {
                401 -> "credencial da VM velha: refazer o fetch_vms, não repetir o bearer"
                403 -> "403 ambíguo (app_id ou admissão; com o link vivo o token está aceito) — caso do oráculo"
                else -> "upgrade inconclusivo (HTTP ${s ?: "-"})"
            }
            VoiceStage.HANDSHAKE -> "protocolo Noise (nada a ver com voz)"
            VoiceStage.ADMISSION -> when {
                s == 404 -> "rota /voice/realtime inexistente nesta VM"
                s != null && s in 400..499 -> "B4 negativo: admissão recusada (HTTP $s)"
                s != null && s >= 500 -> "erro do servidor na admissão (HTTP $s)"
                else -> "admissão sem resposta (${failed.note})"
            }
            VoiceStage.READINESS -> when (failed.note) {
                "timeout" -> "admitido, mas a pilha de voz não subiu (sem hatch.voice.session)"
                "ended_before_ready" -> "ended before readiness"
                "error_before_ready" -> "erro antes da prontidão (fatal)"
                "audio_before_ready" -> "violação de protocolo: PCM não zerado antes da prontidão"
                else -> "prontidão falhou (${failed.note})"
            }
            VoiceStage.HOLD -> "a chamada caiu durante a espera (${failed.note})"
            VoiceStage.END_CALL, VoiceStage.END_CALL_REPEAT -> "end-call não confirmado (${failed.note})"
            VoiceStage.CLOSE -> "fechamento (${failed.note})"
        } + if (failed.stage.ordinal > VoiceStage.END_CALL.ordinal || !result.admissionSent) "" else
            "; end-call: " + if (result.endCallAcknowledged) "ACK" else "sem ACK"
    }
}
