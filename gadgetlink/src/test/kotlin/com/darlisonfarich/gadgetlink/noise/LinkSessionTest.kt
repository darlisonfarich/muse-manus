package com.darlisonfarich.gadgetlink.noise

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Um "Muse" falso que fala o protocolo de verdade: responde o handshake, decifra o que a sessão manda e injeta respostas. */
private class FakeMuse {
    val responder = TestNoiseResponder("muse-hello".toByteArray())
    private var step = 0
    private lateinit var send: CipherState
    private lateinit var recv: CipherState
    private val frames = NoiseFrameDecoder()
    private val controlDecoder = MessageDecoder()

    /** O que a sessão mandou, já decifrado. */
    class Request(val streamId: Long, val service: ServiceType, val request: ApplicationRequest)
    val requests = ArrayList<Request>()
    val resets = ArrayList<Reset>()
    val controlMessages = ArrayList<JsonObject>()

    /** Respostas do Muse à espera de serem entregues à sessão. */
    val outbox = ArrayDeque<ByteArray>()
    var failSends = false
    var closed = false
    val sentByClient = ArrayList<ByteArray>()

    val socket = object : LinkSocket {
        override fun sendBinary(data: ByteArray): Boolean {
            if (failSends) return false
            sentByClient += data
            receive(data)
            return true
        }

        override fun close() {
            closed = true
        }
    }

    private fun receive(data: ByteArray) {
        when (step) {
            0 -> { outbox += responder.readMessage1AndWriteMessage2(data); step = 1 }
            1 -> {
                responder.readMessage3(data)
                val (s, r) = responder.split()
                send = s
                recv = r
                step = 2
            }
            else -> {
                val message = frames.decode(recv.decryptWithAd(ByteArray(0), data)) ?: return
                val service = Envelope.decodeServiceRequest(message)
                val frame = Envelope.decodeServiceFrame(service.payload)
                when (val v = frame.value) {
                    is ApplicationRequest -> {
                        requests += Request(frame.streamId, service.service, v)
                        controlDecoder.feed(v.body).let { controlMessages += it }
                    }
                    is BodyChunk -> controlMessages += controlDecoder.feed(v.data)
                    is Reset -> resets += v
                    else -> Unit
                }
            }
        }
    }

    // ---- o que o Muse manda ----
    private var chunk = 5000L

    private fun emit(frame: ServiceFrame) {
        val envelope = Envelope.encodeServiceResponse(ServiceResponse(Envelope.encodeServiceFrame(frame)))
        NoiseFraming.encode(envelope, chunk++).forEach { outbox += send.encryptWithAd(ByteArray(0), it) }
    }

    fun response(status: Int, body: ByteArray = ByteArray(0), end: Boolean = false, stream: Long = 1) =
        emit(ServiceFrame(stream, ApplicationResponse(status, emptyList(), body, end)))

    fun control(message: JsonObject, stream: Long = 1, end: Boolean = false) =
        emit(ServiceFrame(stream, BodyChunk(LinkWire.encodeMessage(message), end)))

    fun controlRaw(bytes: ByteArray, stream: Long = 1) = emit(ServiceFrame(stream, BodyChunk(bytes, false)))

    fun reset(stream: Long = 1) = emit(ServiceFrame(stream, Reset(ResetCode.CANCELLED, "bye")))
}

private class Recorder : LinkListener {
    val outcomes = ArrayList<LinkOutcome>()
    var registered = 0
    val logs = ArrayList<String>()
    override fun onRegistered() { registered++ }
    override fun onOutcome(outcome: LinkOutcome) { outcomes += outcome }
    override fun log(line: String) { logs += line }
}

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) ->
    k to when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("unsupported")
    }
})

class LinkSessionTest {
    private val registerId = "11111111-2222-3333-4444-555555555555"
    private val device = DeviceDescription("homelink-abc123", "Celular", "0.1.7")

    private class Rig(device: DeviceDescription, private val registerId: String, invokes: InvokeHandler? = null) {
        val muse = FakeMuse()
        val rec = Recorder()
        var nextChunk = 100L
        val session = LinkSession(device, muse.socket, rec, newRequestId = { registerId }, chunkIds = { nextChunk++ }, invokes = invokes)

        /** Entrega à sessão tudo o que o Muse tem na caixa de saída (e o que isso gerar). */
        fun pump() {
            while (muse.outbox.isNotEmpty()) session.onBinary(muse.outbox.removeFirst())
        }

        /** Handshake + registro enviado, ainda sem a resposta do Muse. */
        fun connect() {
            session.onOpen()
            pump()
        }

