// Transporte do túnel Hatch: junta a cifra do Noise, o framing e os envelopes. Port de
// musegadget/noise/transport.py do facebookincubator/muse-gadget-sdk (Copyright (c) Meta
// Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin; os métodos são
// sincronizados (a cifra só pode avançar na ordem em que os registros saem); chunk_id
// injetável para teste. Conformidade provada contra a referência (NoiseTransportTest +
// noise_transport_vectors.json).
package com.darlisonfarich.gadgetlink.noise

import java.security.SecureRandom

/** Registros cifrados de um pedido, prontos para ir um a um num frame binário do WebSocket. */
class EncryptedFrames(val streamId: Long, val frames: List<ByteArray>)

/** O que o servidor mandou: uma resposta, um pedaço de corpo ou um reset de um stream. */
class DecryptedFrame(val streamId: Long, val value: FrameValue)

class NoiseTransport(
    private val send: CipherState,
    private val recv: CipherState,
    private val chunkIds: () -> Long = SecureRandom()::nextLong,
) {
    private val decoder = NoiseFrameDecoder()
    private var nextStreamId = 1L
    private var dead = false

    private fun alive() {
        if (dead) throw NoiseProtocolError("NoiseTransport: dead after prior failure")
    }

    /** Qualquer falha mata o transporte: depois de um erro a cifra não pode mais ser reaproveitada. */
    private inline fun <T> guarded(block: () -> T): T {
        alive()
        try {
            return block()
        } catch (t: Throwable) {
            dead = true
            throw t
        }
    }

    @Synchronized
    fun encryptHttpRequest(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        service: ServiceType = ServiceType.DAEMON,
        headers: List<Header> = emptyList(),
    ): EncryptedFrames = sendApplicationRequest(method, path, body, service, headers, endBody = true)

    /** Abre um stream que continua com [encryptBodyChunk] (end_body = falso). */
    @Synchronized
    fun startStreamRequest(
        method: String,
        path: String,
        service: ServiceType = ServiceType.DAEMON,
        headers: List<Header> = emptyList(),
    ): EncryptedFrames = sendApplicationRequest(method, path, ByteArray(0), service, headers, endBody = false)

    private fun sendApplicationRequest(
        method: String,
        path: String,
        body: ByteArray,
        service: ServiceType,
        headers: List<Header>,
        endBody: Boolean,
    ): EncryptedFrames = guarded {
        val streamId = nextStreamId++
        val request = ApplicationRequest(method, path, headers, body, endBody)
        EncryptedFrames(streamId, encryptRequest(service, ServiceFrame(streamId, request)))
    }

    @Synchronized
    fun encryptBodyChunk(
        streamId: Long,
        data: ByteArray,
        service: ServiceType = ServiceType.DAEMON,
        endBody: Boolean = false,
    ): List<ByteArray> = guarded { encryptRequest(service, ServiceFrame(streamId, BodyChunk(data, endBody))) }

    @Synchronized
    fun encryptReset(
        streamId: Long,
        service: ServiceType = ServiceType.DAEMON,
        reason: String = "",
        code: ResetCode = ResetCode.CANCELLED,
    ): List<ByteArray> = guarded { encryptRequest(service, ServiceFrame(streamId, Reset(code, reason))) }

    /**
     * Decifra um registro. Devolve null quando ele só completou parte de uma mensagem (ou quando o
     * quadro não carrega nada). Pedido vindo do servidor é erro: o cliente só recebe resposta,
     * pedaço de corpo e reset.
     */
    @Synchronized
    fun decryptFrame(ciphertext: ByteArray): DecryptedFrame? = guarded {
        val plain = recv.decryptWithAd(EMPTY_AD, ciphertext)
        val reassembled = decoder.decode(plain) ?: return@guarded null
        val response = Envelope.decodeServiceResponse(reassembled)
        if (response.payload.isEmpty()) throw NoiseProtocolError("empty ServiceResponse payload")
        val frame = Envelope.decodeServiceFrame(response.payload)
        when (val value = frame.value) {
            is ApplicationResponse, is BodyChunk, is Reset -> DecryptedFrame(frame.streamId, value)
            is ApplicationRequest -> throw NoiseProtocolError("NoiseTransport: unexpected request frame from server")
            null -> null
        }
    }

    private fun encryptRequest(service: ServiceType, frame: ServiceFrame): List<ByteArray> {
        val request = Envelope.encodeServiceRequest(ServiceRequest(service, Envelope.encodeServiceFrame(frame)))
        return NoiseFraming.encode(request, chunkIds()).map { send.encryptWithAd(EMPTY_AD, it) }
    }

    private companion object {
        val EMPTY_AD = ByteArray(0)
    }
}
