package com.wangzan101.mika.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.remote.RemoteOwnership
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.McpSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class GadgetDirectSessionTest {
    @Test
    fun reusesOnlyTheConfiguredDirectOwnerAndCoexistsWithMcpReads() = runBlocking<Unit> {
        DeviceSupport.shell()
        DeviceSupport.service()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gadget-session-test").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = McpSettings(directory, AesGcmKeyCipher { key }).apply { setEnabled(true) }
        val host = AndroidDirectPhoneHost(context, settings)
        val args = JsonObject(emptyMap())
        try {
            assertNull(host.gadgetReadScope())
            assertEquals(ErrorCode.UNAVAILABLE, runCatching { host.readForGadget(false, false) }
                .exceptionOrNull().let { (it as CommandException).code })
            assertNull(settings.existingToken())
            assertTrue(host.call(RemoteOwnership.USB, "phone_observe", args).getValue("ok").jsonPrimitive.boolean)
            assertNull(host.gadgetReadScope())

            val owner = RemoteOwnership.of(settings.token())
            assertFalse(host.call(owner, "phone_status", args).getValue("active").jsonPrimitive.boolean)
            assertTrue(host.call(owner, "phone_observe", args).getValue("ok").jsonPrimitive.boolean)
            assertNotNull(host.gadgetReadScope())
            val mcp = async { host.call(owner, "phone_observe", args) }
            val gadget = async { host.readForGadget(false, true) }
            assertTrue(mcp.await().getValue("ok").jsonPrimitive.boolean)
            assertFalse(gadget.await().getValue("hasWindow").jsonPrimitive.boolean)

            settings.regenerate()
            assertNull(host.gadgetReadScope())
            assertEquals(ErrorCode.UNAVAILABLE, runCatching { host.readForGadget(false, false) }
                .exceptionOrNull().let { (it as CommandException).code })
            assertTrue(host.call(owner, "phone_status", args).getValue("active").jsonPrimitive.boolean)
        } finally {
            host.close()
            directory.deleteRecursively()
        }
    }
}
