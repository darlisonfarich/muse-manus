// Envelopes de serviço do túnel Hatch (o que viaja dentro dos frames do Noise): o pedido de
// serviço, o quadro com stream e a requisição/resposta HTTP-like. Port de
// musegadget/noise/envelope.py do facebookincubator/muse-gadget-sdk (Copyright (c) Meta
// Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin; o "kind" + "value"
// do Python viram um valor tipado (FrameValue); enums desconhecidos não existem em Kotlin,
// então a recusa fica em ServiceType.of / ResetCode.of. Conformidade provada contra a
// referência (EnvelopeTest + noise_envelope_vectors.json).
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.Utf8
import java.nio.charset.CharacterCodingException

enum class ServiceType(val code: Int) {
    DAEMON(0), SENTINEL(1), VAULT(2), AUTHD(3);

    companion object {
        /** @param raw o varint como veio (uint64 em Long): qualquer valor fora do enum é recusado. */
        fun of(raw: Long): ServiceType = entries.firstOrNull { it.code.toLong() == raw } ?: throw ProtoError("unknown service type")
    }
}

enum class ResetCode(val code: Int) {
    CODE_UNSPECIFIED(0), CANCELLED(1), TIMEOUT(2), PROTOCOL_ERROR(3), REFUSED_STREAM(4), INTERNAL_ERROR(5), SERVICE_UNAVAILABLE(6);

    companion object {
        fun of(raw: Int): ResetCode = entries.firstOrNull { it.code == raw } ?: throw ProtoError("unknown reset code")
    }
}

class Header(val key: String = "", val value: String = "")

/** O que um ServiceFrame carrega: exatamente um destes (ou nada). */
sealed interface FrameValue

