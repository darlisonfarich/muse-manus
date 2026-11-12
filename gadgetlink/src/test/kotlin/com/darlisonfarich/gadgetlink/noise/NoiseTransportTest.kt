package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.hex
import com.darlisonfarich.gadgetlink.hex as hexBytes
import com.darlisonfarich.gadgetlink.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * O transporte (cifra + framing + envelopes) contra a implementação de REFERÊNCIA do SDK
 * (musegadget/noise/transport.py): `noise_transport_vectors.json` traz, com chaves e chunk_id
 * fixos, os registros cifrados que o cliente envia e o que ele deve concluir de cada registro do
 * servidor (gadgetlink/tools/gen_transport_vectors.py).
 */
class NoiseTransportTest {
    private val v: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("noise_transport_vectors.json")!!.readText()).jsonObject
    }
    private val keyVectors: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("noise_xx_vectors.json")!!.readText()).jsonObject
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).hex()
    private fun pattern70k() = ByteArray(70000) { ((it * 13 + 5) % 256).toByte() }
    private val none = ByteArray(0)

    private fun fixedKeys(vararg keys: String): () -> X25519KeyPair {
        val queue = ArrayDeque(keys.toList())
        return { X25519.fromPrivate(hexBytes(queue.removeFirst())) }
    }

    /** Handshake com as chaves fixas dos vetores: cliente de produção + cifras do servidor de teste. */
    private class Session(val client: NoiseTransport, val serverSend: CipherState, val serverRecv: CipherState)

    private fun session(chunkIds: () -> Long): Session {
        val ini = NoiseXxInitiator(fixedKeys(keyVectors.s("initiator_e_priv"), keyVectors.s("initiator_s_priv")))
        val res = TestNoiseResponder(hexBytes(keyVectors.s("payload")), fixedKeys(keyVectors.s("responder_e_priv"), keyVectors.s("responder_s_priv")))
        ini.readMessage2(res.readMessage1AndWriteMessage2(ini.writeMessage1()))
        res.readMessage3(ini.writeMessage3())
        val (send, recv) = ini.split()
        val (rSend, rRecv) = res.split()
        return Session(NoiseTransport(send, recv, chunkIds), rSend, rRecv)
    }

    private fun counter(start: Long): () -> Long {
        var next = start
        return { next++ }
    }

    /** Registros do servidor: um ServiceFrame dentro de um ServiceResponse, fragmentado e cifrado. */
    private fun serverRecords(s: Session, frame: ServiceFrame, chunkId: Long): List<ByteArray> {
        val envelope = Envelope.encodeServiceResponse(ServiceResponse(Envelope.encodeServiceFrame(frame)))
        return NoiseFraming.encode(envelope, chunkId).map { s.serverSend.encryptWithAd(none, it) }
    }

    // ---- envio ---------------------------------------------------------------------------------
    @Test
    fun whatTheClientSendsMatchesTheReferenceByteForByte() {
        val s = session(counter(v["first_chunk_id"]!!.jsonPrimitive.long))
        val ops = v["send"]!!.jsonArray.map { it.jsonObject }
        assertEquals(6, ops.size)
        for (op in ops) {
            val name = op.s("name")
            val a = op["args"]!!.jsonObject
            fun service(): ServiceType = when (a["service"]?.jsonPrimitive?.content ?: "daemon") {
                "sentinel" -> ServiceType.SENTINEL
                "vault" -> ServiceType.VAULT
                else -> ServiceType.DAEMON
            }
            fun data(): ByteArray = if (a.s("data") == "pattern70k") pattern70k() else hexBytes(a.s("data"))
            val frames: List<ByteArray> = when (a.s("op")) {
                "start_stream_request" -> s.client.startStreamRequest(a.s("method"), a.s("path")).also {
                    assertEquals(name, op["stream_id"]!!.jsonPrimitive.long, it.streamId)
                }.frames
                "http_request" -> s.client.encryptHttpRequest(
                    a.s("method"), a.s("path"), hexBytes(a.s("body")),
                    service(), a["headers"]!!.jsonArray.map { Header(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) },
                ).also { assertEquals(name, op["stream_id"]!!.jsonPrimitive.long, it.streamId) }.frames
                "body_chunk" -> s.client.encryptBodyChunk(a["stream"]!!.jsonPrimitive.long, data(), service(), a["end"]!!.jsonPrimitive.boolean)
                "reset" -> s.client.encryptReset(a["stream"]!!.jsonPrimitive.long, service(), a.s("reason"), ResetCode.of(a["code"]!!.jsonPrimitive.int))
                else -> error("unknown op")
            }
            val want = op["frames"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$name records", want.size, frames.size)
            frames.zip(want).forEachIndexed { i, (got, w) ->
                assertEquals("$name[$i] len", w["len"]!!.jsonPrimitive.int, got.size)
                assertEquals("$name[$i] sha", w.s("sha256"), sha(got))
                w["hex"]?.let { assertEquals("$name[$i] hex", it.jsonPrimitive.content, got.hex()) }
            }
        }
    }

    @Test
    fun streamIdsStartAtOneAndOnlyRequestsConsumeThem() {
        val s = session(counter(1))
        assertEquals(1L, s.client.startStreamRequest("POST", "/a").streamId)
        s.client.encryptBodyChunk(1, byteArrayOf(1))
        s.client.encryptReset(1)
        assertEquals(2L, s.client.encryptHttpRequest("GET", "/b").streamId)
        assertEquals(3L, s.client.startStreamRequest("POST", "/c").streamId)
    }

    @Test
    fun theServerSideCanReadWhatTheClientSent() {
        val s = session { 77L }
        val decoder = NoiseFrameDecoder()
        val records = s.client.encryptBodyChunk(5, pattern70k(), ServiceType.VAULT, true)
        assertEquals(2, records.size) // 70000 > 65489
        var message: ByteArray? = null
        for (r in records) message = decoder.decode(s.serverRecv.decryptWithAd(none, r)) ?: message
        val request = Envelope.decodeServiceRequest(message!!)
        assertEquals(ServiceType.VAULT, request.service)
        val frame = Envelope.decodeServiceFrame(request.payload)
        assertEquals(5L, frame.streamId)
        val chunk = frame.value as BodyChunk
        assertArrayEquals(pattern70k(), chunk.data)
        assertTrue(chunk.endBody)
    }

    // ---- recepção ------------------------------------------------------------------------------
    private fun specOf(d: DecryptedFrame): JsonObject {
        val value: JsonElement = when (val x = d.value) {
            is ApplicationResponse -> JsonObject(mapOf(
                "status" to JsonPrimitive(x.status),
                "headers" to JsonArray(x.headers.map { JsonArray(listOf(JsonPrimitive(it.key), JsonPrimitive(it.value))) }),
                "body" to JsonPrimitive(x.body.hex()), "end_body" to JsonPrimitive(x.endBody),
            ))
            is BodyChunk -> JsonObject(mapOf(
                "data" to if (x.data.size < 400) JsonPrimitive(x.data.hex()) else JsonObject(mapOf("len" to JsonPrimitive(x.data.size), "sha256" to JsonPrimitive(sha(x.data)))),
                "end_body" to JsonPrimitive(x.endBody),
            ))
            is Reset -> JsonObject(mapOf("code" to JsonPrimitive(x.code.code), "reason" to JsonPrimitive(x.reason)))
            else -> error("not a server frame")
        }
        val kind = when (d.value) { is ApplicationResponse -> "response"; is BodyChunk -> "body_chunk"; else -> "reset" }
        return JsonObject(mapOf("kind" to JsonPrimitive(kind), "stream_id" to JsonPrimitive(d.streamId), "value" to value))
    }

    @Test
    fun whatTheClientConcludesFromServerRecordsMatchesTheReference() {
        val scenarios = v["receive"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, scenarios.size)
        for (sc in scenarios) {
            val name = sc.s("name")
            val s = session(counter(v["first_chunk_id"]!!.jsonPrimitive.long))
            val entries = sc["records"]!!.jsonArray
            val records: List<ByteArray> = if (entries.all { it is JsonPrimitive }) {
                entries.map { hexBytes(it.jsonPrimitive.content) }
            } else {
                // Registros grandes (só hash no vetor): o servidor de teste os regenera e o hash da referência confere.
                val big = serverRecords(s, ServiceFrame(7, BodyChunk(pattern70k(), true)), v["first_chunk_id"]!!.jsonPrimitive.long)
                assertEquals("$name records", entries.size, big.size)
                big.zip(entries.map { it.jsonObject }).forEachIndexed { i, (got, w) ->
                    assertEquals("$name[$i] len", w["len"]!!.jsonPrimitive.int, got.size)
                    assertEquals("$name[$i] sha", w.s("sha256"), sha(got))
                }
                big
            }
            val expected = sc["expected"]!!.jsonArray
            assertEquals(name, expected.size, records.size)
            records.zip(expected).forEachIndexed { i, (record, want) ->
                val label = "$name step $i"
                if (want is JsonPrimitive && want.content == "error") {
                    assertThrows(label, RuntimeException::class.java) { s.client.decryptFrame(record) }
                } else if (want is JsonPrimitive && want.content == "none") {
                    assertNull(label, s.client.decryptFrame(record))
                } else {
                    assertEquals(label, want.jsonObject, specOf(s.client.decryptFrame(record)!!))
                }
            }
        }
    }

    @Test
    fun aClientTransportThatFailedOnceRefusesEverythingAfter() {
        val s = session(counter(1))
        assertThrows(RuntimeException::class.java) { s.client.decryptFrame(ByteArray(40)) }
        val dead = assertThrows(NoiseProtocolError::class.java) { s.client.startStreamRequest("POST", "/x") }
        assertTrue(dead.message, dead.message!!.contains("dead"))
        assertThrows(NoiseProtocolError::class.java) { s.client.encryptBodyChunk(1, byteArrayOf(1)) }
        assertThrows(NoiseProtocolError::class.java) { s.client.decryptFrame(ByteArray(40)) }
    }

    @Test
    fun aMessageNeedingMoreThanTheChunkBudgetKillsTheTransport() {
        val s = session(counter(1))
        assertThrows(IllegalArgumentException::class.java) {
            s.client.encryptBodyChunk(1, ByteArray(NoiseFraming.MAX_TOTAL_CHUNKS * NoiseFraming.MAX_CHUNK_PAYLOAD + 1))
        }
        assertThrows(NoiseProtocolError::class.java) { s.client.startStreamRequest("POST", "/x") }
    }
}
