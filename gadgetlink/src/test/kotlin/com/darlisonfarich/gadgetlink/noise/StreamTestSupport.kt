package com.darlisonfarich.gadgetlink.noise

import java.security.MessageDigest

/**
 * Apoio dos testes de [LinkSession] que olham para QUALQUER stream, não só o de controle. Fica num
 * arquivo à parte, e só usa a API que já existia, para não tocar em LinkSessionTest.kt: aquele
 * arquivo é a especificação do comportamento da instância registrada e continua byte a byte igual.
 */

/**
 * Chaves X25519 fixas (uma por chamada, na ordem em que o handshake as pede: efêmera, depois a
 * estática). Com elas, com os chunk ids fixos e com o id do link.register fixo, a sessão inteira
 * é determinística: os mesmos bytes saem do mesmo código, sempre.
 */
internal fun fixedKeys(vararg fill: Int): () -> X25519KeyPair {
    val queue = ArrayDeque(fill.toList())
    return {
        val f = queue.removeFirst()
        X25519.fromPrivate(ByteArray(32) { f.toByte() })
    }
}

/** SHA-256 de uma lista de quadros, cada um com o tamanho na frente (para não confundir fronteiras). */
internal fun digestOf(frames: List<ByteArray>): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (f in frames) {
        md.update(byteArrayOf(f.size.toByte(), (f.size shr 8).toByte(), (f.size shr 16).toByte(), (f.size shr 24).toByte()))
        md.update(f)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Um "Muse" falso que fala o protocolo de verdade e enxerga todos os streams: responde o
 * handshake, decifra (na ordem, com o nonce implícito) tudo o que a sessão manda, e injeta
 * respostas em qualquer stream. Se a sessão deixasse um buraco no nonce, a decifragem aqui
 * falharia: é isso que torna a prova de "quadro barrado não consome nonce" independente do código.
 */
internal class StreamMuse(newKeyPair: () -> X25519KeyPair = X25519::generate) {
    private val responder = TestNoiseResponder("muse-hello".toByteArray(), newKeyPair)
    private var step = 0
    private lateinit var send: CipherState
    private lateinit var recv: CipherState
    private val frames = NoiseFrameDecoder()

    /** O que a sessão mandou depois do handshake, já decifrado, na ordem de chegada. */
    sealed interface Seen {
        val streamId: Long
    }

    class Opened(override val streamId: Long, val service: ServiceType, val request: ApplicationRequest) : Seen
    class Body(override val streamId: Long, val data: ByteArray, val end: Boolean) : Seen
    class Cancelled(override val streamId: Long, val reset: Reset) : Seen

    val seen = ArrayList<Seen>()

    /** Todo binário que a sessão mandou pelo socket, cifrado como saiu (handshake incluído). */
    val sentByClient = ArrayList<ByteArray>()

    /** Respostas do Muse à espera de serem entregues à sessão. */
    val outbox = ArrayDeque<ByteArray>()
    var closed = false

    val socket = object : LinkSocket {
        override fun sendBinary(data: ByteArray): Boolean {
            sentByClient += data
            receive(data)
            return true
        }

        override fun close() {
            closed = true
        }
    }

    private fun receive(data: ByteArray) {
        when (step) {
            0 -> { outbox += responder.readMessage1AndWriteMessage2(data); step = 1 }
            1 -> {
                responder.readMessage3(data)
                val (s, r) = responder.split()
                send = s
                recv = r
                step = 2
            }
            else -> {
                val message = frames.decode(recv.decryptWithAd(ByteArray(0), data)) ?: return
                val service = Envelope.decodeServiceRequest(message)
                val frame = Envelope.decodeServiceFrame(service.payload)
                when (val v = frame.value) {
                    is ApplicationRequest -> {
                        seen += Opened(frame.streamId, service.service, v)
                        if (v.body.isNotEmpty() || v.endBody) seen += Body(frame.streamId, v.body, v.endBody)
                    }
                    is BodyChunk -> seen += Body(frame.streamId, v.data, v.endBody)
                    is Reset -> seen += Cancelled(frame.streamId, v)
                    else -> Unit
                }
            }
        }
    }

    fun opened(): List<Opened> = seen.filterIsInstance<Opened>()

    /** O corpo que o Muse recebeu em [streamId], na ordem, emendado. */
    fun bodyOf(streamId: Long): ByteArray =
        seen.filterIsInstance<Body>().filter { it.streamId == streamId }.fold(ByteArray(0)) { acc, b -> acc + b.data }

    // ---- o que o Muse manda ----
    private var chunk = 5000L

    private fun emit(frame: ServiceFrame) {
        val envelope = Envelope.encodeServiceResponse(ServiceResponse(Envelope.encodeServiceFrame(frame)))
        NoiseFraming.encode(envelope, chunk++).forEach { outbox += send.encryptWithAd(ByteArray(0), it) }
    }

    fun response(stream: Long, status: Int, body: ByteArray = ByteArray(0), end: Boolean = false) =
        emit(ServiceFrame(stream, ApplicationResponse(status, emptyList(), body, end)))

    fun body(stream: Long, data: ByteArray, end: Boolean = false) = emit(ServiceFrame(stream, BodyChunk(data, end)))

    fun reset(stream: Long) = emit(ServiceFrame(stream, Reset(ResetCode.CANCELLED, "bye")))
}
