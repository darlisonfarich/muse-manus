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
    /** AccessibilityService global actions considered by the helperless phone route. */
    enum class GlobalAction { HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, TAKE_SCREENSHOT }
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

        /** D-pad keys (Android 13+): the accessibility global D-pad action, under the same focus proof as BACK. */
        AppGlobalDpad,

        /** System navigation/global actions, only when the agent display is the sole focused display. */
        AppGlobalAction,

        /** No app-only route and no helper: refuse at once, saying a helper is needed. */
        NeedsHelper,
    }

    // volume up/down/mute, headset hook, play/pause, stop, next, previous, rewind, fast-forward, play, pause
    private val AUDIO_KEYS = setOf(24, 25, 164, 79, 85, 86, 87, 88, 89, 90, 126, 127)
    private const val KEYCODE_BACK = 4

    /** DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, in the order of the accessibility D-pad actions. */
    private val DPAD_KEYS = setOf(19, 20, 21, 22, 23)
    // HOME, NOTIFICATION, APP_SWITCH, SYSRQ (screenshot), SLEEP (lock screen).
    private val GLOBAL_ACTION_KEYS = setOf(3, 83, 187, 120, 223, KEYCODE_QUICK_SETTINGS)
    private const val KEYCODE_QUICK_SETTINGS = 10_001
    private const val TIRAMISU = 33

    fun keyPath(keyCode: Int, helperUp: Boolean, sdk: Int = 0): KeyPath = when {
        keyCode == KEYCODE_QUICK_SETTINGS -> KeyPath.AppGlobalAction
        helperUp -> KeyPath.Helper
        keyCode in AUDIO_KEYS -> KeyPath.AppAudio
        keyCode == KEYCODE_BACK -> KeyPath.AppGlobalBack
        keyCode in DPAD_KEYS && sdk >= TIRAMISU -> KeyPath.AppGlobalDpad
        keyCode in GLOBAL_ACTION_KEYS -> KeyPath.AppGlobalAction
        else -> KeyPath.NeedsHelper
    }

    /**
     * The key is refused at once with `helper_required`: the screen's OWNER has no route for it. Decided by the owner
     * of the display, not by whether a helper is up now: a display the app created stays answered by the app even
     * after a helper appears, so "a helper is up" does not make the key deliverable there.
     */
    fun keyNeedsMissingHelper(owner: ShellOwner?, keyCode: Int, sdk: Int = 0): Boolean = when (owner) {
        ShellOwner.Helper -> false
        // No display yet: the session would build it with the app when no helper is up; ask for the app's routes.
        ShellOwner.InApp, null -> keyPath(keyCode, helperUp = false, sdk = sdk) == KeyPath.NeedsHelper
    }

    /**
     * BACK through the accessibility global action goes to whichever display holds input focus, so it is only
     * safe when the agent display's window is the active one and nothing on the user's main screen is.
     */
    fun backIsSafe(agentDisplayActive: Boolean, mainDisplayActive: Boolean): Boolean = agentDisplayActive && !mainDisplayActive

    /** Global actions target Android's current focus; refuse unless the agent alone owns focus. */
    fun globalActionIsSafe(agentDisplayActive: Boolean, mainDisplayActive: Boolean): Boolean =
        agentDisplayActive && !mainDisplayActive

    /** Every action in this set affects the current device UI, so require the same focus proof as BACK. */
    fun globalActionIsSafe(action: GlobalAction, agentDisplayActive: Boolean, mainDisplayActive: Boolean): Boolean =
        when (action) {
            GlobalAction.HOME, GlobalAction.RECENTS, GlobalAction.NOTIFICATIONS,
            GlobalAction.QUICK_SETTINGS, GlobalAction.LOCK_SCREEN, GlobalAction.TAKE_SCREENSHOT ->
                globalActionIsSafe(agentDisplayActive, mainDisplayActive)
        }

    /** True when the key can be delivered without the helper (used to answer `helper_required` before acting). */
    fun keyWorksWithoutHelper(keyCode: Int, sdk: Int = 0): Boolean = keyPath(keyCode, helperUp = false, sdk = sdk) != KeyPath.NeedsHelper

    /**
     * The window-manager verdict the helper normally gives (see `VirtualWindowProtection` codes 0 unknown, 1 empty,
     * 2 clear), derived from what the app itself sees when no helper answers. The app cannot tell FLAG_SECURE apart,
     * but its own ImageReader receives a black frame from such a window, so no pixel leaks, and a password/secret
     * window is still caught by the snapshot (`protected`, `isProtected()`). No accessibility service = unknown
     * (fail-closed). Windows on the display = clear; none = empty.
     */
    fun appWindowVerdict(accessibilityUp: Boolean, windowCount: Int): Int = when {
        !accessibilityUp -> VERDICT_UNKNOWN
        windowCount > 0 -> VERDICT_CLEAR
        else -> VERDICT_EMPTY
    }

    /**
     * The user is using [packageName] on the main screen, as far as the app can tell. An application window whose
     * root cannot be read has an unknown owner: it may well be this package, and opening the package elsewhere would
     * make the system pull the user's task onto the virtual display. So any unreadable application window counts as
     * "in use" (fail-closed).
     */
    fun inUseOnMain(packageName: String, readablePackages: Set<String>, unreadableAppWindows: Int): Boolean =
        packageName in readablePackages || unreadableAppWindows > 0

    const val VERDICT_UNKNOWN = 0
    const val VERDICT_EMPTY = 1
    const val VERDICT_CLEAR = 2
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
 *
 * A locked device or a dark screen is NOT here on purpose: reads on the app's virtual display work with the physical
 * screen off, so they do not block the session. They block only the actions that touch the physical phone, which
 * answer `needs_user` naming [PhoneState.cause].
 */
