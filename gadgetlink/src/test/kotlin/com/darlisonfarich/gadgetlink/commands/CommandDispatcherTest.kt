package com.darlisonfarich.gadgetlink.commands

import com.darlisonfarich.gadgetlink.noise.LinkWire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

private fun invoke(id: Any?, command: Any?, params: JsonObject? = null): JsonObject {
    val m = LinkedHashMap<String, JsonElement>()
    m["method"] = JsonPrimitive("link.invoke")
    if (id != null) m["id"] = if (id is Number) JsonPrimitive(id) else JsonPrimitive(id.toString())
    when (command) {
        null -> Unit
        is JsonElement -> m["command"] = command
        else -> m["command"] = JsonPrimitive(command.toString())
    }
    if (params != null) m["params"] = params
    return JsonObject(m)
}

/** Coleta os link.result que o despachante devolve e espera por eles. */
private class Replies {
    val all: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())
    val sink: (JsonObject) -> Boolean = { all += it; true }

    fun await(n: Int, ms: Long = 5_000): List<JsonObject> {
        val until = System.currentTimeMillis() + ms
        while (all.size < n && System.currentTimeMillis() < until) Thread.sleep(5)
        assertTrue("esperava $n resultados, vieram ${all.size}", all.size >= n)
        return synchronized(all) { all.toList() }
    }

    fun single(ms: Long = 5_000): JsonObject = await(1, ms).single()
}

private fun JsonObject.error(): String? = (this["error"] as? JsonPrimitive)?.content

class CommandDispatcherTest {
    private val opened = ArrayList<CommandDispatcher>()

    @After fun close() = opened.forEach { it.close() }

    private fun dispatcher(
        vararg extra: CommandSpec,
        includeStubs: Boolean = false,
        gate: LocalGate = LocalGate.OPEN,
        audit: (InvokeAuditEntry) -> Unit = {},
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
        maxResultBytes: Int = CommandDispatcher.MAX_RESULT_BYTES,
    ): CommandDispatcher {
        val catalog = CommandCatalog(LinkTestCommands.all { 1_700_000_000_000 } + PhoneCatalog.stubs() + extra, includeStubs)
        return CommandDispatcher(catalog, gate, audit, nowMs = nowMs, maxResultBytes = maxResultBytes).also { opened += it }
    }

    private fun counting(name: String, counter: AtomicInteger, timeoutMs: Long = 5_000, body: () -> CommandResult = { CommandResult.Ok(JsonPrimitive("done")) }) =
        CommandSpec(name, "test command", timeoutMs = timeoutMs, writes = true, implemented = true) {
            counter.incrementAndGet()
            body()
        }

    // ---- deduplicação por id ---------------------------------------------------------------------
    @Test
    fun theSameIdIsAnsweredFromTheCacheWithoutRunningAgain() {
        val runs = AtomicInteger()
        val audit = Collections.synchronizedList(ArrayList<InvokeAuditEntry>())
        val d = dispatcher(counting("test.write", runs), audit = { audit += it })
        val first = Replies()
        d.onInvoke(invoke("inv-1", "test.write"), first.sink)
        val original = first.single()
        val again = Replies()
        d.onInvoke(invoke("inv-1", "test.write"), again.sink) // reenvio depois de uma reconexão
        assertEquals(original, again.single())
        assertEquals(1, runs.get())
        assertEquals(obj("method" to "link.result", "id" to "inv-1", "ok" to true, "payload" to "done"), original)
        // A auditoria agora é escrita no executor próprio (P2-9): espera as duas linhas chegarem.
        val until = System.currentTimeMillis() + 2_000
        while (audit.size < 2 && System.currentTimeMillis() < until) Thread.sleep(2)
        val lines = synchronized(audit) { audit.toList() }
        assertEquals(listOf(false, true), lines.map { it.deduplicated })
        assertTrue(lines.all { it.id == "inv-1" && it.command == "test.write" && it.outcome == "ok" })
    }

