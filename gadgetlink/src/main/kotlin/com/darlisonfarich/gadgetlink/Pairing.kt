// Lado do device no pareamento BLE do Muse Gadget, protocolo v5. Port de
// musegadget/pairing.py do facebookincubator/muse-gadget-sdk (Copyright (c) Meta
// Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: reescrito em
// Kotlin; mensagens como JsonObject; chaves zeradas ao limpar a sessão; erros do
// provedor de criptografia viram PairingException em vez de derrubar a thread.
package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger

/**
 * Constantes, transcript e agenda de chaves do pareamento v5 — iguais ao firmware
 * ESP32 de referência e ao SDK Linux, para os apps Muse parearem sem mudança.
 *
 * Só o modo comunidade (pairing_auth "none", política confirm_app): protege os
 * segredos de setup de quem só escuta, mas NÃO autentica o par do ECDH, então não
 * impede um man-in-the-middle ativo. É o mesmo limite do SDK oficial.
 */
object Pairing {
    const val VERSION = 5
    const val MODEL = "hatch_link"
    const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
    const val POLICY_BUTTON = "confirm_press"
    const val POLICY_APP = "confirm_app"
    const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
    const val AUTH_COMMUNITY = "none"
    const val BUTTON_CONFIRM_TIMEOUT_S = 60

    const val RECORD_LABEL = "hatch-link ble setup v1"
    const val SESSION_ID_LABEL = "hatch-link session id v1"

    const val CLIENT_FINISHED_TIMEOUT_S = 60.0
    const val CONFIRMED_TIMEOUT_S = 120.0
    const val PROVISIONING_TIMEOUT_S = 120.0

    const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
    const val ERROR_DECRYPT = "error_pairing_decrypt"

    const val TO_DEVICE = 0
    const val FROM_DEVICE = 1

    const val NONCE_BYTES = 16
    const val SESSION_ID_BYTES = 16
    const val MAX_CIPHERTEXT_B64_CHARS = 16_384
    private val TWO_TO_64: BigInteger = BigInteger.ONE.shiftLeft(64)
    private val DECIMAL = Regex("[0-9]+")

    /** Transcript canônico v5; o SHA-256 dele é o transcript_hash. */
    fun buildTranscript(
        community: Boolean,
        authEpoch: Int,
        policy: String,
        deviceId: String,
        nodeId: String,
        mac: String,
        firmwareVersion: String,
        mobilePub: String,
        devicePub: String,
        mobileNonce: String,
        deviceNonce: String,
    ): String {
        val button = policy == POLICY_BUTTON
        require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: $policy" }
        require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid auth epoch: $authEpoch" }
        require(
            listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
                .all { it.isNotEmpty() },
        ) { "transcript fields must be non-empty" }
        return listOf(
            "hatch-link-pairing-v$VERSION",
            "version=$VERSION",
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=$deviceId",
            "node_id=$nodeId",
            "mac=$mac",
            "model=$MODEL",
            "firmware_version=$firmwareVersion",
            "selected_cipher_suite=$SUITE",
            "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
            "pairing_auth_epoch=$authEpoch",
            "pairing_policy=$policy",
            "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
            "mobile_pub=$mobilePub",
            "device_pub=$devicePub",
            "mobile_nonce=$mobileNonce",
            "device_nonce=$deviceNonce",
        ).joinToString("\n")
    }

    /**
     * [SessionKeys.mobileTx] abre o que o device RECEBE; [SessionKeys.mobileRx]
     * sela o que o device ENVIA.
     */
    fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        mobileNonce: ByteArray,
        deviceNonce: ByteArray,
        transcriptHash: ByteArray,
    ): SessionKeys {
        val salt = Hash.sha256(mobileNonce, deviceNonce, transcriptHash)
        val sessionSecret = Hkdf.extractAndExpand(salt, ecdhSecret, RECORD_LABEL.toByteArray(), 32)
        val mobileTx = Hkdf.expand(sessionSecret, "mobile->device".toByteArray(), 32)
        val mobileRx = Hkdf.expand(sessionSecret, "device->mobile".toByteArray(), 32)
        sessionSecret.fill(0)
        val sessionId = Hash.sha256(SESSION_ID_LABEL.toByteArray(), transcriptHash, ecdhSecret)
            .copyOf(SESSION_ID_BYTES)
        return SessionKeys(mobileTx, mobileRx, sessionId)
    }

    /** direção, três zeros, contador big-endian de 8 bytes: 12 bytes de nonce GCM. */
    fun recordNonce(direction: Int, counter: Long): ByteArray =
        byteArrayOf(direction.toByte(), 0, 0, 0) + ByteArray(8) { (counter ushr (56 - 8 * it)).toByte() }

    fun recordAad(sessionIdB64: String, direction: Int, counter: Long): ByteArray {
        val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
        return "$RECORD_LABEL|$sessionIdB64|$arrow|$counter".toByteArray()
    }

    /**
     * O JSON do status, compacto e na ordem de chaves do SDK. O sdk_token só vai
     * no pairing_confirmed (apps antigos leem só type e status e ignoram o resto).
     */
    fun statusPlaintext(status: String, sdkToken: String?): String = buildJsonObject {
        put("type", "status")
        put("status", status)
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") put("sdk_token", sdkToken)
    }.toString()

    /**
     * Contador do registro: string decimal ASCII, < 2^64. Valores acima de Long
     * nunca batem com o contador esperado, então são recusados aqui mesmo.
     */
    internal fun parseCounter(element: JsonElement?): Long {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(text != null && DECIMAL.matches(text)) { "invalid counter" }
        val value = BigInteger(text)
        require(value < TWO_TO_64) { "counter overflow" }
        require(value.bitLength() < 64) { "unexpected counter" }
        return value.toLong()
    }
}

class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)
