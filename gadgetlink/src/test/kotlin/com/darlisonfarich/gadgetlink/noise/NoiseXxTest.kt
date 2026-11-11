package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.hex
import com.darlisonfarich.gadgetlink.hex as hexBytes
import com.darlisonfarich.gadgetlink.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformidade byte a byte com a implementação de REFERÊNCIA do SDK
 * (musegadget/noise/noise_xx.py, facebookincubator/muse-gadget-sdk, Apache-2.0).
 * `noise_xx_vectors.json` foi gerado por ela com chaves privadas fixas
 * (gadgetlink/tools/gen_noise_vectors.py); aqui o iniciador em Kotlin recebe as mesmas
 * chaves e tem que produzir exatamente os mesmos bytes.
 */
class NoiseXxTest {
    private val v: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("noise_xx_vectors.json")!!.readText()).jsonObject
    }

    private fun keys(vararg privHex: String): () -> X25519KeyPair {
        val queue = ArrayDeque(privHex.toList())
        return { X25519.fromPrivate(hexBytes(queue.removeFirst())) }
    }

    private fun initiator() = NoiseXxInitiator(keys(v.s("initiator_e_priv"), v.s("initiator_s_priv")))

    private fun handshakeDone(): NoiseXxInitiator = initiator().also {
        it.writeMessage1()
        it.readMessage2(hexBytes(v.s("msg2")))
        it.writeMessage3()
    }

    @Test
    fun handshakeMatchesTheReferenceByteForByte() {
        val ini = initiator()
        assertEquals("msg1", v.s("msg1"), ini.writeMessage1().hex())
        val payload = ini.readMessage2(hexBytes(v.s("msg2")))
        assertEquals("payload", v.s("payload"), payload.hex())
        assertEquals("msg3", v.s("msg3"), ini.writeMessage3().hex())
        assertEquals("handshake hash", v.s("handshake_hash"), ini.handshakeHash().hex())
        assertEquals("remote static", v.s("responder_s_pub"), ini.remoteStaticPublicKey()!!.hex())
        assertEquals(v.s("remote_static"), ini.remoteStaticPublicKey()!!.hex())
    }

    @Test
    fun transportMatchesTheReferenceInBothDirections() {
        val (send, recv) = handshakeDone().split()
        for (m in v["initiator_to_responder"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(m.s("ct"), send.encryptWithAd(hexBytes(m.s("ad")), hexBytes(m.s("pt"))).hex())
        }
        for (m in v["responder_to_initiator"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(m.s("pt"), recv.decryptWithAd(hexBytes(m.s("ad")), hexBytes(m.s("ct"))).hex())
        }
    }

    @Test
    fun roundTripsAgainstAnIndependentResponderWithRandomKeys() {
        repeat(5) {
            val ini = NoiseXxInitiator()
            val res = TestNoiseResponder("payload $it".toByteArray())
            val msg2 = res.readMessage1AndWriteMessage2(ini.writeMessage1())
            assertEquals("payload $it", ini.readMessage2(msg2).decodeToString())
            res.readMessage3(ini.writeMessage3())
            assertArrayEquals(ini.handshakeHash(), res.handshakeHash())
            val (iSend, iRecv) = ini.split()
            val (rSend, rRecv) = res.split()
            for (n in 0 until 50) {
                val msg = ByteArray(n * 7) { (it + n).toByte() }
                assertArrayEquals(msg, rRecv.decryptWithAd(byteArrayOf(1), iSend.encryptWithAd(byteArrayOf(1), msg)))
                assertArrayEquals(msg, iRecv.decryptWithAd(ByteArray(0), rSend.encryptWithAd(ByteArray(0), msg)))
            }
        }
    }

    @Test
    fun theTwoDirectionsUseDifferentKeys() {
        val (send, recv) = handshakeDone().split()
        val ct = send.encryptWithAd(ByteArray(0), "x".toByteArray())
        assertThrows(NoiseProtocolError::class.java) { recv.decryptWithAd(ByteArray(0), ct) }
    }

    @Test
    fun callingStepsOutOfOrderIsRefused() {
        assertThrows(NoiseProtocolError::class.java) { initiator().readMessage2(hexBytes(v.s("msg2"))) }
        assertThrows(NoiseProtocolError::class.java) { initiator().writeMessage3() }
        assertThrows(NoiseProtocolError::class.java) { initiator().split() }
        val ini = initiator().also { it.writeMessage1() }
        assertThrows(NoiseProtocolError::class.java) { ini.writeMessage1() }
    }

    @Test
    fun aShortMessage2KillsTheHandshake() {
        val ini = initiator().also { it.writeMessage1() }
        val short = hexBytes(v.s("msg2")).copyOf(Noise.MIN_MSG2_LEN - 1)
        val error = assertThrows(NoiseProtocolError::class.java) { ini.readMessage2(short) }
        assertTrue(error.message, error.message!!.contains("too short"))
        // Morto: nem a mensagem boa é aceita depois.
        assertThrows(NoiseProtocolError::class.java) { ini.readMessage2(hexBytes(v.s("msg2"))) }
    }

    @Test
    fun aTamperedMessage2IsRefusedInEveryRegion() {
        val good = hexBytes(v.s("msg2"))
        // 0: ephemeral; 40: static cifrada; 100: payload cifrado (inclui a tag).
        for (index in listOf(0, 31, 32, 40, 79, 80, 100, good.size - 1)) {
            val bad = good.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            val ini = initiator().also { it.writeMessage1() }
            assertThrows("byte $index", NoiseProtocolError::class.java) { ini.readMessage2(bad) }
            // Um erro no meio mata o handshake: nem a mensagem 2 certa é aceita depois.
            val dead = assertThrows("dead after byte $index", NoiseProtocolError::class.java) { ini.readMessage2(good) }
            assertTrue("byte $index: ${dead.message}", dead.message!!.contains("dead handshake"))
        }
    }

    @Test
    fun aLowOrderServerEphemeralIsRefused() {
        val lowOrder = listOf(
            ByteArray(32),
            ByteArray(32).also { it[0] = 1 },
        )
        for (point in lowOrder) {
            val bad = point + hexBytes(v.s("msg2")).copyOfRange(32, hexBytes(v.s("msg2")).size)
            val ini = initiator().also { it.writeMessage1() }
            assertThrows(NoiseProtocolError::class.java) { ini.readMessage2(bad) }
        }
    }

    @Test
    fun aFailedDecryptPoisonsTheCipherAndANonceIsNeverReused() {
        val (_, recv) = handshakeDone().split()
        val replies = v["responder_to_initiator"]!!.jsonArray.map { it.jsonObject }
        val first = hexBytes(replies[0].s("ct"))
        assertThrows(NoiseProtocolError::class.java) {
            recv.decryptWithAd(ByteArray(0), first.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        }
        // O contador já andou para 1; a mensagem 1 (a SEGUIDA, cifrada com o nonce 1) é a certa para
        // essa posição e mesmo assim tem que falhar: só a cifra envenenada recusa. Sem o veneno ela abriria.
        assertThrows(NoiseProtocolError::class.java) {
            recv.decryptWithAd(hexBytes(replies[1].s("ad")), hexBytes(replies[1].s("ct")))
        }
    }

    @Test
    fun replayingAnOldCiphertextFails() {
        val (_, recv) = handshakeDone().split()
        val first = hexBytes(v["responder_to_initiator"]!!.jsonArray[0].jsonObject.s("ct"))
        assertEquals("reply one", recv.decryptWithAd(ByteArray(0), first).decodeToString())
        assertThrows(NoiseProtocolError::class.java) { recv.decryptWithAd(ByteArray(0), first) }
    }

    @Test
    fun x25519AgreesWithRfc7748() {
        // RFC 7748 §6.1: chaves de Alice e Bob e o segredo compartilhado.
        val alice = hexBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hexBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.fromPrivate(alice).publicKey.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519.fromPrivate(bob).publicKey.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519.dh(alice, X25519.fromPrivate(bob).publicKey).hex())
        assertEquals(shared, X25519.dh(bob, X25519.fromPrivate(alice).publicKey).hex())
    }

    @Test
    fun x25519RefusesAWrongLengthKey() {
        assertThrows(NoiseProtocolError::class.java) { X25519.dh(ByteArray(32) { 1 }, ByteArray(31)) }
        assertFalse(X25519.generate().publicKey.all { it == 0.toByte() })
        assertTrue(X25519.isAvailable())
    }
}
