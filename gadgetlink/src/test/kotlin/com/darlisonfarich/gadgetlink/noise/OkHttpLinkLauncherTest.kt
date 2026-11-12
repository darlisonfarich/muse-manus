package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.Vm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * O lançador de verdade (OkHttp) contra um servidor WebSocket de verdade na própria máquina que fala o
 * protocolo do Muse: upgrade HTTP com o bearer da VM, handshake Noise, stream /link-control e o registro.
 */
class OkHttpLinkLauncherTest {
    private val servers = ArrayList<MockWebServer>()

    @After fun stop() = servers.forEach { runCatching { it.shutdown() } }

    /** O lado do Muse num WebSocket: responde o handshake, decifra o que chega e deixa injetar quadros. */
    private class MuseSocket(private val onRegisterMessage: (MuseSocket, JsonObject) -> Unit) : WebSocketListener() {
        val responder = TestNoiseResponder("muse-hello".toByteArray())
        private var step = 0
        private lateinit var send: CipherState
        private lateinit var recv: CipherState
        private val frames = NoiseFrameDecoder()
        private val control = MessageDecoder()
        @Volatile var socket: WebSocket? = null
        val requests = LinkedBlockingQueue<ApplicationRequest>()
        val messages = LinkedBlockingQueue<JsonObject>()
        private var chunk = 9000L

        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            when (step) {
                0 -> { webSocket.send(responder.readMessage1AndWriteMessage2(data).toByteString()); step = 1 }
                1 -> {
                    responder.readMessage3(data)
                    val (s, r) = responder.split()
                    send = s; recv = r; step = 2
                }
                else -> {
                    val message = frames.decode(recv.decryptWithAd(ByteArray(0), data)) ?: return
                    val frame = Envelope.decodeServiceFrame(Envelope.decodeServiceRequest(message).payload)
                    when (val v = frame.value) {
                        is ApplicationRequest -> { requests += v; handle(control.feed(v.body)) }
                        is BodyChunk -> handle(control.feed(v.data))
                        else -> Unit
                    }
                }
            }
        }

        private fun handle(list: List<JsonObject>) = list.forEach {
            messages += it
            if (it["method"]?.jsonPrimitive?.content == "link.register") onRegisterMessage(this, it)
        }

        fun emit(frame: ServiceFrame) {
            val envelope = Envelope.encodeServiceResponse(ServiceResponse(Envelope.encodeServiceFrame(frame)))
            NoiseFraming.encode(envelope, chunk++).forEach { socket!!.send(send.encryptWithAd(ByteArray(0), it).toByteString()) }
        }

        fun control(message: JsonObject, end: Boolean = false) = emit(ServiceFrame(1, BodyChunk(LinkWire.encodeMessage(message), end)))

