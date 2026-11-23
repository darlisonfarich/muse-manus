package com.wangzan101.mika.core.shell

/**
 * Pure rules for keeping the gadget link alive with Mika's screen closed. The Android glue
 * (`GadgetConnectService`, `BootRestoreReceiver`, the Diagnostics buttons) only asks these functions, so the
 * decisions are testable on the JVM.
 */
object BackgroundSurvival {
    enum class StartDecision { Run, Stop }

    /**
     * What the link service does when Android calls `onStartCommand`.
     *
     * - [stopRequested] (the explicit "disconnect" action) always stops.
     * - [explicitStart] (the app or the boot receiver asked) runs, even before pairing, so the Diagnostics can say
     *   "not paired yet".
     * - Neither (a null intent: the system restarted the sticky service after killing the process) runs only when
     *   the user left the link on AND a pairing is saved. A restart must never resurrect a link the user turned off.
     */
    fun onStart(stopRequested: Boolean, explicitStart: Boolean, linkEnabled: Boolean, hasPairing: Boolean): StartDecision = when {
        stopRequested -> StartDecision.Stop
        explicitStart -> StartDecision.Run
        linkEnabled && hasPairing -> StartDecision.Run
        else -> StartDecision.Stop
    }

    /** Window in which a failed open is attributed to a background start. */
    const val BACKGROUND_LAUNCH_WINDOW_MS = 20_000L

    /** Fixed token appended to the hint of an open that did not reach the screen. */
    const val BACKGROUND_LAUNCH_TOKEN = "background_launch_blocked"

    /**
     * Android blocks `startActivity` from an app with no visible window and does not throw: the open just does not
     * happen. When the last launch was started with Mika's screen closed ([launchedFromBackgroundAtMs]) and it is
     * still recent, the failure hint names that cause instead of leaving a bare "not in front".
     */
    fun backgroundLaunchSuffix(launchedFromBackgroundAtMs: Long?, nowMs: Long): String =
        if (launchedFromBackgroundAtMs != null && nowMs - launchedFromBackgroundAtMs in 0..BACKGROUND_LAUNCH_WINDOW_MS)
            " Started while Mika's screen was closed; Android may have blocked it ($BACKGROUND_LAUNCH_TOKEN)."
        else ""

    /** Keeps the newest [max] lines of the persisted lifecycle log. */
    fun appendEvent(lines: List<String>, line: String, max: Int = 30): List<String> = (lines + line).takeLast(max)

    /**
     * One lifecycle line. Fixed vocabulary only: [event] is `[a-z_]{1,40}`, [detail] is a class name or short token;
     * anything else is dropped so the log never carries tokens, addresses or screen content.
     */
    fun eventLine(wallClock: String, event: String, detail: String? = null): String {
        val safeEvent = event.takeIf { SAFE.matches(it) } ?: "unknown_event"
        val safeDetail = detail?.takeIf { SAFE_DETAIL.matches(it) }
        return if (safeDetail == null) "$wallClock $safeEvent" else "$wallClock $safeEvent $safeDetail"
    }

    private val SAFE = Regex("[a-z_]{1,40}")
    private val SAFE_DETAIL = Regex("[A-Za-z0-9_.:=-]{1,60}")

    /** What the user must set once, per phone brand, so the system stops killing the link. */
    enum class SurvivalSetting { Autostart, BatteryUnrestricted, AppDetails }

    /** One way to open a settings screen; the first one the phone can resolve wins. */
    data class SettingsCandidate(
        val action: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val extras: Map<String, String> = emptyMap(),
        val withPackageUri: Boolean = false,
    )

    /**
     * Ordered candidates: the HyperOS/MIUI screens first (the ones that actually hold the switch there), then the
     * stock Android screen, ending with the app details page, which every phone has.
     */
    fun settingsCandidates(setting: SurvivalSetting, appPackage: String, appLabel: String): List<SettingsCandidate> = when (setting) {
        SurvivalSetting.Autostart -> listOf(
            SettingsCandidate(packageName = "com.miui.securitycenter", className = "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            SettingsCandidate(action = "miui.intent.action.OP_AUTO_START", packageName = "com.miui.securitycenter"),
            SettingsCandidate(action = APP_DETAILS, withPackageUri = true),
        )
        SurvivalSetting.BatteryUnrestricted -> listOf(
            SettingsCandidate(
                packageName = "com.miui.powerkeeper", className = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                extras = mapOf("package_name" to appPackage, "package_label" to appLabel),
            ),
            SettingsCandidate(action = IGNORE_BATTERY_REQUEST, withPackageUri = true),
            SettingsCandidate(action = IGNORE_BATTERY_LIST),
            SettingsCandidate(action = APP_DETAILS, withPackageUri = true),
        )
        SurvivalSetting.AppDetails -> listOf(SettingsCandidate(action = APP_DETAILS, withPackageUri = true))
    }

    /** First candidate the phone can open, or null when none resolves. */
    fun firstResolvable(candidates: List<SettingsCandidate>, resolves: (SettingsCandidate) -> Boolean): SettingsCandidate? =
        candidates.firstOrNull { resolves(it) }

    const val APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val IGNORE_BATTERY_REQUEST = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val IGNORE_BATTERY_LIST = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
}
