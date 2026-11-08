package com.wangzan101.mika.core.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android device or model is needed to assert authenticated MCP dispatch. */
class McpDirectPhoneToolsTest {
    private class FakeHost : DirectPhoneHost {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(owner: String, name: String, args: JsonObject): JsonObject {
            calls += owner to name
            return buildJsonObject { put("ok", true); put("owner", owner); put("tool", name) }
        }
    }

    @Test fun phoneToolsAreHiddenWithoutAnAuthenticatedOwner() {
        val host = FakeHost()
        val none = McpTaskTools(com.wangzan101.mika.core.remote.RemoteTaskService(FakeRemoteTasks()), directPhone = host)
        assertFalse(none.has("phone_tap"))
        assertFalse(none.has("phone_observe"))
    }

    @Test fun phoneToolsAreOwnerScopedAndDoNotInvokeTheInternalAgent() = runTest {
        val host = FakeHost()
        val tools = McpTaskTools(com.wangzan101.mika.core.remote.RemoteTaskService(FakeRemoteTasks()), directPhone = host, phoneOwner = "owner-a")
        assertTrue(tools.has("phone_tap"))
        assertTrue(tools.has("phone_observe"))
        assertTrue(tools.has("phone_run"))
        val call = tools.call("phone_tap", buildJsonObject {
            put("request_id", "01b0cfee-3b7d-4924-bd36-3d0454e30662")
            put("x", 500); put("y", 500)
        })
        assertEquals("owner-a", call["owner"]?.jsonPrimitive?.content)
        assertEquals("phone_tap", call["tool"]?.jsonPrimitive?.content)
        assertEquals(listOf("owner-a" to "phone_tap"), host.calls)
        val names = tools.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("mika_run_task" in names)
        assertTrue("phone_tap" in names)
    }

    @Test fun noArbitraryCommandsAreExported() {
        val tools = McpTaskTools(com.wangzan101.mika.core.remote.RemoteTaskService(FakeRemoteTasks()), directPhone = FakeHost(), phoneOwner = "a")
        val names = tools.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertFalse("phone_system_command" in names)
        assertFalse("request_confirmation" in names)
        assertFalse("mika_root" in names)
    }
}
