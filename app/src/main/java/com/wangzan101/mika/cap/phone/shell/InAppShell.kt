package com.wangzan101.mika.cap.phone.shell

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.shell.HelperlessRouting
import com.wangzan101.mika.core.shell.HelperlessRouting.KeyPath
import com.wangzan101.mika.core.shell.InAppDisplayFlags
import com.wangzan101.mika.shell.IMikaShell

/**
 * The shell surface answered with the app's own public APIs, for when the shell-uid helper is not running
 * (no Wi-Fi debugging, rebooted, never started). No root, no debugging.
 *
 * What it really does: owns a virtual display (an ordinary app-owned one, not the helper's trusted one), opens an
 * app on it or on the main screen, tells who is on which screen from the accessibility windows, drives volume and
 * media through AudioManager and sends BACK through the accessibility global action when that is provably aimed at
 * the agent display. Everything the app cannot do honestly (arbitrary key injection, pasting, other users, task
 * moves) answers a conservative default; the caller turns those into an immediate `unavailable` naming the helper.
 *
 * Never throws for "not possible": defaults only, so no caller is left waiting.
 */
class InAppShell(private val context: Context) : IMikaShell.Stub() {
    private val myUser = Process.myUid() / 100_000
    private val displays = HashMap<Int, VirtualDisplay>()

    private fun a11y(): ScreenAccessibility? = ScreenAccessibility.instance.value

    private fun own(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    // ---- virtual display ----

    /** Why the last [createDisplay] returned -1, as one fixed word; the host names it in the `unavailable` answer. */
    @Volatile var lastCreateRefusal: String? = null
        private set

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        lastCreateRefusal = null
        val manager = context.getSystemService(DisplayManager::class.java)
        if (manager == null) {
            lastCreateRefusal = "display_manager_missing"
            return -1
        }
        for (flags in InAppDisplayFlags.candidates(Build.VERSION.SDK_INT)) {
            var made: VirtualDisplay? = null
            try {
                val vd = manager.createVirtualDisplay("mika-agent-app", width, height, dpi, surface, flags)
                    ?: throw IllegalStateException("null display")
                made = vd
                val id = vd.display.displayId
                synchronized(displays) { displays[id] = vd }
                Log.i(TAG, "app-owned display $id created, flags=0x${flags.toString(16)}")
                return id
            } catch (t: Throwable) {
                runCatching { made?.release() }
                lastCreateRefusal = "display_create_refused"
                Log.w(TAG, "createVirtualDisplay flags=0x${flags.toString(16)} refused: ${t.javaClass.simpleName}")
            }
        }
        return -1
    }

    override fun releaseDisplay(displayId: Int) {
        val vd = synchronized(displays) { displays.remove(displayId) } ?: return
        runCatching { vd.release() }
    }

    override fun releaseAll() {
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        all.forEach { runCatching { it.release() } }
    }

    override fun destroy() = releaseAll()

