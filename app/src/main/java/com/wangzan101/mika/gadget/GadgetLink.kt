package com.wangzan101.mika.gadget

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.darlisonfarich.gadgetlink.GadgetState
import com.darlisonfarich.gadgetlink.SetupEvent
import com.darlisonfarich.gadgetlink.SetupNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ponto único do gadget-link no app (docs/gadget-link.md, passo 3): permissões,
 * estado do pareamento e o que a Diagnostics mostra. O trabalho de rádio fica no
 * [GadgetLinkService]; a lógica de protocolo no módulo :gadgetlink.
 *
 * O diretório de estado tem o MESMO formato do SDK oficial (identity.json,
 * pairing.json, sdk_token), para o próprio SDK poder usá-lo depois.
 */
object GadgetLink {
    enum class Phase(val label: String) {
        OFF("off"),
        STARTING("starting"),
        ADVERTISING("waiting for the Muse app"),
        CONNECTED("phone connected"),
        HELLO("hello received, waiting for client-finished"),
        CONFIRMED("paired (confirmed), waiting for provisioning"),
        PROVISIONING("provisioning"),
        PAIRED("paired and provisioned"),
        ERROR("error"),
    }

    /** Janela de setup, como o SDK (pair.SETUP_WINDOW_S): não deixa o rádio anunciando para sempre. */
    const val SETUP_WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_EVENTS = 14

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase
    @Volatile var lastError: String? = null
        private set
    private val events = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun stateDir(context: Context) = File(context.applicationContext.filesDir, "gadgetlink/state")
    fun state(context: Context) = GadgetState(stateDir(context))

    /** Android 12+: anunciar e servir GATT são runtime ("Dispositivos próximos"). SCAN não é usado. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun missingPermissions(context: Context): List<String> = requiredPermissions().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    val running: Boolean get() = _phase.value != Phase.OFF && _phase.value != Phase.ERROR && _phase.value != Phase.PAIRED

    /** Abre o setup. Só chamar depois de conferir permissões e Bluetooth (a UI faz isso no toque). */
    fun open(context: Context) {
        val app = context.applicationContext
        app.startForegroundService(Intent(app, GadgetLinkService::class.java).setAction(GadgetLinkService.ACTION_START))
    }

    fun close(context: Context) {
        val app = context.applicationContext
        app.startService(Intent(app, GadgetLinkService::class.java).setAction(GadgetLinkService.ACTION_STOP))
    }

    // -- Estado e eventos ---------------------------------------------------------------

    internal fun setPhase(phase: Phase, note: String? = null) {
        _phase.value = phase
        if (phase == Phase.ERROR) lastError = note
        if (phase == Phase.STARTING) lastError = null
        note?.let { log("${phase.name}: $it") }
    }

