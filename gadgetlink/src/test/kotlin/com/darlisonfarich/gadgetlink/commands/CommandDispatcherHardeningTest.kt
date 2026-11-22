package com.darlisonfarich.gadgetlink.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regressões adversariais das correções obrigatórias do PR nº 37 (auditoria independente sobre 2f6ecf63).
 * Cada teste nomeia o cenário que a auditoria (ou o brief de correções) apontou e prova a falha eliminada.
 */
class CommandDispatcherHardeningTest {
    private val opened = ArrayList<CommandDispatcher>()
    private val latches = ArrayList<CountDownLatch>()

    /** Handlers bloqueantes que JÁ passaram do portão e estão executando (vaga tomada não basta: o ponto 3 vem depois). */
    private val blockStarted = AtomicInteger()

    @After fun close() {
        latches.forEach { while (it.count > 0) it.countDown() }
        opened.forEach { it.close() }
    }

    private fun latch() = CountDownLatch(1).also { latches += it }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is JsonElement -> v
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> error("unsupported")
            }
        },
    )

    private fun invoke(id: Any, command: String, params: JsonObject? = null): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["method"] = JsonPrimitive("link.invoke")
        m["id"] = if (id is Number) JsonPrimitive(id) else JsonPrimitive(id.toString())
        m["command"] = JsonPrimitive(command)
        if (params != null) m["params"] = params
        return JsonObject(m)
    }

    private class Replies(private val alive: AtomicBoolean = AtomicBoolean(true)) {
        val all: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())

        /** Uma sessão: depois de [alive] = false, o envio falha (a sessão caiu) e nada é recebido. */
        val sink: (JsonObject) -> Boolean = { if (alive.get()) { all += it; true } else false }

        fun await(n: Int, ms: Long = 5_000): List<JsonObject> {
            val until = System.currentTimeMillis() + ms
            while (all.size < n && System.currentTimeMillis() < until) Thread.sleep(2)
            assertTrue("esperava $n resultados, vieram ${all.size}", all.size >= n)
            return synchronized(all) { all.toList() }
        }

        fun single(ms: Long = 5_000): JsonObject = await(1, ms).single()
    }

    private fun JsonObject.error(): String? = (this["error"] as? JsonPrimitive)?.content
    private fun JsonObject.detail(): String? = (this["detail"] as? JsonPrimitive)?.content

    private fun dispatcher(
        vararg extra: CommandSpec,
        includeStubs: Boolean = false,
        gate: LocalGate = LocalGate.OPEN,
        audit: (InvokeAuditEntry) -> Unit = {},
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
        maxQueued: Int = CommandDispatcher.MAX_QUEUED,
        maxJoins: Int = CommandDispatcher.MAX_JOINS,
        cacheSize: Int = CommandDispatcher.CACHE_SIZE,
        auditQueue: Int = CommandDispatcher.AUDIT_QUEUE,
        sweepMs: Long = 0, // determinístico: só a varredura que o teste pedir
    ): CommandDispatcher {
        val catalog = CommandCatalog(LinkTestCommands.all { 1_700_000_000_000 } + PhoneCatalog.stubs() + extra, includeStubs)
        return CommandDispatcher(
            catalog, gate, audit, nowMs = nowMs, maxQueued = maxQueued, maxJoins = maxJoins, cacheSize = cacheSize,
            auditQueue = auditQueue, sweepMs = sweepMs,
        ).also { opened += it }
    }

    private fun counting(name: String, counter: AtomicInteger, timeoutMs: Long = 5_000, writes: Boolean = true, body: (JsonObject) -> CommandResult = { CommandResult.Ok(JsonPrimitive("$name done")) }) =
        CommandSpec(name, "test command", timeoutMs = timeoutMs, writes = writes, implemented = true) {
            counter.incrementAndGet()
            body(it)
        }

    private fun blocking(name: String, release: CountDownLatch, started: AtomicInteger = blockStarted, timeoutMs: Long = 10_000) =
        CommandSpec(name, "blocks", timeoutMs = timeoutMs, writes = false, implemented = true) {
            started.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            CommandResult.Ok(JsonPrimitive("released"))
        }

    private fun until(ms: Long = 3_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(2)
        assertTrue(what, cond())
    }

    /** Ocupa as 4 vagas com handlers bloqueados e espera eles estarem de fato rodando. */
    private fun fillSlots(d: CommandDispatcher, sink: (JsonObject) -> Boolean, prefix: String = "blk") {
        for (i in 0 until CommandDispatcher.MAX_CONCURRENT) d.onInvoke(invoke("$prefix-$i", "test.block"), sink)
        until(what = "4 handlers executando") { blockStarted.get() >= CommandDispatcher.MAX_CONCURRENT && d.running == CommandDispatcher.MAX_CONCURRENT }
    }

    // ==== P1-1: id repetido com operações diferentes ================================================
    @Test
    fun aReusedIdWithADifferentCommandRunsItsOwnOperationAndNeverGetsTheOtherResult() {
        val a = AtomicInteger()
        val b = AtomicInteger()
        val d = dispatcher(counting("test.a", a), counting("test.b", b))
        val r = Replies()
        d.onInvoke(invoke("inv-7", "test.a"), r.sink)
        r.await(1)
        d.onInvoke(invoke("inv-7", "test.b"), r.sink)
        val got = r.await(2)
        assertEquals(1, a.get())
        assertEquals("a outra operação executou de verdade", 1, b.get())
        assertEquals(JsonPrimitive("test.a done"), got[0]["payload"])
        assertEquals(JsonPrimitive("test.b done"), got[1]["payload"])
    }

    @Test
    fun aReusedIdWithDifferentParametersRunsItsOwnOperationAndNeverGetsTheOtherResult() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("inv-8", LinkTestCommands.ECHO_VERIFY, obj("data" to "abc")), r.sink)
        r.await(1)
        d.onInvoke(invoke("inv-8", LinkTestCommands.ECHO_VERIFY, obj("data" to "abd")), r.sink)
        val got = r.await(2)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", got[0]["payload"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals(LinkTestCommands.sha256Hex("abd".toByteArray()), got[1]["payload"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        // e parâmetros não declarados também contam: outra operação, outra execução
        val runs = AtomicInteger()
        val d2 = dispatcher(counting("test.w", runs))
        val r2 = Replies()
        d2.onInvoke(invoke("x", "test.w", obj("k" to 1)), r2.sink)
        r2.await(1)
        d2.onInvoke(invoke("x", "test.w", obj("k" to 2)), r2.sink)
        r2.await(2)
        assertEquals(2, runs.get())
    }

    @Test
    fun aConcurrentDifferentOperationWithTheSameIdOnOneSessionIsDroppedWithoutAmbiguousResults() {
        val release = latch()
        val other = AtomicInteger()
        val d = dispatcher(blocking("test.block", release), counting("test.other", other))
        val replies = Replies()
        d.onInvokeFromSession("session-one", invoke("same", "test.block"), replies.sink)
        until(what = "original executando") { blockStarted.get() == 1 }
        d.onInvokeFromSession("session-one", invoke("same", "test.other"), replies.sink)
        assertEquals(1, d.rejectedIdCollisions)
        assertEquals(0, other.get())
        release.countDown()
        assertEquals(JsonPrimitive("released"), replies.single()["payload"])
        Thread.sleep(80)
        assertEquals("never send two distinct link.result for the same live id", 1, replies.all.size)
        // Once the original has completed, a reused id for a new operation is safe.
        d.onInvokeFromSession("session-one", invoke("same", "test.other"), replies.sink)
        assertEquals(JsonPrimitive("test.other done"), replies.await(2).last()["payload"])
        assertEquals(1, other.get())
    }

    @Test
    fun theSameIdInDifferentNoiseSessionsMayRunDifferentOperationsConcurrently() {
        val release = latch()
        val other = AtomicInteger()
        val d = dispatcher(blocking("test.block", release), counting("test.other", other))
        val first = Replies()
        val second = Replies()
        d.onInvokeFromSession("old-session", invoke("same", "test.block"), first.sink)
        until(what = "original executando") { blockStarted.get() == 1 }
        d.onInvokeFromSession("new-session", invoke("same", "test.other"), second.sink)
        assertEquals(JsonPrimitive("test.other done"), second.single()["payload"])
        assertTrue(first.all.isEmpty())
        release.countDown()
        assertEquals(JsonPrimitive("released"), first.single()["payload"])
        assertEquals(1, other.get())
        assertEquals(0, d.rejectedIdCollisions)
    }

    @Test
    fun aRemovedCommandIsNeverServedFromTheOldSessionCache() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.temporary", runs))
        val r = Replies()
        d.onInvoke(invoke("id", "test.temporary"), r.sink)
        assertEquals(JsonPrimitive(true), r.single()["ok"])
        assertEquals(1, runs.get())
        // A different link.register no longer advertises this command.
        d.catalog = CommandCatalog(LinkTestCommands.all())
        d.onInvoke(invoke("id", "test.temporary"), r.sink)
        assertEquals("not_implemented", r.await(2).last().error())
        assertEquals(1, runs.get())
    }

    @Test
    fun theFingerprintSeparatesIdCommandAndParametersWithoutAmbiguity() {
        val p = obj("a" to 1)
        val base = CommandDispatcher.fingerprint(JsonPrimitive("1"), JsonPrimitive("x.y"), p)
        assertEquals(base, CommandDispatcher.fingerprint(JsonPrimitive("1"), JsonPrimitive("x.y"), obj("a" to 1)))
        assertNotEquals(base, CommandDispatcher.fingerprint(JsonPrimitive(1), JsonPrimitive("x.y"), p))
        assertNotEquals(base, CommandDispatcher.fingerprint(JsonPrimitive("1"), JsonPrimitive("x.z"), p))
        assertNotEquals(base, CommandDispatcher.fingerprint(JsonPrimitive("1"), JsonPrimitive("x.y"), obj("a" to 2)))
        assertNotEquals(base, CommandDispatcher.fingerprint(JsonPrimitive("1"), JsonPrimitive("x.y"), obj("a" to "1")))
        // fronteira entre partes: ("ab","c") ≠ ("a","bc")
        assertNotEquals(
            CommandDispatcher.fingerprint(JsonPrimitive("ab"), JsonPrimitive("c"), obj()),
            CommandDispatcher.fingerprint(JsonPrimitive("a"), JsonPrimitive("bc"), obj()),
        )
    }

    // ==== regressão: reenvio idêntico continua idempotente ==========================================
    @Test
    fun anIdenticalResendStaysIdempotentAndDoesNotRunAgainEvenWithReorderedParameters() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.write", runs) { CommandResult.Ok(JsonPrimitive("wrote ${runs.get()}")) })
        val r = Replies()
        d.onInvoke(invoke("w-1", "test.write", obj("a" to 1, "b" to obj("y" to 2, "x" to 1))), r.sink)
        r.await(1)
        d.onInvoke(invoke("w-1", "test.write", obj("b" to obj("x" to 1, "y" to 2), "a" to 1)), r.sink) // mesma operação, outra ordem no fio
        d.onInvoke(invoke("w-1", "test.write", obj("a" to 1, "b" to obj("y" to 2, "x" to 1))), r.sink)
        val got = r.await(3)
        assertEquals(1, runs.get())
        assertEquals(got[0], got[1])
        assertEquals(got[0], got[2])
        assertEquals(JsonPrimitive("wrote 1"), got[0]["payload"])
    }

    // ==== P1-2: portão em todos os pontos de decisão =================================================
    @Test
    fun theGateIsCheckedBeforeTheCacheSoACachedIdIsDeniedOnceTheGateCloses() {
        val runs = AtomicInteger()
        val stopped = AtomicBoolean(false)
        val d = dispatcher(counting("test.write", runs), gate = { if (stopped.get()) "app stopped" else null })
        val r = Replies()
        d.onInvoke(invoke("c-1", "test.write"), r.sink)
        val original = r.await(1)[0]
        assertEquals(JsonPrimitive(true), original["ok"])
        stopped.set(true)
        d.onInvoke(invoke("c-1", "test.write"), r.sink)
        val denied = r.await(2)[1]
        assertEquals("local_gate_denied", denied.error())
        assertNull("nada do resultado guardado sai com o portão fechado", denied["payload"])
        assertEquals(1, runs.get())
        // portão reaberto: o resultado guardado da operação original volta (a negação não substituiu o cache)
        stopped.set(false)
        d.onInvoke(invoke("c-1", "test.write"), r.sink)
        assertEquals(original, r.await(3)[2])
        assertEquals(1, runs.get())
    }

    @Test
    fun theGateIsCheckedBeforeJoiningAResendToTheRunningOriginal() {
        val release = latch()
        val stopped = AtomicBoolean(false)
        val d = dispatcher(blocking("test.block", release), gate = { if (stopped.get()) "app stopped" else null })
        val first = Replies()
        val resend = Replies()
        d.onInvoke(invoke("j-1", "test.block"), first.sink)
        until(what = "original executando") { blockStarted.get() == 1 }
        stopped.set(true)
        d.onInvoke(invoke("j-1", "test.block"), resend.sink)
        assertEquals("o reenvio é negado na hora, sem esperar a original", "local_gate_denied", resend.single(1_000).error())
        release.countDown()
        assertEquals(JsonPrimitive("released"), first.single()["payload"])
        Thread.sleep(50)
        assertEquals("o reenvio negado não recebe o resultado depois", 1, resend.all.size)
    }

    @Test
    fun theJoinPointItselfConsultsTheGate() {
        val release = latch()
        val calls = AtomicInteger()
        // original: consulta 1 (antes do cache) e 2 (antes de executar); reenvio: 3 (antes do cache) libera,
        // 4 (antes de juntar) nega — prova o ponto de junção isolado.
        val d = dispatcher(blocking("test.block", release), gate = { if (calls.incrementAndGet() == 4) "revoked at join" else null })
        val first = Replies()
        val resend = Replies()
        d.onInvoke(invoke("j-2", "test.block"), first.sink)
        until(what = "original executando") { blockStarted.get() == 1 }
        d.onInvoke(invoke("j-2", "test.block"), resend.sink)
        val reply = resend.single(1_000)
        assertEquals("local_gate_denied", reply.error())
        assertEquals("revoked at join", reply.detail())
        release.countDown()
        assertEquals(JsonPrimitive("released"), first.single()["payload"])
        assertEquals(4, calls.get())
    }

    @Test
    fun theGateIsCheckedAgainImmediatelyBeforeExecution() {
        val runs = AtomicInteger()
        val calls = AtomicInteger()
        // libera a 1ª consulta (antes do cache) e nega a seguinte (antes de executar)
        val d = dispatcher(counting("test.write", runs), gate = { if (calls.incrementAndGet() >= 2) "revoked" else null })
        val r = Replies()
        d.onInvoke(invoke("e-1", "test.write"), r.sink)
        val reply = r.single()
        assertEquals("local_gate_denied", reply.error())
        assertEquals("revoked", reply.detail())
        assertEquals(0, runs.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun aGateThatThrowsFailsClosed() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.write", runs), gate = { throw IllegalStateException("prefs broken") })
        val r = Replies()
        d.onInvoke(invoke("g-1", "test.write"), r.sink)
        val reply = r.single()
        assertEquals("local_gate_denied", reply.error())
        assertEquals("gate failed (IllegalStateException)", reply.detail())
        assertEquals(0, runs.get())
    }

    // ==== P1-3: fila não executa depois de parada/revogação =========================================
    @Test
    fun aPermissionRevokedWhileWaitingInTheQueueIsDeniedWhenItLeavesTheQueueAndNeverRuns() {
        val release = latch()
        val writes = AtomicInteger()
        val revoked = AtomicBoolean(false)
        val d = dispatcher(
            blocking("test.block", release), counting("test.write", writes),
            gate = { c -> if (c.writes && revoked.get()) "write_disabled" else null },
        )
        val r = Replies()
        fillSlots(d, r.sink)
        val queued = Replies()
        d.onInvoke(invoke("q-1", "test.write"), queued.sink)
        assertEquals(1, d.queued)
        revoked.set(true) // revogação durante a espera (sem varredura: prova o ponto de saída da fila)
        release.countDown()
        val reply = queued.single()
        assertEquals("local_gate_denied", reply.error())
        assertEquals("write_disabled", reply.detail())
        r.await(4)
        Thread.sleep(50)
        assertEquals("nada executou", 0, writes.get())
        assertEquals(0, d.running)
    }

    @Test
    fun aStopWhileWaitingInTheQueueIsAnsweredByTheSweepWithoutWaitingForASlot() {
        val release = latch()
        val writes = AtomicInteger()
        val stopped = AtomicBoolean(false)
        val d = dispatcher(
            blocking("test.block", release), counting("test.write", writes),
            gate = { if (stopped.get()) "app stopped (quit requested)" else null }, sweepMs = 20,
        )
        val r = Replies()
        fillSlots(d, r.sink)
        val queued = Replies()
        d.onInvoke(invoke("q-2", "test.write"), queued.sink)
        stopped.set(true) // "Sair totalmente" com as 4 vagas ainda ocupadas
        assertEquals("local_gate_denied", queued.single(2_000).error())
        assertEquals("as vagas continuam ocupadas: a negação não esperou nenhuma", 4, d.running)
        assertEquals(0, d.queued)
        release.countDown()
        r.await(4)
        Thread.sleep(50)
        assertEquals(0, writes.get())
    }

    // ==== P2-4: negações e indisponibilidade não são cacheadas ======================================
    @Test
    fun aDeniedIdIsReevaluatedOnTheNextAttemptInsteadOfGettingTheOldDenial() {
        val runs = AtomicInteger()
        val stopped = AtomicBoolean(true)
        val d = dispatcher(counting("test.write", runs), gate = { if (stopped.get()) "app stopped" else null })
        val r = Replies()
        d.onInvoke(invoke("n-1", "test.write"), r.sink)
        assertEquals("local_gate_denied", r.await(1)[0].error())
        assertEquals(0, d.cached)
        stopped.set(false)
        d.onInvoke(invoke("n-1", "test.write"), r.sink)
        assertEquals(JsonPrimitive(true), r.await(2)[1]["ok"])
        assertEquals(1, runs.get())
    }

    @Test
    fun aDenialAtExecutionTimeIsNotCachedEither() {
        val runs = AtomicInteger()
        val calls = AtomicInteger()
        val d = dispatcher(counting("test.write", runs), gate = { if (calls.incrementAndGet() == 2) "revoked" else null })
        val r = Replies()
        d.onInvoke(invoke("n-2", "test.write"), r.sink)
        assertEquals("local_gate_denied", r.await(1)[0].error())
        d.onInvoke(invoke("n-2", "test.write"), r.sink)
        assertEquals(JsonPrimitive(true), r.await(2)[1]["ok"])
        assertEquals(1, runs.get())
    }

    // ==== reconexão e reutilização de ids (investigação do item 10) =================================
    @Test
    fun aResultLostWithItsSessionIsServedToTheIdenticalResendOnTheNextSession() {
        val release = latch()
        val runs = AtomicInteger()
        val d = dispatcher(
            CommandSpec("test.tap", "write", timeoutMs = 5_000, writes = true, implemented = true) {
                runs.incrementAndGet()
                release.await(5, TimeUnit.SECONDS)
                CommandResult.Ok(JsonPrimitive("tapped"))
            },
        )
        val aliveA = AtomicBoolean(true)
        val sessionA = Replies(aliveA)
        d.onInvoke(invoke("inv-41", "test.tap", obj("request_id" to "req-1")), sessionA.sink)
        until(what = "executando") { runs.get() == 1 }
        aliveA.set(false) // a sessão A cai no meio da execução
        release.countDown()
        until(what = "resultado guardado") { d.cached == 1 && d.pending == 0 }
        assertTrue(sessionA.all.isEmpty())
        val sessionB = Replies()
        d.onInvoke(invoke("inv-41", "test.tap", obj("request_id" to "req-1")), sessionB.sink)
        assertEquals(JsonPrimitive("tapped"), sessionB.single()["payload"])
        assertEquals("a escrita não foi repetida na reconexão", 1, runs.get())
    }

    @Test
    fun aResendOnANewSessionWhileTheOriginalStillRunsGetsTheResultOnTheNewSession() {
        val release = latch()
        val started = AtomicInteger()
        val d = dispatcher(blocking("test.block", release, started))
        val aliveA = AtomicBoolean(true)
        val sessionA = Replies(aliveA)
        val sessionB = Replies()
        d.onInvoke(invoke("inv-42", "test.block"), sessionA.sink)
        until(what = "executando") { started.get() == 1 }
        aliveA.set(false)
        d.onInvoke(invoke("inv-42", "test.block"), sessionB.sink)
        release.countDown()
        assertEquals(JsonPrimitive("released"), sessionB.single()["payload"])
        assertEquals(1, started.get())
    }

    @Test
    fun afterAReconnectAnIdReusedForAnotherOperationIsNotAnsweredFromTheCache() {
        val d = dispatcher()
        val sessionA = Replies()
        d.onInvoke(invoke("1", LinkTestCommands.PING), sessionA.sink)
        assertEquals("pong", sessionA.single()["payload"]!!.jsonObject["reply"]!!.jsonPrimitive.content)
        // o Muse reinicia e volta a numerar do começo: o mesmo id "1" chega com outra operação
        val sessionB = Replies()
        d.onInvoke(invoke("1", LinkTestCommands.ECHO_VERIFY, obj("data" to "abc")), sessionB.sink)
        val reply = sessionB.single()
        assertEquals(3, reply["payload"]!!.jsonObject["bytes"]!!.jsonPrimitive.content.toInt())
        assertNull(reply["payload"]!!.jsonObject["reply"])
    }

    // ==== timeouts, cancelamento e higiene de timers ===============================================
    @Test
    fun theProbeGenerationStopsAtTheNextChunkWhenTheThreadIsInterrupted() {
        val chunks = ArrayList<Int>()
        try {
            LinkTestCommands.probePayload(LinkTestCommands.DEFAULT_SEED, LinkTestCommands.MAX_PROBE_BYTES) { c ->
                chunks += c
                if (c == 2) Thread.currentThread().interrupt()
            }
            throw AssertionError("a geração devia ter parado")
        } catch (e: InterruptedException) {
            assertEquals("parou no bloco em que foi interrompida, sem gerar o resto", listOf(0, 1, 2), chunks)
        } finally {
            Thread.interrupted()
        }
        // sem interrupção, o payload sai igual ao provado ao vivo (32.768 B, hash conferido pelo Ship)
        assertEquals(
            "680c370b",
            LinkTestCommands.sha256Hex(LinkTestCommands.probePayload(LinkTestCommands.DEFAULT_SEED, 32_768).toByteArray()).take(8),
        )
    }

    @Test
    fun aProbeThatOutlivesItsDeadlineIsInterruptedMidGenerationAndFreesItsSlot() {
        val lastChunk = AtomicInteger(-1)
        val probe = LinkTestCommands.responseSizeProbe()
        val d = dispatcher(
            CommandSpec("test.probe", "probe with a short deadline", probe.required, probe.optional, timeoutMs = 150, writes = false, implemented = true) {
                val data = LinkTestCommands.probePayload(LinkTestCommands.DEFAULT_SEED, LinkTestCommands.MAX_PROBE_BYTES) { c ->
                    lastChunk.set(c)
                    if (c == 1) while (!Thread.currentThread().isInterrupted) Thread.onSpinWait() // "lento" até o prazo
                }
                CommandResult.Ok(JsonPrimitive(data.length))
            },
        )
        val r = Replies()
        d.onInvoke(invoke("slow-probe", "test.probe", obj("bytes" to LinkTestCommands.MAX_PROBE_BYTES)), r.sink)
        assertEquals("timeout", r.single().error())
        until(what = "vaga devolvida") { d.running == 0 }
        assertEquals("parou no bloco do prazo", 1, lastChunk.get())
    }

    @Test
    fun aSeedOverTheLimitIsAStructuredErrorAndTheLimitItselfIsAccepted() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("s1", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to 16, "seed" to "x".repeat(LinkTestCommands.MAX_SEED_CHARS + 1))), r.sink)
        val over = r.await(1)[0]
        assertEquals("executor_error", over.error())
        assertEquals("seed must be at most ${LinkTestCommands.MAX_SEED_CHARS} characters", over.detail())
        d.onInvoke(invoke("s2", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to 16, "seed" to "x".repeat(LinkTestCommands.MAX_SEED_CHARS))), r.sink)
        assertEquals(JsonPrimitive(true), r.await(2)[1]["ok"])
        val described = CommandCatalog.museManus().commandsV2()[LinkTestCommands.RESPONSE_SIZE_PROBE]!!.jsonObject["optional"]!!.jsonObject["seed"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("o limite é declarado no anúncio", described.contains("at most ${LinkTestCommands.MAX_SEED_CHARS} characters"))
    }

    @Test
    fun deadlineTimersAreCancelledWhenTheirInvocationsEnd() {
        val d = dispatcher()
        val r = Replies()
        for (i in 0 until 50) {
            d.onInvoke(invoke("t-$i", LinkTestCommands.PING), r.sink)
            r.await(i + 1)
        }
        assertTrue(r.all.all { it["ok"] == JsonPrimitive(true) })
        until(what = "nenhum timer de prazo retido (eram 50 de 5 s)") { d.pendingTimers == 0 }
        assertEquals(0, d.pending)
    }

    @Test
    fun aQueuedInvocationThatTimesOutLeavesTheQueueImmediately() {
        val release = latch()
        val quick = AtomicInteger()
        val d = dispatcher(blocking("test.block", release), counting("test.quick", quick, timeoutMs = 100))
        val r = Replies()
        fillSlots(d, r.sink)
        val q = Replies()
        d.onInvoke(invoke("tq", "test.quick"), q.sink)
        assertEquals("timeout", q.single().error())
        assertEquals("a invocação vencida saiu da fila sem esperar vaga", 0, d.queued)
        assertEquals(0, d.pending - CommandDispatcher.MAX_CONCURRENT)
        release.countDown()
        r.await(4)
        assertEquals(0, quick.get())
    }

    @Test
    fun expiredCacheEntriesAreSweptWithoutNewTraffic() {
        var now = 5_000_000L
        val d = dispatcher(nowMs = { now })
        val r = Replies()
        for (i in 0 until 10) d.onInvoke(invoke("x-$i", LinkTestCommands.PING), r.sink)
        r.await(10)
        assertEquals(10, d.cached)
        now += CommandDispatcher.CACHE_TTL_MS
        d.sweep()
        assertEquals("nada vencido fica retido", 0, d.cached)
    }

    // ==== P2-7: esgotamento =========================================================================
    @Test
    fun aFullQueueAnswersUnavailableImmediatelyAndThatAnswerIsNotCached() {
        val release = latch()
        val quick = AtomicInteger()
        val d = dispatcher(blocking("test.block", release), counting("test.quick", quick), maxQueued = 2)
        val r = Replies()
        fillSlots(d, r.sink)
        d.onInvoke(invoke("w1", "test.quick"), r.sink)
        d.onInvoke(invoke("w2", "test.quick"), r.sink)
        assertEquals(2, d.queued)
        val over = Replies()
        val t0 = System.nanoTime()
        d.onInvoke(invoke("w3", "test.quick"), over.sink)
        val reply = over.single(1_000)
        assertTrue("resposta imediata", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertEquals("unavailable", reply.error())
        assertEquals("queue full (2 waiting)", reply.detail())
        release.countDown()
        r.await(6)
        d.onInvoke(invoke("w3", "test.quick"), over.sink) // a nova tentativa é reavaliada
        assertEquals(JsonPrimitive(true), over.await(2)[1]["ok"])
        assertEquals(3, quick.get())
    }

    @Test
    fun concurrencyAboveTheSemaphoreNeverRunsMoreThanFour() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val release = latch()
        val d = dispatcher(
            CommandSpec("test.block", "blocks", timeoutMs = 10_000, writes = false, implemented = true) {
                peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                try {
                    release.await(10, TimeUnit.SECONDS)
                } finally {
                    running.decrementAndGet()
                }
                CommandResult.Ok(JsonPrimitive("ok"))
            },
        )
        val r = Replies()
        val threads = (0 until 8).map { t -> Thread { for (i in 0 until 4) d.onInvoke(invoke("c-$t-$i", "test.block"), r.sink) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        until(what = "4 rodando") { running.get() == 4 }
        assertEquals(28, d.queued)
        release.countDown()
        val got = r.await(32)
        assertEquals(4, peak.get())
        assertTrue(got.all { it["ok"] == JsonPrimitive(true) })
    }

    @Test
    fun theCacheStaysAtItsLimit() {
        val d = dispatcher()
        val r = Replies()
        for (i in 0 until 300) {
            d.onInvoke(invoke("k-$i", LinkTestCommands.PING), r.sink)
            r.await(i + 1)
        }
        until(what = "todas terminadas") { d.pending == 0 }
        assertEquals(CommandDispatcher.CACHE_SIZE, d.cached)
    }

    @Test
    fun anIdOverTheLimitIsRefusedWithoutRunningAndAHugeOneGetsNoReplyAtAll() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.write", runs))
        val r = Replies()
        val okId = "i".repeat(CommandDispatcher.MAX_ID_CHARS - 2) // forma JSON = 128 com as aspas
        d.onInvoke(invoke(okId, "test.write"), r.sink)
        assertEquals(JsonPrimitive(true), r.await(1)[0]["ok"])
        d.onInvoke(invoke(okId + "i", "test.write"), r.sink)
        val refused = r.await(2)[1]
        assertEquals("executor_error", refused.error())
        assertEquals("id is longer than ${CommandDispatcher.MAX_ID_CHARS} characters", refused.detail())
        assertEquals(1, runs.get())
        val huge = Replies()
        d.onInvoke(invoke("h".repeat(250 * 1024), "test.write"), huge.sink)
        Thread.sleep(50)
        assertTrue("a recusa não caberia no orçamento: nada sai", huge.all.isEmpty())
        assertEquals(1, runs.get())
        assertEquals(0, d.pending)
    }

    @Test
    fun tooManyResendsOfARunningIdAreRefusedInsteadOfPilingUp() {
        val release = latch()
        val d = dispatcher(blocking("test.block", release), maxJoins = 3)
        val r = Replies()
        d.onInvoke(invoke("rj", "test.block"), r.sink)
        until(what = "executando") { blockStarted.get() == 1 }
        d.onInvoke(invoke("rj", "test.block"), r.sink)
        d.onInvoke(invoke("rj", "test.block"), r.sink)
        val extra = Replies()
        d.onInvoke(invoke("rj", "test.block"), extra.sink)
        assertEquals("unavailable", extra.single(1_000).error())
        release.countDown()
        val got = r.await(3)
        assertTrue(got.all { it["payload"] == JsonPrimitive("released") })
    }

    // ==== P2-9: auditoria fora do caminho bloqueante ================================================
    @Test
    fun anAuditThatThrowsNeverChangesTheResult() {
        val d = dispatcher(audit = { throw java.io.IOException("disk full") })
        val r = Replies()
        d.onInvoke(invoke("a1", LinkTestCommands.PING), r.sink)
        d.onInvoke(invoke("a2", "nope.nope"), r.sink)
        val got = r.await(2).associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("pong", got["a1"]!!["payload"]!!.jsonObject["reply"]!!.jsonPrimitive.content)
        assertEquals("not_implemented", got["a2"]!!.error())
        until(what = "falhas contadas") { d.auditFailed == 2 }
    }

    @Test
    fun aStuckAuditNeverBlocksTheInvocationsAndOverflowIsDropped() {
        val stuck = latch()
        val d = dispatcher(audit = { stuck.await(30, TimeUnit.SECONDS) }, auditQueue = 2)
        val r = Replies()
        val t0 = System.nanoTime()
        for (i in 0 until 20) d.onInvoke(invoke("s-$i", LinkTestCommands.PING), r.sink)
        r.await(20, 3_000)
        assertTrue("20 invocações sem esperar a auditoria", (System.nanoTime() - t0) / 1_000_000 < 3_000)
        until(what = "linhas descartadas (1 escrevendo + 2 na fila)") { d.auditDropped == 17 }
        stuck.countDown()
    }

    @Test
    fun aFailingDiskUnderTheAuditLogDoesNotAffectTheCommand() {
        val dir = Files.createTempDirectory("audit-fail").toFile()
        try {
            val notADir = File(dir, "file").apply { writeText("x") }
            val log = InvokeAuditLog(File(notADir, "gadgetlink/link-invoke-audit.log")) // o "diretório" é um arquivo: o append falha
            val d = dispatcher(audit = { log.append(it) })
            val r = Replies()
            d.onInvoke(invoke("df", LinkTestCommands.ECHO_VERIFY, obj("data" to "abc")), r.sink)
            d.onInvoke(invoke("df2", LinkTestCommands.ECHO_VERIFY, obj("data" to "abc", "expected_sha256" to LinkTestCommands.sha256Hex("abc".toByteArray()))), r.sink)
            val got = r.await(2).associateBy { it["id"]!!.jsonPrimitive.content }
            assertEquals("received", got["df"]!!["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals("match", got["df2"]!!["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            until(what = "as duas escritas falharam e foram contadas") { d.auditFailed == 2 }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ==== encerramento do serviço durante operações em andamento ====================================
    @Test
    fun closingTheDispatcherEndsPendingInvocationsWithUnavailableWithoutHanging() {
        val release = latch()
        val d = dispatcher(
            // ignora interrupt de propósito: o fechamento não pode depender do handler colaborar
            CommandSpec("test.block", "stubborn", timeoutMs = 10_000, writes = false, implemented = true) {
                blockStarted.incrementAndGet()
                while (release.count > 0) {
                    try {
                        release.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
                CommandResult.Ok(JsonPrimitive("late"))
            },
        )
        val r = Replies()
        fillSlots(d, r.sink)
        d.onInvoke(invoke("q-a", "test.block"), r.sink)
        d.onInvoke(invoke("q-b", "test.block"), r.sink)
        assertEquals(2, d.queued)
        val t0 = System.nanoTime()
        d.close()
        assertTrue("close não espera handler nenhum", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        val got = r.await(6, 1_000)
        assertTrue(got.toString(), got.all { it.error() == "unavailable" && it.detail() == "dispatcher closed" })
        val after = Replies()
        d.onInvoke(invoke("late-1", LinkTestCommands.PING), after.sink)
        assertEquals("unavailable", after.single(1_000).error())
        release.countDown()
        Thread.sleep(50)
        assertEquals("o resultado atrasado nunca sai depois do fechamento", 6, r.all.size)
    }

    // ==== P2-6: o despachante atende exatamente o catálogo anunciado ================================
    @Test
    fun theDispatcherServesExactlyTheCatalogOfTheCurrentRegister() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("st-1", "phone.tap", obj("request_id" to "12345678")), r.sink)
        assertEquals("unknown command", r.await(1)[0].detail()) // não anunciado: desconhecido
        val measuring = CommandCatalog.museManus(includeStubs = true)
        d.catalog = measuring // nova conexão com a medição ligada
        d.onInvoke(invoke("st-2", "phone.tap", obj("request_id" to "12345678")), r.sink)
        val stub = r.await(2)[1]
        assertEquals("not_implemented", stub.error())
        assertNull("o stub anunciado responde como stub, sem detail", stub["detail"])
        d.catalog = CommandCatalog.museManus() // e desligada de novo
        d.onInvoke(invoke("st-3", "phone.tap", obj("request_id" to "12345678")), r.sink)
        assertEquals("unknown command", r.await(3)[2].detail())
    }

    @Test
    fun theStubCountComesFromWhatTheRegisterActuallyCarries() {
        val measuring = CommandCatalog.museManus(includeStubs = true)
        val wire = measuring.commandsV2()
        val markedOnTheWire = wire.values.count { it.jsonObject["description"]!!.jsonPrimitive.content.startsWith(CommandSpec.STUB_PREFIX) }
        assertEquals(markedOnTheWire, measuring.announcedStubs)
        assertEquals(PhoneCatalog.stubs().size, measuring.announcedStubs)
        assertEquals(wire.size, measuring.announced.size)
        val normal = CommandCatalog.museManus()
        assertEquals(0, normal.announcedStubs)
        assertEquals(normal.commandsV2().size, normal.announced.size)
        assertFalse(normal.commandsV2().keys.any { it.startsWith("phone.") })
    }
}
