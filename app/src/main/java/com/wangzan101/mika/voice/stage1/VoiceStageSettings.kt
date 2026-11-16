package com.wangzan101.mika.voice.stage1

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.voiceStageDataStore by preferencesDataStore(name = "muse_voice_stage1")

class VoiceStageSettings(private val context: Context) {
    private val conversationKey = stringPreferencesKey("conversation_id")

    suspend fun conversationId(): String {
        var id = ""
        context.voiceStageDataStore.edit { values ->
            id = values[conversationKey] ?: UUID.randomUUID().toString().also { values[conversationKey] = it }
        }
        return id
    }

}
