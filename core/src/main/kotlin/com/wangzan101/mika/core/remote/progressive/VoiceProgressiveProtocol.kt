package com.wangzan101.mika.core.remote.progressive

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.core.remote.VoiceA2Protocol

/**
 * Isolated JVM reference codec for the negotiated A2 progressive-audio extension.
 *
 * This type is intentionally not wired into VoiceA2Hub or the Android screen.
 */
object VoiceProgressiveProtocol {
    const val EXTENSION = "audio_segments_v1"
    const val MP3 = VoiceA2Protocol.MP3
    const val HEADER_BYTES = 45
    const val CHUNK_BYTES = VoiceA2Protocol.CHUNK_BYTES
    const val MAX_SEGMENT_BYTES = VoiceA2Protocol.MAX_MP3_BYTES
    const val MAX_TURN_BYTES = VoiceA2Protocol.MAX_MP3_BYTES
    const val MAX_SEGMENTS = 128

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), '1'.code.toByte(),
    )

    data class Frame(
        val kind: Int,
        val turnId: String,
        val generationId: String,
        val segmentIndex: Int,
        val seq: Int,
        val payload: ByteArray,
    )

    fun encode(
        turnId: String,
        generationId: String,
        segmentIndex: Int,
        seq: Int,
        payload: ByteArray,
    ): ByteArray {
        require(segmentIndex >= 0 && seq >= 0)
        require(payload.isNotEmpty() && payload.size <= CHUNK_BYTES)
        val turn = UUID.fromString(turnId)
        val generation = UUID.fromString(generationId)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(MP3.toByte())
            .putLong(turn.mostSignificantBits).putLong(turn.leastSignificantBits)
            .putLong(generation.mostSignificantBits).putLong(generation.leastSignificantBits)
            .putInt(segmentIndex).putInt(seq)
            .put(payload)
            .array()
    }

    fun decode(wire: ByteArray): Frame {
        require(wire.size in (HEADER_BYTES + 1)..(HEADER_BYTES + CHUNK_BYTES)) {
            "progressive frame size invalid"
        }
        val buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) { "progressive frame magic invalid" }
        val kind = buffer.get().toInt() and 0xff
        require(kind == MP3) { "progressive frame kind invalid" }
        val turnId = UUID(buffer.long, buffer.long).toString()
        val generationId = UUID(buffer.long, buffer.long).toString()
        val segmentIndex = buffer.int
        val seq = buffer.int
        require(segmentIndex >= 0 && seq >= 0) { "negative progressive frame index" }
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        require(payload.isNotEmpty()) { "empty progressive frame" }
        return Frame(kind, turnId, generationId, segmentIndex, seq, payload)
    }
}

/**
 * Strict, bounded, ordered segment assembler. Completed segments are emitted and not retained.
 *
 * beginTurn() replaces any active identity, which makes all later controls/frames from the
 * previous generation stale. Matching-generation protocol violations clear the active turn.
 */
