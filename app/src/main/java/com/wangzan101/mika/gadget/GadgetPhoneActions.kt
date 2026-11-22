package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.CommandSpec
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.darlisonfarich.gadgetlink.commands.ParamSpec
import com.darlisonfarich.gadgetlink.commands.ParamType
import com.darlisonfarich.gadgetlink.commands.PhoneCatalog
import com.wangzan101.mika.mcp.DirectPhoneBatchSteps
import com.wangzan101.mika.mcp.DirectPhoneStaleRetry
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
     * The names a batch step may carry as announced in commands_v2, with the internal name the host runs. The host
     * only knows the internal ones, so a step is translated here exactly like the command itself is.
     */
    private val batchSteps: Map<String, String> = (actions - "phone.run_batch").associateWith { PhoneCatalog.MIKA.getValue(it) }

    private val openAppDescription = "Open an installed application. It opens in the agent's virtual display unless " +
        "target is \"main\": then it opens on the phone's main screen, in front of the user. The local app, stop and " +
        "write authorization applies to both."
    private val openAppTarget = ParamSpec(ParamType.STRING,
        "Display that receives the app: virtual (default, the agent's virtual display) or main (the phone's main screen, in front of the user).")

    /**
     * [scope] is the live registration (see [GadgetPhoneLease.scope]), deliberately not the session's read
     * scope: a write that already ran must never be reported as `unavailable` because a local task or a
     * helper restart retired the session meanwhile, and reconcile/status/cancel must keep answering
     * exactly then. The host's journal deduplicates writes by request_id across sessions.
     */
    fun specs(scope: () -> String?, call: suspend (String, JsonObject) -> JsonObject): List<CommandSpec> =
        PhoneCatalog.stubs().filter { it.name in actions || it.name in metadata }.map { stub ->
            val openApp = stub.name == "phone.open_app"
            CommandSpec(stub.name, if (openApp) openAppDescription else stub.description.removePrefix(CommandSpec.STUB_PREFIX),
                stub.required, if (openApp) stub.optional + ("target" to openAppTarget) else stub.optional,
                stub.timeoutMs, stub.writes, implemented = true,
                // Cancel ends the registration's pending work as its expected effect.
                replayScope = if (stub.name == "phone.cancel") null else scope,
            ) { args ->
                val raw = runBlocking { call(PhoneCatalog.MIKA.getValue(stub.name), translate(stub.name, args)) }
                result(stub.name, raw)
            }
        }

    /** A batch (and the reconcile of one) reaches the host with internal step names; other commands are untouched. */
    internal fun translate(name: String, args: JsonObject): JsonObject {
        if (name != "phone.run_batch" && name != "phone.reconcile") return args
        val commands = args["commands"] as? JsonArray ?: return args
        return JsonObject(args + ("commands" to DirectPhoneBatchSteps.toInternal(commands, batchSteps)))
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
            reason in setOf("target_unresolved", "stale_ref", "stale_snapshot") -> {
                // The host retries a stale read itself before giving up; the count tells how persistent it was.
                val retries = ((failedStep ?: raw)["staleRetries"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                CommandResult.Err(ErrorCode.STALE_SNAPSHOT, retries?.let(DirectPhoneStaleRetry::retriesDetail))
            }
            reason in setOf("needs_user", "payment_requires_takeover", "password_requires_takeover", "approval_unavailable",
                "confirmation_required", "approval_required", "own_app", "app_access_denied", "guard_warning", "guard_stopped",
                "remote_access_disabled", "write_disabled") -> CommandResult.Err(ErrorCode.LOCAL_GATE_DENIED, reason)
            reason in setOf("delivery_unknown", "launch_timeout_unknown", "reconciliation_required", "checkpoint_unavailable") ->
                CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "reconcile_request_id")
            reason == "wait_timeout" -> CommandResult.Err(ErrorCode.TIMEOUT)
            reason in setOf("phone_unavailable", "launch_in_flight", "cancelled", "intent_queue_full", "session_not_found") ->
                CommandResult.Err(ErrorCode.UNAVAILABLE)
            // Never mute: an internal code without a mapping (invalid_commands, request_id_required, ...) still
            // reaches the caller as the detail, as long as it is a plain fixed code and not screen content.
            else -> CommandResult.Err(ErrorCode.EXECUTOR_ERROR, reason?.takeIf { SAFE_CODE.matches(it) })
        }
    }

    /** Fixed local refusal codes only; anything else is withheld from the detail. */
    private val SAFE_CODE = Regex("[a-z_]{1,40}")
}
