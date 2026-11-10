package com.darlisonfarich.gadgetlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/** Conformidade byte a byte com os três vetores oficiais (community, official, community_app). */
class PairingVectorsTest {
    @Test
    fun transcriptMatchesEveryVector() {
        assertEquals(3, Vectors.all.size)
        for (v in Vectors.all) {
            val transcript = Pairing.buildTranscript(
                community = v.b("community"),
                authEpoch = v.i("pairing_auth_epoch"),
                policy = v.s("pairing_policy"),
                deviceId = v.s("device_id"),
                nodeId = v.s("node_id"),
                mac = v.s("mac"),
                firmwareVersion = v.s("firmware_version"),
                mobilePub = v.s("mobile_pub"),
                devicePub = v.s("device_pub"),
                mobileNonce = v.s("mobile_nonce"),
                deviceNonce = v.s("device_nonce"),
            )
            assertEquals(v.s("name"), v.s("transcript"), transcript)
            val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
            assertEquals(v.s("name"), v.s("transcript_hash"), b64(hash))
        }
    }

    @Test
    fun keyScheduleMatchesEveryVector() {
        for (v in Vectors.all) {
            val keys = Pairing.deriveSessionKeys(
                hex(v.s("ecdh_secret_hex")),
                unb64(v.s("mobile_nonce")),
                unb64(v.s("device_nonce")),
                unb64(v.s("transcript_hash")),
            )
            assertEquals(v.s("name"), v.s("mobile_tx_key_hex"), keys.mobileTx.hex())
            assertEquals(v.s("name"), v.s("mobile_rx_key_hex"), keys.mobileRx.hex())
            assertEquals(v.s("name"), v.s("session_id"), b64(keys.sessionId))
        }
    }

    @Test
    fun sessionSecretMatchesEveryVector() {
        for (v in Vectors.all) {
            val transcriptHash = unb64(v.s("transcript_hash"))
            val salt = MessageDigest.getInstance("SHA-256")
                .digest(unb64(v.s("mobile_nonce")) + unb64(v.s("device_nonce")) + transcriptHash)
            val secret = Hkdf.extractAndExpand(salt, hex(v.s("ecdh_secret_hex")), Pairing.RECORD_LABEL.toByteArray(), 32)
            assertEquals(v.s("name"), v.s("session_secret_hex"), secret.hex())
        }
    }

    @Test
    fun ecdhOfTheVectorKeysMatchesTheVectorSecret() {
        for (v in Vectors.all) {
            val device = keyPairFromVector(v.s("device_private_scalar_hex"), v.s("device_pub"))
            val mobilePub = P256.decodePoint(unb64(v.s("mobile_pub")))
            assertEquals(v.s("name"), v.s("ecdh_secret_hex"), P256.ecdh(device.private, mobilePub).hex())
        }
    }

    @Test
    fun publicPointEncodingRoundTripsTheVectorKeys() {
        for (v in Vectors.all) {
            for (key in listOf("mobile_pub", "device_pub")) {
                val raw = unb64(v.s(key))
                assertArrayEquals(raw, P256.encodePoint(P256.decodePoint(raw)))
            }
        }
    }

    @Test
    fun decodePointRejectsPointsOffTheCurveItself() {
        // Não depende do provedor: um y adulterado tem que parar no decodePoint,
        // antes de virar chave e chegar no ECDH (ataque de curva inválida).
        val valid = unb64(Vectors.APP.s("mobile_pub"))
        val offCurve = valid.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val err = assertThrows(IllegalArgumentException::class.java) { P256.decodePoint(offCurve) }
        assertEquals("point is not on P-256", err.message)
        val outOfRange = byteArrayOf(4) + ByteArray(64) { 0xFF.toByte() }
        assertEquals(
            "coordinate out of range",
            assertThrows(IllegalArgumentException::class.java) { P256.decodePoint(outOfRange) }.message,
        )
    }

    @Test
    fun clientFinishedRecordMatchesEveryVector() {
        for (v in Vectors.all) {
            val keys = Pairing.deriveSessionKeys(
                hex(v.s("ecdh_secret_hex")), unb64(v.s("mobile_nonce")), unb64(v.s("device_nonce")),
                unb64(v.s("transcript_hash")),
            )
            assertEquals(v.s("client_finished_aad"), String(Pairing.recordAad(v.s("session_id"), Pairing.TO_DEVICE, 0)))
            val sealed = Aead.seal(
                keys.mobileTx, Pairing.recordNonce(Pairing.TO_DEVICE, 0),
                v.s("client_finished_plaintext").toByteArray(), Pairing.recordAad(v.s("session_id"), Pairing.TO_DEVICE, 0),
            )
            assertEquals(v.s("name"), v.s("client_finished_ciphertext"), b64(sealed.copyOfRange(0, sealed.size - 16)))
            assertEquals(v.s("name"), v.s("client_finished_tag"), b64(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }
    }

    @Test
    fun transcriptRejectsAppPolicyForOfficialDevices() {
        assertThrows(IllegalArgumentException::class.java) {
            Pairing.buildTranscript(
                community = false, authEpoch = 1, policy = "confirm_app",
                deviceId = "d", nodeId = "n", mac = "m", firmwareVersion = "1",
                mobilePub = "a", devicePub = "b", mobileNonce = "c", deviceNonce = "e",
            )
        }
    }

    @Test
    fun transcriptRejectsWrongEpochsAndEmptyFields() {
        assertThrows(IllegalArgumentException::class.java) { transcript(community = true, epoch = 1) }
        assertThrows(IllegalArgumentException::class.java) { transcript(community = false, epoch = 0, policy = "confirm_press") }
        assertThrows(IllegalArgumentException::class.java) { transcript(community = true, epoch = 0, mac = "") }
        assertThrows(IllegalArgumentException::class.java) { transcript(community = true, epoch = 0, policy = "whatever") }
    }

    @Test
    fun recordNonceIsDirectionThenBigEndianCounter() {
        assertEquals("010000000000000000000102", Pairing.recordNonce(Pairing.FROM_DEVICE, 258).hex())
        assertEquals("000000007fffffffffffffff", Pairing.recordNonce(Pairing.TO_DEVICE, Long.MAX_VALUE).hex())
    }

    private fun transcript(community: Boolean, epoch: Int, policy: String = "confirm_app", mac: String = "m") =
        Pairing.buildTranscript(
            community = community, authEpoch = epoch, policy = policy,
            deviceId = "d", nodeId = "n", mac = mac, firmwareVersion = "1",
            mobilePub = "a", devicePub = "b", mobileNonce = "c", deviceNonce = "e",
        )
}