object SessionReadiness {
    const val REMOTE_ACCESS_DISABLED = "remote_access_disabled"
    const val LINK_DOWN = "gadget_link_down"
    const val ACCESSIBILITY_OFF = "accessibility_off"

    fun blocker(
        remoteAccessEnabled: Boolean,
        linkConnected: Boolean,
        accessibilityUp: Boolean,
    ): String? = when {
        !remoteAccessEnabled -> REMOTE_ACCESS_DISABLED
        !linkConnected -> LINK_DOWN
        !accessibilityUp -> ACCESSIBILITY_OFF
        else -> null
    }
}

/** Why a physical-phone action must wait for the person: the one word named in `needs_user`. */
object PhoneState {
    const val DEVICE_LOCKED = "device_locked"
    const val SCREEN_OFF = "screen_off"
    const val ACCESSIBILITY_OFF = "accessibility_off"

    /** Null = the phone is ready for an action. Locked wins over dark, so the person fixes the real thing first. */
    fun cause(accessibilityUp: Boolean, deviceLocked: Boolean, screenInteractive: Boolean): String? = when {
        !accessibilityUp -> ACCESSIBILITY_OFF
        deviceLocked -> DEVICE_LOCKED
        !screenInteractive -> SCREEN_OFF
        else -> null
    }
}

/**
 * Flag sets for a virtual display the app creates itself, richest first. The app is not allowed the trusted-only
 * flags (TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, OWN_FOCUS, STEAL_TOP_FOCUS_DISABLED) that the helper uses, and
 * some systems also refuse the hidden touch/rotation flags to ordinary apps, so each set is tried until one is accepted.
 * Every set keeps DESTROY_CONTENT_ON_REMOVAL: releasing the display closes its tasks instead of moving them onto the
 * user's main screen, which is what the shell's `finishDisplay` note promises.
 */
object InAppDisplayFlags {
    private const val PUBLIC = 1 shl 0
    private const val OWN_CONTENT_ONLY = 1 shl 3
    private const val SUPPORTS_TOUCH = 1 shl 6
    private const val ROTATES_WITH_CONTENT = 1 shl 7
    private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val TIRAMISU = 33

    fun candidates(sdk: Int): List<Int> {
        val base = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        val sets = buildList {
            if (sdk >= TIRAMISU) add(base or TOUCH_FEEDBACK_DISABLED)
            add(base)
            add(PUBLIC or OWN_CONTENT_ONLY or DESTROY_CONTENT_ON_REMOVAL)
        }
        return sets.distinct()
    }
}
