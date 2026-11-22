package com.wangzan101.mika.mcp

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.VirtualScreenshot
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ChatApprovalChannel
import com.wangzan101.mika.core.gate.ChatApprovalSlot
import com.wangzan101.mika.core.gate.ChatApprovalTokens
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.mcp.DirectPhoneHost
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.bindPhoneTarget
import com.wangzan101.mika.core.uistate.ScreenJson
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.task.GerIntentAttempt
import com.wangzan101.mika.task.GerIntentOutcome
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One persistent virtual-screen session for one authenticated MCP owner. No model
 * is instantiated: an external brain supplies atomic actions, ActionExecutor checks
 * the same gates as local tasks, and the Android port verifies their effects.
 */
class AndroidDirectPhoneHost(private val context: Context, private val settings: McpSettings) : DirectPhoneHost {
    private val app get() = context.applicationContext as MikaApp
    private val lock = Mutex()
    private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launchBoundary = DirectPhoneLaunchBoundary(launchScope, 10_000L, ::onLaunchSettled)
    private val pendingClose = mutableSetOf<String>()
    @Volatile private var closeAfterLaunch = false
    private val journal = File(context.filesDir, "direct-phone-audit.jsonl")
    // GER PR-6: chat approval tokens (Keystore-held HMAC key + fsync'd consumed-id ledger).
    private val chatApprovalStore by lazy { ChatApprovalTokenStore(context.filesDir) }
    private val chatApprovalSlot = ChatApprovalSlot()
    // Append-only fsync’d batch progress; the audit remains the replay gate.
    private val checkpoints = DirectPhoneBatchCheckpoints(File(context.filesDir, "direct-phone-batches.log"))
    private val seen = HashSet<String>()
    private var loaded = false
    private val sessions = ConcurrentHashMap<String, Session>()
    private var step = 0
    private val ttlMs get() = app.resources.ger.budgets.sessionTtlMs

    private class Session(
        val owner: String,
        val binding: ShellBinding,
        val link: GadgetPhoneLease?,
        val sourcesCurrent: () -> Boolean,
        val androidScreen: () -> AndroidScreen?,
        val display: LazyAgentDisplay,
        val screen: ScreenPort,
        val tools: ToolDispatcher,
        val executor: ActionExecutor,
        val captureVirtualPng: suspend () -> VirtualScreenshot,
        val releaseBinding: () -> Unit,
        @Volatile var lastUsed: Long,
    ) {
        val readScope = UUID.randomUUID().toString()
        var passiveTarget: PassiveTarget? = null

        fun close() { try { display.current?.release() } finally { releaseBinding() } }
    }

    private data class PassiveTarget(val id: String, val fingerprint: String, val generation: Long)

    private fun treeFingerprint(tree: JsonObject) =
        digest(JsonObject(tree.filterKeys { it != "snapshotId" && it != "tree" }).toString())

    suspend fun close() {
        // A stopped listener cancels queued calls, but an already issued Binder
        // start cannot safely be interrupted or have its display torn down.
        app.resources.ger.intentions.cancelMatching { it !in gadgetOwners }
        lock.withLock {
            sessions.values.filter { it.link == null }.forEach { retire(it.owner) }
        }
    }

    private fun retire(owner: String) {
        if (launchBoundary.isInFlight()) {
            pendingClose.add(owner)
            closeAfterLaunch = true
        } else closeActive(owner)
    }

    private fun onLaunchSettled() {
        launchScope.launch {
            lock.withLock {
                if (!launchBoundary.isInFlight()) {
                    pendingClose.forEach(::closeActive)
                    pendingClose.clear()
                    closeAfterLaunch = false
                }
            }
        }
    }

    private fun directOwner(owner: String): String = "mcp:" + digest(owner)

    private fun closeActive(owner: String) {
        val session = sessions[owner] ?: return
        sessions.remove(session.owner)
        val key = directOwner(session.owner)
        try {
            session.close()
        } finally {
            // Release every package held by this screen before revoking its GER lease.
            gadgetSlots.remove(key)
            app.resources.releaseOwner(key)
            app.resources.ger.releaseDirect(key)
            app.resources.ger.releaseSlot(key)
        }
    }

    private val gadgetSlots = ConcurrentHashMap.newKeySet<String>()
    /** Queue keys of connected gadget registrations, held or not: stopping the MCP bridge never cancels them. */
    private val gadgetOwners = ConcurrentHashMap.newKeySet<String>()

    private fun allowed(link: GadgetPhoneLease) = link.connected && settings.enabled.value &&
        !FeatureFlags.get(context).quitRequested()

    private fun gadgetSession(link: GadgetPhoneLease): Session? =
        sessions[link.owner]?.takeIf {
            it.link === link && allowed(link) && it.sourcesCurrent() && !launchBoundary.isInFlight() &&
                !closeAfterLaunch && app.resources.ger.canUseSlot(directOwner(link.owner))
        }

    internal suspend fun maintainGadget(link: GadgetPhoneLease): Boolean = lock.withLock {
        val old = sessions[link.owner]
        if (link.connected) gadgetOwners.add(directOwner(link.owner))
        if (!allowed(link) || ScreenAccessibility.instance.value == null ||
            PhoneServices.of(context).hub.shell.value == null) {
            if (old?.link === link) retire(link.owner)
            return@withLock false
        }
        if (launchBoundary.isInFlight() || closeAfterLaunch) return@withLock false
        // A local task takes precedence: hand back the gadget's slot, display and app reservations
        // instead of parking on them. The next maintenance pass after the task builds a fresh session
        // (which also requires a fresh passive read before ref-based writes).
        if (busy()) {
            if (old?.link === link) retire(link.owner)
            return@withLock false
        }
        val key = directOwner(link.owner)
        if (old != null && (old.link !== link || !old.sourcesCurrent())) closeActive(link.owner)
        if (!app.resources.ger.claimSlot(key).admitted) return@withLock false
        gadgetSlots.add(key)
        try {
            val now = System.currentTimeMillis()
            val session = sessions[link.owner] ?: create(link.owner, now, link).also { sessions[link.owner] = it }
            session.lastUsed = now
            gadgetSession(link) != null
        } finally {
            if (!sessions.containsKey(link.owner)) {
                gadgetSlots.remove(key)
                app.resources.ger.releaseSlot(key)
            }
        }
    }

    internal suspend fun releaseGadget(link: GadgetPhoneLease) = lock.withLock {
        if (sessions[link.owner]?.link === link || sessions[link.owner] == null) gadgetOwners.remove(directOwner(link.owner))
        if (sessions[link.owner]?.link === link) {
            app.resources.ger.intentions.cancelOwner(directOwner(link.owner))
            retire(link.owner)
        }
    }

