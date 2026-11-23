package com.wangzan101.mika.core.shell

/**
 * When the optional helper is kept warm on its own (a proactive wake-up, not only on a command's demand).
 *
 * The rule the owner set: the app NEVER turns Wi-Fi (or any radio) on by itself. So the wake-up only asks the
 * watchdog when the environment ALREADY lets the helper in — debugging reachable on a Wi-Fi that is already
 * connected — and otherwise stays dormant without insisting; the app's own accessibility channel carries everything.
 * Every input here is a read; the policy has no way to change a setting.
 */
object HelperWarmup {
    /** Minimum gap between two wake-up asks while nothing changed; the watchdog keeps its own backoff on top. */
    const val MIN_GAP_MS = 30_000L

    sealed interface Decision {
        /** Ask the watchdog to bring the helper up now. */
        data object Ask : Decision

        /** Nothing to do right now (already up, or asked a moment ago). */
        data class Idle(val reason: String) : Decision

        /** The environment does not let the helper in: stay quiet, do not insist, do not touch any setting. */
        data class Dormant(val reason: String) : Decision
    }

    /**
     * @param debuggingReachable the system's wireless-debugging switch as READ now (`null` = unreadable, treated as not reachable).
     * @param sinceLastAskMs time since the last [Decision.Ask] was acted on, `null` when never.
     */
    fun decide(
        helperUp: Boolean,
        rootReady: Boolean,
        paired: Boolean,
        developerOptions: Boolean?,
        debuggingReachable: Boolean?,
        wifiConnected: Boolean,
        sinceLastAskMs: Long?,
    ): Decision {
        if (helperUp) return Decision.Idle("helper_up")
        val throttled = sinceLastAskMs != null && sinceLastAskMs in 0 until MIN_GAP_MS
        // Root starts the helper without Wi-Fi and without the debugging switch.
        if (rootReady) return if (throttled) Decision.Idle("asked_recently") else Decision.Ask
        if (!paired) return Decision.Dormant("not_paired")
        if (developerOptions == false) return Decision.Dormant("developer_options_off")
        if (!wifiConnected) return Decision.Dormant("wifi_off")
        if (debuggingReachable != true) return Decision.Dormant("debugging_off")
        return if (throttled) Decision.Idle("asked_recently") else Decision.Ask
    }
}
