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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Quantas VMs utilizáveis a conta tem para este token, e o status HTTP (null = sem resposta). */
data class VmFetch(val count: Int, val status: Int?)

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

    fun fetchVms(accessToken: String, root: String, timeoutMs: Int = 15_000): VmFetch {
        val connection = try {
            URL(root + FETCH_PATH).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return VmFetch(0, null)
        }
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("X-API-Version", "1.0.0")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) return VmFetch(0, status)
            val body = connection.inputStream.use { it.readBytes() }
            val text = runCatching { Utf8.decodeStrict(body) }.getOrNull() ?: return VmFetch(0, status)
            VmFetch(countVms(text), status)
        } catch (e: IOException) {
            VmFetch(0, null)
        } finally {
            connection.disconnect()
        }
    }

    /** Mesmo filtro do SDK: objeto, sem error_title/backend_error_code, vm_list com url e token. */
    fun countVms(body: String): Int {
        val data = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return 0
        if (truthy(data["error_title"]) || truthy(data["backend_error_code"])) return 0
        val list = data["vm_list"] as? JsonArray ?: return 0
        return list.count { entry ->
            entry is JsonObject &&
                (truthy(entry["vm_ws_url"]) || truthy(entry["vm_url"])) &&
                truthy(entry["vm_auth_token"])
        }
    }

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
