// O despachante de comandos do Gadget Link (Etapa 0 da especificação "Gadget Link sem Tailscale"):
// link.invoke → portão local → executor → link.result, com correlação SOMENTE pelo id da invocação.
// Contrato (especificação §2.1–§2.6):
//  * resultado sempre estruturado: {ok: true, payload} ou {ok: false, error: <taxonomia §2.4>, detail?};
//  * portão local consultado antes de qualquer coisa: negou (parada ativa, acesso desligado) = local_gate_denied;
//  * timeout_ms do catálogo imposto aqui, contado da chegada do invoke: expirou = timeout;
//  * no máximo 4 invocações executando ao mesmo tempo (semáforo); a 5ª espera a vaga dentro do próprio
//    prazo. Não há cancelamento pelo protocolo: um handler que estoura o prazo recebe interrupt, mas a vaga
//    só volta quando a thread dele termina de verdade (como o SDK: nunca mais de 4 executando);
//  * deduplicação: cache de resultados por id (últimos 128 ids ou 10 min, o que expirar primeiro); reenvio
//    do mesmo id devolve o resultado guardado SEM reexecutar — e, se o original ainda está rodando, espera
//    por ele em vez de rodar de novo;
//  * guarda de tamanho: link.result serializado (como sai no fio, prefixo incluído) acima de 240 KiB vira
//    too_large — nunca truncamento;
//  * auditoria local de cada invocação (id, comando, timestamp, desfecho), sem parâmetros nem payload.
// O fluxo de invoke é o de musegadget/link_client.py (_invoke) do facebookincubator/muse-gadget-sdk
// (Apache-2.0): campo "command" (aceita "name", o nome da especificação, na falta dele), params só se for
// objeto, id falso = não há a quem responder.
package com.darlisonfarich.gadgetlink.commands

import com.darlisonfarich.gadgetlink.noise.InvokeHandler
import com.darlisonfarich.gadgetlink.noise.LinkWire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** O portão local do app. Devolve null para liberar, ou um motivo curto (vai no detail do local_gate_denied). */
fun interface LocalGate {
    fun deny(command: CommandSpec): String?

    companion object {
        val OPEN = LocalGate { null }
    }
}

/** Uma linha da auditoria local. Nunca leva parâmetros, payload nem detail (podem ecoar conteúdo). */
class InvokeAuditEntry(
    /** id da invocação, só caracteres imprimíveis e no máximo 64. */
    val id: String,
    val command: String,
    /** relógio de parede, ms, na chegada do invoke */
    val receivedAtMs: Long,
    val durationMs: Long,
    /** "ok" ou o código da taxonomia */
    val outcome: String,
    /** respondido do cache de deduplicação (nada executou) */
    val deduplicated: Boolean,
    /** bytes do link.result como sai no fio */
    val resultBytes: Int,
) {
    /** O formato das linhas de auditoria do app (campos separados por |, uma linha por evento). */
    fun line(): String = "$receivedAtMs|$id|$command|$outcome|${durationMs}ms|${resultBytes}B" + if (deduplicated) "|dedup" else ""
}

