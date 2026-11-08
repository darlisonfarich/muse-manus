package com.wangzan101.mika.mcp

/**
 * Classifies a direct-host app launch BEFORE the durable PENDING audit marker.
 *
 * displayOfPackage() only reports Mika-owned virtual displays. The physical
 * display is deliberately excluded by ShellService, so a value of -1 CANNOT
 * prove the user has stopped using the app. The separate shell Binder method
 * visibleOnMainScreen() uses the very same task visibility predicate that
 * startApp()/restartApp() use to prevent stealing the user's visible app.
 *
 * Null is an unsuccessful query, not "free": fail closed and retry within GER's
 * bounded wait, rather than allowing a launch on an unknown user screen.
 * A package held on our *own* agent display is free to reuse; no user
 * display, reservation or task is taken over.
 */
internal object DirectPhoneLaunchPreflight {
    fun blockedCause(
        userVisible: Boolean?,
        reservedByOther: Boolean,
        agentDisplay: Int,
        ourDisplay: Int?,
    ): String? = when {
        userVisible == null -> "app_presence_unknown"
        userVisible -> "app_in_use_by_user"
        reservedByOther -> "app_busy"
        agentDisplay >= 0 && agentDisplay != ourDisplay -> "app_busy"
        else -> null
    }
}
