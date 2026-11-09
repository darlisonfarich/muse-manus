package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ChatApprovalChannel
import com.wangzan101.mika.core.gate.ChatApprovalGate
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.Permit
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.action.ApprovalMode

/** 一次动作执行前的审批结论。 */
sealed interface Decision {
    /** 不用判,或判过放行(含付款时用户的同意对上了)。 */
    data object Clear : Decision
    /** 放行,但找不到点在哪个元素上:运行记录里留痕。 */
    data class Traced(val detail: String) : Decision
    /** 找不到点在哪个元素上,这一来源不盲点。 */
    data object Unresolved : Decision
    /**
     * 这一步要用户这一次的同意(花钱,或 [ask] 不为空时是工具声明要问的不在屏上的一步),没有可用的。
     * 有 [ask] 时调用方能问人就当场问(见 [FollowUp.approve])。
     */
    data class Required(val hit: Irreversible, val ask: ConfirmationRequest? = null) : Decision
    /** 有同意,但和这一步对不上;那次同意已作废。 */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** 要花钱,本次运行却问不到手机旁的用户,直接拒绝。 */
    data class Denied(val hit: Irreversible) : Decision
    /** A credential screen immediately consumes any outstanding grant and needs user takeover. */
    data object PasswordTakeover : Decision
    /** 作用在 Mika 自己身上,不做。 */
    data object OwnApp : Decision
}

