// Sessão do link com o Muse (passo 4): handshake Noise, stream de controle, link.register e o que
// vem depois. Port da parte de registro de LinkSession em musegadget/link_client.py do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates;
// Apache License 2.0). Mudanças: máquina de estados dirigida por eventos do WebSocket (o
// OkHttp chama callbacks, não há asyncio) e SEM comandos: o device se registra como
// comunicador, e qualquer link.invoke recebe "unsupported command" (nada é executado).
// Chat, TTS, voz e imagens do SDK ficam para os passos 5 e 6.
package com.darlisonfarich.gadgetlink.noise

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.SecureRandom
import java.util.UUID

/** Como a conexão terminou (os mesmos desfechos do SDK). */
enum class LinkOutcome {
    /** acabou; reconectar normalmente */
    CLOSED,

    /** a borda recusou o bearer da VM: buscar as VMs de novo */
    AUTH_REJECTED,

    /** autenticado, mas sem permissão agora */
    FORBIDDEN,

    /** o Muse removeu este device */
    UNPAIRED,

    /** parada pedida por quem chamou */
    STOPPED,
}

/** O lado de saída: o WebSocket de verdade (OkHttp) ou um falso nos testes. */
interface LinkSocket {
    /** @return false se o envio falhou; a sessão se encerra, porque a entrega ficou ambígua. */
    fun sendBinary(data: ByteArray): Boolean
    fun close()
}

interface LinkListener {
    fun onRegistered() {}
    fun onOutcome(outcome: LinkOutcome) {}

    /** Linhas de registro: nunca levam token, chave nem o conteúdo das mensagens. */
    fun log(line: String) {}
}

/**
 * Uma conexão. Os eventos entram por [onOpen], [onBinary], [onText], [onClosed] e
 * [onUpgradeRejected] (o OkHttp os entrega em série); o resultado sai uma vez só em
 * [LinkListener.onOutcome]. Não se reaproveita: uma conexão nova é uma sessão nova.
 */
