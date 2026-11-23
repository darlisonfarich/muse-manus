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

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return -1
        for (flags in InAppDisplayFlags.candidates(Build.VERSION.SDK_INT)) {
            try {
                val vd = manager.createVirtualDisplay("mika-agent-app", width, height, dpi, surface, flags)
                val id = vd.display.displayId
                synchronized(displays) { displays[id] = vd }
                Log.i(TAG, "app-owned display $id created, flags=0x${flags.toString(16)}")
                return id
            } catch (t: Throwable) {
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
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
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

    /** Packages with a readable application window on [displayId]. A window whose root cannot be read has no owner here. */
    private fun appPackagesOn(displayId: Int): Set<String> {
        val windows = runCatching { a11y()?.windowsOn(displayId) }.getOrNull().orEmpty()
        return windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { runCatching { it.root?.packageName?.toString() }.getOrNull() }.toSet()
    }

    /** The user is using [packageName] on the main screen. Other users' copies are not visible to the app: false. */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean {
        if (userId != myUser) return false
        // Without the accessibility service nothing can be seen: say "in use", the cautious answer.
        if (a11y() == null) return true
        return packageName in appPackagesOn(Display.DEFAULT_DISPLAY)
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
     * The window manager's secure-window verdict is not available to an app. Report "unknown" (0): the host treats an
     * unknown verdict as fail-closed for reads that expose pixels, so nothing sensitive leaks by assuming "clear".
     */
    override fun virtualWindowProtection(displayId: Int): Int = 0

    // ---- keys ----

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!own(displayId)) return false
        return when (HelperlessRouting.keyPath(keyCode, helperUp = false)) {
            KeyPath.AppAudio -> audioKey(keyCode)
            KeyPath.AppGlobalBack -> globalBack(displayId)
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
    override fun wakeDevice(): Boolean = false
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
