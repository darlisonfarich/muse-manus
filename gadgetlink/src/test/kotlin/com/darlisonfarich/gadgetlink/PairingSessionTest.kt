package com.darlisonfarich.gadgetlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Port de linux/tests/test_pairing.py do SDK oficial, mais casos de falha que o
 * Python cobre de graça (ponto fora da curva, UTF-8 inválido, concorrência).
 */
class PairingSessionTest {
    private val app = Vectors.APP

    private fun expectDecryptFailure(device: PairingSession, envelope: JsonObject) {
        val err = assertThrows(PairingException::class.java) { device.decrypt(envelope) }
        assertEquals("error_pairing_decrypt", err.status)
        assertEquals(PairingState.IDLE, device.state)
    }

    private fun expectInvalidHello(device: PairingSession, message: JsonObject) {
        val err = assertThrows(PairingException::class.java) { device.handleHello(message) }
        assertEquals("error_pairing_invalid_hello", err.status)
    }

    // --- Device info e pairing_ready --------------------------------------------------

    @Test
    fun deviceInfoAdvertisesCommunityAppConfirmation() {
        assertEquals(
            json(
                "device_id" to app.s("device_id"), "mac" to app.s("mac"), "model" to "hatch_link",
                "pairing_protocol" to 5, "pairing_auth" to "none", "pairing_auth_epoch" to 0,
                "pairing_policy" to "confirm_app",
            ),
            makeDevice().deviceInfo(),
        )
    }

    @Test
    fun helloProducesTheVectorPairingReady() {
        assertEquals(
            json(
                "type" to "pairing_ready", "version" to 5,
                "device_id" to app.s("device_id"), "node_id" to app.s("node_id"), "mac" to app.s("mac"),
                "model" to "hatch_link", "firmware_version" to app.s("firmware_version"),
                "pairing_auth" to "none", "pairing_auth_epoch" to 0, "pairing_policy" to "confirm_app",
                "device_pub" to app.s("device_pub"), "device_nonce" to app.s("device_nonce"),
                "transcript_hash" to app.s("transcript_hash"), "session_id" to app.s("session_id"),
            ),
            makeDevice().handleHello(hello()),
        )
    }

    @Test
    fun emptyFirmwareVersionBecomesUnknown() {
        val device = PairingSession(nodeId = "n", deviceId = "d", mac = "m", firmwareVersion = "")
        assertEquals("unknown", (device.handleHello(hello())["firmware_version"] as JsonPrimitive).content)
    }

    @Test
    fun emptyIdentityFailsAtConstructionNotAtTheFirstPairing() {
        // No SDK isso só estoura no meio do primeiro hello, já no celular do usuário.
        assertThrows(IllegalArgumentException::class.java) { PairingSession(nodeId = "", deviceId = "d", mac = "m", firmwareVersion = "1") }
        assertThrows(IllegalArgumentException::class.java) { PairingSession(nodeId = "n", deviceId = "", mac = "m", firmwareVersion = "1") }
        assertThrows(IllegalArgumentException::class.java) { PairingSession(nodeId = "n", deviceId = "d", mac = "", firmwareVersion = "1") }
    }

    @Test
    fun realKeysAndNoncesAreFreshEveryHello() {
        val device = PairingSession(nodeId = "n", deviceId = "d", mac = "m", firmwareVersion = "1")
        val first = device.handleHello(hello())
        val second = device.handleHello(hello())
        assertNotEquals(first["device_pub"], second["device_pub"])
        assertNotEquals(first["device_nonce"], second["device_nonce"])
        assertNotEquals(first["session_id"], second["session_id"])
        assertEquals(65, unb64((first["device_pub"] as JsonPrimitive).content).size)
        assertEquals(16, unb64((first["device_nonce"] as JsonPrimitive).content).size)
    }

    // --- Handshake completo -----------------------------------------------------------

