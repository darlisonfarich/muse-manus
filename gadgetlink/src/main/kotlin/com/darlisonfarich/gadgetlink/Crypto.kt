// Primitivas do pareamento v5, portadas de musegadget/pairing.py do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and
// affiliates; Apache License 2.0). Mudanças: reescrito em Kotlin sobre
// java.security/javax.crypto; base64url, HKDF e a validação do ponto P-256 feitos
// à mão para o comportamento ser o mesmo na JVM e no Android (Conscrypt).
package com.darlisonfarich.gadgetlink

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * base64url sem padding, com as mesmas recusas do firmware: só o alfabeto
 * url-safe, nada de "=", comprimento 1..[maxChars] e nunca resto 1 em 4.
 * Os bits que sobram no fim não são checados — igual ao Python.
 */
object B64Url {
    const val MAX_CHARS = 4096
    private val ALPHABET = Regex("[A-Za-z0-9_-]+")

    fun encode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** @throws IllegalArgumentException em qualquer entrada que o firmware recusaria. */
    fun decode(text: String?, maxChars: Int = MAX_CHARS): ByteArray {
        require(text != null && text.length in 1..maxChars) { "invalid base64url length" }
        require(text.length % 4 != 1 && ALPHABET.matches(text)) { "invalid base64url" }
        val out = ByteArray(text.length * 6 / 8)
        var acc = 0
        var bits = 0
        var n = 0
        for (c in text) {
            acc = (acc shl 6) or sextet(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out
    }

    private fun sextet(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '-' -> 62
        else -> 63 // '_'; o regex já garantiu que não há outro caractere
    }
}

internal object Hash {
    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
}

/** HKDF-SHA256 (RFC 5869). */
internal object Hkdf {
    private const val HASH_BYTES = 32

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            data.forEach(::update)
            doFinal()
        }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "invalid HKDF length $length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var block = 1
        while (filled < length) {
            previous = hmac(prk, previous, info, byteArrayOf(block.toByte()))
            val take = minOf(HASH_BYTES, length - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            block++
        }
        return out
    }

    fun extractAndExpand(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/** AES-256-GCM com tag de 16 bytes no fim; uma instância de Cipher nova por operação. */
internal object Aead {
    const val TAG_BYTES = 16

    private fun run(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
            doFinal(input)
        }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        run(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)

    /** @throws GeneralSecurityException se a tag não bater. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        run(Cipher.DECRYPT_MODE, key, nonce, aad, sealed)
}

internal object Utf8 {
    /** Decodificação estrita, como `bytes.decode("utf-8")`; nada de trocar por U+FFFD. */
    fun decodeStrict(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    /** Codificação estrita, como `str.encode("utf-8")`; surrogate solto é erro, não "?". */
    fun encodeStrict(text: String): ByteArray {
        val buffer = try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text))
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("text is not valid UTF-16", e)
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

/** P-256 (secp256r1): chaves, ponto não comprimido de 65 bytes e ECDH. */
object P256 {
    const val POINT_BYTES = 65
    private const val COORD_BYTES = 32

    /** Parâmetros nativos do provedor (SunEC na JVM, Conscrypt no Android). */
    private val params: ECParameterSpec by lazy {
        (generate().public as ECPublicKey).params
    }

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encodePoint(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

    /**
     * Decodifica e VALIDA um ponto não comprimido: 0x04, coordenadas < p e
     * y² = x³ + ax + b (mod p). O `from_encoded_point` do Python faz isso; aqui
     * não dá para confiar no provedor, então a conta é feita à mão. Um ponto
     * inválido aceito abriria ataque de curva inválida contra a chave do device.
     *
     * @throws IllegalArgumentException se o ponto não for da curva.
     */
    fun decodePoint(raw: ByteArray): ECPublicKey {
        require(raw.size == POINT_BYTES && raw[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 1 + COORD_BYTES))
        val y = BigInteger(1, raw.copyOfRange(1 + COORD_BYTES, POINT_BYTES))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p) { "coordinate out of range" }
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(curve.a.multiply(x)).add(curve.b).mod(p)
        require(lhs == rhs) { "point is not on P-256" }
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("provider rejected the point", e)
        }
    }

    /** Segredo ECDH: a coordenada x compartilhada, sempre com 32 bytes. */
    fun ecdh(private: PrivateKey, public: PublicKey): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }
        return leftPad(secret)
    }

    private fun fixed(value: BigInteger): ByteArray = leftPad(value.toByteArray().let {
        if (it.size == COORD_BYTES + 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    })

    private fun leftPad(bytes: ByteArray): ByteArray {
        require(bytes.size <= COORD_BYTES) { "value longer than $COORD_BYTES bytes" }
        return ByteArray(COORD_BYTES - bytes.size) + bytes
    }
}
