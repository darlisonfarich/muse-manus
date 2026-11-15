// Sessão do link com o Muse (passo 4): handshake Noise, stream de controle, link.register e o que
// vem depois. Port da parte de registro de LinkSession em musegadget/link_client.py do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates;
// Apache License 2.0). Mudanças: máquina de estados dirigida por eventos do WebSocket (o
// OkHttp chama callbacks, não há asyncio) e SEM comandos: o device se registra como
// comunicador, e qualquer link.invoke recebe "unsupported command" (nada é executado).
// Chat, TTS, voz e imagens do SDK ficam para os passos 5 e 6. A voz usa esta mesma classe numa
// segunda instância com registerDevice = false (como o register_device=False do SDK): sem
// /link-control e sem link.register, só os streams abertos com openStream.
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

    /**
     * Só em sessão sem registro (`registerDevice = false`): o handshake terminou e a sessão está
     * pronta para [LinkSession.openStream]. Nada foi enviado ainda além do handshake.
     */
    fun onTransportReady() {}

    fun onOutcome(outcome: LinkOutcome) {}

    /** Linhas de registro: nunca levam token, chave nem o conteúdo das mensagens. */
    fun log(line: String) {}
}

/**
 * Quem consome um stream aberto por [LinkSession.openStream]. Os callbacks rodam na thread do
 * WebSocket, sob o monitor da sessão: não podem bloquear, e só entregam o que o Muse mandou naquele stream.
 */
interface StreamSink {
    fun onResponse(status: Int, body: ByteArray, end: Boolean) {}
    fun onBody(data: ByteArray, end: Boolean) {}
    fun onReset(code: ResetCode, reason: String) {}
}

/** O que aconteceu com um corpo mandado por [LinkSession.sendStreamBody]. */
enum class StreamSend {
    /** cifrado e entregue ao socket */
    SENT,

    /** barrado pela checagem de admissão, antes de cifrar: nada saiu e nenhum nonce foi gasto */
    GATED,

    /** a sessão não está ativa ou o envio falhou (a sessão se encerra) */
    FAILED,
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
    /**
     * Verdadeiro (a instância do link do gadget): depois do handshake abre /link-control e manda
     * link.register. Falso (a sessão da voz do SDK): não manda nada até o [openStream].
     */
    private val registerDevice: Boolean = true,
) {
    private enum class State { NEW, AWAIT_MSG2, ACTIVE, DONE }

    private var state = State.NEW
    private var initiator: NoiseXxInitiator? = null
    private var transport: NoiseTransport? = null
    private var controlStreamId = 0L
    private var registerId = ""
    private val decoder = MessageDecoder()

    /** Streams abertos por [openStream], por id. Fica vazio na instância registrada. */
    private val streams = HashMap<Long, StreamSink>()

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

    /**
     * Abre um stream POST em [path] e passa a entregar ao [sink] tudo o que o Muse mandar nele.
     * @return o id do stream, ou null se a sessão não está ativa ou o envio falhou.
     */
    @Synchronized fun openStream(path: String, headers: List<Header>, sink: StreamSink): Long? {
        val t = transport
        if (state != State.ACTIVE || t == null) return null
        return try {
            val opened = t.startStreamRequest("POST", path, headers = headers)
            streams[opened.streamId] = sink // antes de enviar: nenhuma resposta se perde
            if (sendAll(opened.frames)) {
                opened.streamId
            } else {
                streams.remove(opened.streamId)
                null
            }
        } catch (e: Throwable) {
            fail("could not open a stream", e)
            null
        }
    }

    /**
     * Manda um pedaço de corpo pelo stream [streamId]. [admissible] é consultado sob o monitor da
     * sessão, ANTES de cifrar: se disser que não, nada é cifrado, então nenhum nonce nem chunk id é
     * gasto e o quadro simplesmente some (GATED). Não chame a sessão de dentro dele.
     */
    @Synchronized fun sendStreamBody(
        streamId: Long,
        data: ByteArray,
        end: Boolean = false,
        admissible: () -> Boolean = { true },
    ): StreamSend {
        val t = transport
        if (state != State.ACTIVE || t == null) return StreamSend.FAILED
        if (!admissible()) return StreamSend.GATED
        return try {
            if (sendAll(t.encryptBodyChunk(streamId, data, endBody = end))) StreamSend.SENT else StreamSend.FAILED
        } catch (e: Throwable) {
            fail("could not send a stream body", e)
            StreamSend.FAILED
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
        if (registerDevice) openControlStream() else listener.onTransportReady()
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
        // Sem registro não há stream de controle (controlStreamId fica em 0, que não é um id de stream de verdade).
        if (!registerDevice || frame.streamId != controlStreamId) {
            streams[frame.streamId]?.let { dispatch(frame, it) } // stream que ninguém abriu: descartado
            return
        }

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

    /** Entrega o quadro ao dono do stream; fim ou reset tiram o stream da tabela. */
    private fun dispatch(frame: DecryptedFrame, sink: StreamSink) {
        when (val value = frame.value) {
            is ApplicationResponse -> {
                if (value.endBody) streams.remove(frame.streamId)
                sink.onResponse(value.status, value.body, value.endBody)
            }
            is BodyChunk -> {
                if (value.endBody) streams.remove(frame.streamId)
                sink.onBody(value.data, value.endBody)
            }
            is Reset -> {
                streams.remove(frame.streamId)
                sink.onReset(value.code, value.reason)
            }
            else -> Unit
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