    @Test
    fun fullAppConfirmedHandshake() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state)

        val plaintext = device.decrypt(clientFinishedRecord())
        assertEquals(app.s("client_finished_plaintext"), plaintext)

        val generation = device.handleClientFinished(Json.parseToJsonElement(plaintext))
        assertTrue(generation != 0L)
        assertTrue(device.confirmed)

        val mobile = Mobile(app)
        assertEquals(
            json("type" to "status", "status" to "pairing_confirmed"),
            mobile.open(device.encryptStatus("pairing_confirmed", generation)),
        )
        val scan = device.decrypt(mobile.seal(json("action" to "wifi_scan"), counter = 1))
        assertEquals(json("action" to "wifi_scan"), Json.parseToJsonElement(scan))
    }

    @Test
    fun pairingConfirmedCarriesTheSdkToken() {
        val token = "mgst_" + "A".repeat(43)
        val device = makeDevice(sdkToken = token)
        device.handleHello(hello())
        val generation = device.handleClientFinished(Json.parseToJsonElement(device.decrypt(clientFinishedRecord())))
        val mobile = Mobile(app)
        assertEquals(
            json("type" to "status", "status" to "pairing_confirmed", "sdk_token" to token),
            mobile.open(device.encryptStatus("pairing_confirmed", generation)),
        )
        assertEquals(
            json("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", generation)),
        )
    }

    @Test
    fun statusPlaintextIsCompactJsonInTheSdkKeyOrder() {
        assertEquals("""{"type":"status","status":"pairing_confirmed"}""", Pairing.statusPlaintext("pairing_confirmed", null))
        assertEquals(
            """{"type":"status","status":"pairing_confirmed","sdk_token":"t"}""",
            Pairing.statusPlaintext("pairing_confirmed", "t"),
        )
        assertEquals("""{"type":"status","status":"wifi_connected"}""", Pairing.statusPlaintext("wifi_connected", "t"))
    }

    @Test
    fun deviceRecordsCountUpFromZero() {
        val (device, mobile, generation) = confirmedDevice()
        val first = device.encryptStatus("pairing_confirmed", generation)!!
        val second = device.encryptJson("""{"type":"wifi_scan_result","networks":[]}""", generation)!!
        assertEquals("0" to "1", (first["counter"] as JsonPrimitive).content to (second["counter"] as JsonPrimitive).content)
        assertEquals(Json.parseToJsonElement("""{"type":"wifi_scan_result","networks":[]}"""), mobile.open(second))
    }

    // --- Validação do hello -----------------------------------------------------------

    @Test
    fun helloRejectsInvalidInput() {
        val cases: List<Pair<String, JsonElement>> = listOf(
            "version" to JsonPrimitive(4),
            "version" to JsonPrimitive("5"),
            "version" to JsonPrimitive(true),
            "version" to JsonNull,
            "pairing_auth" to JsonPrimitive("fleet_ecdsa_p256_v1"),
            "pairing_policy" to JsonPrimitive("confirm_press"),
            "pairing_policy" to JsonNull,
            "mobile_pub" to JsonPrimitive(""),
            "mobile_pub" to JsonPrimitive(b64(byteArrayOf(4) + ByteArray(64))),
            "mobile_pub" to JsonPrimitive(b64(byteArrayOf(2) + ByteArray(32))),
            "mobile_pub" to JsonPrimitive(app.s("mobile_pub") + "="),
            // coordenadas >= p: não são elementos do corpo, então não podem ser ponto da curva
            "mobile_pub" to JsonPrimitive(b64(byteArrayOf(4) + ByteArray(64) { 0xFF.toByte() })),
            "mobile_pub" to JsonPrimitive(7),
            "mobile_nonce" to JsonPrimitive(b64(ByteArray(15))),
            "mobile_nonce" to JsonPrimitive(b64(ByteArray(17))),
            "mobile_nonce" to JsonPrimitive(7),
        )
        for ((key, value) in cases) {
            val device = makeDevice()
            val err = assertThrows("$key=$value", PairingException::class.java) {
                device.handleHello(hello(overrides = mapOf(key to value)))
            }
            assertEquals("error_pairing_invalid_hello", err.status)
            assertEquals("$key=$value", PairingState.IDLE, device.state)
        }
    }

    @Test
    fun helloWithMissingFieldsIsRejected() {
        for (key in listOf("version", "pairing_auth", "pairing_policy", "mobile_pub", "mobile_nonce")) {
            expectInvalidHello(makeDevice(), JsonObject(hello() - key))
        }
    }

    @Test
    fun helloAcceptsVersionWrittenAsAFloat() {
        // json.loads devolve 5.0 e o Python compara 5.0 == 5; o app pode serializar assim.
        val ready = makeDevice().handleHello(hello(overrides = mapOf("version" to JsonPrimitive(5.0))))
        assertEquals(app.s("session_id"), (ready["session_id"] as JsonPrimitive).content)
    }

    @Test
    fun badVersionLeavesTheCurrentSessionAlone() {
        val (device, _, generation) = confirmedDevice()
        expectInvalidHello(device, hello(overrides = mapOf("version" to JsonPrimitive(4))))
        assertTrue(device.isCurrent(generation))
        assertTrue(device.confirmed)
    }

    @Test
    fun badKeyMaterialClearsTheCurrentSession() {
        val (device, _, generation) = confirmedDevice()
        expectInvalidHello(device, hello(overrides = mapOf("mobile_pub" to JsonPrimitive(b64(byteArrayOf(4) + ByteArray(64))))))
        assertFalse(device.isCurrent(generation))
        assertEquals(PairingState.IDLE, device.state)
    }

    @Test
    fun newHelloReplacesThePreviousSession() {
        val (device, _, generation) = confirmedDevice()
        device.handleHello(hello())
        assertFalse(device.isCurrent(generation))
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state)
    }

    // --- Camada de registros ----------------------------------------------------------

    @Test
    fun replayedRecordClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(json("action" to "wifi_scan"))
        device.decrypt(record)
        expectDecryptFailure(device, record)
    }

    @Test
    fun skippedCounterClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        expectDecryptFailure(device, mobile.seal(json("action" to "wifi_scan"), counter = 2))
    }

    @Test
    fun malformedCounterClearsTheSession() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive("-1"), JsonPrimitive("+1"), JsonPrimitive(" 1"), JsonPrimitive("1.0"), JsonPrimitive(""),
            JsonPrimitive("18446744073709551616"), JsonPrimitive("１"), JsonPrimitive(1), JsonNull,
        )
        for (counter in bad) {
            val (device, mobile, _) = confirmedDevice()
            val record = JsonObject(mobile.seal(json("action" to "wifi_scan")) + ("counter" to counter))
            expectDecryptFailure(device, record)
        }
    }

    @Test
    fun tamperedCiphertextClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(json("action" to "wifi_scan"))
        val raw = unb64(record.s("ciphertext")).also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectDecryptFailure(device, JsonObject(record + ("ciphertext" to JsonPrimitive(b64(raw)))))
    }

    @Test
    fun wrongSessionIdClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(json("action" to "wifi_scan"))
        expectDecryptFailure(device, JsonObject(record + ("session_id" to JsonPrimitive(b64(ByteArray(16))))))
    }

    @Test
    fun shortTagClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(json("action" to "wifi_scan"))
        expectDecryptFailure(device, JsonObject(record + ("tag" to JsonPrimitive(record.s("tag").dropLast(2)))))
    }

    @Test
    fun missingRecordFieldsClearTheSession() {
        for (key in listOf("session_id", "counter", "ciphertext", "tag")) {
            val (device, mobile, _) = confirmedDevice()
            expectDecryptFailure(device, JsonObject(mobile.seal(json("action" to "wifi_scan")) - key))
        }
    }

    @Test
    fun oversizeCiphertextClearsTheSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(json("action" to "wifi_scan"))
        expectDecryptFailure(device, JsonObject(record + ("ciphertext" to JsonPrimitive("A".repeat(16_385)))))
    }

    @Test
    fun invalidUtf8PlaintextClearsTheSession() {
        // O Python decodifica UTF-8 estrito; a JVM trocaria por U+FFFD em silêncio se não for cuidadosa.
        val (device, mobile, _) = confirmedDevice()
        expectDecryptFailure(device, mobile.sealBytes(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)))
    }

    @Test
    fun decryptWithoutSessionFails() {
        expectDecryptFailure(makeDevice(), clientFinishedRecord())
    }

    // --- Client finished --------------------------------------------------------------

    @Test
    fun firstRecordMustBeExactClientFinished() {
        val commands: List<JsonElement> = listOf(
            json("action" to "wifi_scan"),
            json("action" to "pairing_client_finished", "extra" to true),
            json("action" to "provision_v2", "ssid" to "x"),
            json("action" to 1),
            JsonArray(listOf(JsonPrimitive("pairing_client_finished"))),
            JsonPrimitive("pairing_client_finished"),
        )
        for (command in commands) {
            val device = makeDevice()
            device.handleHello(hello())
            val plaintext = device.decrypt(Mobile(app).sealBytes(command.toString().toByteArray()))
            assertEquals(command.toString(), 0L, device.handleClientFinished(Json.parseToJsonElement(plaintext)))
            assertEquals(PairingState.IDLE, device.state)
        }
    }

    @Test
    fun clientFinishedOnlyCountsAsTheFirstRecord() {
        val (device, mobile, _) = confirmedDevice()
        val plaintext = device.decrypt(mobile.seal(json("action" to "pairing_client_finished")))
        assertEquals(0L, device.handleClientFinished(Json.parseToJsonElement(plaintext)))
        assertEquals(PairingState.IDLE, device.state)
    }

    @Test
    fun clientFinishedBeforeAnyRecordIsRejected() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(0L, device.handleClientFinished(json("action" to "pairing_client_finished")))
        assertEquals(PairingState.IDLE, device.state)
    }

    // --- Prazos e gerações ------------------------------------------------------------

    @Test
    fun clientFinishedTimesOutAfter60Seconds() {
        val clock = FakeClock()
        val device = makeDevice(clock = clock)
        device.handleHello(hello())
        clock.now += 60.5
        expectDecryptFailure(device, clientFinishedRecord())
    }

    @Test
    fun clientFinishedStillWorksRightAtTheDeadline() {
        val clock = FakeClock()
        val device = makeDevice(clock = clock)
        device.handleHello(hello())
        clock.now += 60.0
        assertEquals(app.s("client_finished_plaintext"), device.decrypt(clientFinishedRecord()))
    }

    @Test
    fun confirmedSessionExpiresAfter120Seconds() {
        val clock = FakeClock()
        val (device, _, generation) = confirmedDevice(clock)
        clock.now += 119
        assertTrue(device.confirmed)
        clock.now += 2
        assertFalse(device.confirmed)
        assertNull(device.encryptStatus("wifi_connected"))
        assertTrue(device.isCurrent(generation))
    }

    @Test
    fun staleGenerationCannotEncrypt() {
        val (device, mobile, generation) = confirmedDevice()
        val provisioning = device.markProvisioning()
        assertTrue(provisioning != 0L && provisioning != generation)
        assertNull(device.encryptStatus("wifi_connecting", generation))
        assertEquals(
            json("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", provisioning)),
        )
    }

    @Test
    fun provisioningRequiresConfirmation() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(0L, device.markProvisioning())
    }

    @Test
    fun markProvisioningTwiceKeepsTheSameGeneration() {
        val (device, _, _) = confirmedDevice()
        val first = device.markProvisioning()
        assertEquals(first, device.markProvisioning())
    }

    @Test
    fun commitRunsOnlyForTheCurrentProvisioningSession() {
        val clock = FakeClock()
        val (device, _, _) = confirmedDevice(clock)
        val generation = device.markProvisioning()
        val committed = mutableListOf<Int>()
        assertFalse(device.commitProvisioning(generation + 1) { committed.add(1); true })
        assertTrue(device.commitProvisioning(generation) { committed.add(2); true })
        assertEquals(listOf(2), committed)

        clock.now += 119
        assertTrue(device.extendProvisioning(generation))
        clock.now += 119
        assertTrue(device.commitProvisioning(generation) { true })
        clock.now += 2
        assertFalse(device.commitProvisioning(generation) { true })
    }

    @Test
    fun resetDropsTheSessionAndItsGeneration() {
        val (device, _, generation) = confirmedDevice()
        device.reset()
        assertEquals(PairingState.IDLE, device.state)
        assertFalse(device.isCurrent(generation))
        assertFalse(device.isCurrent(0))
        assertNull(device.encryptStatus("wifi_connected"))
    }

    @Test
    fun concurrentSendersNeverReuseACounter() {
        // Callbacks de GATT chegam em threads de binder; contador repetido = nonce GCM repetido.
        val (device, mobile, generation) = confirmedDevice()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val counters = Collections.synchronizedList(mutableListOf<String>())
        repeat(400) {
            pool.execute {
                start.await()
                val record = device.encryptJson("{}", generation)!!
                counters.add(record.s("counter"))
                assertEquals(JsonObject(emptyMap()), mobile.open(record))
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((0 until 400).map { it.toString() }.toSet(), counters.toSet())
    }

    // --- base64url --------------------------------------------------------------------

    @Test
    fun b64urlDecodeRejectsWhatTheFirmwareRejects() {
        for (text in listOf("", "a", "ab=c", "ab+c", "ab/c", "a b", "ab\n", "AAAAA", null)) {
            assertThrows("$text", IllegalArgumentException::class.java) { B64Url.decode(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { B64Url.decode("A".repeat(4097)) }
        assertEquals(3072, B64Url.decode("A".repeat(4096)).size)
    }

    @Test
    fun b64urlRoundTripIsUnpadded() {
        assertEquals("-_8", B64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), B64Url.decode("-_8"))
        assertEquals("", B64Url.encode(ByteArray(0)))
        for (n in 1..40) {
            val data = ByteArray(n) { (it * 37 + 11).toByte() }
            assertEquals(b64(data), B64Url.encode(data))
            assertArrayEquals(data, B64Url.decode(B64Url.encode(data)))
        }
    }

    @Test
    fun b64urlIgnoresLeftoverBitsLikePython() {
        // base64.urlsafe_b64decode("AB==") -> b"\x00": os bits que sobram não são checados.
        assertArrayEquals(byteArrayOf(0), B64Url.decode("AB"))
    }
}
