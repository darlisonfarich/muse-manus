package com.darlisonfarich.gadgetlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port de linux/tests/test_ble_framing.py do SDK oficial, mais as bordas de MTU. */
class BleFramingTest {
    private fun ByteArray.header() = copyOfRange(0, 3).toList()
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun chunkPayloadFollowsMtu() {
        for ((mtu, perChunk) in listOf(23 to 17, 185 to 157, 256 to 157, 3 to 17, 0 to 17)) {
            val data = ByteArray(1000) { 'x'.code.toByte() }
            val chunks = BleFraming.encodeChunks(data, mtu)
            assertTrue("mtu=$mtu", chunks.dropLast(1).all { it.size - 3 == perChunk })
            assertArrayEquals(data, chunks.map { it.copyOfRange(3, it.size) }.reduce { a, b -> a + b })
        }
    }

    @Test
    fun chunkHeaders() {
        val chunks = BleFraming.encodeChunks(ByteArray(40) { 'a'.code.toByte() }, 23)
        assertEquals(
            listOf(bytes(0xFE, 0, 3).toList(), bytes(0xFE, 1, 3).toList(), bytes(0xFE, 2, 3).toList()),
            chunks.map { it.header() },
        )
    }

    @Test
    fun emptyMessageIsOneChunk() {
        val chunks = BleFraming.encodeChunks(ByteArray(0))
        assertEquals(1, chunks.size)
        assertArrayEquals(bytes(0xFE, 0, 1), chunks[0])
    }

