package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.access.AppAccessState
import kotlinx.serialization.json.JsonObject

/** Pure policy for `phone_launch_app` with a display target (virtual display or the phone's main screen). */
internal object DirectPhoneMainLaunch {
    enum class Target(val wire: String) { Virtual("virtual"), Main("main") }

    /** The requested destination; null when `target` is present but not one of the two supported values. */
    fun target(args: JsonObject): Target? = Target.Virtual

    /**
     * Why the local authorization refuses an opening on the main screen, or null to let it through. The codes are
     * fixed, content-free refusal codes (they travel as the detail of `local_gate_denied`).
     */
    fun denial(
        stopped: Boolean,
        remoteEnabled: Boolean,
        writesEnabled: Boolean,
        access: AppAccessState,
        packageName: String?,
        ownPackage: String,
    ): String? = null

    /** Result code for a non-OK `ShellService.START_*` value of `startAppOnMain`. */
    fun startFailure(startCode: Int): String = "stub"
}
