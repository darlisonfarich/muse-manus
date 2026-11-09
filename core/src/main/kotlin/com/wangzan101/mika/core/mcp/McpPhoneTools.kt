package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Executed only after RemoteAccess authenticates the request and binds it to an owner. */
interface DirectPhoneHost {
    suspend fun call(owner: String, name: String, args: JsonObject): JsonObject
}

/**
 * Tools for a remote brain. The Android host owns the persistent ScreenPort / ActionExecutor.
 * These definitions grant no permissions, spawn no model and never execute a gesture themselves.
 */
class McpPhoneTools(private val host: DirectPhoneHost, private val owner: String) {
    private fun string(description: String, max: Int? = null): JsonObject = buildJsonObject {
        put("type", "string"); put("description", description)
        max?.let { put("maxLength", it) }
    }
    private fun integer(description: String, min: Int, max: Int): JsonObject = buildJsonObject {
        put("type", "integer"); put("minimum", min); put("maximum", max)
        put("description", description)
    }
    private fun bool(): JsonObject = buildJsonObject { put("type", "boolean") }
    private fun array(description: String, max: Int): JsonObject = buildJsonObject {
        put("type", "array"); put("description", description); put("maxItems", max)
        putJsonObject("items") { put("type", "object") }
    }
    private fun gesturePaths(): JsonObject = buildJsonObject {
        put("type", "array"); put("minItems", 1); put("maxItems", 2)
        putJsonObject("items") {
            put("type", "array"); put("minItems", 2); put("maxItems", 32)
            putJsonObject("items") {
                put("type", "array"); put("minItems", 2); put("maxItems", 2)
                putJsonObject("items") { put("type", "integer"); put("minimum", 0); put("maximum", 999) }
            }
        }
    }
    private fun schema(required: List<String> = emptyList(), vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, spec) -> put(name, spec) } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
    private val id = "request_id" to string("Unique UUID idempotency key for this action. Never reuse for a different operation.", 64)
    private val approval = "approval_token" to string("Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment.", 512)
    private fun obj(description: String): JsonObject = buildJsonObject {
        put("type", "object"); put("description", description)
    }
    private val ref = "ref" to string("Element identifier from phone_observe")
    private val sid = "snapshotId" to string("Snapshot identifier corresponding to ref")

    val tools: List<McpTaskTools.Tool> = listOf(
        McpTaskTools.Tool("phone_status", "Read session state and whether the local kill switch permits writes.", schema(), true),
        McpTaskTools.Tool("phone_get_state", "Alias for phone_status: session, write controls and current task contention.", schema(), true),
        McpTaskTools.Tool("phone_reconcile", "Read-only lookup of prior request_id in the owner's durable audit journal, or a phone_run checkpoint. Never executes or retries; use phone_status then fresh phone_observe.", schema(listOf("request_id", "kind"),
            "request_id" to id.second, "kind" to string("action or batch"),
            "commands" to array("For batch only: exact original ordered commands, compared by fingerprint; never executed.", 30)), true),
        McpTaskTools.Tool("phone_observe", "Read sanitized Android accessibility elements and current application. Password screens require the user.", schema(), true),
        McpTaskTools.Tool("phone_screenshot", "Read a protected-screen-aware screenshot, compressed to under 1 MiB; password screens are denied.", schema(), true),
        McpTaskTools.Tool("phone_list_apps", "List installed application package names and labels.", schema(), true),
        McpTaskTools.Tool("phone_approval_issue", "Mint a signed, single-use chat approval token (GER PR-6) bound to one exact phone action (tool + arguments). Redeemed by the action gate at execution; expires in minutes; never authorizes payment. Minting executes nothing.", schema(listOf("tool", "arguments"),
            "tool" to string("External phone action tool name, e.g. phone_tap"),
            "arguments" to obj("The exact arguments the action will be called with (without request_id).")), false),
        McpTaskTools.Tool("phone_launch_app", "Open an installed application in the agent's virtual display.", schema(listOf("package", "request_id"),
            "package" to string("Exact Android application package name"), "cold" to bool(), id, approval), false),
        McpTaskTools.Tool("phone_tap", "Tap an element ref/snapshot pair or normalized 0–999 coordinates. Consequential taps are denied by the action gate.", schema(listOf("request_id"),
            ref, sid, "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), "target" to string("Description of coordinate target", 80), "clicks" to integer("Tap count", 1, 2), id, approval), false),
        McpTaskTools.Tool("phone_type", "Type text in a non-secret field. Enter may submit content; the same local gate still applies.", schema(listOf("text", "request_id"),
            ref, sid, "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), "text" to string("Text to type", 10000),
            "pressEnter" to bool(), id, approval), false),
        McpTaskTools.Tool("phone_scroll", "Scroll the virtual screen in a chosen direction.", schema(listOf("direction", "request_id"),
            "direction" to string("up, down, left or right"), "amount" to integer("Amount 1–20 (default 3)", 1, 20),
            "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), id, approval), false),
        McpTaskTools.Tool("phone_swipe", "Swipe using 0–999 normalized endpoints.", schema(listOf("request_id"),
            "startX" to integer("Start X", 0, 999), "startY" to integer("Start Y", 0, 999),
            "endX" to integer("End X", 0, 999), "endY" to integer("End Y", 0, 999),
            "durationMs" to integer("Gesture duration ms", 50, 5000), id, approval), false),
        McpTaskTools.Tool("phone_gesture", "Perform a preplanned multipoint gesture with existing phone_gesture safety gates.", schema(listOf("paths", "request_id"),
            "paths" to gesturePaths(),
            "durationMs" to integer("Gesture duration ms", 50, 5000), id, approval), false),
        McpTaskTools.Tool("phone_key", "Press a key allowed by the agent virtual-display key policy. HOME, Recents, volume and media keys are scoped to this virtual display; Power remains blocked.", schema(listOf("key", "request_id"),
            "key" to string("Allowed KEYCODE_* name for the virtual session"), id, approval), false),
        McpTaskTools.Tool("phone_volume", "Adjust virtual-session volume by one scoped volume step.", schema(listOf("direction", "request_id"),
            "direction" to string("up, down or mute"), id, approval), false),
        McpTaskTools.Tool("phone_media", "Send a scoped media control key to the virtual session.", schema(listOf("action", "request_id"),
            "action" to string("play_pause, stop, next or previous"), id, approval), false),
        McpTaskTools.Tool("phone_back", "Go back in the virtual display.", schema(listOf("request_id"), id, approval), false),
        McpTaskTools.Tool("phone_run", "Run an ordered batch of at most 30 predefined atomic actions, verified individually. No autonomous decisions. A step's arguments may carry its own approval_token.", schema(listOf("commands", "request_id"),
            "commands" to array("Array of {name, arguments} (without individual request_id).", 30), id), false),
        McpTaskTools.Tool("phone_cancel", "End the owner's control session and release its virtual display.", schema(), false),
    )

    fun has(name: String): Boolean = tools.any { it.name == name }
    suspend fun call(name: String, args: JsonObject): JsonObject {
        if (!has(name)) throw McpTaskTools.ToolError("Unknown phone tool: $name")
        return host.call(owner, name, args)
    }
}
