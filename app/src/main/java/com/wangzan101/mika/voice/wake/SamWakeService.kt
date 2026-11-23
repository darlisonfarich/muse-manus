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
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.wangzan101.mika.device.ScreenWakeup
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.voice.SamSummonStateMachine
import com.wangzan101.mika.core.voice.WakePhraseGate
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/** Sam's independent local wake listener and explicit accessibility summon chain. */
class SamWakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private lateinit var settings: SamWakeSettings
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        settings = SamWakeSettings(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Chamar a Sam", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val testOnly = intent?.action == ACTION_TEST
        if (!testOnly && intent?.action != ACTION_START) return START_NOT_STICKY
        if (worker?.isActive == true) {
            if (testOnly) scope.launch { summonSam() }
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("MICROPHONE_PERMISSION", "Permissão do microfone ausente")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            startForeground(ID, notification("Preparando escuta local"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            report("FOREGROUND_START_FAILED", "Android recusou o serviço de microfone: ${e.message ?: "falha"}")
            stopSelf(); return START_NOT_STICKY
        }
        if (testOnly) { worker = scope.launch { summonSam(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }; return START_NOT_STICKY }
        worker = scope.launch {
            try {
                val modelPath = WakeModelInstaller.prepare(this@SamWakeService)
                Model(modelPath.absolutePath).use { model ->
                    while (isActive && !stopping && settings.enabled()) {
                        update("Ouvindo as frases configuradas")
                        if (listen(model)) summonSam()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { report("MICROPHONE_REVOKED", "Permissão do microfone revogada") }
            catch (e: Exception) { Log.e(TAG, "SAM_WAKE_LISTENER_FAILED", e); report("LISTENER_FAILED", "Falha na escuta: ${e.message ?: "indisponível"}") }
            finally { if (!stopping) stopSelf() }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen(model: Model): Boolean {
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("Microfone indisponível")
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); throw IOException("Não foi possível iniciar o microfone") }
        val phrases = settings.phrases().ifEmpty { SamWakeSettings.DEFAULT_PHRASES }
        val gate = WakePhraseGate(phrases)
        val samples = ShortArray(1600)
        val bytes = ByteArray(samples.size * 2)
        try {
            val grammar = JSONArray(phrases + "[unk]").toString()
            Recognizer(model, Wav.RATE.toFloat(), grammar).use { rec ->
                audio.startRecording()
                var last = SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive && !stopping && settings.enabled()) {
                    val n = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) throw IOException("MICROPHONE_READ_$n")
                    if (n == 0) { if (SystemClock.elapsedRealtime() - last > 3000) throw IOException("Microfone sem áudio"); delay(12); continue }
                    last = SystemClock.elapsedRealtime()
                    for (i in 0 until n) { val s = samples[i].toInt(); bytes[2*i] = s.toByte(); bytes[2*i+1] = (s shr 8).toByte() }
                    val final = rec.acceptWaveForm(bytes, n * 2)
                    val text = if (final) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
                    if (gate.accept(text, final, SystemClock.elapsedRealtime())) return true
                }
            }
        } finally { runCatching { audio.stop() }; audio.release() }
        return false
    }

    private suspend fun summonSam() {
        val machine = SamSummonStateMachine()
        val a11y = ScreenAccessibility.instance.value
        val root = a11y?.rootInActiveWindow
        if (root != null && isLive(root)) { machine.begin(true); update("Sam Live já está aberto"); return }
        if (!machine.begin(false)) return
        val power = getSystemService(PowerManager::class.java)
        val screenOn = runCatching { power.isInteractive }.getOrDefault(false)
        if (!screenOn && !ScreenWakeup.wake(this)) { machine.screenWoke(false); report("SCREEN_WAKE_FAILED", "Não foi possível acordar a tela"); return }
        machine.screenWoke(true)
        try {
            try { startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { machine.assistantOpened(false); report("ASSIST_LAUNCH_FAILED", "Não foi possível abrir o assistente padrão: ${e.message ?: "falha"}"); return }
            machine.assistantOpened(true)
            update("Assistente aberto; procurando botão de ligação")
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(120)
                val service = ScreenAccessibility.instance.value
                val active = service?.rootInActiveWindow
                if (active != null && isLive(active)) { machine.begin(true); update("Sam Live já está aberto"); return }
                val button = active?.let(::findCallButton)
                if (button != null) {
                    val clicked = runCatching { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                    machine.callButtonTapped(found = true, tapped = clicked)
                    if (clicked) update("Sam Live aberto") else report("CALL_BUTTON_TAP_FAILED", "Botão de ligação encontrado, mas o toque falhou")
                    return
                }
            }
            machine.callButtonTapped(found = false)
            report("CALL_BUTTON_NOT_FOUND", "Botão de ligação da Sam não apareceu em 3 segundos")
        } finally { runCatching { if (lock?.isHeld == true) lock.release() } }
    }

    private fun findCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val label = listOfNotNull(n.contentDescription?.toString(), n.text?.toString()).joinToString(" ").lowercase()
            val semantic = n.isClickable && (label.contains("call") || label.contains("ligar") || label.contains("telefone") ||
                label.contains("phone") || label.contains("chamada") || label.contains("start a conversation"))
            if (semantic) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun isLive(root: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val label = "${n.contentDescription ?: ""} ${n.text ?: ""}".lowercase()
            if (listOf("end call", "hang up", "encerrar chamada", "desligar chamada", "終わり").any(label::contains)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::add)
        }
        return false
    }

    private fun report(code: String, reason: String) {
        Log.e(TAG, "$code: $reason")
        getSystemService(NotificationManager::class.java).notify(ID + 1, notification("Falha $code: $reason", ongoing = false))
    }
    private fun update(text: String) { getSystemService(NotificationManager::class.java).notify(ID, notification(text)) }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val stop = PendingIntent.getService(this, 702, Intent(this, SamWakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 703, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Chamar a Sam")
            .setContentText(text).setOngoing(ongoing).setContentIntent(open).addAction(android.R.drawable.ic_delete, "Desligar", stop).build()
    }
    override fun onDestroy() { stopping = true; worker?.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val TAG = "SamWake"
        private const val CHANNEL = "sam_wake"
        private const val ID = 4305
        private const val ACTION_START = "com.darlisonfarich.musemanus.sam.WAKE_START"
        private const val ACTION_TEST = "com.darlisonfarich.musemanus.sam.WAKE_TEST"
        private const val ACTION_STOP = "com.darlisonfarich.musemanus.sam.WAKE_STOP"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, SamWakeService::class.java).setAction(ACTION_START)) }
        fun test(context: Context) { ContextCompat.startForegroundService(context, Intent(context, SamWakeService::class.java).setAction(ACTION_TEST)) }
        fun stop(context: Context) { context.stopService(Intent(context, SamWakeService::class.java)) }
    }
}
