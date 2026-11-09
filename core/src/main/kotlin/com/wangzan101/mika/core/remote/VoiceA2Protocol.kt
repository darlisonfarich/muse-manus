package com.wangzan101.mika.core.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Version 1 of the reversed voice channel. All multi-byte fields are big endian. */
object VoiceA2Protocol {
    const val VERSION = 1
    const val WAV = 1
    const val MP3 = 2
    const val HEADER_BYTES = 25
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_WAV_BYTES = 1_200_000
    const val MAX_MP3_BYTES = 12 * 1024 * 1024
    const val MAX_CONTROL_BYTES = 4_096

    data class Frame(val kind: Int, val turnId: String, val seq: Int, val payload: ByteArray)

    fun control(type: String, vararg fields: Pair<String, String>): String = buildJsonObject {
        put("type", type)
        fields.forEach { (key, value) -> put(key, value) }
    }.toString()

    fun parseControl(value: String): JsonObject {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_CONTROL_BYTES) { "control too large" }
        return Json.parseToJsonElement(value).jsonObject
    }

    fun field(obj: JsonObject, key: String) = obj[key]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("missing $key")

    fun bytes(obj: JsonObject): Int = obj["total_bytes"]?.jsonPrimitive?.int
        ?: throw IllegalArgumentException("missing total_bytes")

    fun encode(kind: Int, turnId: String, seq: Int, data: ByteArray): ByteArray {
        require(kind == WAV || kind == MP3)
        require(seq >= 0 && data.size <= CHUNK_BYTES)
        val uuid = UUID.fromString(turnId)
        return ByteBuffer.allocate(HEADER_BYTES + data.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'V'.code.toByte(), '0'.code.toByte(), '1'.code.toByte()))
            .put(kind.toByte()).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
            .putInt(seq).put(data).array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size in HEADER_BYTES..(HEADER_BYTES + CHUNK_BYTES)) { "frame size invalid" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(b.get() == 'M'.code.toByte() && b.get() == 'V'.code.toByte() &&
            b.get() == '0'.code.toByte() && b.get() == '1'.code.toByte()) { "frame magic invalid" }
        val kind = b.get().toInt()
        require(kind == WAV || kind == MP3)
        val id = UUID(b.long, b.long).toString()
        val seq = b.int
        require(seq >= 0)
        val payload = ByteArray(b.remaining())
        b.get(payload)
        return Frame(kind, id, seq, payload)
    }
}

/** Strict in-order bounded assembler; corrupted or repeated chunks are rejected. */
class VoiceA2Assembler(private val kind: Int, private val turnId: String, private val expected: Int) {
    private val out = java.io.ByteArrayOutputStream()
    private var next = 0

    init {
        require(expected > 0 && expected <= if (kind == VoiceA2Protocol.WAV)
            VoiceA2Protocol.MAX_WAV_BYTES else VoiceA2Protocol.MAX_MP3_BYTES)
    }

    fun append(frame: VoiceA2Protocol.Frame) {
        require(frame.kind == kind && frame.turnId == turnId && frame.seq == next) { "wrong frame order" }
        require(frame.payload.isNotEmpty() && frame.payload.size <= expected - out.size()) { "invalid frame length" }
        out.write(frame.payload)
        next++
    }

    fun finish(): ByteArray {
        require(out.size() == expected) { "incomplete audio" }
        return out.toByteArray()
    }

    val size: Int get() = out.size()
}
