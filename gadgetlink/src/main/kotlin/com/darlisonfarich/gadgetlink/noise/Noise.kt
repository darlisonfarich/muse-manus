// Noise_XX_25519_AESGCM_SHA256 do túnel Hatch (passo 4 do gadget-link). Port de
// musegadget/noise/noise_xx.py do facebookincubator/muse-gadget-sdk (Copyright (c)
// Meta Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin sobre
// java.security/javax.crypto (X25519 via XDH do provedor, AES-GCM e HMAC do JCA); só o
// papel de iniciador vai para produção (o respondedor do SDK é "de teste" e mora nos
// testes); a inicialização acontece no construtor; as chaves privadas são zeradas ao
// terminar. Conformidade byte a byte provada contra a implementação de referência
// (NoiseXxTest + noise_xx_vectors.json).
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.Aead
import com.darlisonfarich.gadgetlink.Hash
import com.darlisonfarich.gadgetlink.Hkdf
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** Um passo do Noise falhou ou foi chamado fora de ordem; o handshake/cifra não se recupera. */
class NoiseProtocolError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object Noise {
    const val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256"
    const val DH_KEY_LEN = 32
    const val TAG_LEN = 16
    const val MIN_MSG2_LEN = DH_KEY_LEN + (DH_KEY_LEN + TAG_LEN) + TAG_LEN
    const val MIN_MSG3_LEN = DH_KEY_LEN + TAG_LEN + TAG_LEN

    /** Mesmo teto do SDK: o contador nunca passa de 2^53 − 1 (seguro até em JSON/JS). */
    internal const val MAX_SAFE_NONCE = (1L shl 53) - 1
}

class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    fun wipe() = privateKey.fill(0)
}

object X25519 {
    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    private val random = SecureRandom()

    /**
     * Prefixo fixo do PrivateKeyInfo (RFC 8410) para X25519: version=0, AlgorithmIdentifier =
     * OID 1.3.101.110 (2b 65 6e), OCTET STRING externo de 34 bytes envolvendo o OCTET STRING
     * interno de 32 bytes (o escalar cru, sem clamp — o provedor clampa). Concatenado com os 32
     * bytes da chave privada, dá o DER completo do PKCS#8 (48 bytes).
     */
    private val PKCS8_X25519_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e,
        0x04, 0x22, 0x04, 0x20,
    )

    /**
     * Prefixo fixo do SubjectPublicKeyInfo (RFC 8410) para X25519: AlgorithmIdentifier = OID
     * 1.3.101.110, BIT STRING de 33 bytes (0 bits não usados + os 32 bytes do u cru). Concatenado
     * com os 32 bytes da chave pública, dá o DER completo do X509 (44 bytes). Prefixo confirmado
     * batendo com `openssl genpkey -algorithm X25519 | openssl pkey -pubout -outform DER`.
     */
    private val X509_X25519_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    )

    /** Pontos de ordem pequena (libsodium / lista do SDK); o u é comparado com o bit 255 zerado. */
    private val LOW_ORDER: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map { h -> ByteArray(32) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }

    /** O X25519 do provedor existe (JDK 11+; no Android, API 33+). Quem usa decide o que fazer sem ele. */
    fun isAvailable(): Boolean = unavailableReason() == null

    /** Lista "nome(classe)" de cada provedor que registra o serviço dado, para achar um concorrente de XDH. */
    private fun providersFor(service: String, algorithm: String): String = runCatching {
        Security.getProviders().filter { it.getService(service, algorithm) != null }
            .joinToString(", ") { "${it.name}(${it.getService(service, algorithm)!!.className})" }
            .ifEmpty { "none" }
    }.getOrElse { "lookup failed (${it.javaClass.simpleName})" }

    /**
     * Por que o X25519 do provedor não respondeu aqui, se não respondeu. Só diagnóstico
     * (2026-11-12, Claude): a 1ª versão mostrava só a exceção mais externa; rawDh() embrulha a
     * GeneralSecurityException real, então agora desce [Throwable.cause] até o fundo. A causa
     * encontrada no POCO (InvalidKeySpecException via XECPrivateKeySpec, depois via
     * XECPublicKeySpec) já foi corrigida trocando as duas chaves para PKCS8EncodedKeySpec /
     * X509EncodedKeySpec; a lista de provedores de KeyFactory.XDH/KeyAgreement.XDH fica, para o
     * próximo achado que aparecer.
     */
    fun unavailableReason(): String? = try {
        rawDh(ByteArray(32) { 1 }, BASE_POINT)
        null
    } catch (e: Throwable) {
        val root = generateSequence(e) { it.cause }.last()
        val kf = providersFor("KeyFactory", "XDH")
        val ka = providersFor("KeyAgreement", "XDH")
        "${root.javaClass.name}: ${root.message} — KeyFactory.XDH=[$kf] KeyAgreement.XDH=[$ka]"
    }

    fun generate(): X25519KeyPair = fromPrivate(ByteArray(32).also(random::nextBytes))

    /** O JCA aplica o clamp do RFC 7748 ao escalar; a pública é a multiplicação pelo ponto base (u = 9). */
    fun fromPrivate(privateKey: ByteArray): X25519KeyPair {
        if (privateKey.size != 32) throw NoiseProtocolError("x25519: invalid private key length")
        return X25519KeyPair(privateKey.copyOf(), rawDh(privateKey, BASE_POINT))
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw NoiseProtocolError("x25519: invalid public key length")
        if (privateKey.size != 32) throw NoiseProtocolError("x25519: invalid private key length")
        val u = publicKey.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
        if (LOW_ORDER.any { MessageDigest.isEqual(it, u) }) throw NoiseProtocolError("x25519: rejected low-order public key")
        val shared = rawDh(privateKey, publicKey)
        if (shared.all { it == 0.toByte() }) throw NoiseProtocolError("x25519: DH produced all-zeros output")
        return shared
    }

    private fun rawDh(privateKey: ByteArray, publicKey: ByteArray): ByteArray = try {
        val factory = KeyFactory.getInstance("XDH")
        val u = publicKey.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
        val agreement = KeyAgreement.getInstance("XDH")
        // XECPrivateKeySpec e XECPublicKeySpec falham num Conscrypt real (achado no POCO,
        // 2026-11-12): InvalidKeySpecException "Must use XEC...Spec, PKCS8/X509EncodedKeySpec or
        // Raw EncodedKeySpec; was XEC...Spec" — o provedor rejeitando o próprio tipo que diz
        // aceitar, dos dois lados. KeyFactory.XDH só tem AndroidOpenSSL e AndroidKeyStore (sem
        // concorrente). PKCS8EncodedKeySpec/X509EncodedKeySpec (RFC 8410) são os outros caminhos
        // que a própria mensagem de erro lista, e funcionam igual no Conscrypt e no JDK desktop.
        agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(PKCS8_X25519_PREFIX + privateKey)))
        agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(X509_X25519_PREFIX + u)), true)
        agreement.generateSecret()
    } catch (e: GeneralSecurityException) {
        throw NoiseProtocolError("x25519: provider failure", e)
    }
}

