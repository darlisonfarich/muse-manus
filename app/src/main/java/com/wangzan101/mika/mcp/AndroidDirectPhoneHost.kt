package com.wangzan101.mika.mcp

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.view.Display
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
import kotlinx.coroutines.Dispatchers
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
    private val journal = File(context.filesDir, "direct-phone-audit.jsonl")
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

    suspend fun close() = lock.withLock { closeActive() }

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
        if (name == "phone_cancel") {
            val cancelled = scheduler.ger.intentions.cancelOwner(sessionOwner)
            return lock.withLock {
                val hadSession = current?.owner == owner
                if (hadSession) closeActive()
                if (!hadSession && cancelled == 0) fail("session_not_found")
                else buildJsonObject { put("ok", true); put("closed", hadSession); put("cancelledIntents", cancelled) }
            }
        }
        if (name == "phone_status" || name == "phone_get_state") return lock.withLock {
            buildJsonObject {
                put("ok", true); put("active", current?.owner == owner)
                put("writeEnabled", settings.enabled.value && settings.directWritesEnabled.value)
                put("busy", busy()); put("sessionTtlMs", ttlMs)
                put("pendingIntents", scheduler.ger.intentions.pendingCount(sessionOwner))
            }
        }
        if (name !in READS && name != "phone_run" && name !in ACTIONS) return fail("unknown_tool")
        // A rejected/missing ID must not occupy a queue slot. Read tools have no
        // request_id in the unchanged MCP schema and get a synthetic queue key.
        val request = if (name in READS) UUID.randomUUID().toString() else requestId(args)
            ?: return fail("request_id_required")
        val progress = BatchProgress()
        val outcome = scheduler.ger.intentions.dispatch(
            sessionOwner, request, settings.directWaitTimeoutMs,
        ) { isCancelled ->
            lock.withLock {
                if (isCancelled()) return@withLock GerIntentAttempt.Done(fail("cancelled"))
                if (!settings.enabled.value) return@withLock GerIntentAttempt.Done(fail("remote_access_disabled"))
                if (name !in READS && !settings.directWritesEnabled.value)
                    return@withLock GerIntentAttempt.Done(fail("write_disabled"))
                val now = System.currentTimeMillis()
                if (current != null && now - current!!.lastUsed > ttlMs) closeActive()
                if (busy()) return@withLock GerIntentAttempt.Blocked("task_running")
                if (current != null && current!!.owner != owner)
                    return@withLock GerIntentAttempt.Blocked("direct_session_busy")
                if (!scheduler.ger.claimDirect(sessionOwner))
                    return@withLock GerIntentAttempt.Blocked(
                        if (scheduler.ger.hasRunningTasks()) "task_running" else "direct_session_busy"
                    )
                val s = try {
                    current ?: create(owner, now).also { current = it }
                } catch (e: CancellationException) {
                    if (current == null) scheduler.ger.releaseDirect(sessionOwner)
                    throw e
                } catch (_: Exception) {
                    if (current == null) scheduler.ger.releaseDirect(sessionOwner)
                    return@withLock GerIntentAttempt.Done(fail("phone_unavailable"))
                }
                if (!scheduler.ger.canUseDirect(sessionOwner))
                    return@withLock GerIntentAttempt.Blocked("task_running")
                if (isCancelled()) return@withLock GerIntentAttempt.Done(fail("cancelled"))
                s.lastUsed = now
                try {
                    if (!ready(s)) return@withLock GerIntentAttempt.Done(fail("needs_user"))
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
                            else GerIntentAttempt.Done(action(s, name, args))
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { GerIntentAttempt.Done(fail("phone_unavailable")) }
            }
        }
        return when (outcome) {
            is GerIntentOutcome.Completed -> outcome.result
            is GerIntentOutcome.TimedOut -> buildJsonObject {
                put("ok", false); put("error", "wait_timeout"); put("cause", outcome.cause); put("executed", false)
            }
            GerIntentOutcome.Cancelled -> fail("cancelled")
            GerIntentOutcome.Duplicate -> fail("delivery_unknown")
            GerIntentOutcome.QueueFull -> fail("intent_queue_full")
        }
    }

    /** Physical/default display belongs to the user. A living second display belongs to another executor. */
    private fun launchBlocker(s: Session, name: String, args: JsonObject): String? {
        if (name != "phone_launch_app") return null
        val pkg = (args["package"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val scheduler = app.resources
        val owner = directOwner(s.owner)
        if (!scheduler.appFree(pkg, owner, scheduler.ger::isActiveOwner, { true })) return "app_busy"
        val shell = s.binding.shell.value ?: return null // ready() will reject if disconnected.
        val existingDisplay = runCatching { shell.displayOfPackage(pkg) }.getOrNull() ?: return null
        if (existingDisplay >= 0 && existingDisplay != (s.display.current?.displayId ?: -1))
            return if (existingDisplay == Display.DEFAULT_DISPLAY) "app_in_use_by_user" else "app_busy"
        return null
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

    /** Volatile progress survives a wait within one MCP call; process-death recovery is phase 3. */
    private class BatchProgress {
        var index = 0
        val results = mutableListOf<JsonObject>()
    }

    private suspend fun batch(
        s: Session, args: JsonObject, progress: BatchProgress, cancelled: () -> Boolean,
    ): GerIntentAttempt<JsonObject> {
        val id = requestId(args) ?: return GerIntentAttempt.Done(fail("request_id_required"))
        val commands = args["commands"] as? JsonArray ?: return GerIntentAttempt.Done(fail("invalid_commands"))
        if (commands.isEmpty() || commands.size > 30) return GerIntentAttempt.Done(fail("invalid_commands"))
        while (progress.index < commands.size) {
            if (cancelled()) return GerIntentAttempt.Done(fail("cancelled"))
            if (busy() || !app.resources.ger.canUseDirect(directOwner(s.owner)))
                return GerIntentAttempt.Blocked("task_running")
            val raw = commands[progress.index]
            val cmd = raw as? JsonObject ?: return GerIntentAttempt.Done(fail("invalid_commands"))
            val name = (cmd["name"] as? JsonPrimitive)?.contentOrNull ?: return GerIntentAttempt.Done(fail("invalid_commands"))
            if (name !in ACTIONS) return GerIntentAttempt.Done(fail("invalid_commands"))
            val params = cmd["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            launchBlocker(s, name, params)?.let { return GerIntentAttempt.Blocked(it) }
            // A deterministic ID per step makes a completed step non-replayable,
            // even if the MCP connection dies after step N and before the reply.
            val key = UUID.nameUUIDFromBytes((id + ":" + progress.index).toByteArray()).toString()
            val outcome = action(s, name, JsonObject(params + ("request_id" to JsonPrimitive(key))))
            // A concurrent task admission may occur before action() journals. In
            // that case there was no attempt: leave the step in the queue.
            if (outcome["error"] == JsonPrimitive("session_conflict"))
                return GerIntentAttempt.Blocked("task_running")
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

    private suspend fun action(s: Session, external: String, args: JsonObject): JsonObject {
        val tool = ACTIONS[external] ?: return fail("unknown_tool")
        if (!settings.enabled.value || !settings.directWritesEnabled.value) return fail("write_disabled")
        if (busy()) return fail("session_conflict")
        if (!ready(s) || sensitive(s)) return fail("needs_user")
        val request = requestId(args) ?: return fail("request_id_required")
        val clean = JsonObject(args.filterKeys { it != "request_id" })
        val key = digest(s.owner + "|" + request)
        if (!markOnce(key, external, clean.keys.sorted().joinToString(","))) return fail("delivery_unknown")
        val result = try {
            s.executor.run(Part.ToolCall(request, tool, clean.toString()), Origin.McpTool(++step))
                .execution().content
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail("delivery_unknown") }
        audit(key, "completed", if (result["ok"] == JsonPrimitive(true)) "ok" else "not_applied")
        return result
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

    private fun fail(code: String) = buildJsonObject {
        put("ok", false); put("error", code); put("executed", false)
    }

    companion object {
        private val READS = setOf("phone_observe", "phone_list_apps", "phone_screenshot")
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
