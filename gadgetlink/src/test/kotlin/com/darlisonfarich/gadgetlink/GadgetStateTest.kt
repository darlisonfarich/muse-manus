package com.darlisonfarich.gadgetlink

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/** Port de test_identity.py, mais o estado no formato do SDK e a checagem do token na API do Muse. */
class GadgetStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val validToken = "mgst_" + "A".repeat(42) + "Q"

    // --- Identidade ---------------------------------------------------------------

    @Test
    fun namesShareTheSuffix() {
        val ident = Identity("02:00:00:ab:cd:ef")
        assertEquals("homelink-abcdef", ident.nodeId)
        assertEquals("hatch-link:02:00:00:ab:cd:ef", ident.deviceId)
        assertEquals("MuseGadgetABCDEF", ident.bleName)
    }

    @Test
    fun generatedMacIsLocallyAdministeredUnicast() {
        val first = Identity.generateMac { n -> ByteArray(n) { 0xFF.toByte() } }.substring(0, 2).toInt(16)
        assertEquals(0, first and 0x01)
        assertEquals(2, first and 0x02)
        assertEquals("02:01:02:03:04:05", Identity.generateMac { n -> ByteArray(n) { it.toByte() } })
    }

    @Test
    fun invalidMacIsRejected() {
        for (mac in listOf("", "02:00:00:AB:CD:EF", "02-00-00-ab-cd-ef", "02:00:00:ab:cd", "nope")) {
            assertThrows(mac, IllegalArgumentException::class.java) { Identity(mac) }
        }
    }

    @Test
    fun identityPersists() {
        val state = GadgetState(tmp.root)
        val first = state.loadOrCreateIdentity()
        assertEquals(first, GadgetState(tmp.root).loadOrCreateIdentity())
        assertEquals("""{"mac":"${first.mac}"}""", File(tmp.root, "identity.json").readText().filterNot { it.isWhitespace() })
    }

    @Test
    fun corruptIdentityIsReplaced() {
        File(tmp.root, "identity.json").writeText("""{"mac": "nope"}""")
        val ident = GadgetState(tmp.root).loadOrCreateIdentity { n -> ByteArray(n) { it.toByte() } }
        assertEquals("02:01:02:03:04:05", ident.mac)
    }

    @Test
    fun stateDirIsCreatedOnFirstSave() {
        val dir = File(tmp.root, "a/b/state")
        GadgetState(dir).loadOrCreateIdentity()
        assertTrue(File(dir, "identity.json").isFile)
    }

    @Test
    fun saveIsAtomicAndLeavesNoTempFile() {
        val state = GadgetState(tmp.root)
        state.saveJson("pairing.json", buildJsonObject { put("a", 1) })
        state.saveJson("pairing.json", buildJsonObject { put("a", 2) })
        assertEquals(2L, (state.loadJson("pairing.json")!!["a"] as JsonPrimitive).long)
        assertEquals(listOf("pairing.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun garbageJsonReadsAsMissing() {
        File(tmp.root, "pairing.json").writeText("{oops")
        assertNull(GadgetState(tmp.root).loadJson("pairing.json"))
        File(tmp.root, "pairing.json").writeText("[1]")
        assertNull(GadgetState(tmp.root).loadJson("pairing.json"))
    }

    // --- Token do SDK -------------------------------------------------------------

    @Test
    fun sdkTokenFollowsTheIssuedShape() {
        assertTrue(GadgetState.isValidSdkToken(validToken))
        for (bad in listOf("", "mgst_short", "mgst_" + "A".repeat(44),"mgst_" + "A".repeat(42) + "B", "xxxx_" + "A".repeat(43))) {
            assertFalse(bad, GadgetState.isValidSdkToken(bad))
        }
    }

    @Test
    fun sdkTokenIsSavedTrimmedAndReadBack() {
        val state = GadgetState(tmp.root)
        assertNull(state.sdkToken())
        state.saveSdkToken("  $validToken\n")
        assertEquals(validToken, state.sdkToken())
        assertEquals(validToken, File(tmp.root, "sdk_token").readText())
        assertThrows(IllegalArgumentException::class.java) { state.saveSdkToken("mgst_nope") }
        assertEquals(validToken, state.sdkToken())
    }

    @Test
    fun invalidStoredSdkTokenIsAnError() {
        File(tmp.root, "sdk_token").writeText("mgst_bad")
        assertThrows(IllegalArgumentException::class.java) { GadgetState(tmp.root).sdkToken() }
    }

    @Test
    fun sdkTokenHintShowsOnlyThePrefix() {
        assertEquals(validToken.take(12), GadgetState.sdkTokenHint(validToken))
        assertEquals("none", GadgetState.sdkTokenHint(null))
    }

    // --- API do Muse ----------------------------------------------------------------

    private fun serve(status: Int, body: String, check: (Map<String, String>) -> Unit = {}): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fetch_vms") { ex ->
            check(ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() })
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun apiRootPrefersApiUrlV2() {
        assertEquals("https://api.muse.ai", MuseApi.apiRoot(""))
        assertEquals("https://api.example", MuseApi.apiRoot("https://api.example/"))
    }

    @Test
    fun fetchVmsCountsUsableVmsAndSendsTheDeviceToken() {
        var headers: Map<String, String> = emptyMap()
        val (server, root) = serve(
            200,
            """{"vm_list":[{"vm_ws_url":"wss://a","vm_auth_token":"t"},{"vm_url":"wss://b"},{"vm_url":"wss://c","vm_auth_token":"u"},7]}""",
        ) { headers = it }
        try {
            assertEquals(VmFetch(2, 200), MuseApi.fetchVms("device-access", root))
        } finally {
            server.stop(0)
        }
        assertEquals("Bearer device-access", headers["authorization"])
        assertEquals("1.0.0", headers["x-api-version"])
        assertTrue(headers["user-agent"]!!.startsWith("muse-manus-gadgetlink/"))
    }

    @Test
    fun fetchVmsReportsRejectionsAndGarbage() {
        for ((status, body) in listOf(
            401 to """{"error":"nope"}""",
            200 to """{"error_title":"bad","vm_list":[]}""",
            200 to """{"backend_error_code":3}""",
            200 to """{"vm_list":"x"}""",
            200 to "[1]",
            200 to "not json",
        )) {
            val (server, root) = serve(status, body)
            try {
                val fetch = MuseApi.fetchVms("t", root)
                assertEquals(body, 0, fetch.count)
                assertEquals(body, status, fetch.status)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun fetchVmsRefusesABodyBeyondTheCap() {
        // O api_url_v2 vem do celular que está pareando: um host que despeja gigabytes
        // não pode estourar a memória do app. Uma resposta válida, mas inchada, é recusada.
        val padding = "x".repeat(MuseApi.MAX_BODY_BYTES + 1)
        val (server, root) = serve(200, """{"vm_list":[{"vm_ws_url":"wss://a","vm_auth_token":"t"}],"pad":"$padding"}""")
        try {
            assertEquals(VmFetch(0, 200), MuseApi.fetchVms("t", root))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchVmsAcceptsABodyJustUnderTheCap() {
        val padding = "x".repeat(MuseApi.MAX_BODY_BYTES - 200)
        val (server, root) = serve(200, """{"vm_list":[{"vm_ws_url":"wss://a","vm_auth_token":"t"}],"pad":"$padding"}""")
        try {
            assertEquals(VmFetch(1, 200), MuseApi.fetchVms("t", root))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchVmsGivesUpOnAServerThatDripsTheBody() {
        // Um byte por vez, mais devagar que o prazo total: cada read cabe no readTimeout,
        // mas a thread de provisionamento não pode ficar presa para sempre.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fetch_vms") { ex ->
            ex.sendResponseHeaders(200, 40)
            runCatching {
                ex.responseBody.use { out ->
                    repeat(40) {
                        out.write('x'.code)
                        out.flush()
                        Thread.sleep(150)
                    }
                }
            }
        }
        server.start()
        try {
            val started = System.nanoTime()
            val fetch = MuseApi.fetchVms("t", "http://127.0.0.1:${server.address.port}", totalTimeoutMs = 600)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(VmFetch(0, 200), fetch)
            assertTrue("gave up after $tookMs ms", tookMs < 2_500)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchVmsWithoutAServerHasNoStatus() {
        assertEquals(VmFetch(0, null), MuseApi.fetchVms("t", "http://127.0.0.1:1"))
    }

    @Test
    fun fetchVmsDoesNotFollowRedirects() {
        // O bearer não pode ir parar em outro host via 302: a resposta é o próprio
        // 302 com contagem 0, e o servidor de destino nunca é chamado.
        var targetHits = 0
        var targetAuth: String? = null
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/fetch_vms") { ex ->
            targetHits++
            targetAuth = ex.requestHeaders.getFirst("Authorization")
            val bytes = """{"vm_list":[]}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        target.start()
        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        redirector.createContext("/fetch_vms") { ex ->
            ex.responseHeaders.set("Location", "http://127.0.0.1:${target.address.port}/fetch_vms")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        redirector.start()
        try {
            val fetch = MuseApi.fetchVms("device-access", "http://127.0.0.1:${redirector.address.port}")
            assertEquals(VmFetch(0, 302), fetch)
            assertEquals(0, targetHits)
            assertNull(targetAuth)
        } finally {
            redirector.stop(0)
            target.stop(0)
        }
    }

    // --- Provisionamento ------------------------------------------------------------

    private val credentials = Credentials("acc", "ref", "someone", "http://insecure", "https://api.example", "noise.example")

    @Test
    fun provisionVerifiesThenSavesTheSdkPairingRecord() {
        val state = GadgetState(tmp.root)
        var root = ""
        val provisioner = Provisioner(state, validToken, fetch = { token, r -> root = r; assertEquals("acc", token); VmFetch(1, 200) }, nowSeconds = { 1234L })
        var committed = false
        provisioner.provision(credentials) { save -> committed = true; save() }
        assertTrue(committed)
        assertEquals("https://api.example", root)
        val record = state.pairing()!!
        assertEquals("acc", record.s("access_token"))
        assertEquals("ref", record.s("refresh_token"))
        assertEquals("device", record.s("token_type"))
        assertEquals("someone", record.s("username"))
        assertEquals("", record.s("api_url")) // só https passa, igual ao SDK
        assertEquals("https://api.example", record.s("api_url_v2"))
        assertEquals("noise.example", record.s("noise_host"))
        assertEquals(1234L, (record["access_token_saved_at"] as JsonPrimitive).long)
        assertEquals(validToken.take(12), record.s("sdk_token_hint"))
    }

    @Test
    fun provisionWithUserinfoApiUrlFallsBackToTheOfficialApi() {
        // "https://api.example@host-malicioso/" passaria num startsWith("https://")
        // e o bearer iria para o host errado; userinfo na URL a torna inutilizável
        // e o root cai para a API oficial, como já acontece com http puro.
        val state = GadgetState(tmp.root)
        var root = ""
        val evil = Credentials("acc", "ref", "someone", "", "https://api.example@evil.example", "noise.example")
        Provisioner(state, null, fetch = { _, r -> root = r; VmFetch(1, 200) }).provision(evil) { save -> save() }
        assertEquals(MuseApi.API_BASE, root)
        assertEquals("", state.pairing()!!.s("api_url_v2"))
    }

    @Test
    fun provisionWithNoVmsIsAuthFailedAndSavesNothing() {
        val state = GadgetState(tmp.root)
        val err = assertThrows(ProvisionFailed::class.java) {
            Provisioner(state, null, fetch = { _, _ -> VmFetch(0, 401) }).provision(credentials) { save -> save() }
        }
        assertEquals("auth_failed", err.status)
        assertNull(state.pairing())
    }

    @Test
    fun provisionRefusedByTheSessionIsErrorStorage() {
        val state = GadgetState(tmp.root)
        val err = assertThrows(ProvisionFailed::class.java) {
            Provisioner(state, null, fetch = { _, _ -> VmFetch(1, 200) }).provision(credentials) { false }
        }
        assertEquals("error_storage", err.status)
        assertNull(state.pairing())
    }

    @Test
    fun provisionThatCannotWriteIsErrorStorage() {
        val blocker = tmp.newFile("not-a-dir")
        val err = assertThrows(ProvisionFailed::class.java) {
            Provisioner(GadgetState(File(blocker, "state")), null, fetch = { _, _ -> VmFetch(1, 200) })
                .provision(credentials) { save -> save() }
        }
        assertEquals("error_storage", err.status)
    }
}
