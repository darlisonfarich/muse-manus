package com.wangzan101.mika.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure formatter for durable GER audit completion; no global shortcut around action(). */
internal object DirectPhoneKeyAudit {
    fun completion(result: JsonObject): String? {
        val status = (result["keyDispatch"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (status !in setOf("dispatched", "not_applied", "effect_unavailable")) return null
        val applied = status == "dispatched" && result["ok"] == JsonPrimitive(true)
        fun safe(field: String): String = (result[field] as? JsonPrimitive)?.contentOrNull
            ?.take(110)?.replace(Regex("[^A-Za-z0-9_.:;=,/-]"), "_") ?: "unavailable"
        return (if (applied) "dispatched" else "not_applied") +
            "|dispatch=$status|before=${safe("keyBefore")}|after=${safe("keyAfter")}"
    }
}
