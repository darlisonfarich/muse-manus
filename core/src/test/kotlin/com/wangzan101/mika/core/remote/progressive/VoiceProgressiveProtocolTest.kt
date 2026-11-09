package com.wangzan101.mika.core.remote.progressive

import org.junit.Assert.*
import org.junit.Test
import com.wangzan101.mika.core.remote.VoiceA2Protocol

class VoiceProgressiveProtocolTest {
    private val turn = "12345678-1234-4234-9234-123456789abc"
    private val generation = "12345678-1234-4234-9234-123456789abd"
    private val oldGeneration = "12345678-1234-4234-9234-123456789abe"
    private val nextTurn = "12345678-1234-4234-9234-123456789abf"

    @Test fun mv11FrameRoundTripPreservesGenerationAndSegment() {
        val payload = ByteArray(VoiceProgressiveProtocol.CHUNK_BYTES) { (it * 7).toByte() }
        val wire = VoiceProgressiveProtocol.encode(turn, generation, 3, 4, payload)
        assertEquals(VoiceProgressiveProtocol.HEADER_BYTES + payload.size, wire.size)
        assertEquals("MV11", String(wire.copyOfRange(0, 4), Charsets.US_ASCII))
        val frame = VoiceProgressiveProtocol.decode(wire)
        assertEquals(turn, frame.turnId)
        assertEquals(generation, frame.generationId)
        assertEquals(3, frame.segmentIndex)
        assertEquals(4, frame.seq)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun emitsCompleteSegmentsInOrderAndCompletesOnlyAtTurnEnd() {
        val assembler = VoiceProgressiveAssembler()
        assembler.beginTurn(turn, generation)
        assembler.acceptControl(transcript("pedido reconhecido"))
        assembler.acceptControl(start(0, last = false, totalBytes = 3))
        assembler.onBinary(frame(0, 0, byteArrayOf(1, 2)))
        assembler.onBinary(frame(0, 1, byteArrayOf(3)))
        val first = assembler.acceptControl(audioEnd(0)).single() as VoiceProgressiveAssembler.Event.SegmentReady
        assertEquals(0, first.segmentIndex)
        assertFalse(first.lastSegment)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.audio)

        assembler.acceptControl(reply("resposta completa"))
        assembler.acceptControl(start(1, last = true, totalBytes = 2))
        assembler.onBinary(frame(1, 0, byteArrayOf(4, 5)))
        val last = assembler.acceptControl(audioEnd(1)).single() as VoiceProgressiveAssembler.Event.SegmentReady
        assertEquals(1, last.segmentIndex)
        assertTrue(last.lastSegment)
        assertArrayEquals(byteArrayOf(4, 5), last.audio)

        val complete = assembler.acceptControl(turnEnd(2)).single() as VoiceProgressiveAssembler.Event.TurnComplete
        assertEquals(2, complete.segmentCount)
    }

    @Test fun rejectsSkippedAndDuplicateSegmentIndexes() {
        val skipped = VoiceProgressiveAssembler()
        skipped.beginTurn(turn, generation)
        expectIllegalArgument { skipped.acceptControl(start(1, last = true, totalBytes = 1)) }

        val duplicate = VoiceProgressiveAssembler()
        duplicate.beginTurn(turn, generation)
        duplicate.acceptControl(start(0, last = false, totalBytes = 1))
        duplicate.onBinary(frame(0, 0, byteArrayOf(1)))
        duplicate.acceptControl(audioEnd(0))
        expectIllegalArgument { duplicate.acceptControl(start(0, last = true, totalBytes = 1)) }
    }

    @Test fun rejectsDuplicateOrOutOfOrderBinaryChunks() {
        val assembler = VoiceProgressiveAssembler()
        assembler.beginTurn(turn, generation)
        assembler.acceptControl(start(0, last = true, totalBytes = 2))
        assembler.onBinary(frame(0, 0, byteArrayOf(1)))
        expectIllegalArgument { assembler.onBinary(frame(0, 0, byteArrayOf(2))) }
    }

    @Test fun discardsControlsAndFramesFromAnOlderGeneration() {
        val assembler = VoiceProgressiveAssembler()
        assembler.beginTurn(turn, oldGeneration)
        assembler.beginTurn(turn, generation)

        assertTrue(assembler.acceptControl(start(0, last = true, totalBytes = 1, gen = oldGeneration)).isEmpty())
        assertTrue(assembler.onBinary(frame(0, 0, byteArrayOf(9), gen = oldGeneration)).isEmpty())

        assembler.acceptControl(start(0, last = true, totalBytes = 1))
        assembler.onBinary(frame(0, 0, byteArrayOf(7)))
        val segment = assembler.acceptControl(audioEnd(0)).single() as VoiceProgressiveAssembler.Event.SegmentReady
        assertArrayEquals(byteArrayOf(7), segment.audio)
    }

