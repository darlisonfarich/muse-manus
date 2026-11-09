package com.wangzan101.mika.mcp

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.task.GerIntentAttempt
import com.wangzan101.mika.task.GerIntentOutcome
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.mcp.DirectPhoneHost
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.settings.McpSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    @Volatile private var closeAfterLaunch = false
    private val journal = File(context.filesDir, "direct-phone-audit.jsonl")
    // Append-only fsync’d batch progress; the audit remains the replay gate.
    private val checkpoints = DirectPhoneBatchCheckpoints(File(context.filesDir, "direct-phone-batches.log"))
    private val seen = HashSet<String>()
    private var loaded = false
    private var current: Session? = null
    private var step = 0
    private val ttlMs = 15 * 60_000L

    private class Session(
        val owner: String,
        val binding: ShellBinding,
        val display: LazyAgentDisplay,
        val screen: ScreenPort,
        val tools: ToolDispatcher,
        val executor: ActionExecutor,
        val releaseBinding: () -> Unit,
        var lastUsed: Long,
    ) {
        fun close() { display.current?.release(); releaseBinding() }
    }

    suspend fun close() {
        // A stopped listener cancels queued calls, but an already issued Binder
        // start cannot safely be interrupted or have its display torn down.
        app.resources.ger.intentions.cancelAll()
        lock.withLock {
            if (launchBoundary.isInFlight()) closeAfterLaunch = true else closeActive()
        }
    }

    private fun onLaunchSettled() {
        launchScope.launch {
            lock.withLock {
                if (closeAfterLaunch && !launchBoundary.isInFlight()) {
                    closeAfterLaunch = false
                    closeActive()
                }
            }
        }
    }

    private fun directOwner(owner: String): String = "mcp:" + digest(owner)

    private fun closeActive() {
        val session = current ?: return
        current = null
        val key = directOwner(session.owner)
        try {
            session.close()
        } finally {
            // Release every package held by this screen before revoking its GER lease.
            app.resources.releaseOwner(key)
            app.resources.ger.releaseDirect(key)
        }
    }

    /**
     * Cancellation/status are out-of-band. All other phone_* calls go through
     * GER's per-owner FIFO, NOT through TaskCenter's persistent task queue.
     * The host mutex is held for an atomic attempt, never for parked waiting.
     */
    override suspend fun call(owner: String, name: String, args: JsonObject): JsonObject {
        if (owner.isBlank()) return fail("unauthenticated")
        val scheduler = app.resources
        val sessionOwner = directOwner(owner)
        if (name == "phone_reconcile") {
            if (!settings.enabled.value) return fail("remote_access_disabled")
            // Read-only and out of the intention queue: no display/session is
            // created, no action is submitted, no pending marker is written.
            return lock.withLock { reconcile(owner, args) }
        }
        if (name == "phone_cancel") {
            val cancelled = scheduler.ger.intentions.cancelOwner(sessionOwner)
            return lock.withLock {
                val hadSession = current?.owner == owner
                val deferredClose = hadSession && launchBoundary.isInFlight()
                if (deferredClose) closeAfterLaunch = true
                else if (hadSession) closeActive()
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
                put("ok", true); put("active", current?.owner == owner)
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
                if (isCancelled()) return@attempt GerIntentAttempt.Done(fail("cancelled"))
                if (!settings.enabled.value) return@attempt GerIntentAttempt.Done(fail("remote_access_disabled"))
                if (name !in READS && !settings.directWritesEnabled.value)
                    return@attempt GerIntentAttempt.Done(fail("write_disabled"))
                val now = System.currentTimeMillis()
                if (current != null && now - current!!.lastUsed > ttlMs) closeActive()
                if (busy()) return@attempt GerIntentAttempt.Blocked("task_running")
                if (current != null && current!!.owner != owner)
                    return@attempt GerIntentAttempt.Blocked("direct_session_busy")
                if (!scheduler.ger.claimDirect(sessionOwner))
                    return@attempt GerIntentAttempt.Blocked(
                        if (scheduler.ger.hasRunningTasks()) "task_running" else "direct_session_busy"
                    )
                val s = try {
                    current ?: create(owner, now).also { current = it }
                } catch (e: CancellationException) {
                    if (current == null) scheduler.ger.releaseDirect(sessionOwner)
                    throw e
                } catch (_: Exception) {
                    if (current == null) scheduler.ger.releaseDirect(sessionOwner)
                    return@attempt GerIntentAttempt.Done(fail("phone_unavailable"))
                }
                if (!scheduler.ger.canUseDirect(sessionOwner))
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
                                    !scheduler.ger.canUseDirect(sessionOwner) -> GerIntentAttempt.Blocked("task_running")
                                    sensitive(s) -> GerIntentAttempt.Done(fail("needs_user"))
                                    else -> GerIntentAttempt.Done(observed)
                                }
                            }
                        }
                        "phone_screenshot" -> {
                            val result = screenshot(s)
                            if (!scheduler.ger.canUseDirect(sessionOwner)) GerIntentAttempt.Blocked("task_running")
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
            app.resources.ger.canUseDirect(directOwner(s.owner))
    }

    private fun readyForGate(binding: ShellBinding): Boolean {
        val lock = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return lock?.isDeviceLocked != true && lock?.isKeyguardLocked != true &&
            power?.isInteractive != false && binding.shell.value != null &&
            ScreenAccessibility.instance.value != null
    }

    private suspend fun create(owner: String, now: Long): Session {
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
                        !busy() && scheduler.ger.canUseDirect(sessionOwner) &&
                            scheduler.reserveApp(pkg, sessionOwner, scheduler.ger::isActiveOwner, { true })
                    },
                    reserveCopy = { pkg, user ->
                        !busy() && scheduler.ger.canUseDirect(sessionOwner) &&
                            scheduler.reserveApp(claimKey(pkg, user), sessionOwner, scheduler.ger::isActiveOwner, { true })
                    },
                    appFree = { pkg, user ->
                        !busy() && scheduler.ger.canUseDirect(sessionOwner) &&
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
                gate = { check(settings.directWritesEnabled.value && settings.enabled.value && !busy() &&
                    scheduler.ger.canUseDirect(sessionOwner) && readyForGate(binding)) },
                recorder = ActionRecorder(sink), approvals = ApprovalSettings.STRICT,
                // Bridge origin is STRICT in ApprovalPolicy; generic Mcp runs only use ASK.
                // Origin.McpTool never consumes a remote approval/grant.
                provenance = RunProvenance(RunOrigin.Bridge, Attendance.Unattended),
                selfPackage = context.packageName,
            )
            return Session(owner, binding, display, safe, tools, executor, services.sharedShell::release, now)
        } catch (e: Exception) {
            services.sharedShell.release()
            throw e
        }
    }

    private fun secretNode(node: UiTreeNode): Boolean = node.secret || node.children.any(::secretNode)
    private suspend fun sensitive(s: Session): Boolean {
        val v = s.screen.peek()
        return v.protected || v.captureError != null || v.elements.any { it.secret } ||
            v.windows.any { w -> w.nodes.any(::secretNode) } || s.screen.isProtected()
    }

    private suspend fun screenshot(s: Session): JsonObject {
        if (sensitive(s)) return fail("needs_user")
        val png = s.screen.screenshotPng() ?: return fail("screenshot_unavailable")
        // Recheck after capture, preventing the usual check-then-screen-transition race.
        if (sensitive(s)) return fail("needs_user")
        if (png.size <= 600_000) return encoded(png, "image/png")
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return fail("screenshot_unavailable")
        var scaled: Bitmap = bmp
        try {
            var quality = 78
            repeat(9) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= 600_000) return encoded(out.toByteArray(), "image/jpeg")
                quality = (quality - 8).coerceAtLeast(30)
                val next = Bitmap.createScaledBitmap(scaled,
                    (scaled.width * 0.75).toInt().coerceAtLeast(1),
                    (scaled.height * 0.75).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) scaled.recycle()
                scaled = next
            }
            return fail("payload_too_large")
        } finally {
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
    }

    private fun encoded(data: ByteArray, mime: String): JsonObject = buildJsonObject {
        put("ok", true); put("mimeType", mime)
        put("data", Base64.getEncoder().encodeToString(data))
        put("sizeBytes", data.size)
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
            if (busy() || !app.resources.ger.canUseDirect(directOwner(s.owner)))
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
        if (!settings.enabled.value || !settings.directWritesEnabled.value)
            return GerIntentAttempt.Done(fail("write_disabled"))
        if (busy() || !app.resources.ger.canUseDirect(directOwner(s.owner)))
            return GerIntentAttempt.Blocked("task_running")
        if (!ready(s) || sensitive(s)) return GerIntentAttempt.Done(fail("needs_user"))
        val request = requestId(args) ?: return GerIntentAttempt.Done(fail("request_id_required"))
        val clean = JsonObject(args.filterKeys { it != "request_id" })
        // Recheck physical app ownership at the LAST pre-PENDING boundary.
        // Previously displayOfPackage() always missed the user's display 0,
        // then startApp returned app_in_use_by_user AFTER markOnce had already
        // committed this ID, leaving the queue unable to retry it.
        launchBlocker(s, external, args)?.let { return GerIntentAttempt.Blocked(it) }
        // A still-running launch must not commit another request's PENDING.
        if (launchBoundary.isInFlight() || closeAfterLaunch)
            return GerIntentAttempt.Done(fail("launch_in_flight"))
        val key = digest(s.owner + "|" + request)
        if (!markOnce(key, external, clean.keys.sorted().joinToString(",")))
            return GerIntentAttempt.Done(fail("delivery_unknown"))
        val sequence = ++step
        if (external == "phone_launch_app") {
            val result = try {
                launchBoundary.execute {
                    // Detached only AFTER the fsync'd PENDING marker. Android
                    // Binder cannot be interrupted safely; the worker records
                    // its eventual outcome even when the MCP caller timed out.
                    val actual = try {
                        s.executor.run(Part.ToolCall(request, tool, clean.toString()), Origin.McpTool(sequence))
                            .execution().content
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
            s.executor.run(Part.ToolCall(request, tool, clean.toString()), Origin.McpTool(sequence))
                .execution().content
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail("delivery_unknown") }
        audit(key, "completed", if (result["ok"] == JsonPrimitive(true)) "ok" else "not_applied")
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

    private fun fail(code: String) = buildJsonObject {
        put("ok", false); put("error", code); put("executed", false)
    }

    companion object {
        private val READS = DirectPhoneScreenPolicy.reads
        private val ACTIONS = mapOf(
            "phone_launch_app" to "phone_launch_app",
            "phone_tap" to "phone_tap",
            "phone_type" to "phone_type_text",
            "phone_scroll" to "phone_scroll",
            "phone_swipe" to "phone_swipe",
            "phone_gesture" to "phone_gesture",
            "phone_key" to "phone_press_key",
            "phone_back" to "phone_back",
        )
    }
}