        fun ack(register: JsonObject, ok: Boolean = true) {
            emit(ServiceFrame(1, ApplicationResponse(200, emptyList(), ByteArray(0), false)))
            control(JsonObject(mapOf("id" to register["id"]!!, "ok" to JsonPrimitive(ok))))
        }
    }

    private class Events : SessionLauncher.Events {
        val registered = CountDownLatch(1)
        val ended = LinkedBlockingQueue<Pair<LinkOutcome, Long>>()
        override fun onRegistered() { registered.countDown() }
        override fun onEnded(outcome: LinkOutcome, lastedMs: Long) { ended += outcome to lastedMs }
    }

    private class Clock : LinkClock {
        @Volatile var now = 0L
        override fun nowMs() = now
        override fun wallSeconds() = 0L
    }

    private val pairing = PairingRecord("a", "r", "", "", 0, null)
    private val vm = Vm("wss://ignored", "vm-token-123", "Alpha", "vm-id-9", true)

    private fun launcher(clock: LinkClock = Clock(), logs: MutableList<String> = ArrayList()) = OkHttpLinkLauncher(
        client = OkHttpClient(),
        device = { DeviceDescription("homelink-abc123", "Celular", "0.1.7") },
        clock = clock,
        secure = false,
        log = { logs += it },
    )

    private fun museServer(muse: MuseSocket): MockWebServer {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(muse))
        server.start()
        servers += server
        return server
    }

    private fun PairingRecord.at(server: MockWebServer) = copy(noiseHost = "127.0.0.1:${server.port}")

    @Test
    fun itRegistersOverARealWebSocketWithTheVmBearerAndTheVmIdInTheUrl() {
        val muse = MuseSocket { m, reg -> m.ack(reg) }
        val server = museServer(muse)
        val events = Events()
        val handle = launcher().launch(vm, pairing.at(server), events)
        assertTrue("registrou", events.registered.await(10, TimeUnit.SECONDS))

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer vm-token-123", upgrade.getHeader("Authorization"))
        assertEquals("/v1/noise?vm_id=vm-id-9", upgrade.path)
        assertTrue(upgrade.getHeader("User-Agent")!!.startsWith("muse-manus-gadgetlink"))

        val request = muse.requests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.verb)
        assertEquals("/link-control", request.path)
        val register = muse.messages.poll(5, TimeUnit.SECONDS)!!
        assertEquals("link.register", register["method"]!!.jsonPrimitive.content)
        assertEquals("homehub", register["params"]!!.let { (it as JsonObject)["device_family"]!!.jsonPrimitive.content })

        handle.stop()
        val (outcome, _) = events.ended.poll(10, TimeUnit.SECONDS)!!
        assertEquals(LinkOutcome.STOPPED, outcome)
        assertTrue("só um desfecho", events.ended.isEmpty())
    }

    @Test
    fun theVmNameIsUsedWhenTheVmHasNoId() {
        val muse = MuseSocket { m, reg -> m.ack(reg) }
        val server = museServer(muse)
        val events = Events()
        launcher().launch(vm.copy(vmId = "", vmName = "Meu Muse"), pairing.at(server), events).also {
            assertTrue(events.registered.await(10, TimeUnit.SECONDS))
            assertEquals("/v1/noise?vm_id=Meu%20Muse", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            it.stop()
        }
    }

    @Test
    fun anUpgradeRefusedWith401And403EndsWithTheMatchingOutcome() {
        for ((code, want) in listOf(401 to LinkOutcome.AUTH_REJECTED, 403 to LinkOutcome.FORBIDDEN, 500 to LinkOutcome.CLOSED)) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(code))
            server.start()
            servers += server
            val events = Events()
            launcher().launch(vm, pairing.at(server), events)
            val (outcome, lasted) = events.ended.poll(10, TimeUnit.SECONDS)!!
            assertEquals("HTTP $code", want, outcome)
            assertEquals("nunca registrou", 0L, lasted)
            assertEquals(0L, events.registered.count - 1)
        }
    }

    @Test
    fun theLifetimeCountsFromTheRegistrationNotFromTheConnection() {
        val clock = Clock()
        val muse = MuseSocket { m, reg -> m.ack(reg) }
        val server = museServer(muse)
        val events = Events()
        clock.now = 1_000
        launcher(clock).launch(vm, pairing.at(server), events)
        assertTrue(events.registered.await(10, TimeUnit.SECONDS))
        clock.now = 41_000
        muse.control(JsonObject(mapOf("event" to JsonPrimitive("link.unpaired"))))
        val (outcome, lasted) = events.ended.poll(10, TimeUnit.SECONDS)!!
        assertEquals(LinkOutcome.UNPAIRED, outcome)
        assertEquals("40 s desde o registro", 40_000L, lasted)
    }

    @Test
    fun theServerClosingTheSocketEndsClosed() {
        val muse = MuseSocket { m, reg -> m.ack(reg) }
        val server = museServer(muse)
        val events = Events()
        launcher().launch(vm, pairing.at(server), events)
        assertTrue(events.registered.await(10, TimeUnit.SECONDS))
        muse.socket!!.close(1000, "bye")
        assertEquals(LinkOutcome.CLOSED, events.ended.poll(10, TimeUnit.SECONDS)!!.first)
    }

    @Test
    fun aRejectedRegistrationEndsForbidden() {
        val muse = MuseSocket { m, reg -> m.ack(reg, ok = false) }
        val server = museServer(muse)
        val events = Events()
        launcher().launch(vm, pairing.at(server), events)
        assertEquals(LinkOutcome.FORBIDDEN, events.ended.poll(10, TimeUnit.SECONDS)!!.first)
        assertEquals("nunca registrou", 1L, events.registered.count)
    }

    @Test
    fun anUnreachableServerEndsClosedAndAnInvalidHostNeverEvenConnects() {
        val events = Events()
        launcher().launch(vm, pairing.copy(noiseHost = "127.0.0.1:1"), events)
        assertEquals(LinkOutcome.CLOSED, events.ended.poll(15, TimeUnit.SECONDS)!!.first)

        val logs = ArrayList<String>()
        val bad = Events()
        launcher(logs = logs).launch(vm, pairing.copy(noiseHost = "evil.com/x?y"), bad)
        assertEquals(LinkOutcome.CLOSED, bad.ended.poll(5, TimeUnit.SECONDS)!!.first)
        assertTrue(logs.toString(), logs.any { it.contains("invalid noise host") })
    }

    @Test
    fun theLogsNeverCarryTheVmToken() {
        val logs = ArrayList<String>()
        val muse = MuseSocket { m, reg -> m.ack(reg) }
        val server = museServer(muse)
        val events = Events()
        launcher(logs = logs).launch(vm, pairing.at(server), events).also {
            assertTrue(events.registered.await(10, TimeUnit.SECONDS))
            it.stop()
        }
        events.ended.poll(10, TimeUnit.SECONDS)
        assertNotNull(logs.firstOrNull())
        assertTrue(logs.toString(), logs.none { it.contains("vm-token-123") })
    }
}