    @Test fun cancelMidSegmentDiscardsBufferAndIgnoresLateFrames() {
        val assembler = VoiceProgressiveAssembler()
        assembler.beginTurn(turn, generation)
        assembler.acceptControl(start(0, last = true, totalBytes = 4))
        assembler.onBinary(frame(0, 0, byteArrayOf(1, 2)))

        val cancelled = assembler.acceptControl(cancel()).single() as VoiceProgressiveAssembler.Event.Cancelled
        assertEquals(turn, cancelled.key.turnId)
        assertTrue(assembler.onBinary(frame(0, 1, byteArrayOf(3, 4))).isEmpty())
        assertTrue(assembler.acceptControl(audioEnd(0)).isEmpty())

        assembler.beginTurn(nextTurn, oldGeneration)
        assembler.acceptControl(start(0, last = true, totalBytes = 1, id = nextTurn, gen = oldGeneration))
        assembler.onBinary(frame(0, 0, byteArrayOf(5), id = nextTurn, gen = oldGeneration))
        assertTrue(assembler.acceptControl(audioEnd(0, id = nextTurn, gen = oldGeneration)).single()
            is VoiceProgressiveAssembler.Event.SegmentReady)
    }

    @Test fun enforcesBothPerSegmentAndAggregateTurnByteCeilings() {
        val segmentLimited = VoiceProgressiveAssembler(maxSegmentBytes = 4, maxTurnBytes = 5, maxSegments = 4)
        segmentLimited.beginTurn(turn, generation)
        expectIllegalArgument { segmentLimited.acceptControl(start(0, last = true, totalBytes = 5)) }

        val turnLimited = VoiceProgressiveAssembler(maxSegmentBytes = 4, maxTurnBytes = 5, maxSegments = 4)
        turnLimited.beginTurn(turn, generation)
        turnLimited.acceptControl(start(0, last = false, totalBytes = 3))
        turnLimited.onBinary(frame(0, 0, byteArrayOf(1, 2, 3)))
        turnLimited.acceptControl(audioEnd(0))
        expectIllegalArgument { turnLimited.acceptControl(start(1, last = true, totalBytes = 3)) }
    }

    @Test fun rejectsTurnEndWithMissingFinalSegmentOrReply() {
        val assembler = VoiceProgressiveAssembler()
        assembler.beginTurn(turn, generation)
        assembler.acceptControl(start(0, last = false, totalBytes = 1))
        assembler.onBinary(frame(0, 0, byteArrayOf(1)))
        assembler.acceptControl(audioEnd(0))
        expectIllegalArgument { assembler.acceptControl(turnEnd(1)) }
    }

    private fun start(
        index: Int,
        last: Boolean,
        totalBytes: Int,
        id: String = turn,
        gen: String = generation,
    ) = """{"type":"AUDIO_START","turn_id":"$id","generation_id":"$gen","format":"mp3","segment_index":$index,"last_segment":$last,"total_bytes":$totalBytes}"""

    private fun audioEnd(index: Int, id: String = turn, gen: String = generation) =
        VoiceA2Protocol.control(
            "AUDIO_END", "turn_id" to id, "generation_id" to gen,
            "kind" to "mp3", "segment_index" to index.toString(),
        )

    private fun transcript(text: String) =
        VoiceA2Protocol.control("TRANSCRIPT", "turn_id" to turn, "generation_id" to generation, "text" to text)

    private fun reply(text: String) =
        VoiceA2Protocol.control("REPLY", "turn_id" to turn, "generation_id" to generation, "text" to text)

    private fun turnEnd(count: Int) =
        VoiceA2Protocol.control(
            "TURN_END", "turn_id" to turn, "generation_id" to generation,
            "segment_count" to count.toString(),
        )

    private fun cancel(id: String = turn, gen: String = generation) =
        VoiceA2Protocol.control("CANCEL", "turn_id" to id, "generation_id" to gen)

    private fun frame(
        segmentIndex: Int,
        seq: Int,
        payload: ByteArray,
        id: String = turn,
        gen: String = generation,
    ) = VoiceProgressiveProtocol.encode(id, gen, segmentIndex, seq, payload)

    private inline fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
