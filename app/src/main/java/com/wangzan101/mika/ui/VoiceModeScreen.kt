package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wangzan101.mika.core.voice.WakePhase
import com.wangzan101.mika.voice.wake.VoiceWakeService
import com.wangzan101.mika.voice.wake.VoiceWakeStatus
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.voice.stage1.ReverseWebSocketVoiceTransport
import com.wangzan101.mika.voice.stage1.VoiceChannelController
import com.wangzan101.mika.core.voice.SegmentCursor
import com.wangzan101.mika.voice.stage1.LiveAudioInfo
import com.wangzan101.mika.voice.stage1.VoiceLiveRecorder
import com.wangzan101.mika.voice.stage1.VoiceSegmentPlayer
import com.wangzan101.mika.voice.stage1.VoiceStageRecorder
import com.wangzan101.mika.voice.stage1.VoiceStageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Voice A2 UI with an explicit, reversible local wake-word opt-in. */
@Composable
fun VoiceModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val scope = rememberCoroutineScope()
    val settings = remember(context) { VoiceStageSettings(context.applicationContext) }
    val recorder = remember { VoiceStageRecorder() }
    val controller = remember(app) { VoiceChannelController(ReverseWebSocketVoiceTransport(app.mcp.voiceHub)) }
    val channel by controller.state.collectAsState()
    val wake by VoiceWakeStatus.state.collectAsState()
    var status by remember { mutableStateOf("Pronto") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var liveHeard by remember { mutableStateOf(false) }
    var liveInfo by remember { mutableStateOf<LiveAudioInfo?>(null) }
    var liveJob by remember { mutableStateOf<Job?>(null) }
    // Read from the capture thread (gate), written from the main thread.
    val playingFlag = remember { AtomicBoolean(false) }
    val reopenAt = remember { AtomicLong(0L) }
    val aecOn = remember { AtomicBoolean(false) }
    val player = remember(context) {
        VoiceSegmentPlayer(context.applicationContext) { now ->
            playingFlag.set(now)
            playing = now
            // Without AEC the mic would hear the tail of the Ship's own voice: keep it shut a bit longer.
            if (!now) reopenAt.set(SystemClock.elapsedRealtime() + if (aecOn.get()) 150L else 600L)
        }
    }
    // Seeded with whatever the hub already holds, so reopening the screen never replays an old answer.
    val cursor = remember(controller) {
        SegmentCursor().also { it.update(controller.state.value.audioTurnId, controller.state.value.segments) }
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            job?.cancel()
            liveJob?.cancel()
            controller.cancel()
            player.stop()
        }
    }

    // Segments are played as they arrive (first one right away), not at TURN_END. The cursor hands over
    // only what is new; a reset means the turn was cancelled, failed or replaced, so the queue is dropped.
    LaunchedEffect(channel.audioTurnId, channel.segments.size) {
        val update = cursor.update(channel.audioTurnId, channel.segments)
        if (wake.active) return@LaunchedEffect // the wake service plays its own turns
        if (update.reset) player.stop()
        for (segment in update.fresh) {
            try { player.enqueue(segment) }
            catch (_: Exception) { status = "Falha ao reproduzir o MP3 do Muse" }
        }
        if (update.fresh.isNotEmpty()) status = "Reproduzindo resposta"
    }

    val stopAll: () -> Unit = {
        // Barge-in: CANCEL goes out first, then the local queue is dropped; neither waits for the gateway.
        controller.cancel()
        player.stop()
        playing = false
        status = "Interrompido"
    }

    val startLive: () -> Unit = {
        player.stop()
        live = true
        liveHeard = false
        status = "Conversa ao vivo — microfone aberto"
        liveJob = scope.launch {
            try {
                VoiceLiveRecorder().run(
                    context,
                    gate = {
                        controller.state.value.turnId == null && !playingFlag.get() &&
                            SystemClock.elapsedRealtime() >= reopenAt.get()
                    },
                    onInfo = { info -> aecOn.set(info.aecEnabled); liveInfo = info },
                    onSpeech = { speaking -> liveHeard = speaking },
                    onUtterance = { wav ->
                        val sent = controller.send(settings.conversationId(),
                            UUID.randomUUID().toString(), UUID.randomUUID().toString(), wav)
                        status = if (sent) "Aguardando resposta do Muse" else "Muse desconectado — fala não enviada"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status = "Conversa ao vivo parou: " + (e.message ?: "erro no microfone")
            } finally {
                live = false
                liveHeard = false
            }
        }
    }
    val stopLive: () -> Unit = {
        liveJob?.cancel()
        liveJob = null
        live = false
        status = "Conversa ao vivo encerrada"
    }

    val start: () -> Unit = {
        player.stop()
        playing = false
        status = "Gravando — toque novamente para enviar (máx. 30 s)"
        recording = true
        job = scope.launch {
            try {
                val wav = recorder.record(context)
                recording = false
                sending = true
                status = "Enviando pelo canal privado"
                val conversation = settings.conversationId()
                val sent = controller.send(conversation, UUID.randomUUID().toString(), UUID.randomUUID().toString(), wav)
                status = if (sent) "Aguardando resposta do Muse" else "Muse desconectado — não enviado"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Falha na gravação ou no envio"
            } finally {
                recording = false
                sending = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted && channel.ready) start()
        else status = if (granted) "Muse desconectado" else "Microfone sem permissão"
    }
    val wakePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try { VoiceWakeService.start(context) }
            catch (e: Exception) { status = "Não foi possível iniciar escuta: " + (e.message ?: "erro") }
        } else VoiceWakeStatus.denied()
    }
    val livePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) startLive() else status = "Microfone sem permissão"
    }
    val busy = recording || sending || channel.turnId != null || live
    val wakeActive = wake.active

    PageScaffold("Modo voz", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Conexão privada com a VM")
            Text(if (channel.ready) "Muse conectado — Tailscale" else "Muse desconectado — aguardando conexão da VM",
                modifier = Modifier.testTag("voicePrivateConnection"))
            Text("Toque para gravar. Nenhuma conexão pública é necessária.")
            OutlinedButton(
                onClick = {
                    if (wakeActive) VoiceWakeService.stop(context)
                    else {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) {
                            try { VoiceWakeService.start(context) }
                            catch (e: Exception) { status = "Não foi possível iniciar escuta: " + (e.message ?: "erro") }
                        } else wakePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !recording && !sending && channel.turnId == null && !live,
                modifier = Modifier.fillMaxWidth().testTag("voiceWakeToggle"),
            ) { Text(if (wakeActive) "Desligar ativação por voz" else "Ativar 'oi Muse' offline") }
            Text("Escuta local: " + wake.detail, modifier = Modifier.testTag("voiceWakeStatus"))
            Text("Primeira ativação: download verificado de aproximadamente 31 MiB. Depois, funciona offline. " +
                "Uma notificação permite desligar; a escuta utiliza o microfone continuamente.")
            if (wakeActive) Text("Para gravar manualmente, desligue primeiro a ativação por voz.")
            OutlinedButton(
                onClick = {
                    if (live) stopLive()
                    else if (channel.ready && !busy) {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) startLive() else livePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !wakeActive && (live || (channel.ready && !busy)),
                modifier = Modifier.fillMaxWidth().height(76.dp).testTag("voiceLiveToggle"),
            ) { Text(if (live) "Encerrar conversa ao vivo" else "Conversa ao vivo (microfone aberto)") }
            if (live) {
                Text(when {
                    liveHeard -> "Ouvindo você falar…"
                    channel.turnId != null -> "Muse está processando"
                    playing -> "Muse está falando"
                    else -> "Fale quando quiser"
                } + " · " + (liveInfo?.summary ?: "iniciando microfone"), modifier = Modifier.testTag("voiceLiveState"))
            }
            Button(
                onClick = {
                    if (recording) recorder.stop()
                    else if (!busy && channel.ready) {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !wakeActive && !live && (recording || (channel.ready && !busy)),
                modifier = Modifier.fillMaxWidth().height(76.dp).testTag("voiceRecordButton"),
            ) { Text(if (recording) "Parar e enviar" else "Gravar pedido") }

            if (permissionDenied) Text("O microfone precisa de permissão. Autorize nas configurações do Android.")
            if (channel.turnId != null || playing) {
                Button(
                    onClick = stopAll,
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("voiceStopAll"),
                ) { Text("Parar agora") }
            }
            Text("Estado: " + (channel.error?.let { "Muse indisponível — $it" }
                ?: if (recording || sending || playing || live) status else channel.status),
                modifier = Modifier.testTag("voiceStatus"))
            Spacer(Modifier.height(4.dp))
            Text("Transcrição: " + channel.transcript, modifier = Modifier.testTag("voiceTranscript"))
            Text("Resposta: " + channel.reply, modifier = Modifier.testTag("voiceReply"))
        }
    }
}