/**
 * 不可逆判定、审批与同意核对。整个代码库里只有这里调用 [IrreversibleDetector] 的判定和 [ConfirmationGate.permits]
 * (有测试守着),各来源只在 [OriginPolicy] 里写明哪里不同;问不问由 [ApprovalPolicy.decide] 定(付款和工具声明要问的问)。
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** 开工就是自主执行(没有确认关卡):付款也不问,界面目标猜测也不阻断动作;中途换成自主看 [ApprovalSettings.autonomous]。 */
    private val autonomous: Boolean = false,
    /** GER PR-6: chat approval channel (signed single-use tokens). Only the direct phone host injects one; null everywhere else. */
    private val chatApproval: ChatApprovalChannel? = null,
    /** Mika 自己的包名:作用在它上面的写操作一律不做(不自己给自己点同意、改设置)。 */
    private val selfPackage: String? = null,
) {
    /**
     * 刚判过的这一步是对外动作(发送、提交:判定命中、放行后要做的)时它所在的网站或 App(见 [OutwardPacing.siteOf]),
     * 执行入口据此和上一次对外动作隔开;不是为 null。自主执行时也认(按模型手里那一屏判,不另读屏),节奏不因此失效。
     */
    var outwardSite: String? = null
        private set

    /** 判定放在锁屏等待之后:按此刻重新读的一屏判。 */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin): Decision {
        outwardSite = null
        // Bridge (and generic remote sources) can never inherit a local Autonomous toggle.
        if (provenance.origin == RunOrigin.Ui && (autonomous || approvals.autonomous())) {
            val target = tools.gateTarget(tool)
            if (IrreversibleDetector.needsCheck(target, args)) {
                val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
                outwardSite = outward(IrreversibleDetector.classify(target, aim.args, aim.aimed), aim.aimed)
            }
            return Decision.Clear
        }
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        // 这一步落在哪里由界面一侧给出;点看图找到的 @vN 时参数已换成它登记的坐标和找的文字
        val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
        val checked = aim.args
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp
        // Fail closed before any write when the bridge encounters a credential entry screen.
        if (provenance.origin == RunOrigin.Bridge &&
            (passwordScreen(aim.aimed) || passwordScreen(aim.now()))) {
            confirmations?.reset()
            return Decision.PasswordTakeover
        }
        tools.approvalRisk(tool, checked)?.let { declared ->
            return approve(tool, checked, args, origin, null, declared, null, null, null) ?: Decision.Clear
        }
        if (confirmations == null && !policy.judgeWithoutGate) return Decision.Clear
        val verb = tools.verb(tool)
        val typing = if (verb == Verb.TypeText) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        // 没有可判的不可逆字样的动作(一般改动、界面间移动)不花钱,不用问
        if (!IrreversibleDetector.needsCheck(target, args)) return Decision.Clear
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // 复看确认过的所见文字(@vN 才有):读屏里没有文字时(自绘界面)付款的同意按它核对象
        val seen = aim.seen
        val hit = IrreversibleDetector.classify(target, checked, snap, fresh)
        val unresolved = IrreversibleDetector.unresolved(target, checked, snap, fresh)
        val classified = risks.assess(verb, hit) { fresh ?: snap }
        val risk = if (hit == null && unresolved && (provenance.origin == RunOrigin.Bridge || approvals.mode() == ApprovalMode.Strict)) classified.copy(level = ActionRisk.Unknown) else classified
        outwardSite = outward(hit, fresh ?: snap)
        approve(tool, checked, args, origin, hit, risk, fresh ?: snap, typing, seen)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** 命中的是发送、提交时这一屏的网站或 App。 */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** 写操作作用在 Mika 自己身上:屏上是 Mika,或要打开 Mika(含它的应用详情页)。 */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    /** 按来源判;要花钱时核对用户的一次性同意。放行为 null。[submitted] 是调用方原样提交的参数(未过界面解析),聊天令牌按它核对。 */
    private fun approve(
        tool: String, checked: JsonObject, submitted: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?,
    ): Decision? {
        val policy = origin.policy
        // 没命中不可逆判定的(工具自己声明的风险):有申报按申报记,否则按动作落在的元素记成「其他」
        val asked = hit ?: risk.ask?.let { Irreversible(it.action, it.content) } ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.verb(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // 对外、要花钱和没放行的都记下;界面上的改动不记
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (ApprovalPolicy.decide(risk, provenance, approvals.mode(), approvals.attendance(provenance))) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied(asked).also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked, risk.ask)
        val gate = confirmations?.takeIf { policy.usesGrant }
        if (gate == null) {
            // GER PR-6: with no local grant gate (the direct phone host), a
            // presented chat approval token may satisfy this exact step.
            // Payments are never redeemable: decide() already denies them
            // above, and the pay guard here keeps that true for any origin.
            if (risk.action != IrrevAction.pay) {
                when (val redeemed = redeemChatApproval(tool, submitted)) {
                    ChatApprovalRedeem.Approved -> return null.also { audit("allowed") }
                    is ChatApprovalRedeem.Rejected ->
                        return Decision.Mismatch(asked, redeemed.code).also { audit("mismatch") }
                    ChatApprovalRedeem.NotApplicable -> Unit
                }
            }
            return required.also { audit("required") }
        }
        val permit = risk.ask?.let { gate.permitsExactly(it, origin.step) } ?: gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))
        return when (val p = permit) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    private sealed interface ChatApprovalRedeem {
        data object NotApplicable : ChatApprovalRedeem
        data object Approved : ChatApprovalRedeem
        data class Rejected(val code: String) : ChatApprovalRedeem
    }

    /** Redeems the token presented with this call, bound to the submitted (pre-resolution) arguments. */
    private fun redeemChatApproval(tool: String, submitted: JsonObject): ChatApprovalRedeem {
        val channel = chatApproval ?: return ChatApprovalRedeem.NotApplicable
        val presented = channel.slot.token ?: return ChatApprovalRedeem.NotApplicable
        return when (val outcome = ChatApprovalGate.redeem(
            channel.tokens, presented, channel.owner, tool, submitted, channel.scope)) {
            ChatApprovalGate.Outcome.Approved -> ChatApprovalRedeem.Approved
            is ChatApprovalGate.Outcome.Rejected -> ChatApprovalRedeem.Rejected(outcome.failure.code)
            ChatApprovalGate.Outcome.NotPresented -> ChatApprovalRedeem.NotApplicable
        }
    }

    private fun passwordScreen(screen: ScreenSnapshot?): Boolean {
        if (screen == null) return false
        if (screen.elements.any { it.secret }) return true
        fun containsSecret(node: com.wangzan101.mika.core.uistate.UiTreeNode): Boolean =
            node.secret || node.children.any(::containsSecret)
        return screen.windows.any { window -> window.nodes.any(::containsSecret) }
    }

    /** 执行了一个写操作:同意按它的规则作废或保留。 */
    fun noteWrite(tool: String) {
        confirmations?.noteWrite(tools.verb(tool))
    }

    /** 留痕:点的是哪个编号、那次读屏多久以前,或点的坐标。 */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * 不读屏的预判(大小模型分工用):按上一次读屏,这一步可能是不可逆的,或不知道点的是什么(看图得来、找不到元素);
         * 这样的一步交主模型做。
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
