package com.darlisonfarich.gadgetlink.commands

import com.darlisonfarich.gadgetlink.noise.LinkWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogTest {
    /** O tools/list REAL do servidor mika (tools/mika-dump), golden. */
    private val mika: JsonArray by lazy {
        val text = javaClass.getResourceAsStream("/mika_tools.json")!!.readBytes().toString(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonArray
    }

    @Test
    fun aNormalRegisterAnnouncesOnlyTheImplementedCommands() {
        val catalog = CommandCatalog.museManus()
        assertFalse(catalog.includeStubs)
        assertEquals(
            listOf(LinkTestCommands.PING, LinkTestCommands.ECHO_VERIFY, LinkTestCommands.RESPONSE_SIZE_PROBE),
            catalog.commandsV2().keys.toList(),
        )
        assertTrue(catalog.announced.all { it.implemented })
        assertEquals(null, catalog.find("phone.tap"))
    }

    @Test
    fun theMeasurementRegisterAddsThe29MarkedStubs() {
        val catalog = CommandCatalog.museManus(includeStubs = true)
        val wire = catalog.commandsV2()
        assertEquals(32, wire.size)
        val phone = catalog.announced.filter { it.name.startsWith("phone.") }
        assertEquals(29, phone.size)
        for (c in phone) {
            assertFalse(c.name, c.implemented)
            assertTrue(c.name, c.description.startsWith("[STUB — NÃO IMPLEMENTADO] "))
            assertTrue(c.name, wire[c.name]!!.jsonObject["description"]!!.jsonPrimitive.content.startsWith("[STUB — NÃO IMPLEMENTADO] "))
        }
        assertTrue(catalog.announced.filter { it.name.startsWith("linktest.") }.all { it.implemented && !it.description.startsWith("[STUB") })
    }

    @Test
    fun everyEntryUsesTheSdkShapeAndOnlyTheClosedParameterTypes() {
        val types = setOf("string", "integer", "number", "boolean", "object", "array")
        for ((name, entry) in CommandCatalog.museManus(includeStubs = true).commandsV2()) {
            val e = entry.jsonObject
            assertEquals(name, setOf("description", "required", "optional", "timeout_ms"), e.keys)
            for (group in listOf("required", "optional")) {
                for ((p, spec) in e[group]!!.jsonObject) {
                    val s = spec.jsonObject
                    assertEquals("$name.$p", setOf("type", "description"), s.keys)
                    assertTrue("$name.$p", s["type"]!!.jsonPrimitive.content in types)
                    assertTrue("$name.$p", s["description"]!!.jsonPrimitive.content.isNotBlank())
                }
            }
            assertTrue(name, e["timeout_ms"]!!.jsonPrimitive.long in 1..(CommandSpec.MAX_TIMEOUT_MS + CommandSpec.TIMEOUT_GRACE_MS))
        }
    }

    @Test
    fun thePhoneStubsMatchThe29MikaToolsOneToOne() {
        assertEquals(29, mika.size)
        val stubs = PhoneCatalog.stubs().associateBy { it.name }
        assertEquals(29, stubs.size)
        assertEquals(mika.map { it.jsonObject["name"]!!.jsonPrimitive.content }, PhoneCatalog.MIKA.values.toList())
        for (tool in mika) {
            val t = tool.jsonObject
            val mikaName = t["name"]!!.jsonPrimitive.content
            val gadget = PhoneCatalog.MIKA.entries.single { it.value == mikaName }.key
            val stub = stubs[gadget]!!
            assertEquals(mikaName, CommandSpec.STUB_PREFIX + t["description"]!!.jsonPrimitive.content, stub.description)
            val schema = t["inputSchema"]!!.jsonObject
            val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals(mikaName, required, stub.required.keys)
            assertEquals(mikaName, props.keys - required, stub.optional.keys)
            for ((p, ps) in props) {
                val spec = stub.required[p] ?: stub.optional[p]!!
                assertEquals("$mikaName.$p", ps.jsonObject["type"]!!.jsonPrimitive.content, spec.type.wire)
                ps.jsonObject["description"]?.let { assertTrue("$mikaName.$p", spec.description.startsWith(it.jsonPrimitive.content)) }
            }
            assertEquals(mikaName, !t["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean, stub.writes)
        }
    }

    @Test
    fun theRegisterFitsTheBudgetWithAndWithoutStubs() {
        val node = "homelink-000000"
        val name = "23078PND5G (Muse Manus)"
        val full = CommandCatalog.registerBytes(CommandCatalog.museManus(includeStubs = true), node, name, "0.0.0-measure")
        val implemented = CommandCatalog.registerBytes(CommandCatalog.museManus(), node, name, "0.0.0-measure")
        val commandsFull = LinkWire.encodeMessage(CommandCatalog.museManus(includeStubs = true).commandsV2()).size - 4
        val commandsImpl = LinkWire.encodeMessage(CommandCatalog.museManus().commandsV2()).size - 4
        println("MEASURE link.register full (3 linktest + 29 stubs) = $full bytes; commands_v2 alone = $commandsFull bytes; budget ${LinkWire.MAX_CONTROL_MESSAGE_BYTES}")
        println("MEASURE link.register implemented only (3 linktest) = $implemented bytes; commands_v2 alone = $commandsImpl bytes")
        assertTrue(full < LinkWire.MAX_CONTROL_MESSAGE_BYTES)
        assertTrue(implemented < full)
    }

    @Test
    fun duplicateNamesAndReservedPrefixesAreRefused() {
        val ping = LinkTestCommands.ping { 0 }
        assertTrue(runCatching { CommandCatalog(listOf(ping, ping)) }.isFailure)
        for (bad in listOf("system.run", "file.read", "device.health", "link.x", "Phone.tap", "phone", "phone.Tap")) {
            assertTrue(bad, runCatching { CommandSpec(bad, "x", timeoutMs = 1, writes = false, implemented = true) { CommandResult.Ok(JsonObject(emptyMap())) } }.isFailure)
        }
    }
}
