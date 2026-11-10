package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Os vetores oficiais usam os escalares 1 e 2, cujas coordenadas não começam com
 * byte zero. Em chaves reais isso acontece em ~1 de cada 128 pareamentos; um erro
 * de preenchimento quebraria o pareamento só às vezes. Aqui as chaves são aleatórias
 * e o lado do celular codifica/decodifica pontos sem usar o código de produção.
 */
class RandomKeysTest {
    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun coord(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
        return ByteArray(32 - raw.size) + raw
    }

    private fun uncompressed(key: ECPublicKey) = byteArrayOf(4) + coord(key.w.affineX) + coord(key.w.affineY)

    private fun publicFrom(raw: ByteArray, like: ECPublicKey): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))),
            like.params,
        ),
    ) as ECPublicKey

    @Test
    fun pointsWithLeadingZeroCoordinatesKeep65Bytes() {
        var found = 0
        var tries = 0
        while (found < 3 && tries < 20_000) {
            tries++
            val key = newKeyPair().public as ECPublicKey
            if (key.w.affineX.bitLength() > 248 && key.w.affineY.bitLength() > 248) continue
            found++
            val encoded = P256.encodePoint(key)
            assertEquals(65, encoded.size)
            assertArrayEquals(uncompressed(key), encoded)
            assertEquals(key.w, P256.decodePoint(encoded).w)
        }
        assertEquals("no leading-zero key in $tries tries", 3, found)
    }

    @Test
    fun randomHandshakesInteroperateWithAnIndependentMobile() {
        repeat(64) { round ->
            val device = PairingSession(nodeId = "homelink-000001", deviceId = "hatch-link:02:00:00:00:00:01",
                mac = "02:00:00:00:00:01", firmwareVersion = "1.0.0", sdkToken = "mgst_test")
            val mobileKeys = newKeyPair()
            val mobilePub = uncompressed(mobileKeys.public as ECPublicKey)
            val mobileNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val ready = device.handleHello(hello(overrides = mapOf(
                "mobile_pub" to JsonPrimitive(b64(mobilePub)),
                "mobile_nonce" to JsonPrimitive(b64(mobileNonce)),
            )))
            fun r(key: String) = (ready[key] as JsonPrimitive).content

            // O celular refaz tudo do seu lado: ECDH pela JDK, transcript e chaves pelas funções já batidas nos vetores.
            val devicePub = unb64(r("device_pub"))
            val ecdh = KeyAgreement.getInstance("ECDH").run {
                init(mobileKeys.private)
                doPhase(publicFrom(devicePub, mobileKeys.public as ECPublicKey), true)
                generateSecret()
            }
            val transcript = Pairing.buildTranscript(
                community = true, authEpoch = 0, policy = "confirm_app",
                deviceId = r("device_id"), nodeId = r("node_id"), mac = r("mac"), firmwareVersion = r("firmware_version"),
                mobilePub = b64(mobilePub), devicePub = r("device_pub"), mobileNonce = b64(mobileNonce),
                deviceNonce = r("device_nonce"),
            )
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
            assertEquals("round $round", b64(hash), r("transcript_hash"))
            val keys = Pairing.deriveSessionKeys(ecdh, mobileNonce, unb64(r("device_nonce")), hash)
            assertEquals("round $round", b64(keys.sessionId), r("session_id"))

            val mobile = Mobile(buildJsonObject {
                put("mobile_tx_key_hex", keys.mobileTx.hex())
                put("mobile_rx_key_hex", keys.mobileRx.hex())
                put("session_id", r("session_id"))
            })
            val finished = device.decrypt(mobile.seal(json("action" to "pairing_client_finished")))
            val generation = device.handleClientFinished(Json.parseToJsonElement(finished))
            assertTrue("round $round", generation != 0L && device.confirmed)
            assertEquals(
                json("type" to "status", "status" to "pairing_confirmed", "sdk_token" to "mgst_test"),
                mobile.open(device.encryptStatus("pairing_confirmed", generation)),
            )
        }
    }
}
