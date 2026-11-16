package com.darlisonfarich.gadgetlink

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * A lista de VMs e a renovação do token do device (musegadget/muse_api.py: fetch_vms_with_status e
 * refresh_device_token), contra um servidor HTTP local.
 */
class MuseApiClientTest {
    private val servers = ArrayList<HttpServer>()

    @After fun stop() = servers.forEach { it.stop(0) }

    private class Seen(val method: String, val headers: Map<String, String>, val body: String)

    private fun serve(path: String, status: Int, body: String, seen: MutableList<Seen> = ArrayList()): Pair<String, MutableList<Seen>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { ex ->
            seen += Seen(ex.requestMethod, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, ex.requestBody.readBytes().decodeToString())
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}" to seen
    }

    // ---- lista de VMs ----------------------------------------------------------------------------
    private val vmBody = """
        {"vm_list":[
          {"vm_ws_url":"wss://a.example/ws","vm_auth_token":"tok-a","vm_name":"Alpha","vm_id":"id-a","default":true},
          {"vm_url":"https://b.example","vm_auth_token":"tok-b","vm_name":"Beta"},
          {"vm_ws_url":"wss://c.example","vm_auth_token":""},
          {"vm_auth_token":"tok-d"},
          "not-an-object",
          {"vm_ws_url":"wss://e.example","vm_auth_token":"tok-e","vm_id":"id-e","default":0}
        ]}
    """.trimIndent()

    @Test
    fun theVmListKeepsOnlyUsableEntriesWithTheirFields() {
        val (root, seen) = serve("/fetch_vms", 200, vmBody)
        val list = MuseApi.fetchVmList("device-access", root)
        assertEquals(200, list.status)
        assertEquals(
            listOf(
                Vm("wss://a.example/ws", "tok-a", "Alpha", "id-a", true),
                Vm("https://b.example", "tok-b", "Beta", "", false),
                Vm("wss://e.example", "tok-e", "", "id-e", false),
            ),
            list.vms,
        )
        assertEquals("GET", seen.single().method)
        assertEquals("Bearer device-access", seen.single().headers["authorization"])
        assertEquals("1.0.0", seen.single().headers["x-api-version"])
    }

    @Test
    fun theWebSocketUrlWinsOverTheVmUrlAndAnEmptyOneFallsBack() {
        val (root, _) = serve("/fetch_vms", 200, """{"vm_list":[{"vm_ws_url":"","vm_url":"https://fallback","vm_auth_token":"t"},{"vm_ws_url":"wss://ws","vm_url":"https://http","vm_auth_token":"t"}]}""")
        assertEquals(listOf("https://fallback", "wss://ws"), MuseApi.fetchVmList("t", root).vms.map { it.vmUrl })
    }

    @Test
    fun theVmListReportsErrorsAndGarbageAsEmptyWithTheStatus() {
        for (body in listOf("""{"error_title":"x","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", """{"backend_error_code":5,"vm_list":[]}""", "[]", "garbage", """{"vm_list":"no"}""", """{"other":1}""")) {
            val (root, _) = serve("/fetch_vms", 200, body)
            val list = MuseApi.fetchVmList("t", root)
            assertEquals(body, VmList(emptyList(), 200), list)
        }
        val (denied, _) = serve("/fetch_vms", 401, "{}")
        assertEquals(VmList(emptyList(), 401), MuseApi.fetchVmList("t", denied))
        assertEquals(VmList(emptyList(), null), MuseApi.fetchVmList("t", "http://127.0.0.1:1"))
    }

    @Test
    fun countingVmsStillAgreesWithTheListLength() {
        assertEquals(3, MuseApi.countVms(vmBody))
        assertEquals(3, MuseApi.parseVmList(vmBody).size)
    }

    // ---- nomes dos campos (5.0.1) -----------------------------------------------------------------
    private val extrasBody = """
        {"vm_list":[
          {"vm_ws_url":"wss://VAL-url","vm_auth_token":"VAL-tok","vm_name":"VAL-name","vm_id":"VAL-id","default":true,
           "capabilities":{"voice":"VAL-cap"},"voice_enabled":false,"region":"VAL-region"},
          {"vm_auth_token":"VAL-dropped","extra_on_dropped":"VAL-x"},
          {"vm_url":"https://VAL-b","vm_auth_token":"VAL-tok-b","vm_name":"VAL-b","vm_id":"VAL-id-b","default":false}
        ]}
    """.trimIndent()

    @Test
    fun entryFieldNamesListExactlyTheRawKeysSortedAndAlignedWithTheParsedList() {
        val names = MuseApi.parseVmEntryFieldNames(extrasBody)
        assertEquals(MuseApi.parseVmList(extrasBody).size, names.size)
        assertEquals(
            listOf(
                listOf("capabilities", "default", "region", "vm_auth_token", "vm_id", "vm_name", "vm_ws_url", "voice_enabled"),
                listOf("default", "vm_auth_token", "vm_id", "vm_name", "vm_url"),
            ),
            names,
        )
        assertTrue("nenhum valor sai, só nomes", names.flatten().none { it.contains("VAL") })
    }

    @Test
    fun entriesWithoutExtrasReturnOnlyTheKnownFields() {
        assertEquals(
            listOf(
                listOf("default", "vm_auth_token", "vm_id", "vm_name", "vm_ws_url"),
                listOf("vm_auth_token", "vm_name", "vm_url"),
                listOf("default", "vm_auth_token", "vm_id", "vm_ws_url"),
            ),
            MuseApi.parseVmEntryFieldNames(vmBody),
        )
        for (body in listOf("""{"error_title":"x","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", "[]", "garbage", """{"vm_list":"no"}""")) {
            assertEquals(body, emptyList<List<String>>(), MuseApi.parseVmEntryFieldNames(body))
        }
    }

    @Test
    fun unsafeOrTooManyFieldNamesAreRedactedAndCapped() {
        val odd = """{"vm_list":[{"vm_url":"u","vm_auth_token":"t","has space":1,"${"k".repeat(41)}":2,"ok.name-1":3}]}"""
        assertEquals(listOf(listOf("?", "?", "ok.name-1", "vm_auth_token", "vm_url")), MuseApi.parseVmEntryFieldNames(odd))
        val many = (1..40).joinToString(",") { "\"f%02d\":0".format(it) }
        val capped = MuseApi.parseVmEntryFieldNames("""{"vm_list":[{"vm_url":"u","vm_auth_token":"t",$many}]}""").single()
        assertEquals(MuseApi.MAX_FIELD_NAMES, capped.size)
        assertEquals("+${42 - (MuseApi.MAX_FIELD_NAMES - 1)}", capped.last())
    }

    @Test
    fun theDetailedFetchMakesOneCallAndKeepsTheSameVmList() {
        val (root, seen) = serve("/fetch_vms", 200, extrasBody)
        val detailed = MuseApi.fetchVmListWithFields("device-access", root)
        assertEquals(1, seen.size)
        assertEquals(VmList(MuseApi.parseVmList(extrasBody), 200), detailed.list)
        assertEquals(MuseApi.parseVmEntryFieldNames(extrasBody), detailed.fieldNames)
        val (denied, _) = serve("/fetch_vms", 401, "{}")
        assertEquals(VmListWithFields(VmList(emptyList(), 401), emptyList(), emptyList()), MuseApi.fetchVmListWithFields("t", denied))
    }

    // ---- 5.0.2: valores dos campos de metadado (só a whitelist) -------------------------------
    private val metaBody = """
        {"vm_list":[
          {"vm_ws_url":"https://VAL-a","vm_auth_token":"VAL-tok-a","vm_name":"VAL-a","vm_id":"VAL-id-a","default":true,
           "vm_type":"VAL-type-a","vm_state":"VAL-state-a","vm_ingress_kind":"VAL-ingress-a","vm_notary_tokens":["VAL-notary"],"region":"VAL-region"},
          {"vm_url":"https://VAL-b","vm_auth_token":"VAL-tok-b","vm_name":"VAL-b","vm_id":"VAL-id-b","default":false}
        ]}
    """.trimIndent()

    @Test
    fun entryMetaListsOnlyTheWhitelistedValuesAlignedWithTheParsedList() {
        val meta = MuseApi.parseVmEntryMeta(metaBody)
        assertEquals(MuseApi.parseVmList(metaBody).size, meta.size)
        assertEquals(
            listOf(
                mapOf("vm_ingress_kind" to "VAL-ingress-a", "vm_state" to "VAL-state-a", "vm_type" to "VAL-type-a"),
                emptyMap(),
            ),
            meta,
        )
        // Nenhum outro valor sai: nem token, nem notário, nem URL, nem id, nem nome, nem região.
        val shown = meta.flatMap { it.values }
        assertTrue("só os três valores da whitelist", shown.size == 3 && shown.none { it.contains("VAL-tok") || it.contains("VAL-notary") || it.contains("VAL-id") })
    }

    @Test
    fun unsafeOrNonScalarMetaValuesAreRedacted() {
        val odd = """{"vm_list":[{"vm_url":"u","vm_auth_token":"t",
          "vm_type":"has space","vm_state":{"nested":1},"vm_ingress_kind":"${"k".repeat(41)}","vm_name":"n"}]}"""
        assertEquals(listOf(mapOf("vm_ingress_kind" to "?", "vm_state" to "?", "vm_type" to "?")), MuseApi.parseVmEntryMeta(odd))
        assertEquals(listOf(emptyMap<String, String>()), MuseApi.parseVmEntryMeta("""{"vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}"""))
        assertEquals(listOf(mapOf("vm_type" to "gadget")), MuseApi.parseVmEntryMeta("""{"vm_list":[{"vm_url":"u","vm_auth_token":"t","vm_type":"gadget"}]}"""))
        for (body in listOf("""{"error_title":"x","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", "[]", "garbage", """{"vm_list":"no"}""")) {
            assertEquals(body, emptyList<Map<String, String>>(), MuseApi.parseVmEntryMeta(body))
        }
    }

    @Test
    fun theDetailedFetchCarriesMetaToo() {
        val (root, seen) = serve("/fetch_vms", 200, metaBody)
        val detailed = MuseApi.fetchVmListWithFields("device-access", root)
        assertEquals(1, seen.size)
        assertEquals(VmList(MuseApi.parseVmList(metaBody), 200), detailed.list)
        assertEquals(MuseApi.parseVmEntryMeta(metaBody), detailed.meta)
        val (denied, _) = serve("/fetch_vms", 401, "{}")
        assertEquals(VmListWithFields(VmList(emptyList(), 401), emptyList(), emptyList()), MuseApi.fetchVmListWithFields("t", denied))
    }

    // ---- renovação do token -----------------------------------------------------------------------
    @Test
    fun aRefreshSendsTheRefreshTokenAsBearerAndReturnsTheNewPair() {
        val (root, seen) = serve("/device_token/refresh", 200, """{"access_token":"new-a","refresh_token":"new-r"}""")
        val result = MuseApi.refreshDeviceToken("old-refresh", "homelink-abc123", root, "mgst_secret")
        assertEquals(RefreshResult(TokenPair("new-a", "new-r"), 200), result)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("Bearer hatch_refresh:old-refresh", request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("homelink-abc123", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("mgst_secret", body["sdk_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun theRefreshPrefixIsNeverDoubled() {
        for ((given, wire) in listOf("hatch_refresh:abc" to "abc", "x:y:z" to "z", "plain" to "plain")) {
            val (root, seen) = serve("/device_token/refresh", 200, """{"access_token":"a","refresh_token":"r"}""")
            MuseApi.refreshDeviceToken(given, "dev", root, null)
            assertEquals(given, "Bearer hatch_refresh:$wire", seen.single().headers["authorization"])
        }
    }

    @Test
    fun theSdkTokenIsOmittedWhenThereIsNone() {
        for (token in listOf(null, "")) {
            val (root, seen) = serve("/device_token/refresh", 200, """{"access_token":"a","refresh_token":"r"}""")
            MuseApi.refreshDeviceToken("r", "dev", root, token)
            assertEquals("{\"device_id\":\"dev\"}", seen.single().body)
        }
    }

    @Test
    fun aNestedPayloadIsUnwrapped() {
        val (root, _) = serve("/device_token/refresh", 200, """{"payload":{"access_token":"na","refresh_token":"nr"}}""")
        assertEquals(TokenPair("na", "nr"), MuseApi.refreshDeviceToken("r", "d", root, null).tokens)
    }

    @Test
    fun aRefreshThatFailsReportsTheStatusAndNeverTokens() {
        val (unauthorized, _) = serve("/device_token/refresh", 401, "{}")
        assertEquals(RefreshResult(null, 401), MuseApi.refreshDeviceToken("r", "d", unauthorized, null))
        val (broken, _) = serve("/device_token/refresh", 500, "{}")
        assertEquals(RefreshResult(null, 500), MuseApi.refreshDeviceToken("r", "d", broken, null))
        for (body in listOf("""{"access_token":"a"}""", """{"refresh_token":"r"}""", """{"access_token":"","refresh_token":"r"}""", """{"access_token":"a","refresh_token":""}""", """{"access_token":5,"refresh_token":"r"}""", "[]", "garbage", """{"payload":"x"}""")) {
            val (root, _) = serve("/device_token/refresh", 200, body)
            assertEquals(body, RefreshResult(null, 200), MuseApi.refreshDeviceToken("r", "d", root, null))
        }
        assertEquals(RefreshResult(null, null), MuseApi.refreshDeviceToken("r", "d", "http://127.0.0.1:1", null))
    }

    @Test
    fun aRefreshNeverFollowsARedirectWithTheSecretBearer() {
        var reached = false
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/device_token/refresh") { ex -> reached = true; ex.sendResponseHeaders(200, -1); ex.close() }
        target.start()
        servers += target
        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        redirector.createContext("/device_token/refresh") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/device_token/refresh")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        redirector.start()
        servers += redirector
        val result = MuseApi.refreshDeviceToken("secret", "d", "http://127.0.0.1:${redirector.address.port}", null)
        assertEquals(RefreshResult(null, 307), result)
        assertTrue("o bearer não pode ir para outro host", !reached)
    }

    @Test
    fun aHugeRefreshBodyIsRefusedLikeTheVmListOne() {
        val (root, _) = serve("/device_token/refresh", 200, """{"access_token":"a","refresh_token":"${"r".repeat(MuseApi.MAX_BODY_BYTES)}"}""")
        assertEquals(RefreshResult(null, 200), MuseApi.refreshDeviceToken("r", "d", root, null))
        assertNotNull(TokenPair("a", "b"))
        assertNull(RefreshResult(null, 1).tokens)
    }
}
