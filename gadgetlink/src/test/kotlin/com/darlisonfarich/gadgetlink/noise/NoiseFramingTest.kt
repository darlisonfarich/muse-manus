package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.hex
import com.darlisonfarich.gadgetlink.hex as hexBytes
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Conformidade com musegadget/noise/framing.py e _proto.py do SDK (Apache-2.0). Os vetores de
 * `noise_framing_vectors.json` saem da implementação de REFERÊNCIA (gadgetlink/tools/gen_framing_vectors.py):
 * a codificação tem que ser idêntica em bytes (hash dos frames grandes) e o decoder tem que
 * aceitar/recusar/remontar exatamente como ela, inclusive o envenenamento depois de um erro.
 */
class NoiseFramingTest {
    private val v: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("noise_framing_vectors.json")!!.readText()).jsonObject
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).hex()
    private fun payload(n: Int) = ByteArray(n) { ((it * 7 + 3) % 256).toByte() }
    private val maxChunk = NoiseFraming.MAX_CHUNK_PAYLOAD

    @Test
    fun theLimitsAreTheSdkOnes() {
        assertEquals(v["max_chunk_payload"]!!.jsonPrimitive.int, maxChunk)
        assertEquals(65489, maxChunk)
        assertEquals(256, NoiseFraming.MAX_TOTAL_CHUNKS)
        assertEquals(16, NoiseFraming.MAX_PENDING_ASSEMBLIES)
        assertEquals(16 * 1024 * 1024, NoiseFraming.MAX_ASSEMBLY_BYTES)
    }

    @Test
    fun encodingMatchesTheReferenceByteForByte() {
        val cases = v["encode"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, cases.size)
        for (c in cases) {
            val size = c["size"]!!.jsonPrimitive.int
            val frames = NoiseFraming.encode(payload(size), c["chunk_id"]!!.jsonPrimitive.long)
            val expected = c["frames"]!!.jsonArray.map { it.jsonObject }
            assertEquals("size $size", expected.size, frames.size)
            frames.zip(expected).forEachIndexed { i, (got, want) ->
                assertEquals("size $size frame $i len", want["len"]!!.jsonPrimitive.int, got.size)
                assertEquals("size $size frame $i sha", want["sha256"]!!.jsonPrimitive.content, sha(got))
                want["hex"]?.let { assertEquals(it.jsonPrimitive.content, got.hex()) }
            }
        }
    }

    @Test
    fun decodingRawFramesMatchesTheReferenceIncludingTheRefusals() {
        val cases = v["decode"]!!.jsonArray.map { it.jsonObject }
        assertEquals(25, cases.size)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val raw = hexBytes(c["raw"]!!.jsonPrimitive.content)
            if (c["ok"]!!.jsonPrimitive.boolean) {
                val f = NoiseFraming.decodeFrame(raw)
                assertEquals(name, c["chunk_id"]!!.jsonPrimitive.long, f.chunkId)
                assertEquals(name, c["chunk_index"]!!.jsonPrimitive.long, f.chunkIndex)
                assertEquals(name, c["total_chunks"]!!.jsonPrimitive.long, f.totalChunks)
                assertEquals(name, c["payload"]!!.jsonPrimitive.content, f.payload.hex())
            } else {
                assertThrows(name, ProtoError::class.java) { NoiseFraming.decodeFrame(raw) }
            }
        }
    }

    private fun materialize(spec: JsonObject): ByteArray = when {
        "gen" in spec -> spec["gen"]!!.jsonArray.let {
            NoiseFraming.encode(payload(it[0].jsonPrimitive.int), it[1].jsonPrimitive.long)[it[2].jsonPrimitive.int]
        }
        "frame" in spec -> spec["frame"]!!.jsonArray.let {
            val n = it[3].jsonPrimitive.int
            NoiseFraming.encodeFrame(
                NoiseTransportFrame(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long, it[2].jsonPrimitive.long, ByteArray(n) { j -> ((j * 5 + 1) % 256).toByte() }),
            )
        }
        else -> hexBytes(spec["hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun reassemblyMatchesTheReferenceStepByStep() {
        val scenarios = v["scenarios"]!!.jsonArray.map { it.jsonObject }
        assertEquals(18, scenarios.size)
        for (s in scenarios) {
            val name = s["name"]!!.jsonPrimitive.content
            val decoder = NoiseFrameDecoder()
            val steps = s["steps"]!!.jsonArray.map { it.jsonObject }
            val expected: List<JsonElement> = s["expected"]!!.jsonArray
            assertEquals(name, steps.size, expected.size)
            steps.zip(expected).forEachIndexed { i, (step, want) ->
                val label = "$name step $i"
                val frame = materialize(step)
                if (want is JsonPrimitive) {
                    when (want.content) {
                        "none" -> assertNull(label, decoder.decode(frame))
                        "error" -> assertThrows(label, IllegalArgumentException::class.java) { decoder.decode(frame) }
                        "poisoned" -> assertThrows(label, IllegalStateException::class.java) { decoder.decode(frame) }
                        else -> error("unknown expectation $want")
                    }
                } else {
                    val out = decoder.decode(frame)
                    assertNotNull(label, out)
                    assertEquals("$label len", want.jsonObject["len"]!!.jsonPrimitive.int, out!!.size)
                    assertEquals("$label sha", want.jsonObject["sha256"]!!.jsonPrimitive.content, sha(out))
                }
            }
        }
    }

    @Test
    fun anAssemblyThatTakesMoreThanSixtySecondsIsDropped() {
        val now = doubleArrayOf(1000.0)
        val decoder = NoiseFrameDecoder { now[0] }
        val first = NoiseFraming.encodeFrame(NoiseTransportFrame(5, 0, 2, byteArrayOf(1)))
        assertNull(decoder.decode(first))
        // Exatamente 60 s ainda vale (a recusa é "> 60"): o mesmo índice de novo é duplicata.
        now[0] = 1060.0
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(first) }

        val fresh = NoiseFrameDecoder { now[0] }
        now[0] = 2000.0
        assertNull(fresh.decode(first))
        now[0] = 2060.5 // passou de 60 s: a montagem velha some e o mesmo índice entra como novo
        assertNull(fresh.decode(first))
        val second = NoiseFraming.encodeFrame(NoiseTransportFrame(5, 1, 2, byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1, 2), fresh.decode(second))
    }

    @Test
    fun randomPayloadsRoundTripThroughChunking() {
        val random = java.util.Random(7)
        val decoder = NoiseFrameDecoder()
        for (size in listOf(0, 1, 2, maxChunk - 1, maxChunk, maxChunk + 1, 3 * maxChunk + 17, 200_000)) {
            val data = ByteArray(size).also(random::nextBytes)
            val frames = NoiseFraming.encode(data, random.nextLong())
            assertEquals(maxOf(1, (size + maxChunk - 1) / maxChunk), frames.size)
            var result: ByteArray? = null
            for ((i, f) in frames.shuffled(random).withIndex()) {
                val r = decoder.decode(f)
                if (i < frames.size - 1) assertNull(r) else result = r
            }
            assertArrayEquals("size $size", data, result)
        }
    }

    @Test
    fun aPayloadNeedingMoreThanTheChunkBudgetIsRefusedWhenEncoding() {
        assertEquals(256, NoiseFraming.encode(ByteArray(256 * maxChunk)).size)
        assertThrows(IllegalArgumentException::class.java) { NoiseFraming.encode(ByteArray(256 * maxChunk + 1)) }
    }

    @Test
    fun varintsRoundTripAndRefuseTheMalformedOnes() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 1L shl 32, Long.MAX_VALUE, -1L, Long.MIN_VALUE)) {
            val bytes = Proto.encodeVarint(value)
            assertEquals("$value", value, ProtoReader(bytes).readVarint())
        }
        assertEquals(10, Proto.encodeVarint(-1L).size)
        assertThrows(ProtoError::class.java) { ProtoReader(byteArrayOf(0x80.toByte())).readVarint() }
        assertThrows(ProtoError::class.java) { ProtoReader(ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x02)).readVarint() }
        assertThrows(ProtoError::class.java) { ProtoReader(ByteArray(10) { 0x80.toByte() } + byteArrayOf(0)).readVarint() }
    }

    @Test
    fun fieldNumbersAndWireTypesAreValidated() {
        assertThrows(ProtoError::class.java) { ProtoWriter().varint(0, 1) }
        assertThrows(ProtoError::class.java) { ProtoWriter().varint(19000, 1) }
        assertThrows(ProtoError::class.java) { ProtoWriter().varint(19999, 1) }
        assertTrue(ProtoWriter().also { it.varint(18999, 1); it.varint(20000, 1) }.toByteArray().isNotEmpty())
        assertThrows(ProtoError::class.java) { ProtoWriter().uint32(1, 1L shl 32) }
        assertThrows(ProtoError::class.java) { ProtoWriter().uint32(1, -1) }
    }
}
