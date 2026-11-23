package com.wangzan101.mika.cap.phone.shell

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.gadget.GadgetLink
import com.wangzan101.mika.core.shell.DisplayLedger
import com.wangzan101.mika.core.shell.HelperlessRouting
import com.wangzan101.mika.core.shell.ShellOwner
import com.wangzan101.mika.shell.IMikaShell

/**
 * The one shell a gadget session talks to. The helper is an accelerator, not a requirement: while it is alive every
 * call goes through it exactly as before (trusted virtual display, real key injection); when it is not, the same
 * calls are answered by [inApp] with the app's own APIs. Who answers is decided by [HelperlessRouting].
 *
 * A display belongs to whoever created it. If the helper that made a display dies, [isCurrent] turns false so the
 * session is rebuilt (on an app-owned display) instead of talking to a screen that no longer exists.
 */
class RoutedShell(
    private val helper: () -> IMikaShell?,
    private val inApp: InAppShell,
    /** Where a fallback is recorded: the link's own log, so the person sees it when they copy the log. */
    private val note: (String) -> Unit = { GadgetLink.log(it) },
) : IMikaShell.Stub() {
    private val ledger = DisplayLedger()

    /** False once a display made by the helper outlived that helper (it died or a new process took over). */
    fun isCurrent(): Boolean = !ledger.orphaned(helper())

    /** True while the helper answers calls (the optional accelerator is up). */
    fun helperUp(): Boolean = helper() != null

    /** BACK can reach the agent display without the helper right now (see [InAppShell.canGoBack]). */
    fun canGoBackWithoutHelper(displayId: Int): Boolean = inApp.canGoBack(displayId)

    /** Who answers calls on [displayId] right now: its creator, or the app when that creator (the helper) is gone. */
    fun ownerOf(displayId: Int): ShellOwner = HelperlessRouting.displayOwner(ledger, displayId, helper() != null)

    /** Why the last display creation was refused, as a fixed word, for the `unavailable` answer. */
    fun createRefusal(): String = inApp.lastCreateRefusal ?: "display_create_failed"

    /** Releases only the displays this app created. The helper's are its own, and the helper is shared with tasks. */
    fun releaseOwn() = inApp.releaseAll()

    /**
     * A call that names no display. With the helper up it goes through it; if the helper fails AT that call (its process
     * died or its binder broke) the app's own answer comes back at once, in the same call: no wait for the helper to be
     * revived in the middle of a command. The revival is the watchdog's own job, in the background.
     */
    private fun <T> viaHelper(name: String, call: (IMikaShell) -> T): T {
        val h = helper() ?: return call(inApp)
        return try {
            call(h)
        } catch (e: RemoteException) {
            Log.w(TAG, "helper failed at $name; answering with the app's own", e)
            note("shell: helper_call_failed $name, answered_in_app")
            call(inApp)
        }
    }

    private fun on(displayId: Int): IMikaShell {
        val h = helper()
        return when (HelperlessRouting.displayOwner(ledger, displayId, h != null)) {
            ShellOwner.Helper -> h ?: inApp
            ShellOwner.InApp -> inApp
        }
    }

    // ---- display lifecycle ----

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        val h = helper()
        if (h != null && HelperlessRouting.newDisplayOwner(helperUp = true) == ShellOwner.Helper) {
            val id = runCatching { h.createDisplay(surface, width, height, dpi) }.getOrDefault(-1)
            if (id >= 0) {
                ledger.record(id, ShellOwner.Helper, h)
                return id
            }
            Log.w(TAG, "helper could not create the display; using the app's own")
            note("shell: helper_display_failed, using_app_display")
        }
        val id = inApp.createDisplay(surface, width, height, dpi)
        if (id >= 0) ledger.record(id, ShellOwner.InApp)
        else note("shell: app_display_refused ${inApp.lastCreateRefusal ?: "unknown"}")
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        runCatching { on(displayId).releaseDisplay(displayId) }
        ledger.forget(displayId)
    }

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        val result = runCatching { on(displayId).finishDisplay(displayId, keep, close) }.getOrNull()
        ledger.forget(displayId)
        return result
    }

    override fun releaseAll() {
        helper()?.let { runCatching { it.releaseAll() } }
        inApp.releaseAll()
        ledger.clear()
    }

    override fun destroy() = inApp.releaseAll()

    // ---- calls on a display ----

    override fun startApp(displayId: Int, packageName: String): Int = on(displayId).startApp(displayId, packageName)
    override fun restartApp(displayId: Int, packageName: String): Int = on(displayId).restartApp(displayId, packageName)
    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int =
        on(displayId).startAppInUser(displayId, packageName, userId, cold)
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int =
        on(displayId).startView(displayId, action, uri, packageName)
    override fun startShare(displayId: Int, packageName: String, text: String): Int = on(displayId).startShare(displayId, packageName, text)
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int =
        on(displayId).startSystemAction(displayId, intent, asMika)
    override fun topTaskId(displayId: Int): Int = on(displayId).topTaskId(displayId)
    override fun topActivity(displayId: Int): String? = on(displayId).topActivity(displayId)
    override fun moveTask(taskId: Int, displayId: Int): Boolean = on(displayId).moveTask(taskId, displayId)
    override fun injectKey(displayId: Int, keyCode: Int): Boolean =
        if (keyCode == 10_001) inApp.injectKey(displayId, keyCode) else on(displayId).injectKey(displayId, keyCode)
    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = on(displayId).injectTap(displayId, x, y)
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        on(displayId).injectStroke(displayId, xs, ys, times)
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = on(displayId).injectCtrlKey(displayId, keyCode)
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int =
        on(displayId).injectAsciiText(displayId, taskId, packageName, text)
    override fun runSystemCommand(displayId: Int, command: String): Bundle = on(displayId).runSystemCommand(displayId, command)
    override fun virtualWindowProtection(displayId: Int): Int = on(displayId).virtualWindowProtection(displayId)

    // ---- who has an app: the helper sees its own displays, the app sees its own ----

    override fun displayOfPackage(packageName: String): Int {
        val seen = helper()?.let { runCatching { it.displayOfPackage(packageName) }.getOrDefault(-1) } ?: -1
        return if (seen >= 0) seen else inApp.displayOfPackage(packageName)
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int {
        val seen = helper()?.let { runCatching { it.displayOfPackageInUser(packageName, userId) }.getOrDefault(-1) } ?: -1
        return if (seen >= 0) seen else inApp.displayOfPackageInUser(packageName, userId)
    }

    // ---- calls that name no display ----

    override fun startAppOnMain(packageName: String): Int = viaHelper("startAppOnMain") { it.startAppOnMain(packageName) }
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean = viaHelper("visibleOnMainScreen") { it.visibleOnMainScreen(packageName, userId) }
    override fun watchMainScreen(windowMs: Int): Int = viaHelper("watchMainScreen") { it.watchMainScreen(windowMs) }
    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = viaHelper("mainArrivals") { it.mainArrivals(sinceUptimeMs) }
    override fun displayOfTask(taskId: Int): Int = viaHelper("displayOfTask") { it.displayOfTask(taskId) }
    override fun packageOfTask(taskId: Int): String? = viaHelper("packageOfTask") { it.packageOfTask(taskId) }
    override fun userOfTask(taskId: Int): Int = viaHelper("userOfTask") { it.userOfTask(taskId) }
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = viaHelper("setAudioMuted") { it.setAudioMuted(packageName, muted) }
    override fun getAudioMode(packageName: String): String? = viaHelper("getAudioMode") { it.getAudioMode(packageName) }
    override fun setAudioMode(packageName: String, mode: String): Boolean = viaHelper("setAudioMode") { it.setAudioMode(packageName, mode) }
    override fun getAudioModeInUser(packageName: String, userId: Int): String? = viaHelper("getAudioModeInUser") { it.getAudioModeInUser(packageName, userId) }
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean =
        viaHelper("setAudioMutedInUser") { it.setAudioMutedInUser(packageName, userId, muted) }
    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean =
        viaHelper("setAudioModeInUser") { it.setAudioModeInUser(packageName, userId, mode) }
    override fun musicVolumeState(): Bundle? = viaHelper("musicVolumeState") { it.musicVolumeState() }
    override fun setClipText(text: String): Int = viaHelper("setClipText") { it.setClipText(text) }
    override fun restoreClip(): Boolean = viaHelper("restoreClip") { it.restoreClip() }
    override fun savedClipText(): String? = viaHelper("savedClipText") { it.savedClipText() }
    override fun restoreClipText(original: String, ours: String): Boolean = viaHelper("restoreClipText") { it.restoreClipText(original, ours) }
    override fun identity(): IntArray = viaHelper("identity") { it.identity() }
    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? = viaHelper("openDevtoolsSocket") { it.openDevtoolsSocket(name) }
    override fun listUsers(): String? = viaHelper("listUsers") { it.listUsers() }
    override fun listPackages(userId: Int): String? = viaHelper("listPackages") { it.listPackages(userId) }
    override fun isSystemPackage(packageName: String): Boolean = viaHelper("isSystemPackage") { it.isSystemPackage(packageName) }
    override fun runCommand(command: String): Bundle = viaHelper("runCommand") { it.runCommand(command) }
    override fun setRestoreWish(flags: Int) = viaHelper("setRestoreWish") { it.setRestoreWish(flags) }
    override fun appCopyUsers(): Bundle? = viaHelper("appCopyUsers") { it.appCopyUsers() }
    override fun wakeDevice(): Boolean = viaHelper("wakeDevice") { it.wakeDevice() }
    override fun dismissDeviceKeyguard(): Boolean = viaHelper("dismissDeviceKeyguard") { it.dismissDeviceKeyguard() }
    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean = viaHelper("enterDeviceCredential") { it.enterDeviceCredential(kind, credential) }
    override fun revealDeviceCredential(): Boolean = viaHelper("revealDeviceCredential") { it.revealDeviceCredential() }

    private companion object {
        const val TAG = "RoutedShell"
    }
}
