package com.wangzan101.mika.voice.stage1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class VoiceTurnResponse(
    val conversationId: String,
    val turnId: String,
    val transcript: String,
    val replyText: String,
    val fallback: Boolean,
    val audioBase64: String,
    val timingsMs: JsonObject?,
) {
    companion object {
        fun parse(raw: String, expectedConversationId: String, expectedTurnId: String): VoiceTurnResponse {
            val obj = Json.parseToJsonElement(raw).jsonObject
            fun field(name: String) = obj[name]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Campo ausente: " + name)
            val conversation = field("conversation_id")
            val turn = field("turn_id")
            require(conversation == expectedConversationId && turn == expectedTurnId) { "IDs divergentes no gateway" }
            return VoiceTurnResponse(
                conversation, turn, field("transcript"), field("reply_text"),
                obj["fallback"]?.jsonPrimitive?.boolean
                    ?: throw IllegalArgumentException("Campo fallback ausente"),
                field("audio_base64").also { require(it.isNotBlank()) { "Áudio ausente" } },
                obj["timings_ms"] as? JsonObject,
            )
        }
    }
}
