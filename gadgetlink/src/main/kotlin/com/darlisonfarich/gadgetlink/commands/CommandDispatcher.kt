// O despachante de comandos do Gadget Link (Etapa 0 da especificação "Gadget Link sem Tailscale"):
// link.invoke → portão local → executor → link.result, com correlação SOMENTE pelo id da invocação.
// Contrato (especificação §2.1–§2.6), com as correções obrigatórias do PR nº 37:
//  * resultado sempre estruturado: {ok: true, payload} ou {ok: false, error: <taxonomia §2.4>, detail?};
//  * portão local consultado em TODOS os pontos de decisão: antes do cache de deduplicação, antes de juntar
//    um reenvio a uma execução em curso e de novo imediatamente antes de executar (quem esperou na fila é
//    revalidado ao sair dela, e a fila é varrida periodicamente). Negou em qualquer ponto = local_gate_denied,
//    nada executa e nada sai do cache. Portão que lança exceção = negado (falha fechada);
//  * timeout_ms do catálogo imposto aqui, contado da chegada do invoke: expirou = timeout. O timer de cada
//    invocação é cancelado quando ela termina (nada vencido fica retido);
//  * no máximo 4 invocações executando ao mesmo tempo (semáforo); até [MAX_QUEUED] esperam a vaga dentro do
//    próprio prazo, e acima disso a resposta é unavailable na hora (fila cheia), sem espera sem fim. Não há
//    cancelamento pelo protocolo: um handler que estoura o prazo recebe interrupt, mas a vaga só volta quando
//    a thread dele termina de verdade (como o SDK: nunca mais de 4 executando);
//  * deduplicação pela IMPRESSÃO DIGITAL da invocação = SHA-256(id ‖ comando ‖ parâmetros canônicos), não
//    só pelo id: o mesmo id com outro comando ou outros parâmetros é OUTRA operação e nunca recebe o resultado
//    da primeira. Reenvio idêntico (mesmo id, comando e parâmetros) devolve o resultado guardado SEM
//    reexecutar — e, se a original ainda roda, espera por ela. Cache: últimos 128 resultados ou 10 min, o que
//    vencer primeiro, varrido também sem tráfego. O cache atravessa reconexões (§2.6); local_gate_denied e
//    unavailable NUNCA entram nele (a nova tentativa é reavaliada);
//  * ids acima de [MAX_ID_CHARS] (forma JSON) são recusados sem executar;
//  * guarda de tamanho: link.result serializado (como sai no fio, prefixo incluído) acima de 240 KiB vira
//    too_large — nunca truncamento;
//  * auditoria local de cada invocação (id, comando, timestamp, desfecho), sem parâmetros nem payload, FORA
//    do caminho da invocação: um executor próprio com fila limitada; fila cheia = a linha é descartada
//    (contada em [auditDropped]), nunca o comando bloqueia; falha da auditoria nunca derruba a resposta;
//  * [close] termina tudo o que está pendente com unavailable, sem esperar handler nenhum.
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
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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
    /** respondido do cache de deduplicação ou junto da execução original (nada executou de novo) */
    val deduplicated: Boolean,
    /** bytes do link.result como sai no fio */
    val resultBytes: Int,
) {
    /** O formato das linhas de auditoria do app (campos separados por |, uma linha por evento). */
    fun line(): String = "$receivedAtMs|$id|$command|$outcome|${durationMs}ms|${resultBytes}B" + if (deduplicated) "|dedup" else ""
}