    internal fun gadgetReadScope(link: GadgetPhoneLease): String? {
        val session = gadgetSession(link) ?: return null
        val access = app.appAccess.current()
        return "${session.readScope}:${access.revision}:${access.available}:${app.appAccess.sensitiveUi}"
    }

    /**
     * A passive read that finds the screen unstable is repeated here, after a short settle, before the caller ever
     * sees `stale_snapshot`. The waiting happens between attempts and outside the host mutex, which each attempt
     * takes on its own. What is left after the limit is either the same `stale_snapshot` (its detail counts the
     * retries) or, for a window that stays unreadable, a specific `window_unreadable` error with a diagnosis in
     * the log and the audit, never an empty tree with a success face.
     */
    internal suspend fun readForGadget(link: GadgetPhoneLease, screenshot: Boolean, full: Boolean): JsonObject = try {
        DirectPhoneStaleRetry.run(
            finalize = { e, retries ->
                if (e.detail == DirectPhoneReads.WINDOW_UNREADABLE) CommandException(ErrorCode.EXECUTOR_ERROR, DirectPhoneReads.WINDOW_UNREADABLE)
                else CommandException(ErrorCode.STALE_SNAPSHOT, if (retries > 0) DirectPhoneStaleRetry.retriesDetail(retries) else e.detail)
            },
            settled = { retries, ok -> auditStaleRetry(if (screenshot) "phone_screenshot" else "phone_observe", retries, ok) },
        ) { readForGadgetOnce(link, screenshot, full) }
    } catch (e: CommandException) {
        if (e.detail == DirectPhoneReads.WINDOW_UNREADABLE) recordUnreadableWindow(link, if (screenshot) "phone_screenshot" else "phone_observe")
        throw e
    }

    private fun auditStaleRetry(command: String, retries: Int, succeeded: Boolean) {
        runCatching { audit("stale_retry", command, "retries=$retries;result=${if (succeeded) "ok" else "stale"}") }
    }

    /** What the reader and the window manager saw when a window could not be read; no screen content, only shapes. */
    private suspend fun recordUnreadableWindow(link: GadgetPhoneLease, command: String) {
        val line = lock.withLock {
            val session = sessions[link.owner]?.takeIf { it.link === link } ?: return@withLock null
            val shell = session.binding.shell.value
            val wm = withContext(Dispatchers.IO) {
                val id = session.display.current?.displayId
                if (shell == null || id == null) null
                else runCatching { VirtualWindowProtection.fromCode(shell.virtualWindowProtection(id)).name }.getOrNull()
            }
            "wm=${wm ?: "n/a"} " + (session.androidScreen()?.windowDiagnostics() ?: "screen=none")
        } ?: return
        Log.w(TAG, "$command: window_unreadable $line")
        runCatching { audit("window_unreadable", command, line) }
    }

    private suspend fun readForGadgetOnce(link: GadgetPhoneLease, screenshot: Boolean, full: Boolean): JsonObject = lock.withLock {
        if (!settings.enabled.value) throw CommandException(ErrorCode.LOCAL_GATE_DENIED, "remote_access_disabled")
        val session = gadgetSession(link) ?: throw CommandException(ErrorCode.UNAVAILABLE)
        val access = app.appAccess.current()
        val guarded = app.appAccess.sensitiveUi
        val reader = reader(session)
        val generation = ScreenAccessibility.windowGeneration()
        val result = if (screenshot) reader.screenshot() else reader.tree(full)
        if (gadgetSession(link) !== session) throw CommandException(ErrorCode.UNAVAILABLE)
        if (app.appAccess.current() != access || app.appAccess.sensitiveUi != guarded)
            throw CommandException(ErrorCode.LOCAL_GATE_DENIED, "app_access_changed")
        if (!screenshot) {
            val id = (result["snapshotId"] as JsonPrimitive).content
            session.passiveTarget = PassiveTarget(id, treeFingerprint(result), generation)
        }
        result
    }

    private fun reader(session: Session) = DirectPhoneReads(
        screen = session.screen,
        generation = { ScreenAccessibility.windowGeneration() },
        windowProtection = {
            val displayId = session.display.current?.displayId
            if (displayId == null) VirtualWindowProtection.Empty else withContext(Dispatchers.IO) {
                val shell = session.binding.shell.value ?: throw CommandException(ErrorCode.UNAVAILABLE)
                VirtualWindowProtection.fromCode(shell.virtualWindowProtection(displayId))
            }
        },
        inUseByUser = { snapshot -> withContext(Dispatchers.IO) { inUseOnPhysicalScreen(session, snapshot) } },
        capture = session.captureVirtualPng,
        compress = ::compressPhoneScreenshot,
    )

    /**
     * Null means the screen did not match the observation this time, which waiting may fix; a snapshot that was
     * already replaced (another observation or a window change since) can never match again and says so with
     * [DirectPhoneStaleRetry.SUPERSEDED], so the retry does not spend its budget on it.
     */
    private suspend fun bindGadgetTarget(session: Session, args: JsonObject): JsonObject? {
        val ref = (args["ref"] as? JsonPrimitive)?.content ?: return args.takeUnless { "snapshotId" in it }
        val superseded = CommandException(ErrorCode.STALE_SNAPSHOT, DirectPhoneStaleRetry.SUPERSEDED)
        val target = session.passiveTarget ?: throw superseded
        if (args["snapshotId"] != JsonPrimitive(target.id) || target.generation != ScreenAccessibility.windowGeneration()) throw superseded
        val fresh = session.androidScreen()?.bindPassiveReference { snapshot ->
            !snapshot.protected && snapshot.captureError == null &&
                treeFingerprint(ScreenJson.passivePhoneTree(snapshot, false)) == target.fingerprint
        } ?: return null
        if (target.generation != ScreenAccessibility.windowGeneration()) throw superseded
        val (modelRef, modelId) = session.tools.bindPhoneTarget(fresh, ref) ?: return null
        return JsonObject(args + mapOf("ref" to JsonPrimitive(modelRef), "snapshotId" to JsonPrimitive(modelId)))
    }