/**
 * Cifra de transporte (CipherState do Noise): AES-256-GCM, nonce de 96 bits = 4 zeros + contador
 * de 64 bits big-endian. O contador anda mesmo quando a decifragem falha, e qualquer falha
 * ENVENENA a cifra — nada de tentar de novo com o mesmo nonce.
 */
class CipherState internal constructor(key: ByteArray?) {
    private var key: ByteArray? = key?.copyOf()
    private var nonce = 0L
    private var poisoned = false

    @Synchronized internal fun initializeKey(newKey: ByteArray) {
        check0()
        if (newKey.size != 32) throw NoiseProtocolError("CipherState: AES-GCM key must be 32 bytes")
        key = newKey.copyOf()
        nonce = 0
    }

    @Synchronized fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        check0()
        val k = key ?: return plaintext
        val n = nextNonce()
        return try {
            Aead.seal(k, nonceBytes(n), plaintext, ad)
        } catch (e: Exception) {
            poisoned = true
            throw NoiseProtocolError("CipherState: encrypt failed", e)
        }
    }

    @Synchronized fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        check0()
        val k = key ?: return ciphertext
        val n = nextNonce()
        return try {
            Aead.open(k, nonceBytes(n), ciphertext, ad)
        } catch (e: Exception) {
            poisoned = true
            throw NoiseProtocolError("CipherState: decrypt failed", e)
        }
    }

    @Synchronized fun hasKey(): Boolean = key != null

    private fun check0() {
        if (poisoned) throw NoiseProtocolError("CipherState: poisoned after prior failure")
    }

    private fun nextNonce(): Long {
        if (nonce >= Noise.MAX_SAFE_NONCE) {
            poisoned = true
            throw NoiseProtocolError("CipherState: nonce exhausted")
        }
        return nonce++
    }

    private fun nonceBytes(n: Long): ByteArray = ByteArray(12).also {
        for (i in 0 until 8) it[11 - i] = (n shr (8 * i)).toByte()
    }
}

/** SymmetricState do Noise (ck, h, cifra) com SHA-256 e o HKDF do Noise (= RFC 5869 com info vazio). */
internal class NoiseSymmetric {
    private var ck: ByteArray
    private var h: ByteArray
    private var cipher = CipherState(null)

    init {
        val name = Noise.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        h = ByteArray(32).also { name.copyInto(it) } // 28 bytes: cabe, é só preencher com zeros
        ck = h.copyOf()
        mixHash(ByteArray(0)) // prólogo vazio
    }

