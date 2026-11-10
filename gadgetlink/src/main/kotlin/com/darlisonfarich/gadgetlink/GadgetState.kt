// Identidade e estado do gadget no MESMO formato de arquivos do SDK oficial
// (musegadget/identity.py e config.py do facebookincubator/muse-gadget-sdk,
// Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0), para o
// próprio SDK poder ler este diretório como MUSEGADGET_STATE_DIR.
// Mudanças: Kotlin; arquivo ilegível é erro, não identidade nova; gravação atômica
// com java.nio (vale na JVM e no Android).
package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Identidade estável: um valor com cara de MAC, aleatório e "localmente
 * administrado", gerado uma vez. Não é o MAC real do aparelho. O node id e o nome
 * BLE terminam nos mesmos seis dígitos hex, como os apps Muse esperam.
 */
data class Identity(val mac: String) {
    init {
        require(isValidMac(mac)) { "invalid identity mac" }
    }

    val suffix: String get() = mac.replace(":", "").takeLast(6)
    val nodeId: String get() = NODE_ID_PREFIX + suffix
    val deviceId: String get() = "hatch-link:$mac"

    /** Sem separador: os apps comparam o texto depois do prefixo com o depois de "homelink-". */
    val bleName: String get() = BLE_NAME_PREFIX + suffix.uppercase()

    companion object {
        const val NODE_ID_PREFIX = "homelink-"
        const val BLE_NAME_PREFIX = "MuseGadget"
        private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        private val random = SecureRandom()

        fun isValidMac(text: String): Boolean = MAC.matches(text)

        fun generateMac(randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also(random::nextBytes) }): String {
            val octets = randomBytes(6).copyOf()
            require(octets.size == 6) { "random source returned ${octets.size} bytes" }
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, localmente administrado
            return octets.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

/** O diretório de estado, igual ao do SDK: identity.json, pairing.json e sdk_token. */
class GadgetState(val dir: File) {
    companion object {
        const val IDENTITY_FILE = "identity.json"
        const val PAIRING_FILE = "pairing.json"
        const val SDK_TOKEN_FILE = "sdk_token"

        /** mgst_ + 43 caracteres base64url canônicos, como o gadgets.muse.ai emite. */
        private val SDK_TOKEN = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
        private val pretty = Json { prettyPrint = true }

        fun isValidSdkToken(token: String): Boolean = SDK_TOKEN.matches(token)

        /** A parte do token que o gadgets.muse.ai mostra; "none" sem token. */
        fun sdkTokenHint(token: String?): String = token?.take(12) ?: "none"
    }

    /**
     * Lê um JSON objeto. Ausente, JSON quebrado ou não-objeto → null. Arquivo que
     * existe mas não pode ser lido → IOException (não é o mesmo que ausente: uma
     * identidade nova por cima de uma ilegível quebraria o pareamento salvo).
     */
    fun loadJson(name: String): JsonObject? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    /** Grava atômico: quem cair no meio deixa o arquivo velho ou o novo, nunca metade. */
    @Synchronized
    fun saveJson(name: String, data: JsonObject) = writeAtomic(name, pretty.encodeToString(JsonObject.serializer(), data) + "\n")

    @Synchronized
    fun delete(name: String) {
        File(dir, name).delete()
    }

    fun loadOrCreateIdentity(randomBytes: ((Int) -> ByteArray)? = null): Identity {
        val mac = (loadJson(IDENTITY_FILE)?.get("mac") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (mac != null && Identity.isValidMac(mac)) return Identity(mac)
        val identity = Identity(if (randomBytes != null) Identity.generateMac(randomBytes) else Identity.generateMac())
        saveJson(IDENTITY_FILE, buildJsonObject { put("mac", identity.mac) })
        return identity
    }

    /** O token do SDK, se houver. @throws IllegalArgumentException para um token que o site não emitiria. */
    fun sdkToken(): String? {
        val file = File(dir, SDK_TOKEN_FILE)
        if (!file.exists()) return null
        val token = file.readText(Charsets.UTF_8).trim()
        if (token.isEmpty()) return null
        require(isValidSdkToken(token)) { "the SDK token is not valid; copy it again from gadgets.muse.ai" }
        return token
    }

    @Synchronized
    fun saveSdkToken(token: String) {
        val trimmed = token.trim()
        require(isValidSdkToken(trimmed)) { "the SDK token is not valid; copy it again from gadgets.muse.ai" }
        writeAtomic(SDK_TOKEN_FILE, trimmed)
    }

    fun pairing(): JsonObject? = loadJson(PAIRING_FILE)

    private fun writeAtomic(name: String, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create state dir")
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                // só o dono lê (no Android o diretório do app já é privado; na JVM ajuda)
                tmp.setReadable(false, false); tmp.setReadable(true, true)
                tmp.setWritable(false, false); tmp.setWritable(true, true)
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }
}
