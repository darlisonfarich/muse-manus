// O fio do link (passo 4): a mensagem de controle, o decodificador delas, a URL do Noise e a
// descrição do device. Port de musegadget/link_client.py do facebookincubator/muse-gadget-sdk
// (Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin
// sobre kotlinx.serialization; o JSON sai como o json.dumps do Python (separadores compactos e
// ensure_ascii) para a codificação ser idêntica em bytes; o noise_host é validado como host:porta
// puro (a referência o concatena na URL sem checar, e ele vem do celular que pareou). Conformidade
// provada contra a referência (LinkWireTest + link_wire_vectors.json). Diferença conhecida: o
// json.loads do Python aceita NaN/Infinity e o parser daqui não; o Muse não os manda.
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.Utf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException

/** Como o device se descreve ao Muse no link.register. */
class DeviceDescription(
    val nodeId: String,
    val displayName: String,
    val version: String,
    /** commands_v2: o que o Muse pode pedir a este device. Vazio = só comunicador, nada executável. */
    val commands: JsonObject = JsonObject(emptyMap()),
    /** O SDK anuncia "linux"; ficam configuráveis para o ajuste que só o teste real mostra. */
    val platform: String = "linux",
    val modelId: String = "linux",
) {
    /** A família é SEMPRE homehub: nunca "link" nem o canal de OTA (docs/gadget-link.md). */
    fun registerParams(): JsonObject = JsonObject(
        linkedMapOf(
            "node_id" to JsonPrimitive(nodeId),
            "display_name" to JsonPrimitive(displayName),
            "platform" to JsonPrimitive(platform),
            "version" to JsonPrimitive(version),
            "device_family" to JsonPrimitive("homehub"),
            "model_id" to JsonPrimitive(modelId),
            "is_wakeup_supported" to JsonPrimitive(false),
            "commands_v2" to commands,
        ),
    )

    fun registerMessage(requestId: String): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("req"),
            "id" to JsonPrimitive(requestId),
            "method" to JsonPrimitive("link.register"),
            "params" to registerParams(),
        ),
    )
}

object LinkWire {
    const val NOISE_PATH = "/v1/noise"
    const val CONTROL_PATH = "/link-control"
    const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"

    /** Limite do Muse para uma mensagem de controle do device: 256 KiB, contando o prefixo de tamanho. */
    const val MAX_CONTROL_MESSAGE_BYTES = 256 * 1024
    const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024

    /** Igual ao encodeURIComponent do firmware. */
    private const val URI_COMPONENT_SAFE = "-_.!~*'()"

    private val json = Json

    /** u32 little-endian com o tamanho + JSON compacto em ASCII puro (tudo fora de 0x20..0x7E vira \uXXXX). */
    fun encodeMessage(obj: JsonObject): ByteArray {
        val text = json.encodeToString(JsonObject.serializer(), obj)
        val ascii = StringBuilder(text.length)
        for (c in text) {
            if (c.code in 0x20..0x7E) ascii.append(c) else ascii.append("\\u").append("%04x".format(c.code))
        }
        val data = ascii.toString().toByteArray(Charsets.US_ASCII)
        return byteArrayOf(
            data.size.toByte(), (data.size shr 8).toByte(), (data.size shr 16).toByte(), (data.size shr 24).toByte(),
        ) + data
    }

    private val HOST = Regex(
        "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*(?::([0-9]{1,5}))?$",
    )

    /** host ou host:porta, sem caminho, usuário, consulta nem fragmento. */
    fun requireHost(host: String): String {
        val m = HOST.matchEntire(host) ?: throw IllegalArgumentException("invalid noise host")
        m.groupValues[1].takeIf { it.isNotEmpty() }?.let { require(it.toInt() in 1..65535) { "invalid noise port" } }
        return host
    }

    fun noiseUrl(noiseHost: String, vmId: String, appId: String? = null): String {
        var url = "wss://${requireHost(noiseHost)}$NOISE_PATH?vm_id=${uriComponent(vmId, URI_COMPONENT_SAFE)}"
        if (appId != null) url += "&app_id=" + uriComponent(validateAppId(appId), "")
        return url
    }

    /** O app id é fixo por conexão: "hatch-web" ou um int64 positivo (zeros à esquerda saem). */
    fun validateAppId(appId: String): String {
        if (appId == "hatch-web") return appId
        val value = if (appId.isNotEmpty() && appId.length <= 19 && appId.all { it in '0'..'9' }) appId.toLongOrNull() else null
        require(value != null && value > 0) { "app ID must be a positive int64 or hatch-web" }
        return value.toString()
    }

    /** Percent-encoding dos bytes UTF-8, hexadecimal maiúsculo, mantendo letras, dígitos, "-_.~" e [safe]. */
    private fun uriComponent(text: String, safe: String): String {
        val out = StringBuilder()
        for (b in Utf8.encodeStrict(text)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == '-' || c == '~' || safe.indexOf(c) >= 0) {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}

/**
 * Corta o fluxo de controle em mensagens JSON com prefixo de tamanho (u32 little-endian). Uma mensagem
 * pode atravessar vários pedaços. Vazia = keepalive; JSON inválido ou que não é objeto é descartado;
 * tamanho acima de [LinkWire.MAX_INBOUND_MESSAGE] é erro e deixa o decoder quebrado.
 */
class MessageDecoder {
    private var buffer = ByteArray(0)
    private var broken = false

    @Synchronized fun feed(data: ByteArray): List<JsonObject> {
        if (broken) throw IllegalStateException("MessageDecoder: broken after an oversized message")
        buffer += data
        val messages = ArrayList<JsonObject>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val length = (buffer[offset].toLong() and 0xFF) or ((buffer[offset + 1].toLong() and 0xFF) shl 8) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 16) or ((buffer[offset + 3].toLong() and 0xFF) shl 24)
            if (length > LinkWire.MAX_INBOUND_MESSAGE) {
                broken = true
                throw IllegalArgumentException("inbound message too large: $length")
            }
            if (buffer.size - offset < 4 + length) break
            val raw = buffer.copyOfRange(offset + 4, offset + 4 + length.toInt())
            offset += 4 + length.toInt()
            if (raw.isEmpty()) continue // keepalive
            parse(raw)?.let { messages += it }
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return messages
    }

    private fun parse(raw: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(Utf8.decodeStrict(raw)) as? JsonObject
    } catch (e: CharacterCodingException) {
        null
    } catch (e: IllegalArgumentException) {
        null // JSON malformado (SerializationException é IllegalArgumentException)
    } catch (e: StackOverflowError) {
        null // aninhamento fundo demais, como o RecursionError do Python
    }
}