    @Test
    fun tooManyChunksIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { BleFraming.encodeChunks(ByteArray(17 * 255 + 1), 23) }
        assertEquals(255, BleFraming.encodeChunks(ByteArray(17 * 255), 23).size)
    }

    @Test
    fun mtuTooSmallForAHeaderIsAnErrorNotSilentDataLoss() {
        // No Python, mtu 4..6 dá fatia <= 0 e a mensagem some sem erro. O ATT exige MTU >= 23,
        // então isso nunca é legítimo: aqui falha alto em vez de mandar uma mensagem vazia.
        for (mtu in 4..6) {
            assertThrows("mtu=$mtu", IllegalArgumentException::class.java) { BleFraming.encodeChunks(ByteArray(10), mtu) }
        }
        assertEquals(10, BleFraming.encodeChunks(ByteArray(10), 7).size)
    }

    @Test
    fun roundTrip() {
        val message = ByteArray(2560) { (it % 256).toByte() }
        val assembler = ChunkAssembler()
        val results = BleFraming.encodeChunks(message, 185).map { assembler.feed(it) }
        assertArrayEquals(message, results.last())
        assertTrue(results.dropLast(1).all { it == null })
    }

    @Test
    fun roundTripAtEveryRealisticMtu() {
        val message = ByteArray(3000) { (it * 7).toByte() }
        for (mtu in listOf(23, 27, 64, 128, 185, 247, 251, 512, 517)) {
            val assembler = ChunkAssembler()
            val out = BleFraming.encodeChunks(message, mtu).map { assembler.feed(it) }
            assertArrayEquals("mtu=$mtu", message, out.last())
        }
    }

    @Test
    fun plainWritePassesThrough() {
        val write = """{"action":"get_device_info"}""".toByteArray()
        assertArrayEquals(write, ChunkAssembler().feed(write))
    }

    @Test
    fun shortWritesStartingWithMagicPassThrough() {
        assertArrayEquals(bytes(0xFE), ChunkAssembler().feed(bytes(0xFE)))
        assertArrayEquals(bytes(0xFE, 1), ChunkAssembler().feed(bytes(0xFE, 1)))
    }

    @Test
    fun outOfOrderChunkDiscardsTheMessage() {
        val chunks = BleFraming.encodeChunks(ByteArray(60) { 'y'.code.toByte() }, 23)
        val assembler = ChunkAssembler()
        assertNull(assembler.feed(chunks[0]))
        assertNull(assembler.feed(chunks[2]))
        assertNull(assembler.feed(chunks[1]))
        assertNull(assembler.feed(chunks[3]))
    }

    @Test
    fun indexZeroRestartsTheMessage() {
        val old = BleFraming.encodeChunks(ByteArray(60) { 'o'.code.toByte() }, 23)
        val new = BleFraming.encodeChunks(ByteArray(30) { 'n'.code.toByte() }, 23)
        val assembler = ChunkAssembler()
        assembler.feed(old[0])
        assembler.feed(old[1])
        assertArrayEquals(ByteArray(30) { 'n'.code.toByte() }, new.map { assembler.feed(it) }.last())
    }

    @Test
    fun changedTotalMidMessageDiscardsIt() {
        val assembler = ChunkAssembler()
        assembler.feed(bytes(0xFE, 0, 3) + "abc".toByteArray())
        assertNull(assembler.feed(bytes(0xFE, 1, 2) + "def".toByteArray()))
        assertNull(assembler.feed(bytes(0xFE, 2, 3) + "ghi".toByteArray()))
    }

    @Test
    fun zeroTotalIsIgnored() {
        assertNull(ChunkAssembler().feed(bytes(0xFE, 0, 0) + "abc".toByteArray()))
    }

    @Test
    fun indexBeyondTotalIsDiscarded() {
        val assembler = ChunkAssembler()
        assertNull(assembler.feed(bytes(0xFE, 0, 2, 0x61)))
        assertNull(assembler.feed(bytes(0xFE, 2, 2, 0x62)))
        // a mensagem foi descartada: o índice 1 sozinho não completa nada
        assertNull(assembler.feed(bytes(0xFE, 1, 2, 0x62)))
    }

    @Test
    fun oversizeMessageIsDiscarded() {
        val assembler = ChunkAssembler(maxBytes = 20)
        val chunks = BleFraming.encodeChunks(ByteArray(30) { 'z'.code.toByte() }, 23)
        assertEquals(listOf(null, null), chunks.map { assembler.feed(it) })
    }

    @Test
    fun oversizePlainWriteIsDiscarded() {
        // O teto também vale para pacote cru: no Android, long writes remontados
        // pelo transporte chegam como um pacote só, maior que qualquer MTU.
        val assembler = ChunkAssembler(maxBytes = 20)
        assertNull(assembler.feed(ByteArray(21) { 'p'.code.toByte() }))
        assertArrayEquals(ByteArray(20) { 'p'.code.toByte() }, assembler.feed(ByteArray(20) { 'p'.code.toByte() }))
    }

    @Test
    fun duplicateChunkDiscardsTheMessage() {
        // Semântica fixada: chunk repetido no meio da montagem perde a mensagem
        // inteira (o índice não bate com o próximo esperado e tudo é descartado).
        val chunks = BleFraming.encodeChunks(ByteArray(60) { 'd'.code.toByte() }, 23)
        val assembler = ChunkAssembler()
        assertNull(assembler.feed(chunks[0]))
        assertNull(assembler.feed(chunks[1]))
        assertNull(assembler.feed(chunks[1]))
        assertNull(assembler.feed(chunks[2]))
        assertNull(assembler.feed(chunks[3]))
    }

    @Test
    fun plainWriteDuringAssemblyDropsThePartial() {
        // Pacote cru no meio de uma montagem descarta o parcial: se os chunks
        // restantes chegarem depois, eles não completam mensagem nenhuma.
        val chunks = BleFraming.encodeChunks(ByteArray(30) { 'm'.code.toByte() }, 23)
        val assembler = ChunkAssembler()
        assertNull(assembler.feed(chunks[0]))
        val plain = """{"action":"get_device_info"}""".toByteArray()
        assertArrayEquals(plain, assembler.feed(plain))
        assertNull(assembler.feed(chunks[1]))
    }

    @Test
    fun highIndexAndTotalBytesAreUnsigned() {
        val message = ByteArray(17 * 200) { 1 }
        val chunks = BleFraming.encodeChunks(message, 23)
        assertEquals(200, chunks.size)
        assertEquals(0xC8, chunks[0][2].toInt() and 0xFF)
        val assembler = ChunkAssembler()
        assertArrayEquals(message, chunks.map { assembler.feed(it) }.last())
    }
}
