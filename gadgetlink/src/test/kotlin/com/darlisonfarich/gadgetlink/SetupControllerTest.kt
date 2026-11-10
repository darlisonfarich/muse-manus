package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Port de linux/tests/test_ble_setup.py do SDK oficial, mais as bordas do transporte Android. */
class SetupControllerTest {
    class FakeTransport(private val mtu: Int = 185) : SetupTransport {
        val messages = CopyOnWriteArrayList<Any>()
        val disconnects = CopyOnWriteArrayList<Double>()
        private val assembler = ChunkAssembler()

        override fun mtu() = mtu

        @Synchronized
        override fun sendPackets(packets: List<ByteArray>) {
            for (packet in packets) {
                assertTrue("packet ${packet.size} > mtu $mtu", packet.size <= maxOf(mtu - 3, 20))
                val message = assembler.feed(packet) ?: continue
                if ((packet[0].toInt() and 0xFF) == 0xFE) messages.add(Json.parseToJsonElement(message.decodeToString()).jsonObject)
                else messages.add(message.decodeToString())
            }
        }

        override fun disconnect(delaySeconds: Double) {
            disconnects.add(delaySeconds)
        }
    }

    class FakeNetwork(@Volatile var online: Boolean = true) : SetupNetwork {
        override fun isOnline() = online
        override fun currentConnectionEntry(): JsonObject = buildJsonObject {
            put("ssid", "HomeNet"); put("rssi", -40); put("secure", false)
        }
    }

    private val identity = Identity(Vectors.APP.s("mac"))

    inner class Harness(
        online: Boolean = true,
        mtu: Int = 185,
        provision: ((Credentials, Commit) -> Unit)? = null,
        clock: FakeClock = FakeClock(),
    ) {
        val transport = FakeTransport(mtu)
        val network = FakeNetwork(online)
        val pairing = makeDevice(clock = clock)
        val saved = CopyOnWriteArrayList<Credentials>()
        val completed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<SetupEvent>()
        val controller = SetupController(
            pairing = pairing,
            identity = identity,
            version = Vectors.APP.s("firmware_version"),
            transport = transport,
            network = network,
            provision = provision ?: { credentials, commit ->
                if (!commit { saved.add(credentials); true }) throw ProvisionFailed("error_storage")
            },
            onComplete = { completed.countDown() },
            onEvent = { event, _ -> events.add(event) },
        )
        val mobile = Mobile(Vectors.APP)

        fun send(obj: JsonObject) = controller.handleMessage(obj.toString().toByteArray())
        fun sendEncrypted(command: JsonObject) = send(mobile.seal(command))
        fun pair() {
            send(hello())
            send(clientFinishedRecord())
            mobile.txCounter = 1
        }

        fun opened(): List<JsonObject> = transport.messages.filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.content == "pairing_encrypted" }
            .map { mobile.open(it) }

        fun statuses(): List<String> = opened().filter { it.s("type") == "status" }.map { it.s("status") }

