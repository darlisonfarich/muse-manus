// Port de PairingSession em musegadget/pairing.py do facebookincubator/muse-gadget-sdk
// (Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0).
// Mudanças: Kotlin; mensagens como JsonObject; chaves zeradas ao limpar; UTF-8
// estrito nos dois sentidos; falha do provedor vira PairingException com a causa.
package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom

/** Um passo do handshake falhou; [status] é o erro a reportar no fio. */
class PairingException(val status: String, cause: Throwable? = null) : Exception(status, cause)

enum class PairingState(val wire: String) {
    IDLE("idle"),
    WAIT_CLIENT_FINISHED("wait_client_finished"),
    READY("ready"),
    PROVISIONING("provisioning"),
}

/**
 * Estado de pareamento de um device; seguro para chamar de várias threads
 * (callbacks de GATT chegam em threads de binder).
 *
 * Os métodos que avançam o handshake devolvem um token de *geração* diferente de
 * zero. Trabalho adiado (entrar no Wi-Fi, provisionar) guarda esse token e checa
 * [isCurrent] antes de agir, para trabalho de uma tentativa abandonada não mexer
 * numa mais nova.
 *
 * @param clock segundos monotônicos (o padrão usa System.nanoTime).
 */
class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    private val generateKey: () -> KeyPair = P256::generate,
    private val randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
) {
    private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
    private val lock = Any()

    private var generation = 0L
    private var phase = PairingState.IDLE
    private var deadline = 0.0
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var sessionIdB64 = ""
    private var rxCounter = 0L
    private var txCounter = 0L

    init {
        // Identidade vazia só estouraria no meio do primeiro hello, já no aparelho do usuário.
        require(nodeId.isNotEmpty() && deviceId.isNotEmpty() && mac.isNotEmpty()) {
            "nodeId, deviceId and mac must be non-empty"
        }
        synchronized(lock) { resetLocked() }
    }

    /** Campos de pareamento da resposta de get_device_info. */
    fun deviceInfo(): JsonObject = buildJsonObject {
        put("device_id", deviceId)
        put("mac", mac)
        put("model", Pairing.MODEL)
        put("pairing_protocol", Pairing.VERSION)
        put("pairing_auth", Pairing.AUTH_COMMUNITY)
        put("pairing_auth_epoch", 0)
        put("pairing_policy", Pairing.POLICY_APP)
    }

    val state: PairingState
        get() = synchronized(lock) {
            expireLocked()
            phase
        }

    val confirmed: Boolean
        get() = state.let { it == PairingState.READY || it == PairingState.PROVISIONING }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation != 0L && generation == this.generation
    }

    fun reset() = synchronized(lock) { resetLocked() }

    /**
     * Começa uma sessão a partir de pairing_client_hello; devolve pairing_ready.
     *
     * Versão/auth/política erradas são recusadas sem tocar na sessão atual; material
     * de chave inválido derruba a sessão atual (igual ao SDK).
     */
    fun handleHello(message: JsonObject): JsonObject {
        if (!isVersion(message["version"]) ||
            message.string("pairing_auth") != Pairing.AUTH_COMMUNITY ||
            message.string("pairing_policy") != Pairing.POLICY_APP
        ) throw PairingException(Pairing.ERROR_INVALID_HELLO)

        synchronized(lock) {
            resetLocked()
            val mobilePub: ByteArray
            val mobileNonce: ByteArray
            val peer: PublicKey
            try {
                mobilePub = B64Url.decode(message.string("mobile_pub"))
                mobileNonce = B64Url.decode(message.string("mobile_nonce"))
                require(
                    mobilePub.size == P256.POINT_BYTES && mobilePub[0] == 0x04.toByte() &&
                        mobileNonce.size == Pairing.NONCE_BYTES,
                ) { "invalid hello key material" }
                peer = P256.decodePoint(mobilePub)
            } catch (e: IllegalArgumentException) {
                resetLocked()
                throw PairingException(Pairing.ERROR_INVALID_HELLO, e)
            }

            try {
                val deviceKey = generateKey()
                val devicePub = P256.encodePoint(deviceKey.public)
                val deviceNonce = randomBytes(Pairing.NONCE_BYTES)
                check(deviceNonce.size == Pairing.NONCE_BYTES) { "random source returned ${deviceNonce.size} bytes" }
                val transcript = Pairing.buildTranscript(
                    community = true,
                    authEpoch = 0,
                    policy = Pairing.POLICY_APP,
                    deviceId = deviceId,
                    nodeId = nodeId,
                    mac = mac,
                    firmwareVersion = firmwareVersion,
                    mobilePub = B64Url.encode(mobilePub),
                    devicePub = B64Url.encode(devicePub),
                    mobileNonce = B64Url.encode(mobileNonce),
                    deviceNonce = B64Url.encode(deviceNonce),
                )
                val transcriptHash = Hash.sha256(transcript.toByteArray())
                val ecdhSecret = P256.ecdh(deviceKey.private, peer)
                // O segredo bruto é zerado mesmo se a derivação lançar (provedor
                // degradado): a regra do arquivo é não deixar chave na heap.
                val keys = try {
                    Pairing.deriveSessionKeys(ecdhSecret, mobileNonce, deviceNonce, transcriptHash)
                } finally {
                    ecdhSecret.fill(0)
                }

                rxKey = keys.mobileTx
                txKey = keys.mobileRx
                sessionIdB64 = B64Url.encode(keys.sessionId)
                rxCounter = 0
                txCounter = 0
                phase = PairingState.WAIT_CLIENT_FINISHED
                deadline = clock() + Pairing.CLIENT_FINISHED_TIMEOUT_S
                return buildJsonObject {
                    put("type", "pairing_ready")
                    put("version", Pairing.VERSION)
                    put("device_id", deviceId)
                    put("node_id", nodeId)
                    put("mac", mac)
                    put("model", Pairing.MODEL)
                    put("firmware_version", firmwareVersion)
                    put("pairing_auth", Pairing.AUTH_COMMUNITY)
                    put("pairing_auth_epoch", 0)
                    put("pairing_policy", Pairing.POLICY_APP)
                    put("device_pub", B64Url.encode(devicePub))
                    put("device_nonce", B64Url.encode(deviceNonce))
                    put("transcript_hash", B64Url.encode(transcriptHash))
                    put("session_id", sessionIdB64)
                }
            } catch (e: GeneralSecurityException) {
                // Provedor sem EC/ECDH/HMAC: não é culpa do hello, mas o app precisa de uma resposta.
                resetLocked()
                throw PairingException(Pairing.ERROR_INVALID_HELLO, e)
            }
        }
    }

    /**
     * Abre um registro pairing_encrypted do celular para o device. Qualquer falha
     * (sessão errada, contador pulado ou repetido, tag ruim, prazo vencido, UTF-8
     * inválido) limpa a sessão, como o firmware faz.
     */
    fun decrypt(envelope: JsonObject): String = synchronized(lock) {
        if (expireLocked() || phase == PairingState.IDLE) {
            resetLocked()
            throw PairingException(Pairing.ERROR_DECRYPT)
        }
        val plaintext = try {
            require(envelope.string("session_id") == sessionIdB64) { "wrong session" }
            val counter = Pairing.parseCounter(envelope["counter"])
            require(counter == rxCounter) { "unexpected counter" }
            val ciphertext = B64Url.decode(envelope.string("ciphertext"), Pairing.MAX_CIPHERTEXT_B64_CHARS)
            val tag = B64Url.decode(envelope.string("tag"))
            require(tag.size == Aead.TAG_BYTES) { "invalid tag length" }
            val opened = Aead.open(
                rxKey!!,
                Pairing.recordNonce(Pairing.TO_DEVICE, counter),
                ciphertext + tag,
                Pairing.recordAad(sessionIdB64, Pairing.TO_DEVICE, counter),
            )
            Utf8.decodeStrict(opened)
        } catch (e: IllegalArgumentException) {
            resetLocked()
            throw PairingException(Pairing.ERROR_DECRYPT, e)
        } catch (e: GeneralSecurityException) {
            resetLocked()
            throw PairingException(Pairing.ERROR_DECRYPT, e)
        } catch (e: CharacterCodingException) {
            resetLocked()
            throw PairingException(Pairing.ERROR_DECRYPT, e)
        }
        rxCounter++
        plaintext
    }

    /**
     * Confirma a sessão depois do primeiro registro decifrado. [command] tem que ser
     * exatamente {"action":"pairing_client_finished"} e ter sido o primeiro registro.
     * Em confirm_app o app já pediu o consentimento, então confirma direto.
     * Devolve a nova geração, ou 0 (depois de limpar a sessão) se o registro não vale.
     */
    fun handleClientFinished(command: JsonElement): Long = synchronized(lock) {
        val ok = isExactClientFinished(command) &&
            !expireLocked() &&
            phase == PairingState.WAIT_CLIENT_FINISHED &&
            rxCounter == 1L
        if (!ok) {
            resetLocked()
            return 0L
        }
        generation++
        phase = PairingState.READY
        deadline = clock() + Pairing.CONFIRMED_TIMEOUT_S
        generation
    }

    /** Entra em provisionamento a partir de uma sessão confirmada; devolve a geração dela (0 se não pode). */
    fun markProvisioning(): Long = synchronized(lock) {
        if (!expireLocked() && phase == PairingState.READY) {
            generation++
            phase = PairingState.PROVISIONING
            deadline = clock() + Pairing.PROVISIONING_TIMEOUT_S
        }
        if (phase == PairingState.PROVISIONING) generation else 0L
    }

    fun extendProvisioning(generation: Long): Boolean = synchronized(lock) {
        provisioningLocked(generation).also { if (it) deadline = clock() + Pairing.PROVISIONING_TIMEOUT_S }
    }

    /**
     * Roda [commit] sob a trava do pareamento se a sessão ainda vale. [commit] só
     * pode gravar estado local — nada de rede ou BLE aqui dentro.
     */
    fun commitProvisioning(generation: Long, commit: () -> Boolean): Boolean = synchronized(lock) {
        provisioningLocked(generation) && commit()
    }

    /**
     * Sela um registro do device para o celular. Devolve null quando não há sessão
     * ativa, ou quando uma [generation] diferente de zero já não é a atual.
     *
     * @throws IllegalArgumentException se [plaintext] tiver surrogate solto (não vira UTF-8).
     */
    fun encryptJson(plaintext: String, generation: Long = 0L): JsonObject? {
        val bytes = Utf8.encodeStrict(plaintext)
        synchronized(lock) {
            if ((generation != 0L && generation != this.generation) ||
                expireLocked() ||
                phase == PairingState.IDLE
            ) return null
            val counter = txCounter
            val sealed = Aead.seal(
                txKey!!,
                Pairing.recordNonce(Pairing.FROM_DEVICE, counter),
                bytes,
                Pairing.recordAad(sessionIdB64, Pairing.FROM_DEVICE, counter),
            )
            txCounter++
            return buildJsonObject {
                put("type", "pairing_encrypted")
                put("session_id", sessionIdB64)
                put("counter", counter.toString())
                put("ciphertext", B64Url.encode(sealed.copyOfRange(0, sealed.size - Aead.TAG_BYTES)))
                put("tag", B64Url.encode(sealed.copyOfRange(sealed.size - Aead.TAG_BYTES, sealed.size)))
            }
        }
    }

    fun encryptStatus(status: String, generation: Long = 0L): JsonObject? =
        encryptJson(Pairing.statusPlaintext(status, sdkToken), generation)

    private fun provisioningLocked(generation: Long): Boolean =
        generation != 0L &&
            generation == this.generation &&
            !expireLocked() &&
            phase == PairingState.PROVISIONING

    private fun resetLocked() {
        generation++
        clearLocked()
    }

    private fun clearLocked() {
        phase = PairingState.IDLE
        deadline = 0.0
        rxKey?.fill(0)
        txKey?.fill(0)
        rxKey = null
        txKey = null
        sessionIdB64 = ""
        rxCounter = 0
        txCounter = 0
    }

    /** Vencido: joga as chaves fora mas mantém a geração, para o dono reconhecer e fechar a conexão. */
    private fun expireLocked(): Boolean {
        if (phase == PairingState.IDLE || clock() <= deadline) return false
        clearLocked()
        return true
    }

    private companion object {
        val random = SecureRandom()

        fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** O Python aceita 5 e 5.0 (json.loads) e recusa bool e string. */
        fun isVersion(element: JsonElement?): Boolean {
            val p = element as? JsonPrimitive ?: return false
            if (p is JsonNull || p.isString || p.content == "true" || p.content == "false") return false
            return p.content.toDoubleOrNull() == Pairing.VERSION.toDouble()
        }

        fun isExactClientFinished(command: JsonElement): Boolean {
            val obj = command as? JsonObject ?: return false
            val action = obj["action"] as? JsonPrimitive ?: return false
            return obj.size == 1 && action.isString && action.content == "pairing_client_finished"
        }
    }
}
