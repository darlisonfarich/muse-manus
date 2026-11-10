// Port de musegadget/ble_framing.py do facebookincubator/muse-gadget-sdk
// (Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0).
// Mudanças: Kotlin; MTU que não deixa espaço para payload é erro em vez de
// perder a mensagem em silêncio; o montador é sincronizado.
package com.darlisonfarich.gadgetlink

import java.io.ByteArrayOutputStream

/**
 * Framing para mensagens maiores que um pacote BLE. Os dois sentidos usam o mesmo
 * quadro: 0xFE, índice do pedaço, total de pedaços, depois o fragmento. Um write
 * ou notify que não começa com 0xFE é uma mensagem completa, sem pedaços.
 */
object BleFraming {
    const val CHUNK_MAGIC = 0xFE
    const val HEADER_BYTES = 3
    const val MAX_PACKET_BYTES = 160
    const val MAX_CHUNKS = 255
    const val MAX_MESSAGE_BYTES = 8192
    const val DEFAULT_ATT_MTU = 23
    const val CHUNK_STAGGER_MS = 50L

    /**
     * Quebra [data] em pacotes que cabem em um notify no [mtu].
     *
     * @throws IllegalArgumentException se precisar de mais de [MAX_CHUNKS] pedaços,
     *   ou se o [mtu] não deixar espaço para payload (só acontece com 4..6, que o
     *   ATT nem permite; o Python mandaria uma mensagem vazia).
     */
    fun encodeChunks(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
        val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES)
        val usable = notifyMax - HEADER_BYTES
        require(usable > 0) { "mtu $mtu leaves no room for a chunk payload" }
        val fragments = if (data.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            (data.indices step usable).map { data.copyOfRange(it, minOf(it + usable, data.size)) }
        }
        require(fragments.size <= MAX_CHUNKS) { "message needs ${fragments.size} chunks (max $MAX_CHUNKS)" }
        val total = fragments.size
        return fragments.mapIndexed { i, fragment ->
            byteArrayOf(CHUNK_MAGIC.toByte(), i.toByte(), total.toByte()) + fragment
        }
    }
}

/**
 * Remonta writes em pedaços, estritamente em ordem. Índice 0, ou um total
 * diferente, começa uma mensagem nova. Pedaço fora de ordem ou mensagem grande
 * demais descarta o que foi juntado. Um por conexão.
 */
class ChunkAssembler(private val maxBytes: Int = BleFraming.MAX_MESSAGE_BYTES) {
    private val buffer = ByteArrayOutputStream()
    private var total = 0
    private var next = 0

    @Synchronized
    fun reset() {
        buffer.reset()
        total = 0
        next = 0
    }

    /** Junta um write; devolve a mensagem completa quando ela fecha, senão null. */
    @Synchronized
    fun feed(packet: ByteArray): ByteArray? {
        if (packet.size < BleFraming.HEADER_BYTES || (packet[0].toInt() and 0xFF) != BleFraming.CHUNK_MAGIC) {
            // Pacote cru = mensagem completa. O teto vale aqui também: no Android o
            // transporte remonta long writes, então um pacote cru pode passar de
            // maxBytes (no SDK cada write chega limitado pelo MTU e o furo não existe).
            // E um pacote cru no meio de uma montagem descarta o parcial — desviar do
            // SDK aqui é consciente: misturar prefixo velho com o resto corromperia a
            // mensagem e derrubaria a sessão na decifragem.
            val partial = total != 0
            if (partial) reset()
            if (packet.size > maxBytes) return null
            return packet.copyOf()
        }
        val index = packet[1].toInt() and 0xFF
        val packetTotal = packet[2].toInt() and 0xFF
        if (packetTotal == 0) {
            reset()
            return null
        }
        if (index == 0 || packetTotal != total) {
            reset()
            total = packetTotal
        }
        if (index != next || index >= total) {
            reset()
            return null
        }
        val fragmentSize = packet.size - BleFraming.HEADER_BYTES
        if (buffer.size() + fragmentSize > maxBytes) {
            reset()
            return null
        }
        buffer.write(packet, BleFraming.HEADER_BYTES, fragmentSize)
        next = index + 1
        if (next < total) return null
        val message = buffer.toByteArray()
        reset()
        return message
    }
}
