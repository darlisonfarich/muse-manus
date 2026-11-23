package com.wangzan101.mika.gadget

import android.app.Notification
import com.darlisonfarich.gadgetlink.Vm
import com.darlisonfarich.gadgetlink.noise.LinkOutcome
import com.darlisonfarich.gadgetlink.noise.PairingRecord
import com.darlisonfarich.gadgetlink.noise.SessionLauncher
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.mcp.GadgetPhoneLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.darlisonfarich.gadgetlink.GadgetState
import com.darlisonfarich.gadgetlink.commands.CommandCatalog
import com.darlisonfarich.gadgetlink.commands.CommandDispatcher
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
 * pareamento: renova o token, busca a VM, abre o WebSocket Noise e se registra como homehub anunciando o
 * catálogo do Gadget Link ([GadgetLinkCommands]: linktest.* e, só na medição, os stubs phone.*); os
 * link.invoke vão para o [CommandDispatcher], que vive o serviço inteiro (a deduplicação atravessa as
 * reconexões) e a cada conexão recebe o catálogo que aquele link.register anunciou.
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
    private var dispatcher: CommandDispatcher? = null
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controlJob: Job? = null
    private var lease: GadgetPhoneLease? = null
    private val phoneHost get() = (applicationContext as MikaApp).mcp.directHost
    private var started = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            destroyed = true
            stopControl(lease)
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
        val commands = GadgetLinkCommands.dispatcher(this, CommandCatalog(emptyList())).also { dispatcher = it }
        val displayName = "${Build.MODEL} (Muse Manus)"
        val client = OkHttpClient()
        val launcher = object : SessionLauncher {
            override fun launch(vm: Vm, pairing: PairingRecord, events: SessionLauncher.Events): SessionLauncher.Handle {
                val account = (state.pairing()?.get("username") as? JsonPrimitive)?.content.orEmpty()
                val registration = GadgetPhoneLease(identity.deviceId, pairing.noiseHost, account, vm.vmId.ifBlank { vm.vmUrl })
                val catalog = GadgetLinkCommands.catalog(this@GadgetConnectService, registration)
                commands.catalog = catalog
                val bytes = CommandCatalog.registerBytes(catalog, identity.nodeId, displayName, BuildConfig.VERSION_NAME)
                GadgetLink.onAnnounced(GadgetLink.Announced(catalog.announced.size, catalog.announcedStubs, bytes))
                GadgetLink.log("link: commands_v2 ${catalog.announced.size} commands (${catalog.announcedStubs} stubs), link.register $bytes bytes")
                val device = DeviceDescription(identity.nodeId, displayName, BuildConfig.VERSION_NAME, catalog.commandsV2())
                return OkHttpLinkLauncher(client, { device }, clock, log = { GadgetLink.log("link: $it") }, invokes = commands)
                    .launch(vm, pairing, object : SessionLauncher.Events {
                        override fun onRegistered() {
                            startControl(registration)
                            events.onRegistered()
                        }

                        override fun onEnded(outcome: LinkOutcome, lastedMs: Long) {
                            stopControl(registration)
                            events.onEnded(outcome, lastedMs)
                        }
                    })
            }
        }
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

    @Synchronized private fun startControl(registration: GadgetPhoneLease) {
        if (destroyed || lease === registration) return
        stopControl(lease)
        lease = registration
        registration.connected = true
        controlJob = controlScope.launch {
            val phone = PhoneServices.of(this@GadgetConnectService)
            var lastStatus: String? = null
            var lastHelper: String? = null
            var lastOff: com.wangzan101.mika.core.shell.AutoOffOutcome? = phone.shellManager.wirelessAutoOn.lastOff.value
            try {
                while (isActive) {
                    val ready = try {
                        phoneHost.maintainGadget(registration)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                    // The helper is an optional accelerator: the session is built without it, so a missing helper
                    // is never the reason the session is not ready. It is only noted on its own log line.
                    if (phone.hub.shell.value == null) {
                        // The watchdog relaunches the helper by itself; ask it once per registration
                        // (it keeps its own backoff) and otherwise only name what it waits on.
                        if (lastStatus == null) phone.shellManager.need()
                        val helper = GadgetHelperDiagnosis.helperStatus(
                            phone.shellManager.engine.status.value, phone.hub.lastHandoffRejection,
                            developerOptions = runCatching {
                                com.wangzan101.mika.cap.phone.setup.DeveloperSettings.developerOptions(contentResolver)
                            }.getOrDefault(true),
                        )
                        if (helper != lastHelper) GadgetLink.log("link: helper (optional) $helper")
                        lastHelper = helper
                    } else lastHelper = null
                    val status = when {
                        ready -> "ready"
                        !(applicationContext as MikaApp).mcp.settings.enabled.value -> "remote_access_disabled"
                        com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.instance.value == null -> "accessibility_off"
                        else -> "awaiting_slot"
                    }
                    val off = phone.shellManager.wirelessAutoOn.lastOff.value
                    if (off != lastOff && off != null) GadgetLink.log("link: wireless debugging after helper launch: ${off.name}")
                    lastOff = off
                    if (status != lastStatus) GadgetLink.log("link: phone session $status")
                    lastStatus = status
                    delay(if (ready) 5_000L else 1_000L)
                }
            } finally {
                withContext(NonCancellable) { phoneHost.releaseGadget(registration) }
            }
        }
    }

    @Synchronized private fun stopControl(registration: GadgetPhoneLease?) {
        if (registration == null || lease !== registration) return
        registration.connected = false
        controlJob?.cancel()
        controlJob = null
        lease = null
    }

    override fun onDestroy() {
        destroyed = true
        stopControl(lease)
        controlScope.cancel()
        supervisor?.stop()
        // O stop() devolve o desfecho pelo agendador; dá um instante para ele rodar antes de fechar a thread.
        executor?.let { exec -> runCatching { exec.schedule({ exec.shutdownNow() }, 3, TimeUnit.SECONDS) } }
        dispatcher?.close()
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

internal object GadgetHelperDiagnosis {
    /** Diagnostics label for a missing helper; fixed vocabulary, no addresses or error text. */
    fun helperStatus(
        status: com.wangzan101.mika.core.shell.BuiltinShellStatus, rejection: String?, developerOptions: Boolean = true,
    ): String = when {
        rejection == "StaleApk" && status == com.wangzan101.mika.core.shell.BuiltinShellStatus.Connecting -> "helper_upgrading"
        !developerOptions -> "helper_needs_developer_options"
        status is com.wangzan101.mika.core.shell.BuiltinShellStatus.Failed -> when (status.reason) {
            com.wangzan101.mika.core.shell.BuiltinShellFailure.NoWifi -> "helper_needs_wifi"
            com.wangzan101.mika.core.shell.BuiltinShellFailure.WifiNotAllowed -> "helper_wifi_not_allowed"
            com.wangzan101.mika.core.shell.BuiltinShellFailure.AuthorizationRevoked,
            com.wangzan101.mika.core.shell.BuiltinShellFailure.KeyUnreadable -> "helper_needs_pairing"
            else -> if (rejection == "StaleApk") "stale_helper" else "helper_unavailable"
        }
        status == com.wangzan101.mika.core.shell.BuiltinShellStatus.NotPaired -> "helper_not_set_up"
        status == com.wangzan101.mika.core.shell.BuiltinShellStatus.WirelessDebuggingOff -> "helper_needs_wireless_debugging"
        rejection == "StaleApk" -> "helper_upgrading"
        status == com.wangzan101.mika.core.shell.BuiltinShellStatus.Connecting -> "helper_starting"
        else -> "helper_unavailable"
    }
}
