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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.voice.stage1.VoiceGateway
import com.wangzan101.mika.voice.stage1.VoiceStagePlayer
import com.wangzan101.mika.voice.stage1.VoiceStageRecorder
import com.wangzan101.mika.voice.stage1.VoiceStageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Stage 1 manually triggered voice circuit. Intentionally does not call accessibility,
 * the virtual display, the phone-control host or any existing speech service.
 */
@Composable
fun VoiceModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { VoiceStageSettings(context.applicationContext) }
    val recorder = remember { VoiceStageRecorder() }
    val player = remember(context) { VoiceStagePlayer(context.applicationContext) }
    val gateway = remember { VoiceGateway() }
    var url by remember { mutableStateOf(VoiceStageSettings.DEFAULT_GATEWAY_URL) }
    var status by remember { mutableStateOf("Pronto para gravar") }
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        try { url = settings.gatewayUrl() } catch (_: Exception) { status = "Não foi possível carregar a configuração" }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            job?.cancel()
            player.stop()
        }
    }

    val startRecording: () -> Unit = {
        player.stop()
        playing = false
        transcript = ""
        reply = ""
        recording = true
        status = "Gravando — toque novamente para enviar (máx. 30 s)"
        job = scope.launch {
            try {
                val wav = recorder.record(context)
                recording = false
                sending = true
                status = "Enviando áudio ao Muse..."
                settings.saveGatewayUrl(url)
                val conversationId = settings.conversationId()
                val turnId = UUID.randomUUID().toString()
                val result = gateway.send(url, wav, conversationId, turnId)
                transcript = result.transcript
                reply = result.replyText
                status = if (result.fallback) "Resposta automática de teste — Muse fora do circuito"
                    else "Transcrição recebida • reproduzindo resposta"
                sending = false
                playing = true
                player.play(result.audioBase64) {
                    playing = false
                    status = if (result.fallback) "Resposta automática de teste" else "Resposta concluída"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recording = false
                sending = false
                playing = false
                status = "Muse indisponível — falha na gravação, no gateway ou no áudio"
            } finally {
                recording = false
                sending = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) startRecording() else status = "Permissão de microfone necessária para gravar"
    }

    PageScaffold("Modo voz", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Etapa 1 — conversa por botão. Sem escuta permanente.")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL HTTPS do gateway Muse") },
                modifier = Modifier.fillMaxWidth().testTag("voiceGatewayUrl"),
                singleLine = true,
                enabled = !recording && !sending,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            settings.saveGatewayUrl(url)
                            status = "Gateway salvo"
                        } catch (_: Exception) { status = "Falha ao salvar o gateway" }
                    }
                },
                enabled = !recording && !sending,
            ) { Text("Salvar endereço") }

            Button(
                onClick = {
                    if (recording) recorder.stop()
                    else if (!sending) {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().height(76.dp).testTag("voiceRecordButton"),
            ) { Text(if (recording) "Parar e enviar" else "Gravar pedido") }

            if (permissionDenied) Text("Sem permissão, não é possível gravar. Autorize o microfone nas configurações do Android.")
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
            Text("Estado: " + status, modifier = Modifier.testTag("voiceStatus"))
            Spacer(Modifier.height(4.dp))
            Text("Transcrição: " + transcript, modifier = Modifier.testTag("voiceTranscript"))
            Text("Resposta: " + reply, modifier = Modifier.testTag("voiceReply"))
        }
    }
}
