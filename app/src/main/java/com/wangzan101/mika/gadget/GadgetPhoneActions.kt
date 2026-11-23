package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.CommandSpec
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.darlisonfarich.gadgetlink.commands.ParamSpec
import com.darlisonfarich.gadgetlink.commands.ParamType
import com.darlisonfarich.gadgetlink.commands.PhoneCatalog
import com.wangzan101.mika.core.shell.BackgroundSurvival
import com.wangzan101.mika.core.shell.PhoneState
import com.wangzan101.mika.mcp.DirectPhoneBatchSteps
import com.wangzan101.mika.mcp.DirectPhoneStaleRetry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only direct-screen tools. Task submission, credential entry and issuing approvals are separate local capabilities. */
internal object GadgetPhoneActions {
    private val deviceActions = setOf("phone.wake", "phone.unlock")
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
    fun specs(
        scope: () -> String?,
        /** Where [failureLine] goes: the link's own log. The wire audit never carries a detail, so this line is the only place a failure keeps both the wire code and the host's raw reason. */
        note: (String) -> Unit = {},
        deviceCall: suspend (String) -> JsonObject = { JsonObject(mapOf("status" to JsonPrimitive("device_unavailable"))) },
        call: suspend (String, JsonObject) -> JsonObject,
    ): List<CommandSpec> =
        PhoneCatalog.stubs().filter { it.name in actions || it.name in metadata || it.name in deviceActions }.map { stub ->
            val openApp = stub.name == "phone.open_app"
            CommandSpec(stub.name, if (openApp) openAppDescription else stub.description.removePrefix(CommandSpec.STUB_PREFIX),
                stub.required, if (openApp) stub.optional + ("target" to openAppTarget) else stub.optional,
                stub.timeoutMs, stub.writes, implemented = true,
                // Cancel ends the registration's pending work as its expected effect.
                replayScope = if (stub.name == "phone.cancel") null else scope,
            ) { args ->
                val raw = runBlocking {
                    if (stub.name in deviceActions) deviceCall(stub.name)
                    else call(PhoneCatalog.MIKA.getValue(stub.name), translate(stub.name, args))
                }
                val outcome = if (stub.name in deviceActions) deviceResult(stub.name, raw) else result(stub.name, raw)
                outcome.also { r -> if (r is CommandResult.Err) runCatching { note(failureLine(stub.name, r, raw)) } }
            }
        }

