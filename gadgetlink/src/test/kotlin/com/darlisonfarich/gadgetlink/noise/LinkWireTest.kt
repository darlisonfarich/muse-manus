package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.hex
import com.darlisonfarich.gadgetlink.hex as hexBytes
import com.darlisonfarich.gadgetlink.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O fio do link contra a implementação de REFERÊNCIA do SDK (musegadget/link_client.py): a mensagem
 * de controle (u32 LE + JSON compacto, com o ensure_ascii do Python), o decodificador dessas
 * mensagens, a URL do Noise, o app id e o link.register. `link_wire_vectors.json` sai de
 * gadgetlink/tools/gen_link_vectors.py.
 */
class LinkWireTest {
    private val v: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResource("link_wire_vectors.json")!!.readText()).jsonObject
    }

    @Test
    fun theLimitsAreTheSdkOnes() {
        assertEquals(v["max_inbound_message"]!!.jsonPrimitive.int, LinkWire.MAX_INBOUND_MESSAGE)
        assertEquals(v["max_control_message_bytes"]!!.jsonPrimitive.int, LinkWire.MAX_CONTROL_MESSAGE_BYTES)
        assertEquals(4 * 1024 * 1024, LinkWire.MAX_INBOUND_MESSAGE)
        assertEquals(256 * 1024, LinkWire.MAX_CONTROL_MESSAGE_BYTES)
    }

    @Test
    fun controlMessagesAreEncodedByteForByteLikeTheReference() {
        val cases = v["encode"]!!.jsonArray.map { it.jsonObject }
        assertEquals(9, cases.size)
        cases.forEachIndexed { i, c ->
            assertEquals("case $i", c.s("hex"), LinkWire.encodeMessage(c["msg"]!!.jsonObject).hex())
        }
    }

    @Test
    fun nonAsciiAndControlCharactersAreEscapedLikePythonEnsureAscii() {
        val text = "é\u007f 🚀\u0001"
        val out = LinkWire.encodeMessage(JsonObject(mapOf("s" to JsonPrimitive(text)))).copyOfRange(4, LinkWire.encodeMessage(JsonObject(mapOf("s" to JsonPrimitive(text)))).size).decodeToString()
        assertEquals("{\"s\":\"\\u00e9\\u007f\\u2028\\ud83d\\ude80\\u0001\"}", out)
        assertTrue(out.all { it.code in 0x20..0x7E })
    }

    @Test
    fun theDecoderReadsMessagesLikeTheReference() {
        val cases = v["decoder"]!!.jsonArray.map { it.jsonObject }
        assertEquals(16, cases.size)
        for (c in cases) {
            val name = c.s("name")
            val decoder = MessageDecoder()
            val feeds = c["feeds"]!!.jsonArray.map { hexBytes(it.jsonPrimitive.content) }
            val expected = c["expected"]!!.jsonArray
            assertTrue(name, feeds.size >= expected.size)
            expected.forEachIndexed { i, want ->
                if (want is JsonPrimitive && want.content == "error") {
                    assertThrows("$name feed $i", IllegalArgumentException::class.java) { decoder.feed(feeds[i]) }
                } else {
                    assertEquals("$name feed $i", want, JsonArray(decoder.feed(feeds[i])))
                }
            }
        }
    }

    @Test
    fun aDecoderThatSawAnOversizedLengthStaysBroken() {
        val decoder = MessageDecoder()
        assertThrows(IllegalArgumentException::class.java) { decoder.feed(byteArrayOf(0x01, 0x00, 0x40, 0x00)) } // 4 MiB + 1
        assertThrows(IllegalStateException::class.java) { decoder.feed(LinkWire.encodeMessage(JsonObject(emptyMap()))) }
    }

    @Test
    fun noiseUrlsAndAppIdsMatchTheReference() {
        val cases = v["urls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(18, cases.size)
        for (c in cases) {
            val label = "${c.s("host")} ${c.s("vm_id")} ${c["app_id"]}"
            val app = c["app_id"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val want = c["url"]!!
            if (want is JsonNull) {
                assertThrows(label, IllegalArgumentException::class.java) { LinkWire.noiseUrl(c.s("host"), c.s("vm_id"), app) }
            } else {
                assertEquals(label, want.jsonPrimitive.content, LinkWire.noiseUrl(c.s("host"), c.s("vm_id"), app))
            }
        }
    }

    @Test
    fun theHostOfTheTunnelMustBeAPlainHostAndPort() {
        // Não vem da referência: o noise_host vem do celular que pareou, e vira parte de uma URL.
        for (bad in listOf("", "evil.com/x", "a@b.com", "a b", "a.com?x=1", "a.com#f", "http://a.com", "a.com:99999", "a.com:", ":80", "-a.com", "a.com/", "a..b.com", "[::1]")) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { LinkWire.noiseUrl(bad, "vm") }
        }
        for (good in listOf("hatch.metaaivm.com", "a.b-c.example.org:8443", "localhost", "10.0.0.7:1")) {
            assertTrue(good, LinkWire.noiseUrl(good, "vm").startsWith("wss://$good/v1/noise?vm_id=vm"))
        }
    }

    @Test
    fun linkRegisterMatchesTheReferenceByteForByte() {
        val cases = v["register"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, cases.size)
        for (c in cases) {
            val device = DeviceDescription(c.s("node_id"), c.s("display_name"), c.s("version"), c["commands"]!!.jsonObject)
            assertEquals(c.s("display_name"), c.s("hex"), LinkWire.encodeMessage(device.registerMessage(c.s("id"))).hex())
        }
    }

    @Test
    fun theDeviceNeverClaimsTheLinkFamilyOrAnyCommands() {
        val params = DeviceDescription("homelink-abc123", "Celular", "1").registerParams()
        assertEquals("homehub", params["device_family"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), params["commands_v2"])
        assertEquals(JsonPrimitive(false), params["is_wakeup_supported"])
        val custom = DeviceDescription("n", "d", "v", platform = "android", modelId = "poco").registerParams()
        assertEquals("android", custom["platform"]!!.jsonPrimitive.content)
        assertEquals("poco", custom["model_id"]!!.jsonPrimitive.content)
    }

    @Suppress("unused") private fun unusedKeepsImports(e: JsonElement? = null) = e
}
