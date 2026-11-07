package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.risk.IrrevAction

/**
 * 一个动作的风险等级(记进审批记录,核查据此知道任务做过对外的事)。[Read] 只看或只在界面间移动;[Change] 在界面上改东西(点、打字);
 * [External] 把东西交给别人(发送、提交);[Irreversible] 做了收不回(付款、下单、删除);[Unknown] 看不清点的是什么。
 */
enum class ActionRisk { Read, Change, External, Irreversible, Unknown;

    /** 会影响屏幕之外的世界(或拿不准会不会)。 */
    val outward: Boolean get() = this == External || this == Irreversible || this == Unknown
}

/**
 * [payment] 为 true 时这一步要花钱(付款、下单,或屏上有应付金额时的对外动作):询问时先问用户。
 * [ask] 不为空时是不在屏上、改了收不回的一步(工具按这次的参数声明,如会改动东西的 root 命令),询问时也先问用户,
 * 给用户看的就是它;同意只放行一字不差的这一步。
 */
data class Risk(val level: ActionRisk, val payment: Boolean = false, val ask: ConfirmationRequest? = null, val action: IrrevAction? = null)

/** 用户在确认卡片上的选择:同意这一次,或不同意。 */
enum class ConfirmChoice { Once, Deny;

    val approved: Boolean get() = this != Deny
}

/** Persisted policy: Ask (legacy), Strict (outward actions), Autonomous (local only). */
enum class ApprovalMode { Ask, Strict, Autonomous }

/** The task policy and user presence are read *at every action*, not cached at run start. */
fun interface ApprovalSettings {
    fun autonomous(): Boolean
    fun mode(): ApprovalMode = if (autonomous()) ApprovalMode.Autonomous else ApprovalMode.Ask
    fun attendance(run: RunProvenance): Attendance = run.attendance

    companion object {
        val ASK = ApprovalSettings { false }
        val STRICT = object : ApprovalSettings {
            override fun autonomous() = false
            override fun mode() = ApprovalMode.Strict
        }
    }
}

sealed interface Approval {
    /** 不用问。 */
    data object Allow : Approval
    /** 要用户这一次的同意(有一次性同意就核对并用掉它)。 */
    data object Ask : Approval
    /** 本次运行不能向用户要同意，动作必须停下。 */
    data object Deny : Approval
}

/**
 * 审批判定:纯函数。付款和工具声明要问的(见 [Risk.ask])要问;问不到人(无人值守、子任务)或不在手机上
 * (渠道、定时、MCP 发起的)时这些都不做。
 */
object ApprovalPolicy {
    fun decide(
        risk: Risk,
        run: RunProvenance = RunProvenance(),
        configured: ApprovalMode = ApprovalMode.Ask,
        attendance: Attendance = run.attendance,
    ): Approval {
        val mode = if (run.origin == RunOrigin.Bridge) ApprovalMode.Strict else configured
        // A payment button is NEVER unlocked by confirmation in non-autonomous mode.
        // Generic remote sources cannot use local autonomous permission either.
        if (risk.action == IrrevAction.pay) return if (mode == ApprovalMode.Autonomous &&
            run.origin == RunOrigin.Ui) Approval.Allow else Approval.Deny
        if (run.origin == RunOrigin.Ui && mode == ApprovalMode.Autonomous) return Approval.Allow
        val required = risk.ask != null || risk.payment ||
            (mode == ApprovalMode.Strict && risk.level.outward)
        if (!required) return Approval.Allow
        return when (run.origin) {
            RunOrigin.Bridge -> Approval.Ask // Unattended bridge runs wait for a local tap.
            RunOrigin.Ui -> if (attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            else -> Approval.Deny // MCP, channel, trigger, child do NOT inherit bridge privileges.
        }
    }
}