        fun ack() {
            muse.response(200)
            muse.control(obj("id" to registerId, "ok" to true))
            pump()
        }
    }

    private fun rig() = Rig(device, registerId)

    /** Espera a sessão mandar [n] mensagens de controle (as respostas do despachante saem de outra thread). */
    private fun Rig.awaitControl(n: Int): List<JsonObject> {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            synchronized(session) { if (muse.controlMessages.size >= n) return muse.controlMessages.toList() }
            Thread.sleep(5)
        }
        throw AssertionError("a sessão não mandou $n mensagens de controle")
    }

    @Test
    fun eachNoiseSessionHasItsOwnInvokeResponseIdNamespace() {
        val seen = ArrayList<String>()
        val observer = object : InvokeHandler {
            override fun onInvoke(message: JsonObject, reply: (JsonObject) -> Boolean) {
                throw AssertionError("registered LinkSession must supply the connection identity")
            }

            override fun onInvokeFromSession(sessionId: String, message: JsonObject, reply: (JsonObject) -> Boolean) {
                seen += sessionId
            }
        }
        val first = Rig(device, registerId, observer)
        first.connect()
        first.ack()
        first.muse.control(obj("method" to "link.invoke", "id" to "1", "command" to "linktest.ping"))
        first.muse.control(obj("method" to "link.invoke", "id" to "2", "command" to "linktest.ping"))
        first.pump()

        val second = Rig(device, registerId, observer)
        second.connect()
        second.ack()
        second.muse.control(obj("method" to "link.invoke", "id" to "1", "command" to "linktest.ping"))
        second.pump()

        assertEquals(3, seen.size)
        assertEquals("calls on one connection share a namespace", seen[0], seen[1])
        assertNotEquals("new connection gets a new response-id namespace", seen[0], seen[2])
    }

    // ---- caminho feliz -------------------------------------------------------------------------
    @Test
    fun theSessionHandshakesOpensTheControlStreamAndRegistersAsAHomehubWithNoCommands() {
        val r = rig()
        r.connect()
        val request = r.muse.requests.single()
        assertEquals(1L, request.streamId)
        assertEquals(ServiceType.DAEMON, request.service)
        assertEquals("POST", request.request.verb)
        assertEquals("/link-control", request.request.path)
        assertFalse("o stream de controle fica aberto", request.request.endBody)
        val register = r.muse.controlMessages.single()
        assertEquals("req", register["type"]!!.jsonPrimitive.content)
        assertEquals(registerId, register["id"]!!.jsonPrimitive.content)
        assertEquals("link.register", register["method"]!!.jsonPrimitive.content)
        val params = register["params"]!!.jsonObject
        assertEquals("homelink-abc123", params["node_id"]!!.jsonPrimitive.content)
        assertEquals("homehub", params["device_family"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), params["commands_v2"])
        assertEquals(0, r.rec.registered)
        assertTrue(r.rec.outcomes.isEmpty())
    }

    @Test
    fun theMusesAcknowledgementMarksTheSessionRegisteredExactlyOnce() {
        val r = rig()
        r.connect()
        r.ack()
        assertEquals(1, r.rec.registered)
        assertTrue(r.session.isRegistered)
        assertTrue(r.rec.logs.contains("registered with the Muse"))
        r.muse.control(obj("id" to registerId, "ok" to true))
        r.pump()
        assertEquals("um segundo ack não registra de novo", 1, r.rec.registered)
        assertTrue(r.rec.outcomes.isEmpty())
    }

    @Test
    fun anAckWithAnEmptyOrZeroErrorIsStillAnAckLikeInPython() {
        for (falsy in listOf<Any?>("", 0, null, false)) {
            val r = rig()
            r.connect()
            r.muse.control(obj("id" to registerId, "error" to falsy))
            r.pump()
            assertEquals("error=$falsy", 1, r.rec.registered)
            assertTrue(r.rec.outcomes.isEmpty())
        }
    }

    @Test
    fun aRejectedRegistrationEndsForbidden() {
        for (reject in listOf(obj("id" to registerId, "ok" to false), obj("id" to registerId, "error" to "nope"), obj("id" to registerId, "error" to true))) {
            val r = rig()
            r.connect()
            r.muse.control(reject)
            r.pump()
            assertEquals(listOf(LinkOutcome.FORBIDDEN), r.rec.outcomes)
            assertEquals(0, r.rec.registered)
            assertTrue(r.muse.closed)
        }
    }

    @Test
    fun aMessageWithAnotherIdOrWithAMethodIsNotTheAck() {
        val r = rig()
        r.connect()
        r.muse.control(obj("id" to "other", "ok" to true))
        r.muse.control(obj("id" to registerId, "method" to "something", "ok" to true))
        r.muse.control(obj("id" to 5, "ok" to true))
        r.pump()
        assertEquals(0, r.rec.registered)
    }

    // ---- eventos do Muse -----------------------------------------------------------------------
    @Test
    fun theMuseRemovingTheDeviceEndsUnpaired() {
        for (event in listOf("link.unpaired", "node.unpaired")) {
            val r = rig()
            r.connect()
            r.ack()
            r.muse.control(obj("event" to event))
            r.pump()
            assertEquals(event, listOf(LinkOutcome.UNPAIRED), r.rec.outcomes)
        }
        val other = rig()
        other.connect()
        other.muse.control(obj("event" to "something.else"))
        other.pump()
        assertTrue(other.rec.outcomes.isEmpty())
    }

    @Test
    fun everyInvokeIsRefusedAndNothingIsExecuted() {
        val r = rig()
        r.connect()
        r.ack()
        r.muse.control(obj("method" to "link.invoke", "id" to "inv-1", "command" to "system.run", "params" to obj("cmd" to "ls")))
        r.pump()
        val reply = r.muse.controlMessages.last()
        assertEquals(obj("method" to "link.result", "id" to "inv-1", "ok" to false, "error" to "unsupported command: system.run"), reply)
        assertTrue(r.rec.outcomes.isEmpty())
    }

    // ---- com o despachante de comandos (Gadget Link Etapa 0) ---------------------------------------
    @Test
    fun withTheDispatcherTheRegisterAnnouncesCommandsAndAnInvokeGetsItsResultById() {
        val catalog = com.darlisonfarich.gadgetlink.commands.CommandCatalog.museManus(clock = { 1_700_000_000_000 })
        val dispatcher = com.darlisonfarich.gadgetlink.commands.CommandDispatcher(catalog)
        try {
            val r = Rig(DeviceDescription("homelink-abc123", "Celular", "0.1.7", catalog.commandsV2()), registerId, dispatcher)
            r.connect()
            val commands = r.muse.controlMessages.single()["params"]!!.jsonObject["commands_v2"]!!.jsonObject
            assertEquals(listOf("linktest.ping", "linktest.echo_verify", "linktest.response_size_probe"), commands.keys.toList())
            r.ack()
            r.muse.control(obj("method" to "link.invoke", "id" to "inv-ping", "command" to "linktest.ping", "params" to obj()))
            r.muse.control(obj("method" to "link.invoke", "id" to "inv-echo", "command" to "linktest.echo_verify", "params" to obj("data" to "abc")))
            r.pump()
            val results = r.awaitControl(3).drop(1).associateBy { it["id"]!!.jsonPrimitive.content }
            assertEquals(
                obj("method" to "link.result", "id" to "inv-ping", "ok" to true,
                    "payload" to obj("reply" to "pong", "device_time_ms" to 1_700_000_000_000, "device_time" to "2023-11-14T22:13:20Z")),
                results["inv-ping"],
            )
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                results["inv-echo"]!!["payload"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)

            // O mesmo id de novo (reconexão do lado do Muse): o resultado guardado, nada reexecutado.
            r.muse.control(obj("method" to "link.invoke", "id" to "inv-ping", "command" to "linktest.ping"))
            r.pump()
            assertEquals(results["inv-ping"], r.awaitControl(4)[3])
            assertTrue(r.rec.outcomes.isEmpty())
        } finally {
            dispatcher.close()
        }
    }

    /**
     * Reconexão de ponta a ponta, pelo protocolo real (item 10 das correções do PR nº 37): a sessão A cai com
     * uma escrita em curso; na sessão B o reenvio idêntico recebe o resultado guardado sem reexecutar, e o
     * mesmo id com OUTRA operação (o Muse renumerando depois de reiniciar) executa a operação nova.
     */
    @Test
    fun theDedupSurvivesAReconnectButAnIdReusedForAnotherCommandRunsFresh() {
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val write = com.darlisonfarich.gadgetlink.commands.CommandSpec(
            "test.write", "write for the reconnect test", timeoutMs = 5_000, writes = true, implemented = true,
        ) {
            writes.incrementAndGet()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            com.darlisonfarich.gadgetlink.commands.CommandResult.Ok(JsonPrimitive("written"))
        }
        val catalog = com.darlisonfarich.gadgetlink.commands.CommandCatalog(
            com.darlisonfarich.gadgetlink.commands.LinkTestCommands.all { 1_700_000_000_000 } + write,
        )
        val dispatcher = com.darlisonfarich.gadgetlink.commands.CommandDispatcher(catalog)
        try {
            val a = Rig(DeviceDescription("homelink-abc123", "Celular", "0.1.7", catalog.commandsV2()), registerId, dispatcher)
            a.connect()
            a.ack()
            a.muse.control(obj("method" to "link.invoke", "id" to "inv-9", "command" to "test.write", "params" to obj("request_id" to "r-1")))
            a.pump()
            val until = System.currentTimeMillis() + 2_000
            while (writes.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(2)
            a.session.onClosed("network lost") // a sessão A cai com a escrita em curso
            release.countDown()
            Thread.sleep(100)
            assertEquals("só o link.register saiu por A", 1, a.muse.controlMessages.size)

            val b = Rig(DeviceDescription("homelink-abc123", "Celular", "0.1.7", catalog.commandsV2()), registerId, dispatcher)
            b.connect()
            b.ack()
            b.muse.control(obj("method" to "link.invoke", "id" to "inv-9", "command" to "test.write", "params" to obj("request_id" to "r-1")))
            b.pump()
            assertEquals(obj("method" to "link.result", "id" to "inv-9", "ok" to true, "payload" to "written"), b.awaitControl(2)[1])
            assertEquals("a escrita não repetiu na reconexão", 1, writes.get())

            b.muse.control(obj("method" to "link.invoke", "id" to "inv-9", "command" to "linktest.ping"))
            b.pump()
            val fresh = b.awaitControl(3)[2]
            assertEquals("pong", fresh["payload"]!!.jsonObject["reply"]!!.jsonPrimitive.content)
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun withoutTheDispatcherNothingChanges() {
        val r = rig()
        r.connect()
        assertEquals(JsonObject(emptyMap()), r.muse.controlMessages.single()["params"]!!.jsonObject["commands_v2"])
        r.ack()
        r.muse.control(obj("method" to "link.invoke", "id" to "i", "command" to "linktest.ping"))
        r.pump()
        assertEquals("unsupported command: linktest.ping", r.muse.controlMessages.last()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun invokeEdgeCasesFollowTheReference() {
        fun reply(message: JsonObject): JsonObject? {
            val r = rig()
            r.connect()
            r.ack()
            val before = r.muse.controlMessages.size
            r.muse.control(message)
            r.pump()
            return r.muse.controlMessages.drop(before).singleOrNull()
        }
        assertEquals("command must be a string", reply(obj("method" to "link.invoke", "id" to "a", "command" to 5))!!["error"]!!.jsonPrimitive.content)
        assertEquals("command must be a string", reply(obj("method" to "link.invoke", "id" to "a", "command" to JsonArray(listOf(JsonPrimitive(1)))))!!["error"]!!.jsonPrimitive.content)
        // command ausente/falso vira "" (o "or" do Python): fica "unsupported command: ".
        assertEquals("unsupported command: ", reply(obj("method" to "link.invoke", "id" to "a"))!!["error"]!!.jsonPrimitive.content)
        assertEquals("unsupported command: ", reply(obj("method" to "link.invoke", "id" to "a", "command" to 0))!!["error"]!!.jsonPrimitive.content)
        // sem id (ou id falso) não há a quem responder
        assertNull(reply(obj("method" to "link.invoke", "command" to "x")))
        assertNull(reply(obj("method" to "link.invoke", "id" to "", "command" to "x")))
        assertNull(reply(obj("method" to "link.invoke", "id" to 0, "command" to "x")))
        // id numérico é devolvido como veio
        assertEquals(JsonPrimitive(7), reply(obj("method" to "link.invoke", "id" to 7, "command" to "x"))!!["id"])
    }

    // ---- falhas e fim --------------------------------------------------------------------------
    @Test
    fun resetAndRefusedStatusesEndTheSession() {
        val reset = rig().also { it.connect(); it.muse.reset(); it.pump() }
        assertEquals(listOf(LinkOutcome.CLOSED), reset.rec.outcomes)
        for ((status, want) in listOf(403 to LinkOutcome.FORBIDDEN, 401 to LinkOutcome.CLOSED, 500 to LinkOutcome.CLOSED, 199 to LinkOutcome.CLOSED, 300 to LinkOutcome.CLOSED)) {
            val r = rig()
            r.connect()
            r.muse.response(status)
            r.pump()
            assertEquals("HTTP $status", listOf(want), r.rec.outcomes)
        }
    }

    @Test
    fun theStreamEndingEndsTheSessionAfterItsMessagesAreHandled() {
        val r = rig()
        r.connect()
        r.muse.control(obj("id" to registerId, "ok" to true), end = true)
        r.pump()
        assertEquals("a mensagem do mesmo pedaço ainda é tratada", 1, r.rec.registered)
        assertEquals(listOf(LinkOutcome.CLOSED), r.rec.outcomes)
    }

    @Test
    fun aTamperedHandshakeEndsClosedAndClosesTheSocket() {
        val r = rig()
        r.session.onOpen()
        val msg2 = r.muse.outbox.removeFirst()
        msg2[40] = (msg2[40].toInt() xor 1).toByte()
        r.session.onBinary(msg2)
        assertEquals(listOf(LinkOutcome.CLOSED), r.rec.outcomes)
        assertTrue(r.muse.closed)
        assertTrue(r.muse.requests.isEmpty())
    }

    @Test
    fun aFailedSendEndsTheSessionBecauseDeliveryIsAmbiguous() {
        val r = rig()
        r.muse.failSends = true
        r.session.onOpen()
        assertEquals(listOf(LinkOutcome.CLOSED), r.rec.outcomes)

        val late = rig()
        late.connect()
        late.muse.failSends = true
        late.muse.control(obj("method" to "link.invoke", "id" to "x", "command" to "c"))
        late.pump()
        assertEquals(listOf(LinkOutcome.CLOSED), late.rec.outcomes)
    }

    @Test
    fun theOutcomeIsReportedOnceAndLaterEventsAreIgnored() {
        val r = rig()
        r.connect()
        r.session.stop()
        assertEquals(listOf(LinkOutcome.STOPPED), r.rec.outcomes)
        r.session.onClosed("late")
        r.session.stop()
        r.muse.response(200)
        r.pump()
        assertEquals(listOf(LinkOutcome.STOPPED), r.rec.outcomes)
        assertFalse(r.session.sendControl(obj("a" to 1)))
    }

    @Test
    fun aSocketThatClosesOrIsRefusedAtTheUpgradeEndsWithTheRightOutcome() {
        val closed = rig().also { it.connect(); it.session.onClosed("eof") }
        assertEquals(listOf(LinkOutcome.CLOSED), closed.rec.outcomes)
        for ((status, want) in listOf(401 to LinkOutcome.AUTH_REJECTED, 403 to LinkOutcome.FORBIDDEN, 500 to LinkOutcome.CLOSED, 404 to LinkOutcome.CLOSED)) {
            val r = rig()
            r.session.onUpgradeRejected(status)
            assertEquals("HTTP $status", listOf(want), r.rec.outcomes)
        }
        assertEquals(LinkOutcome.AUTH_REJECTED, LinkSession.outcomeForUpgradeStatus(401))
        assertNull(LinkSession.outcomeForUpgradeStatus(200))
    }

    @Test
    fun textFramesMalformedMessagesAndOtherStreamsAreIgnored() {
        val r = rig()
        r.connect()
        r.session.onText()
        r.muse.controlRaw(byteArrayOf(5, 0, 0, 0) + "{oops".toByteArray())
        r.muse.control(obj("id" to registerId, "ok" to true), stream = 99)
        r.pump()
        assertTrue(r.rec.outcomes.isEmpty())
        assertEquals(0, r.rec.registered)
        r.muse.control(obj("id" to registerId, "ok" to true))
        r.pump()
        assertEquals(1, r.rec.registered)
    }

    @Test
    fun anOversizedInboundMessageEndsTheSession() {
        val r = rig()
        r.connect()
        r.muse.controlRaw(byteArrayOf(1, 0, 0x40, 0))
        r.pump()
        assertEquals(listOf(LinkOutcome.CLOSED), r.rec.outcomes)
    }

    @Test
    fun aRegistrationTooBigForTheMusesBudgetIsNeverSent() {
        val big = DeviceDescription("n", "d", "v", obj("c" to "x".repeat(LinkWire.MAX_CONTROL_MESSAGE_BYTES)))
        val r = Rig(big, registerId)
        r.connect()
        assertEquals(listOf(LinkOutcome.CLOSED), r.rec.outcomes)
        assertTrue("só o pedido de abertura chegou, o link.register não", r.muse.controlMessages.isEmpty())
        assertTrue(r.rec.logs.any { it.contains("budget") })
    }

    @Test
    fun logsNeverCarryTheDeviceTokenOrMessageContents() {
        val r = rig()
        r.connect()
        r.ack()
        r.muse.control(obj("method" to "link.invoke", "id" to "i", "command" to "SEGREDO-NO-COMANDO"))
        r.pump()
        assertTrue(r.rec.logs.none { it.contains("SEGREDO") })
        assertNotNull(r.rec.logs.find { it == "registered with the Muse" })
    }
}
