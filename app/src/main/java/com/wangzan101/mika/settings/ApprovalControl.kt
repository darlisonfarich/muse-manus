package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ApprovalMode
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance

/** Per-run policy. Bridge policy cannot be lowered from Strict even by global changes. */
class RunApproval(
    startAutonomous: Boolean,
    val origin: RunOrigin = RunOrigin.Ui,
    private val userPresence: (() -> Attendance)? = null,
    initialMode: ApprovalMode = if (startAutonomous) ApprovalMode.Autonomous else ApprovalMode.Ask,
) : ApprovalSettings {
    val startedAutonomous = startAutonomous && origin == RunOrigin.Ui
    @Volatile private var configured = if (origin == RunOrigin.Bridge) ApprovalMode.Strict else initialMode

    override fun autonomous() = mode() == ApprovalMode.Autonomous
    override fun mode(): ApprovalMode =
        if (origin == RunOrigin.Bridge) ApprovalMode.Strict else configured

    /** Presence is never snapshotted at task start. */
    override fun attendance(run: RunProvenance): Attendance = userPresence?.invoke() ?: run.attendance

    fun set(value: ApprovalMode) {
        if (origin != RunOrigin.Ui || startedAutonomous) return
        configured = value
    }

    fun set(autonomous: Boolean) = set(if (autonomous) ApprovalMode.Autonomous else ApprovalMode.Ask)

    fun gate(): ConfirmationGate? = if (startedAutonomous) null else ConfirmationGate()
}

/** Global settings track active local runs; remote tasks do not gain local autonomy. */
class ApprovalControl(private val store: ApprovalStore) {
    private val runs = mutableSetOf<RunApproval>()

    @Synchronized fun start(
        remote: Boolean = false,
        origin: RunOrigin = if (remote) RunOrigin.Mcp else RunOrigin.Ui,
        presence: (() -> Attendance)? = null,
    ): RunApproval {
        val initial = when (origin) {
            RunOrigin.Bridge -> ApprovalMode.Strict
            RunOrigin.Ui -> store.mode.value
            else -> ApprovalMode.Ask
        }
        return RunApproval(initial == ApprovalMode.Autonomous, origin, presence, initial)
            .also { if (origin == RunOrigin.Ui) runs += it }
    }

    @Synchronized fun end(run: RunApproval) { runs -= run }

    @Synchronized fun setMode(mode: ApprovalMode) {
        store.setMode(mode)
        runs.forEach { it.set(mode) }
    }

    @Synchronized fun setAutonomous(on: Boolean) =
        setMode(if (on) ApprovalMode.Autonomous else ApprovalMode.Ask)
}
