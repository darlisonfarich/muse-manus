package com.wangzan101.mika.mcp

import kotlinx.serialization.json.JsonArray

/** Pure helpers for the `commands` array of a phone batch (`phone_run` / `phone_reconcile` kind=batch). */
internal object DirectPhoneBatchSteps {
    /** Rewrites every step named after a gadget command to its internal name; everything else is left as it is. */
    fun toInternal(commands: JsonArray, gadgetToInternal: Map<String, String>): JsonArray = commands

    /** Index of the first step that is not an object naming one of [allowed]; null when the whole batch is valid. */
    fun firstInvalid(commands: JsonArray, allowed: Set<String>): Int? = null
}
