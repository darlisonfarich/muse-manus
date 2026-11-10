package com.wangzan101.mika.gadget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.darlisonfarich.gadgetlink.PairingSession
import com.darlisonfarich.gadgetlink.Provisioner
import com.darlisonfarich.gadgetlink.SetupController
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano (connectedDevice) que segura o setup BLE enquanto o
 * usuário mexe no app Muse em OUTRO celular — com a tela do POCO apagada o HyperOS
 * mataria um anúncio de app em segundo plano. Fecha sozinho depois de parear ou
 * quando a janela de setup ([GadgetLink.SETUP_WINDOW_MS]) acaba.
 */
class GadgetLinkService : Service() {
    companion object {
        const val ACTION_START = "com.wangzan101.mika.gadget.START"
        const val ACTION_STOP = "com.wangzan101.mika.gadget.STOP"
        private const val CHANNEL = "gadget_link"
        private const val NOTIFICATION_ID = 0x6d75 // "mu"
        private const val SHUTDOWN_AFTER_PAIRED_MS = 1_500L
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "gadget-link") }
    private var transport: GattTransport? = null
    private var controller: SetupController? = null
    private var window: ScheduledFuture<*>? = null
    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService exige entrar em primeiro plano já; se o sistema recusar, sai sem derrubar o app.
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification("Abrindo o pareamento…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification("Abrindo o pareamento…"))
            }
        }
        if (foreground.isFailure) {
            GadgetLink.setPhase(GadgetLink.Phase.ERROR, "foreground refused: ${foreground.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            GadgetLink.setPhase(GadgetLink.Phase.STARTING, "opening setup")
            worker.execute { openSetup() }
        }
        return START_NOT_STICKY
    }

    private fun openSetup() {
        val missing = GadgetLink.missingPermissions(this)
        if (missing.isNotEmpty()) return fail("missing permission ${missing.joinToString { it.substringAfterLast('.') }}")
        if (!GadgetLink.bluetoothOn(this)) return fail("bluetooth is off")
        try {
            // Se uma sessão anterior morreu no meio, devolve o nome antes de trocar de novo.
            AdapterName.restoreIfStale(this)
            val state = GadgetLink.state(this)
            val identity = state.loadOrCreateIdentity()
            val sdkToken = runCatching { state.sdkToken() }.getOrElse {
                GadgetLink.log("sdk token invalid; pairing without it")
                null
            }
            if (sdkToken == null) GadgetLink.log("no sdk token: pairs now, but gadgets without one may stop pairing")
            val pairing = PairingSession(
                nodeId = identity.nodeId,
                deviceId = identity.deviceId,
                mac = identity.mac,
                firmwareVersion = BuildConfig.VERSION_NAME,
                sdkToken = sdkToken,
            )
            if (!AdapterName.apply(this, identity.bleName)) GadgetLink.log("could not rename adapter; advertising by service UUID only")

            lateinit var setup: SetupController
            val gatt = GattTransport(
                context = this,
                bleName = identity.bleName,
                onWrite = { packet ->
                    GadgetLink.onClientConnected()
                    setup.onWrite(packet)
                },
                onDisconnect = { setup.onDisconnect() },
                log = GadgetLink::log,
                onReopenFailed = { reason -> worker.execute { fail("setup lost after forced disconnect: $reason") } },
            )
            setup = SetupController(
                pairing = pairing,
                identity = identity,
                version = BuildConfig.VERSION_NAME,
                transport = gatt,
                network = AndroidSetupNetwork(this),
                provision = Provisioner(state, sdkToken)::provision,
                onComplete = { worker.schedule({ stopSelf() }, SHUTDOWN_AFTER_PAIRED_MS, TimeUnit.MILLISECONDS) },
                onEvent = GadgetLink::onSetupEvent,
            )
            transport = gatt
            controller = setup
            setup.start()
            gatt.start()?.let { return fail(it) }
            GadgetLink.setPhase(GadgetLink.Phase.ADVERTISING, "open as ${identity.bleName} for ${GadgetLink.SETUP_WINDOW_MS / 60_000} min")
            updateNotification("Aberto como ${identity.bleName}. Escolha esse nome no app Muse do outro celular.")
            window = worker.schedule({
                GadgetLink.log("setup window closed")
                stopSelf()
            }, GadgetLink.SETUP_WINDOW_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(reason: String) {
        GadgetLink.setPhase(GadgetLink.Phase.ERROR, reason)
        stopSelf()
    }

    override fun onDestroy() {
        window?.cancel(false)
        controller?.stop()
        transport?.stop()
        runCatching { AdapterName.restoreIfStale(this) }
        if (GadgetLink.phase.value != GadgetLink.Phase.PAIRED && GadgetLink.phase.value != GadgetLink.Phase.ERROR) {
            GadgetLink.setPhase(GadgetLink.Phase.OFF, "setup closed")
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pareamento Muse Gadget", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pareamento Muse Gadget")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }
}
