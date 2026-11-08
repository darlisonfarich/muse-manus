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
    private val ref = "ref" to string("Element identifier from phone_observe")
    private val sid = "snapshotId" to string("Snapshot identifier corresponding to ref")

    val tools: List<McpTaskTools.Tool> = listOf(
        McpTaskTools.Tool("phone_status", "Read session state and whether the local kill switch permits writes.", schema(), true),
        McpTaskTools.Tool("phone_get_state", "Alias for phone_status: session, write controls and current task contention.", schema(), true),
        McpTaskTools.Tool("phone_observe", "Read sanitized Android accessibility elements and current application. Password screens require the user.", schema(), true),
        McpTaskTools.Tool("phone_screenshot", "Read a protected-screen-aware screenshot, compressed to under 1 MiB; password screens are denied.", schema(), true),
        McpTaskTools.Tool("phone_list_apps", "List installed application package names and labels.", schema(), true),
        McpTaskTools.Tool("phone_launch_app", "Open an installed application in the agent's virtual display.", schema(listOf("package", "request_id"),
            "package" to string("Exact Android application package name"), "cold" to bool(), id), false),
        McpTaskTools.Tool("phone_tap", "Tap an element ref/snapshot pair or normalized 0–999 coordinates. Consequential taps are denied by the action gate.", schema(listOf("request_id"),
            ref, sid, "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), "target" to string("Description of coordinate target", 80), "clicks" to integer("Tap count", 1, 2), id), false),
        McpTaskTools.Tool("phone_type", "Type text in a non-secret field. Enter may submit content; the same local gate still applies.", schema(listOf("text", "request_id"),
            ref, sid, "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), "text" to string("Text to type", 10000),
            "pressEnter" to bool(), id), false),
        McpTaskTools.Tool("phone_scroll", "Scroll the virtual screen in a chosen direction.", schema(listOf("direction", "request_id"),
            "direction" to string("up, down, left or right"), "amount" to integer("Amount 1–20 (default 3)", 1, 20),
            "x" to integer("Normalized X", 0, 999), "y" to integer("Normalized Y", 0, 999), id), false),
        McpTaskTools.Tool("phone_swipe", "Swipe using 0–999 normalized endpoints.", schema(listOf("request_id"),
            "startX" to integer("Start X", 0, 999), "startY" to integer("Start Y", 0, 999),
            "endX" to integer("End X", 0, 999), "endY" to integer("End Y", 0, 999),
            "durationMs" to integer("Gesture duration ms", 50, 5000), id), false),
        McpTaskTools.Tool("phone_gesture", "Perform a preplanned multipoint gesture with existing phone_gesture safety gates.", schema(listOf("paths", "request_id"),
            "paths" to gesturePaths(),
            "durationMs" to integer("Gesture duration ms", 50, 5000), id), false),
        McpTaskTools.Tool("phone_key", "Press an allowed navigation/editing key. System-wide HOME/POWER/VOLUME/RECENTS remain blocked by the shared shell allowlist.", schema(listOf("key", "request_id"),
            "key" to string("Allowed KEYCODE_* name"), id), false),
        McpTaskTools.Tool("phone_back", "Go back in the virtual display.", schema(listOf("request_id"), id), false),
        McpTaskTools.Tool("phone_run", "Run an ordered batch of at most 30 predefined atomic actions, verified individually. No autonomous decisions.", schema(listOf("commands", "request_id"),
            "commands" to array("Array of {name, arguments} (without individual request_id).", 30), id), false),
        McpTaskTools.Tool("phone_cancel", "End the owner's control session and release its virtual display.", schema(), false),
    )

    fun has(name: String): Boolean = tools.any { it.name == name }
    suspend fun call(name: String, args: JsonObject): JsonObject {
        if (!has(name)) throw McpTaskTools.ToolError("Unknown phone tool: $name")
        return host.call(owner, name, args)
    }
}
