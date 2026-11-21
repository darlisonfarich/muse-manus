package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandCatalog
import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.mcp.GadgetPhoneLease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class GadgetPhoneActionsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun onlyRegisteredDirectControlIsAnnounced() {
        val calls = mutableListOf<String>()
        val actions = GadgetPhoneActions.specs({ "active" }) { name, _ ->
            calls += name
            json("""{"ok":true,"elements":["secret"],"text":"secret"}""")
        }
        val catalog = CommandCatalog.museManus(emptyList(), controlCommands = actions)
        val tap = catalog.find("phone.tap")!!
        assertTrue(tap.writes)
        assertNull(catalog.find("phone.unlock"))
        assertNull(catalog.find("phone.approval_issue"))
        assertNull(catalog.find("phone.run_task"))
        val args = json("""{"request_id":"abcdefgh","x":1,"y":2}""")
        assertEquals(json("""{"ok":true}"""), (tap.handler.run(args) as CommandResult.Ok).payload)
        assertEquals(listOf("phone_tap"), calls)
        assertFalse(catalog.find("phone.reconcile")!!.writes)
        assertTrue(CommandCatalog.registerBytes(catalog, "device", "POCO", "test") < 256 * 1024)
    }

    @Test fun mapsNestedFailuresWithoutEchoingPrivateData() {
        fun code(raw: String) = (GadgetPhoneActions.result("phone.run_batch", json(raw)) as CommandResult.Err)
        assertEquals(ErrorCode.LOCAL_GATE_DENIED, code("""{"ok":false,"results":[{"ok":false,"error":"needs_user"}]}""").code)
        assertEquals(ErrorCode.APP_IN_USE_BY_USER, code("""{"ok":false,"error":"wait_timeout","cause":"app_in_use_by_user"}""").code)
        assertEquals("reconcile_request_id", code("""{"ok":false,"error":"delivery_unknown"}""").detail)
        assertNull(code("""{"ok":false,"error":"private user content"}""").detail)
    }

    @Test fun journalOwnerIsStableButPermissionIsPerRegistration() {
        fun lease(account: String, vm: String) = GadgetPhoneLease("device", "host", account, vm)
        val first = lease("a", "vm")
        val reconnect = lease("a", "vm")
        first.connected = true
        assertFalse(reconnect.connected)
        assertEquals(first.owner, reconnect.owner)
        assertNotEquals(first.owner, lease("a", "other").owner)
        assertNotEquals(first.owner, lease("b", "vm").owner)
    }
}