class CommandDispatcher(
    private val catalog: CommandCatalog,
    private val gate: LocalGate = LocalGate.OPEN,
    private val audit: (InvokeAuditEntry) -> Unit = {},
    /** monotônico, ms: prazos e validade do cache */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val cacheSize: Int = CACHE_SIZE,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val maxResultBytes: Int = MAX_RESULT_BYTES,
) : InvokeHandler {
    companion object {
        const val MAX_CONCURRENT = 4
        const val CACHE_SIZE = 128
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 256 KiB do teto da mensagem de controle menos 16 KiB de margem (especificação §2.2). */
        const val MAX_RESULT_BYTES = 240 * 1024
        const val MAX_LOGGED_ID = 64

        fun ok(payload: JsonElement): JsonObject = JsonObject(linkedMapOf("ok" to JsonPrimitive(true), "payload" to payload))

        fun error(code: ErrorCode, detail: String? = null): JsonObject = JsonObject(
            LinkedHashMap<String, JsonElement>().apply {
                put("ok", JsonPrimitive(false))
                put("error", JsonPrimitive(code.wire))
                if (detail != null) put("detail", JsonPrimitive(detail.take(200)))
            },
        )

        /** ASCII imprimível sem o separador "|" (não forja linha nem campo de auditoria) e curto. */
        fun printable(text: String, max: Int = MAX_LOGGED_ID): String =
            text.take(max).map { if (it.code in 0x20..0x7E && it != '|') it else '?' }.joinToString("")
    }

    private class Cached(val result: JsonObject, val resultBytes: Int, val at: Long)

    private inner class Invocation(
        val key: String,
        val id: JsonElement,
        val command: String,
        val spec: CommandSpec?,
        val params: JsonObject,
        val receivedAt: Long,
        val receivedWall: Long,
        first: (JsonObject) -> Boolean,
    ) {
        val replies = ArrayList<(JsonObject) -> Boolean>().apply { add(first) }
        var done = false

        /** Parâmetros já conferidos contra o catálogo (só os declarados). */
        var checked: JsonObject? = null

        /** A thread que roda o handler agora (para o interrupt do timeout); null fora da execução. */
        var thread: Thread? = null
    }

    private val lock = Any()
    private val cache = LinkedHashMap<String, Cached>()
    private val inFlight = HashMap<String, Invocation>()
    private val queue = ArrayDeque<Invocation>()
    private val slots = Semaphore(maxConcurrent)

    private val threadCount = AtomicInteger()
    private val daemon = ThreadFactory { r -> Thread(r, "gadget-cmd-${threadCount.incrementAndGet()}").apply { isDaemon = true } }
    private val workers: ExecutorService = Executors.newCachedThreadPool(daemon)
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gadget-cmd-timer").apply { isDaemon = true }
    }

    /** Quantos handlers estão executando agora (para testes e diagnóstico). */
    val running: Int get() = maxConcurrent - slots.availablePermits()

    override fun onInvoke(message: JsonObject, reply: (JsonObject) -> Boolean) {
        val id = message["id"] ?: return
        if (!truthy(id)) return
        val key = id.toString() // a forma JSON: "7" e 7 não se confundem
        val receivedAt = nowMs()
        val receivedWall = wallMs()

        val rawCommand = message["command"]?.takeIf { truthy(it) } ?: message["name"]?.takeIf { truthy(it) } ?: JsonPrimitive("")
        val command = if (rawCommand is JsonPrimitive && rawCommand.isString) rawCommand.content else null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        var hit: Cached? = null
        val invocation: Invocation? = synchronized(lock) {
            purge(receivedAt)
            hit = cache[key]
            val running = inFlight[key]
            when {
                hit != null -> null
                // O mesmo id ainda executando (reconexão no meio): espera o original, não roda de novo.
                running != null -> {
                    running.replies += reply
                    null
                }
                else -> Invocation(key, id, command ?: "", command?.let { catalog.find(it) }, params, receivedAt, receivedWall, reply)
                    .also { inFlight[key] = it }
            }
        }
        // Resposta e auditoria sempre fora do lock (o envio cifra e a auditoria faz fsync).
        hit?.let {
            deliver(listOf(reply), id, it.result)
            record(id, command ?: "", receivedWall, 0, outcomeOf(it.result), true, it.resultBytes)
        }
        if (invocation == null) return

        // Portão local primeiro: com a parada ativa, NADA passa — nem comando desconhecido, nem stub.
        val spec = invocation.spec
        val denied = if (spec != null) gate.deny(spec) else gate.deny(GATE_PROBE)
        when {
            denied != null -> complete(invocation, error(ErrorCode.LOCAL_GATE_DENIED, denied))
            command == null -> complete(invocation, error(ErrorCode.EXECUTOR_ERROR, "command must be a string"))
            spec == null -> complete(invocation, error(ErrorCode.NOT_IMPLEMENTED, "unknown command"))
            else -> {
                val (checked, problem) = spec.checkParams(params)
                if (checked == null) {
                    complete(invocation, error(ErrorCode.EXECUTOR_ERROR, problem))
                } else {
                    schedule(invocation, spec, checked)
                }
            }
        }
    }

    private fun schedule(invocation: Invocation, spec: CommandSpec, params: JsonObject) {
        invocation.checked = params
        try {
            timer.schedule({ onDeadline(invocation) }, spec.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            complete(invocation, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
            return
        }
        val runNow = synchronized(lock) {
            if (slots.tryAcquire()) true else {
                queue.addLast(invocation)
                false
            }
        }
        if (runNow) start(invocation)
    }

    /** Chamado com uma vaga já tomada para [invocation]. */
    private fun start(invocation: Invocation) {
        try {
            workers.execute { runHandler(invocation) }
        } catch (e: Exception) {
            release()
            complete(invocation, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
        }
    }

    private fun runHandler(invocation: Invocation) {
        try {
            val spec = invocation.spec!!
            val params = invocation.checked!!
            synchronized(lock) {
                if (invocation.done) return // o prazo venceu antes de a thread pegar a tarefa
                invocation.thread = Thread.currentThread()
            }
            val result = try {
                when (val r = spec.handler.run(params)) {
                    is CommandResult.Ok -> ok(r.payload)
                    is CommandResult.Err -> error(r.code, r.detail)
                }
            } catch (e: CommandException) {
                error(e.code, e.detail)
            } catch (e: InterruptedException) {
                error(ErrorCode.TIMEOUT)
            } catch (e: Throwable) {
                // Só o tipo da exceção: o texto dela pode trazer parâmetros (como o SDK).
                error(ErrorCode.EXECUTOR_ERROR, e.javaClass.simpleName)
            }
            synchronized(lock) { invocation.thread = null }
            // O interrupt só acontece sob o lock com a thread registrada: limpar aqui impede que ele vaze
            // para a próxima tarefa desta thread do pool.
            Thread.interrupted()
            complete(invocation, result)
        } finally {
            synchronized(lock) { invocation.thread = null }
            Thread.interrupted()
            release()
        }
    }

    /** Devolve a vaga, ou a passa direto ao próximo da fila que ainda está no prazo. */
    private fun release() {
        val next = synchronized(lock) {
            var candidate: Invocation? = null
            while (candidate == null && queue.isNotEmpty()) {
                candidate = queue.removeFirst().takeUnless { it.done }
            }
            if (candidate == null) slots.release()
            candidate
        }
        if (next != null) start(next)
    }

    private fun onDeadline(invocation: Invocation) {
        synchronized(lock) {
            if (invocation.done) return
            invocation.thread?.interrupt()
        }
        complete(invocation, error(ErrorCode.TIMEOUT, "${invocation.spec?.timeoutMs ?: 0} ms"))
    }

    /** O primeiro desfecho vence; os outros (resultado depois do timeout, por exemplo) são ignorados. */
    private fun complete(invocation: Invocation, result: JsonObject) {
        var final = result
        var bytes = encodedSize(invocation.id, final)
        if (bytes > maxResultBytes) {
            final = error(ErrorCode.TOO_LARGE, "result is $bytes bytes, budget $maxResultBytes")
            bytes = encodedSize(invocation.id, final)
        }
        val replies: List<(JsonObject) -> Boolean>
        synchronized(lock) {
            if (invocation.done) return
            invocation.done = true
            inFlight.remove(invocation.key)
            cache.remove(invocation.key)
            cache[invocation.key] = Cached(final, bytes, nowMs())
            while (cache.size > cacheSize) cache.remove(cache.keys.first())
            replies = invocation.replies.toList()
        }
        deliver(replies, invocation.id, final)
        record(invocation.id, invocation.command, invocation.receivedWall, nowMs() - invocation.receivedAt, outcomeOf(final), false, bytes)
    }

    private fun deliver(replies: List<(JsonObject) -> Boolean>, id: JsonElement, result: JsonObject) {
        val message = resultMessage(id, result)
        for (r in replies) {
            try {
                r(message)
            } catch (_: Throwable) {
                // Sessão caída: o resultado fica no cache para o reenvio do mesmo id.
            }
        }
    }

    private fun record(id: JsonElement, command: String, at: Long, ms: Long, outcome: String, dedup: Boolean, bytes: Int) {
        val shown = if (id is JsonPrimitive && id.isString) id.content else id.toString()
        val entry = InvokeAuditEntry(printable(shown), printable(command), at, ms, outcome, dedup, bytes)
        try {
            audit(entry)
        } catch (_: Throwable) {
            // auditoria nunca derruba a resposta
        }
    }

    private fun purge(now: Long) {
        val it = cache.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().at >= cacheTtlMs) it.remove() else break // ordem de inserção = ordem de idade
        }
    }

    /** Desliga as threads. Invocações ainda em curso terminam sem resposta (a sessão já não existe). */
    fun close() {
        timer.shutdownNow()
        workers.shutdownNow()
    }

    private fun resultMessage(id: JsonElement, result: JsonObject): JsonObject = JsonObject(
        LinkedHashMap<String, JsonElement>().apply {
            put("method", JsonPrimitive("link.result"))
            put("id", id)
            putAll(result)
        },
    )

    private fun encodedSize(id: JsonElement, result: JsonObject): Int = LinkWire.encodeMessage(resultMessage(id, result)).size

    private fun outcomeOf(result: JsonObject): String =
        if (result["ok"] == JsonPrimitive(true)) "ok" else (result["error"] as? JsonPrimitive)?.content ?: "error"

    /** Verdade no sentido do Python (como o SDK decide se há id e comando). */
    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, JsonNull -> false
        is JsonArray -> e.isNotEmpty()
        is JsonObject -> e.isNotEmpty()
        is JsonPrimitive -> when {
            e.isString -> e.content.isNotEmpty()
            e.booleanOrNull != null -> e.booleanOrNull!!
            else -> e.doubleOrNull?.let { it != 0.0 } ?: true
        }
    }
}

/** O que o portão vê quando o comando não existe no catálogo: a parada ainda vale, nada mais se sabe dele. */
private val GATE_PROBE = CommandSpec(
    name = "gate.unknown_command",
    description = "unknown command",
    timeoutMs = 1,
    writes = true,
    implemented = false,
    handler = { CommandResult.Err(ErrorCode.NOT_IMPLEMENTED) },
)
