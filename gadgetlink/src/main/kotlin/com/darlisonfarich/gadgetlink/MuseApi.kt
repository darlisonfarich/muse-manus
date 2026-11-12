// Checagem do token do device e gravação do pareamento. Port de fetch_vms_with_status
// (musegadget/muse_api.py) e _verify_and_save (musegadget/pair.py) do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and
// affiliates; Apache License 2.0). Mudanças: Kotlin sobre HttpURLConnection; não
// segue redirect (o bearer não vai para outro host); falha de gravação vira
// error_storage em vez de derrubar a thread.
package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Quantas VMs utilizáveis a conta tem para este token, e o status HTTP (null = sem resposta). */
data class VmFetch(val count: Int, val status: Int?)

/** Uma VM arrendada à conta: a URL, o bearer por-VM e como chamá-la. */
data class Vm(val vmUrl: String, val vmAuthToken: String, val vmName: String, val vmId: String, val isDefault: Boolean)

data class VmList(val vms: List<Vm>, val status: Int?)

data class TokenPair(val accessToken: String, val refreshToken: String)

/** tokens = null em qualquer falha; status = o HTTP que encerrou a tentativa (null = falha de rede). */
data class RefreshResult(val tokens: TokenPair?, val status: Int?)

object MuseApi {
    const val API_BASE = "https://api.muse.ai"
    const val FETCH_PATH = "/fetch_vms"
    const val USER_AGENT = "muse-manus-gadgetlink/1"

    /** api_url_v2, ou a API do Muse. O api_url antigo é ignorado (só firmware velho o lê). */
    fun apiRoot(apiUrlV2: String): String = apiUrlV2.ifEmpty { API_BASE }.trimEnd('/')

