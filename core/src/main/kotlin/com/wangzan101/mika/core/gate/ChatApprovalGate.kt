package com.wangzan101.mika.core.gate

import kotlinx.serialization.json.JsonObject

/**
 * GER PR-6 redemption kernel: maps a presented chat approval token to an
 * approval-gate outcome. Pure mapping over [ChatApprovalTokens.consume];
 * the caller (ConfirmDecision) decides where redemption is allowed —
 * never for payments, never instead of a local grant that exists.
 */
object ChatApprovalGate {
    enum class Failure(val code: String) {
        Invalid("approval_token_invalid"),
        Expired("approval_token_expired"),
        Replay("approval_token_replay"),
        Mismatch("approval_token_mismatch"),
    }

    sealed interface Outcome {
        /** The token authorized exactly this action; it is now consumed. */
        data object Approved : Outcome

        /** A token was presented but did not authorize this action. */
        data class Rejected(val failure: Failure) : Outcome

        /** No token was presented; the normal approval flow applies. */
        data object NotPresented : Outcome
    }

    fun redeem(
        tokens: ChatApprovalTokens,
        presented: String?,
        owner: String,
        tool: String,
        arguments: JsonObject,
        scope: String,
    ): Outcome {
        if (presented.isNullOrBlank()) return Outcome.NotPresented
        return when (tokens.consume(presented, owner, tool, arguments, scope)) {
            ChatApprovalTokens.Result.Approved -> Outcome.Approved
            ChatApprovalTokens.Result.Invalid -> Outcome.Rejected(Failure.Invalid)
            ChatApprovalTokens.Result.Expired -> Outcome.Rejected(Failure.Expired)
            ChatApprovalTokens.Result.Replay -> Outcome.Rejected(Failure.Replay)
            ChatApprovalTokens.Result.Mismatch -> Outcome.Rejected(Failure.Mismatch)
        }
    }
}

/** Per-run slot carrying the token presented with one direct-host call. */
class ChatApprovalSlot {
    @Volatile var token: String? = null
}

/** Everything the approval gate needs to redeem a presented chat approval. */
class ChatApprovalChannel(
    val tokens: ChatApprovalTokens,
    val owner: String,
    val scope: String,
    val slot: ChatApprovalSlot,
)
