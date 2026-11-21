package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.CommandSpec
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.darlisonfarich.gadgetlink.commands.PhoneCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only direct-screen tools. Task submission, credential entry and issuing approvals are separate local capabilities. */
internal object GadgetPhoneActions {
    private val actions = setOf("phone.open_app", "phone.tap", "phone.type_text", "phone.scroll", "phone.swipe",
        "phone.gesture", "phone.press_key", "phone.volume", "phone.media", "phone.press_back", "phone.run_batch")
    private val metadata = mapOf(
        "phone.status" to setOf("active", "writeEnabled", "busy", "sessionTtlMs", "pendingIntents", "launchInFlight", "launchClosePending"),
        "phone.reconcile" to setOf("kind", "journalState", "checkpointState", "executionUnknown", "retryable",
            "requiresFreshObservation", "total", "completed", "nextIndex", "nextJournalState"),
        "phone.list_apps" to setOf("apps"),
        "phone.run_batch" to setOf("completed"),
        "phone.cancel" to setOf("closed", "cancelledIntents", "closePending"),
    )

    /**
     * [scope] is the live registration (see [GadgetPhoneLease.scope]), deliberately not the session's read
     * scope: a write that already ran must never be reported as `unavailable` because a local task or a
     * helper restart retired the session meanwhile, and reconcile/status/cancel must keep answering
     * exactly then. The host's journal deduplicates writes by request_id across sessions.
     */
    fun specs(scope: () -> String?, call: suspend (String, JsonObject) -> JsonObject): List<CommandSpec> =
        PhoneCatalog.stubs().filter { it.name in actions || it.name in metadata }.map { stub ->
            CommandSpec(stub.name, stub.description.removePrefix(CommandSpec.STUB_PREFIX),
                stub.required, stub.optional, stub.timeoutMs, stub.writes, implemented = true,
                // Cancel ends the registration's pending work as its expected effect.
                replayScope = if (stub.name == "phone.cancel") null else scope,
            ) { args ->
                val raw = runBlocking { call(PhoneCatalog.MIKA.getValue(stub.name), args) }
                result(stub.name, raw)
            }
        }

    internal fun result(name: String, raw: JsonObject): CommandResult {
        if (raw["ok"] == JsonPrimitive(true)) {
            // Executor replies may include an inline tree, typed text or target labels.
            // Screen content is available only via the two checked passive reads.
            return CommandResult.Ok(JsonObject(raw.filterKeys { it == "ok" || it in metadata[name].orEmpty() }))
        }
        val failedStep = ((raw["results"] as? JsonArray)?.lastOrNull() as? JsonObject)
            ?.takeIf { it["ok"] != JsonPrimitive(true) }
        val error = (failedStep?.get("error") ?: raw["error"]) as? JsonPrimitive
        val cause = (raw["cause"] as? JsonPrimitive)?.content
        val reason = error?.content
        // Part of a batch already ran: whatever stopped the rest, Muse must reconcile instead of retrying.
        val completed = (raw["completed"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (completed > 0 || raw["executed"] == JsonPrimitive(true))
            return CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "reconcile_request_id")
        val gate = (failedStep ?: raw)["gateDenied"] == JsonPrimitive(true)
        return when {
            gate -> CommandResult.Err(ErrorCode.LOCAL_GATE_DENIED, reason?.takeIf { SAFE_CODE.matches(it) })
            reason == "app_in_use_by_user" || cause == "app_in_use_by_user" -> CommandResult.Err(ErrorCode.APP_IN_USE_BY_USER)
            reason in setOf("target_unresolved", "stale_ref", "stale_snapshot") -> CommandResult.Err(ErrorCode.STALE_SNAPSHOT)
            reason in setOf("needs_user", "payment_requires_takeover", "password_requires_takeover", "approval_unavailable",
                "confirmation_required", "approval_required", "own_app", "app_access_denied", "guard_warning", "guard_stopped",
                "remote_access_disabled", "write_disabled") -> CommandResult.Err(ErrorCode.LOCAL_GATE_DENIED, reason)
            reason in setOf("delivery_unknown", "launch_timeout_unknown", "reconciliation_required", "checkpoint_unavailable") ->
                CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "reconcile_request_id")
            reason == "wait_timeout" -> CommandResult.Err(ErrorCode.TIMEOUT)
            reason in setOf("phone_unavailable", "launch_in_flight", "cancelled", "intent_queue_full", "session_not_found") ->
                CommandResult.Err(ErrorCode.UNAVAILABLE)
            else -> CommandResult.Err(ErrorCode.EXECUTOR_ERROR)
        }
    }

    /** Fixed local refusal codes only; anything else is withheld from the detail. */
    private val SAFE_CODE = Regex("[a-z_]{1,40}")
}