    /**
     * URL de API utilizável: https, com host e SEM userinfo. Sem a checagem de
     * userinfo, "https://api.muse.ai@outro-host/" passaria no startsWith e o
     * bearer do device iria para o outro host (o URL do Java aceita a forma).
     */
    fun isUsableApiUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.userInfo == null && !parsed.host.isNullOrEmpty()
    }

    /**
     * Teto do corpo de /fetch_vms. O api_url_v2 vem do celular que está pareando, então
     * o host do outro lado não é de confiança: sem teto, uma resposta gigante estouraria
     * a memória do app. Uma lista real de VMs tem poucos KB.
     */
    const val MAX_BODY_BYTES = 1 shl 20

    /** Prazo total da checagem (conexão + corpo): um servidor que pinga 1 byte por vez não prende a thread. */
    const val TOTAL_TIMEOUT_MS = 20_000

    const val REFRESH_PATH = "/device_token/refresh"

    private class Reply(val status: Int?, val body: ByteArray?)

    /**
     * Uma chamada HTTP: sem seguir redirect (o bearer, e mais ainda o refresh token, não vai para outro
     * host), com teto no corpo e prazo total. status null = sem resposta; body null = não 2xx, estourou o
     * teto ou o prazo.
     */
    private fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: ByteArray?,
        timeoutMs: Int,
        totalTimeoutMs: Int,
    ): Reply {
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000L
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Reply(null, null)
        }
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status !in 200..299) return Reply(status, null)
            Reply(status, connection.inputStream.use { readBounded(it, MAX_BODY_BYTES, deadline) })
        } catch (e: IOException) {
            Reply(null, null)
        } finally {
            connection.disconnect()
        }
    }

    /** As VMs utilizáveis da conta para este token do device, e o status HTTP (null = sem resposta). */
    fun fetchVmList(
        accessToken: String,
        root: String,
        timeoutMs: Int = 15_000,
        totalTimeoutMs: Int = TOTAL_TIMEOUT_MS,
    ): VmList {
        val reply = call(
            "GET", root + FETCH_PATH,
            mapOf("Authorization" to "Bearer $accessToken", "X-API-Version" to "1.0.0", "User-Agent" to USER_AGENT),
            null, timeoutMs, totalTimeoutMs,
        )
        val status = reply.status ?: return VmList(emptyList(), null)
        val body = reply.body ?: return VmList(emptyList(), status)
        val text = runCatching { Utf8.decodeStrict(body) }.getOrNull() ?: return VmList(emptyList(), status)
        return VmList(parseVmList(text), status)
    }

    fun fetchVms(
        accessToken: String,
        root: String,
        timeoutMs: Int = 15_000,
        totalTimeoutMs: Int = TOTAL_TIMEOUT_MS,
    ): VmFetch = fetchVmList(accessToken, root, timeoutMs, totalTimeoutMs).let { VmFetch(it.vms.size, it.status) }

    /**
     * Gira o par de tokens do device com o refresh token. O access token nunca é apresentado (como no
     * ESP32: o servidor pode aceitá-lo e devolver tokens que depois todo endpoint recusa). O prefixo
     * hatch_refresh: nunca é dobrado. 401 = o pareamento acabou, é preciso parear de novo.
     */
    fun refreshDeviceToken(
        refreshToken: String,
        deviceId: String,
        root: String,
        sdkToken: String? = null,
        timeoutMs: Int = 15_000,
        totalTimeoutMs: Int = TOTAL_TIMEOUT_MS,
    ): RefreshResult {
        val raw = refreshToken.substringAfterLast(':')
        val payload = buildJsonObject {
            put("device_id", deviceId)
            if (!sdkToken.isNullOrEmpty()) put("sdk_token", sdkToken)
        }.toString().toByteArray(Charsets.UTF_8)
        val reply = call(
            "POST", root + REFRESH_PATH,
            mapOf("Authorization" to "Bearer hatch_refresh:$raw", "Content-Type" to "application/json", "User-Agent" to USER_AGENT),
            payload, timeoutMs, totalTimeoutMs,
        )
        val status = reply.status ?: return RefreshResult(null, null)
        val body = reply.body ?: return RefreshResult(null, status)
        val text = runCatching { Utf8.decodeStrict(body) }.getOrNull() ?: return RefreshResult(null, status)
        var data = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return RefreshResult(null, status)
        (data["payload"] as? JsonObject)?.let { data = it }
        val access = (data["access_token"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        val refresh = (data["refresh_token"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        return RefreshResult(if (access != null && refresh != null) TokenPair(access, refresh) else null, status)
    }

    /** Lê até [maxBytes] antes do [deadline] (System.nanoTime); null se passou de um dos dois. */
    private fun readBounded(input: InputStream, maxBytes: Int, deadline: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (System.nanoTime() - deadline > 0) return null
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
        }
    }

    fun countVms(body: String): Int = parseVmList(body).size

    /** Mesmo filtro do SDK: objeto, sem error_title/backend_error_code, vm_list com (vm_ws_url ou vm_url) e token. */
    fun parseVmList(body: String): List<Vm> {
        val data = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        if (truthy(data["error_title"]) || truthy(data["backend_error_code"])) return emptyList()
        val list = data["vm_list"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            if (entry !is JsonObject) return@mapNotNull null
            val ws = entry["vm_ws_url"]
            val url = if (truthy(ws)) ws else entry["vm_url"]
            val token = entry["vm_auth_token"]
            if (!truthy(url) || !truthy(token)) return@mapNotNull null
            Vm(
                vmUrl = textOf(url), vmAuthToken = textOf(token), vmName = textOf(entry["vm_name"]),
                vmId = textOf(entry["vm_id"]), isDefault = truthy(entry["default"]),
            )
        }
    }

    private fun textOf(element: JsonElement?): String = (element as? JsonPrimitive)?.content ?: ""

    /** Verdade no sentido do Python: null, "", 0, false, [] e {} são falsos. */
    private fun truthy(element: JsonElement?): Boolean = when (element) {
        null, JsonNull -> false
        is JsonArray -> element.isNotEmpty()
        is JsonObject -> element.isNotEmpty()
        is JsonPrimitive -> when {
            element.isString -> element.content.isNotEmpty()
            element.booleanOrNull != null -> element.booleanOrNull!!
            else -> element.doubleOrNull?.let { it != 0.0 } ?: true
        }
    }
}

/**
 * Faz o provisionamento: confere o token do device na API do Muse e grava o
 * pairing.json no formato do SDK — só dentro do commit da sessão de pareamento,
 * que garante que a sessão ainda é a atual.
 */
class Provisioner(
    private val state: GadgetState,
    private val sdkToken: String?,
    private val fetch: (accessToken: String, root: String) -> VmFetch = { token, root -> MuseApi.fetchVms(token, root) },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun provision(credentials: Credentials, commit: Commit) {
        val apiUrl = credentials.apiUrl.takeIf { MuseApi.isUsableApiUrl(it) } ?: ""
        val apiUrlV2 = credentials.apiUrlV2.takeIf { MuseApi.isUsableApiUrl(it) } ?: ""
        val vms = fetch(credentials.accessToken, MuseApi.apiRoot(apiUrlV2))
        if (vms.count == 0) throw ProvisionFailed("auth_failed")

        val record = buildJsonObject {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
            put("token_type", "device")
            put("username", credentials.username)
            put("api_url", apiUrl)
            put("api_url_v2", apiUrlV2)
            put("noise_host", credentials.noiseHost)
            put("access_token_saved_at", nowSeconds())
            put("sdk_token_hint", GadgetState.sdkTokenHint(sdkToken))
        }
        val saved = commit {
            try {
                state.saveJson(GadgetState.PAIRING_FILE, record)
                true
            } catch (e: IOException) {
                false
            }
        }
        if (!saved) throw ProvisionFailed("error_storage")
    }
}