class VoiceProgressiveAssembler(
    private val maxSegmentBytes: Int = VoiceProgressiveProtocol.MAX_SEGMENT_BYTES,
    private val maxTurnBytes: Int = VoiceProgressiveProtocol.MAX_TURN_BYTES,
    private val maxSegments: Int = VoiceProgressiveProtocol.MAX_SEGMENTS,
) {
    data class TurnKey(val turnId: String, val generationId: String)

    sealed interface Event {
        data class TranscriptReceived(val key: TurnKey, val text: String) : Event
        data class ReplyReceived(val key: TurnKey, val text: String) : Event
        data class SegmentReady(
            val key: TurnKey,
            val segmentIndex: Int,
            val lastSegment: Boolean,
            val audio: ByteArray,
        ) : Event
        data class TurnComplete(val key: TurnKey, val segmentCount: Int) : Event
        data class Cancelled(val key: TurnKey) : Event
        data class Failed(val key: TurnKey, val code: String) : Event
    }

    private data class OpenSegment(
        val index: Int,
        val expectedBytes: Int,
        val last: Boolean,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(),
        var nextSeq: Int = 0,
    )

    private var activeKey: TurnKey? = null
    private var openSegment: OpenSegment? = null
    private var nextSegmentIndex = 0
    private var finalSegmentIndex: Int? = null
    private var totalBytes = 0
    private var transcriptReceived = false
    private var replyReceived = false

    init {
        require(maxSegmentBytes > 0 && maxTurnBytes > 0 && maxSegments > 0)
        require(maxSegmentBytes <= VoiceProgressiveProtocol.MAX_SEGMENT_BYTES)
        require(maxTurnBytes <= VoiceProgressiveProtocol.MAX_TURN_BYTES)
        require(maxSegments <= VoiceProgressiveProtocol.MAX_SEGMENTS)
    }

    /** Starts or supersedes a turn. Both IDs are UUIDs and must be unique per generation. */
    fun beginTurn(turnId: String, generationId: String) {
        UUID.fromString(turnId)
        UUID.fromString(generationId)
        clear()
        activeKey = TurnKey(turnId, generationId)
    }

    /** Accepts the v1.1 control messages relevant to progressive audio. */
    fun acceptControl(raw: String): List<Event> {
        val message = try {
            VoiceA2Protocol.parseControl(raw)
        } catch (failure: Exception) {
            clear()
            throw failure
        }
        val type = try {
            VoiceA2Protocol.field(message, "type")
        } catch (failure: Exception) {
            clear()
            throw failure
        }
        if (type !in RELEVANT_CONTROLS) return emptyList()

        val key = activeKey ?: return emptyList()
        val turnId = message["turn_id"]?.jsonPrimitive?.content
        val generationId = message["generation_id"]?.jsonPrimitive?.content
        require(turnId != null && generationId != null) {
            "progressive control is missing turn/generation identity"
        }
        if (turnId != key.turnId || generationId != key.generationId) return emptyList()

        return try {
            when (type) {
                "TRANSCRIPT" -> {
                    require(!transcriptReceived) { "duplicate TRANSCRIPT" }
                    require(openSegment == null && nextSegmentIndex == 0) {
                        "TRANSCRIPT must precede progressive audio"
                    }
                    val text = VoiceA2Protocol.field(message, "text")
                    transcriptReceived = true
                    listOf(Event.TranscriptReceived(key, text))
                }
                "REPLY" -> {
                    require(!replyReceived) { "duplicate REPLY" }
                    val text = VoiceA2Protocol.field(message, "text")
                    require(text.isNotBlank()) { "empty REPLY" }
                    replyReceived = true
                    listOf(Event.ReplyReceived(key, text))
                }
                "AUDIO_START" -> {
                    require(openSegment == null) { "previous segment has not ended" }
                    require(finalSegmentIndex == null) { "segment received after final marker" }
                    require(VoiceA2Protocol.field(message, "format") == "mp3") {
                        "unsupported progressive audio format"
                    }
                    val index = intField(message, "segment_index")
                    require(index == nextSegmentIndex) { "segment index is duplicate or out of order" }
                    require(index < maxSegments) { "too many progressive segments" }
                    val expectedBytes = VoiceA2Protocol.bytes(message)
                    require(expectedBytes > 0 && expectedBytes <= maxSegmentBytes) {
                        "segment size exceeds limit"
                    }
                    require(expectedBytes <= maxTurnBytes - totalBytes) {
                        "turn audio size exceeds limit"
                    }
                    val last = booleanField(message, "last_segment")
                    openSegment = OpenSegment(index, expectedBytes, last)
                    if (last) finalSegmentIndex = index
                    emptyList()
                }
                "AUDIO_END" -> {
                    require(VoiceA2Protocol.field(message, "kind") == "mp3") {
                        "unsupported progressive audio kind"
                    }
                    val segment = openSegment ?: throw IllegalArgumentException("AUDIO_END without AUDIO_START")
                    require(intField(message, "segment_index") == segment.index) {
                        "AUDIO_END segment index mismatch"
                    }
                    require(segment.output.size() == segment.expectedBytes) {
                        "segment byte count mismatch"
                    }
                    val audio = segment.output.toByteArray()
                    totalBytes += audio.size
                    nextSegmentIndex++
                    openSegment = null
                    listOf(Event.SegmentReady(key, segment.index, segment.last, audio))
                }
                "TURN_END" -> {
                    require(openSegment == null) { "TURN_END with an open segment" }
                    val count = intField(message, "segment_count")
                    require(count in 1..maxSegments) { "invalid segment_count" }
                    require(nextSegmentIndex == count) { "TURN_END before all segments completed" }
                    require(finalSegmentIndex == count - 1) { "final segment marker missing or misplaced" }
                    require(replyReceived) { "TURN_END before full REPLY" }
                    clear()
                    listOf(Event.TurnComplete(key, count))
                }
                "CANCEL" -> {
                    clear()
                    listOf(Event.Cancelled(key))
                }
                "ERROR" -> {
                    val code = message["code"]?.jsonPrimitive?.content ?: "gateway_error"
                    clear()
                    listOf(Event.Failed(key, code))
                }
                else -> emptyList()
            }
        } catch (failure: Exception) {
            clear()
            throw failure
        }
    }

    /** Accepts one MV11 binary frame; stale turn/generation frames are discarded. */
    fun onBinary(wire: ByteArray): List<Event> {
        val frame = try {
            VoiceProgressiveProtocol.decode(wire)
        } catch (failure: Exception) {
            clear()
            throw failure
        }
        val key = activeKey ?: return emptyList()
        if (frame.turnId != key.turnId || frame.generationId != key.generationId) return emptyList()

        return try {
            val segment = openSegment ?: throw IllegalArgumentException("binary frame without AUDIO_START")
            require(frame.kind == VoiceProgressiveProtocol.MP3)
            require(frame.segmentIndex == segment.index) { "binary segment index mismatch" }
            require(frame.seq == segment.nextSeq) { "binary chunk sequence is duplicate or out of order" }
            require(frame.payload.size <= segment.expectedBytes - segment.output.size()) {
                "binary frame exceeds declared segment size"
            }
            segment.output.write(frame.payload)
            segment.nextSeq++
            emptyList()
        } catch (failure: Exception) {
            clear()
            throw failure
        }
    }

    private fun intField(message: kotlinx.serialization.json.JsonObject, name: String): Int =
        message[name]?.jsonPrimitive?.int ?: throw IllegalArgumentException("missing $name")

    private fun booleanField(message: kotlinx.serialization.json.JsonObject, name: String): Boolean =
        message[name]?.jsonPrimitive?.boolean ?: throw IllegalArgumentException("missing $name")

    private fun clear() {
        activeKey = null
        openSegment = null
        nextSegmentIndex = 0
        finalSegmentIndex = null
        totalBytes = 0
        transcriptReceived = false
        replyReceived = false
    }

    private companion object {
        val RELEVANT_CONTROLS = setOf(
            "TRANSCRIPT", "REPLY", "AUDIO_START", "AUDIO_END", "TURN_END", "CANCEL", "ERROR",
        )
    }
}
