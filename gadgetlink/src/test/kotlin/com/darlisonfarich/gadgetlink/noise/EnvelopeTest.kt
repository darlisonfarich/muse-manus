package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.hex
import com.darlisonfarich.gadgetlink.hex as hexBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformidade com musegadget/noise/envelope.py do SDK (Apache-2.0). `noise_envelope_vectors.json`
 * sai da implementação de REFERÊNCIA (gadgetlink/tools/gen_envelope_vectors.py): os bytes
 * codificados têm que ser idênticos, e o decoder tem que aceitar, recusar e interpretar
 * (último campo vence, int32 com faixa, UTF-8 estrito, enum desconhecido recusado) como ela.
 */
class EnvelopeTest {
    private val v: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("noise_envelope_vectors.json")!!.readText()).jsonObject
    }

    // ---- spec JSON <-> objetos de produção ------------------------------------------------
    private fun headers(a: JsonElement) = a.jsonArray.map { Header(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }

    private fun frameFrom(spec: JsonObject): ServiceFrame {
        val value = spec["value"]
        val val0: FrameValue? = when (spec["kind"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }) {
            "request" -> value!!.jsonObject.let {
                ApplicationRequest(it.str("verb"), it.str("path"), headers(it["headers"]!!), hexBytes(it.str("body")), it["end_body"]!!.jsonPrimitive.boolean)
            }
            "response" -> value!!.jsonObject.let {
                ApplicationResponse(it["status"]!!.jsonPrimitive.int, headers(it["headers"]!!), hexBytes(it.str("body")), it["end_body"]!!.jsonPrimitive.boolean)
            }
            "body_chunk" -> value!!.jsonObject.let { BodyChunk(hexBytes(it.str("data")), it["end_body"]!!.jsonPrimitive.boolean) }
            "reset" -> value!!.jsonObject.let { Reset(ResetCode.of(it["code"]!!.jsonPrimitive.int), it.str("reason")) }
            else -> null
        }
        return ServiceFrame(spec["stream_id"]!!.jsonPrimitive.long, val0)
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun hdrs(list: List<Header>) = JsonArray(list.map { JsonArray(listOf(JsonPrimitive(it.key), JsonPrimitive(it.value))) })

    private fun specOf(frame: ServiceFrame): JsonObject {
        val value: JsonElement
        val kind: String?
        when (val x = frame.value) {
            is ApplicationRequest -> { kind = "request"; value = JsonObject(mapOf("verb" to JsonPrimitive(x.verb), "path" to JsonPrimitive(x.path), "headers" to hdrs(x.headers), "body" to JsonPrimitive(x.body.hex()), "end_body" to JsonPrimitive(x.endBody))) }
            is ApplicationResponse -> { kind = "response"; value = JsonObject(mapOf("status" to JsonPrimitive(x.status), "headers" to hdrs(x.headers), "body" to JsonPrimitive(x.body.hex()), "end_body" to JsonPrimitive(x.endBody))) }
            is BodyChunk -> { kind = "body_chunk"; value = JsonObject(mapOf("data" to JsonPrimitive(x.data.hex()), "end_body" to JsonPrimitive(x.endBody))) }
            is Reset -> { kind = "reset"; value = JsonObject(mapOf("code" to JsonPrimitive(x.code.code), "reason" to JsonPrimitive(x.reason))) }
            null -> { kind = null; value = JsonNull }
        }
        return JsonObject(mapOf("stream_id" to JsonPrimitive(frame.streamId), "kind" to (kind?.let { JsonPrimitive(it) } ?: JsonNull), "value" to value))
    }

    private fun encode(kind: String, spec: JsonObject): ByteArray = when (kind) {
        "frame" -> Envelope.encodeServiceFrame(frameFrom(spec))
        "service_request" -> Envelope.encodeServiceRequest(ServiceRequest(ServiceType.of(spec["service"]!!.jsonPrimitive.long), hexBytes(spec.str("payload"))))
        else -> Envelope.encodeServiceResponse(ServiceResponse(hexBytes(spec.str("payload"))))
    }

    private fun decode(kind: String, raw: ByteArray): JsonObject = when (kind) {
        "frame" -> specOf(Envelope.decodeServiceFrame(raw))
        "service_request" -> Envelope.decodeServiceRequest(raw).let {
            JsonObject(mapOf("service" to JsonPrimitive(it.service.code), "payload" to JsonPrimitive(it.payload.hex())))
        }
        else -> JsonObject(mapOf("payload" to JsonPrimitive(Envelope.decodeServiceResponse(raw).payload.hex())))
    }

    // ---- testes -----------------------------------------------------------------------------
    @Test
    fun encodingMatchesTheReferenceByteForByte() {
        val cases = v["encode"]!!.jsonArray.map { it.jsonObject }
        assertEquals(18, cases.size)
        for (c in cases) {
            val name = c.str("name")
            val got = encode(c.str("kind"), c["spec"]!!.jsonObject)
            assertEquals(name, c.str("hex"), got.hex())
        }
    }

    @Test
    fun decodingTheReferenceBytesGivesTheReferenceMeaning() {
        for (c in v["encode"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("name"), c["decoded"]!!.jsonObject, decode(c.str("kind"), hexBytes(c.str("hex"))))
        }
    }

    @Test
    fun rawMessagesAreAcceptedRefusedAndReadLikeTheReference() {
        val cases = v["decode"]!!.jsonArray.map { it.jsonObject }
        assertEquals(48, cases.size)
        for (c in cases) {
            val name = c.str("name")
            val raw = hexBytes(c.str("hex"))
            if (c["ok"]!!.jsonPrimitive.boolean) {
                assertEquals(name, c["spec"]!!.jsonObject, decode(c.str("kind"), raw))
            } else {
                assertThrows(name, ProtoError::class.java) { decode(c.str("kind"), raw) }
            }
        }
    }

    @Test
    fun theReferenceRefusalsOnEncodingAreUnrepresentableOrRefused() {
        val refused = v["refused"]!!.jsonArray.map { it.jsonObject }
        assertTrue(refused.all { it["refused"]!!.jsonPrimitive.boolean })
        assertThrows(ProtoError::class.java) { ResetCode.of(99) }
        assertThrows(ProtoError::class.java) { ServiceType.of(9) }
        // status 2^31 nem cabe: o campo é Int, e o -1 da referência sai estendido para 64 bits.
        assertEquals(10 + 1, Envelope.encodeApplicationResponse(ApplicationResponse(status = -1)).size)
    }

    @Test
    fun theLinkControlRequestShapeSurvivesARoundTrip() {
        val request = ApplicationRequest(
            verb = "POST", path = "/link-control",
            headers = listOf(Header("authorization", "Bearer t"), Header("x", "y")),
            body = "{\"type\":\"req\"}".toByteArray(), endBody = false,
        )
        val frame = Envelope.decodeServiceFrame(Envelope.encodeServiceFrame(ServiceFrame(42, request)))
        assertEquals(42L, frame.streamId)
        val back = frame.value as ApplicationRequest
        assertEquals("POST", back.verb)
        assertEquals("/link-control", back.path)
        assertEquals(listOf("authorization" to "Bearer t", "x" to "y"), back.headers.map { it.key to it.value })
        assertEquals("{\"type\":\"req\"}", back.body.decodeToString())
        assertEquals(false, back.endBody)
        assertNull(Envelope.decodeServiceFrame(ByteArray(0)).value)
    }
}
