// Contrato dos comandos do Gadget Link (Etapa 0 da especificação "Gadget Link sem Tailscale"): o que
// o app anuncia em commands_v2 no link.register e o que um link.invoke pode receber de volta. O
// formato da entrada de commands_v2 é o do SDK oficial (musegadget/commands.py e executor.py do
// facebookincubator/muse-gadget-sdk, Apache-2.0): {description, required, optional, timeout_ms}, com
// parâmetros {type, description} e os seis tipos fechados do protocolo. O resultado é sempre
// {ok: true, payload} ou {ok: false, error: <código da taxonomia>, detail?}.
package com.darlisonfarich.gadgetlink.commands

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Os tipos de parâmetro do protocolo (PARAM_TYPES do SDK). Não há outros. */
enum class ParamType(val wire: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    OBJECT("object"),
    ARRAY("array"),
    ;

    /** O valor JSON é deste tipo? (Booleano não é número, como no SDK; string nunca é número.) */
    fun accepts(value: JsonElement): Boolean = when (this) {
        STRING -> value is JsonPrimitive && value.isString
        BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        INTEGER -> value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.longOrNull != null
        NUMBER -> value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.doubleOrNull != null
        OBJECT -> value is JsonObject
        ARRAY -> value is JsonArray
    }
}

class ParamSpec(val type: ParamType, val description: String) {
    fun wire(): JsonObject = JsonObject(linkedMapOf("type" to JsonPrimitive(type.wire), "description" to JsonPrimitive(description)))
}

/** A taxonomia fechada de erros (especificação §2.4). Nenhum outro código sai do despachante. */
enum class ErrorCode(val wire: String) {
    NOT_IMPLEMENTED("not_implemented"),
    TIMEOUT("timeout"),
    TOO_LARGE("too_large"),
    LOCAL_GATE_DENIED("local_gate_denied"),
    STALE_SNAPSHOT("stale_snapshot"),
    APP_IN_USE_BY_USER("app_in_use_by_user"),
    EXECUTOR_ERROR("executor_error"),
    UNAVAILABLE("unavailable"),
}

/** O desfecho de um handler. [Err.detail] é texto curto, sem parâmetros, conteúdo ou credenciais. */
sealed class CommandResult {
    class Ok(val payload: JsonElement) : CommandResult()
    class Err(val code: ErrorCode, val detail: String? = null) : CommandResult()
}

/** Um handler pode lançar esta exceção para devolver um código da taxonomia. */
class CommandException(val code: ErrorCode, val detail: String? = null) : RuntimeException(code.wire)

/** Roda numa thread de trabalho do despachante. Recebe só os parâmetros declarados, já com o tipo conferido. */
fun interface CommandHandler {
    fun run(params: JsonObject): CommandResult
}

class CommandSpec(
    val name: String,
    val description: String,
    val required: Map<String, ParamSpec> = emptyMap(),
    val optional: Map<String, ParamSpec> = emptyMap(),
    /** O despachante impõe este prazo; o catálogo anuncia ele + [TIMEOUT_GRACE_MS] (como o SDK). */
    val timeoutMs: Long,
    /** Comando com efeito no aparelho: o portão local também exige a escrita liberada. */
    val writes: Boolean,
    /** Falso = stub da medição da Etapa 1: só anunciado com includeStubs e sempre not_implemented. */
    val implemented: Boolean,
    val handler: CommandHandler,
) {
    init {
        require(NAME.matches(name) && name.length <= MAX_NAME_LEN) { "invalid command name: $name" }
        require(RESERVED_PREFIXES.none { name.startsWith(it) }) { "reserved prefix: $name" }
        require(description.isNotBlank()) { "description is required: $name" }
        require(timeoutMs in 1..MAX_TIMEOUT_MS) { "timeout out of range: $name" }
        require(required.keys.intersect(optional.keys).isEmpty()) { "both required and optional: $name" }
    }

    /** A entrada deste comando em commands_v2. */
    fun wire(): JsonObject = JsonObject(
        linkedMapOf(
            "description" to JsonPrimitive(description),
            "required" to JsonObject(required.mapValuesTo(LinkedHashMap()) { it.value.wire() }),
            "optional" to JsonObject(optional.mapValuesTo(LinkedHashMap()) { it.value.wire() }),
            "timeout_ms" to JsonPrimitive(timeoutMs + TIMEOUT_GRACE_MS),
        ),
    )

    /** Só os parâmetros declarados, ou a mensagem curta do primeiro problema (check_params do SDK). */
    fun checkParams(params: JsonObject): Pair<JsonObject?, String?> {
        for (name in required.keys) if (params[name] == null || params[name] is JsonNull) return null to "$name is required"
        val checked = LinkedHashMap<String, JsonElement>()
        for ((name, spec) in required + optional) {
            val value = params[name] ?: continue
            if (value is JsonNull && name in optional) continue
            if (!spec.type.accepts(value)) return null to "$name must be ${if (spec.type.wire[0] in "aeiou") "an" else "a"} ${spec.type.wire}"
            checked[name] = value
        }
        return JsonObject(checked) to null
    }

    companion object {
        /** O Muse espera este tanto a mais que o comando pode rodar, para o timeout chegar como erro e não como invoke perdido. */
        const val TIMEOUT_GRACE_MS = 5_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_NAME_LEN = 64
        const val STUB_PREFIX = "[STUB — NÃO IMPLEMENTADO] "
        private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        private val RESERVED_PREFIXES = listOf("system.", "file.", "device.", "link.")

        /** Stub marcado: anuncia o contrato do comando e não faz NADA além de devolver not_implemented. */
        fun stub(
            name: String,
            description: String,
            required: Map<String, ParamSpec>,
            optional: Map<String, ParamSpec>,
            timeoutMs: Long,
            writes: Boolean,
        ) = CommandSpec(
            name, STUB_PREFIX + description, required, optional, timeoutMs, writes, implemented = false,
            handler = { CommandResult.Err(ErrorCode.NOT_IMPLEMENTED) },
        )
    }
}
