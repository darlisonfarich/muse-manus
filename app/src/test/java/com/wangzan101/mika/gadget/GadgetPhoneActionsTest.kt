package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandCatalog
import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.darlisonfarich.gadgetlink.commands.ParamType
import com.wangzan101.mika.mcp.GadgetPhoneLease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun aBatchStoppedAfterSuccessfulStepsKeepsItsTopLevelCause() {
        fun err(raw: String) = GadgetPhoneActions.result("phone.run_batch", json(raw)) as CommandResult.Err
        // Nothing ran yet: the top-level cause wins over an earlier successful step.
        assertEquals(ErrorCode.TIMEOUT, err("""{"ok":false,"error":"wait_timeout","executed":false,"results":[]}""").code)
        // Some steps ran: Muse must reconcile by request_id instead of retrying the batch.
        val partial = err("""{"ok":false,"error":"wait_timeout","executed":true,"completed":1,"results":[{"ok":true}]}""")
        assertEquals(ErrorCode.EXECUTOR_ERROR, partial.code)
        assertEquals("reconcile_request_id", partial.detail)
        val refused = err("""{"ok":false,"completed":1,"results":[{"ok":true},{"ok":false,"error":"app_in_use_by_user"}]}""")
        assertEquals("reconcile_request_id", refused.detail)
    }

    private val batch = """[{"name":"phone.open_app","arguments":{"package":"a.b","cold":true}},{"name":"phone.tap","arguments":{"x":1,"y":2}},{"name":"phone.type_text","arguments":{"text":"hi"}}]"""
    private val internalBatch = """[{"name":"phone_launch_app","arguments":{"package":"a.b","cold":true}},{"name":"phone_tap","arguments":{"x":1,"y":2}},{"name":"phone_type","arguments":{"text":"hi"}}]"""

    private fun recordingCatalog(calls: MutableList<Pair<String, JsonObject>>): CommandCatalog {
        val actions = GadgetPhoneActions.specs({ "active" }) { name, args ->
            calls += name to args
            json("""{"ok":true}""")
        }
        return CommandCatalog.museManus(emptyList(), controlCommands = actions)
    }

    @Test fun aBatchWorksWithTheNamesTheCatalogAnnounces() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val run = recordingCatalog(calls).find("phone.run_batch")!!
        run.handler.run(json("""{"request_id":"abcdefgh","commands":$batch}"""))
        assertEquals("phone_run", calls.single().first)
        assertEquals(json("""{"request_id":"abcdefgh","commands":$internalBatch}"""), calls.single().second)
    }

    @Test fun aBatchThatAlreadyUsesInternalNamesKeepsWorking() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        recordingCatalog(calls).find("phone.run_batch")!!.handler.run(json("""{"request_id":"abcdefgh","commands":$internalBatch}"""))
        assertEquals(json("""{"request_id":"abcdefgh","commands":$internalBatch}"""), calls.single().second)
    }

    @Test fun reconcilingABatchSeesTheSameCommandsTheBatchWasRunWith() {
        // The host fingerprints the commands it ran; a reconcile with the caller's own names must match it.
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val catalog = recordingCatalog(calls)
        catalog.find("phone.run_batch")!!.handler.run(json("""{"request_id":"abcdefgh","commands":$batch}"""))
        catalog.find("phone.reconcile")!!.handler.run(json("""{"request_id":"abcdefgh","kind":"batch","commands":$batch}"""))
        assertEquals("phone_reconcile", calls[1].first)
        assertEquals(calls[0].second["commands"], calls[1].second["commands"])
        assertEquals(json("""{"commands":$internalBatch}""")["commands"], calls[1].second["commands"])
        // An action reconcile has no commands and is untouched.
        catalog.find("phone.reconcile")!!.handler.run(json("""{"request_id":"abcdefgh","kind":"action"}"""))
        assertEquals(json("""{"request_id":"abcdefgh","kind":"action"}"""), calls[2].second)
    }

    @Test fun anInvalidBatchIsNamedAsSuchInsteadOfAMuteExecutorError() {
        fun err(raw: String) = GadgetPhoneActions.result("phone.run_batch", json(raw)) as CommandResult.Err
        val invalid = err("""{"ok":false,"error":"invalid_commands","executed":false}""")
        assertEquals(ErrorCode.EXECUTOR_ERROR, invalid.code)
        assertEquals("invalid_commands", invalid.detail)
        // Any other internal code that used to fall into the silent branch keeps its identity too.
        assertEquals("surface_check_failed", err("""{"ok":false,"error":"surface_check_failed","executed":false}""").detail)
        assertEquals("request_id_required", err("""{"ok":false,"error":"request_id_required","executed":false}""").detail)
        // The failing step of a batch that stopped before anything ran says why.
        assertEquals("ref_not_found", err("""{"ok":false,"completed":0,"results":[{"ok":false,"error":"ref_not_found"}]}""").detail)
    }

    @Test fun openingAnAppDeclaresWhichDisplayReceivesIt() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val open = recordingCatalog(calls).find("phone.open_app")!!
        val target = open.optional["target"]!!
        assertEquals(ParamType.STRING, target.type)
        assertTrue(target.description.contains("main") && target.description.contains("virtual"))
        assertTrue(open.description.contains("main screen"))
        // The default is still the virtual display, and an explicit main target reaches the host untouched.
        assertTrue(open.description.contains("virtual display"))
        open.handler.run(json("""{"request_id":"abcdefgh","package":"com.whatsapp","target":"main"}"""))
        assertEquals("phone_launch_app", calls.single().first)
        assertEquals("main", calls.single().second["target"]?.jsonPrimitive?.content)
    }

    @Test fun aStaleSnapshotThatSurvivedTheInternalRetriesSaysHowManyWereMade() {
        fun err(raw: String) = GadgetPhoneActions.result("phone.media", json(raw)) as CommandResult.Err
        val retried = err("""{"ok":false,"error":"stale_snapshot","executed":false,"staleRetries":4}""")
        assertEquals(ErrorCode.STALE_SNAPSHOT, retried.code)
        assertEquals("retries_4", retried.detail)
        assertNull(err("""{"ok":false,"error":"stale_snapshot","executed":false}""").detail)
    }

    @Test fun aLocalSurfaceRefusalIsAGateDenialWithAFixedCode() {
        fun err(raw: String) = GadgetPhoneActions.result("phone.tap", json(raw)) as CommandResult.Err
        val denied = err("""{"ok":false,"error":"app_access_denied","gateDenied":true}""")
        assertEquals(ErrorCode.LOCAL_GATE_DENIED, denied.code)
        assertEquals("app_access_denied", denied.detail)
        assertNull(err("""{"ok":false,"error":"Some Private Text","gateDenied":true}""").detail)
    }

    @Test fun controlScopeFollowsTheRegistrationNotTheSession() {
        val lease = GadgetPhoneLease("device", "host", "a", "vm")
        assertNull(lease.scope())
        lease.connected = true
        val live = lease.scope()
        assertNotNull(live)
        assertEquals(live, lease.scope())
        lease.connected = false
        assertNull(lease.scope())
        assertNotEquals(live, GadgetPhoneLease("device", "host", "a", "vm").apply { connected = true }.scope())
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