    private fun inUseOnPhysicalScreen(session: Session, snapshot: ScreenSnapshot): Boolean {
        if (snapshot.opened?.onUserScreen == true) return true
        val packages = (snapshot.windows.filter { it.type == "application" }.mapNotNull { it.packageName } +
            listOfNotNull(snapshot.packageName, snapshot.unreadableApp)).distinct()
        if (packages.isEmpty()) return false
        val shell = session.binding.shell.value ?: throw CommandException(ErrorCode.UNAVAILABLE)
        val topTask = session.display.current?.let { shell.topTaskId(it.displayId) } ?: -1
        val topPackage = if (topTask >= 0) shell.packageOfTask(topTask) else null
        val topUser = if (topTask >= 0) shell.userOfTask(topTask) else -1
        val androidUser = android.os.Process.myUid() / 100_000
        return packages.any {
            shell.visibleOnMainScreen(it, if (it == topPackage && topUser >= 0) topUser else androidUser)
        }
    }

    /**
     * Cancellation/status are out-of-band. All other phone_* calls go through
     * GER's per-owner FIFO, NOT through TaskCenter's persistent task queue.
     * The host mutex is held for an atomic attempt, never for parked waiting.
     */
    override suspend fun call(owner: String, name: String, args: JsonObject): JsonObject =
        call(owner, name, args, null)

    internal suspend fun callGadget(link: GadgetPhoneLease, name: String, args: JsonObject): JsonObject =
        call(link.owner, name, args, link)