    // Keeping apps behind the main screen needs task moves (helper). The app only releases its display; the system
    // closes the tasks with it (DESTROY_CONTENT_ON_REMOVAL) and the note says so.
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        if (!own(displayId)) return null
        releaseDisplay(displayId)
        return Bundle().apply {
            putStringArray(AppsAfterTask.KEY_KEPT, emptyArray())
            putStringArray(AppsAfterTask.KEY_CLOSED, emptyArray())
            putString(AppsAfterTask.KEY_NOTE, AppsAfterTask.NOTE_MOVE_UNSUPPORTED)
        }
    }

    // ---- opening apps ----

    override fun startApp(displayId: Int, packageName: String): Int = launch(displayId, packageName, cold = false)

    // A cold start needs to kill the process, which only the helper may do: open normally and say the cold part was ignored.
    override fun restartApp(displayId: Int, packageName: String): Int =
        launch(displayId, packageName, cold = true).let { if (it == ShellService.START_OK) ShellService.START_OK_COLD_IGNORED else it }

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int = when {
        userId != myUser -> ShellService.START_USER_NOT_ALLOWED
        cold -> restartApp(displayId, packageName)
        else -> startApp(displayId, packageName)
    }

    private fun launch(displayId: Int, packageName: String, cold: Boolean): Int {
        if (!own(displayId)) return ShellService.START_ERROR
        if (visibleOnMainScreen(packageName, myUser)) return ShellService.START_IN_USE_ON_MAIN
        val elsewhere = displayOfPackage(packageName)
        if (elsewhere >= 0 && elsewhere != displayId) return ShellService.START_IN_USE_BY_OTHER
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return ShellService.START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        BackgroundLaunchTrace.note(context)
        return try {
            // This path is called by the foreground link service, including immediately after boot when no Activity
            // has ever run in this process. A direct Context.startActivity is denied by BAL for a target-35 app in
            // that state. Delegate the launch through a PendingIntent and explicitly grant the creator's BAL
            // privilege (Android 15+) while preserving the requested virtual display.
            val launch = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            if (Build.VERSION.SDK_INT >= 34) {
                launch.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            if (Build.VERSION.SDK_INT >= 35) {
                launch.pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                launch.toBundle(),
            )
            pending.send(launch.toBundle())
            ShellService.START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity on display $displayId failed: ${t.javaClass.simpleName} cold=$cold")
            ShellService.START_ERROR
        }
    }

    override fun startAppOnMain(packageName: String): Int {
        if (displayOfPackage(packageName) >= 0) return START_OPEN_ON_AGENT_DISPLAY
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return ShellService.START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        BackgroundLaunchTrace.note(context)
        return try {
            context.startActivity(intent)
            ShellService.START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity on main failed: ${t.javaClass.simpleName}")
            ShellService.START_ERROR
        }
    }

    // ---- who is where, from the accessibility windows ----

    /** The application windows on one display: who owns the readable ones, and how many have an unreadable root. */
    private class AppWindows(val packages: Set<String>, val unreadable: Int)

    private fun appWindowsOn(displayId: Int): AppWindows {
        val windows = runCatching { a11y()?.windowsOn(displayId) }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val owners = windows.map { runCatching { it.root?.packageName?.toString() }.getOrNull() }
        return AppWindows(owners.filterNotNull().toSet(), owners.count { it == null })
    }

    /** Packages with a readable application window on [displayId]. */
    private fun appPackagesOn(displayId: Int): Set<String> = appWindowsOn(displayId).packages

    /** The user is using [packageName] on the main screen. Other users' copies are not visible to the app: false. */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean {
        if (userId != myUser) return false
        // Without the accessibility service nothing can be seen: say "in use", the cautious answer.
        if (a11y() == null) return true
        val main = appWindowsOn(Display.DEFAULT_DISPLAY)
        return HelperlessRouting.inUseOnMain(packageName, main.packages, main.unreadable)
    }

    override fun displayOfPackage(packageName: String): Int {
        val ids = synchronized(displays) { displays.keys.toList() }
        return ids.firstOrNull { packageName in appPackagesOn(it) } ?: -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int =
        if (userId == myUser) displayOfPackage(packageName) else -1

    // Task bookkeeping belongs to the activity manager: the caller falls back to the accessibility windows.
    override fun topTaskId(displayId: Int): Int = -1
    override fun packageOfTask(taskId: Int): String? = null
    override fun displayOfTask(taskId: Int): Int = -1
    override fun userOfTask(taskId: Int): Int = -1
    override fun topActivity(displayId: Int): String? = null
    override fun moveTask(taskId: Int, displayId: Int): Boolean = false
    override fun watchMainScreen(windowMs: Int): Int = -1
    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = null

    /**
     * The window manager's secure-window verdict is not available to an app, so it is derived from what the app sees
     * (see [HelperlessRouting.appWindowVerdict]): windows on the display = clear, none = empty, no accessibility
     * service = unknown (fail-closed). Reporting "unknown" always made every read and action fail once the display
     * existed. Not claimed for displays this shell did not create.
     */
    override fun virtualWindowProtection(displayId: Int): Int {
        if (!own(displayId)) return HelperlessRouting.VERDICT_UNKNOWN
        val service = a11y()
        val count = runCatching { service?.windowsOn(displayId)?.size }.getOrNull()
        return HelperlessRouting.appWindowVerdict(accessibilityUp = service != null && count != null, windowCount = count ?: 0)
    }

    // ---- keys ----

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!own(displayId) && keyCode != 10_001) return false
        return when (HelperlessRouting.keyPath(keyCode, helperUp = false, sdk = Build.VERSION.SDK_INT)) {
            KeyPath.AppAudio -> audioKey(keyCode)
            KeyPath.AppGlobalBack -> globalBack(displayId)
            KeyPath.AppGlobalDpad -> globalDpad(displayId, keyCode)
            KeyPath.AppGlobalAction -> globalKeyAction(displayId, keyCode)
            else -> {
                Log.w(TAG, "key $keyCode needs the helper")
                false
            }
        }
    }

    private fun audioKey(keyCode: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                KeyEvent.KEYCODE_VOLUME_MUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                else -> {
                    val now = System.currentTimeMillis()
                    audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                    audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "audio key $keyCode failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /** Whether a global BACK would provably land on the agent display now (see [HelperlessRouting.backIsSafe]). */
    fun canGoBack(displayId: Int): Boolean {
        val service = a11y() ?: return false
        fun active(id: Int) = runCatching { service.windowsOn(id).any { it.isActive || it.isFocused } }.getOrDefault(false)
        return own(displayId) && HelperlessRouting.backIsSafe(active(displayId), active(Display.DEFAULT_DISPLAY))
    }

    private fun globalBack(displayId: Int): Boolean {
        val service = a11y() ?: return false
        if (!canGoBack(displayId)) return false
        return runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
    }

    /** D-pad through the accessibility global action (Android 13+), only under the same focus proof as BACK. */
    private fun globalDpad(displayId: Int, keyCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val service = a11y() ?: return false
        if (!canGoBack(displayId)) return false
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
            else -> return false
        }
        return runCatching { service.performGlobalAction(action) }.getOrDefault(false)
    }

    /** Android global navigation actions are allowed only when this agent display alone owns focus. */
    private fun globalKeyAction(displayId: Int, keyCode: Int): Boolean {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_HOME -> HelperlessRouting.GlobalAction.HOME
            KeyEvent.KEYCODE_APP_SWITCH -> HelperlessRouting.GlobalAction.RECENTS
            KeyEvent.KEYCODE_NOTIFICATION -> HelperlessRouting.GlobalAction.NOTIFICATIONS
            KeyEvent.KEYCODE_SLEEP -> HelperlessRouting.GlobalAction.LOCK_SCREEN
            KeyEvent.KEYCODE_SYSRQ -> HelperlessRouting.GlobalAction.TAKE_SCREENSHOT
            10_001 -> HelperlessRouting.GlobalAction.QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(displayId, action)
    }

    /** Shared route for the accessibility actions (including QUICK_SETTINGS for future direct callers). */
    private fun performGlobalAction(displayId: Int, action: HelperlessRouting.GlobalAction): Boolean {
        val service = a11y() ?: return false
        fun active(id: Int) = runCatching { service.windowsOn(id).any { it.isActive || it.isFocused } }.getOrDefault(false)
        if (!HelperlessRouting.globalActionIsSafe(action, active(displayId), active(Display.DEFAULT_DISPLAY))) return false
        val platformAction = when (action) {
            HelperlessRouting.GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            HelperlessRouting.GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            HelperlessRouting.GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            HelperlessRouting.GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            HelperlessRouting.GlobalAction.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            HelperlessRouting.GlobalAction.TAKE_SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
        }
        return runCatching { service.performGlobalAction(platformAction) }.getOrDefault(false)
    }

    override fun musicVolumeState(): Bundle? = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        Bundle().apply {
            putInt("current", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            putInt("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            putBoolean("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
        }
    }.getOrNull()

    // The accessibility gestures were already tried by the caller; raw injection is the helper's.
    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = false
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean = false
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = AsciiTextKeys.NOT_SENT
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int = AsciiTextKeys.NOT_SENT

    // ---- clipboard (pasting needs injected Ctrl+V, so there is nothing to set) ----

    override fun setClipText(text: String): Int = ShellService.CLIP_FAILED
    override fun restoreClip(): Boolean = true
    override fun savedClipText(): String? = null
    override fun restoreClipText(original: String, ours: String): Boolean = true

    // ---- audio app-ops, users, setup commands, device credentials: shell privileges ----

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = false
    override fun getAudioMode(packageName: String): String? = null
    override fun setAudioMode(packageName: String, mode: String): Boolean = false
    override fun getAudioModeInUser(packageName: String, userId: Int): String? = null
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean = false
    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean = false

    override fun isSystemPackage(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    override fun listUsers(): String? = null
    override fun listPackages(userId: Int): String? = null
    override fun appCopyUsers(): Bundle? = null

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = ShellService.START_ERROR
    override fun startShare(displayId: Int, packageName: String, text: String): Int = ShellService.START_ERROR
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int = ShellService.START_ERROR

    override fun runSystemCommand(displayId: Int, command: String): Bundle = needsHelperBundle()
    override fun runCommand(command: String): Bundle = needsHelperBundle()
    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? = null

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())
    override fun setRestoreWish(flags: Int) = Unit
    override fun wakeDevice(): Boolean = com.wangzan101.mika.device.ScreenWakeup.wake(context)
    override fun dismissDeviceKeyguard(): Boolean = false
    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean = false
    override fun revealDeviceCredential(): Boolean = false

    private fun needsHelperBundle() = Bundle().apply {
        putInt("exitCode", -1)
        putString("output", "helper_required")
    }

    private companion object {
        const val TAG = "InAppShell"

        /** startAppOnMain: the app already has a task on an agent display (same code as the helper's). */
        const val START_OPEN_ON_AGENT_DISPLAY = 7
    }
}
