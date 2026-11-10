// Os comandos de setup BLE: a lógica de protocolo por trás das características GATT.
// Port de musegadget/ble_setup.py do facebookincubator/muse-gadget-sdk (Copyright (c)
// Meta Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin;
// eventos para o diagnóstico; exceção inesperada no provisionamento vira
// error_storage para o app e solta a trava (no Python ela some na thread).
package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue

/** Transporte que entrega writes remontados e manda notificações em pedaços. */
interface SetupTransport {
    /** Notifica cada pacote em ordem; bloqueia até todos estarem na fila. */
    fun sendPackets(packets: List<ByteArray>)

    fun mtu(): Int

    fun disconnect(delaySeconds: Double)
}

interface SetupNetwork {
    fun isOnline(): Boolean

    fun currentConnectionEntry(): JsonObject
}

data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
)

/** O setup não pôde terminar; [status] é o que o app recebe. */
class ProvisionFailed(val status: String) : Exception(status)

/** Roda `save` sob a trava do pareamento se a sessão ainda vale; devolve se gravou. */
typealias Commit = (save: () -> Boolean) -> Boolean

/** O que aconteceu no setup, para o diagnóstico (nunca leva segredo). */
enum class SetupEvent {
    DEVICE_INFO, HELLO_OK, HELLO_REJECTED, CONFIRMED, CONFIRM_REJECTED, DECRYPT_FAILED, WIFI_SCAN,
    PROVISION_STARTED, PROVISION_OFFLINE, PROVISIONED, PROVISION_FAILED, DISCONNECTED, INVALID_COMMAND, ERROR,
}

/**
 * Atende um cliente de setup BLE por vez. As mensagens são tratadas uma a uma numa
 * thread própria, na ordem de chegada, e todo envio passa por uma trava só para os
 * contadores dos registros cifrados chegarem ao celular em ordem.
 *
 * [provision] roda numa thread própria com as credenciais recebidas; tem que
 * conferi-las e gravá-las pelo [Commit], lançando [ProvisionFailed] se falhar.
 */