class CommandDispatcher(
    catalog: CommandCatalog,
    private val gate: LocalGate = LocalGate.OPEN,
    private val audit: (InvokeAuditEntry) -> Unit = {},
    /** monotônico, ms: prazos e validade do cache */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val cacheSize: Int = CACHE_SIZE,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val maxResultBytes: Int = MAX_RESULT_BYTES,
    private val maxQueued: Int = MAX_QUEUED,
    private val maxIdChars: Int = MAX_ID_CHARS,
    private val maxJoins: Int = MAX_JOINS,
    auditQueue: Int = AUDIT_QUEUE,
    /** Período da varredura (cache vencido + revalidação do portão de quem está na fila); <= 0 desliga. */
    sweepMs: Long = SWEEP_MS,
) : InvokeHandler {
    companion object {
        const val MAX_CONCURRENT = 4
        const val CACHE_SIZE = 128
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 256 KiB do teto da mensagem de controle menos 16 KiB de margem (especificação §2.2). */
        const val MAX_RESULT_BYTES = 240 * 1024
        const val MAX_LOGGED_ID = 64

        /** Maior id aceito, na forma JSON (aspas incluídas). Os do Muse são curtos; acima disso nada executa. */
        const val MAX_ID_CHARS = 128

        /** Quantas invocações podem esperar vaga; a seguinte recebe unavailable na hora. */
        const val MAX_QUEUED = 32

        /** Quantos reenvios do mesmo id podem esperar a mesma execução (a original incluída). */
        const val MAX_JOINS = 8

        /** Linhas de auditoria esperando escrita; acima disso a linha é descartada, o comando nunca espera. */
        const val AUDIT_QUEUE = 256
        const val SWEEP_MS = 1_000L

        /** Desfechos que nunca entram no cache: a próxima tentativa do mesmo id é reavaliada (P2-4). */
        private val UNCACHEABLE = setOf(ErrorCode.LOCAL_GATE_DENIED.wire, ErrorCode.UNAVAILABLE.wire)

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

        /** JSON canônico: chaves de objeto ordenadas em todos os níveis (a ordem do fio não muda a operação). */
        internal fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
            is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            else -> e.toString()
        }

        /**
         * A impressão digital de uma invocação: SHA-256 de id, comando e parâmetros canônicos, cada parte
         * prefixada pelo tamanho (nenhuma concatenação ambígua). É a chave do cache de deduplicação.
         */
        internal fun fingerprint(id: JsonElement, command: JsonElement, params: JsonObject): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (part in listOf(id.toString(), command.toString(), canonical(params))) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                md.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                md.update(bytes)
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    /**
     * O catálogo que o registro ATUAL anunciou em commands_v2. Quem monta o link.register troca este
     * catálogo junto (a cada conexão), para o despachante atender exatamente o que o Muse viu anunciado.
     */
    @Volatile var catalog: CommandCatalog = catalog

    private class Cached(val result: JsonObject, val resultBytes: Int, val at: Long)

    private inner class Invocation(
        val key: String,
        val id: JsonElement,
        val command: String,
        val spec: CommandSpec?,
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

        /** O timer do prazo; cancelado quando a invocação termina. */
        var deadline: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val cache = LinkedHashMap<String, Cached>()
    private val inFlight = HashMap<String, Invocation>()
    private val queue = ArrayDeque<Invocation>()
    private val slots = Semaphore(maxConcurrent)
    @Volatile private var closed = false

    private val threadCount = AtomicInteger()
    private fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "gadget-cmd-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }
    private val timer = ScheduledThreadPoolExecutor(1, daemon("gadget-cmd-timer")).apply {
        removeOnCancelPolicy = true // timer cancelado sai da fila do agendador na hora
    }

    private val dropped = AtomicInteger()
    private val auditFailures = AtomicInteger()
    private val auditor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(auditQueue), daemon("gadget-cmd-audit"),
        RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }, // fila cheia ou fechada: descarta, não bloqueia
    )

    init {
        if (sweepMs > 0) timer.scheduleWithFixedDelay({ sweep() }, sweepMs, sweepMs, TimeUnit.MILLISECONDS)
    }

    /** Quantos handlers estão executando agora (para testes e diagnóstico). */
    val running: Int get() = maxConcurrent - slots.availablePermits()

    /** Quantas invocações esperam vaga agora. */
    val queued: Int get() = synchronized(lock) { queue.size }

    /** Quantos resultados estão no cache de deduplicação agora (vencidos já removidos ou ainda por varrer). */
    val cached: Int get() = synchronized(lock) { cache.size }

    /** Invocações em curso (na fila ou executando), por impressão digital. */
    val pending: Int get() = synchronized(lock) { inFlight.size }

    /** Timers de prazo ainda agendados (os de invocações terminadas são cancelados e saem). */
    val pendingTimers: Int get() = timer.queue.count { (it as? RunnableScheduledFuture<*>)?.isPeriodic == false }

    /** Linhas de auditoria descartadas porque a fila estava cheia (ou o despachante já fechado). */
    val auditDropped: Int get() = dropped.get()

    /** Linhas de auditoria cuja escrita falhou (exceção da auditoria). */
    val auditFailed: Int get() = auditFailures.get()

    override fun onInvoke(message: JsonObject, reply: (JsonObject) -> Boolean) {
        val id = message["id"] ?: return
        if (!truthy(id)) return
        val receivedAt = nowMs()
        val receivedWall = wallMs()

        val rawCommand = message["command"]?.takeIf { truthy(it) } ?: message["name"]?.takeIf { truthy(it) } ?: JsonPrimitive("")
        val command = if (rawCommand is JsonPrimitive && rawCommand.isString) rawCommand.content else null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val shownCommand = command ?: ""

        if (closed) return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
        if (id.toString().length > maxIdChars) {
            return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.EXECUTOR_ERROR, "id is longer than $maxIdChars characters"))
        }
        val spec = command?.let { catalog.find(it) }
        val key = fingerprint(id, rawCommand, params)

        while (true) {
            // Ponto 1: portão ANTES do cache — com a parada ativa nem um resultado guardado sai.
            gateDenial(spec)?.let { return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.LOCAL_GATE_DENIED, it)) }
            var hit: Cached? = null
            var running: Invocation? = null
            var fresh: Invocation? = null
            var shut = false
            synchronized(lock) {
                purge(nowMs())
                shut = closed // fechou entre a primeira olhada e agora: nada novo entra em inFlight
                hit = if (shut) null else cache[key]
                if (!shut && hit == null) {
                    running = inFlight[key]
                    if (running == null) {
                        fresh = Invocation(key, id, shownCommand, spec, receivedAt, receivedWall, reply).also { inFlight[key] = it }
                    }
                }
            }
            if (shut) return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
            hit?.let {
                // Resposta e auditoria sempre fora do lock (o envio cifra). A linha de auditoria só é
                // enfileirada (não espera nada), e entra antes da resposta para a ordem das linhas seguir a
                // ordem dos desfechos.
                record(id, shownCommand, receivedWall, 0, outcomeOf(it.result), true, it.resultBytes)
                deliver(listOf(reply), id, it.result)
                return
            }
            running?.let { original ->
                // Ponto 2: portão ANTES de juntar o reenvio à execução em curso.
                gateDenial(spec)?.let { return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.LOCAL_GATE_DENIED, it)) }
                var refused = false
                val joined = synchronized(lock) {
                    when {
                        original.done -> false // terminou entre as duas olhadas: decide de novo (cache ou nova)
                        original.replies.size >= maxJoins -> true.also { refused = true }
                        else -> true.also { original.replies += reply }
                    }
                }
                if (refused) return answer(reply, id, shownCommand, receivedAt, receivedWall, error(ErrorCode.UNAVAILABLE, "too many resends of this id"))
                if (joined) return
            }
            fresh?.let { return dispatch(it, command, spec, params) }
        }
    }

    private fun dispatch(invocation: Invocation, command: String?, spec: CommandSpec?, params: JsonObject) {
        when {
            command == null -> complete(invocation, error(ErrorCode.EXECUTOR_ERROR, "command must be a string"))
            spec == null -> complete(invocation, error(ErrorCode.NOT_IMPLEMENTED, "unknown command"))
            else -> {
                val (checked, problem) = spec.checkParams(params)
                if (checked == null) complete(invocation, error(ErrorCode.EXECUTOR_ERROR, problem)) else schedule(invocation, spec, checked)
            }
        }
    }

    private fun schedule(invocation: Invocation, spec: CommandSpec, params: JsonObject) {
        invocation.checked = params
        val deadline = try {
            timer.schedule({ onDeadline(invocation) }, spec.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return complete(invocation, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
        }
        var full = false
        val runNow = synchronized(lock) {
            invocation.deadline = deadline
            when {
                invocation.done -> {
                    deadline.cancel(false)
                    return
                }
                slots.tryAcquire() -> true
                queue.size >= maxQueued -> false.also { full = true }
                else -> false.also { queue.addLast(invocation) }
            }
        }
        if (full) complete(invocation, error(ErrorCode.UNAVAILABLE, "queue full ($maxQueued waiting)"))
        if (runNow) start(invocation)
    }

    /** Chamado com uma vaga já tomada para [invocation]. */
    private fun start(invocation: Invocation) {
        try {
            workers.execute { runHandler(invocation) }
        } catch (e: Exception) {
            complete(invocation, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
            release()
        }
    }

    private fun runHandler(invocation: Invocation) {
        try {
            val spec = invocation.spec!!
            val params = invocation.checked!!
            if (synchronized(lock) { invocation.done }) return // o prazo venceu antes de a thread pegar a tarefa
            // Ponto 3: portão IMEDIATAMENTE antes de executar — vale para quem esperou na fila durante uma
            // parada ou uma revogação.
            gateDenial(spec)?.let { return complete(invocation, error(ErrorCode.LOCAL_GATE_DENIED, it)) }
            synchronized(lock) {
                if (invocation.done) return
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
        val outcome = outcomeOf(final)
        val replies: List<(JsonObject) -> Boolean>
        synchronized(lock) {
            if (invocation.done) return
            invocation.done = true
            if (inFlight[invocation.key] === invocation) inFlight.remove(invocation.key)
            queue.remove(invocation)
            invocation.deadline?.cancel(false)
            invocation.deadline = null
            if (outcome !in UNCACHEABLE) {
                cache.remove(invocation.key)
                cache[invocation.key] = Cached(final, bytes, nowMs())
                while (cache.size > cacheSize) cache.remove(cache.keys.first())
            }
            replies = invocation.replies.toList()
        }
        val ms = nowMs() - invocation.receivedAt
        record(invocation.id, invocation.command, invocation.receivedWall, ms, outcome, false, bytes)
        // Cada reenvio que esperou esta execução também é uma invocação auditada (dedup: nada rodou de novo).
        repeat(replies.size - 1) { record(invocation.id, invocation.command, invocation.receivedWall, ms, outcome, true, bytes) }
        deliver(replies, invocation.id, final)
    }

    /** Resposta direta, sem execução e sem cache (recusa antes de qualquer decisão). */
    private fun answer(reply: (JsonObject) -> Boolean, id: JsonElement, command: String, at: Long, wall: Long, result: JsonObject) {
        val bytes = encodedSize(id, result)
        record(id, command, wall, nowMs() - at, outcomeOf(result), false, bytes)
        // Um id gigante faria a própria recusa estourar o orçamento: nesse caso não há resposta possível.
        if (bytes <= maxResultBytes) deliver(listOf(reply), id, result)
    }

    private fun gateDenial(spec: CommandSpec?): String? = try {
        gate.deny(spec ?: GATE_PROBE)
    } catch (e: Throwable) {
        "gate failed (${e.javaClass.simpleName})" // portão quebrado = fechado
    }

    private fun deliver(replies: List<(JsonObject) -> Boolean>, id: JsonElement, result: JsonObject) {
        val message = resultMessage(id, result)
        for (r in replies) {
            try {
                r(message)
            } catch (_: Throwable) {
                // Sessão caída: o resultado fica no cache para o reenvio idêntico.
            }
        }
    }

    /** Enfileira a linha para o executor da auditoria; nunca espera, nunca lança. */
    private fun record(id: JsonElement, command: String, at: Long, ms: Long, outcome: String, dedup: Boolean, bytes: Int) {
        val shown = if (id is JsonPrimitive && id.isString) id.content else id.toString()
        val entry = InvokeAuditEntry(printable(shown), printable(command), at, ms, outcome, dedup, bytes)
        try {
            auditor.execute {
                try {
                    audit(entry)
                } catch (_: Throwable) {
                    auditFailures.incrementAndGet() // auditoria nunca derruba a resposta
                }
            }
        } catch (_: Throwable) {
            dropped.incrementAndGet()
        }
    }

    private fun purge(now: Long) {
        val it = cache.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().at >= cacheTtlMs) it.remove() else break // ordem de inserção = ordem de idade
        }
    }

    /**
     * Revalida o portão de quem espera na fila: negou = local_gate_denied na hora, sem executar. Só
     * responde quem ainda está na fila (quem já saiu dela é revalidado pelo ponto 3, antes de executar).
     */
    fun recheckQueued() {
        val waiting = synchronized(lock) { queue.toList() }
        for (invocation in waiting) {
            val denied = gateDenial(invocation.spec) ?: continue
            val claimed = synchronized(lock) { queue.remove(invocation) }
            if (claimed) complete(invocation, error(ErrorCode.LOCAL_GATE_DENIED, denied))
        }
    }

    /** Varredura periódica: cache vencido sai mesmo sem tráfego, e a fila é revalidada contra o portão. */
    internal fun sweep() {
        try {
            synchronized(lock) { purge(nowMs()) }
            recheckQueued()
        } catch (_: Throwable) {
            // a varredura periódica nunca pode morrer por exceção (o agendador a cancelaria)
        }
    }

    /**
     * Desliga o despachante sem esperar ninguém: tudo o que está pendente (na fila ou executando) termina com
     * unavailable, invocações novas recebem unavailable na hora, handlers em curso recebem interrupt.
     */
    fun close() {
        val pendingNow = synchronized(lock) {
            if (closed) return
            closed = true
            inFlight.values.toList()
        }
        for (invocation in pendingNow) {
            synchronized(lock) { invocation.thread?.interrupt() }
            complete(invocation, error(ErrorCode.UNAVAILABLE, "dispatcher closed"))
        }
        timer.shutdownNow()
        workers.shutdownNow()
        auditor.shutdown() // as linhas já na fila ainda saem; nada novo entra
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