class LinkSession(
    private val device: DeviceDescription,
    private val socket: LinkSocket,
    private val listener: LinkListener,
    private val newInitiator: () -> NoiseXxInitiator = { NoiseXxInitiator() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val chunkIds: () -> Long = SecureRandom()::nextLong,
) {
    private enum class State { NEW, AWAIT_MSG2, ACTIVE, DONE }

    private var state = State.NEW
    private var initiator: NoiseXxInitiator? = null
    private var transport: NoiseTransport? = null
    private var controlStreamId = 0L
    private var registerId = ""
    private val decoder = MessageDecoder()

    @Volatile var isRegistered = false
        private set

    companion object {
        /** O upgrade do WebSocket recusado: 401 pede VMs novas, 403 é "agora não"; outro status não é conclusivo. */
        fun outcomeForUpgradeStatus(status: Int): LinkOutcome? = when (status) {
            401 -> LinkOutcome.AUTH_REJECTED
            403 -> LinkOutcome.FORBIDDEN
            else -> null
        }
    }

    /** O WebSocket abriu: começa o handshake. */
    @Synchronized fun onOpen() {
        if (state != State.NEW) return
        val ini = newInitiator().also { initiator = it }
        state = State.AWAIT_MSG2
        try {
            sendAll(listOf(ini.writeMessage1()))
        } catch (t: Throwable) {
            fail("handshake could not start", t)
        }
    }

    @Synchronized fun onBinary(data: ByteArray) {
        when (state) {
            State.AWAIT_MSG2 -> handshake(data)
            State.ACTIVE -> receive(data)
            else -> Unit
        }
    }

    @Synchronized fun onText() {
        if (state != State.DONE) listener.log("ignoring text frame on Noise connection")
    }

    /** O WebSocket fechou ou falhou depois de aberto. */
    @Synchronized fun onClosed(reason: String = "") {
        if (state == State.DONE) return
        listener.log("control connection closed" + if (reason.isEmpty()) "" else ": $reason")
        finish(LinkOutcome.CLOSED)
    }

    /** O upgrade foi recusado com [status]. */
    @Synchronized fun onUpgradeRejected(status: Int) {
        listener.log("VM refused connection: HTTP $status")
        finish(outcomeForUpgradeStatus(status) ?: LinkOutcome.CLOSED)
    }

    @Synchronized fun stop() = finish(LinkOutcome.STOPPED)

    /** Manda uma mensagem de controle pelo stream do link (só com a sessão ativa). */
    @Synchronized fun sendControl(message: JsonObject): Boolean {
        val t = transport
        if (state != State.ACTIVE || t == null) return false
        return try {
            sendAll(t.encryptBodyChunk(controlStreamId, LinkWire.encodeMessage(message)))
        } catch (e: Throwable) {
            fail("could not send control message", e)
            false
        }
    }

    // ---- handshake e registro -----------------------------------------------------------------
    private fun handshake(data: ByteArray) {
        val ini = initiator!!
        try {
            ini.readMessage2(data)
            // O bearer já autenticou no upgrade; a mensagem 3 leva payload vazio.
            if (!sendAll(listOf(ini.writeMessage3()))) return
            val (send, recv) = ini.split()
            transport = NoiseTransport(send, recv, chunkIds)
            state = State.ACTIVE
            listener.log("Noise session established")
        } catch (t: Throwable) {
            fail("Noise handshake failed", t)
            return
        }
        openControlStream()
    }

    private fun openControlStream() {
        val t = transport!!
        try {
            val opened = t.startStreamRequest("POST", LinkWire.CONTROL_PATH)
            if (!sendAll(opened.frames)) return
            controlStreamId = opened.streamId
            registerId = newRequestId()
            val message = LinkWire.encodeMessage(device.registerMessage(registerId))
            if (message.size > LinkWire.MAX_CONTROL_MESSAGE_BYTES) {
                listener.log("link.register exceeds the VM's control message budget")
                finish(LinkOutcome.CLOSED)
                return
            }
            if (!sendAll(t.encryptBodyChunk(controlStreamId, message))) return
            listener.log("sent link.register as ${device.nodeId}")
        } catch (e: Throwable) {
            fail("could not open the control stream", e)
        }
    }

    // ---- recepção -----------------------------------------------------------------------------
    private fun receive(data: ByteArray) {
        val frame = try {
            transport!!.decryptFrame(data)
        } catch (e: Throwable) {
            fail("could not read a frame", e)
            return
        } ?: return
        if (frame.streamId != controlStreamId) return // só existe o stream de controle

        val body: ByteArray
        val ended: Boolean
        when (val value = frame.value) {
            is Reset -> {
                listener.log("control stream reset")
                finish(LinkOutcome.CLOSED)
                return
            }
            is ApplicationResponse -> {
                if (value.status !in 200..299) {
                    listener.log("/link-control refused: HTTP ${value.status}")
                    finish(if (value.status == 403) LinkOutcome.FORBIDDEN else LinkOutcome.CLOSED)
                    return
                }
                body = value.body
                ended = value.endBody
            }
            is BodyChunk -> {
                body = value.data
                ended = value.endBody
            }
            else -> return
        }
        val messages = try {
            decoder.feed(body)
        } catch (e: Throwable) {
            fail("bad control stream", e)
            return
        }
        for (message in messages) {
            val outcome = handle(message)
            if (state == State.DONE) return
            if (outcome != null) {
                finish(outcome)
                return
            }
        }
        if (ended) {
            listener.log("control stream ended by VM")
            finish(LinkOutcome.CLOSED)
        }
    }

    private fun handle(message: JsonObject): LinkOutcome? {
        val id = message["id"]
        val method = message["method"]
        if (id is JsonPrimitive && id.isString && id.content == registerId && (method == null || method is JsonNull)) {
            if (truthy(message["error"]) || message["ok"].let { it is JsonPrimitive && it.booleanOrNull == false }) {
                listener.log("link.register rejected")
                return LinkOutcome.FORBIDDEN
            }
            if (!isRegistered) {
                isRegistered = true
                listener.log("registered with the Muse")
                listener.onRegistered()
            }
            return null
        }
        val event = message["event"]
        if (event is JsonPrimitive && event.isString && (event.content == "link.unpaired" || event.content == "node.unpaired")) {
            listener.log("the Muse removed this device")
            return LinkOutcome.UNPAIRED
        }
        if (method is JsonPrimitive && method.isString && method.content == "link.invoke") refuseInvoke(message)
        return null
    }

    /** Este device não registra comandos: todo link.invoke é recusado, sem executar nada. */
    private fun refuseInvoke(message: JsonObject) {
        val invokeId = message["id"] ?: return
        if (!truthy(invokeId)) return
        val command = message["command"].let { if (truthy(it)) it else JsonPrimitive("") }
        val error = if (command is JsonPrimitive && command.isString) "unsupported command: ${command.content}" else "command must be a string"
        listener.log("invoke refused (no commands registered)")
        sendControl(JsonObject(linkedMapOf("method" to JsonPrimitive("link.result"), "id" to invokeId, "ok" to JsonPrimitive(false), "error" to JsonPrimitive(error))))
    }

    /** Verdade no sentido do Python: null, false, 0, "", [] e {} são falsos. */
    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, JsonNull -> false
        is JsonArray -> e.isNotEmpty()
        is JsonObject -> e.isNotEmpty()
        is JsonPrimitive -> when {
            e.isString -> e.content.isNotEmpty()
            e.booleanOrNull != null -> e.booleanOrNull!!
            else -> e.doubleOrNull?.let { it != 0.0 } ?: true
        }
    }

    // ---- saída e fim --------------------------------------------------------------------------
    private fun sendAll(frames: List<ByteArray>): Boolean {
        for (f in frames) {
            val ok = try {
                socket.sendBinary(f)
            } catch (e: Throwable) {
                false
            }
            if (!ok) {
                listener.log("send failed")
                finish(LinkOutcome.CLOSED)
                return false
            }
        }
        return true
    }

    /** Falha técnica (nunca com o texto da exceção, que pode trazer dado de rede): encerra como CLOSED. */
    private fun fail(what: String, t: Throwable) {
        listener.log("$what (${t.javaClass.simpleName})")
        finish(LinkOutcome.CLOSED)
    }

    private fun finish(outcome: LinkOutcome) {
        if (state == State.DONE) return
        state = State.DONE
        initiator?.destroy()
        try {
            socket.close()
        } catch (_: Throwable) {
            listener.log("could not close Noise connection")
        }
        listener.onOutcome(outcome)
    }
}
