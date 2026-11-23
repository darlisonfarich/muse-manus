package com.wangzan101.mika.core.shell

import java.util.concurrent.ConcurrentHashMap

/** Who answers a shell call: the shell-uid helper (needs debugging) or the app itself (public APIs only). */
enum class ShellOwner { Helper, InApp }

/**
 * Routing rules of the gadget session when the helper may be absent. Pure on purpose: the Android glue
 * (`RoutedShell`, `InAppShell`) only asks these functions, so the rules are testable on the JVM.
 *
 * Rule of thumb: the helper is an optional accelerator. When it is alive we keep using it (trusted virtual
 * display, real key injection), so nothing regresses; when it is not, the app answers with its own APIs; when
 * neither can do the job the caller gets an immediate, named refusal instead of a timeout.
 */
object HelperlessRouting {
    /** A new virtual display: the helper's (trusted) one when it is alive, the app's own otherwise. */
    fun newDisplayOwner(helperUp: Boolean): ShellOwner = if (helperUp) ShellOwner.Helper else ShellOwner.InApp

    /** Calls that name no display (inspection, clipboard, users...). */
    fun globalOwner(helperUp: Boolean): ShellOwner = if (helperUp) ShellOwner.Helper else ShellOwner.InApp

    /**
     * Calls on a display: whoever created it answers. A helper-owned display whose helper is gone no longer exists
     * (the helper process owned its VirtualDisplay), so it falls to the app, which reports it as unknown.
     */
    fun displayOwner(ledger: DisplayLedger, displayId: Int, helperUp: Boolean): ShellOwner =
        when (ledger.ownerOf(displayId)) {
            ShellOwner.Helper -> if (helperUp) ShellOwner.Helper else ShellOwner.InApp
            ShellOwner.InApp -> ShellOwner.InApp
            null -> globalOwner(helperUp)
        }

    /** How a key can be delivered right now. */
    enum class KeyPath {
        /** The helper injects it on the display (the only way for most keys on a virtual display). */
        Helper,

        /** Volume and media: global effects, done with AudioManager. */
        AppAudio,

        /** Back: only when the accessibility service can prove the agent display holds input focus. */
        AppGlobalBack,

        /** No app-only route and no helper: refuse at once, saying a helper is needed. */
        NeedsHelper,
    }

    private val AUDIO_KEYS = setOf(24, 25, 164, 85, 86, 87, 88)
    private const val KEYCODE_BACK = 4

    fun keyPath(keyCode: Int, helperUp: Boolean): KeyPath = when {
        helperUp -> KeyPath.Helper
        keyCode in AUDIO_KEYS -> KeyPath.AppAudio
        keyCode == KEYCODE_BACK -> KeyPath.AppGlobalBack
        else -> KeyPath.NeedsHelper
    }

    /** True when the key can be delivered without the helper (used to answer `helper_required` before acting). */
    fun keyWorksWithoutHelper(keyCode: Int): Boolean = keyPath(keyCode, helperUp = false) != KeyPath.NeedsHelper
}

/**
 * Which owner made each virtual display. [helperToken] is the helper's identity at creation time, so a display the
 * helper made is recognised as orphaned once that helper died or was replaced.
 */
class DisplayLedger {
    private class Entry(val owner: ShellOwner, val helperToken: Any?)

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun record(displayId: Int, owner: ShellOwner, helperToken: Any? = null) {
        entries[displayId] = Entry(owner, if (owner == ShellOwner.Helper) helperToken else null)
    }

    fun forget(displayId: Int) {
        entries.remove(displayId)
    }

    fun clear() = entries.clear()

    fun ownerOf(displayId: Int): ShellOwner? = entries[displayId]?.owner

    fun ids(): Set<Int> = entries.keys.toSet()

    /** A helper-made display outlived the helper that made it (died, or a new helper process took over). */
    fun orphaned(currentHelper: Any?): Boolean =
        entries.values.any { it.owner == ShellOwner.Helper && it.helperToken !== currentHelper }
}

/**
 * Why the gadget session cannot exist right now, as one stable word, in the order the person can fix it.
 * Null means nothing blocks it. These words reach the caller as the detail of an `unavailable` answer.
 */
object SessionReadiness {
    const val REMOTE_ACCESS_DISABLED = "remote_access_disabled"
    const val LINK_DOWN = "gadget_link_down"
    const val ACCESSIBILITY_OFF = "accessibility_off"
    const val DEVICE_LOCKED = "device_locked"
    const val SCREEN_OFF = "screen_off"

    fun blocker(
        remoteAccessEnabled: Boolean,
        linkConnected: Boolean,
        accessibilityUp: Boolean,
        deviceLocked: Boolean,
        screenInteractive: Boolean,
    ): String? = when {
        !remoteAccessEnabled -> REMOTE_ACCESS_DISABLED
        !linkConnected -> LINK_DOWN
        !accessibilityUp -> ACCESSIBILITY_OFF
        deviceLocked -> DEVICE_LOCKED
        !screenInteractive -> SCREEN_OFF
        else -> null
    }
}
