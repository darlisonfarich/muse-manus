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
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.voice.stage1.ReverseWebSocketVoiceTransport
import com.wangzan101.mika.voice.stage1.VoiceChannelController
import com.wangzan101.mika.voice.stage1.VoiceStagePlayer
import com.wangzan101.mika.voice.stage1.VoiceStageRecorder
import com.wangzan101.mika.voice.stage1.VoiceStageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Button-based voice UI; no public tunnel, wake-word service or phone-control coupling. */
@Composable
fun VoiceModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val scope = rememberCoroutineScope()
    val settings = remember(context) { VoiceStageSettings(context.applicationContext) }
    val recorder = remember { VoiceStageRecorder() }
    val player = remember(context) { VoiceStagePlayer(context.applicationContext) }
    val controller = remember(app) { VoiceChannelController(ReverseWebSocketVoiceTransport(app.mcp.voiceHub)) }
    val channel by controller.state.collectAsState()
    var status by remember { mutableStateOf("Pronto") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            job?.cancel()
            controller.cancel()
            player.stop()
        }
    }

    LaunchedEffect(channel.resultId) {
        val resultId = channel.resultId ?: return@LaunchedEffect
        val bytes = controller.takeAudio(resultId) ?: return@LaunchedEffect
        try {
            playing = true
            status = "Reproduzindo resposta"
            player.playBytesAsync(bytes) {
                playing = false
                status = "Resposta concluída"
            }
        } catch (_: Exception) {
            playing = false
            status = "Falha ao reproduzir o MP3 do Muse"
        }
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
    val busy = recording || sending || channel.turnId != null

    PageScaffold("Modo voz", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Conexão privada com a VM")
            Text(if (channel.ready) "Muse conectado — Tailscale" else "Muse desconectado — aguardando conexão da VM",
                modifier = Modifier.testTag("voicePrivateConnection"))
            Text("Toque para gravar. Nenhuma conexão pública é necessária.")
            Button(
                onClick = {
                    if (recording) recorder.stop()
                    else if (!busy && channel.ready) {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = recording || (channel.ready && !busy),
                modifier = Modifier.fillMaxWidth().height(76.dp).testTag("voiceRecordButton"),
            ) { Text(if (recording) "Parar e enviar" else "Gravar pedido") }

            if (permissionDenied) Text("O microfone precisa de permissão. Autorize nas configurações do Android.")
            if (channel.turnId != null) {
                OutlinedButton(
                    onClick = { controller.cancel(); status = "Turno cancelado" },
                    modifier = Modifier.fillMaxWidth().testTag("voiceCancelTurn"),
                ) { Text("Cancelar pedido") }
            }
            if (playing) {
                OutlinedButton(
                    onClick = {
                        player.stop()
                        playing = false
                        status = "Reprodução interrompida"
                    },
                    modifier = Modifier.fillMaxWidth().testTag("voiceStopPlayback"),
                ) { Text("Parar reprodução") }
            }
            Text("Estado: " + (channel.error?.let { "Muse indisponível — $it" }
                ?: if (recording || sending || playing) status else channel.status),
                modifier = Modifier.testTag("voiceStatus"))
            Spacer(Modifier.height(4.dp))
            Text("Transcrição: " + channel.transcript, modifier = Modifier.testTag("voiceTranscript"))
            Text("Resposta: " + channel.reply, modifier = Modifier.testTag("voiceReply"))
        }
    }
}
