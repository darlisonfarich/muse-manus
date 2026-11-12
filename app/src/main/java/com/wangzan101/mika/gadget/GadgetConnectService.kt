package com.wangzan101.mika.gadget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.darlisonfarich.gadgetlink.GadgetState
import com.darlisonfarich.gadgetlink.noise.DeviceDescription
import com.darlisonfarich.gadgetlink.noise.GadgetPairingStore
import com.darlisonfarich.gadgetlink.noise.LinkClock
import com.darlisonfarich.gadgetlink.noise.LinkScheduler
import com.darlisonfarich.gadgetlink.noise.LinkSupervisor
import com.darlisonfarich.gadgetlink.noise.OkHttpLinkLauncher
import com.darlisonfarich.gadgetlink.noise.RealMuseApi
import com.darlisonfarich.gadgetlink.noise.SupervisorListener
import com.darlisonfarich.gadgetlink.noise.SupervisorState
import com.darlisonfarich.gadgetlink.noise.X25519
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano que mantém o celular registrado no Muse (passo 4 do gadget-link) depois do
 * pareamento: renova o token, busca a VM, abre o WebSocket Noise e se registra como homehub SEM comandos.
 * Só roda enquanto o usuário deixa ligado ("Conectar ao Muse" na Diagnostics); toda a lógica de decisão
 * está no [LinkSupervisor] (testado na JVM), aqui só mora a cola com o Android.
 */
class GadgetConnectService : Service() {
    companion object {
        const val ACTION_START = "com.wangzan101.mika.gadget.CONNECT_START"
        const val ACTION_STOP = "com.wangzan101.mika.gadget.CONNECT_STOP"
        private const val CHANNEL = "gadget_link"
        private const val NOTIFICATION_ID = 0x6d76
    }

    private var executor: ScheduledExecutorService? = null
    private var supervisor: LinkSupervisor? = null
    private var started = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification("Conectando ao Muse…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification("Conectando ao Muse…"))
            }
        }
        if (foreground.isFailure) {
            GadgetLink.setLinkError("foreground refused: ${foreground.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        GadgetLink.setLinkError(null)
        // O X25519 do provedor devia existir a partir do Android 13+; se não existir mesmo em Android
        // mais novo, a causa é outra — por isso o diagnóstico (2026-11-12, Claude) mostra a exceção real
        // em vez de presumir "versão antiga" sem checar.
        val x25519Problem = X25519.unavailableReason()
        if (x25519Problem != null) {
            GadgetLink.setLinkError("X25519 unavailable: $x25519Problem")
            stopSelf()
            return
        }
        val state = GadgetLink.state(this)
        val identity = try {
            state.loadOrCreateIdentity()
        } catch (e: IOException) {
            GadgetLink.setLinkError("identity unreadable (${e.javaClass.simpleName})")
            stopSelf()
            return
        }
        val sdkToken = runCatching { state.sdkToken() }.getOrNull()
        val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "gadget-link-net") }.also { executor = it }
        val scheduler = object : LinkScheduler {
            override fun schedule(delayMs: Long, task: () -> Unit) {
                // Depois do shutdown o agendamento é recusado; nesse ponto já não há o que fazer.
                runCatching {
                    exec.schedule({
                        runCatching(task).onFailure { GadgetLink.log("link: step failed (${it.javaClass.simpleName})") }
                    }, delayMs, TimeUnit.MILLISECONDS)
                }
            }
        }
        val clock = object : LinkClock {
            override fun nowMs(): Long = SystemClock.elapsedRealtime()
            override fun wallSeconds(): Long = System.currentTimeMillis() / 1000
        }
        val launcher = OkHttpLinkLauncher(
            client = OkHttpClient(),
            // O nome que o Muse mostra é só o modelo do aparelho: nada de nome do dono nem de conta.
            device = { DeviceDescription(nodeId = identity.nodeId, displayName = "${Build.MODEL} (Muse Manus)", version = BuildConfig.VERSION_NAME) },
            clock = clock,
            log = { GadgetLink.log("link: $it") },
        )
        val sup = LinkSupervisor(
            deviceId = identity.nodeId,
            sdkToken = sdkToken,
            sdkTokenHint = GadgetState.sdkTokenHint(sdkToken),
            store = GadgetPairingStore(state),
            api = RealMuseApi(),
            launcher = launcher,
            scheduler = scheduler,
            clock = clock,
            listener = object : SupervisorListener {
                override fun onState(state: SupervisorState, detail: String) {
                    GadgetLink.onLinkState(state, detail)
                    updateNotification(GadgetLink.linkLabel(state, detail))
                }

                override fun log(line: String) = GadgetLink.log("link: $line")
            },
        )
        supervisor = sup
        sup.start()
        // onDestroy pode ter chegado antes do supervisor existir: quem o criou o para.
        if (destroyed) sup.stop()
    }

    override fun onDestroy() {
        destroyed = true
        supervisor?.stop()
        // O stop() devolve o desfecho pelo agendador; dá um instante para ele rodar antes de fechar a thread.
        executor?.let { exec -> runCatching { exec.schedule({ exec.shutdownNow() }, 3, TimeUnit.SECONDS) } }
        GadgetLink.onLinkState(SupervisorState.STOPPED, "")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pareamento Muse Gadget", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Conexão com o Muse")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(label: String) {
        if (destroyed) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Muse: $label")) }
    }
}
