package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vetores oficiais copiados sem alteração de facebookincubator/muse-gadget-sdk
 * (linux/tests/vectors/link_pairing_v5.json, idêntico ao de esp32/tests/vectors),
 * commit 55cbf48, Apache-2.0. Dados sintéticos públicos: escalares 1 e 2.
 */
object Vectors {
    val all: List<JsonObject> by lazy {
        val text = javaClass.classLoader.getResource("link_pairing_v5.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
    }
    val APP: JsonObject get() = all.single { it.s("name") == "community_app_v5" }
}

fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int
fun JsonObject.b(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBooleanStrict()

fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** base64url independente do código de produção (JDK), para os testes não se validarem sozinhos. */
fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

class FakeClock(var now: Double = 1000.0) : () -> Double {
    override fun invoke(): Double = now
}

/** Par de chaves P-256 a partir do escalar do vetor e do ponto público publicado ao lado dele. */
fun keyPairFromVector(scalarHex: String, publicB64: String): KeyPair {
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    val point = unb64(publicB64)
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    val factory = KeyFactory.getInstance("EC")
    return KeyPair(
        factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)),
        factory.generatePrivate(ECPrivateKeySpec(BigInteger(scalarHex, 16), params)),
    )
}

fun makeDevice(
    vector: JsonObject = Vectors.APP,
    clock: FakeClock = FakeClock(),
    sdkToken: String? = null,
): PairingSession = PairingSession(
    nodeId = vector.s("node_id"),
    deviceId = vector.s("device_id"),
    mac = vector.s("mac"),
    firmwareVersion = vector.s("firmware_version"),
    sdkToken = sdkToken,
    clock = clock,
    generateKey = { keyPairFromVector(vector.s("device_private_scalar_hex"), vector.s("device_pub")) },
    randomBytes = { unb64(vector.s("device_nonce")) },
)

fun hello(vector: JsonObject = Vectors.APP, overrides: Map<String, JsonElement> = emptyMap()): JsonObject {
    val base = buildJsonObject {
        put("action", "pairing_client_hello")
        put("version", 5)
        put("pairing_auth", "none")
        put("pairing_policy", "confirm_app")
        put("mobile_pub", vector.s("mobile_pub"))
        put("mobile_nonce", vector.s("mobile_nonce"))
    }
    return JsonObject(base + overrides)
}

fun clientFinishedRecord(vector: JsonObject = Vectors.APP): JsonObject = buildJsonObject {
    put("action", "pairing_encrypted")
    put("session_id", vector.s("session_id"))
    put("counter", "0")
    put("ciphertext", vector.s("client_finished_ciphertext"))
    put("tag", vector.s("client_finished_tag"))
}

/** Lado do celular na camada de registros, escrito a partir do contrato e não do código do device. */
class Mobile(vector: JsonObject) {
    private val txKey = hex(vector.s("mobile_tx_key_hex"))
    private val rxKey = hex(vector.s("mobile_rx_key_hex"))
    val sessionId = vector.s("session_id")
    var txCounter = 0L

    private fun nonce(direction: Int, counter: Long): ByteArray =
        byteArrayOf(direction.toByte(), 0, 0, 0) + ByteArray(8) { (counter shr (56 - 8 * it)).toByte() }

    private fun aad(arrow: String, counter: Long) = "hatch-link ble setup v1|$sessionId|$arrow|$counter".toByteArray()

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(input)
        }

    fun sealBytes(plaintext: ByteArray, counter: Long? = null): JsonObject {
        val c = counter ?: txCounter++
        val sealed = gcm(Cipher.ENCRYPT_MODE, txKey, nonce(0, c), aad("m2d", c), plaintext)
        return buildJsonObject {
            put("action", "pairing_encrypted")
            put("session_id", sessionId)
            put("counter", c.toString())
            put("ciphertext", b64(sealed.copyOfRange(0, sealed.size - 16)))
            put("tag", b64(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }
    }

    fun seal(command: JsonObject, counter: Long? = null) = sealBytes(command.toString().toByteArray(), counter)

    fun open(envelope: JsonObject?): JsonObject {
        requireNotNull(envelope) { "device returned no record" }
        check(envelope.s("type") == "pairing_encrypted")
        check(envelope.s("session_id") == sessionId)
        val counter = envelope.s("counter").toLong()
        val plain = gcm(
            Cipher.DECRYPT_MODE, rxKey, nonce(1, counter), aad("d2m", counter),
            unb64(envelope.s("ciphertext")) + unb64(envelope.s("tag")),
        )
        return Json.parseToJsonElement(plain.decodeToString()).jsonObject
    }
}

fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) ->
    k to when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("unsupported $v")
    }
})

/** Sessão já confirmada pelo client-finished do vetor; o celular já gastou o contador 0. */
fun confirmedDevice(clock: FakeClock = FakeClock()): Triple<PairingSession, Mobile, Long> {
    val device = makeDevice(clock = clock)
    device.handleHello(hello())
    val command = Json.parseToJsonElement(device.decrypt(clientFinishedRecord()))
    val generation = device.handleClientFinished(command)
    val mobile = Mobile(Vectors.APP).apply { txCounter = 1 }
    return Triple(device, mobile, generation)
}