    @Test
    fun aResendWhileTheOriginalStillRunsWaitsForItInsteadOfRunningTwice() {
        val runs = AtomicInteger()
        val gate = CountDownLatch(1)
        val d = dispatcher(
            counting("test.slow", runs) {
                gate.await(5, TimeUnit.SECONDS)
                CommandResult.Ok(JsonPrimitive("once"))
            },
        )
        val a = Replies()
        val b = Replies()
        d.onInvoke(invoke("dup", "test.slow"), a.sink)
        Thread.sleep(50)
        d.onInvoke(invoke("dup", "test.slow"), b.sink)
        assertTrue(b.all.isEmpty())
        gate.countDown()
        assertEquals(a.single(), b.single())
        assertEquals(1, runs.get())
    }

    @Test
    fun differentIdsRunSeparatelyAndNumericIdsDoNotCollideWithStrings() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.write", runs))
        val r = Replies()
        d.onInvoke(invoke("7", "test.write"), r.sink)
        d.onInvoke(invoke(7, "test.write"), r.sink)
        d.onInvoke(invoke("8", "test.write"), r.sink)
        val got = r.await(3)
        assertEquals(3, runs.get())
        assertEquals(setOf(JsonPrimitive("7"), JsonPrimitive(7), JsonPrimitive("8")), got.map { it["id"] }.toSet())
    }

    @Test
    fun theCacheKeepsOnlyTheLast128Ids() {
        val runs = AtomicInteger()
        val d = dispatcher(counting("test.write", runs))
        val r = Replies()
        // Um por vez: com a fila limitada (P2-7), 129 de uma vez esbarrariam em "queue full" — que não é o
        // que este teste mede.
        for (i in 0 until 129) {
            d.onInvoke(invoke("id-$i", "test.write"), r.sink)
            r.await(i + 1)
        }
        assertEquals(129, runs.get())
        d.onInvoke(invoke("id-128", "test.write"), r.sink) // ainda no cache
        r.await(130)
        assertEquals(129, runs.get())
        d.onInvoke(invoke("id-0", "test.write"), r.sink) // o mais velho saiu: roda de novo
        r.await(131)
        assertEquals(130, runs.get())
    }

    @Test
    fun theCacheForgetsAnIdAfterTenMinutes() {
        val runs = AtomicInteger()
        var now = 1_000_000L
        val d = dispatcher(counting("test.write", runs), nowMs = { now })
        val r = Replies()
        d.onInvoke(invoke("old", "test.write"), r.sink)
        r.await(1)
        now += CommandDispatcher.CACHE_TTL_MS - 1
        d.onInvoke(invoke("old", "test.write"), r.sink)
        r.await(2)
        assertEquals(1, runs.get())
        now += 1
        d.onInvoke(invoke("old", "test.write"), r.sink)
        r.await(3)
        assertEquals(2, runs.get())
    }

    // ---- taxonomia de erros ----------------------------------------------------------------------
    @Test
    fun unknownCommandsAndStubsAnswerNotImplementedAndNothingRuns() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("u", "system.run", obj("cmd" to "ls")), r.sink)
        d.onInvoke(invoke("t", "phone.tap", obj("ref" to "e1", "request_id" to "12345678")), r.sink) // stub fora do registro
        val got = r.await(2).associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("not_implemented", got["u"]!!.error())
        assertEquals("not_implemented", got["t"]!!.error())
        assertEquals(JsonPrimitive(false), got["u"]!!["ok"])

        val withStubs = dispatcher(includeStubs = true)
        val s = Replies()
        withStubs.onInvoke(invoke("s", "phone.tap", obj("ref" to "e1", "request_id" to "12345678")), s.sink)
        assertEquals(obj("method" to "link.result", "id" to "s", "ok" to false, "error" to "not_implemented"), s.single())
    }

    @Test
    fun everyStubOnlyEverAnswersNotImplemented() {
        for (stub in PhoneCatalog.stubs()) {
            val result = stub.handler.run(JsonObject(emptyMap()))
            assertTrue(stub.name, result is CommandResult.Err && result.code == ErrorCode.NOT_IMPLEMENTED && result.detail == null)
        }
    }

    @Test
    fun theLocalGateDeniesBeforeAnythingElseWithLocalGateDenied() {
        val runs = AtomicInteger()
        var stopped = true
        val d = dispatcher(counting("test.write", runs), includeStubs = true, gate = { if (stopped) "stopped" else null })
        val r = Replies()
        d.onInvoke(invoke("a", LinkTestCommands.PING), r.sink)
        d.onInvoke(invoke("b", "test.write"), r.sink)
        d.onInvoke(invoke("c", "no.such_command"), r.sink)
        d.onInvoke(invoke("d", "phone.tap"), r.sink)
        val got = r.await(4)
        assertTrue(got.all { it.error() == "local_gate_denied" && it["detail"] == JsonPrimitive("stopped") })
        assertEquals(0, runs.get())
        stopped = false
        d.onInvoke(invoke("e", "test.write"), r.sink)
        assertEquals(JsonPrimitive(true), r.await(5).last()["ok"])
        assertEquals(1, runs.get())
    }

    @Test
    fun badParametersAreAnExecutorErrorWithTheShortReason() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("p1", LinkTestCommands.ECHO_VERIFY, obj()), r.sink)
        d.onInvoke(invoke("p2", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to "10")), r.sink)
        d.onInvoke(invoke("p3", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to true)), r.sink)
        d.onInvoke(invoke("p4", JsonPrimitive(5)), r.sink)
        val got = r.await(4).associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("executor_error", got["p1"]!!.error())
        assertEquals(JsonPrimitive("data is required"), got["p1"]!!["detail"])
        assertEquals(JsonPrimitive("bytes must be an integer"), got["p2"]!!["detail"])
        assertEquals(JsonPrimitive("bytes must be an integer"), got["p3"]!!["detail"])
        assertEquals(JsonPrimitive("command must be a string"), got["p4"]!!["detail"])
    }

    @Test
    fun aHandlerExceptionIsAnExecutorErrorThatNeverEchoesItsMessage() {
        val d = dispatcher(
            CommandSpec("test.boom", "throws", timeoutMs = 5_000, writes = false, implemented = true) {
                throw IllegalStateException("SEGREDO-123")
            },
            CommandSpec("test.stale", "stale", timeoutMs = 5_000, writes = false, implemented = true) {
                throw CommandException(ErrorCode.STALE_SNAPSHOT)
            },
            CommandSpec("test.busy", "in use", timeoutMs = 5_000, writes = true, implemented = true) {
                CommandResult.Err(ErrorCode.APP_IN_USE_BY_USER, "package in use")
            },
        )
        val r = Replies()
        d.onInvoke(invoke("x", "test.boom"), r.sink)
        d.onInvoke(invoke("y", "test.stale"), r.sink)
        d.onInvoke(invoke("z", "test.busy"), r.sink)
        val got = r.await(3).associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(obj("method" to "link.result", "id" to "x", "ok" to false, "error" to "executor_error", "detail" to "IllegalStateException"), got["x"])
        assertFalse(got["x"].toString().contains("SEGREDO"))
        assertEquals("stale_snapshot", got["y"]!!.error())
        assertEquals("app_in_use_by_user", got["z"]!!.error())
    }

    @Test
    fun onlyTaxonomyCodesEverLeaveTheDispatcher() {
        val codes = ErrorCode.values().map { it.wire }.toSet()
        assertEquals(
            setOf("not_implemented", "timeout", "too_large", "local_gate_denied", "stale_snapshot", "app_in_use_by_user", "executor_error", "unavailable"),
            codes,
        )
        val d = dispatcher(includeStubs = true)
        val r = Replies()
        val messages = listOf(
            invoke("1", "nope.nope"), invoke("2", JsonPrimitive(1)), invoke("3", LinkTestCommands.ECHO_VERIFY, obj("data" to 1)),
            invoke("4", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to -1)), invoke("5", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to 300 * 1024)),
            invoke("6", "phone.get_tree"), invoke("7", LinkTestCommands.ECHO_VERIFY, obj("data" to "%%%", "encoding" to "base64")),
        )
        messages.forEach { d.onInvoke(it, r.sink) }
        for (m in r.await(messages.size)) assertTrue(m.toString(), m.error() in codes)
    }

    @Test
    fun anInvokeWithoutAUsableIdGetsNoReply() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke(null, LinkTestCommands.PING), r.sink)
        d.onInvoke(invoke("", LinkTestCommands.PING), r.sink)
        d.onInvoke(invoke(0, LinkTestCommands.PING), r.sink)
        Thread.sleep(100)
        assertTrue(r.all.isEmpty())
    }

    // ---- timeout --------------------------------------------------------------------------------
    @Test
    fun aCommandPastItsTimeoutAnswersTimeoutAndIsInterrupted() {
        val interrupted = CountDownLatch(1)
        val d = dispatcher(
            CommandSpec("test.hang", "hangs", timeoutMs = 150, writes = false, implemented = true) {
                try {
                    Thread.sleep(10_000)
                    CommandResult.Ok(JsonPrimitive("late"))
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
            },
        )
        val r = Replies()
        val started = System.nanoTime()
        d.onInvoke(invoke("h", "test.hang"), r.sink)
        val reply = r.single()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("timeout", reply.error())
        assertTrue("timeout em $ms ms", ms in 100..2_000)
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals("a resposta atrasada nunca sai", 1, r.all.size)
        assertEquals(0, d.running)
    }

    @Test
    fun theCatalogAnnouncesTheTimeoutPlusTheGraceLikeTheSdk() {
        val wire = CommandCatalog.museManus().commandsV2()
        assertEquals(5_000L + CommandSpec.TIMEOUT_GRACE_MS, wire[LinkTestCommands.PING]!!.jsonObject["timeout_ms"]!!.jsonPrimitive.long)
    }

    // ---- concorrência ---------------------------------------------------------------------------
    @Test
    fun atMostFourInvocationsRunAtOnceAndTheRestWaitForASlot() {
        val now = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        val d = dispatcher(
            CommandSpec("test.block", "blocks", timeoutMs = 10_000, writes = false, implemented = true) {
                val c = now.incrementAndGet()
                peak.accumulateAndGet(c) { a, b -> maxOf(a, b) }
                try {
                    release.await(10, TimeUnit.SECONDS)
                } finally {
                    now.decrementAndGet()
                }
                CommandResult.Ok(JsonPrimitive("ok"))
            },
        )
        val r = Replies()
        for (i in 0 until 10) d.onInvoke(invoke("c$i", "test.block"), r.sink)
        val until = System.currentTimeMillis() + 2_000
        while (now.get() < 4 && System.currentTimeMillis() < until) Thread.sleep(5)
        Thread.sleep(150)
        assertEquals(4, now.get())
        assertEquals(4, d.running)
        assertTrue(r.all.isEmpty())
        release.countDown()
        val got = r.await(10)
        assertEquals(4, peak.get())
        assertTrue(got.all { it["ok"] == JsonPrimitive(true) })
        assertEquals((0 until 10).map { "c$it" }.toSet(), got.map { it["id"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun aQueuedInvocationThatRunsOutOfTimeWhileWaitingNeverRuns() {
        val release = CountDownLatch(1)
        val ranQuick = AtomicInteger()
        val d = dispatcher(
            CommandSpec("test.block", "blocks", timeoutMs = 10_000, writes = false, implemented = true) {
                release.await(10, TimeUnit.SECONDS)
                CommandResult.Ok(JsonPrimitive("ok"))
            },
            counting("test.quick", ranQuick, timeoutMs = 100),
        )
        val r = Replies()
        for (i in 0 until 4) d.onInvoke(invoke("b$i", "test.block"), r.sink)
        Thread.sleep(50)
        d.onInvoke(invoke("q", "test.quick"), r.sink)
        assertEquals("timeout", r.single()["error"]!!.jsonPrimitive.content)
        release.countDown()
        r.await(5)
        Thread.sleep(50)
        assertEquals(0, ranQuick.get())
        assertEquals(0, d.running)
    }

    // ---- orçamento de tamanho -------------------------------------------------------------------
    @Test
    fun aResultOverTheBudgetIsTooLargeAndNeverTruncated() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("big", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to 240 * 1024)), r.sink)
        val reply = r.single()
        assertEquals("too_large", reply.error())
        assertNull("nada do payload sai", reply["payload"])
        assertTrue(LinkWire.encodeMessage(reply).size < 1024)
    }

    @Test
    fun theLargestProbeThatFitsGoesOutWholeAndOneByteMoreIsTooLarge() {
        val d = dispatcher()
        fun probe(id: String, n: Int): JsonObject {
            val r = Replies()
            d.onInvoke(invoke(id, LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to n)), r.sink)
            return r.single()
        }
        // No fio: envelope fixo (com bytes=0 e data="") + n caracteres + (dígitos de n − 1), porque o
        // tamanho aparece uma vez, em "bytes", e o hash não muda de comprimento.
        // ids do mesmo tamanho ("p0", "p1", "p2"): cada um único, senão o cache de deduplicação responderia.
        val overhead = LinkWire.encodeMessage(probe("p0", 0)).size
        fun wire(n: Int) = overhead + n + (n.toString().length - 1)
        var largest = CommandDispatcher.MAX_RESULT_BYTES - overhead
        while (wire(largest) > CommandDispatcher.MAX_RESULT_BYTES) largest--
        val fits = probe("p1", largest)
        assertEquals(JsonPrimitive(true), fits["ok"])
        assertEquals(wire(largest), LinkWire.encodeMessage(fits).size)
        val data = fits["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertEquals(largest, data.length)
        assertEquals(LinkTestCommands.sha256Hex(data.toByteArray()), fits["payload"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals("too_large", probe("p2", largest + 1).error())
    }

    @Test
    fun theSizeGuardCoversAnyHandlerPayload() {
        val d = dispatcher(
            CommandSpec("test.huge", "huge", timeoutMs = 5_000, writes = false, implemented = true) {
                CommandResult.Ok(JsonPrimitive("é".repeat(50_000))) // vira é: 6 bytes cada no fio
            },
            maxResultBytes = CommandDispatcher.MAX_RESULT_BYTES,
        )
        val r = Replies()
        d.onInvoke(invoke("h", "test.huge"), r.sink)
        assertEquals("too_large", r.single().error())
    }

    // ---- comandos linktest ----------------------------------------------------------------------
    @Test
    fun pingAnswersPongWithThePhonesClock() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("p", LinkTestCommands.PING), r.sink)
        val payload = r.single()["payload"]!!.jsonObject
        assertEquals("pong", payload["reply"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000, payload["device_time_ms"]!!.jsonPrimitive.long)
        assertEquals("2023-11-14T22:13:20Z", payload["device_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun echoVerifyReturnsOnlySizeAndHashAndNeverThePayload() {
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("e1", LinkTestCommands.ECHO_VERIFY, obj("data" to "abc")), r.sink)
        val p1 = r.single()["payload"]!!.jsonObject
        assertEquals(obj("bytes" to 3, "sha256" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "status" to "received"), p1)

        // ~100 KB binários em base64 (o passo 4 da Etapa 1), com o hash que o remetente calculou.
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val expected = "cd2df694e424bc7968cc37f47751019e5ca0cd1bdf2e479ea537c3a1c32ee1aa"
        val b64 = Base64.getEncoder().encodeToString(data)
        val r2 = Replies()
        d.onInvoke(invoke("e2", LinkTestCommands.ECHO_VERIFY, obj("data" to b64, "encoding" to "base64", "expected_sha256" to expected.uppercase())), r2.sink)
        val reply = r2.single()
        assertEquals(obj("bytes" to 100_000, "sha256" to expected, "status" to "match"), reply["payload"])
        assertFalse(reply.toString().contains(b64.take(32)))

        val r3 = Replies()
        d.onInvoke(invoke("e3", LinkTestCommands.ECHO_VERIFY, obj("data" to "abd", "expected_sha256" to expected)), r3.sink)
        assertEquals("mismatch", r3.single()["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content)

        // UTF-8 de verdade: o hash é dos bytes UTF-8.
        val r4 = Replies()
        d.onInvoke(invoke("e4", LinkTestCommands.ECHO_VERIFY, obj("data" to "ação")), r4.sink)
        val p4 = r4.single()["payload"]!!.jsonObject
        assertEquals(6, p4["bytes"]!!.jsonPrimitive.int)
        assertEquals(LinkTestCommands.sha256Hex("ação".toByteArray(Charsets.UTF_8)), p4["sha256"]!!.jsonPrimitive.content)
    }

    @Test
    fun theProbePayloadMatchesTheIndependentPythonVectors() {
        // python: out += sha256(f"{seed}:{c}").hexdigest() até o tamanho; sha256 do resultado.
        val vectors = mapOf(
            0 to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            1 to "5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9",
            64 to "ddf8c5814073fedc95d8438698e7bf92bb2c350f43b16ce9f85974813d1ad10e",
            65 to "814a261ac75fff036e6b1a00e0a8d8f263116e6452152b91ad86e65262fa78ea",
            1000 to "a28ee669e0c42b81b9f7ad63b7938a475c7af29466012cfb333c127e64ced4db",
            100 * 1024 to "7ce2b9c8da4358e58f0e954d8a8673cee2ede4543ab6bb9df7a801777cf7eaaa",
        )
        for ((n, sha) in vectors) {
            val p = LinkTestCommands.probePayload(LinkTestCommands.DEFAULT_SEED, n)
            assertEquals(n, p.length)
            assertEquals("n=$n", sha, LinkTestCommands.sha256Hex(p.toByteArray(Charsets.US_ASCII)))
        }
        val d = dispatcher()
        val r = Replies()
        d.onInvoke(invoke("v", LinkTestCommands.RESPONSE_SIZE_PROBE, obj("bytes" to 100 * 1024)), r.sink)
        val payload = r.single()["payload"]!!.jsonObject
        assertEquals(vectors[100 * 1024], payload["sha256"]!!.jsonPrimitive.content)
        assertEquals(102400, payload["bytes"]!!.jsonPrimitive.int)
    }

    // ---- auditoria ------------------------------------------------------------------------------
    @Test
    fun theAuditRecordsEveryInvocationWithoutParametersOrPayload() {
        val audit = Collections.synchronizedList(ArrayList<InvokeAuditEntry>())
        val d = dispatcher(audit = { audit += it })
        val r = Replies()
        d.onInvoke(invoke("a\nforged|line", LinkTestCommands.ECHO_VERIFY, obj("data" to "SEGREDO-DO-PAYLOAD")), r.sink)
        d.onInvoke(invoke("b", "nope.nope"), r.sink)
        r.await(2)
        // A auditoria agora é escrita no executor próprio (P2-9): espera as duas linhas chegarem.
        val until = System.currentTimeMillis() + 2_000
        while (audit.size < 2 && System.currentTimeMillis() < until) Thread.sleep(2)
        val lines = synchronized(audit) { audit.map { it.line() } }
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("SEGREDO") || it.contains("\n") })
        // o desconhecido responde na hora; o echo, da thread de trabalho: a ordem entre os dois não é fixa
        assertTrue(lines.toString(), lines.any { it.contains("|a?forged?line|linktest.echo_verify|ok|") })
        assertTrue(lines.toString(), lines.any { it.contains("|b|nope.nope|not_implemented|") })
    }

    // ---- escopo revogado depois de executar ------------------------------------------------------
    @Test
    fun aWriteThatRanIsNeverReportedAsUnavailableWhenItsScopeEndsMeanwhile() {
        var scope: String? = "registration-1"
        val runs = AtomicInteger()
        val write = CommandSpec("test.tap", "test write", timeoutMs = 5_000, writes = true, implemented = true,
            replayScope = { scope }) {
            runs.incrementAndGet()
            scope = null // the registration ended while the action ran
            CommandResult.Ok(JsonPrimitive("tapped"))
        }
        val r = Replies()
        dispatcher(write).onInvoke(invoke("w-1", "test.tap"), r.sink)
        val reply = r.single()
        assertEquals(1, runs.get())
        assertEquals("executor_error", reply.error())
        assertEquals(CommandDispatcher.RECONCILE_DETAIL, (reply["detail"] as JsonPrimitive).content)
    }

    @Test
    fun aReadWhoseScopeEndsMeanwhileStillWithholdsItsContent() {
        var scope: String? = "session-1"
        val read = CommandSpec("test.read", "test read", timeoutMs = 5_000, writes = false, implemented = true,
            replayScope = { scope }) {
            scope = "session-2"
            CommandResult.Ok(JsonPrimitive("screen content"))
        }
        val r = Replies()
        dispatcher(read).onInvoke(invoke("r-1", "test.read"), r.sink)
        val reply = r.single()
        assertEquals("unavailable", reply.error())
        assertFalse(reply.toString().contains("screen content"))
    }
}
