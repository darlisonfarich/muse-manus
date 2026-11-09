package com.wangzan101.mika.voice.stage1

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.voiceStageDataStore by preferencesDataStore(name = "muse_voice_stage1")

class VoiceStageSettings(private val context: Context) {
    private val urlKey = stringPreferencesKey("gateway_url")
    private val conversationKey = stringPreferencesKey("conversation_id")

    suspend fun gatewayUrl() = context.voiceStageDataStore.data.first()[urlKey] ?: DEFAULT_GATEWAY_URL

    suspend fun saveGatewayUrl(value: String) {
        context.voiceStageDataStore.edit { it[urlKey] = value.trim().trimEnd('/') }
    }

    suspend fun conversationId(): String {
        var id = ""
        context.voiceStageDataStore.edit { values ->
            id = values[conversationKey] ?: UUID.randomUUID().toString().also { values[conversationKey] = it }
        }
        return id
    }

    companion object { const val DEFAULT_GATEWAY_URL = "https://7e8dc7b65be8f5.lhr.life" }
}
