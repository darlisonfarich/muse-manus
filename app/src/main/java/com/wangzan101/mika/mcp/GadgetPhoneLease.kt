package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.remote.RemoteOwnership
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The journal owner survives reconnects. The revocable lease belongs to exactly one Noise registration. */
internal class GadgetPhoneLease(device: String, authority: String, account: String, vm: String) {
    val owner = "gadget:" + RemoteOwnership.of(JsonArray(listOf(device, authority, account, vm).map(::JsonPrimitive)).toString())
    @Volatile var connected = false
    private val registration = java.util.UUID.randomUUID().toString()

    /** Replay/authorization scope of this one registration; null once it ended. */
    fun scope(): String? = registration.takeIf { connected }
}