    private fun deviceResult(name: String, raw: JsonObject): CommandResult {
        val status = (raw["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "device_unavailable"
        val success = if (name == "phone.wake") status == "awake" else status == "unlocked"
        return if (success) CommandResult.Ok(JsonObject(mapOf("ok" to JsonPrimitive(true), "status" to JsonPrimitive(status))))
        else CommandResult.Err(ErrorCode.UNAVAILABLE, deviceUnavailableReason(status))
    }

    internal fun deviceUnavailableReason(status: String): String = when (status) {
        "helper_unavailable" -> "helper_required_for_credential_unlock"
        "disabled" -> "device_access_disabled"
        "first_unlock_required" -> "first_unlock_required"
        "attempt_blocked" -> "credential_attempt_blocked"
        "credential_required" -> "credential_not_configured"
        "credential_unavailable" -> "credential_unavailable"
        "unsupported_keyguard" -> "unsupported_keyguard"
        "credential_kind_mismatch" -> "credential_kind_mismatch"
        "wake_failed" -> "wake_failed"
        "dismiss_failed" -> "keyguard_dismiss_failed"
        "input_failed" -> "credential_entry_failed"
        "unlock_failed" -> "unlock_failed"
        else -> "device_unavailable"
    }

    /**
     * One log line for a failed phone command: the code and detail that go on the wire, then the host's raw reason and
     * cause, which the wire detail may have folded away (`launch_timeout_unknown` + `start_app_timeout` leave as the
     * bare `reconcile_request_id`), and, for an open that did not reach the front, what was in front and whether the
     * background-launch token was present. Fixed vocabulary only: every value is a plain code or a package name, never
     * screen content.
     */
    internal fun failureLine(name: String, err: CommandResult.Err, raw: JsonObject): String {
        val failedStep = ((raw["results"] as? JsonArray)?.lastOrNull() as? JsonObject)?.takeIf { it["ok"] != JsonPrimitive(true) }
        val source = failedStep ?: raw
        fun word(key: String) = (source[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { SAFE_CODE.matches(it) }
        val message = (source["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val front = FRONT_PACKAGE.find(message)?.groupValues?.get(1)
        return buildString {
            append("phone: ").append(name).append(' ').append(err.code.wire)
            err.detail?.takeIf { SAFE_CODE.matches(it) }?.let { append(" detail=").append(it) }
            word("error")?.let { append(" raw=").append(it) }
            word("cause")?.let { append(" cause=").append(it) }
            if ("Nothing came to the foreground" in message) append(" front=none")
            else if (front != null) append(" front=").append(front)
            if (BackgroundSurvival.BACKGROUND_LAUNCH_TOKEN in message) append(" bal=1")
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
            // The phone is locked or dark (or accessibility is off): name it instead of the bare "needs the user".
            reason == "needs_user" && cause in PHONE_STATES -> CommandResult.Err(ErrorCode.LOCAL_GATE_DENIED, cause)
            reason in setOf("needs_user", "payment_requires_takeover", "password_requires_takeover", "approval_unavailable",
                "confirmation_required", "approval_required", "own_app", "app_access_denied", "guard_warning", "guard_stopped",
                "remote_access_disabled", "write_disabled") -> CommandResult.Err(ErrorCode.LOCAL_GATE_DENIED, reason)
            reason in setOf("delivery_unknown", "launch_timeout_unknown", "reconciliation_required", "checkpoint_unavailable") ->
                CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "reconcile_request_id")
            // Never started because the phone had no session for the command in time: not a slow command, a missing
            // precondition. Say which one (the last blocker) instead of a bare timeout.
            reason == "wait_timeout" && cause == SESSION_PENDING -> CommandResult.Err(ErrorCode.UNAVAILABLE, SESSION_PENDING)
            // The wait ended on a resource that never freed (task_running, host_busy, slot_budget_exhausted): still a
            // timeout, but one that says what held it. Nothing was executed, so the detail is safe to name.
            reason == "wait_timeout" -> CommandResult.Err(ErrorCode.TIMEOUT, cause?.takeIf { SAFE_CODE.matches(it) })
            // No app-only route for this key and no helper running.
            reason == "helper_required" -> CommandResult.Err(ErrorCode.UNAVAILABLE, "helper_required")
            // An opening that did not reach the virtual screen (cold start: no display, no owner, cold service). The
            // phone did not run anything, so this is a missing precondition, never a bare executor error. When Mika had
            // no visible window Android blocks the start without throwing, and the host's hint carries that token.
            reason in OPEN_NOT_REACHED -> CommandResult.Err(ErrorCode.UNAVAILABLE, openNotReachedDetail(reason!!, cause))
            // The host names why it is unavailable (accessibility_off, gadget_link_down, ...) when it knows.
            reason in setOf("phone_unavailable", "launch_in_flight", "cancelled", "intent_queue_full", "session_not_found") ->
                CommandResult.Err(ErrorCode.UNAVAILABLE, cause?.takeIf { SAFE_CODE.matches(it) })
            // Never mute: an internal code without a mapping (invalid_commands, request_id_required, ...) still
            // reaches the caller as the detail, as long as it is a plain fixed code and not screen content.
            else -> CommandResult.Err(ErrorCode.EXECUTOR_ERROR, reason?.takeIf { SAFE_CODE.matches(it) })
        }
    }

    private const val SESSION_PENDING = "phone_session_pending"

    /** Host codes for an opening that was started but never arrived on Mika's virtual screen. */
    private val OPEN_NOT_REACHED = setOf("app_not_in_front", "launch_failed")

    /** `background_launch_blocked` when the host's own hint says Android blocked a start with no visible window; else the host code. */
    internal fun openNotReachedDetail(reason: String, cause: String?): String =
        if (cause?.contains(BackgroundSurvival.BACKGROUND_LAUNCH_TOKEN) == true) BackgroundSurvival.BACKGROUND_LAUNCH_TOKEN else reason

    private val PHONE_STATES = setOf(PhoneState.DEVICE_LOCKED, PhoneState.SCREEN_OFF, PhoneState.ACCESSIBILITY_OFF)

    /** Fixed local refusal codes only; anything else is withheld from the detail. */
    private val SAFE_CODE = Regex("[a-z_]{1,40}")

    /** The package of the window the open found in front (see `AndroidScreen.awaitOpened`); a package name, not screen content. */
    private val FRONT_PACKAGE = Regex("In the foreground now: ([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)")
}
