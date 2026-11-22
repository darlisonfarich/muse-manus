package com.wangzan101.mika.mcp

import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.access.AppAccessState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure policy for `phone_launch_app` with a display target (virtual display or the phone's main screen). */
internal object DirectPhoneMainLaunch {
    enum class Target(val wire: String) { Virtual("virtual"), Main("main") }

    /** The requested destination (virtual when absent); null when `target` is present but not one of the two values. */
    fun target(args: JsonObject): Target? {
        val value = args["target"] ?: return Target.Virtual
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return Target.entries.firstOrNull { it.wire == text }
    }

    /**
     * Why the local authorization refuses an opening on the main screen, or null to let it through. The codes are
     * fixed, content-free refusal codes (they travel as the detail of `local_gate_denied`). The opening on the
     * virtual display gets the same decisions from the action executor; the main screen has no executor in
     * between, so they are made here.
     */
    fun denial(
        stopped: Boolean,
        remoteEnabled: Boolean,
        writesEnabled: Boolean,
        access: AppAccessState,
        packageName: String?,
        ownPackage: String,
    ): String? = when {
        stopped -> "app_stopped"
        !remoteEnabled -> "remote_access_disabled"
        !writesEnabled -> "write_disabled"
        packageName == ownPackage -> "own_app"
        else -> access.denial(packageName)?.code
    }

    /** Result code for a non-OK `ShellService.START_*` value of `startAppOnMain`. */
    fun startFailure(startCode: Int): String = when (startCode) {
        ShellService.START_IN_USE_BY_OTHER -> "app_open_on_virtual_display"
        ShellService.START_NOT_FOUND -> "app_not_found"
        else -> "main_launch_failed"
    }
}