class SetupController(
    private val pairing: PairingSession,
    private val identity: Identity,
    private val version: String,
    private val transport: SetupTransport,
    private val network: SetupNetwork,
    private val provision: (Credentials, Commit) -> Unit,
    private val onComplete: () -> Unit = {},
    private val onEvent: (SetupEvent, String) -> Unit = { _, _ -> },
) {
    companion object {
        val SENSITIVE_ACTIONS = setOf("provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth")
        val PLAINTEXT_STATUSES = setOf(
            "error_encryption_required", "error_pairing_invalid_hello", "error_pairing_unavailable", "error_pairing_decrypt",
        )
        const val DISCONNECT_AFTER_ERROR_S = 0.3
        const val DISCONNECT_AFTER_PROVISION_FAILURE_S = 0.5
        private val STOP = Any()
    }

    private val assembler = ChunkAssembler()
    private val inbox = LinkedBlockingQueue<Any>()
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var provisioning = false

    /** Há um provisionamento rodando (a próxima tentativa recebe error_operation_in_progress). */
    val provisioningInFlight: Boolean get() = synchronized(stateLock) { provisioning }

    // -- Callbacks do transporte (qualquer thread) --------------------------------------

    fun onWrite(packet: ByteArray) {
        assembler.feed(packet)?.let { inbox.put(it) }
    }

    fun onDisconnect() {
        assembler.reset()
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
        emit(SetupEvent.DISCONNECTED, "client disconnected; session cleared")
    }

    // -- Ciclo de vida -------------------------------------------------------------------

    fun start() {
        Thread({ run() }, "ble-setup").apply { isDaemon = true }.start()
    }

    fun stop() {
        inbox.put(STOP)
    }

    private fun run() {
        while (true) {
            val message = inbox.take()
            if (message === STOP) return
            try {
                handleMessage(message as ByteArray)
            } catch (e: Exception) {
                // Uma falha (rádio, provedor) não pode matar o atendimento das próximas mensagens.
                emit(SetupEvent.ERROR, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // -- Despacho ------------------------------------------------------------------------

    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val command: JsonElement = try {
            kotlinx.serialization.json.Json.parseToJsonElement(Utf8.decodeStrict(raw))
        } catch (e: Exception) {
            invalidCommand(raw.size)
            return
        } catch (e: StackOverflowError) {
            invalidCommand(raw.size)
            return
        }
        if (command !is JsonObject) {
            invalidCommand(raw.size)
            return
        }
        val action = command.string("action") ?: ""
        val blocked = synchronized(stateLock) { plaintextBlocked }

        when {
            !decrypted && action == "pairing_client_hello" -> handleHello(command)
            !decrypted && action == "pairing_encrypted" -> handleRecord(command)
            // Metadado público, pode ir em texto puro a qualquer momento; o app relê ao reiniciar o handshake.
            action == "get_device_info" -> {
                emit(SetupEvent.DEVICE_INFO, "")
                sendJson(deviceInfo())
            }
            !decrypted && blocked -> Unit // texto puro ignorado depois que o pareamento começou
            !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
            decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> handleWifiScan()
            decrypted && action == "provision_v2" -> handleProvision(command)
            else -> sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): JsonObject = buildJsonObject {
        put("type", "device_info")
        put("node_id", identity.nodeId)
        put("version", version)
        pairing.deviceInfo().forEach { (key, value) -> put(key, value) }
        put("build_sha", "")
        put("network_ready", network.isOnline())
    }

    private fun invalidCommand(size: Int) {
        emit(SetupEvent.INVALID_COMMAND, "$size bytes")
        sendStatus("error_invalid_command")
    }

    private fun handleHello(command: JsonObject) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingException) {
            emit(SetupEvent.HELLO_REJECTED, e.status + (e.cause?.message?.let { " ($it)" } ?: ""))
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        emit(SetupEvent.HELLO_OK, "session ${ready.string("session_id")?.take(6)}…")
        sendJson(ready)
    }

    private fun handleRecord(envelope: JsonObject) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingException) {
            emit(SetupEvent.DECRYPT_FAILED, e.cause?.message ?: e.status)
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_S)
            return
        }
        handleMessage(plaintext.toByteArray(Charsets.UTF_8), decrypted = true)
    }

    private fun handleClientFinished(command: JsonObject) {
        val generation = pairing.handleClientFinished(command)
        if (generation == 0L) {
            emit(SetupEvent.CONFIRM_REJECTED, "client-finished was not the first record")
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_S)
            return
        }
        emit(SetupEvent.CONFIRMED, "pairing confirmed (app consent)")
        sendStatus("pairing_confirmed", generation)
    }

    private fun handleWifiScan() {
        val online = network.isOnline()
        emit(SetupEvent.WIFI_SCAN, if (online) "online" else "offline")
        sendEncryptedJson(buildJsonObject {
            put("type", "wifi_scan_result")
            put("networks", JsonArray(if (online) listOf(network.currentConnectionEntry()) else emptyList()))
        })
    }

    private fun handleProvision(command: JsonObject) {
        fun text(key: String) = command.string(key) ?: ""
        if (command.string("ssid") == null ||
            command.string("password") == null ||
            text("access_token").isEmpty() ||
            text("refresh_token").isEmpty() ||
            text("token_type") != "device"
        ) {
            sendStatus("error_missing_credentials")
            return
        }
        var generation = 0L
        val refusal: String?
        synchronized(stateLock) {
            if (provisioning) {
                refusal = "error_operation_in_progress"
            } else {
                generation = pairing.markProvisioning()
                refusal = if (generation != 0L) null else "error_pairing_confirm_required"
                provisioning = generation != 0L
            }
        }
        // Enviado fora da trava: sendStatus() pega stateLock quando a sessão não tem chaves.
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // Os campos de Wi-Fi ficam de fora de propósito: este device só configura já estando online.
        val credentials = Credentials(
            accessToken = text("access_token"),
            refreshToken = text("refresh_token"),
            username = text("username"),
            apiUrl = text("api_url"),
            apiUrlV2 = text("api_url_v2"),
            noiseHost = text("noise_host"),
        )
        val gen = generation
        Thread({ runProvision(credentials, gen) }, "provision").apply { isDaemon = true }.start()
    }

    private fun runProvision(credentials: Credentials, generation: Long) {
        try {
            sendStatus("wifi_connecting", generation)
            if (!network.isOnline()) {
                // Continua em provisionamento para o app tentar de novo, como o firmware faz.
                pairing.extendProvisioning(generation)
                emit(SetupEvent.PROVISION_OFFLINE, "device offline")
                sendStatus("wifi_failed", generation)
                return
            }
            sendStatus("wifi_connected", generation)
            emit(SetupEvent.PROVISION_STARTED, "checking device token")
            try {
                provision(credentials) { save -> pairing.commitProvisioning(generation, save) }
            } catch (e: ProvisionFailed) {
                emit(SetupEvent.PROVISION_FAILED, e.status)
                sendStatus(e.status, generation)
                transport.disconnect(DISCONNECT_AFTER_PROVISION_FAILURE_S)
                return
            } catch (e: Exception) {
                emit(SetupEvent.PROVISION_FAILED, "error_storage (${e.javaClass.simpleName})")
                sendStatus("error_storage", generation)
                transport.disconnect(DISCONNECT_AFTER_PROVISION_FAILURE_S)
                return
            }
            sendStatus("auth_ok", generation)
            emit(SetupEvent.PROVISIONED, "setup complete")
            onComplete()
        } catch (e: Exception) {
            emit(SetupEvent.ERROR, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            synchronized(stateLock) { provisioning = false }
        }
    }

    // -- Envio ---------------------------------------------------------------------------

    fun sendStatus(status: String, generation: Long = 0L) {
        synchronized(txLock) {
            val envelope = pairing.encryptStatus(status, generation)
            if (envelope != null) {
                sendLocked(envelope.toString())
                return
            }
            val blocked = synchronized(stateLock) { plaintextBlocked }
            if (generation != 0L || blocked || status !in PLAINTEXT_STATUSES) return
            transport.sendPackets(listOf(status.toByteArray(Charsets.UTF_8)))
        }
    }

    fun sendJson(obj: JsonObject) {
        synchronized(txLock) { sendLocked(obj.toString()) }
    }

    fun sendEncryptedJson(obj: JsonObject, generation: Long = 0L) {
        synchronized(txLock) {
            val envelope = pairing.encryptJson(obj.toString(), generation) ?: return
            sendLocked(envelope.toString())
        }
    }

    private fun sendLocked(text: String) {
        transport.sendPackets(BleFraming.encodeChunks(text.toByteArray(Charsets.UTF_8), transport.mtu()))
    }

    private fun emit(event: SetupEvent, detail: String) {
        runCatching { onEvent(event, detail) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
