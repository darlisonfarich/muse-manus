// Framing do túnel Hatch: cada mensagem cifrada vira uma sequência de frames protobuf
// (chunk_id, chunk_index, total_chunks, payload) e é remontada do outro lado. Port de
// musegadget/noise/framing.py do facebookincubator/muse-gadget-sdk (Copyright (c) Meta
// Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin; relógio
// injetável; o decoder é sincronizado. Conformidade provada contra a referência
// (NoiseFramingTest + noise_framing_vectors.json).
package com.darlisonfarich.gadgetlink.noise

import java.security.SecureRandom

class NoiseTransportFrame(
    val chunkId: Long = 0,
    val chunkIndex: Long = 0,
    val totalChunks: Long = 1,
    val payload: ByteArray = ByteArray(0),
)

object NoiseFraming {
    const val MAX_CHUNK_PAYLOAD = 65489
    const val MAX_PENDING_ASSEMBLIES = 16
    const val MAX_TOTAL_CHUNKS = 256
    const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
    const val ASSEMBLY_TTL_SECONDS = 60

    private val random = SecureRandom()

    /** Campos com valor padrão (0) ficam de fora, como no protobuf; só o total 0 nunca é enviado. */
    fun encodeFrame(frame: NoiseTransportFrame): ByteArray {
        val out = ProtoWriter()
        if (frame.chunkId != 0L) out.int64(1, frame.chunkId)
        if (frame.chunkIndex != 0L) out.uint32(2, frame.chunkIndex)
        if (frame.totalChunks != 0L) out.uint32(3, frame.totalChunks)
        if (frame.payload.isNotEmpty()) out.bytes(4, frame.payload)
        return out.toByteArray()
    }

    /** Campo ausente vale o padrão do protobuf, menos total_chunks, que vale 1; campo desconhecido é pulado. */
    fun decodeFrame(data: ByteArray): NoiseTransportFrame {
        var chunkId = 0L
        var chunkIndex = 0L
        var totalChunks = 1L
        var payload = ByteArray(0)
        val reader = ProtoReader(data)
        while (!reader.atEnd) {
            val key = reader.readKey()
            when (key.field) {
                1 -> chunkId = reader.expectVarint(key, "chunk_id")
                2 -> chunkIndex = Proto.decodeUint32(reader.expectVarint(key, "chunk_index"))
                3 -> totalChunks = Proto.decodeUint32(reader.expectVarint(key, "total_chunks"))
                4 -> {
                    if (key.wire != Proto.WIRE_DELIMITED) throw ProtoError("NoiseTransportFrame.payload wrong wire type")
                    payload = reader.readDelimited()
                }
                else -> reader.skip(key.wire)
            }
        }
        return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
    }

    private fun ProtoReader.expectVarint(key: ProtoReader.Key, name: String): Long {
        if (key.wire != Proto.WIRE_VARINT) throw ProtoError("NoiseTransportFrame.$name wrong wire type")
        return readVarint()
    }

    /** Parte [data] em frames de até [MAX_CHUNK_PAYLOAD] bytes; vazio vira um frame só. */
    fun encode(data: ByteArray, chunkId: Long = random.nextLong()): List<ByteArray> {
        val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
        require(total <= MAX_TOTAL_CHUNKS) {
            "payload too large for noise framing (${data.size} bytes, $total chunks > $MAX_TOTAL_CHUNKS)"
        }
        if (data.isEmpty()) return listOf(encodeFrame(NoiseTransportFrame(chunkId, 0, 1, ByteArray(0))))
        return List(total) { index ->
            val start = index * MAX_CHUNK_PAYLOAD
            val chunk = data.copyOfRange(start, minOf(data.size, start + MAX_CHUNK_PAYLOAD))
            encodeFrame(NoiseTransportFrame(chunkId, index.toLong(), total.toLong(), chunk))
        }
    }
}

/**
 * Remonta mensagens a partir dos frames. Qualquer erro ENVENENA o decoder (IllegalStateException
 * dali em diante): um fluxo que já veio corrompido não é mais confiável. Montagens paradas há
 * mais de 60 s são descartadas, e há no máximo 16 ao mesmo tempo.
 */
class NoiseFrameDecoder(private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private class Assembly(val total: Int, val createdAt: Double) {
        val chunks = HashMap<Int, ByteArray>()
        var totalBytes = 0
    }

    private val pending = LinkedHashMap<Long, Assembly>()
    private var poisoned = false

    /** @return a mensagem completa quando o último fragmento chega; null enquanto faltam fragmentos. */
    @Synchronized fun decode(frameBytes: ByteArray): ByteArray? {
        if (poisoned) throw IllegalStateException("NoiseFrameDecoder: poisoned after prior failure")
        try {
            val frame = NoiseFraming.decodeFrame(frameBytes)
            if (frame.totalChunks < 1 || frame.totalChunks > NoiseFraming.MAX_TOTAL_CHUNKS) {
                throw IllegalArgumentException("invalid totalChunks: ${frame.totalChunks}")
            }
            if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
                throw IllegalArgumentException("chunkIndex ${frame.chunkIndex} out of range [0, ${frame.totalChunks})")
            }
            if (frame.payload.size > NoiseFraming.MAX_CHUNK_PAYLOAD) {
                throw IllegalArgumentException("payload too large for noise frame (${frame.payload.size} bytes)")
            }
            evictExpired()

            val now = nowSeconds()
            var assembly = pending[frame.chunkId]
            if (assembly == null) {
                if (pending.size >= NoiseFraming.MAX_PENDING_ASSEMBLIES) {
                    throw IllegalArgumentException("too many pending noise frame assemblies")
                }
                assembly = Assembly(frame.totalChunks.toInt(), now)
                pending[frame.chunkId] = assembly
            }
            if (assembly.total.toLong() != frame.totalChunks) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("inconsistent totalChunks for chunkId: expected ${assembly.total}, got ${frame.totalChunks}")
            }
            val index = frame.chunkIndex.toInt()
            if (index in assembly.chunks) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("duplicate chunkIndex $index")
            }
            assembly.totalBytes += frame.payload.size
            if (assembly.totalBytes > NoiseFraming.MAX_ASSEMBLY_BYTES) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("assembly exceeded byte budget")
            }
            assembly.chunks[index] = frame.payload
            if (assembly.chunks.size < assembly.total) return null

            pending.remove(frame.chunkId)
            if (assembly.total == 1) return assembly.chunks.getValue(0)
            val out = java.io.ByteArrayOutputStream(assembly.totalBytes)
            for (i in 0 until assembly.total) out.write(assembly.chunks.getValue(i))
            return out.toByteArray()
        } catch (t: Throwable) {
            poisoned = true
            throw t
        }
    }

    private fun evictExpired() {
        val now = nowSeconds()
        pending.entries.removeAll { now - it.value.createdAt > NoiseFraming.ASSEMBLY_TTL_SECONDS }
    }
}
