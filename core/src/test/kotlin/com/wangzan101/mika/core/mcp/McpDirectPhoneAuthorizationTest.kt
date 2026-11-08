package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteOwnership
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Wire-through security check: an untrusted requester must never reach the phone host. */
class McpDirectPhoneAuthorizationTest {
    private class FakePhone : DirectPhoneHost {
        val owners = mutableListOf<String>()
        override suspend fun call(owner: String, name: String, args: JsonObject): JsonObject {
            owners += owner
            return buildJsonObject { put("ok", true) }
        }
    }

    private val tasks = FakeRemoteTasks()
    private val phone = FakePhone()
    private val endpoints = RemoteEndpoints(tasks, "test", directPhone = phone)
    private val gate = RemoteHost(tasks, { TOKEN }, 0, false, "test", endpoints = endpoints)

    private fun request(token: String?, origin: String? = null, name: String = "phone_observe"): HttpRequest {
        val json = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"""" + name +
            """","arguments":{}}}"""
        val headers = mutableMapOf("content-type" to "application/json")
        token?.let { headers["authorization"] = "Bearer " + it }
        origin?.let { headers["origin"] = it }
        return HttpRequest("POST", "/mcp", headers, json.toByteArray(),
            InetAddress.getByName("192.168.10.8"))
    }

    @Test fun unauthenticatedAndBrowserCallsNeverReachDirectExecutor() {
        assertEquals(401, gate.handle(request(null)).status)
        assertEquals(401, gate.handle(request("incorrect")).status)
        assertEquals(403, gate.handle(request(TOKEN, "https://evil.example")).status)
        assertTrue(phone.owners.isEmpty())
    }

    @Test fun authenticatedToolRunsWithDerivedOwner() {
        val response = gate.handle(request(TOKEN))
        assertEquals(200, response.status)
        assertEquals(listOf(RemoteOwnership.of(TOKEN)), phone.owners)
    }

    @Test fun directToolsAreHiddenWhenDeviceAccessIsDeniedForRelay() {
        val passed = request(TOKEN)
        val secureReq = passed.ownedBy(RemoteOwnership.of(TOKEN))
        val response = endpoints.handle(secureReq, deviceAccess = false)
        assertEquals(200, response.status)
        assertTrue(phone.owners.isEmpty())
        assertTrue(String(response.body).contains("Unknown tool"))
    }

    private companion object { const val TOKEN = "testing-secure-bearer-token-0123456789" }
}