    private suspend fun call(owner: String, name: String, args: JsonObject, link: GadgetPhoneLease?): JsonObject {
        // A gadget call is authorized by its live registration, not by its session: a session retired
        // for a local task or a helper restart is re-created by maintainGadget, and until then queued
        // calls wait ("phone_session_pending"), while reconcile, status and cancel answer at once.
        fun authorized() = link == null || allowed(link)
        // A stopped app (or any local refusal) answers an opening on the main screen with the local gate's
        // verdict, not with the generic "unavailable" every other command gets.
        if (link != null) mainLaunchDenial(name, args)?.let { return gateDenied(it) }
        if (!authorized()) return fail("phone_unavailable")
        if (owner.isBlank()) return fail("unauthenticated")
        val scheduler = app.resources
        val sessionOwner = directOwner(owner)
        if (name == "phone_reconcile") {
            if (!settings.enabled.value) return fail("remote_access_disabled")
            // Read-only and out of the intention queue: no display/session is
            // created, no action is submitted, no pending marker is written.
            return lock.withLock { if (authorized()) reconcile(owner, args) else fail("phone_unavailable") }
        }
        if (name == "phone_approval_issue") {
            if (!settings.enabled.value) return fail("remote_access_disabled")
            if (!settings.directWritesEnabled.value) return fail("write_disabled")
            // Minting is out of the intention queue: no display/session is
            // created and nothing executes. The token only takes effect when
            // the approval gate consumes it for the exact bound action.
            return lock.withLock { issueApproval(owner, args) }
        }
        if (name == "phone_cancel") {
            val cancelled = scheduler.ger.intentions.cancelOwner(sessionOwner)
            return lock.withLock {
                if (!authorized()) return@withLock fail("phone_unavailable")
                val hadSession = sessions[owner]?.let { link == null || it.link === link } == true
                val deferredClose = hadSession && launchBoundary.isInFlight()
                if (hadSession) retire(owner)
                if (!hadSession && cancelled == 0) fail("session_not_found")
                else buildJsonObject {
                    put("ok", true); put("closed", hadSession && !deferredClose)
                    put("cancelledIntents", cancelled)
                    if (deferredClose) put("closePending", true)
                }
            }
        }
        if (name == "phone_status" || name == "phone_get_state") return lock.withLock {
            buildJsonObject {
                put("ok", true); put("active", sessions.containsKey(owner))
                put("writeEnabled", settings.enabled.value && settings.directWritesEnabled.value)
                put("busy", busy()); put("sessionTtlMs", ttlMs)
                put("pendingIntents", scheduler.ger.intentions.pendingCount(sessionOwner))
                put("launchInFlight", launchBoundary.isInFlight())
                put("launchClosePending", closeAfterLaunch)
            }
        }
        if (name !in READS && name != "phone_run" && name !in ACTIONS) return fail("unknown_tool")
        // Do not queue behind an Android launch whose Binder call can still
        // complete after its caller timed out. No new journal entry is written.
        if (launchBoundary.isInFlight() || closeAfterLaunch) return fail("launch_in_flight")
        // A rejected/missing ID must not occupy a queue slot. Read tools have no
        // request_id in the unchanged MCP schema and get a synthetic queue key.
        val request = if (name in READS) UUID.randomUUID().toString() else requestId(args)
            ?: return fail("request_id_required")
        val progress = BatchProgress()
        val outcome = scheduler.ger.intentions.dispatch(
            sessionOwner, request, settings.directWaitTimeoutMs,
        ) attempt@{ isCancelled ->
            // Never park waiting for an already-running host action. This also
            // lets phone_cancel revoke queued calls without waiting on its mutex.
            if (!lock.tryLock()) return@attempt GerIntentAttempt.Blocked("host_busy")
            try {
                if (launchBoundary.isInFlight() || closeAfterLaunch)
                    return@attempt GerIntentAttempt.Done(fail("launch_in_flight"))
                if (!authorized()) return@attempt GerIntentAttempt.Done(fail("phone_unavailable"))
                if (isCancelled()) return@attempt GerIntentAttempt.Done(fail("cancelled"))
                if (!settings.enabled.value) return@attempt GerIntentAttempt.Done(fail("remote_access_disabled"))
                if (name !in READS && !settings.directWritesEnabled.value)
                    return@attempt GerIntentAttempt.Done(fail("write_disabled"))
                val now = System.currentTimeMillis()
                sessions.values.filter { it.link == null && now - it.lastUsed > ttlMs }.map { it.owner }.forEach(::closeActive)
                if (busy()) return@attempt GerIntentAttempt.Blocked("task_running")
                val admission = scheduler.ger.claimSlot(sessionOwner)
                if (!admission.admitted) return@attempt GerIntentAttempt.Blocked("slot_budget_exhausted")
                // Only maintainGadget creates a gadget session (with its lease and identity).
                if (link != null && sessions[owner]?.link !== link) {
                    if (!sessions.containsKey(owner)) scheduler.ger.releaseSlot(sessionOwner)
                    return@attempt GerIntentAttempt.Blocked("phone_session_pending")
                }
                val s = try {
                    sessions[owner] ?: create(owner, now).also { sessions[owner] = it }
                } catch (e: CancellationException) {
                    if (!sessions.containsKey(owner)) scheduler.ger.releaseSlot(sessionOwner)
                    throw e
                } catch (_: Exception) {
                    if (!sessions.containsKey(owner)) scheduler.ger.releaseSlot(sessionOwner)
                    return@attempt GerIntentAttempt.Done(fail("phone_unavailable"))
                }
                if (!scheduler.ger.canUseSlot(sessionOwner))
                    return@attempt GerIntentAttempt.Blocked("task_running")
                if (isCancelled()) return@attempt GerIntentAttempt.Done(fail("cancelled"))
                s.lastUsed = now
                try {
                    // Virtual-display reads must work with the physical screen off.
                    // Only actions require an unlocked, interactive physical phone.
                    if (DirectPhoneScreenPolicy.requiresInteractivePhysicalScreen(name) && !ready(s))
                        return@attempt GerIntentAttempt.Done(fail("needs_user"))
                    if (sensitive(s)) return@attempt GerIntentAttempt.Done(fail("needs_user"))
                    when (name) {
                        "phone_observe", "phone_list_apps" -> {
                            if (sensitive(s)) GerIntentAttempt.Done(fail("needs_user"))
                            else {
                                val observed = s.tools.executeRead(Part.ToolCall(UUID.randomUUID().toString(), name, "{}")).content
                                when {
                                    !scheduler.ger.canUseSlot(sessionOwner) -> GerIntentAttempt.Blocked("task_running")
                                    sensitive(s) -> GerIntentAttempt.Done(fail("needs_user"))
                                    else -> GerIntentAttempt.Done(observed)
                                }
                            }
                        }
                        "phone_screenshot" -> {
                            val result = screenshot(s)
                            if (!scheduler.ger.canUseSlot(sessionOwner)) GerIntentAttempt.Blocked("task_running")
                            else GerIntentAttempt.Done(result)
                        }
                        "phone_run" -> batch(s, args, progress, isCancelled)
                        else -> {
                            val reason = launchBlocker(s, name, args)
                            if (reason != null) GerIntentAttempt.Blocked(reason)
                            else if (busy()) GerIntentAttempt.Blocked("task_running")
                            else action(s, name, args)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: CommandException) { throw e }
                catch (_: Exception) { GerIntentAttempt.Done(fail("phone_unavailable")) }
            } finally { lock.unlock() }
        }
        return when (outcome) {
            is GerIntentOutcome.Completed -> outcome.result
            is GerIntentOutcome.TimedOut -> buildJsonObject {
                put("ok", false); put("error", "wait_timeout"); put("cause", outcome.cause)
                put("executed", progress.index > 0)
                if (name == "phone_run") {
                    put("completed", progress.index)
                    put("results", buildJsonArray { progress.results.forEach { add(it) } })
                }
            }
            GerIntentOutcome.Cancelled -> buildJsonObject {
                put("ok", false); put("error", "cancelled"); put("executed", progress.index > 0)
                if (name == "phone_run") {
                    put("completed", progress.index)
                    put("results", buildJsonArray { progress.results.forEach { add(it) } })
                }
            }
            GerIntentOutcome.Duplicate -> fail("delivery_unknown")
            GerIntentOutcome.QueueFull -> fail("intent_queue_full")
        }
    }

    /**
     * GER PR-6: mint a signed, single-use chat approval token bound to the
     * owner + internal tool name + exact arguments (minus request_id and any
     * presented token). The issuance is audited digest-only; the token is
     * redeemed by the approval gate at execution time, never here.
     */
    /** GER PR-7: the scoped KEYCODE a volume/media tool executes as, or null when its argument is invalid. */
    private fun scopedKeyFor(external: String, args: JsonObject): String? = when (external) {
        "phone_volume" -> when ((args["direction"] as? JsonPrimitive)?.contentOrNull) {
            "up" -> "KEYCODE_VOLUME_UP"; "down" -> "KEYCODE_VOLUME_DOWN"
            "mute" -> "KEYCODE_VOLUME_MUTE"; else -> null
        }
        "phone_media" -> when ((args["action"] as? JsonPrimitive)?.contentOrNull) {
            "play_pause" -> "KEYCODE_MEDIA_PLAY_PAUSE"; "stop" -> "KEYCODE_MEDIA_STOP"
            "next" -> "KEYCODE_MEDIA_NEXT"; "previous" -> "KEYCODE_MEDIA_PREVIOUS"; else -> null
        }
        else -> null
    }

    private fun issueApproval(owner: String, args: JsonObject): JsonObject {
        val external = (args["tool"] as? JsonPrimitive)?.contentOrNull
            ?: return fail("invalid_tool")
        val internal = ACTIONS[external] ?: return fail("invalid_tool")
        val raw = args["arguments"] as? JsonObject ?: return fail("invalid_arguments")
        // Volume/media bind the translated {key} object the executor will
        // actually judge, not the caller-facing direction/action arguments.
        val binding = if (external == "phone_volume" || external == "phone_media") {
            val keyName = scopedKeyFor(external, raw) ?: return fail("invalid_arguments")
            buildJsonObject { put("key", keyName) }
        } else JsonObject(raw.filterKeys { it != "request_id" && it != "approval_token" })
        val token = chatApprovalStore.tokens.issue(directOwner(owner), internal, binding, "direct")
        audit(digest("issue|" + owner + "|" + internal), "approval_issue", internal)
        return buildJsonObject {
            put("ok", true); put("token", token); put("tool", external)
            put("expiresInMs", ChatApprovalTokens.DEFAULT_TTL_MS)
        }
    }

    /**
     * Durable recovery lookup for the authenticated owner only. Every result
     * explicitly requires fresh observation; it NEVER authorizes automatic
     * replay or treats a missing journal entry as proof of no execution.
     */
    private fun reconcile(owner: String, args: JsonObject): JsonObject {
        val id = requestId(args) ?: return fail("request_id_required")
        val kind = (args["kind"] as? JsonPrimitive)?.contentOrNull ?: return fail("invalid_kind")
        if (kind == "action") {
            if ("commands" in args) return fail("invalid_commands")
            val status = DirectPhoneJournalInspection.lookup(journal, digest(owner + "|" + id))
            return buildJsonObject {
                put("ok", true); put("kind", "action")
                put("journalState", status.wire)
                put("executionUnknown", status != DirectPhoneJournalInspection.State.COMPLETED_OK)
                put("retryable", false); put("requiresFreshObservation", true)
            }
        }
        if (kind != "batch") return fail("invalid_kind")
        val commands = args["commands"] as? JsonArray ?: return fail("invalid_commands")
        if (commands.size !in 1..30) return fail("invalid_commands")
        val key = digest(owner + "|batch|" + id)
        return when (val saved = runCatching {
            checkpoints.inspect(key, digest(commands.toString()), commands.size)
        }.getOrDefault(DirectPhoneBatchCheckpoints.InspectResult.Corrupt)) {
            DirectPhoneBatchCheckpoints.InspectResult.Corrupt -> buildJsonObject {
                put("ok", false); put("error", "checkpoint_corrupt")
                put("executionUnknown", true); put("retryable", false)
                put("requiresFreshObservation", true)
            }
            DirectPhoneBatchCheckpoints.InspectResult.Conflict -> buildJsonObject {
                put("ok", false); put("error", "checkpoint_mismatch")
                put("executionUnknown", true); put("retryable", false)
                put("requiresFreshObservation", true)
            }
            DirectPhoneBatchCheckpoints.InspectResult.Missing -> buildJsonObject {
                put("ok", true); put("kind", "batch")
                put("checkpointState", "not_seen")
                put("executionUnknown", true); put("retryable", false)
                put("requiresFreshObservation", true)
            }
            is DirectPhoneBatchCheckpoints.InspectResult.Found -> {
                val completed = saved.snapshot.completed
                val stepState = if (completed == commands.size) null else {
                    val stepId = UUID.nameUUIDFromBytes((id + ":" + completed).toByteArray()).toString()
                    DirectPhoneJournalInspection.lookup(journal, digest(owner + "|" + stepId))
                }
                buildJsonObject {
                    put("ok", true); put("kind", "batch")
                    put("checkpointState", "found")
                    put("total", commands.size); put("completed", completed)
                    put("nextIndex", completed)
                    stepState?.let { put("nextJournalState", it.wire) }
                    put("executionUnknown", stepState != null)
                    put("retryable", false); put("requiresFreshObservation", true)
                }
            }
        }
    }

    /**
     * Pure pre-action check, not a launch or a reservation. ShellService's
     * displayOfPackage() ONLY searches our virtual displays, never display 0:
     * we must independently query the physical screen via the existing Binder
     * visibleOnMainScreen(), which matches startApp's actual ownership gate.
     */
    private fun launchBlocker(s: Session, name: String, args: JsonObject): String? {
        if (name != "phone_launch_app") return null
        // The user using the app on the main screen is no obstacle to opening it there.
        if (DirectPhoneMainLaunch.target(args) == DirectPhoneMainLaunch.Target.Main) return null
        val pkg = (args["package"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val shell = s.binding.shell.value ?: return "app_presence_unknown"
        val userId = android.os.Process.myUid() / 100_000
        val userVisible = runCatching { shell.visibleOnMainScreen(pkg, userId) }.getOrNull()
        if (userVisible != false) {
            return DirectPhoneLaunchPreflight.blockedCause(
                userVisible = userVisible,
                reservedByOther = false,
                agentDisplay = -1,
                ourDisplay = s.display.current?.displayId,
            )
        }
        val scheduler = app.resources
        val owner = directOwner(s.owner)
        val reserved = !scheduler.appFree(pkg, owner, scheduler.ger::isActiveOwner, { true })
        if (reserved) return "app_busy"
        val display = runCatching { shell.displayOfPackage(pkg) }.getOrNull()
            ?: return "app_presence_unknown"
        return DirectPhoneLaunchPreflight.blockedCause(
            userVisible = false,
            reservedByOther = false,
            agentDisplay = display,
            ourDisplay = s.display.current?.displayId,
        )
    }

    private fun busy(): Boolean =
        app.resources.ger.hasRunningTasks() || app.taskState.busy.value || app.taskState.runs.value.isNotEmpty()

    /** A remote tool must not wake, unlock or take back a screen without user action. */
    private fun ready(s: Session): Boolean {
        val lock = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return lock?.isDeviceLocked != true && lock?.isKeyguardLocked != true &&
            power?.isInteractive != false && s.binding.shell.value != null &&
            ScreenAccessibility.instance.value != null &&
            app.resources.ger.canUseSlot(directOwner(s.owner))
    }

    private fun readyForGate(binding: ShellBinding): Boolean {
        val lock = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return lock?.isDeviceLocked != true && lock?.isKeyguardLocked != true &&
            power?.isInteractive != false && binding.shell.value != null &&
            ScreenAccessibility.instance.value != null
    }

    private suspend fun create(owner: String, now: Long, link: GadgetPhoneLease? = null): Session {
        val services = PhoneServices.of(context)
        val binding = services.sharedShell.acquire()
        try {
            val shell = withTimeoutOrNull(12_000L) { binding.shell.filterNotNull().first() }
                ?: throw IllegalStateException("shell unavailable")
            val a11y = withTimeoutOrNull(12_000L) { ScreenAccessibility.instance.filterNotNull().first() }
                ?: throw IllegalStateException("accessibility unavailable")
            val metrics = context.resources.displayMetrics
            val scheduler = app.resources
            val sessionOwner = directOwner(owner)
            fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
            val display = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    context.getSystemService(DisplayManager::class.java))
            }
            var physical: AndroidScreen? = null
            val lazy = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) } },
            ) {
                AndroidScreen(a11y, shell, display,
                    checkOtherTasks = true,
                    reserve = { pkg ->
                        !busy() && scheduler.ger.canUseSlot(sessionOwner) &&
                            scheduler.reserveApp(pkg, sessionOwner, scheduler.ger::isActiveOwner, { true })
                    },
                    reserveCopy = { pkg, user ->
                        !busy() && scheduler.ger.canUseSlot(sessionOwner) &&
                            scheduler.reserveApp(claimKey(pkg, user), sessionOwner, scheduler.ger::isActiveOwner, { true })
                    },
                    appFree = { pkg, user ->
                        !busy() && scheduler.ger.canUseSlot(sessionOwner) &&
                            scheduler.appFree(claimKey(pkg, user), sessionOwner, scheduler.ger::isActiveOwner, { true })
                    },
                    appAccess = app.appAccess,
                ).also { physical = it }
            }
            val safe = AppAccessScreenPort(lazy, app.appAccess,
                packages = { physical?.accessPackages().orEmpty() },
                protectedUi = { app.appAccess.sensitiveUi },
                ownPackage = context.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            )
            val tools = toolDispatcher(safe, stableRefs = true, inlineScreen = true)
            val sink = object : RunSink {
                override fun event(e: RunEvent) {
                    // Only class name, not possibly sensitive event text/arguments.
                    runCatching { audit("event", e.javaClass.simpleName, "") }
                }
            }
            val executor = ActionExecutor(
                tools, OperationGuards(400), confirmations = null,
                gate = { check((link == null || allowed(link)) && settings.directWritesEnabled.value && settings.enabled.value && !busy() &&
                    scheduler.ger.canUseSlot(sessionOwner) && readyForGate(binding)) },
                recorder = ActionRecorder(sink), approvals = ApprovalSettings.STRICT,
                // Bridge origin is STRICT in ApprovalPolicy; generic Mcp runs only use ASK.
                // Origin.McpTool never consumes a remote approval/grant.
                provenance = RunProvenance(RunOrigin.Bridge, Attendance.Unattended),
                // GER PR-6: gated steps may be satisfied by a presented chat
                // approval token (single-use, exact-action bound); payments
                // remain denied even with a token.
                chatApproval = ChatApprovalChannel(
                    chatApprovalStore.tokens, sessionOwner, "direct", chatApprovalSlot),
                selfPackage = context.packageName,
            )
            val captureVirtualPng: suspend () -> VirtualScreenshot = {
                safe.checkAppAccess()
                val result = physical?.captureVirtualPng() ?: VirtualScreenshot.Failed
                safe.checkAppAccess()
                result
            }
            return Session(owner, binding, link, { services.hub.shell.value === shell && ScreenAccessibility.instance.value === a11y }, { physical }, display, safe, tools, executor, captureVirtualPng, services.sharedShell::release, now)
        } catch (e: Exception) {
            services.sharedShell.release()
            throw e
        }
    }

    private suspend fun sensitive(s: Session): Boolean = DirectPhoneReads.sensitive(s.screen)

    private suspend fun screenshot(s: Session): JsonObject {
        if (sensitive(s)) return fail("needs_user")
        val png = s.screen.screenshotPng() ?: return fail("screenshot_unavailable")
        if (sensitive(s)) return fail("needs_user")
        return when (val encoded = compressPhoneScreenshot(png, DirectPhoneReads.MCP_SCREENSHOT_BYTES)) {
            null -> fail("screenshot_unavailable")
            EncodedPhoneScreenshot.TooLarge -> fail("payload_too_large")
            is EncodedPhoneScreenshot.Image -> encoded.json()
        }
    }

    /** Progress for this MCP call; durable success prefix lives in DirectPhoneBatchCheckpoints. */
    private class BatchProgress {
        var index = 0
        var initialized = false
        val results = mutableListOf<JsonObject>()
    }

    private suspend fun batch(
        s: Session, args: JsonObject, progress: BatchProgress, cancelled: () -> Boolean,
    ): GerIntentAttempt<JsonObject> {
        val id = requestId(args) ?: return GerIntentAttempt.Done(fail("request_id_required"))
        val commands = args["commands"] as? JsonArray ?: return GerIntentAttempt.Done(fail("invalid_commands"))
        if (commands.isEmpty() || commands.size > 30) return GerIntentAttempt.Done(fail("invalid_commands"))
        // Nothing runs unless every step is a known one: a bad name in the middle must not leave the steps before
        // it executed behind an "executed:false" answer.
        DirectPhoneBatchSteps.firstInvalid(commands, ACTIONS.keys)?.let { index ->
            return GerIntentAttempt.Done(buildJsonObject {
                put("ok", false); put("error", "invalid_commands"); put("executed", false); put("invalidStepIndex", index)
            })
        }
        val batchKey = digest(s.owner + "|batch|" + id)
        if (!progress.initialized) {
            // Reconnect/restart uses the same stable request_id and exact commands.
            // We can prove which earlier steps succeeded, but we CANNOT assume the
            // UI is still on the same screen. Muse must observe/reconcile before
            // issuing the remainder as a new batch. Never replay a tap or send.
            when (val saved = checkpoints.open(batchKey, digest(commands.toString()), commands.size)) {
                DirectPhoneBatchCheckpoints.OpenResult.Corrupt ->
                    return GerIntentAttempt.Done(buildJsonObject {
                        put("ok", false); put("error", "checkpoint_corrupt")
                        put("executionUnknown", true); put("requiresFreshObservation", true)
                    })
                DirectPhoneBatchCheckpoints.OpenResult.Conflict ->
                    return GerIntentAttempt.Done(buildJsonObject {
                        put("ok", false); put("error", "checkpoint_mismatch")
                        put("executionUnknown", true); put("requiresFreshObservation", true)
                    })
                is DirectPhoneBatchCheckpoints.OpenResult.Ready -> {
                    progress.initialized = true
                    if (!saved.snapshot.fresh && saved.snapshot.completed > 0) {
                        return GerIntentAttempt.Done(buildJsonObject {
                            put("ok", false); put("error", "reconciliation_required")
                            put("recovered", true); put("executed", true)
                            put("completed", saved.snapshot.completed)
                            put("total", commands.size)
                            put("nextIndex", saved.snapshot.completed)
                            put("requiresFreshObservation", true)
                            put("resultsUnavailable", true)
                        })
                    }
                }
            }
        }
        while (progress.index < commands.size) {
            if (cancelled()) return GerIntentAttempt.Done(fail("cancelled"))
            if (busy() || !app.resources.ger.canUseSlot(directOwner(s.owner)))
                return GerIntentAttempt.Blocked("task_running")
            val raw = commands[progress.index]
            val cmd = raw as? JsonObject ?: return GerIntentAttempt.Done(fail("invalid_commands"))
            val name = (cmd["name"] as? JsonPrimitive)?.contentOrNull ?: return GerIntentAttempt.Done(fail("invalid_commands"))
            if (name !in ACTIONS) return GerIntentAttempt.Done(fail("invalid_commands"))
            val params = cmd["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            if (!ready(s) || sensitive(s)) return GerIntentAttempt.Done(fail("needs_user"))
            launchBlocker(s, name, params)?.let { return GerIntentAttempt.Blocked(it) }
            // A deterministic ID per step makes a completed step non-replayable,
            // even if the MCP connection dies after step N and before the reply.
            val key = UUID.nameUUIDFromBytes((id + ":" + progress.index).toByteArray()).toString()
            // Only Blocked is guaranteed pre-PENDING; no step may be replayed
            // after an execution has reached the durable journal.
            val outcome = when (val step = action(s, name, JsonObject(params + ("request_id" to JsonPrimitive(key))))) {
                is GerIntentAttempt.Blocked -> return step
                is GerIntentAttempt.Done -> step.result
            }
            // The bounded launch correction (stacked separately after #15)
            // also reports uncertain delivery when Android Binder outlives the
            // MCP deadline. Neither case may advance a durable checkpoint.
            if (outcome["error"] == JsonPrimitive("delivery_unknown") ||
                outcome["error"] == JsonPrimitive("launch_timeout_unknown"))
                return GerIntentAttempt.Done(buildJsonObject {
                    put("ok", false); put("error", "reconciliation_required")
                    put("executionUnknown", true); put("completed", progress.index)
                    put("uncertainStepIndex", progress.index)
                    put("cause", (outcome["cause"] as? JsonPrimitive)?.contentOrNull
                        ?: (outcome["error"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    put("requiresFreshObservation", true)
                })
            if (outcome["ok"] == JsonPrimitive(true)) {
                // Persist only confirmed success, and only AFTER the action's
                // separate idempotency audit has been fsync'd by action().
                val stored = runCatching { checkpoints.completed(batchKey, progress.index) }.getOrDefault(false)
                if (!stored) return GerIntentAttempt.Done(buildJsonObject {
                    put("ok", false); put("error", "checkpoint_unavailable")
                    put("executed", true); put("completed", progress.index)
                    put("uncertainStepIndex", progress.index)
                    put("requiresFreshObservation", true)
                })
            }
            progress.results += outcome
            progress.index++
            if (outcome["ok"] != JsonPrimitive(true)) break
        }
        return GerIntentAttempt.Done(buildJsonObject {
            put("ok", progress.index == commands.size && progress.results.all { it["ok"] == JsonPrimitive(true) })
            put("results", buildJsonArray { progress.results.forEach { add(it) } })
            put("completed", progress.index)
        })
    }

    /**
     * Blocked means no audit marker and NO action attempted. After markOnce's
     * fsync(PENDING), every result is terminal for this request_id, even when
     * Android reports no progress; repeating it could otherwise double-send.
     */
    private suspend fun action(s: Session, external: String, args: JsonObject): GerIntentAttempt<JsonObject> {
        val tool = ACTIONS[external] ?: return GerIntentAttempt.Done(fail("unknown_tool"))
        val launchTarget = if (external == "phone_launch_app") DirectPhoneMainLaunch.target(args) ?: return GerIntentAttempt.Done(fail("invalid_target"))
            else DirectPhoneMainLaunch.Target.Virtual
        if (launchTarget == DirectPhoneMainLaunch.Target.Main) {
            // Only the Gadget Link owner opens on the main screen, never with a restart, and always past the same
            // local authorization (also checked when a batch step reaches it).
            if (s.link == null) return GerIntentAttempt.Done(fail("invalid_target"))
            if ((args["cold"] as? JsonPrimitive)?.contentOrNull == "true") return GerIntentAttempt.Done(fail("cold_on_main_unsupported"))
            mainLaunchDenial(external, args)?.let { return GerIntentAttempt.Done(gateDenied(it)) }
        }
        if (!settings.enabled.value || !settings.directWritesEnabled.value)
            return GerIntentAttempt.Done(fail("write_disabled"))
        if (busy() || !app.resources.ger.canUseSlot(directOwner(s.owner)))
            return GerIntentAttempt.Blocked("task_running")
        if (!ready(s) || sensitive(s)) return GerIntentAttempt.Done(fail("needs_user"))
        val actionArgs = if (s.link != null) {
            if (gadgetSession(s.link) !== s) return GerIntentAttempt.Done(fail("phone_unavailable"))
            // Nothing has executed for this step yet: report the refusal as this step's result
            // (a batch then still says how many earlier steps ran) instead of throwing past it.
            // A busy screen gets a few settles before the caller hears about it. Nothing has executed yet at this
            // point (the PENDING marker comes later), so repeating the check is safe.
            try {
                DirectPhoneStaleRetry.run(settled = { retries, ok -> auditStaleRetry(external, retries, ok) }) {
                    reader(s).checkActionSurface()
                    bindGadgetTarget(s, args) ?: throw CommandException(ErrorCode.STALE_SNAPSHOT)
                }
            } catch (e: CommandException) {
                return GerIntentAttempt.Done(surfaceRefusal(e))
            }
        } else args
        val request = requestId(args) ?: return GerIntentAttempt.Done(fail("request_id_required"))
        // GER PR-6: a presented chat approval token is gate input, not an
        // action argument — strip it (and request_id) from what executes and
        // from the exact-args digest the token was bound to at issuance.
        val presentedToken = (args["approval_token"] as? JsonPrimitive)?.contentOrNull
        // `target` is this host's own routing argument for an opening; for phone_tap it is a real argument.
        val clean = JsonObject(actionArgs.filterKeys {
            it != "request_id" && it != "approval_token" && !(external == "phone_launch_app" && it == "target")
        })
        // GER PR-7: volume/media execute as one scoped phone_press_key whose
        // arguments are the translated {key} object — the same shape the
        // token issuer binds for those tools.
        val actualTool: String
        val executorTool: JsonObject
        if (external == "phone_volume" || external == "phone_media") {
            val keyName = scopedKeyFor(external, args)
                ?: return GerIntentAttempt.Done(fail(
                    if (external == "phone_volume") "invalid_volume_direction" else "invalid_media_action"))
            actualTool = "phone_press_key"
            executorTool = buildJsonObject { put("key", keyName) }
        } else {
            actualTool = tool
            executorTool = clean
        }
        // Recheck physical app ownership at the LAST pre-PENDING boundary.
        // Previously displayOfPackage() always missed the user's display 0,
        // then startApp returned app_in_use_by_user AFTER markOnce had already
        // committed this ID, leaving the queue unable to retry it.
        launchBlocker(s, external, args)?.let { return GerIntentAttempt.Blocked(it) }
        // A still-running launch must not commit another request's PENDING.
        if (launchBoundary.isInFlight() || closeAfterLaunch)
            return GerIntentAttempt.Done(fail("launch_in_flight"))
        if (s.link != null && gadgetSession(s.link) !== s) return GerIntentAttempt.Done(fail("phone_unavailable"))
        val key = digest(s.owner + "|" + request)
        // The PENDING marker records where an opening went, next to the argument names it always kept. (The
        // completion record keeps its fixed vocabulary: the journal inspection reads it strictly.)
        val audited = clean.keys.sorted().joinToString(",") +
            if (external == "phone_launch_app") ";target=${launchTarget.wire}" else ""
        if (!markOnce(key, external, audited))
            return GerIntentAttempt.Done(fail("delivery_unknown"))
        val sequence = ++step
        if (external == "phone_launch_app") {
            val result = try {
                launchBoundary.execute {
                    // Detached only AFTER the fsync'd PENDING marker. Android
                    // Binder cannot be interrupted safely; the worker records
                    // its eventual outcome even when the MCP caller timed out.
                    val actual = if (launchTarget == DirectPhoneMainLaunch.Target.Main) {
                        try {
                            openOnMain(s, (args["package"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { uncertain("main_launch_exception") }
                    } else try {
                        chatApprovalSlot.token = presentedToken
                        try {
                            s.executor.run(Part.ToolCall(request, actualTool, executorTool.toString()), Origin.McpTool(sequence))
                                .execution().content
                        } finally { chatApprovalSlot.token = null }
                    } catch (_: Exception) {
                        uncertain("launch_executor_exception")
                    }
                    audit(key, "completed", when {
                        actual["ok"] == JsonPrimitive(true) -> "ok"
                        actual["error"] == JsonPrimitive("delivery_unknown") -> "unknown"
                        else -> "not_applied"
                    })
                    actual
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                return GerIntentAttempt.Done(uncertain("launch_boundary_exception"))
            }
            return GerIntentAttempt.Done(when (result) {
                is DirectLaunchOutcome.Completed -> result.value
                DirectLaunchOutcome.TimedOut -> uncertain("start_app_timeout")
                DirectLaunchOutcome.InFlight -> uncertain("launch_already_running")
            })
        }
        val result = try {
            chatApprovalSlot.token = presentedToken
            try {
                s.executor.run(Part.ToolCall(request, actualTool, executorTool.toString()), Origin.McpTool(sequence))
                    .execution().content
            } finally { chatApprovalSlot.token = null }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail("delivery_unknown") }
        val guardCauseDigest = directPhoneGuardCauseDigest(result, ::digest)
        val completion = buildString {
            append(if (result["ok"] == JsonPrimitive(true)) "ok" else "not_applied")
            guardCauseDigest?.let { append("|guardCauseSha256=").append(it) }
        }
        audit(key, "completed", completion)
        return GerIntentAttempt.Done(result)
    }

    private fun requestId(args: JsonObject): String? =
        (args["request_id"] as? JsonPrimitive)?.contentOrNull?.takeIf {
            it.length in 8..64 && it.matches(Regex("[A-Za-z0-9_-]+"))
        }

    private fun markOnce(key: String, command: String, fields: String): Boolean {
        if (!loaded) {
            if (journal.isFile) journal.forEachLine { line ->
                line.substringBefore('|').takeIf { it.length == 64 }?.let(seen::add)
            }
            loaded = true
        }
        if (key in seen) return false
        // Durable PENDING before injecting a gesture; crashes return delivery_unknown.
        audit(key, command, fields)
        seen += key
        return true
    }

    private fun audit(key: String, command: String, fields: String) {
        val line = key + "|" + System.currentTimeMillis() + "|" + command + "|" +
            fields.replace('\n', ' ').take(300) + "\n"
        journal.parentFile?.mkdirs()
        FileOutputStream(journal, true).use { stream ->
            stream.write(line.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
    }

    private fun digest(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun uncertain(cause: String) = buildJsonObject {
        put("ok", false); put("error", "launch_timeout_unknown")
        put("cause", cause); put("executed", false)
        put("executionUnknown", true); put("retryable", false)
    }

    private fun surfaceRefusal(e: CommandException): JsonObject = when (e.code) {
        ErrorCode.APP_IN_USE_BY_USER -> fail("app_in_use_by_user")
        ErrorCode.STALE_SNAPSHOT -> buildJsonObject {
            put("ok", false); put("error", "stale_snapshot"); put("executed", false)
            e.detail?.removePrefix("retries_")?.toIntOrNull()?.takeIf { it > 0 }?.let { put("staleRetries", it) }
        }
        ErrorCode.LOCAL_GATE_DENIED -> buildJsonObject {
            put("ok", false); put("error", e.detail ?: "needs_user"); put("gateDenied", true)
        }
        ErrorCode.UNAVAILABLE -> fail("phone_unavailable")
        else -> fail("surface_check_failed")
    }

    private fun fail(code: String) = buildJsonObject {
        put("ok", false); put("error", code); put("executed", false)
    }

    /** A refusal of the local authorization, shaped like the ones the action executor reports for its own gates. */
    private fun gateDenied(code: String) = buildJsonObject {
        put("ok", false); put("error", code); put("gateDenied", true); put("executed", false)
    }

    /** The local authorization's verdict for an opening on the main screen; null for anything else or when allowed. */
    private fun mainLaunchDenial(name: String, args: JsonObject): String? {
        if (name != "phone_launch_app" || DirectPhoneMainLaunch.target(args) != DirectPhoneMainLaunch.Target.Main) return null
        return DirectPhoneMainLaunch.denial(
            stopped = FeatureFlags.get(context).quitRequested(),
            remoteEnabled = settings.enabled.value,
            writesEnabled = settings.directWritesEnabled.value,
            access = app.appAccess.current(),
            packageName = (args["package"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            ownPackage = context.packageName,
        )
    }

    /**
     * Opens [pkg] on the phone's main screen through the helper and only reports success once the app is really
     * visible there. A helper that predates this call answers with an error instead of the opening.
     */
    private suspend fun openOnMain(s: Session, pkg: String): JsonObject = withContext(Dispatchers.IO) {
        val shell = s.binding.shell.value ?: return@withContext fail("phone_unavailable")
        val started = try {
            shell.startAppOnMain(pkg)
        } catch (e: CancellationException) { throw e }
        catch (_: Throwable) { return@withContext fail("main_launch_unsupported") }
        if (started != ShellService.START_OK) return@withContext fail(DirectPhoneMainLaunch.startFailure(started))
        val user = android.os.Process.myUid() / 100_000
        val deadline = SystemClock.uptimeMillis() + MAIN_VISIBLE_WAIT_MS
        while (!runCatching { shell.visibleOnMainScreen(pkg, user) }.getOrDefault(false)) {
            if (SystemClock.uptimeMillis() >= deadline) return@withContext fail("main_launch_not_visible")
            delay(MAIN_VISIBLE_POLL_MS)
        }
        buildJsonObject { put("ok", true) }
    }

    companion object {
        private const val TAG = "MikaDirectPhone"
        private const val MAIN_VISIBLE_WAIT_MS = 4_000L
        private const val MAIN_VISIBLE_POLL_MS = 250L
        private val READS = DirectPhoneScreenPolicy.reads
        private val ACTIONS = mapOf(
            "phone_launch_app" to "phone_launch_app",
            "phone_tap" to "phone_tap",
            "phone_type" to "phone_type_text",
            "phone_scroll" to "phone_scroll",
            "phone_swipe" to "phone_swipe",
            "phone_gesture" to "phone_gesture",
            "phone_key" to "phone_press_key",
            "phone_volume" to "phone_press_key",
            "phone_media" to "phone_press_key",
            "phone_back" to "phone_back",
        )
    }
}
