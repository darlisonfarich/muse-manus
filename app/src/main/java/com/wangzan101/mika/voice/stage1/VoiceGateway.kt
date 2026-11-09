package com.wangzan101.mika.voice.stage1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Isolated from the phone-control bridge; never fabricates a response. */
class VoiceGateway {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun send(baseUrl: String, wav: ByteArray, conversationId: String, turnId: String): VoiceTurnResponse =
        withContext(Dispatchers.IO) {
            val root = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("URL inválida")
            require(root.scheme == "https" && root.username.isEmpty() && root.password.isEmpty()
                && root.query == null && root.fragment == null) { "Use um gateway HTTPS válido" }
            val endpoint = root.newBuilder().addPathSegments("v1/voice/turn").build()
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("conversation_id", conversationId)
                .addFormDataPart("turn_id", turnId)
                .addFormDataPart("audio", "voice.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            client.newCall(Request.Builder().url(endpoint).post(form).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Gateway HTTP " + r.code)
                val payload = r.body?.string() ?: throw IOException("Gateway sem resposta")
                VoiceTurnResponse.parse(payload, conversationId, turnId)
            }
        }
}
