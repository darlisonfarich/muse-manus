package com.wangzan101.mika.core.gate

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

/** Signed consent for exactly one direct-host action. Tokens are opaque to the executor. */
class ChatApprovalTokens(
    private val key: () -> SecretKey,
    private val consumed: ConsumedApprovalIds,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun issue(owner: String, tool: String, arguments: JsonObject, scope: String, ttlMs: Long = DEFAULT_TTL_MS): String {
        require(owner.isNotBlank() && tool.isNotBlank() && scope.isNotBlank())
        require(ttlMs in 1..MAX_TTL_MS)
        val issued = nowMs()
        val body = listOf("1", UUID.randomUUID().toString(), b64(owner), b64(tool), digest(arguments), b64(scope), (issued + ttlMs).toString()).joinToString(".")
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body))
    }

    @Synchronized fun consume(token: String, owner: String, tool: String, arguments: JsonObject, scope: String): Result {
        val fields = token.split('.')
        if (fields.size != 8 || fields[0] != "1") return Result.Invalid
        val body = fields.dropLast(1).joinToString(".")
        val signature = runCatching { Base64.getUrlDecoder().decode(fields.last()) }.getOrNull() ?: return Result.Invalid
        if (!MessageDigest.isEqual(signature, mac(body))) return Result.Invalid
        val tokenOwner = decode(fields[2]) ?: return Result.Invalid
        val tokenTool = decode(fields[3]) ?: return Result.Invalid
        val tokenScope = decode(fields[5]) ?: return Result.Invalid
        val expiry = fields[6].toLongOrNull() ?: return Result.Invalid
        val id = fields[1]
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return Result.Invalid
        if (nowMs() >= expiry) return Result.Expired
        if (tokenOwner != owner || tokenTool != tool || fields[4] != digest(arguments) || tokenScope != scope) return Result.Mismatch
        return if (consumed.consume(id)) Result.Approved else Result.Replay
    }

    private fun mac(value: String) = Mac.getInstance("HmacSHA256").run { init(key()); doFinal(value.toByteArray(StandardCharsets.UTF_8)) }
    private fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String) = runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrNull()
    private fun digest(value: JsonObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is JsonPrimitive, JsonNull -> value.toString()
    }

    enum class Result { Approved, Invalid, Expired, Replay, Mismatch }
    companion object { const val DEFAULT_TTL_MS = 2 * 60_000L; const val MAX_TTL_MS = 5 * 60_000L }
}

/** Implementations must durably record the id before returning true. */
fun interface ConsumedApprovalIds { fun consume(id: String): Boolean }
