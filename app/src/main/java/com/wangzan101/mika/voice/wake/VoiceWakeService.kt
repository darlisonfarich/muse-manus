package com.wangzan101.mika.voice.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.voice.ShortPcmRing
import com.wangzan101.mika.core.voice.WakePhraseGate
import com.wangzan101.mika.core.voice.WakePhase
import com.wangzan101.mika.core.voice.WakeStateMachine
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.stage1.ReverseWebSocketVoiceTransport
import com.wangzan101.mika.voice.stage1.VoiceChannelController
import com.wangzan101.mika.voice.stage1.VoiceStagePlayer
import com.wangzan101.mika.voice.stage1.VoiceStageRecorder
import com.wangzan101.mika.voice.stage1.VoiceStageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * User-opted-in Android microphone FGS. Offline Vosk only; the existing A2 transport
 * is used AFTER wake recognition. No activity is launched from the background.
 */
class VoiceWakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val machine = WakeStateMachine()
    private val recorder = VoiceStageRecorder()
    private lateinit var player: VoiceStagePlayer
    private lateinit var controller: VoiceChannelController
    private lateinit var settings: VoiceStageSettings
    private var worker: Job? = null
    private var ownTurnId: String? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as MikaApp
        controller = VoiceChannelController(ReverseWebSocketVoiceTransport(app.mcp.voiceHub))
        settings = VoiceStageSettings(app)
        player = VoiceStagePlayer(app)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ativação por voz Muse", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Escuta local para o comando oi Muse" },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (worker?.isActive == true) return START_NOT_STICKY

        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        machine.enable(allowed)
        if (!allowed) {
            VoiceWakeStatus.denied()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Preparando escuta offline"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Microphone foreground service refused", e)
            machine.permissionLost()
            VoiceWakeStatus.denied()
            stopSelf()
            return START_NOT_STICKY
        }
        publish("Preparando modelo offline")
        worker = scope.launch {
            try {
                val modelPath = WakeModelInstaller.prepare(this@VoiceWakeService) { received ->
                    publish("Baixando modelo: " + (received / 1_048_576L) + " MiB")
                }
                Model(modelPath.absolutePath).use { model ->
                    if (!machine.prepared()) return@use
                    while (isActive && !stopping) {
                        publish("Ouvindo 'oi Muse' no aparelho")
                        if (!listenForWake(model)) break
                        if (!machine.detected()) break
                        runTurn()
                        if (!isActive || stopping) break
                        machine.finished()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                machine.permissionLost()
                publish("Permissão do microfone revogada")
            } catch (e: Exception) {
                machine.error()
                Log.w(TAG, "Wake listening stopped", e)
                publish("Erro na escuta: " + (e.message ?: "indisponível"))
            } finally {
                if (!stopping && machine.phase == WakePhase.Listening) {
                    machine.pause()
                    publish("Escuta interrompida pelo Android")
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(detail: String) {
        VoiceWakeStatus.publish(machine.phase, detail, ownTurnId)
        if (::player.isInitialized && machine.phase != WakePhase.Off) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenForWake(model: Model): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microfone sem permissão")
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("Microfone indisponível")
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, Wav.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4_096))
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("Não foi possível iniciar o microfone")
        }
        val gate = WakePhraseGate()
        val ring = ShortPcmRing(Wav.RATE)
        val chunk = ShortArray(1_600)
        val bytes = ByteArray(chunk.size * 2)
        try {
            Recognizer(model, Wav.RATE.toFloat(), "[\"oi muse\", \"[unk]\"]").use { recognizer ->
                audio.startRecording()
                var lastRead = SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive && !stopping) {
                    val n = audio.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) throw IOException("Falha do microfone: " + n)
                    if (n == 0) {
                        if (SystemClock.elapsedRealtime() - lastRead > 3_000L)
                            throw IOException("Microfone não está produzindo áudio")
                        delay(12)
                        continue
                    }
                    lastRead = SystemClock.elapsedRealtime()
                    ring.add(chunk, n)
                    for (i in 0 until n) {
                        val s = chunk[i].toInt()
                        bytes[2 * i] = s.toByte()
                        bytes[2 * i + 1] = (s shr 8).toByte()
                    }
                    val isFinal = recognizer.acceptWaveForm(bytes, n * 2)
                    val text = if (isFinal) JSONObject(recognizer.result).optString("text")
                        else JSONObject(recognizer.partialResult).optString("partial")
                    if (gate.accept(text, isFinal, SystemClock.elapsedRealtime())) {
                        ring.clear() // A etapa seguinte fará pre-roll; aqui não enviamos o wake PCM.
                        return true
                    }
                }
            }
        } finally {
            try { audio.stop() } catch (_: IllegalStateException) {}
            audio.release()
            ring.clear()
        }
        return false
    }

    private suspend fun runTurn() {
        publish("Muse ativado — preparando pedido")
        if (!controller.state.value.ready) {
            publish("Muse desconectado — nenhum áudio enviado")
            return
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110); delay(170) }
                finally { tone.release() }
            }
            publish("Gravando pedido (até 30 s; envia ao terminar de falar)")
            // Reuse the same stage-one recorder; only the wake-triggered call has silence-stop.
            val wav = recorder.record(this, silenceStopMs = 1_300L)
            currentCoroutineContext().ensureActive()
            val turn = UUID.randomUUID().toString()
            ownTurnId = turn
            val sent = controller.send(settings.conversationId(), turn, UUID.randomUUID().toString(), wav)
            if (!sent) {
                ownTurnId = null
                publish("Canal A2 indisponível — pedido não enviado")
                return
            }
            machine.sent()
            publish("Aguardando resposta do Muse")
            val result = withTimeoutOrNull(65_000L) {
                controller.state.first {
                    it.resultId == turn || it.error != null || !it.connected
                }
            }
            if (result == null) {
                controller.cancel()
                publish("Muse não respondeu a tempo")
                return
            }
            val responseAudio = result.audio
            if (result.resultId != turn || responseAudio == null) {
                publish("Muse não entregou uma resposta válida")
                return
            }
            machine.answer()
            publish("Reproduzindo resposta")
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main.immediate) {
                player.playBytes(responseAudio) { done.complete(Unit) }
            }
            if (withTimeoutOrNull(30_000L) { done.await() } == null) {
                withContext(Dispatchers.Main.immediate) { player.stop() }
                publish("Reprodução expirou")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ownTurnId != null && controller.state.value.turnId == ownTurnId) controller.cancel()
            Log.w(TAG, "Wake voice turn failed", e)
            publish("Pedido não concluído: " + (e.message ?: "falha"))
        } finally {
            ownTurnId = null
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 40, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Muse Manus — ativação por voz")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Desligar", stop)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        recorder.stop()
        val turn = ownTurnId
        if (turn != null && controller.state.value.turnId == turn) controller.cancel()
        worker?.cancel()
        scope.cancel()
        player.stop()
        machine.disable()
        if (VoiceWakeStatus.state.value.phase !in setOf(WakePhase.Error, WakePhase.PermissionDenied, WakePhase.Paused))
            VoiceWakeStatus.publish(WakePhase.Off, "Escuta desligada")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MuseWake"
        private const val CHANNEL = "muse_voice_wake"
        private const val NOTIFICATION_ID = 4303
        private const val ACTION_START = "com.darlisonfarich.musemanus.voice.WAKE_START"
        private const val ACTION_STOP = "com.darlisonfarich.musemanus.voice.WAKE_STOP"

        /** Call only from a visible UI after runtime microphone permission was granted. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceWakeService::class.java)
                .setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceWakeService::class.java))
            VoiceWakeStatus.publish(WakePhase.Off, "Escuta desligada")
        }
    }
}