    @Synchronized
    internal fun log(text: String) {
        events.addLast("${clock.format(Date())} $text")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** Evento do protocolo: anda a fase e entra no registro (sem segredos — o controller não manda nenhum). */
    internal fun onSetupEvent(event: SetupEvent, detail: String) {
        log(if (detail.isEmpty()) event.name else "${event.name} $detail")
        when (event) {
            SetupEvent.HELLO_OK -> _phase.value = Phase.HELLO
            SetupEvent.CONFIRMED -> _phase.value = Phase.CONFIRMED
            SetupEvent.PROVISION_STARTED -> _phase.value = Phase.PROVISIONING
            SetupEvent.PROVISIONED -> _phase.value = Phase.PAIRED
            SetupEvent.DECRYPT_FAILED, SetupEvent.CONFIRM_REJECTED, SetupEvent.HELLO_REJECTED,
            SetupEvent.PROVISION_FAILED, SetupEvent.PROVISION_OFFLINE,
            -> lastError = "${event.name} $detail".trim()
            SetupEvent.DISCONNECTED -> if (_phase.value != Phase.PAIRED && _phase.value != Phase.OFF) _phase.value = Phase.ADVERTISING
            else -> Unit
        }
    }

    internal fun onClientConnected() {
        if (_phase.value == Phase.ADVERTISING) _phase.value = Phase.CONNECTED
    }

    /**
     * As linhas da seção "Gadget link (spike)" da Diagnostics. Nada de token de conta:
     * do token do SDK só o prefixo que o próprio site mostra.
     */
    fun diagnosticsLines(context: Context): List<Pair<String, String>> {
        val lines = mutableListOf<Pair<String, String>>()
        val state = state(context)
        lines += "link" to (_phase.value.label + (lastError?.let { " — last error: $it" } ?: ""))
        runCatching { state.loadJson(GadgetState.IDENTITY_FILE) }.getOrNull()
            ?.let { (it["mac"] as? JsonPrimitive)?.content }
            ?.takeIf { com.darlisonfarich.gadgetlink.Identity.isValidMac(it) }
            ?.let { com.darlisonfarich.gadgetlink.Identity(it) }
            ?.let { lines += "link identity" to "${it.bleName} / ${it.nodeId}" }
            ?: run { lines += "link identity" to "not created yet (created on first open)" }
        lines += "link sdk token" to runCatching { GadgetState.sdkTokenHint(state.sdkToken()) }.getOrElse { "invalid — paste it again" }
        val pairing = runCatching { state.pairing() }.getOrNull()
        lines += "link saved pairing" to if (pairing == null) "none" else {
            val saved = (pairing["access_token_saved_at"] as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() }
            val host = (pairing["api_url_v2"] as? JsonPrimitive)?.content?.removePrefix("https://")?.ifEmpty { null } ?: "api.muse.ai"
            "yes (saved ${saved?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(it * 1000)) } ?: "?"}, $host)"
        }
        val missing = missingPermissions(context)
        lines += "link permissions" to if (missing.isEmpty()) "ok" else "missing ${missing.joinToString { it.substringAfterLast('.') }}"
        lines += "link bluetooth" to if (bluetoothOn(context)) "on" else "off"
        AdapterName.pendingRestore(context)?.let { lines += "link bt name" to "renamed for setup (will restore \"$it\")" }
        synchronized(this) { events.toList() }.forEach { lines += "link event" to it }
        return lines
    }
}

/** Rede do aparelho para o setup: o celular já está online, então só informa a "rede atual". */
class AndroidSetupNetwork(private val context: Context) : SetupNetwork {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * O SSID de verdade exige permissão de localização; o device ignora os campos de
     * Wi-Fi do provision_v2 de qualquer jeito, então mostra um nome fixo.
     */
    override fun currentConnectionEntry(): JsonObject = buildJsonObject {
        put("ssid", "Rede atual (Muse Manus)")
        put("rssi", -40)
        put("secure", true)
    }
}

/**
 * O Android só anuncia o nome do adaptador. Durante o setup o nome vira
 * MuseGadgetXXXXXX (como o SDK faz com o Alias do BlueZ) e depois volta. O nome
 * original fica gravado antes da troca, para voltar mesmo se o app morrer no meio
 * (ver [restoreIfStale], chamado ao abrir o setup e ao fechar o serviço).
 */
@SuppressLint("MissingPermission")
object AdapterName {
    private const val PREFS = "gadget_link"
    private const val KEY_ORIGINAL = "original_bt_name"
    private const val KEY_SETUP = "setup_bt_name"

    fun pendingRestore(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORIGINAL, null)

    /** Troca o nome e espera ele valer (até ~3 s). Falso se não deu; o setup segue sem nome no anúncio. */
    fun apply(context: Context, setupName: String): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val current = runCatching { adapter.name }.getOrNull() ?: return false
        if (current == setupName) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ORIGINAL, null) == null) {
            prefs.edit().putString(KEY_ORIGINAL, current).putString(KEY_SETUP, setupName).commit()
        }
        if (runCatching { adapter.setName(setupName) }.getOrDefault(false) != true) return false
        repeat(30) {
            if (runCatching { adapter.name }.getOrNull() == setupName) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Devolve o nome original, se ele foi trocado por nós e ainda não voltou. */
    fun restoreIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val original = prefs.getString(KEY_ORIGINAL, null) ?: return
        val setupName = prefs.getString(KEY_SETUP, null)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val current = runCatching { adapter.name }.getOrNull() ?: return // sem permissão: tenta na próxima
        // Só devolve se o nome ainda é o nosso; se o usuário trocou por conta própria, respeita.
        if (current == setupName) {
            if (runCatching { adapter.setName(original) }.getOrDefault(false) != true) return
        }
        prefs.edit().remove(KEY_ORIGINAL).remove(KEY_SETUP).commit()
    }
}