    fun mixHash(data: ByteArray) {
        h = Hash.sha256(h, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val out = Hkdf.extractAndExpand(ck, inputKeyMaterial, ByteArray(0), 64)
        ck = out.copyOfRange(0, 32)
        cipher = CipherState(null).also { it.initializeKey(out.copyOfRange(32, 64)) }
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    /** (iniciador→respondedor, respondedor→iniciador). */
    fun split(): Pair<CipherState, CipherState> {
        val out = Hkdf.extractAndExpand(ck, ByteArray(0), ByteArray(0), 64)
        ck = ByteArray(32)
        h = ByteArray(32)
        val c1 = CipherState(null).also { it.initializeKey(out.copyOfRange(0, 32)) }
        val c2 = CipherState(null).also { it.initializeKey(out.copyOfRange(32, 64)) }
        out.fill(0)
        return c1 to c2
    }

    fun handshakeHash(): ByteArray = h.copyOf()
}

/**
 * Lado iniciador do XX: → e / ← e, ee, s, es / → s, se. A autenticação do servidor não vem das
 * chaves do Noise (a estática dele só é registrada): vem do bearer por-VM no upgrade do WebSocket
 * sobre TLS. Cada conexão usa chaves novas, efêmera e estática.
 */
class NoiseXxInitiator(private val newKeyPair: () -> X25519KeyPair = X25519::generate) {
    private enum class Phase { INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

    private val ss = NoiseSymmetric()
    private var phase = Phase.INITIALIZED
    private var e: X25519KeyPair? = null
    private var s: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null

    private fun require(expected: Phase, method: String) {
        if (phase == Phase.DEAD) throw NoiseProtocolError("NoiseXX: $method called on dead handshake")
        if (phase != expected) throw NoiseProtocolError("NoiseXX: $method called in wrong phase (expected $expected, got $phase)")
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        die()
        throw t
    }

    fun writeMessage1(): ByteArray {
        require(Phase.INITIALIZED, "writeMessage1")
        return guarded {
            val ephemeral = newKeyPair().also { e = it }
            ss.mixHash(ephemeral.publicKey)
            ss.encryptAndHash(ByteArray(0)) // payload vazio, ainda sem chave: só entra no hash
            phase = Phase.MSG1_SENT
            ephemeral.publicKey.copyOf()
        }
    }

    /** @return o payload do servidor (decifrado e autenticado). */
    fun readMessage2(msg: ByteArray): ByteArray {
        require(Phase.MSG1_SENT, "readMessage2")
        if (msg.size < Noise.MIN_MSG2_LEN) {
            die()
            throw NoiseProtocolError("NoiseXX: message 2 too short (${msg.size} < ${Noise.MIN_MSG2_LEN})")
        }
        return guarded {
            val remoteEphemeral = msg.copyOfRange(0, Noise.DH_KEY_LEN).also { re = it }
            ss.mixHash(remoteEphemeral)
            ss.mixKey(X25519.dh(e!!.privateKey, remoteEphemeral))
            val remoteStatic = ss.decryptAndHash(msg.copyOfRange(Noise.DH_KEY_LEN, Noise.DH_KEY_LEN + Noise.DH_KEY_LEN + Noise.TAG_LEN))
            rs = remoteStatic
            ss.mixKey(X25519.dh(e!!.privateKey, remoteStatic))
            val payload = ss.decryptAndHash(msg.copyOfRange(Noise.DH_KEY_LEN + Noise.DH_KEY_LEN + Noise.TAG_LEN, msg.size))
            phase = Phase.MSG2_READ
            payload
        }
    }

    fun writeMessage3(): ByteArray {
        require(Phase.MSG2_READ, "writeMessage3")
        return guarded {
            val staticKey = newKeyPair().also { s = it }
            val encS = ss.encryptAndHash(staticKey.publicKey)
            ss.mixKey(X25519.dh(staticKey.privateKey, re!!))
            val encPayload = ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG3_SENT
            encS + encPayload
        }
    }

    /** @return (cifra de envio, cifra de recepção) — a ordem do iniciador. */
    fun split(): Pair<CipherState, CipherState> {
        require(Phase.MSG3_SENT, "split")
        phase = Phase.SPLIT
        val result = ss.split()
        wipe()
        return result
    }

    fun destroy() = die()

    fun remoteStaticPublicKey(): ByteArray? = rs?.copyOf()

    fun handshakeHash(): ByteArray = ss.handshakeHash()

    private fun die() {
        phase = Phase.DEAD
        wipe()
    }

    private fun wipe() {
        e?.wipe()
        s?.wipe()
        e = null
        s = null
        re = null
    }
}