        fun waitForStatus(status: String, timeoutMs: Long = 3000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (status !in statuses()) {
                assertTrue("no $status; got ${statuses()}", System.currentTimeMillis() < deadline)
                Thread.sleep(10)
            }
        }
    }

    private val provisionCommand = json(
        "action" to "provision_v2", "ssid" to "HomeNet", "password" to "",
        "access_token" to "device-access", "refresh_token" to "device-refresh", "token_type" to "device",
        "username" to "someone", "api_url" to "https://legacy-api.example", "api_url_v2" to "https://api.example",
    )

    @Test
    fun deviceInfoMatchesThePairingTranscriptFields() {
        val h = Harness()
        h.send(json("action" to "get_device_info"))
        val info = h.transport.messages.last() as JsonObject
        assertEquals("device_info", info.s("type"))
        assertEquals(Vectors.APP.s("node_id"), info.s("node_id"))
        assertEquals(Vectors.APP.s("firmware_version"), info.s("version"))
        assertEquals("hatch_link", info.s("model"))
        assertEquals(5, info.i("pairing_protocol"))
        assertEquals("none", info.s("pairing_auth"))
        assertEquals("confirm_app", info.s("pairing_policy"))
        assertEquals("", info.s("build_sha"))
        assertTrue(info["network_ready"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("type", "node_id", "version", "device_id", "mac", "model", "pairing_protocol", "pairing_auth",
                "pairing_auth_epoch", "pairing_policy", "build_sha", "network_ready"),
            info.keys.toList(),
        )
    }

    @Test
    fun helloReturnsPairingReadyInChunksAtMinimumMtu() {
        val h = Harness(mtu = 23)
        h.send(hello())
        val ready = h.transport.messages.last() as JsonObject
        assertEquals("pairing_ready", ready.s("type"))
        assertEquals(Vectors.APP.s("session_id"), ready.s("session_id"))
        assertEquals(listOf(SetupEvent.HELLO_OK), h.events.toList())
    }

    @Test
    fun fullSetupFlow() {
        val h = Harness()
        h.pair()
        assertEquals(listOf("pairing_confirmed"), h.statuses())

        h.sendEncrypted(json("action" to "wifi_scan"))
        assertEquals(
            Json.parseToJsonElement("""{"type":"wifi_scan_result","networks":[{"ssid":"HomeNet","rssi":-40,"secure":false}]}"""),
            h.opened().last(),
        )

        h.sendEncrypted(provisionCommand)
        h.waitForStatus("auth_ok")
        assertEquals(listOf("pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"), h.statuses())
        assertTrue(h.completed.await(2, TimeUnit.SECONDS))
        val saved = h.saved.single()
        assertEquals(Credentials("device-access", "device-refresh", "someone", "https://legacy-api.example", "https://api.example", ""), saved)
        assertEquals(PairingState.PROVISIONING, h.pairing.state)
        assertTrue(SetupEvent.CONFIRMED in h.events && SetupEvent.PROVISIONED in h.events)
    }

    @Test
    fun setupCommandsNeedEncryption() {
        val h = Harness()
        h.send(json("action" to "wifi_scan"))
        h.send(provisionCommand)
        assertEquals(listOf<Any>("error_encryption_required", "error_encryption_required"), h.transport.messages.toList())
    }

    @Test
    fun plaintextIsIgnoredOncePairingStarts() {
        val h = Harness()
        h.send(hello())
        h.send(json("action" to "wifi_scan"))
        assertEquals(1, h.transport.messages.size)
    }

    @Test
    fun deviceInfoIsAnsweredMidSessionAndANewHelloRestarts() {
        val h = Harness()
        h.pair()
        h.send(json("action" to "get_device_info"))
        assertEquals("device_info", (h.transport.messages.last() as JsonObject).s("type"))
        assertTrue(h.pairing.confirmed)
        h.send(hello())
        assertEquals("pairing_ready", (h.transport.messages.last() as JsonObject).s("type"))
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, h.pairing.state)
    }

    @Test
    fun badHelloGetsAPlaintextError() {
        val h = Harness()
        h.send(hello(overrides = mapOf("pairing_policy" to JsonPrimitive("confirm_press"))))
        assertEquals(listOf<Any>("error_pairing_invalid_hello"), h.transport.messages.toList())
        assertEquals(listOf(SetupEvent.HELLO_REJECTED), h.events.toList())
    }

    @Test
    fun decryptFailureDisconnectsWithoutAPlaintextReply() {
        val h = Harness()
        h.send(hello())
        h.send(JsonObject(clientFinishedRecord() + ("counter" to JsonPrimitive("1"))))
        assertEquals(1, h.transport.messages.size)
        assertEquals(listOf(0.3), h.transport.disconnects.toList())
        assertEquals(PairingState.IDLE, h.pairing.state)
        assertTrue(SetupEvent.DECRYPT_FAILED in h.events)
    }

    @Test
    fun wrongFirstRecordMeansTheSessionCanNeverConfirm() {
        val h = Harness()
        h.send(hello())
        h.sendEncrypted(json("action" to "wifi_scan"))
        assertEquals(listOf("error_pairing_confirm_required"), h.statuses())
        h.sendEncrypted(json("action" to "pairing_client_finished"))
        assertEquals(listOf(0.3), h.transport.disconnects.toList())
        assertEquals(PairingState.IDLE, h.pairing.state)
    }

    @Test
    fun provisionRequiresDeviceTokens() {
        val changes: List<Pair<String, JsonElement>> = listOf(
            "refresh_token" to JsonPrimitive(""),
            "token_type" to JsonPrimitive("user"),
            "access_token" to JsonNull,
            "password" to JsonNull,
            "ssid" to JsonPrimitive(5),
        )
        for ((key, value) in changes) {
            val h = Harness()
            h.pair()
            h.sendEncrypted(JsonObject(provisionCommand + (key to value)))
            assertEquals("$key=$value", "error_missing_credentials", h.statuses().last())
            assertEquals(PairingState.READY, h.pairing.state)
        }
    }

    @Test
    fun offlineDeviceReportsWifiFailureAndCanRetry() {
        val h = Harness(online = false)
        h.pair()
        h.sendEncrypted(json("action" to "wifi_scan"))
        assertEquals(Json.parseToJsonElement("[]"), h.opened().last()["networks"])

        h.sendEncrypted(provisionCommand)
        h.waitForStatus("wifi_failed")
        assertTrue(h.transport.disconnects.isEmpty())

        h.network.online = true
        // espera a thread de provisionamento anterior soltar a trava antes de tentar de novo
        val deadline = System.currentTimeMillis() + 2000
        while (h.controller.provisioningInFlight && System.currentTimeMillis() < deadline) Thread.sleep(5)
        h.sendEncrypted(provisionCommand)
        h.waitForStatus("auth_ok")
    }

    @Test
    fun rejectedTokenReportsAuthFailedAndDisconnects() {
        val h = Harness(provision = { _, _ -> throw ProvisionFailed("auth_failed") })
        h.pair()
        h.sendEncrypted(provisionCommand)
        h.waitForStatus("auth_failed")
        val deadline = System.currentTimeMillis() + 2000
        while (h.transport.disconnects.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(listOf(0.5), h.transport.disconnects.toList())
        assertEquals(1L, h.completed.count)
        assertTrue(SetupEvent.PROVISION_FAILED in h.events)
    }

    @Test
    fun unexpectedProvisionCrashIsReportedNotSwallowed() {
        // No Python uma exceção qualquer some na thread; aqui o app recebe um status e a trava é solta.
        val h = Harness(provision = { _, _ -> error("boom") })
        h.pair()
        h.sendEncrypted(provisionCommand)
        h.waitForStatus("error_storage")
        val deadline = System.currentTimeMillis() + 2000
        while (h.controller.provisioningInFlight && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse(h.controller.provisioningInFlight)
    }

    @Test
    fun secondProvisionWhileOneRunsIsRefused() {
        val release = CountDownLatch(1)
        val h = Harness(provision = { _, commit ->
            release.await(5, TimeUnit.SECONDS)
            commit { true }
        })
        h.pair()
        h.sendEncrypted(provisionCommand)
        h.waitForStatus("wifi_connected")
        h.sendEncrypted(provisionCommand)
        h.waitForStatus("error_operation_in_progress")
        release.countDown()
        h.waitForStatus("auth_ok")
    }

    @Test
    fun disconnectClearsTheSession() {
        val h = Harness()
        h.pair()
        h.controller.onDisconnect()
        assertEquals(PairingState.IDLE, h.pairing.state)
        h.send(json("action" to "wifi_scan"))
        assertEquals("error_encryption_required", h.transport.messages.last())
        assertTrue(SetupEvent.DISCONNECTED in h.events)
    }

    @Test
    fun invalidJsonAndNonObjectsGetInvalidCommand() {
        // Sem sessão o SDK não responde em texto puro (o status não está na lista permitida);
        // com sessão, a resposta vai cifrada.
        val h = Harness()
        for (raw in listOf("{not json".toByteArray(), "[1,2]".toByteArray(), byteArrayOf(0x7B, 0xFF.toByte(), 0x7D))) {
            h.controller.handleMessage(raw)
        }
        assertTrue(h.transport.messages.isEmpty())
        assertEquals(3, h.events.count { it == SetupEvent.INVALID_COMMAND })
        h.pair()
        h.controller.handleMessage("[1,2]".toByteArray())
        assertEquals("error_invalid_command", h.statuses().last())
    }

    @Test
    fun unknownActionIsReportedOnlyWhenItCanBe() {
        val h = Harness()
        // sem sessão e fora da lista de status em texto puro: não sai nada
        h.send(json("action" to "dance"))
        assertTrue(h.transport.messages.isEmpty())
        h.pair()
        h.sendEncrypted(json("action" to "dance"))
        assertEquals("error_unknown_action", h.statuses().last())
    }

    @Test
    fun writesAreReassembledBeforeDispatch() {
        val h = Harness()
        h.controller.start()
        try {
            for (packet in BleFraming.encodeChunks(hello().toString().toByteArray(), 23)) h.controller.onWrite(packet)
            val deadline = System.currentTimeMillis() + 2000
            while (h.transport.messages.isEmpty()) {
                assertTrue(System.currentTimeMillis() < deadline)
                Thread.sleep(10)
            }
        } finally {
            h.controller.stop()
        }
        assertEquals("pairing_ready", (h.transport.messages[0] as JsonObject).s("type"))
    }

    @Test
    fun aThrowingHandlerDoesNotKillTheWorker() {
        // Transporte que falha uma vez: a próxima mensagem ainda tem que ser atendida.
        var failNext = true
        val inner = FakeTransport()
        val flaky = object : SetupTransport by inner {
            override fun sendPackets(packets: List<ByteArray>) {
                if (failNext) { failNext = false; throw IllegalStateException("radio hiccup") }
                inner.sendPackets(packets)
            }
        }
        val controller = SetupController(
            pairing = makeDevice(), identity = identity, version = "1", transport = flaky,
            network = FakeNetwork(), provision = { _, _ -> },
        )
        controller.start()
        try {
            controller.onWrite(json("action" to "get_device_info").toString().toByteArray())
            controller.onWrite(json("action" to "get_device_info").toString().toByteArray())
            val deadline = System.currentTimeMillis() + 2000
            while (inner.messages.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        } finally {
            controller.stop()
        }
        assertEquals(1, inner.messages.size)
    }

    @Test
    fun encryptedStatusesStayInCounterOrderUnderConcurrentSenders() {
        val h = Harness()
        h.pair()
        val threads = (1..8).map { n -> Thread { repeat(25) { h.controller.sendStatus("s$n") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val counters = h.transport.messages.filterIsInstance<JsonObject>()
            .filter { it.s("type") == "pairing_encrypted" }.map { it.s("counter").toInt() }
        assertEquals((0 until counters.size).toList(), counters)
    }
}