class ApplicationRequest(
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue

class ApplicationResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue

class BodyChunk(val data: ByteArray = ByteArray(0), val endBody: Boolean = false) : FrameValue

class Reset(val code: ResetCode = ResetCode.CODE_UNSPECIFIED, val reason: String = "") : FrameValue

class ServiceFrame(val streamId: Long = 0, val value: FrameValue? = null)

class ServiceRequest(val service: ServiceType = ServiceType.DAEMON, val payload: ByteArray = ByteArray(0))

class ServiceResponse(val payload: ByteArray = ByteArray(0))

object Envelope {
    // ---- codificação ----------------------------------------------------------------------
    fun encodeHeader(h: Header): ByteArray = ProtoWriter().apply {
        if (h.key.isNotEmpty()) string(1, h.key)
        if (h.value.isNotEmpty()) string(2, h.value)
    }.toByteArray()

    fun encodeApplicationRequest(r: ApplicationRequest): ByteArray = ProtoWriter().apply {
        if (r.verb.isNotEmpty()) string(1, r.verb)
        if (r.path.isNotEmpty()) string(2, r.path)
        for (h in r.headers) bytes(3, encodeHeader(h))
        if (r.body.isNotEmpty()) bytes(4, r.body)
        if (r.endBody) bool(5, true)
    }.toByteArray()

    fun encodeApplicationResponse(r: ApplicationResponse): ByteArray = ProtoWriter().apply {
        if (r.status != 0) int32(1, r.status)
        for (h in r.headers) bytes(2, encodeHeader(h))
        if (r.body.isNotEmpty()) bytes(3, r.body)
        if (r.endBody) bool(4, true)
    }.toByteArray()

    fun encodeBodyChunk(c: BodyChunk): ByteArray = ProtoWriter().apply {
        if (c.data.isNotEmpty()) bytes(1, c.data)
        if (c.endBody) bool(2, true)
    }.toByteArray()

    fun encodeReset(r: Reset): ByteArray = ProtoWriter().apply {
        if (r.code != ResetCode.CODE_UNSPECIFIED) int32(1, r.code.code)
        if (r.reason.isNotEmpty()) string(2, r.reason)
    }.toByteArray()

    fun encodeServiceFrame(f: ServiceFrame): ByteArray = ProtoWriter().apply {
        if (f.streamId != 0L) int64(1, f.streamId)
        when (val v = f.value) {
            is ApplicationRequest -> bytes(2, encodeApplicationRequest(v))
            is ApplicationResponse -> bytes(3, encodeApplicationResponse(v))
            is BodyChunk -> bytes(4, encodeBodyChunk(v))
            is Reset -> bytes(5, encodeReset(v))
            null -> Unit
        }
    }.toByteArray()

    fun encodeServiceRequest(r: ServiceRequest): ByteArray = ProtoWriter().apply {
        if (r.service != ServiceType.DAEMON) varint(1, r.service.code.toLong())
        if (r.payload.isNotEmpty()) bytes(2, r.payload)
    }.toByteArray()

    fun encodeServiceResponse(r: ServiceResponse): ByteArray =
        if (r.payload.isEmpty()) ByteArray(0) else ProtoWriter().bytes(1, r.payload).toByteArray()

    // ---- decodificação --------------------------------------------------------------------
    private fun ProtoReader.Key.expect(wire: Int, name: String) {
        if (this.wire != wire) throw ProtoError("$name wrong wire type")
    }

    private fun text(raw: ByteArray): String = try {
        Utf8.decodeStrict(raw)
    } catch (e: CharacterCodingException) {
        throw ProtoError("invalid utf-8 string")
    }

    fun decodeHeader(data: ByteArray): Header {
        var key = ""
        var value = ""
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_DELIMITED, "Header.key"); key = text(r.readDelimited()) }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "Header.value"); value = text(r.readDelimited()) }
                else -> r.skip(k.wire)
            }
        }
        return Header(key, value)
    }

    fun decodeApplicationRequest(data: ByteArray): ApplicationRequest {
        var verb = ""
        var path = ""
        val headers = ArrayList<Header>()
        var body = ByteArray(0)
        var endBody = false
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.verb"); verb = text(r.readDelimited()) }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.path"); path = text(r.readDelimited()) }
                3 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.headers"); headers += decodeHeader(r.readDelimited()) }
                4 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationRequest.body"); body = r.readDelimited() }
                5 -> { k.expect(Proto.WIRE_VARINT, "ApplicationRequest.end_body"); endBody = r.readVarint() != 0L }
                else -> r.skip(k.wire)
            }
        }
        return ApplicationRequest(verb, path, headers, body, endBody)
    }

    fun decodeApplicationResponse(data: ByteArray): ApplicationResponse {
        var status = 0
        val headers = ArrayList<Header>()
        var body = ByteArray(0)
        var endBody = false
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_VARINT, "ApplicationResponse.status"); status = Proto.decodeInt32(r.readVarint()) }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationResponse.headers"); headers += decodeHeader(r.readDelimited()) }
                3 -> { k.expect(Proto.WIRE_DELIMITED, "ApplicationResponse.body"); body = r.readDelimited() }
                4 -> { k.expect(Proto.WIRE_VARINT, "ApplicationResponse.end_body"); endBody = r.readVarint() != 0L }
                else -> r.skip(k.wire)
            }
        }
        return ApplicationResponse(status, headers, body, endBody)
    }

    fun decodeBodyChunk(data: ByteArray): BodyChunk {
        var chunk = ByteArray(0)
        var endBody = false
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_DELIMITED, "BodyChunk.data"); chunk = r.readDelimited() }
                2 -> { k.expect(Proto.WIRE_VARINT, "BodyChunk.end_body"); endBody = r.readVarint() != 0L }
                else -> r.skip(k.wire)
            }
        }
        return BodyChunk(chunk, endBody)
    }

    fun decodeReset(data: ByteArray): Reset {
        var code = ResetCode.CODE_UNSPECIFIED
        var reason = ""
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_VARINT, "Reset.code"); code = ResetCode.of(Proto.decodeInt32(r.readVarint())) }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "Reset.reason"); reason = text(r.readDelimited()) }
                else -> r.skip(k.wire)
            }
        }
        return Reset(code, reason)
    }

    /** Se o mesmo campo de tipo aparece mais de uma vez, o ÚLTIMO vence (não há mescla), como na referência. */
    fun decodeServiceFrame(data: ByteArray): ServiceFrame {
        var streamId = 0L
        var value: FrameValue? = null
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_VARINT, "ServiceFrame.stream_id"); streamId = r.readVarint() }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceFrame.request"); value = decodeApplicationRequest(r.readDelimited()) }
                3 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceFrame.response"); value = decodeApplicationResponse(r.readDelimited()) }
                4 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceFrame.body_chunk"); value = decodeBodyChunk(r.readDelimited()) }
                5 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceFrame.reset"); value = decodeReset(r.readDelimited()) }
                else -> r.skip(k.wire)
            }
        }
        return ServiceFrame(streamId, value)
    }

    fun decodeServiceRequest(data: ByteArray): ServiceRequest {
        var service = ServiceType.DAEMON
        var payload = ByteArray(0)
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_VARINT, "ServiceRequest.service"); service = ServiceType.of(r.readVarint()) }
                2 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceRequest.payload"); payload = r.readDelimited() }
                else -> r.skip(k.wire)
            }
        }
        return ServiceRequest(service, payload)
    }

    fun decodeServiceResponse(data: ByteArray): ServiceResponse {
        var payload = ByteArray(0)
        val r = ProtoReader(data)
        while (!r.atEnd) {
            val k = r.readKey()
            when (k.field) {
                1 -> { k.expect(Proto.WIRE_DELIMITED, "ServiceResponse.payload"); payload = r.readDelimited() }
                else -> r.skip(k.wire)
            }
        }
        return ServiceResponse(payload)
    }
}
