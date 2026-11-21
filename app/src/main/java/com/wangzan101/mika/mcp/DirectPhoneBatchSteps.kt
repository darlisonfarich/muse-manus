package com.wangzan101.mika.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure helpers for the `commands` array of a phone batch (`phone_run` / `phone_reconcile` kind=batch). */
internal object DirectPhoneBatchSteps {
    /**
     * Rewrites every step named after a gadget command (`phone.tap`) to its internal name (`phone_tap`), the only
     * names the host runs; everything else, internal names included, is left as it is. The batch fingerprint is
     * taken over the result, so run and reconcile must both pass through here.
     */
    fun toInternal(commands: JsonArray, gadgetToInternal: Map<String, String>): JsonArray = JsonArray(commands.map { step ->
        val name = ((step as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val internal = name?.let(gadgetToInternal::get)
        if (step is JsonObject && internal != null) JsonObject(step + ("name" to JsonPrimitive(internal))) else step
    })

    /** Index of the first step that is not an object naming one of [allowed]; null when the whole batch is valid. */
    fun firstInvalid(commands: JsonArray, allowed: Set<String>): Int? {
        commands.forEachIndexed { index, step ->
            val name = ((step as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (name == null || name !in allowed) return index
        }
        return null
    }
}
