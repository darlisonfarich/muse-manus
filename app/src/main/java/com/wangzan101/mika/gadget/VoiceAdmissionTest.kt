package com.wangzan101.mika.gadget

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.darlisonfarich.gadgetlink.noise.DeviceDescription
import com.darlisonfarich.gadgetlink.noise.GadgetPairingStore
import com.darlisonfarich.gadgetlink.noise.LinkClock
import com.darlisonfarich.gadgetlink.noise.LinkScheduler
import com.darlisonfarich.gadgetlink.noise.OkHttpLinkLauncher
import com.darlisonfarich.gadgetlink.noise.RealMuseApi
import com.darlisonfarich.gadgetlink.noise.X25519
import com.darlisonfarich.gadgetlink.voice.VoiceAdmissionDiagnosis
import com.darlisonfarich.gadgetlink.voice.VoiceAdmissionProbe
import com.darlisonfarich.gadgetlink.voice.VoiceAdmissionResult
import com.darlisonfarich.gadgetlink.voice.VoiceClock
import com.darlisonfarich.gadgetlink.voice.VoiceProbeListener
import com.darlisonfarich.gadgetlink.voice.VoiceStageEntry
import com.darlisonfarich.gadgetlink.voice.VoiceWire
import com.wangzan101.mika.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Passo 5.0 da voz, a partir da Diagnostics (Activity visível, sem serviço novo): roda UMA vez a
 * [VoiceAdmissionProbe] — segunda sessão Noise sem registro, app_id=hatch-web, admissão, prontidão,
 * ~2 s sem áudio, end-call — e mostra o registro por estágio. Toda a lógica está no gadgetlink (testada
 * na JVM); aqui só mora a cola com o Android. Só lê o pareamento: nunca renova nem grava token (o
 * serviço do link continua sendo o único dono deles).
 */
object VoiceAdmissionTest {
    data class Ui(val running: Boolean = false, val lines: List<String> = emptyList(), val summary: String = "")

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    @Synchronized
    fun start(context: Context) {
        if (_ui.value.running) return
        val app = context.applicationContext
        // O controle do debate: com o link registrado vivo (mesmo token, mesma VM), um 401/403 da voz
        // exclui o token — sobram app_id e admissão. Por isso o estado do link entra no resumo.
        val linkAtStart = GadgetLink.link.value?.name ?: "desligado"
        _ui.value = Ui(running = true, lines = listOf("link no início: $linkAtStart"))
        X25519.unavailableReason()?.let { return finishEarly("X25519 indisponível neste aparelho") }
        val state = GadgetLink.state(app)
        val identity = try {
            state.loadOrCreateIdentity()
        } catch (e: IOException) {
            return finishEarly("identidade ilegível (${e.javaClass.simpleName})")
        }
        val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "voice-50") }
        val scheduler = object : LinkScheduler {
            override fun schedule(delayMs: Long, task: () -> Unit) {
                runCatching {
                    exec.schedule({
                        runCatching(task).onFailure { GadgetLink.log("voz 5.0: passo falhou (${it.javaClass.simpleName})") }
                    }, delayMs, TimeUnit.MILLISECONDS)
                }
            }
        }
        val clock = object : VoiceClock, LinkClock {
            override fun monoMs(): Long = SystemClock.elapsedRealtime()
            override fun wallMs(): Long = System.currentTimeMillis()
            override fun nowMs(): Long = SystemClock.elapsedRealtime()
            override fun wallSeconds(): Long = System.currentTimeMillis() / 1000
        }
        val launcher = OkHttpLinkLauncher(
            client = OkHttpClient(),
            device = { DeviceDescription(nodeId = identity.nodeId, displayName = "${Build.MODEL} (Muse Manus)", version = BuildConfig.VERSION_NAME) },
            clock = clock,
            log = {},
            appId = VoiceWire.APP_ID,
        )
        lateinit var probe: VoiceAdmissionProbe
        probe = VoiceAdmissionProbe(
            deviceId = identity.nodeId,
            store = GadgetPairingStore(state),
            api = RealMuseApi(),
            connector = { vm, pairing, listener, onUpgrade -> launcher.open(vm, pairing, registerDevice = false, listener = listener, onUpgrade = onUpgrade) },
            scheduler = scheduler,
            clock = clock,
            listener = object : VoiceProbeListener {
                override fun onStage(entry: VoiceStageEntry) {
                    _ui.value = _ui.value.copy(lines = _ui.value.lines + entry.line())
                }

                override fun onFinished(result: VoiceAdmissionResult) {
                    val summary = VoiceAdmissionDiagnosis.explain(result, probe.entries()) + " (link no início: $linkAtStart)"
                    _ui.value = _ui.value.copy(running = false, summary = summary)
                    GadgetLink.log("voz 5.0: $summary")
                    // Dá um instante para o fechamento do WebSocket sair antes de encerrar a thread.
                    runCatching { exec.schedule({ exec.shutdown() }, 2, TimeUnit.SECONDS) }
                }
            },
        )
        probe.start()
    }

    private fun finishEarly(summary: String) {
        _ui.value = Ui(running = false, summary = summary)
    }
}
