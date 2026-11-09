package com.wangzan101.mika.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray

/** Journal-safe cause reference: the direct audit log stores no cause fields or UI-derived data. */
internal fun directPhoneGuardCauseDigest(result: JsonObject, digest: (String) -> String): String? =
    (result["guard"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("cause") }
        ?.takeIf { it.isNotEmpty() }?.let { causes -> digest(buildJsonArray { causes.forEach { add(it) } }.toString()) }
